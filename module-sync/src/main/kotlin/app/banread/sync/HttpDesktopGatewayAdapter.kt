package app.banread.sync

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.BlockId
import app.banread.model.ChapterOrdinal
import app.banread.model.CrossBookHit
import app.banread.model.DesktopBookSummary
import app.banread.model.DesktopBridgePort
import app.banread.model.DeviceId
import app.banread.model.DeviceProgressShard
import app.banread.model.OutboxTicket
import app.banread.model.ReadingAnchor
import app.banread.model.WorkId
import app.banread.sync.internal.CatalogResponseDto
import app.banread.sync.internal.CrossSearchRequestDto
import app.banread.sync.internal.CrossSearchResponseDto
import app.banread.sync.internal.DesktopProgressShardDto
import app.banread.sync.internal.GatewayErrorDto
import app.banread.sync.internal.HealthResponseDto
import app.banread.sync.internal.MergedProgressResponseDto
import app.banread.sync.internal.OutboxAckRequestDto
import app.banread.sync.internal.OutboxResponseDto
import app.banread.sync.internal.SyncEndpointUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class ParsedPairingUri(
    val baseUrl: String,
    val token: String,
)

object PairingUriParser {
    fun parse(qrPayload: String): ParsedPairingUri {
        val raw = qrPayload.trim()
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("无效的配对二维码链接"))
        if (!"bc-pair".equals(uri.scheme, ignoreCase = true) || !"v1".equals(uri.host, ignoreCase = true)) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("配对协议必须为 bc-pair://v1"))
        }
        val params = parseQueryParams(uri.rawQuery.orEmpty())
        val rawBase = params["base"]?.trim().orEmpty()
        val token = params["token"]?.trim().orEmpty()
        if (rawBase.isEmpty()) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("配对二维码缺少 base 参数"))
        }
        if (token.isEmpty() || token.any { it.isWhitespace() }) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("配对二维码缺少有效 token 参数"))
        }
        val normalizedBase = SyncEndpointUrlPolicy.normalizeHttpBaseUrl(rawBase)
        return ParsedPairingUri(baseUrl = normalizedBase, token = token)
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = URLDecoder.decode(if (idx >= 0) pair.substring(0, idx) else pair, "UTF-8")
            val value = if (idx >= 0) URLDecoder.decode(pair.substring(idx + 1), "UTF-8") else ""
            result[key] = value
        }
        return result
    }
}

/**
 * Production OkHttp adapter for desktop `bookcorpus.mobile_gateway` (`--mobile-bind`).
 * All wire DTOs and HTTP mechanics remain `internal`.
 */
