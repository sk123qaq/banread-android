package app.banread.ai.internal

import app.banread.model.AppFailure
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

internal data class ResolvedModelContext(val modelId: String) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<ResolvedModelContext>
}

internal suspend fun resolveEffectiveModel(fallback: String): String =
    currentCoroutineContext()[ResolvedModelContext]?.modelId?.takeIf { it.isNotBlank() } ?: fallback

internal object Redactor {
    private val bearerHeaderPattern = Regex("""(?i)(Authorization\s*:\s*Bearer\s+)[^\s,;"]+""")
    private val bearerTokenPattern = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._\-+=/]{8,}""")
    private val apiKeyHeaderPattern = Regex("""(?i)((?:x-api-key|x-goog-api-key)\s*[:=]\s*)[^\s,;"]+""")
    private val queryKeyPattern = Regex("""(?i)([?&]key=)[^&\s]+""")
    private val skPattern = Regex("""\bsk-[A-Za-z0-9_-]{8,}\b""")
    private val aizaPattern = Regex("""\bAIza[A-Za-z0-9_-]{10,}\b""")

    fun redact(text: String, vararg secrets: String): String {
        var out = text
        for (secret in secrets) {
            if (secret.isNotBlank()) {
                out = out.replace(secret, "[REDACTED]")
            }
        }
        out = bearerHeaderPattern.replace(out, "$1[REDACTED]")
        out = bearerTokenPattern.replace(out, "$1[REDACTED]")
        out = apiKeyHeaderPattern.replace(out, "$1[REDACTED]")
        out = queryKeyPattern.replace(out, "$1[REDACTED]")
        out = skPattern.replace(out, "[REDACTED]")
        out = aizaPattern.replace(out, "[REDACTED]")
        return out
    }
}

internal object HttpStreamingSupport {
    val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    fun parseRetryAfterMs(headerValue: String?): Long? {
        val raw = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { seconds ->
            return seconds.coerceIn(0L, 60L) * 1000L
        }
        return runCatching {
            val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
            Duration.between(ZonedDateTime.now(), target).toMillis().coerceIn(0L, 60_000L)
        }.getOrNull()
    }

    fun computeBackoffMs(attempt: Int, retryAfterHeader: String?, baseDelayMs: Long): Long {
        parseRetryAfterMs(retryAfterHeader)?.let { return it }
        val exp = baseDelayMs * (1L shl attempt.coerceAtMost(6))
        val jitter = if (baseDelayMs > 1L) Random.nextLong(0L, baseDelayMs / 2L + 1L) else 0L
        return (exp + jitter).coerceAtMost(30_000L)
    }

    fun isRetryableStatus(code: Int?): Boolean = code == 429 || code == 503

    private sealed interface AttemptOutcome {
        data object Done : AttemptOutcome
        data class Retry(val delayMs: Long) : AttemptOutcome
    }

