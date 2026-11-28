package app.banread.ai

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.DiscoveredModel
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmStreamEvent
import app.banread.model.ProviderCapabilities
import app.banread.model.ProviderConfig
import app.banread.model.ProviderKind
import app.banread.model.SecretVaultPort
import app.banread.model.ThinkingLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ByokEnhancementsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: TestHttpServer
    private var port: Int = 0
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = TestHttpServer()
        port = server.port
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun baseUrl(): String = "http://127.0.0.1:$port"

    @Test
    fun `listAvailableModels parses OpenAI-compat, Gemini, Anthropic, and Ollama and maps auth failures`() = runTest {
        val capturedHeaders = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val capturedPaths = CopyOnWriteArrayList<String>()

        server.route("/openai/v1/models") { req ->
            capturedPaths += req.fullPath
            capturedHeaders += req.pathOnly to req.headers
            TestResp(
                200,
                """{"data":[{"id":"gpt-4.1","owned_by":"openai"},{"id":"deepseek-reasoner","owned_by":"deepseek"}]}""",
            )
        }
        server.route("/v1beta/models") { req ->
            capturedPaths += req.fullPath
            capturedHeaders += req.pathOnly to req.headers
            TestResp(
                200,
                """{"models":[{"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","supportedGenerationMethods":["generateContent","countTokens"]},{"name":"models/text-embedding-004","displayName":"Embedding","supportedGenerationMethods":["embedContent"]}]}""",
            )
        }
        server.route("/anthropic/v1/models") { req ->
            capturedPaths += req.fullPath
            capturedHeaders += req.pathOnly to req.headers
            TestResp(
                200,
                """{"data":[{"id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"}]}""",
            )
        }
        server.route("/api/tags") { req ->
            capturedPaths += req.fullPath
            capturedHeaders += req.pathOnly to req.headers
            TestResp(
                200,
                """{"models":[{"name":"qwen3:14b"},{"name":"deepseek-r1:14b"}]}""",
            )
        }
        server.route("/auth-err/v1/models") { _ ->
            TestResp(401, """{"error":{"message":"invalid key sk-secret-999999"}}""")
        }
        server.route("/quota-err/v1/models") { _ ->
            TestResp(429, """{"error":{"message":"rate limit"}}""")
        }
        server.route("/forbidden/v1/models") { _ ->
            TestResp(403, """{"error":{"message":"forbidden"}}""")
        }
        server.start()

        val runtime = AiRuntimeFactory.createInMemory(configDir = tempFolder.newFolder("ai_disc"))

        val openaiModels = runtime.listAvailableModels(
            kind = ProviderKind.OPENAI_COMPAT,
            endpoint = "${baseUrl()}/openai/v1/",
            rawApiKey = "sk-openai-test-1234",
        ).getOrThrow()
        assertEquals(listOf("gpt-4.1", "deepseek-reasoner"), openaiModels.map { it.id })
        assertEquals("openai", openaiModels[0].ownedBy)

        val geminiModels = runtime.listAvailableModels(
            kind = ProviderKind.GEMINI,
            endpoint = "${baseUrl()}/v1beta",
            rawApiKey = "AIzaTestKey1234567",
        ).getOrThrow()
        assertEquals(listOf("gemini-2.5-flash"), geminiModels.map { it.id })
        assertEquals("Gemini 2.5 Flash", geminiModels.single().displayName)
        assertFalse(capturedPaths.any { "key=" in it })
        val geminiHeaders = capturedHeaders.first { it.first == "/v1beta/models" }.second
        assertEquals("AIzaTestKey1234567", geminiHeaders["x-goog-api-key"])

        val anthropicModels = runtime.listAvailableModels(
            kind = ProviderKind.ANTHROPIC,
            endpoint = "${baseUrl()}/anthropic",
            rawApiKey = "sk-ant-test-key-8888",
        ).getOrThrow()
        assertEquals(listOf("claude-sonnet-4-5"), anthropicModels.map { it.id })
        assertEquals("Claude Sonnet 4.5", anthropicModels.single().displayName)
        val antHeaders = capturedHeaders.first { it.first == "/anthropic/v1/models" }.second
        assertEquals("sk-ant-test-key-8888", antHeaders["x-api-key"])
        assertEquals("2023-06-01", antHeaders["anthropic-version"])

        val ollamaModels = runtime.listAvailableModels(
            kind = ProviderKind.OLLAMA,
            endpoint = baseUrl(),
            rawApiKey = null,
        ).getOrThrow()
        assertEquals(listOf("qwen3:14b", "deepseek-r1:14b"), ollamaModels.map { it.id })

        runtime.saveProviderConfig(
            ProviderConfig(
                id = "saved-openai",
                displayName = "Saved OpenAI",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = "${baseUrl()}/openai/v1",
                modelId = "gpt-4.1",
                capabilities = ProviderCapabilities(true, true, 128_000),
            ),
            rawApiKey = "sk-saved-key-abcd",
        )
        val connStatus = runtime.testProviderConnection("saved-openai").getOrThrow()
        assertTrue(connStatus.ok)
        assertEquals(2, connStatus.modelCount)

        for ((path, expectedCode) in listOf("/auth-err" to 401, "/forbidden" to 403, "/quota-err" to 429)) {
            val res = runtime.listAvailableModels(
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = "${baseUrl()}$path",
                rawApiKey = "sk-secret-999999",
            )
            assertTrue(res.isFailure)
            val ex = res.exceptionOrNull() as AppFailureException
            val failure = ex.failure as AppFailure.ProviderAuthOrQuota
            assertEquals(expectedCode, failure.httpCode)
            assertFalse(failure.message.contains("sk-secret-999999"))
        }

        server.close()
        val netRes = runtime.listAvailableModels(
            kind = ProviderKind.OPENAI_COMPAT,
            endpoint = "http://127.0.0.1:$port",
            rawApiKey = "sk-net-fail-1234",
        )
        assertTrue(netRes.isFailure)
        val netFailure = (netRes.exceptionOrNull() as AppFailureException).failure as AppFailure.ProviderAuthOrQuota
        assertNull(netFailure.httpCode)
    }

    @Test
    fun `ThinkingLevel maps cleanly across all 4 protocols and isolates ThinkingDelta vs ContentDelta`() = runTest {
        val openaiBodies = CopyOnWriteArrayList<String>()
        val geminiBodies = CopyOnWriteArrayList<String>()
        val anthropicBodies = CopyOnWriteArrayList<String>()
        val ollamaBodies = CopyOnWriteArrayList<String>()

        server.route("/v1/chat/completions") { req ->
            openaiBodies += req.body
            TestResp(
                200,
                "data: {\"choices\":[{\"delta\":{\"reasoning\":\"思路\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"回答\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
                contentType = "text/event-stream; charset=utf-8",
            )
        }
        server.route("/v1beta/models/gemini-2.5-flash:streamGenerateContent") { req ->
            geminiBodies += req.body
            TestResp(
                200,
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"推理\"},{\"text\":\"正文\"}]}}]}\n\n",
                contentType = "text/event-stream; charset=utf-8",
            )
        }
        server.route("/v1/messages") { req ->
            anthropicBodies += req.body
            TestResp(
                200,
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"深思\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"结论\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                contentType = "text/event-stream; charset=utf-8",
            )
        }
        server.route("/api/chat") { req ->
            ollamaBodies += req.body
            TestResp(
                200,
                "{\"message\":{\"role\":\"assistant\",\"thinking\":\"本地思考\",\"content\":\"本地答案\"},\"done\":true}\n",
                contentType = "application/x-ndjson; charset=utf-8",
            )
        }
        server.start()

        val openai = OpenAiCompatAdapter(retryDelayBaseMs = 1L, maxRetries = 0)
        val gemini = GeminiAdapter(retryDelayBaseMs = 1L, maxRetries = 0)
        val anthropic = AnthropicAdapter(retryDelayBaseMs = 1L, maxRetries = 0)
        val ollama = OllamaAdapter(retryDelayBaseMs = 1L, maxRetries = 0)

        for (level in ThinkingLevel.entries) {
            val ev = openai.stream(baseUrl(), "sk-test", LlmPromptRequest(systemPrompt = "s", userPrompt = "u", thinkingLevel = level)).toList()
            assertEquals("思路", ev.filterIsInstance<LlmStreamEvent.ThinkingDelta>().single().delta)
            assertEquals("回答", ev.filterIsInstance<LlmStreamEvent.ContentDelta>().single().delta)
        }
        val oaObjs = openaiBodies.map { json.parseToJsonElement(it).jsonObject }
        assertNull(oaObjs[0]["reasoning_effort"])
        assertEquals("low", oaObjs[1]["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("medium", oaObjs[2]["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("high", oaObjs[3]["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("high", oaObjs[4]["reasoning_effort"]?.jsonPrimitive?.content)

        for (level in ThinkingLevel.entries) {
            val ev = gemini.stream(baseUrl(), "AIza-test", LlmPromptRequest(systemPrompt = "s", userPrompt = "u", thinkingLevel = level)).toList()
            assertEquals("推理", ev.filterIsInstance<LlmStreamEvent.ThinkingDelta>().single().delta)
            assertEquals("正文", ev.filterIsInstance<LlmStreamEvent.ContentDelta>().single().delta)
        }
        val gmObjs = geminiBodies.map { json.parseToJsonElement(it).jsonObject }
        assertNull(gmObjs[0]["generationConfig"]?.jsonObject?.get("thinkingConfig"))
        val expectedBudgets = listOf(1024, 4096, 10240, 24576)
        expectedBudgets.forEachIndexed { idx, budget ->
            val tc = gmObjs[idx + 1]["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
            assertEquals(true, tc["includeThoughts"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(budget, tc["thinkingBudget"]?.jsonPrimitive?.intOrNull)
        }

        for (level in ThinkingLevel.entries) {
            val ev = anthropic.stream(baseUrl(), "sk-ant-test", LlmPromptRequest(systemPrompt = "s", userPrompt = "u", maxOutputTokens = 512, thinkingLevel = level)).toList()
            assertEquals("深思", ev.filterIsInstance<LlmStreamEvent.ThinkingDelta>().single().delta)
            assertEquals("结论", ev.filterIsInstance<LlmStreamEvent.ContentDelta>().single().delta)
        }
        val anObjs = anthropicBodies.map { json.parseToJsonElement(it).jsonObject }
        assertNull(anObjs[0]["thinking"])
        assertEquals(512, anObjs[0]["max_tokens"]?.jsonPrimitive?.intOrNull)
        expectedBudgets.forEachIndexed { idx, budget ->
            val thinking = anObjs[idx + 1]["thinking"]!!.jsonObject
            assertEquals("enabled", thinking["type"]?.jsonPrimitive?.content)
            assertEquals(budget, thinking["budget_tokens"]?.jsonPrimitive?.intOrNull)
            val maxTok = anObjs[idx + 1]["max_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            assertTrue("Expected max_tokens >= ${budget + 1024}, got $maxTok", maxTok >= budget + 1024)
        }

        ollama.stream(baseUrl(), "", LlmPromptRequest(systemPrompt = "s", userPrompt = "u", thinkingLevel = ThinkingLevel.OFF)).toList()
        val olEvents = ollama.stream(baseUrl(), "", LlmPromptRequest(systemPrompt = "s", userPrompt = "u", thinkingLevel = ThinkingLevel.MEDIUM)).toList()
        assertEquals("本地思考", olEvents.filterIsInstance<LlmStreamEvent.ThinkingDelta>().single().delta)
        assertEquals("本地答案", olEvents.filterIsInstance<LlmStreamEvent.ContentDelta>().single().delta)
        val olOff = json.parseToJsonElement(ollamaBodies[0]).jsonObject
        val olOn = json.parseToJsonElement(ollamaBodies[1]).jsonObject
        assertNull(olOff["think"])
        assertEquals(true, olOn["think"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `BYOK edit preserves secret, delete promotes defaults and wipes secret, undo restores atomically`() = runTest {
        val vault = InMemorySecretVaultAdapter()
        val cfgDir = tempFolder.newFolder("ai_crud")
        val runtime = AiRuntimeFactory.createInMemory(secretVault = vault, configDir = cfgDir)

        val deepseekKey = "sk-deepseek-plain-9876"
        val geminiKey = "AIzaSyGeminiSecretKey5432"

        runtime.saveProviderConfig(
            ProviderConfig(
                id = "deepseek",
                displayName = "DeepSeek",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = "https://api.deepseek.com/v1",
                modelId = "deepseek-reasoner",
                capabilities = ProviderCapabilities(supportsJsonSchema = true, supportsVision = false, contextWindow = 128_000),
                providerPresetId = "deepseek",
                thinkingLevel = ThinkingLevel.HIGH,
            ),
            rawApiKey = deepseekKey,
        )
        runtime.saveProviderConfig(
            ProviderConfig(
                id = "gemini",
                displayName = "Gemini Flash",
                kind = ProviderKind.GEMINI,
                endpoint = "https://generativelanguage.googleapis.com/v1beta",
                modelId = "gemini-2.5-flash",
                capabilities = ProviderCapabilities(supportsJsonSchema = true, supportsVision = true, contextWindow = 1_000_000),
                providerPresetId = "gemini",
                thinkingLevel = ThinkingLevel.LOW,
            ),
            rawApiKey = geminiKey,
        )
        runtime.saveProviderConfig(
            ProviderConfig(
                id = "ollama",
                displayName = "Ollama 本地",
                kind = ProviderKind.OLLAMA,
                endpoint = "http://127.0.0.1:11434",
                modelId = "gemma3:12b",
                capabilities = ProviderCapabilities(supportsJsonSchema = true, supportsVision = true, contextWindow = 32_768),
                providerPresetId = "ollama",
                thinkingLevel = ThinkingLevel.OFF,
                requiresApiKey = false,
            ),
            rawApiKey = null,
        )

        val profiles = runtime.listProviderProfiles()
        assertEquals(3, profiles.size)
        val dsSummary = runtime.getProviderSummary("deepseek")!!
        assertTrue(dsSummary.isDefaultCompanion)
        assertFalse(dsSummary.isDefaultVision)
        assertEquals("sk-d••••9876", dsSummary.maskedApiKey)
        assertFalse(profiles.toString().contains(deepseekKey))
        assertFalse(profiles.toString().contains(geminiKey))
        val diskText = cfgDir.resolve("providers_meta.json").readText()
        assertFalse(diskText.contains(deepseekKey))
        assertFalse(diskText.contains(geminiKey))

        val olSummary = runtime.getProviderSummary("ollama")!!
        assertEquals("本地免 Key", olSummary.maskedApiKey)
        assertFalse(olSummary.hasApiKey)

        val rejectNonVision = runCatching { runtime.setDefaultVision("deepseek") }
        assertTrue(rejectNonVision.isFailure)

        runtime.saveProviderConfig(
            ProviderConfig(
                id = "deepseek",
                displayName = "DeepSeek Renamed",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = "https://api.deepseek.com/v1",
                modelId = "deepseek-chat",
                capabilities = ProviderCapabilities(supportsJsonSchema = true, supportsVision = false, contextWindow = 128_000),
                providerPresetId = "deepseek",
                thinkingLevel = ThinkingLevel.OFF,
            ),
            rawApiKey = "",
        )
        assertEquals(deepseekKey, vault.getSecret("provider_api_key_deepseek"))
        assertEquals("DeepSeek Renamed", runtime.getProviderSummary("deepseek")!!.displayName)
        assertTrue(runtime.getProviderSummary("deepseek")!!.isDefaultCompanion)

        val deletedDs = runtime.deleteProviderConfig("deepseek")!!
        assertNull(vault.getSecret("provider_api_key_deepseek"))
        assertNull(runtime.getProviderSummary("deepseek"))
        assertFalse(deletedDs.toString().contains(deepseekKey))
        assertTrue(runtime.getProviderSummary("gemini")!!.isDefaultCompanion)

        val deletedGm = runtime.deleteProviderConfig("gemini")!!
        assertNull(vault.getSecret("provider_api_key_gemini"))
        assertTrue(runtime.getProviderSummary("ollama")!!.isDefaultCompanion)
        assertTrue(runtime.getProviderSummary("ollama")!!.isDefaultVision)

        assertTrue(runtime.restoreDeletedProvider(deletedGm))
        assertEquals(geminiKey, vault.getSecret("provider_api_key_gemini"))
        assertTrue(runtime.getProviderSummary("gemini")!!.isDefaultVision)

        assertTrue(runtime.restoreDeletedProvider(deletedDs))
        assertEquals(deepseekKey, vault.getSecret("provider_api_key_deepseek"))
        assertTrue(runtime.getProviderSummary("deepseek")!!.isDefaultCompanion)
        assertFalse(runtime.restoreDeletedProvider(deletedDs))
    }

    @Test
    fun `ScriptedLlmFakeAdapter supports deterministic model discovery and connection test`() = runTest {
        val fake = ScriptedLlmFakeAdapter()
        fake.setAvailableModels(
            listOf(
                DiscoveredModel(id = "fake-a", displayName = "Fake A", supportsVision = true),
                DiscoveredModel(id = "fake-b", displayName = "Fake B"),
            ),
        )
        fake.enqueueReply(
            ScriptedLlmFakeAdapter.ScriptedStreamReply(
                thinkingText = "fake thinking",
                contentText = "fake answer",
            ),
        )

        val runtime = AiRuntimeFactory.createInMemory(
            providerAdapters = mapOf(
                ProviderKind.OPENAI_COMPAT to fake,
                ProviderKind.GEMINI to fake,
                ProviderKind.ANTHROPIC to fake,
                ProviderKind.OLLAMA to fake,
            ),
        )
        runtime.saveProviderConfig(
            ProviderConfig(
                id = "fake",
                displayName = "Fake",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = "https://fake.local/v1",
                modelId = "fake-a",
                capabilities = ProviderCapabilities(true, true, 64_000),
            ),
            rawApiKey = "sk-fake-123456",
        )

        val models = runtime.listAvailableModels("fake").getOrThrow()
        assertEquals(listOf("fake-a", "fake-b"), models.map { it.id })
        val status = runtime.testProviderConnection("fake").getOrThrow()
        assertTrue(status.ok)
        assertEquals(2, status.modelCount)

        val events = runtime.streamCompletion(LlmPromptRequest(systemPrompt = "s", userPrompt = "u")).toList()
        assertEquals("fake thinking", events.filterIsInstance<LlmStreamEvent.ThinkingDelta>().single().delta)
        assertEquals("fake answer", events.filterIsInstance<LlmStreamEvent.Completed>().single().fullText)
    }

    @Test
    fun `stalled GET endpoint fails fast with ProviderAuthOrQuota instead of inheriting infinite readTimeout`() = runTest {
        val heldSockets = CopyOnWriteArrayList<Socket>()
        val stallServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val stallThread = Thread {
            while (!stallServer.isClosed) {
                val sock = runCatching { stallServer.accept() }.getOrNull() ?: break
                heldSockets += sock
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            val infiniteStreamClient = app.banread.ai.internal.HttpStreamingSupport.defaultClient()
            assertEquals(0, infiniteStreamClient.readTimeoutMillis)
            assertEquals(0, infiniteStreamClient.callTimeoutMillis)

            val shortTimeoutClient = OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(250, TimeUnit.MILLISECONDS)
                .callTimeout(400, TimeUnit.MILLISECONDS)
                .build()
            val runtime = AiRuntimeFactory.createInMemory(
                providerAdapters = mapOf(
                    ProviderKind.OPENAI_COMPAT to OpenAiCompatAdapter(client = shortTimeoutClient, maxRetries = 0),
                ),
            )
            val startMs = System.currentTimeMillis()
            val res = runtime.listAvailableModels(
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = "http://127.0.0.1:${stallServer.localPort}/v1",
                rawApiKey = "sk-stall-test-1234",
            )
            val elapsedMs = System.currentTimeMillis() - startMs

            assertTrue(res.isFailure)
            assertTrue("Expected fast timeout (< 5s), took ${elapsedMs}ms", elapsedMs < 5_000L)
            val failure = (res.exceptionOrNull() as AppFailureException).failure as AppFailure.ProviderAuthOrQuota
            assertNull(failure.httpCode)
            assertFalse(failure.message.contains("sk-stall-test-1234"))
        } finally {
            heldSockets.forEach { runCatching { it.close() } }
            runCatching { stallServer.close() }
            stallThread.interrupt()
        }
    }

    @Test
    fun `saveProviderConfig writes secretVault before persisting metadata and rolls back cleanly on vault failure`() = runTest {
        val cfgDir = tempFolder.newFolder("ai_vault_fail")
        val failingVault = object : SecretVaultPort {
            override suspend fun putSecret(alias: String, plaintext: String) {
                throw IllegalStateException("Keystore locked")
            }

            override suspend fun getSecret(alias: String): String? = null
            override suspend fun deleteSecret(alias: String) = Unit
        }
        val runtime = AiRuntimeFactory.createInMemory(secretVault = failingVault, configDir = cfgDir)

        val attempt = runCatching {
            runtime.saveProviderConfig(
                ProviderConfig(
                    id = "openai",
                    displayName = "OpenAI",
                    kind = ProviderKind.OPENAI_COMPAT,
                    endpoint = "https://api.openai.com/v1",
                    modelId = "gpt-4.1",
                    capabilities = ProviderCapabilities(true, true, 128_000),
                ),
                rawApiKey = "sk-should-not-persist",
            )
        }
        assertTrue(attempt.isFailure)
        assertTrue(runtime.listProviderProfiles().isEmpty())
        assertFalse(cfgDir.resolve("providers_meta.json").exists())
    }

    private data class TestReq(
        val method: String,
        val fullPath: String,
        val pathOnly: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class TestResp(
        val code: Int,
        val bodyText: String,
        val contentType: String = "application/json; charset=utf-8",
    )

    private class TestHttpServer : Closeable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort
        private val routes = ConcurrentHashMap<String, (TestReq) -> TestResp>()
        private val executor = Executors.newCachedThreadPool()
        private val running = AtomicBoolean(false)

        fun route(pathPrefix: String, handler: (TestReq) -> TestResp) {
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
                var p3 = 0
                var p2 = 0
                var p1 = 0
                while (true) {
                    val b = input.read()
                    if (b == -1) return
                    headerBytes.write(b)
                    if (p3 == '\r'.code && p2 == '\n'.code && p1 == '\r'.code && b == '\n'.code) break
                    p3 = p2
                    p2 = p1
                    p1 = b
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
                        headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
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
                val req = TestReq(method, fullPath, pathOnly, headers, String(bodyBytes, 0, readTotal, Charsets.UTF_8))
                val handler = routes[pathOnly] ?: routes.entries.firstOrNull { pathOnly.startsWith(it.key) }?.value
                val resp = handler?.invoke(req) ?: TestResp(404, "Not found", "text/plain")
                val payload = resp.bodyText.toByteArray(Charsets.UTF_8)
                val statusText = when (resp.code) {
                    200 -> "OK"
                    401 -> "Unauthorized"
                    403 -> "Forbidden"
                    404 -> "Not Found"
                    429 -> "Too Many Requests"
                    else -> "Error"
                }
                val out = sock.getOutputStream()
                val header = "HTTP/1.1 ${resp.code} $statusText\r\n" +
                    "Content-Type: ${resp.contentType}\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                out.write(payload)
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
