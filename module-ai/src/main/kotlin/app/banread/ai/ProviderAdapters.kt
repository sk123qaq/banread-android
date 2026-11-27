package app.banread.ai

import app.banread.ai.internal.HttpStreamingSupport
import app.banread.ai.internal.Redactor
import app.banread.ai.internal.resolveEffectiveModel
import app.banread.model.AppFailure
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmProviderPort
import app.banread.model.LlmStreamEvent
import app.banread.model.ProviderCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

class OpenAiCompatAdapter(
    private val defaultModelId: String = "gpt-4o-mini",
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsJsonSchema = true,
        supportsVision = true,
        contextWindow = 128_000,
    ),
    private val client: OkHttpClient = HttpStreamingSupport.defaultClient(),
    private val retryDelayBaseMs: Long = 250L,
    private val maxRetries: Int = 3,
) : LlmProviderPort {

    override fun stream(endpoint: String, apiKey: String, payload: LlmPromptRequest): Flow<LlmStreamEvent> {
        val url = resolveEndpoint(endpoint)
        return HttpStreamingSupport.streamSseWithRetry(
            client = client,
            providerId = payload.providerId ?: "openai_compat",
            apiKey = apiKey,
            maxRetries = maxRetries,
            retryDelayBaseMs = retryDelayBaseMs,
            buildRequest = {
                val model = resolveEffectiveModel(defaultModelId)
                val bodyJson = buildPayloadJson(model, payload)
                Request.Builder()
                    .url(url)
                    .apply {
                        if (apiKey.isNotBlank()) {
                            header("Authorization", "Bearer $apiKey")
                        }
                    }
                    .header("Accept", "text/event-stream")
                    .post(HttpStreamingSupport.jsonBody(bodyJson.toString()))
                    .build()
            },
            onSseEvent = { _, data, fullContent, emit, markCompleted ->
                val trimmed = data.trim()
                if (trimmed == "[DONE]") {
                    markCompleted()
                    return@streamSseWithRetry
                }
                if (trimmed.isEmpty()) return@streamSseWithRetry
                val root = HttpStreamingSupport.jsonParser.parseToJsonElement(trimmed).jsonObject
                val errorObj = root["error"]
                if (errorObj != null && errorObj is JsonObject) {
                    val msg = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: errorObj.toString()
                    emit(
                        LlmStreamEvent.Failed(
                            AppFailure.ProviderAuthOrQuota(
                                providerId = payload.providerId ?: "openai_compat",
                                httpCode = null,
                                message = Redactor.redact(msg, apiKey),
                            ),
                        ),
                    )
                    return@streamSseWithRetry
                }
                val choices = root["choices"] as? JsonArray ?: return@streamSseWithRetry
                val first = choices.firstOrNull() as? JsonObject ?: return@streamSseWithRetry
                val delta = first["delta"] as? JsonObject
                if (delta != null) {
                    val reasoning = delta.stringOrNull("reasoning_content") ?: delta.stringOrNull("reasoning")
                    if (!reasoning.isNullOrEmpty()) {
                        emit(LlmStreamEvent.ThinkingDelta(reasoning))
                    }
                    val content = delta.stringOrNull("content")
                    if (!content.isNullOrEmpty()) {
                        fullContent.append(content)
                        emit(LlmStreamEvent.ContentDelta(content))
                    }
                }
                val finishReason = first.stringOrNull("finish_reason")
                if (!finishReason.isNullOrBlank()) {
                    markCompleted()
                }
            },
        )
    }

    private fun resolveEndpoint(endpoint: String): String {
        val clean = endpoint.trim().trimEnd('/')
        return when {
            clean.endsWith("/chat/completions") -> clean
            clean.endsWith("/v1") -> "$clean/chat/completions"
            else -> "$clean/v1/chat/completions"
        }
    }

    private fun buildPayloadJson(model: String, payload: LlmPromptRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        put(
            "messages",
            buildJsonArray {
                if (payload.systemPrompt.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", payload.systemPrompt)
                        },
                    )
                }
                add(
                    buildJsonObject {
                        put("role", "user")
                        val png = payload.imagePngBytes
                        if (png != null) {
                            val b64 = Base64.getEncoder().encodeToString(png)
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", payload.userPrompt)
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("type", "image_url")
                                            put(
                                                "image_url",
                                                buildJsonObject {
                                                    put("url", "data:image/png;base64,$b64")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        } else {
                            put("content", payload.userPrompt)
                        }
                    },
                )
            },
        )
        if (payload.expectJson && capabilities.supportsJsonSchema) {
            put(
                "response_format",
                buildJsonObject {
                    put("type", "json_object")
                },
            )
        }
        payload.maxOutputTokens?.let { put("max_tokens", it) }
    }
}