    fun streamSseWithRetry(
        client: OkHttpClient,
        providerId: String,
        apiKey: String,
        maxRetries: Int,
        retryDelayBaseMs: Long,
        buildRequest: suspend () -> Request,
        onSseEvent: (
            eventType: String?,
            data: String,
            fullContent: StringBuilder,
            emit: (LlmStreamEvent) -> Unit,
            markCompleted: () -> Unit,
        ) -> Unit,
    ): Flow<LlmStreamEvent> = flow {
        val factory = EventSources.createFactory(client)
        var attempt = 0
        while (true) {
            val request = buildRequest()
            var retryDelayMs: Long? = null
            var terminalFailure: LlmStreamEvent.Failed? = null
            var completedNormally = false

            val attemptFlow = callbackFlow {
                val emittedAnyDelta = AtomicBoolean(false)
                val completed = AtomicBoolean(false)
                val cancelled = AtomicBoolean(false)
                val fullContent = StringBuilder()
                val sourceRef = AtomicReference<EventSource?>(null)

                val listener = object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (cancelled.get() || completed.get()) return
                        try {
                            onSseEvent(
                                type,
                                data,
                                fullContent,
                                { ev ->
                                    if (ev is LlmStreamEvent.ThinkingDelta || ev is LlmStreamEvent.ContentDelta) {
                                        emittedAnyDelta.set(true)
                                    }
                                    if (ev is LlmStreamEvent.Completed) {
                                        if (completed.compareAndSet(false, true)) {
                                            trySend(ev)
                                        }
                                    } else if (ev is LlmStreamEvent.Failed) {
                                        if (completed.compareAndSet(false, true)) {
                                            trySend(redactFailedEvent(ev, apiKey))
                                        }
                                    } else {
                                        trySend(ev)
                                    }
                                },
                                {
                                    if (completed.compareAndSet(false, true)) {
                                        trySend(LlmStreamEvent.Completed(fullContent.toString()))
                                    }
                                    eventSource.cancel()
                                    close()
                                },
                            )
                            if (completed.get()) {
                                eventSource.cancel()
                                close()
                            }
                        } catch (ex: Exception) {
                            if (completed.compareAndSet(false, true)) {
                                trySend(
                                    LlmStreamEvent.Failed(
                                        AppFailure.ProviderAuthOrQuota(
                                            providerId = providerId,
                                            httpCode = null,
                                            message = Redactor.redact(ex.message ?: "SSE parse failure", apiKey),
                                        ),
                                    ),
                                )
                            }
                            eventSource.cancel()
                            close()
                        }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (!cancelled.get() && completed.compareAndSet(false, true)) {
                            trySend(LlmStreamEvent.Completed(fullContent.toString()))
                        }
                        close()
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        if (cancelled.get() || completed.get()) {
                            close()
                            return
                        }
                        val code = response?.code
                        val retryAfter = response?.header("Retry-After")
                        val rawBody = runCatching { response?.body?.string().orEmpty() }.getOrDefault("")
                        response?.close()

                        if (!emittedAnyDelta.get() && attempt < maxRetries &&
                            (isRetryableStatus(code) || (code == null && t is IOException))
                        ) {
                            retryDelayMs = computeBackoffMs(attempt, retryAfter, retryDelayBaseMs)
                            close()
                            return
                        }

                        val detail = buildString {
                            if (code != null) append("HTTP $code")
                            val msg = rawBody.ifBlank { t?.message ?: "Request failed" }
                            if (msg.isNotBlank()) {
                                if (isNotEmpty()) append(": ")
                                append(msg.take(300))
                            }
                        }
                        if (completed.compareAndSet(false, true)) {
                            trySend(
                                LlmStreamEvent.Failed(
                                    AppFailure.ProviderAuthOrQuota(
                                        providerId = providerId,
                                        httpCode = code,
                                        message = Redactor.redact(detail, apiKey),
                                    ),
                                ),
                            )
                        }
                        close()
                    }
                }

                val es = factory.newEventSource(request, listener)
                sourceRef.set(es)
                awaitClose {
                    cancelled.set(true)
                    sourceRef.get()?.cancel()
                }
            }

            attemptFlow.collect { event ->
                when (event) {
                    is LlmStreamEvent.Completed -> {
                        completedNormally = true
                        emit(event)
                    }
                    is LlmStreamEvent.Failed -> {
                        terminalFailure = event
                    }
                    else -> emit(event)
                }
            }

