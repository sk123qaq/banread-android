package app.banread.ai

import app.banread.model.AppFailure
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmStreamEvent
import app.banread.model.ProviderKind
import app.banread.model.ThinkingLevel
import app.banread.model.ProviderPresetCatalog
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class OpenCodeProviderTest {

    private val payload = LlmPromptRequest(
        providerId = "opencode",
        systemPrompt = "系统提示",
        userPrompt = "伴读问题",
        expectJson = false,
        thinkingLevel = ThinkingLevel.OFF,
        conversationId = "thread-abc",
    )

    private fun requestFor(endpoint: String, body: LlmPromptRequest = payload) =
        OpenAiCompatAdapter().buildHttpRequest(
            url = endpoint,
            apiKey = "test-key",
            payload = body,
            model = "claude-sonnet-4-5",
            bodyJson = buildJsonObject { put("model", "claude-sonnet-4-5") },
        )

    @Test
    fun `opencode preset keeps zen v1 chat path and sends session header`() {
        val preset = ProviderPresetCatalog.presets.single { it.id == "opencode" }
        assertEquals(ProviderKind.OPENAI_COMPAT, preset.kind)
        assertEquals("https://opencode.ai/zen/v1", preset.defaultEndpoint)

        val request = requestFor("https://opencode.ai/zen/v1/chat/completions")

        assertEquals("https://opencode.ai/zen/v1/chat/completions", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer test-key", request.header("Authorization"))
        val session = request.header("x-opencode-session")
        assertTrue("session header missing", session != null)
        assertTrue("unexpected session format: $session", Regex("^ses_[0-9a-f]{32}$").matches(session!!))
        assertEquals("banread-android", request.header("User-Agent"))
    }

    @Test
    fun `session header is stable per conversation and absent for other providers`() {
        val first = requestFor("https://opencode.ai/zen/v1/chat/completions").header("x-opencode-session")
        val same = requestFor("https://opencode.ai/zen/v1/chat/completions").header("x-opencode-session")
        val other = requestFor(
            "https://opencode.ai/zen/v1/chat/completions",
            payload.copy(conversationId = "thread-xyz"),
        ).header("x-opencode-session")

        assertEquals(first, same)
        assertNotEquals(first, other)
        assertFalse(
            requestFor("https://api.openai.com/v1/chat/completions").headers
                .any { it.first.equals("x-opencode-session", ignoreCase = true) },
        )
    }

    @Test
    fun `missing session 400 body becomes readable message`() = runTest {
        val body = """
            {"type":"error","error":{"type":"MissingSessionID","message":"Error from provider (Console Go): Request is missing x-opencode-session and cannot be routed efficiently."}}
        """.trimIndent()
        TestHttpServer { _, _, _ -> TestResponse(400, body) }.use { server ->
            val events = OpenAiCompatAdapter(maxRetries = 0)
                .stream("http://127.0.0.1:${server.port}", "test-key", payload)
                .toList()

            val failure = events.single() as LlmStreamEvent.Failed
            val quota = failure.failure as AppFailure.ProviderAuthOrQuota
            assertEquals(400, quota.httpCode)
            assertTrue("message should name OpenCode: ${quota.message}", quota.message.contains("OpenCode"))
            assertTrue("message should name the header: ${quota.message}", quota.message.contains("x-opencode-session"))
            assertFalse("raw JSON leaked: ${quota.message}", quota.message.contains("{"))
            assertFalse("raw type leaked: ${quota.message}", quota.message.contains("MissingSessionID"))
        }
    }

    @Test
    fun `payment authorization 403 becomes subscription message`() = runTest {
        val body = """
            {"type":"error","error":{"type":"EntitlementError","message":"Your subscription ended because its payment method needs authorization."}}
        """.trimIndent()
        TestHttpServer { _, _, _ -> TestResponse(403, body) }.use { server ->
            val events = OpenAiCompatAdapter(maxRetries = 0)
                .stream("http://127.0.0.1:${server.port}", "test-key", payload)
                .toList()

            val quota = (events.single() as LlmStreamEvent.Failed).failure as AppFailure.ProviderAuthOrQuota
            assertEquals(403, quota.httpCode)
            assertTrue("message should mention subscription: ${quota.message}", quota.message.contains("订阅"))
            assertTrue("message should mention payment: ${quota.message}", quota.message.contains("付款"))
            assertFalse("raw JSON leaked: ${quota.message}", quota.message.contains("{"))
        }
    }
}

private data class TestResponse(val code: Int, val body: String)

private class TestHttpServer(
    private val handler: (path: String, headers: Map<String, String>, body: String) -> TestResponse,
) : Closeable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    private val worker = thread(isDaemon = true, name = "test-http-${server.localPort}") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream()
            val headerText = readHeaders(input)
            val headers = headerText.lines()
                .drop(1)
                .mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
                }
                .toMap()
            val path = headerText.lines().first().split(' ').getOrNull(1) ?: "/"
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            var body = ""
            if (length > 0) {
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(bytes, read, length - read)
                    if (count < 0) break
                    read += count
                }
                body = String(bytes, 0, read, Charsets.UTF_8)
            }
            val response = handler(path, headers, body)
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            val reason = when (response.code) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                429 -> "Too Many Requests"
                else -> "Error"
            }
            val head = "HTTP/1.1 ${response.code} $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
    }

    private fun readHeaders(input: InputStream): String {
        val buffer = StringBuilder()
        var matched = 0
        while (matched < 4) {
            val byte = input.read()
            if (byte < 0) break
            buffer.append(byte.toChar())
            matched = when {
                (matched == 0 || matched == 2) && byte == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && byte == '\n'.code -> matched + 1
                else -> 0
            }
        }
        return buffer.toString()
    }

    override fun close() {
        runCatching { server.close() }
        worker.join(1_000)
    }
}