class GeminiAdapter(
    private val defaultModelId: String = "gemini-2.5-flash",
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsJsonSchema = true,
        supportsVision = true,
        contextWindow = 1_000_000,
    ),
    private val client: OkHttpClient = HttpStreamingSupport.defaultClient(),
    private val retryDelayBaseMs: Long = 250L,
    private val maxRetries: Int = 3,
) : LlmProviderPort {

    override fun stream(endpoint: String, apiKey: String, payload: LlmPromptRequest): Flow<LlmStreamEvent> {
        return HttpStreamingSupport.streamSseWithRetry(
            client = client,
            providerId = payload.providerId ?: "gemini",
            apiKey = apiKey,
            maxRetries = maxRetries,
            retryDelayBaseMs = retryDelayBaseMs,
            buildRequest = {
                val model = resolveEffectiveModel(defaultModelId)
                val url = resolveEndpoint(endpoint, model)
                val bodyJson = buildPayloadJson(payload)
                Request.Builder()
                    .url(url)
                    .apply {
                        if (apiKey.isNotBlank()) {
                            header("x-goog-api-key", apiKey)
                        }
                    }
                    .header("Accept", "text/event-stream")
                    .post(HttpStreamingSupport.jsonBody(bodyJson.toString()))
                    .build()
            },
            onSseEvent = { _, data, fullContent, emit, markCompleted ->
                val trimmed = data.trim()
                if (trimmed == "[DONE]") {
                    markCompleted()
                    return@streamSseWithRetry
                }
                if (trimmed.isEmpty()) return@streamSseWithRetry
                val root = HttpStreamingSupport.jsonParser.parseToJsonElement(trimmed).jsonObject
                val errorObj = root["error"] as? JsonObject
                if (errorObj != null) {
                    val msg = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: errorObj.toString()
                    emit(
                        LlmStreamEvent.Failed(
                            AppFailure.ProviderAuthOrQuota(
                                providerId = payload.providerId ?: "gemini",
                                httpCode = null,
                                message = Redactor.redact(msg, apiKey),
                            ),
                        ),
                    )
                    return@streamSseWithRetry
                }
                val candidates = root["candidates"] as? JsonArray ?: return@streamSseWithRetry
                val first = candidates.firstOrNull() as? JsonObject ?: return@streamSseWithRetry
                val content = first["content"] as? JsonObject
                val parts = content?.get("parts") as? JsonArray
                if (parts != null) {
                    for (partEl in parts) {
                        val part = partEl as? JsonObject ?: continue
                        val text = part.stringOrNull("text")
                        if (!text.isNullOrEmpty()) {
                            val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                            if (isThought) {
                                emit(LlmStreamEvent.ThinkingDelta(text))
                            } else {
                                fullContent.append(text)
                                emit(LlmStreamEvent.ContentDelta(text))
                            }
                        }
                    }
                }
            },
        )
    }

    private fun resolveEndpoint(endpoint: String, model: String): String {
        val clean = endpoint.trim().trimEnd('/')
        return when {
            ":streamGenerateContent" in clean -> {
                if ("alt=sse" in clean) clean
                else if ('?' in clean) "$clean&alt=sse" else "$clean?alt=sse"
            }
            "/models/" in clean -> "$clean:streamGenerateContent?alt=sse"
            clean.endsWith("/v1beta") || clean.endsWith("/v1") ->
                "$clean/models/$model:streamGenerateContent?alt=sse"
            else -> "$clean/v1beta/models/$model:streamGenerateContent?alt=sse"
        }
    }

    private fun buildPayloadJson(payload: LlmPromptRequest): JsonObject = buildJsonObject {
        if (payload.systemPrompt.isNotBlank()) {
            put(
                "systemInstruction",
                buildJsonObject {
                    put(
                        "parts",
                        buildJsonArray {
                            add(buildJsonObject { put("text", payload.systemPrompt) })
                        },
                    )
                },
            )
        }
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject { put("text", payload.userPrompt) })
                                val png = payload.imagePngBytes
                                if (png != null) {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "inlineData",
                                                buildJsonObject {
                                                    put("mimeType", "image/png")
                                                    put("data", Base64.getEncoder().encodeToString(png))
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )
        if (payload.expectJson || payload.maxOutputTokens != null) {
            put(
                "generationConfig",
                buildJsonObject {
                    if (payload.expectJson) {
                        put("responseMimeType", "application/json")
                    }
                    payload.maxOutputTokens?.let { put("maxOutputTokens", it) }
                },
            )
        }
    }
}