            val delayMs = retryDelayMs
            if (delayMs != null) {
                attempt++
                delay(delayMs)
                continue
            }
            if (terminalFailure != null) {
                emit(terminalFailure!!)
            } else if (!completedNormally && currentCoroutineContext().isActive) {
                emit(LlmStreamEvent.Completed(""))
            }
            break
        }
    }

    fun streamNdjsonWithRetry(
        client: OkHttpClient,
        providerId: String,
        apiKey: String,
        maxRetries: Int,
        retryDelayBaseMs: Long,
        buildRequest: suspend () -> Request,
        onLine: (
            line: String,
            fullContent: StringBuilder,
            emit: (LlmStreamEvent) -> Unit,
            markCompleted: () -> Unit,
        ) -> Unit,
    ): Flow<LlmStreamEvent> = flow {
        var attempt = 0
        while (true) {
            val request = buildRequest()
            val call = client.newCall(request)
            var emittedAny = false
            var retryDelayMs: Long? = null
            var failedEvent: LlmStreamEvent.Failed? = null
            var completed = false
            val fullContent = StringBuilder()

            try {
                coroutineScope {
                    val response = call.execute()
                    response.use { resp ->
                        val code = resp.code
                        if (!resp.isSuccessful) {
                            val retryAfter = resp.header("Retry-After")
                            val rawBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                            if (attempt < maxRetries && isRetryableStatus(code)) {
                                retryDelayMs = computeBackoffMs(attempt, retryAfter, retryDelayBaseMs)
                                return@coroutineScope
                            }
                            val msg = rawBody.ifBlank { "HTTP $code" }.take(300)
                            failedEvent = LlmStreamEvent.Failed(
                                AppFailure.ProviderAuthOrQuota(
                                    providerId = providerId,
                                    httpCode = code,
                                    message = Redactor.redact("HTTP $code: $msg", apiKey),
                                ),
                            )
                            return@coroutineScope
                        }

                        val source = resp.body?.source()
                        if (source == null) {
                            emit(LlmStreamEvent.Completed(""))
                            completed = true
                            return@coroutineScope
                        }
                        while (isActive && !completed) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val pending = mutableListOf<LlmStreamEvent>()
                            onLine(
                                line,
                                fullContent,
                                { ev -> pending += if (ev is LlmStreamEvent.Failed) redactFailedEvent(ev, apiKey) else ev },
                                { completed = true },
                            )
                            for (ev in pending) {
                                if (ev is LlmStreamEvent.ThinkingDelta || ev is LlmStreamEvent.ContentDelta) {
                                    emittedAny = true
                                }
                                if (ev is LlmStreamEvent.Completed || ev is LlmStreamEvent.Failed) {
                                    completed = true
                                }
                                emit(ev)
                            }
                        }
                        if (!completed && isActive) {
                            emit(LlmStreamEvent.Completed(fullContent.toString()))
                            completed = true
                        }
                    }
                }
            } catch (ce: CancellationException) {
                call.cancel()
                throw ce
            } catch (ex: IOException) {
                if (!emittedAny && attempt < maxRetries) {
                    retryDelayMs = computeBackoffMs(attempt, null, retryDelayBaseMs)
                } else {
                    failedEvent = LlmStreamEvent.Failed(
                        AppFailure.ProviderAuthOrQuota(
                            providerId = providerId,
                            httpCode = null,
                            message = Redactor.redact(ex.message ?: "Network failure", apiKey),
                        ),
                    )
                }
            } catch (ex: Exception) {
                failedEvent = LlmStreamEvent.Failed(
                    AppFailure.ProviderAuthOrQuota(
                        providerId = providerId,
                        httpCode = null,
                        message = Redactor.redact(ex.message ?: "Stream error", apiKey),
                    ),
                )
            }

            val delayMs = retryDelayMs
            if (delayMs != null) {
                attempt++
                delay(delayMs)
                continue
            }
            if (failedEvent != null) {
                emit(failedEvent!!)
            }
            break
        }
    }.flowOn(Dispatchers.IO)

    fun jsonBody(jsonText: String) = jsonText.toRequestBody(jsonMediaType)

    private fun redactFailedEvent(event: LlmStreamEvent.Failed, apiKey: String): LlmStreamEvent.Failed {
        val failure = event.failure
        if (failure !is AppFailure.ProviderAuthOrQuota) return event
        val redacted = Redactor.redact(failure.message, apiKey)
        return if (redacted == failure.message) {
            event
        } else {
            LlmStreamEvent.Failed(failure.copy(message = redacted))
        }
    }
}
