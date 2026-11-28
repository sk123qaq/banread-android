package app.banread.sync.internal

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.ProtocolException
import java.net.URI

/**
 * Hard Red Line Guard for WebDAV / S3-R2 light-state sync:
 * - Allows ONLY `progress/<uid>/<device_id>.json` and `notes/<uid>/<device_id>.jsonl`.
 * - Rejects `.bcbook`, EPUB, PDF, media, companion caches, path traversal, and payloads > 2 MiB.
 */
internal object ShardRedLineGuard {
    const val MAX_SHARD_BYTES: Int = 2 * 1024 * 1024 // 2 MiB

    private val UID_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val DEVICE_ID_REGEX = Regex("^[A-Za-z0-9_.-]{1,128}$")
    private val FORBIDDEN_EXTENSIONS = setOf(
        ".bcbook", ".bcshare", ".bcnote", ".epub", ".pdf", ".mobi", ".azw3", ".kfx",
        ".zip", ".tar", ".gz", ".png", ".jpg", ".jpeg", ".webp", ".mp3", ".mp4",
    )

    fun normalizeAndValidateShardPath(rawPath: String): String {
        val normalized = normalizeBase(rawPath)
        val segments = normalized.split('/')
        if (segments.size != 3) {
            throw redLineError("Shard path must have exactly 3 segments (<progress|notes>/<uid>/<device_id>.<ext>): $rawPath")
        }
        val (root, uid, fileName) = segments
        if (root != "progress" && root != "notes") {
            throw redLineError("Shard path outside allowlisted prefixes (progress/, notes/): $rawPath")
        }
        if (!UID_REGEX.matches(uid)) {
            throw redLineError("Invalid uid segment in shard path: $uid")
        }
        val expectedExt = if (root == "progress") ".json" else ".jsonl"
        if (!fileName.endsWith(expectedExt)) {
            throw redLineError("Invalid shard file extension for $root/: $fileName (expected $expectedExt)")
        }
        val deviceId = fileName.removeSuffix(expectedExt)
        if (!DEVICE_ID_REGEX.matches(deviceId) || deviceId == "." || deviceId == "..") {
            throw redLineError("Invalid device_id in shard path: $deviceId")
        }
        return normalized
    }

    fun normalizeAndValidatePrefix(rawPrefix: String): String {
        val clean = normalizeBase(rawPrefix, allowTrailingSlash = true)
        val trimmed = clean.trimEnd('/')
        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            throw redLineError("Shard list prefix must start with progress/ or notes/")
        }
        val root = segments[0]
        if (root != "progress" && root != "notes") {
            throw redLineError("Shard list prefix outside allowlisted prefixes (progress/, notes/): $rawPrefix")
        }
        if (segments.size >= 2 && !UID_REGEX.matches(segments[1])) {
            throw redLineError("Invalid uid in shard list prefix: ${segments[1]}")
        }
        if (segments.size > 2) {
            throw redLineError("Shard list prefix too deep: $rawPrefix")
        }
        return if (clean.endsWith("/")) "$trimmed/" else trimmed
    }

    fun validatePayloadSize(relativePath: String, contentBytes: ByteArray) {
        if (contentBytes.size > MAX_SHARD_BYTES) {
            throw redLineError(
                "Shard payload exceeds 2 MiB hard limit (${contentBytes.size} bytes) for $relativePath",
            )
        }
    }

    private fun normalizeBase(raw: String, allowTrailingSlash: Boolean = false): String {
        if (raw.contains('\u0000') || raw.contains('\\')) {
            throw redLineError("Illegal characters in shard path: $raw")
        }
        val stripped = raw.trim().trimStart('/')
        val clean = if (allowTrailingSlash) stripped else stripped.trimEnd('/')
        if (clean.isEmpty()) {
            throw redLineError("Shard path must not be empty")
        }
        val lower = clean.lowercase()
        if (FORBIDDEN_EXTENSIONS.any { lower.endsWith(it) }) {
            throw redLineError("Forbidden binary/archive/media extension in light-state sync: $raw")
        }
        val parts = clean.trimEnd('/').split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw redLineError("Path traversal or empty segment rejected: $raw")
        }
        return clean
    }

    private fun redLineError(reason: String): AppFailureException =
        AppFailureException(AppFailure.NetworkOrSyncConflict("Red-line guard: $reason"))
}