class AnthropicAdapter(
    private val defaultModelId: String = "claude-sonnet-4-20250514",
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsJsonSchema = false,
        supportsVision = true,
        contextWindow = 200_000,
    ),
    private val client: OkHttpClient = HttpStreamingSupport.defaultClient(),
    private val retryDelayBaseMs: Long = 250L,
    private val maxRetries: Int = 3,
) : LlmProviderPort {

    override fun stream(endpoint: String, apiKey: String, payload: LlmPromptRequest): Flow<LlmStreamEvent> {
        val url = resolveEndpoint(endpoint)
        return HttpStreamingSupport.streamSseWithRetry(
            client = client,
            providerId = payload.providerId ?: "anthropic",
            apiKey = apiKey,
            maxRetries = maxRetries,
            retryDelayBaseMs = retryDelayBaseMs,
            buildRequest = {
                val model = resolveEffectiveModel(defaultModelId)
                val bodyJson = buildPayloadJson(model, payload)
                Request.Builder()
                    .url(url)
                    .apply {
                        if (apiKey.isNotBlank()) {
                            header("x-api-key", apiKey)
                        }
                    }
                    .header("anthropic-version", "2023-06-01")
                    .header("Accept", "text/event-stream")
                    .post(HttpStreamingSupport.jsonBody(bodyJson.toString()))
                    .build()
            },
            onSseEvent = { eventType, data, fullContent, emit, markCompleted ->
                val trimmed = data.trim()
                if (trimmed.isEmpty()) return@streamSseWithRetry
                val root = HttpStreamingSupport.jsonParser.parseToJsonElement(trimmed).jsonObject
                val type = root.stringOrNull("type") ?: eventType.orEmpty()
                when (type) {
                    "content_block_delta" -> {
                        val delta = root["delta"] as? JsonObject ?: return@streamSseWithRetry
                        when (delta.stringOrNull("type")) {
                            "thinking_delta" -> {
                                val thinking = delta.stringOrNull("thinking")
                                if (!thinking.isNullOrEmpty()) {
                                    emit(LlmStreamEvent.ThinkingDelta(thinking))
                                }
                            }
                            "text_delta" -> {
                                val text = delta.stringOrNull("text")
                                if (!text.isNullOrEmpty()) {
                                    fullContent.append(text)
                                    emit(LlmStreamEvent.ContentDelta(text))
                                }
                            }
                        }
                    }
                    "message_stop" -> markCompleted()
                    "error" -> {
                        val err = root["error"] as? JsonObject
                        val msg = err?.stringOrNull("message") ?: root.toString()
                        emit(
                            LlmStreamEvent.Failed(
                                AppFailure.ProviderAuthOrQuota(
                                    providerId = payload.providerId ?: "anthropic",
                                    httpCode = null,
                                    message = Redactor.redact(msg, apiKey),
                                ),
                            ),
                        )
                    }
                }
            },
        )
    }

    private fun resolveEndpoint(endpoint: String): String {
        val clean = endpoint.trim().trimEnd('/')
        return when {
            clean.endsWith("/messages") -> clean
            clean.endsWith("/v1") -> "$clean/messages"
            else -> "$clean/v1/messages"
        }
    }

    private fun buildPayloadJson(model: String, payload: LlmPromptRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", payload.maxOutputTokens ?: 4096)
        put("stream", true)
        if (payload.systemPrompt.isNotBlank()) {
            put("system", payload.systemPrompt)
        }
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        val png = payload.imagePngBytes
                        if (png != null) {
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "image")
                                            put(
                                                "source",
                                                buildJsonObject {
                                                    put("type", "base64")
                                                    put("media_type", "image/png")
                                                    put("data", Base64.getEncoder().encodeToString(png))
                                                },
                                            )
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", payload.userPrompt)
                                        },
                                    )
                                },
                            )
                        } else {
                            put("content", payload.userPrompt)
                        }
                    },
                )
            },
        )
    }
}

