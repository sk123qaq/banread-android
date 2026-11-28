package app.banread.sync

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.SecretVaultPort
import app.banread.model.StateSyncPort
import app.banread.sync.internal.ShardRedLineGuard
import app.banread.sync.internal.SyncEndpointUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-Kotlin AWS Signature V4 (`AWS4-HMAC-SHA256`) [StateSyncPort] adapter for Cloudflare R2 & S3-compatible buckets.
 * Zero AWS SDK bloat; enforces [ShardRedLineGuard] before any network transfer.
 */
class S3R2ShardAdapter(
    private val config: S3R2Config,
    private val secretVault: SecretVaultPort,
    private val secretAlias: String,
    private val client: OkHttpClient = HttpDesktopGatewayAdapter.defaultHttpClient(),
    private val clock: Clock = Clock.systemUTC(),
) : StateSyncPort {

    private val normalizedEndpoint: String = SyncEndpointUrlPolicy.normalizeHttpBaseUrl(config.endpointUrl)
    private val cleanKeyPrefix: String = config.keyPrefix.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }

    override suspend fun putShard(relativePath: String, contentBytes: ByteArray) {
        val cleanPath = ShardRedLineGuard.normalizeAndValidateShardPath(relativePath)
        ShardRedLineGuard.validatePayloadSize(cleanPath, contentBytes)
        val secretKey = requireSecretKey()
        val objectKey = "$cleanKeyPrefix$cleanPath"

        withContext(Dispatchers.IO) {
            val mediaType = if (cleanPath.endsWith(".jsonl")) NDJSON_MEDIA else JSON_MEDIA
            val signed = buildSignedRequest(
                method = "PUT",
                objectKey = objectKey,
                queryParams = emptyList(),
                bodyBytes = contentBytes,
                contentType = mediaType.toString(),
                secretAccessKey = secretKey,
            )
            val req = Request.Builder()
                .url(signed.url)
                .apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                }
                .put(contentBytes.toRequestBody(mediaType))
                .build()

            val response = try {
                client.newCall(req).execute()
            } catch (ex: IOException) {
                throw AppFailureException(AppFailure.NetworkOrSyncConflict("S3/R2 上传失败：${ex.message ?: "IO error"}"))
            }
            response.use { resp ->
                when (resp.code) {
                    in 200..299 -> Unit
                    401, 403 -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("S3/R2 鉴权失败 (HTTP ${resp.code})"),
                    )
                    429 -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("S3/R2 请求限流 (HTTP 429)"),
                    )
                    else -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("S3/R2 PUT 失败 (${resp.code}): ${resp.message}"),
                    )
                }
            }
        }
    }

    override suspend fun listShards(prefix: String): List<String> {
        val cleanPrefix = ShardRedLineGuard.normalizeAndValidatePrefix(prefix)
        val secretKey = requireSecretKey()
        val fullPrefix = "$cleanKeyPrefix${cleanPrefix.trimEnd('/')}/"

        return withContext(Dispatchers.IO) {
            val queryParams = listOf(
                "list-type" to "2",
                "prefix" to fullPrefix,
            )
            val signed = buildSignedRequest(
                method = "GET",
                objectKey = "",
                queryParams = queryParams,
                bodyBytes = EMPTY_BYTES,
                contentType = null,
                secretAccessKey = secretKey,
            )
            val req = Request.Builder()
                .url(signed.url)
                .apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                }
                .get()
                .build()

            val response = try {
                client.newCall(req).execute()
            } catch (ex: IOException) {
                throw AppFailureException(AppFailure.NetworkOrSyncConflict("S3/R2 ListObjectsV2 失败：${ex.message ?: "IO error"}"))
            }
            response.use { resp ->
                when (resp.code) {
                    404 -> emptyList()
                    401, 403 -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("S3/R2 鉴权失败 (HTTP ${resp.code})"),
                    )
                    429 -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("S3/R2 请求限流 (HTTP 429)"),
                    )
                    in 200..299 -> {
                        val xml = resp.body?.string().orEmpty()
                        parseListObjectsV2(xml, cleanPrefix)
                    }
                    else -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("S3/R2 ListObjectsV2 失败 (${resp.code}): ${resp.message}"),
                    )
                }
            }
        }
    }

    override suspend fun getShard(relativePath: String): ByteArray? {
        val cleanPath = ShardRedLineGuard.normalizeAndValidateShardPath(relativePath)
        val secretKey = requireSecretKey()
        val objectKey = "$cleanKeyPrefix$cleanPath"

        return withContext(Dispatchers.IO) {
            val signed = buildSignedRequest(
                method = "GET",
                objectKey = objectKey,
                queryParams = emptyList(),
                bodyBytes = EMPTY_BYTES,
                contentType = null,
                secretAccessKey = secretKey,
            )
            val req = Request.Builder()
                .url(signed.url)
                .apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                }
                .get()
                .build()

            val response = try {
                client.newCall(req).execute()
            } catch (ex: IOException) {
                throw AppFailureException(AppFailure.NetworkOrSyncConflict("S3/R2 下载失败：${ex.message ?: "IO error"}"))
            }
            response.use { resp ->
                when (resp.code) {
                    404 -> null
                    401, 403 -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("S3/R2 鉴权失败 (HTTP ${resp.code})"),
                    )
                    429 -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("S3/R2 请求限流 (HTTP 429)"),
                    )
                    in 200..299 -> {
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        ShardRedLineGuard.validatePayloadSize(cleanPath, bytes)
                        bytes
                    }
                    else -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("S3/R2 GET 失败 (${resp.code}): ${resp.message}"),
                    )
                }
            }
        }
    }

    internal fun buildSignedRequest(
        method: String,
        objectKey: String,
        queryParams: List<Pair<String, String>>,
        bodyBytes: ByteArray,
        contentType: String?,
        secretAccessKey: String,
        instant: Instant = clock.instant(),
    ): SignedRequest {
        val endpointUri = URI(normalizedEndpoint)
        val scheme = endpointUri.scheme.lowercase()
        val baseHost = endpointUri.host.lowercase()
        val portPart = if (endpointUri.port > 0) ":${endpointUri.port}" else ""
        val basePath = endpointUri.path.orEmpty().trimEnd('/')

        val effectiveHostHeader: String
        val rawResourcePath: String
        if (config.usePathStyle) {
            effectiveHostHeader = "$baseHost$portPart"
            rawResourcePath = buildString {
                append(basePath)
                append('/').append(config.bucket.trim('/'))
                if (objectKey.isNotEmpty()) {
                    append('/').append(objectKey.trimStart('/'))
                }
            }
        } else {
            effectiveHostHeader = "${config.bucket.trim('.')}.$baseHost$portPart"
            rawResourcePath = buildString {
                append(basePath)
                append('/')
                if (objectKey.isNotEmpty()) {
                    append(objectKey.trimStart('/'))
                }
            }
        }

        val canonicalUri = encodeCanonicalPath(rawResourcePath)
        val sortedQuery = queryParams
            .map { (k, v) -> awsUriEncode(k) to awsUriEncode(v) }
            .sortedWith(compareBy({ it.first }, { it.second }))
        val canonicalQuery = sortedQuery.joinToString("&") { "${it.first}=${it.second}" }

        val utc = instant.atOffset(ZoneOffset.UTC)
        val amzDate = AMZ_DATE_FMT.format(utc)
        val dateStamp = DATE_STAMP_FMT.format(utc)
        val payloadHash = sha256Hex(bodyBytes)

        val headerMap = linkedMapOf<String, String>(
            "host" to effectiveHostHeader,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate,
        )
        if (!contentType.isNullOrBlank()) {
            headerMap["content-type"] = contentType.trim()
        }

        val sortedHeaders = headerMap.entries.sortedBy { it.key }
        val canonicalHeaders = sortedHeaders.joinToString("") { "${it.key}:${it.value}\n" }
        val signedHeaders = sortedHeaders.joinToString(";") { it.key }

        val canonicalRequest = buildString {
            append(method.uppercase()).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadHash)
        }

        val region = config.region.ifBlank { "auto" }
        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256\n")
            append(amzDate).append('\n')
            append(credentialScope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        val kDate = hmacSha256("AWS4$secretAccessKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, "s3")
        val kSigning = hmacSha256(kService, "aws4_request")
        val signature = hmacSha256(kSigning, stringToSign).toHexLower()

        val authorization =
            "AWS4-HMAC-SHA256 Credential=${config.accessKeyId}/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        val finalUrl = buildString {
            append(scheme).append("://").append(effectiveHostHeader).append(canonicalUri)
            if (canonicalQuery.isNotEmpty()) append('?').append(canonicalQuery)
        }

        val outHeaders = linkedMapOf(
            "Authorization" to authorization,
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
        )
        return SignedRequest(
            url = finalUrl,
            headers = outHeaders,
            canonicalRequest = canonicalRequest,
            stringToSign = stringToSign,
            signature = signature,
        )
    }

    private fun parseListObjectsV2(xml: String, cleanPrefix: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val keyNodes = doc.getElementsByTagNameNS("*", "Key").let { list ->
            if (list.length > 0) list else doc.getElementsByTagName("Key")
        }
        val expectedPrefix = cleanPrefix.trimEnd('/') + "/"
        val results = linkedSetOf<String>()
        for (i in 0 until keyNodes.length) {
            val rawKey = keyNodes.item(i).textContent?.trim().orEmpty()
            val rel = if (cleanKeyPrefix.isNotEmpty() && rawKey.startsWith(cleanKeyPrefix)) {
                rawKey.removePrefix(cleanKeyPrefix)
            } else {
                rawKey
            }.trimStart('/')
            if (rel.startsWith(expectedPrefix)) {
                runCatching { ShardRedLineGuard.normalizeAndValidateShardPath(rel) }
                    .getOrNull()
                    ?.let { results += it }
            }
        }
        return results.sorted()
    }

    private suspend fun requireSecretKey(): String =
        secretVault.getSecret(secretAlias)?.takeIf { it.isNotBlank() }
            ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("S3/R2 Secret Access Key 未配置"))

    internal data class SignedRequest(
        val url: String,
        val headers: Map<String, String>,
        val canonicalRequest: String,
        val stringToSign: String,
        val signature: String,
    )

    companion object {
        private val EMPTY_BYTES = ByteArray(0)
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val NDJSON_MEDIA = "application/x-ndjson; charset=utf-8".toMediaType()
        private val AMZ_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private val DATE_STAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

        private fun encodeCanonicalPath(rawPath: String): String {
            if (rawPath.isEmpty() || rawPath == "/") return "/"
            val trailingSlash = rawPath.endsWith("/")
            val segments = rawPath.split('/').filter { it.isNotEmpty() }
            val encoded = segments.joinToString("/", prefix = "/") { awsUriEncode(it) }
            return if (trailingSlash && !encoded.endsWith("/")) "$encoded/" else encoded
        }

        internal fun awsUriEncode(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                val isUnreserved = (c in 'A'.code..'Z'.code) ||
                    (c in 'a'.code..'z'.code) ||
                    (c in '0'.code..'9'.code) ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
                if (isUnreserved) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%')
                    sb.append(HEX_UPPER[(c ushr 4) and 0x0F])
                    sb.append(HEX_UPPER[c and 0x0F])
                }
            }
            return sb.toString()
        }

        private fun hmacSha256(key: ByteArray, data: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8))
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHexLower()

        private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
        private val HEX_UPPER = "0123456789ABCDEF".toCharArray()
    }
}