internal object SyncEndpointUrlPolicy {
    fun normalizeHttpBaseUrl(rawUrl: String, requireHttpsForPublicHosts: Boolean = true): String {
        val clean = rawUrl.trim().trimEnd('/')
        val httpUrl = clean.toHttpUrlOrNull()
            ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("URL must use http:// or https:// with host: $rawUrl"))
        val uri = runCatching { URI(clean) }.getOrNull()
            ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("Invalid URL: $rawUrl"))
        val scheme = httpUrl.scheme.lowercase()
        val host = httpUrl.host.lowercase()
        if ((scheme != "http" && scheme != "https") || host.isBlank() || uri.host.isNullOrBlank()) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("URL must use http:// or https:// with host: $rawUrl"))
        }
        if (requireHttpsForPublicHosts && scheme == "http" && !isPrivateOrLoopbackHost(host)) {
            throw AppFailureException(
                AppFailure.NetworkOrSyncConflict("Public endpoint must use https:// ($host)"),
            )
        }
        return clean
    }

    @Throws(IOException::class)
    fun checkTransportAllowed(httpUrl: HttpUrl) {
        if (httpUrl.scheme.equals("http", ignoreCase = true) && !isPrivateOrLoopbackHost(httpUrl.host)) {
            throw ProtocolException("Public endpoint must use https:// (${httpUrl.host})")
        }
    }

    @Throws(IOException::class)
    fun checkTransportAllowed(uri: URI) {
        if (uri.scheme.equals("http", ignoreCase = true) && !isPrivateOrLoopbackHost(uri.host.orEmpty())) {
            throw ProtocolException("Public endpoint must use https:// (${uri.host})")
        }
    }

    fun isTailscaleCgnatIpv4(host: String): Boolean {
        val ipv4 = parseIpv4Octets(host.trim()) ?: return false
        return ipv4[0] == 100 && ipv4[1] in 64..127
    }

    fun isPrivateOrLoopbackHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']').trimEnd('.')
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "10.0.2.2") return true
        if ((h.endsWith(".local") && h.length > ".local".length) ||
            (h.endsWith(".lan") && h.length > ".lan".length) ||
            (h.endsWith(".home.arpa") && h.length > ".home.arpa".length)
        ) {
            return !h.startsWith(".") && !h.contains("..")
        }
        val ipv4 = parseIpv4Octets(h)
        if (ipv4 != null) {
            val a = ipv4[0]
            val b = ipv4[1]
            if (a == 10 || a == 127) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 100 && b in 64..127) return true // Tailscale CGNAT 100.64.0.0/10
            return false
        }
        if (':' in h && isPrivateOrLoopbackIpv6(h)) return true
        return false
    }

    private fun parseIpv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || !p.all(Char::isDigit)) return null
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v
        }
        return out
    }

    private fun isPrivateOrLoopbackIpv6(host: String): Boolean {
        if ('.' in host || host.contains(":::")) return false
        val raw = host.substringBefore('%')
        if (raw.split("::").size > 2) return false
        val parts = raw.split(':')
        if (parts.size < 3 || parts.size > 8) return false
        if (!parts.all { it.isEmpty() || (it.length <= 4 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' }) }) {
            return false
        }
        if (raw == "::1") return true
        val nonEmpty = parts.filter { it.isNotEmpty() }
        if (nonEmpty.size == 1 && raw.startsWith("::") && nonEmpty[0].toInt(16) == 1) return true
        if (parts.size == 8 && parts.take(7).all { it.isNotEmpty() && it.toInt(16) == 0 } && parts[7].toInt(16) == 1) {
            return true
        }
        val firstSegment = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val firstHextet = firstSegment.toInt(16)
        return firstHextet in 0xFC00..0xFDFF || firstHextet in 0xFE80..0xFEBF
    }
}