class OllamaAdapter(
    private val defaultModelId: String = "llama3.1",
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsJsonSchema = true,
        supportsVision = true,
        contextWindow = 32_768,
    ),
    private val client: OkHttpClient = HttpStreamingSupport.defaultClient(),
    private val retryDelayBaseMs: Long = 250L,
    private val maxRetries: Int = 3,
) : LlmProviderPort {

    override fun stream(endpoint: String, apiKey: String, payload: LlmPromptRequest): Flow<LlmStreamEvent> {
        val url = resolveEndpoint(endpoint)
        return HttpStreamingSupport.streamNdjsonWithRetry(
            client = client,
            providerId = payload.providerId ?: "ollama",
            apiKey = apiKey,
            maxRetries = maxRetries,
            retryDelayBaseMs = retryDelayBaseMs,
            buildRequest = {
                val model = resolveEffectiveModel(defaultModelId)
                val bodyJson = buildPayloadJson(model, payload)
                Request.Builder()
                    .url(url)
                    .apply {
                        if (apiKey.isNotBlank()) {
                            header("Authorization", "Bearer $apiKey")
                        }
                    }
                    .post(HttpStreamingSupport.jsonBody(bodyJson.toString()))
                    .build()
            },
            onLine = { line, fullContent, emit, markCompleted ->
                val root = HttpStreamingSupport.jsonParser.parseToJsonElement(line).jsonObject
                val errorText = root.stringOrNull("error")
                if (!errorText.isNullOrBlank()) {
                    emit(
                        LlmStreamEvent.Failed(
                            AppFailure.ProviderAuthOrQuota(
                                providerId = payload.providerId ?: "ollama",
                                httpCode = null,
                                message = Redactor.redact(errorText, apiKey),
                            ),
                        ),
                    )
                    return@streamNdjsonWithRetry
                }
                val message = root["message"] as? JsonObject
                if (message != null) {
                    val thinking = message.stringOrNull("thinking")
                    if (!thinking.isNullOrEmpty()) {
                        emit(LlmStreamEvent.ThinkingDelta(thinking))
                    }
                    val content = message.stringOrNull("content")
                    if (!content.isNullOrEmpty()) {
                        fullContent.append(content)
                        emit(LlmStreamEvent.ContentDelta(content))
                    }
                }
                if (root["done"]?.jsonPrimitive?.booleanOrNull == true) {
                    emit(LlmStreamEvent.Completed(fullContent.toString()))
                    markCompleted()
                }
            },
        )
    }

    private fun resolveEndpoint(endpoint: String): String {
        val clean = endpoint.trim().trimEnd('/')
        return if (clean.endsWith("/api/chat")) clean else "$clean/api/chat"
    }

    private fun buildPayloadJson(model: String, payload: LlmPromptRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        if (payload.expectJson) {
            put("format", "json")
        }
        payload.maxOutputTokens?.let { maxTok ->
            put(
                "options",
                buildJsonObject {
                    put("num_predict", maxTok)
                },
            )
        }
        put(
            "messages",
            buildJsonArray {
                if (payload.systemPrompt.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", payload.systemPrompt)
                        },
                    )
                }
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", payload.userPrompt)
                        val png = payload.imagePngBytes
                        if (png != null) {
                            put(
                                "images",
                                buildJsonArray {
                                    add(JsonPrimitive(Base64.getEncoder().encodeToString(png)))
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}

private fun JsonObject.stringOrNull(key: String): String? {
    val el: JsonElement = this[key] ?: return null
    val prim = el as? JsonPrimitive ?: return null
    return prim.contentOrNull
}
