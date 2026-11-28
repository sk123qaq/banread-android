package app.banread.sync.internal

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ephemeral local HTTP/1.1 server for Channel 2 (LAN Direct Transfer).
 * Implemented directly over [ServerSocket] so it runs identically on Android and JVM tests.
 * Enforces a short-lived 6-digit pairing code and supports `Range: bytes=...` streaming for `/share/v1/book`.
 */
internal class EphemeralLanHttpServer(
    private val manifestJsonBytes: ByteArray,
    private val companionJsonBytes: ByteArray,
    private val bookFile: File?,
    val pairingCode: String,
    bindHost: String = "0.0.0.0",
    preferredPort: Int = 0,
    ttlMinutes: Long = 15L,
) : Closeable {

    private val running = AtomicBoolean(true)
    private val serverSocket: ServerSocket = try {
        ServerSocket(preferredPort, 16, InetAddress.getByName(bindHost))
    } catch (_: Exception) {
        ServerSocket(0, 16, InetAddress.getByName(bindHost))
    }
    private val workers: ExecutorService = Executors.newCachedThreadPool()
    private val reaper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    val port: Int get() = serverSocket.localPort
    val hostForShareUrl: String = if (bindHost == "127.0.0.1") "127.0.0.1" else detectLanIpv4()

    init {
        workers.submit { acceptLoop() }
        reaper.schedule({ close() }, ttlMinutes, TimeUnit.MINUTES)
    }

    private fun acceptLoop() {
        while (running.get() && !serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break
            }
            workers.submit {
                socket.use { handleClient(it) }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = BufferedOutputStream(socket.getOutputStream())

        val headerLines = readHeaderLines(input) ?: return
        if (headerLines.isEmpty()) return
        val requestLine = headerLines.first().split(' ')
        if (requestLine.size < 2) {
            writeJsonError(out, 400, "bad_request")
            return
        }
        val method = requestLine[0].uppercase()
        val rawTarget = requestLine[1]
        if (method != "GET" && method != "HEAD") {
            writeJsonError(out, 405, "method_not_allowed")
            return
        }

        val headers = linkedMapOf<String, String>()
        for (i in 1 until headerLines.size) {
            val line = headerLines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val queryParams = parseQuery(if (qIdx >= 0) rawTarget.substring(qIdx + 1) else "")

        val providedCode = headers["x-banread-code"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: queryParams["code"]
            ?: ""
        if (normalizePairingCode(providedCode) != normalizePairingCode(pairingCode)) {
            writeJsonError(out, 401, "unauthorized")
            return
        }

        val isHead = method == "HEAD"
        when (path) {
            "/share/v1/manifest" -> {
                writeBytesResponse(out, 200, "application/json; charset=utf-8", manifestJsonBytes, isHead)
            }
            "/share/v1/companion" -> {
                writeBytesResponse(out, 200, "application/json; charset=utf-8", companionJsonBytes, isHead)
            }
            "/share/v1/book" -> {
                val file = bookFile?.takeIf { it.isFile }
                if (file == null) {
                    writeJsonError(out, 404, "book_not_included")
                    return
                }
                serveFileWithRange(out, file, headers["range"], isHead)
            }
            else -> writeJsonError(out, 404, "not_found")
        }
    }

    private fun serveFileWithRange(
        out: BufferedOutputStream,
        file: File,
        rangeHeader: String?,
        head: Boolean,
    ) {
        val totalSize = file.length()
        if (rangeHeader.isNullOrBlank()) {
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Content-Length: ").append(totalSize).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray(Charsets.US_ASCII))
            if (!head) {
                file.inputStream().buffered().use { it.copyTo(out) }
            }
            out.flush()
            return
        }

        val parsed = parseByteRange(rangeHeader, totalSize)
        if (parsed == null) {
            val header = buildString {
                append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                append("Content-Range: bytes */").append(totalSize).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.flush()
            return
        }

        val (start, end) = parsed
        val length = end - start + 1
        val header = buildString {
            append("HTTP/1.1 206 Partial Content\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(totalSize).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (!head) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(16 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                }
            }
        }
        out.flush()
    }

    private fun writeBytesResponse(
        out: BufferedOutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        head: Boolean,
    ) {
        val reason = if (status == 200) "OK" else "Error"
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (!head && body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    private fun writeJsonError(out: BufferedOutputStream, status: Int, error: String) {
        val body = """{"ok":false,"error":"$error"}""".toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun readHeaderLines(input: BufferedInputStream): List<String>? {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var totalRead = 0
        while (totalRead < 16 * 1024) {
            val b = input.read()
            if (b == -1) return if (lines.isEmpty()) null else lines
            totalRead++
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

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            val k = URLDecoder.decode(if (idx >= 0) pair.substring(0, idx) else pair, "UTF-8")
            val v = if (idx >= 0) URLDecoder.decode(pair.substring(idx + 1), "UTF-8") else ""
            out[k] = v
        }
        return out
    }

    private fun parseByteRange(headerVal: String, totalSize: Long): Pair<Long, Long>? {
        if (totalSize <= 0L) return null
        val raw = headerVal.trim()
        if (!raw.startsWith("bytes=", ignoreCase = true)) return null
        val spec = raw.substring(6).trim()
        if (',' in spec || '-' !in spec) return null
        val (sStr, eStr) = spec.split('-', limit = 2).map { it.trim() }
        if (sStr.isEmpty() && eStr.isEmpty()) return null
        if (sStr.isEmpty()) {
            val suffix = eStr.toLongOrNull()?.takeIf { it > 0L } ?: return null
            val start = maxOf(0L, totalSize - suffix)
            return start to (totalSize - 1L)
        }
        val start = sStr.toLongOrNull() ?: return null
        if (start < 0L || start >= totalSize) return null
        if (eStr.isEmpty()) return start to (totalSize - 1L)
        val end = minOf(eStr.toLongOrNull() ?: return null, totalSize - 1L)
        return if (end < start) null else start to end
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket.close() }
        runCatching { reaper.shutdownNow() }
        runCatching { workers.shutdownNow() }
    }

    companion object {
        fun normalizePairingCode(raw: String): String =
            raw.trim().replace("-", "").replace(" ", "")

        fun detectLanIpv4(): String {
            runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
            }
            return "127.0.0.1"
        }
    }
}
