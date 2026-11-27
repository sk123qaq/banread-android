package app.banread.ai

import app.banread.model.AppFailure
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmStreamEvent
import app.banread.model.ProviderCapabilities
import app.banread.model.ProviderConfig
import app.banread.model.ProviderKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AiRuntimeContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: LocalHttpTestServer
    private var port: Int = 0
    private val capturedHeaders = CopyOnWriteArrayList<Map<String, String>>()
    private val capturedPaths = CopyOnWriteArrayList<String>()
    private val capturedBodies = CopyOnWriteArrayList<String>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = LocalHttpTestServer()
        port = server.port
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun baseUrl(): String = "http://127.0.0.1:$port"

    @Test
    fun `OpenAiCompatAdapter splits DeepSeek reasoning_content from content and sends vision base64`() = runTest {
        server.route("/v1/chat/completions") { req ->
            capturedBodies += req.body
            capturedHeaders += req.headers
            val sse = buildString {
                append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先推理\"}}]}\n\n")
                append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"步骤\"}}]}\n\n")
                append("data: {\"choices\":[{\"delta\":{\"content\":\"结论：\"}}]}\n\n")
                append("data: {\"choices\":[{\"delta\":{\"content\":\"成立\"},\"finish_reason\":\"stop\"}]}\n\n")
                append("data: [DONE]\n\n")
            }.toByteArray(Charsets.UTF_8)
            TestHttpResponse(
                code = 200,
                headers = mapOf("Content-Type" to "text/event-stream; charset=utf-8"),
                body = sse,
            )
        }
        server.start()

        val vault = InMemorySecretVaultAdapter()
        val runtime = AiRuntimeFactory.createInMemory(
            secretVault = vault,
            configDir = tempFolder.newFolder("ai_cfg_openai"),
        )
        val secretKey = "sk-deepseek-secret-99999"
        runtime.saveProviderConfig(
            ProviderConfig(
                id = "deepseek",
                displayName = "DeepSeek Reasoner",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = baseUrl(),
                modelId = "deepseek-reasoner",
                capabilities = ProviderCapabilities(supportsJsonSchema = true, supportsVision = true, contextWindow = 64_000),
            ),
            rawApiKey = secretKey,
        )

        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val events = runtime.streamCompletion(
            LlmPromptRequest(
                providerId = "deepseek",
                systemPrompt = "你是推理助手",
                userPrompt = "分析图示",
                imagePngBytes = pngBytes,
                expectJson = true,
            ),
        ).toList()

        val thinking = events.filterIsInstance<LlmStreamEvent.ThinkingDelta>().joinToString("") { it.delta }
        val content = events.filterIsInstance<LlmStreamEvent.ContentDelta>().joinToString("") { it.delta }
        val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()

        assertEquals("先推理步骤", thinking)
        assertEquals("结论：成立", content)
        assertEquals("结论：成立", completed.fullText)

        assertEquals("Bearer $secretKey", capturedHeaders.single()["authorization"])
        val reqObj = json.parseToJsonElement(capturedBodies.single()).jsonObject
        assertEquals("deepseek-reasoner", reqObj["model"]?.jsonPrimitive?.content)
        assertEquals("json_object", reqObj["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        val userContent = reqObj["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        val expectedB64 = Base64.getEncoder().encodeToString(pngBytes)
        assertTrue(userContent[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.contains(expectedB64))
    }

    @Test
    fun `GeminiAdapter calls streamGenerateContent alt=sse with x-goog-api-key and inlineData`() = runTest {
        server.route("/v1beta/models/gemini-2.5-flash:streamGenerateContent") { req ->
            capturedPaths += req.fullPath
            capturedHeaders += req.headers
            capturedBodies += req.body
            val sse = buildString {
                append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"ok\\\":\"}]}}]}\n\n")
                append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"true}\"}]}}]}\n\n")
            }.toByteArray(Charsets.UTF_8)
            TestHttpResponse(
                code = 200,
                headers = mapOf("Content-Type" to "text/event-stream; charset=utf-8"),
                body = sse,
            )
        }
        server.start()

        val runtime = AiRuntimeFactory.createInMemory(configDir = tempFolder.newFolder("ai_cfg_gemini"))
        val geminiKey = "AIzaSyTestSecretKey123456"
        runtime.saveProviderConfig(
            ProviderConfig(
                id = "gemini-main",
                displayName = "Gemini 2.5 Flash",
                kind = ProviderKind.GEMINI,
                endpoint = baseUrl(),
                modelId = "gemini-2.5-flash",
                capabilities = ProviderCapabilities(supportsJsonSchema = true, supportsVision = true, contextWindow = 1_000_000),
            ),
            rawApiKey = geminiKey,
        )

        val pngBytes = byteArrayOf(1, 2, 3, 4)
        val events = runtime.streamCompletion(
            LlmPromptRequest(
                systemPrompt = "系统指令",
                userPrompt = "解析图",
                imagePngBytes = pngBytes,
                expectJson = true,
            ),
        ).toList()

        assertEquals("{\"ok\":true}", events.filterIsInstance<LlmStreamEvent.Completed>().single().fullText)
        assertEquals("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse", capturedPaths.single())
        assertEquals(geminiKey, capturedHeaders.single()["x-goog-api-key"])
        val reqObj = json.parseToJsonElement(capturedBodies.single()).jsonObject
        assertEquals("application/json", reqObj["generationConfig"]?.jsonObject?.get("responseMimeType")?.jsonPrimitive?.content)
        val parts = reqObj["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("image/png", parts[1].jsonObject["inlineData"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.content)
    }

    @Test
    fun `AnthropicAdapter parses thinking_delta and text_delta with required headers`() = runTest {
        server.route("/v1/messages") { req ->
            capturedHeaders += req.headers
            capturedBodies += req.body
            val sse = buildString {
                append("event: content_block_delta\n")
                append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"思考中\"}}\n\n")
                append("event: content_block_delta\n")
                append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"答案\"}}\n\n")
                append("event: message_stop\n")
                append("data: {\"type\":\"message_stop\"}\n\n")
            }.toByteArray(Charsets.UTF_8)
            TestHttpResponse(
                code = 200,
                headers = mapOf("Content-Type" to "text/event-stream; charset=utf-8"),
                body = sse,
            )
        }
        server.start()

        val runtime = AiRuntimeFactory.createInMemory(configDir = tempFolder.newFolder("ai_cfg_anthropic"))
        runtime.saveProviderConfig(
            ProviderConfig(
                id = "claude",
                displayName = "Claude Sonnet",
                kind = ProviderKind.ANTHROPIC,
                endpoint = baseUrl(),
                modelId = "claude-sonnet-4-20250514",
                capabilities = ProviderCapabilities(supportsJsonSchema = false, supportsVision = true, contextWindow = 200_000),
            ),
            rawApiKey = "sk-ant-test-key-abcdef",
        )

        val events = runtime.streamCompletion(
            LlmPromptRequest(
                systemPrompt = "sys",
                userPrompt = "hi",
                imagePngBytes = byteArrayOf(9, 8, 7),
            ),
        ).toList()

        assertEquals("思考中", events.filterIsInstance<LlmStreamEvent.ThinkingDelta>().single().delta)
        assertEquals("答案", events.filterIsInstance<LlmStreamEvent.Completed>().single().fullText)
        assertEquals("2023-06-01", capturedHeaders.single()["anthropic-version"])
        assertEquals("sk-ant-test-key-abcdef", capturedHeaders.single()["x-api-key"])
    }

    @Test
    fun `OllamaAdapter streams NDJSON lines and optional vision images`() = runTest {
        server.route("/api/chat") { req ->
            capturedBodies += req.body
            val ndjson = buildString {
                append("{\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"done\":false}\n")
                append("{\"message\":{\"role\":\"assistant\",\"content\":\"世界\"},\"done\":true}\n")
            }.toByteArray(Charsets.UTF_8)
            TestHttpResponse(
                code = 200,
                headers = mapOf("Content-Type" to "application/x-ndjson; charset=utf-8"),
                body = ndjson,
            )
        }
        server.start()

        val runtime = AiRuntimeFactory.createInMemory(configDir = tempFolder.newFolder("ai_cfg_ollama"))
        runtime.saveProviderConfig(
            ProviderConfig(
                id = "ollama-local",
                displayName = "Ollama",
                kind = ProviderKind.OLLAMA,
                endpoint = baseUrl(),
                modelId = "qwen2.5:14b",
                capabilities = ProviderCapabilities(supportsJsonSchema = true, supportsVision = true, contextWindow = 32_000),
            ),
            rawApiKey = "",
        )

        val events = runtime.streamCompletion(
            LlmPromptRequest(
                systemPrompt = "",
                userPrompt = "hello",
                imagePngBytes = byteArrayOf(5, 6),
                expectJson = true,
            ),
        ).toList()

        assertEquals(listOf("你好", "世界"), events.filterIsInstance<LlmStreamEvent.ContentDelta>().map { it.delta })
        assertEquals("你好世界", events.filterIsInstance<LlmStreamEvent.Completed>().single().fullText)
        val reqObj = json.parseToJsonElement(capturedBodies.single()).jsonObject
        assertEquals("json", reqObj["format"]?.jsonPrimitive?.content)
        assertNotNull(reqObj["messages"]!!.jsonArray[0].jsonObject["images"])
    }

    @Test
    fun `handles 429 Retry-After backoff, redacts API keys in errors, and never leaks secrets in profiles`() = runTest {
        val attempts = AtomicInteger(0)
        val secretKey = "sk-super-secret-leak-check-12345"
        server.route("/v1/chat/completions") { _ ->
            val n = attempts.incrementAndGet()
            if (n == 1) {
                TestHttpResponse(
                    code = 429,
                    headers = mapOf("Retry-After" to "0"),
                    body = "Rate limited for Bearer $secretKey".toByteArray(Charsets.UTF_8),
                )
            } else {
                val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"recovered\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    .toByteArray(Charsets.UTF_8)
                TestHttpResponse(
                    code = 200,
                    headers = mapOf("Content-Type" to "text/event-stream"),
                    body = sse,
                )
            }
        }
        server.route("/err/v1/chat/completions") { _ ->
            TestHttpResponse(
                code = 401,
                headers = emptyMap(),
                body = "Unauthorized: Authorization: Bearer $secretKey x-api-key: $secretKey".toByteArray(Charsets.UTF_8),
            )
        }
        server.start()

        val cfgDir = tempFolder.newFolder("ai_cfg_sec")
        val adapterFastRetry = OpenAiCompatAdapter(retryDelayBaseMs = 10L, maxRetries = 2)
        val runtime = AiRuntimeFactory.createInMemory(
            secretVault = InMemorySecretVaultAdapter(),
            configDir = cfgDir,
            providerAdapters = AiRuntimeFactory.defaultAdapters() + (ProviderKind.OPENAI_COMPAT to adapterFastRetry),
        )

        runtime.saveProviderConfig(
            ProviderConfig(
                id = "retry-ok",
                displayName = "Retry OK",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = baseUrl(),
                modelId = "gpt-4o-mini",
                capabilities = ProviderCapabilities(true, false, 32_000),
            ),
            rawApiKey = secretKey,
        )
        runtime.saveProviderConfig(
            ProviderConfig(
                id = "auth-fail",
                displayName = "Auth Fail",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = "${baseUrl()}/err",
                modelId = "gpt-4o-mini",
                capabilities = ProviderCapabilities(true, false, 32_000),
            ),
            rawApiKey = secretKey,
        )

        val profiles = runtime.listProviderProfiles()
        assertEquals(2, profiles.size)
        assertTrue(profiles.all { it.hasApiKey })
        assertFalse(profiles.toString().contains(secretKey))
        val metaJson = cfgDir.resolve("providers_meta.json").readText()
        assertFalse(metaJson.contains(secretKey))

        val okEvents = runtime.streamCompletion(
            LlmPromptRequest(providerId = "retry-ok", systemPrompt = "s", userPrompt = "u"),
        ).toList()
        assertEquals(2, attempts.get())
        assertEquals("recovered", okEvents.filterIsInstance<LlmStreamEvent.Completed>().single().fullText)

        val failEvents = runtime.streamCompletion(
            LlmPromptRequest(providerId = "auth-fail", systemPrompt = "s", userPrompt = "u"),
        ).toList()
        val failed = failEvents.filterIsInstance<LlmStreamEvent.Failed>().single().failure as AppFailure.ProviderAuthOrQuota
        assertEquals(401, failed.httpCode)
        assertFalse("Secret leaked in error message: ${failed.message}", failed.message.contains(secretKey))
        assertTrue("Expected readable auth hint: ${failed.message}", failed.message.contains("鉴权"))
        assertTrue("Expected API Key hint: ${failed.message}", failed.message.contains("API Key"))
    }

    @Test
    fun `enforces per-provider Semaphore concurrency cap of 2 and cancels cleanly`() = runTest {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        server.route("/v1/chat/completions") { _ ->
            val cur = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { prev -> maxOf(prev, cur) }
            try {
                Thread.sleep(120)
                val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    .toByteArray(Charsets.UTF_8)
                TestHttpResponse(
                    code = 200,
                    headers = mapOf("Content-Type" to "text/event-stream"),
                    body = sse,
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }
        server.start()

        val runtime = AiRuntimeFactory.createInMemory(configDir = tempFolder.newFolder("ai_cfg_conc"))
        runtime.saveProviderConfig(
            ProviderConfig(
                id = "capped",
                displayName = "Capped",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = baseUrl(),
                modelId = "gpt-4o-mini",
                capabilities = ProviderCapabilities(true, false, 32_000),
            ),
            rawApiKey = "sk-cap",
        )

        val results = (1..4).map {
            async {
                runtime.streamCompletion(LlmPromptRequest(providerId = "capped", systemPrompt = "s", userPrompt = "u-$it"))
                    .toList()
                    .filterIsInstance<LlmStreamEvent.Completed>()
                    .single()
                    .fullText
            }
        }.awaitAll()

        assertEquals(listOf("ok", "ok", "ok", "ok"), results)
        assertTrue("Expected maxInFlight <= 2, got ${maxInFlight.get()}", maxInFlight.get() <= 2)

        val job = launch {
            runtime.streamCompletion(LlmPromptRequest(providerId = "capped", systemPrompt = "s", userPrompt = "cancel")).collect {}
        }
        job.cancelAndJoin()
    }

    @Test
    fun `redacts API keys inside HTTP 200 SSE inline JSON error payloads`() = runTest {
        val inlineSecret = "sk-inline-leak-99999"
        server.route("/v1/chat/completions") { _ ->
            val sse = "data: {\"error\":{\"message\":\"Incorrect API key provided: $inlineSecret\"}}\n\n"
                .toByteArray(Charsets.UTF_8)
            TestHttpResponse(
                code = 200,
                headers = mapOf("Content-Type" to "text/event-stream; charset=utf-8"),
                body = sse,
            )
        }
        server.start()

        val runtime = AiRuntimeFactory.createInMemory(configDir = tempFolder.newFolder("ai_cfg_inline_err"))
        runtime.saveProviderConfig(
            ProviderConfig(
                id = "inline-err",
                displayName = "Inline Err",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = baseUrl(),
                modelId = "gpt-4o-mini",
                capabilities = ProviderCapabilities(true, false, 32_000),
            ),
            rawApiKey = inlineSecret,
        )

        val events = runtime.streamCompletion(
            LlmPromptRequest(providerId = "inline-err", systemPrompt = "s", userPrompt = "u"),
        ).toList()
        val failed = events.filterIsInstance<LlmStreamEvent.Failed>().single().failure as AppFailure.ProviderAuthOrQuota
        assertFalse("Inline secret leaked in error: ${failed.message}", failed.message.contains(inlineSecret))
        assertTrue(failed.message.contains("[REDACTED]"))
    }

    private data class TestHttpRequest(
        val method: String,
        val fullPath: String,
        val pathOnly: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class TestHttpResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class LocalHttpTestServer : Closeable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort
        private val routes = ConcurrentHashMap<String, (TestHttpRequest) -> TestHttpResponse>()
        private val executor = Executors.newCachedThreadPool()
        private val running = AtomicBoolean(false)

        fun route(pathPrefix: String, handler: (TestHttpRequest) -> TestHttpResponse) {
            routes[pathPrefix] = handler
        }

        fun start() {
            if (!running.compareAndSet(false, true)) return
            executor.submit {
                while (running.get() && !serverSocket.isClosed) {
                    val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    executor.submit { handleClient(socket) }
                }
            }
        }

        private fun handleClient(socket: Socket) {
            socket.use { sock ->
                val input = BufferedInputStream(sock.getInputStream())
                val headerBytes = ByteArrayOutputStream()
                var prev3 = 0
                var prev2 = 0
                var prev1 = 0
                while (true) {
                    val b = input.read()
                    if (b == -1) return
                    headerBytes.write(b)
                    if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                        break
                    }
                    prev3 = prev2
                    prev2 = prev1
                    prev1 = b
                }
                val headerText = headerBytes.toString(Charsets.ISO_8859_1.name())
                val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
                if (lines.isEmpty()) return
                val reqParts = lines[0].split(" ")
                val method = reqParts.getOrElse(0) { "GET" }
                val fullPath = reqParts.getOrElse(1) { "/" }
                val pathOnly = fullPath.substringBefore('?')

                val headers = linkedMapOf<String, String>()
                for (i in 1 until lines.size) {
                    val idx = lines[i].indexOf(':')
                    if (idx > 0) {
                        val k = lines[i].substring(0, idx).trim().lowercase()
                        val v = lines[i].substring(idx + 1).trim()
                        headers[k] = v
                    }
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                val req = TestHttpRequest(
                    method = method,
                    fullPath = fullPath,
                    pathOnly = pathOnly,
                    headers = headers,
                    body = String(bodyBytes, 0, readTotal, Charsets.UTF_8),
                )
                val handler = routes[pathOnly]
                    ?: routes.entries.firstOrNull { pathOnly.startsWith(it.key) }?.value
                val resp = handler?.invoke(req) ?: TestHttpResponse(404, emptyMap(), "Not found".toByteArray())

                val out = sock.getOutputStream()
                val statusText = when (resp.code) {
                    200 -> "OK"
                    401 -> "Unauthorized"
                    429 -> "Too Many Requests"
                    404 -> "Not Found"
                    else -> "Status"
                }
                val respHeader = buildString {
                    append("HTTP/1.1 ${resp.code} $statusText\r\n")
                    append("Content-Length: ${resp.body.size}\r\n")
                    append("Connection: close\r\n")
                    for ((k, v) in resp.headers) {
                        append("$k: $v\r\n")
                    }
                    append("\r\n")
                }
                out.write(respHeader.toByteArray(Charsets.ISO_8859_1))
                out.write(resp.body)
                out.flush()
            }
        }

        override fun close() {
            running.set(false)
            runCatching { serverSocket.close() }
            executor.shutdownNow()
        }
    }
}
