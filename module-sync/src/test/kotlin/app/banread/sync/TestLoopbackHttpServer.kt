package app.banread.sync

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class TestHttpRequest(
    val method: String,
    val path: String,
    val rawQuery: String,
    val headers: Map<String, String>,
    val bodyBytes: ByteArray,
)

internal data class TestHttpResponse(
    val code: Int,
    val bodyBytes: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
)

internal class TestLoopbackHttpServer(
    private val handler: (TestHttpRequest) -> TestHttpResponse,
) : Closeable {

    private val running = AtomicBoolean(true)
    private val serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    val port: Int get() = serverSocket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        pool.submit {
            while (running.get() && !serverSocket.isClosed) {
                val sock = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                pool.submit { sock.use { handleSocket(it) } }
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = BufferedOutputStream(socket.getOutputStream())
        val lines = readHeaderLines(input) ?: return
        if (lines.isEmpty()) return

        val reqParts = lines.first().split(' ')
        if (reqParts.size < 2) return
        val method = reqParts[0].uppercase()
        val rawTarget = reqParts[1]
        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val query = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""

        val headers = linkedMapOf<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                headers[lines[i].substring(0, colon).trim().lowercase()] = lines[i].substring(colon + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            if (read == contentLength) buf else buf.copyOf(read)
        } else {
            ByteArray(0)
        }

        val resp = handler(TestHttpRequest(method, path, query, headers, bodyBytes))
        val headerText = buildString {
            append("HTTP/1.1 ").append(resp.code).append(" OK\r\n")
            if ("Content-Length" !in resp.headers && "content-length" !in resp.headers) {
                append("Content-Length: ").append(resp.bodyBytes.size).append("\r\n")
            }
            resp.headers.forEach { (k, v) ->
                append(k).append(": ").append(v).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        out.write(headerText.toByteArray(Charsets.US_ASCII))
        if (method != "HEAD" && resp.bodyBytes.isNotEmpty()) {
            out.write(resp.bodyBytes)
        }
        out.flush()
    }

    private fun readHeaderLines(input: BufferedInputStream): List<String>? {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var total = 0
        while (total < 32 * 1024) {
            val b = input.read()
            if (b == -1) return if (lines.isEmpty()) null else lines
            total++
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (line.isEmpty()) break
                lines += line
            } else {
                sb.append(b.toChar())
            }
        }
        return lines
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket.close() }
        runCatching { pool.shutdownNow() }
    }
}
