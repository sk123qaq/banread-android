package app.banread.sync

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.SecretVaultPort
import app.banread.model.StateSyncPort
import app.banread.sync.internal.ShardRedLineGuard
import app.banread.sync.internal.SyncEndpointUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV [StateSyncPort] adapter for lightweight per-device progress & note-log shards.
 * Enforces [ShardRedLineGuard] so large files (.bcbook/PDF/media) can never be uploaded.
 */
class WebDavShardAdapter(
    private val config: WebDavConfig,
    private val secretVault: SecretVaultPort,
    private val secretAlias: String,
    private val client: OkHttpClient = HttpDesktopGatewayAdapter.defaultHttpClient(),
) : StateSyncPort {

    private val normalizedBaseUrl: String = SyncEndpointUrlPolicy.normalizeHttpBaseUrl(config.baseUrl)
    private val basePathPrefix: String = URI(normalizedBaseUrl).path.orEmpty().trimEnd('/')

    override suspend fun putShard(relativePath: String, contentBytes: ByteArray) {
        val cleanPath = ShardRedLineGuard.normalizeAndValidateShardPath(relativePath)
        ShardRedLineGuard.validatePayloadSize(cleanPath, contentBytes)
        val authHeader = resolveAuthHeader()

        withContext(Dispatchers.IO) {
            ensureParentCollections(cleanPath, authHeader)
            val mediaType = if (cleanPath.endsWith(".jsonl")) NDJSON_MEDIA else JSON_MEDIA
            val targetUrl = "$normalizedBaseUrl/$cleanPath"
            val req = Request.Builder()
                .url(targetUrl)
                .header("Authorization", authHeader)
                .put(contentBytes.toRequestBody(mediaType))
                .build()

            var retriedAfterMkcol = false
            while (true) {
                val response = try {
                    client.newCall(req).execute()
                } catch (ex: IOException) {
                    throw AppFailureException(AppFailure.NetworkOrSyncConflict("WebDAV 上传失败：${ex.message ?: "IO error"}"))
                }
                response.use { resp ->
                    when (resp.code) {
                        in 200..299 -> return@withContext
                        401, 403 -> throw authFailure(resp.code)
                        429 -> throw rateLimitFailure(resp.header("Retry-After"))
                        409, 404 -> {
                            if (!retriedAfterMkcol) {
                                retriedAfterMkcol = true
                                ensureParentCollections(cleanPath, authHeader)
                            } else {
                                throw AppFailureException(
                                    AppFailure.NetworkOrSyncConflict("WebDAV 写入冲突 (${resp.code}): $cleanPath"),
                                )
                            }
                        }
                        else -> throw AppFailureException(
                            AppFailure.NetworkOrSyncConflict("WebDAV PUT 失败 (${resp.code}): ${resp.message}"),
                        )
                    }
                }
            }
        }
    }

    override suspend fun listShards(prefix: String): List<String> {
        val cleanPrefix = ShardRedLineGuard.normalizeAndValidatePrefix(prefix)
        val authHeader = resolveAuthHeader()

        return withContext(Dispatchers.IO) {
            val dirPath = cleanPrefix.trimEnd('/')
            val targetUrl = "$normalizedBaseUrl/$dirPath/"
            val req = Request.Builder()
                .url(targetUrl)
                .header("Authorization", authHeader)
                .header("Depth", "1")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
                .build()

            val response = try {
                client.newCall(req).execute()
            } catch (ex: IOException) {
                throw AppFailureException(AppFailure.NetworkOrSyncConflict("WebDAV PROPFIND 失败：${ex.message ?: "IO error"}"))
            }
            response.use { resp ->
                when (resp.code) {
                    404 -> return@withContext emptyList()
                    401, 403 -> throw authFailure(resp.code)
                    429 -> throw rateLimitFailure(resp.header("Retry-After"))
                    200, 207 -> {
                        val xml = resp.body?.string().orEmpty()
                        parsePropfindHrefs(xml, cleanPrefix)
                    }
                    else -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("WebDAV PROPFIND 失败 (${resp.code}): ${resp.message}"),
                    )
                }
            }
        }
    }

    override suspend fun getShard(relativePath: String): ByteArray? {
        val cleanPath = ShardRedLineGuard.normalizeAndValidateShardPath(relativePath)
        val authHeader = resolveAuthHeader()

        return withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$normalizedBaseUrl/$cleanPath")
                .header("Authorization", authHeader)
                .get()
                .build()
            val response = try {
                client.newCall(req).execute()
            } catch (ex: IOException) {
                throw AppFailureException(AppFailure.NetworkOrSyncConflict("WebDAV 下载失败：${ex.message ?: "IO error"}"))
            }
            response.use { resp ->
                when (resp.code) {
                    404 -> null
                    401, 403 -> throw authFailure(resp.code)
                    429 -> throw rateLimitFailure(resp.header("Retry-After"))
                    in 200..299 -> {
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        ShardRedLineGuard.validatePayloadSize(cleanPath, bytes)
                        bytes
                    }
                    else -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("WebDAV GET 失败 (${resp.code}): ${resp.message}"),
                    )
                }
            }
        }
    }

    private fun ensureParentCollections(cleanPath: String, authHeader: String) {
        val parts = cleanPath.split('/').dropLast(1)
        val cumulative = StringBuilder()
        for (seg in parts) {
            if (cumulative.isNotEmpty()) cumulative.append('/')
            cumulative.append(seg)
            val colUrl = "$normalizedBaseUrl/$cumulative/"
            val req = Request.Builder()
                .url(colUrl)
                .header("Authorization", authHeader)
                .method("MKCOL", ByteArray(0).toRequestBody(null))
                .build()
            val response = try {
                client.newCall(req).execute()
            } catch (ex: IOException) {
                throw AppFailureException(AppFailure.NetworkOrSyncConflict("WebDAV MKCOL 失败：${ex.message ?: "IO error"}"))
            }
            response.use { resp ->
                when (resp.code) {
                    200, 201, 204, 301, 405 -> Unit // created or already exists
                    401, 403 -> throw authFailure(resp.code)
                    429 -> throw rateLimitFailure(resp.header("Retry-After"))
                    409 -> Unit // parent missing handled by sequential loop, or treat on retry
                    else -> throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("WebDAV MKCOL 失败 (${resp.code}) at $cumulative"),
                    )
                }
            }
        }
    }

    private fun parsePropfindHrefs(xml: String, cleanPrefix: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val doc = newSecureDocumentBuilder().parse(InputSource(StringReader(xml)))
        val hrefNodes = doc.getElementsByTagNameNS("*", "href").let { list ->
            if (list.length > 0) list else doc.getElementsByTagName("href")
        }
        val expectedPrefix = cleanPrefix.trimEnd('/') + "/"
        val results = linkedSetOf<String>()
        for (i in 0 until hrefNodes.length) {
            val node = hrefNodes.item(i) as? Element ?: continue
            val rawHref = node.textContent?.trim().orEmpty()
            if (rawHref.isEmpty() || rawHref.endsWith("/")) continue
            val pathPart = runCatching { URI(rawHref).path }.getOrNull() ?: rawHref
            val decoded = runCatching { URLDecoder.decode(pathPart, "UTF-8") }.getOrDefault(pathPart)
            val stripped = if (basePathPrefix.isNotEmpty() && decoded.startsWith(basePathPrefix)) {
                decoded.removePrefix(basePathPrefix).trimStart('/')
            } else {
                decoded.trimStart('/')
            }
            val candidate = stripped.indexOf(expectedPrefix).let { idx ->
                if (idx >= 0) stripped.substring(idx) else stripped
            }
            if (candidate.startsWith(expectedPrefix)) {
                runCatching { ShardRedLineGuard.normalizeAndValidateShardPath(candidate) }
                    .getOrNull()
                    ?.let { results += it }
            }
        }
        return results.sorted()
    }

    private suspend fun resolveAuthHeader(): String {
        val secret = secretVault.getSecret(secretAlias)?.takeIf { it.isNotBlank() }
            ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("WebDAV 凭据未配置"))
        return if (config.useBearerAuth) {
            "Bearer $secret"
        } else {
            Credentials.basic(config.username, secret, Charsets.UTF_8)
        }
    }

    private fun authFailure(code: Int): AppFailureException =
        AppFailureException(AppFailure.NetworkOrSyncConflict("WebDAV 鉴权失败 (HTTP $code)"))

    private fun rateLimitFailure(retryAfter: String?): AppFailureException {
        val hint = retryAfter?.takeIf { it.isNotBlank() }?.let { "，请 ${it}s 后重试" }.orEmpty()
        return AppFailureException(AppFailure.NetworkOrSyncConflict("WebDAV 请求限流 (HTTP 429)$hint"))
    }

    private fun newSecureDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder()

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val NDJSON_MEDIA = "application/x-ndjson; charset=utf-8".toMediaType()
        private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
    }
}