class HttpDesktopGatewayAdapter(
    private val client: OkHttpClient = defaultHttpClient(),
) : DesktopBridgePort {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun checkHealth(token: String, baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val url = "${normalizeBase(baseUrl)}/api/mobile/v1/health"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        executeChecked(req) { body ->
            val dto = json.decodeFromString(HealthResponseDto.serializer(), body)
            dto.ok
        }
    }

    override suspend fun fetchCatalog(token: String, baseUrl: String, query: String?): List<DesktopBookSummary> =
        fetchCatalog(token = token, baseUrl = baseUrl, query = query, onlyWithCache = false)

    override suspend fun fetchCatalog(
        token: String,
        baseUrl: String,
        query: String?,
        onlyWithCache: Boolean,
    ): List<DesktopBookSummary> = withContext(Dispatchers.IO) {
        val parsed = "${normalizeBase(baseUrl)}/api/mobile/v1/catalog".toHttpUrlOrNull()
            ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("Invalid desktop gateway URL"))
        val url = parsed.newBuilder().apply {
            if (!query.isNullOrBlank()) addQueryParameter("q", query.trim())
            if (onlyWithCache) addQueryParameter("has_cache", "1")
        }.build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        executeChecked(req) { body ->
            json.decodeFromString(CatalogResponseDto.serializer(), body).books
        }
    }

    override suspend fun fetchOutbox(token: String, baseUrl: String): List<OutboxTicket> =
        withContext(Dispatchers.IO) {
            val url = "${normalizeBase(baseUrl)}/api/mobile/v1/outbox"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            executeChecked(req) { body ->
                json.decodeFromString(OutboxResponseDto.serializer(), body).tickets
            }
        }

    override suspend fun ackOutbox(token: String, baseUrl: String, ticketIds: List<String>) {
        if (ticketIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            val url = "${normalizeBase(baseUrl)}/api/mobile/v1/outbox/ack"
            val payload = json.encodeToString(OutboxAckRequestDto.serializer(), OutboxAckRequestDto(ticketIds))
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeChecked(req) { _ -> Unit }
        }
    }

    override suspend fun downloadBundleStream(
        token: String,
        baseUrl: String,
        workId: WorkId,
        destFile: String,
        resumeOffset: Long,
    ): Flow<Long> = downloadBundleStreamWithCacheFlag(
        token = token,
        baseUrl = baseUrl,
        workId = workId,
        destFile = destFile,
        resumeOffset = resumeOffset,
        includeCache = true,
    )

    fun downloadBundleStreamWithCacheFlag(
        token: String,
        baseUrl: String,
        workId: WorkId,
        destFile: String,
        resumeOffset: Long,
        includeCache: Boolean,
    ): Flow<Long> = flow {
        val partFile = File(destFile)
        partFile.parentFile?.mkdirs()
        val etagFile = File(partFile.parentFile, "${partFile.name}.etag")
        val flag = if (includeCache) "1" else "0"
        val url = "${normalizeBase(baseUrl)}/api/mobile/v1/bundle/${workId.raw}?include_cache=$flag"

        val storedEtag = if (etagFile.isFile) etagFile.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() } else null
        var effectiveOffset = if (resumeOffset > 0L && partFile.isFile && partFile.length() >= resumeOffset) {
            resumeOffset
        } else {
            0L
        }

        if (effectiveOffset > 0L) {
            val rangeReq = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Range", "bytes=$effectiveOffset-")
                .get()
                .build()
            val response = try {
                client.newCall(rangeReq).execute()
            } catch (ex: IOException) {
                throw AppFailureException(AppFailure.NetworkOrSyncConflict("桌面连接中断：${ex.message ?: "IO error"}"))
            }

            var needRestartFromZero = false
            response.use { resp ->
                when (resp.code) {
                    401 -> throw unauthorizedError()
                    404 -> throw AppFailureException(AppFailure.NetworkOrSyncConflict("桌面端未找到书籍 ${workId.raw}"))
                    206 -> {
                        val serverEtag = resp.header("ETag")?.trim()
                        if (storedEtag != null && serverEtag != null && storedEtag != serverEtag) {
                            // ETag mismatch restart guard: discard stale .part and restart from byte 0
                            needRestartFromZero = true
                        } else {
                            if (serverEtag != null) etagFile.writeText(serverEtag, Charsets.UTF_8)
                            val body = resp.body
                                ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("Empty bundle response"))
                            var totalWritten = effectiveOffset
                            body.byteStream().use { input ->
                                openPartStream(partFile, append = true).use { out ->
                                    val buf = ByteArray(16 * 1024)
                                    while (true) {
                                        val read = input.read(buf)
                                        if (read <= 0) break
                                        out.write(buf, 0, read)
                                        totalWritten += read
                                        emit(totalWritten)
                                    }
                                }
                            }
                            return@flow
                        }
                    }
                    200 -> {
                        val serverEtag = resp.header("ETag")?.trim()
                        if (serverEtag != null) etagFile.writeText(serverEtag, Charsets.UTF_8)
                        val body = resp.body
                            ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("Empty bundle response"))
                        var totalWritten = 0L
                        body.byteStream().use { input ->
                            openPartStream(partFile, append = false).use { out ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val read = input.read(buf)
                                    if (read <= 0) break
                                    out.write(buf, 0, read)
                                    totalWritten += read
                                    emit(totalWritten)
                                }
                            }
                        }
                        return@flow
                    }
                    416 -> {
                        val headInfo = probeHead(token, url)
                        if (headInfo != null &&
                            headInfo.contentLength == partFile.length() &&
                            (storedEtag == null || headInfo.etag == null || storedEtag == headInfo.etag)
                        ) {
                            emit(partFile.length())
                            return@flow
                        }
                        needRestartFromZero = true
                    }
                    else -> {
                        throw AppFailureException(
                            AppFailure.NetworkOrSyncConflict("下载失败 HTTP ${resp.code}: ${resp.message}"),
                        )
                    }
                }
            }

            if (needRestartFromZero) {
                partFile.delete()
                etagFile.delete()
                effectiveOffset = 0L
            }
        }

        val fullReq = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val response = try {
            client.newCall(fullReq).execute()
        } catch (ex: IOException) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("桌面连接中断：${ex.message ?: "IO error"}"))
        }
        response.use { resp ->
            when (resp.code) {
                401 -> throw unauthorizedError()
                404 -> throw AppFailureException(AppFailure.NetworkOrSyncConflict("桌面端未找到书籍 ${workId.raw}"))
                200 -> {
                    val serverEtag = resp.header("ETag")?.trim()
                    if (serverEtag != null) etagFile.writeText(serverEtag, Charsets.UTF_8)
                    val body = resp.body
                        ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("Empty bundle response"))
                    var totalWritten = 0L
                    body.byteStream().use { input ->
                        openPartStream(partFile, append = false).use { out ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                                totalWritten += read
                                emit(totalWritten)
                            }
                        }
                    }
                }
                else -> throw AppFailureException(
                    AppFailure.NetworkOrSyncConflict("下载失败 HTTP ${resp.code}: ${resp.message}"),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun searchRelated(
        token: String,
        baseUrl: String,
        excludeWorkId: WorkId,
        query: String,
        sourceText: String?,
    ): List<CrossBookHit> = withContext(Dispatchers.IO) {
        val url = "${normalizeBase(baseUrl)}/api/mobile/v1/cross-search"
        val reqDto = CrossSearchRequestDto(
            uid = excludeWorkId.raw,
            question = query.trim().take(300),
            excludeBooks = listOf(excludeWorkId.raw),
            sourceText = sourceText?.trim()?.take(500).orEmpty(),
        )
        val bodyStr = json.encodeToString(CrossSearchRequestDto.serializer(), reqDto)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(bodyStr.toRequestBody(JSON_MEDIA))
            .build()
        val response = try {
            client.newCall(req).execute()
        } catch (ex: IOException) {
            throw AppFailureException(AppFailure.DesktopConnectionRequired("cross_book_search"))
        }
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            when {
                resp.code == 401 -> throw unauthorizedError()
                resp.code == 503 -> throw AppFailureException(AppFailure.DesktopConnectionRequired("cross_book_search"))
                !resp.isSuccessful -> throw AppFailureException(
                    AppFailure.NetworkOrSyncConflict("跨书检索请求失败 (${resp.code}): ${extractErrorMessage(raw)}"),
                )
            }
            val parsed = json.decodeFromString(CrossSearchResponseDto.serializer(), raw)
            parsed.hits.mapNotNull { wire ->
                val cleanUid = wire.uid.trim()
                if (!WORK_ID_REGEX.matches(cleanUid)) return@mapNotNull null
                CrossBookHit(
                    uid = WorkId(cleanUid),
                    title = wire.title,
                    type = wire.type,
                    relevance = wire.relevance,
                    bestPath = wire.bestPath,
                    bestLine = wire.bestLine,
                    snippet = wire.snippet,
                )
            }
        }
    }

    override suspend fun pushProgressShard(token: String, baseUrl: String, shard: DeviceProgressShard) {
        withContext(Dispatchers.IO) {
            val url = "${normalizeBase(baseUrl)}/api/mobile/v1/progress/${shard.workId.raw}"
            val dto = shard.toDesktopWireDto()
            val payload = json.encodeToString(DesktopProgressShardDto.serializer(), dto)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeChecked(req) { _ -> Unit }
        }
    }

    override suspend fun fetchProgressShards(
        token: String,
        baseUrl: String,
        workId: WorkId,
    ): List<DeviceProgressShard> = withContext(Dispatchers.IO) {
        val url = "${normalizeBase(baseUrl)}/api/mobile/v1/progress/${workId.raw}"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        executeChecked(req) { raw ->
            val dto = json.decodeFromString(MergedProgressResponseDto.serializer(), raw)
            dto.shards.mapNotNull { row -> row.toDomainOrNull() }
        }
    }

    private fun probeHead(token: String, url: String): HeadProbe? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .head()
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                HeadProbe(
                    etag = resp.header("ETag")?.trim(),
                    contentLength = resp.header("Content-Length")?.toLongOrNull(),
                )
            }
        }.getOrNull()
    }

    private inline fun <T> executeChecked(request: Request, onSuccess: (String) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (ex: IOException) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("无法连接桌面网关：${ex.message ?: "network error"}"))
        }
        return response.use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            when {
                resp.code == 401 -> throw unauthorizedError()
                resp.code == 503 -> throw AppFailureException(AppFailure.DesktopConnectionRequired("desktop_gateway"))
                !resp.isSuccessful -> {
                    val detail = extractErrorMessage(bodyStr)
                    throw AppFailureException(
                        AppFailure.NetworkOrSyncConflict("桌面网关返回 HTTP ${resp.code}: $detail"),
                    )
                }
                else -> onSuccess(bodyStr)
            }
        }
    }

    private fun extractErrorMessage(raw: String): String {
        if (raw.isBlank()) return "empty error body"
        return runCatching {
            json.decodeFromString(GatewayErrorDto.serializer(), raw).error
        }.getOrDefault(raw.take(200))
    }

    private fun unauthorizedError(): AppFailureException =
        AppFailureException(AppFailure.NetworkOrSyncConflict("桌面网关鉴权失败 (HTTP 401)：请重新扫码配对"))

    private fun openPartStream(file: File, append: Boolean): FileOutputStream = FileOutputStream(file, append)

    private fun normalizeBase(baseUrl: String): String =
        SyncEndpointUrlPolicy.normalizeHttpBaseUrl(baseUrl)

    private fun DeviceProgressShard.toDesktopWireDto(): DesktopProgressShardDto {
        val (blk, offset) = when (val c = cursor) {
            is ReadingAnchor.BlockOffset -> c.blockId.value to c.charStart.coerceAtLeast(0)
            is ReadingAnchor.ReflowCfi -> (c.blockId?.value ?: "B01") to (c.progression.coerceIn(0f, 1f) * 1000f).toInt()
            is ReadingAnchor.PdfPage -> "B01" to c.pageIndex.coerceAtLeast(0)
        }
        return DesktopProgressShardDto(
            uid = workId.raw,
            deviceId = deviceId.raw,
            ordinal = cursor.ordinal.value,
            blockId = blk,
            charOffset = offset,
            updatedAtMs = updatedAtEpochMs.coerceAtLeast(0L),
        )
    }

    private fun DesktopProgressShardDto.toDomainOrNull(): DeviceProgressShard? = runCatching {
        DeviceProgressShard(
            workId = WorkId(uid),
            deviceId = DeviceId(deviceId),
            cursor = ReadingAnchor.BlockOffset(
                ordinal = ChapterOrdinal(ordinal),
                blockId = BlockId(blockId),
                charStart = charOffset,
                charEnd = charOffset,
                snippet = "",
            ),
            updatedAtEpochMs = updatedAtMs,
        )
    }.getOrNull()

    private data class HeadProbe(val etag: String?, val contentLength: Long?)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val WORK_ID_REGEX = Regex("^[A-Za-z0-9_-]+$")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .proxySelector(CleartextGuardProxySelector)
            .addNetworkInterceptor { chain ->
                SyncEndpointUrlPolicy.checkTransportAllowed(chain.request().url)
                chain.proceed(chain.request())
            }
            .build()

        private object CleartextGuardProxySelector : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                if (uri != null) {
                    SyncEndpointUrlPolicy.checkTransportAllowed(uri)
                }
                return getDefault()?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                getDefault()?.connectFailed(uri, sa, ioe)
            }
        }
    }
}
