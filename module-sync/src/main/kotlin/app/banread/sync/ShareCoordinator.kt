package app.banread.sync

import android.content.Context
import android.net.Uri
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.ChapterOrdinal
import app.banread.model.CompanionEngine
import app.banread.model.DeviceProgressShard
import app.banread.model.LibraryVault
import app.banread.model.NoteLogEntry
import app.banread.model.ReaderEngine
import app.banread.model.SecretVaultPort
import app.banread.model.SharedCompanionArtifact
import app.banread.model.SharedCompanionPayload
import app.banread.model.SharedQaTurn
import app.banread.model.WorkId
import app.banread.sync.internal.EphemeralLanHttpServer
import app.banread.sync.internal.ShareAesGcmCrypto
import app.banread.sync.internal.SyncConfigStore
import app.banread.sync.internal.SyncEndpointUrlPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
internal data class ShareCompanionBundle(
    val schemaVersion: Int = 1,
    val artifacts: List<SharedCompanionArtifact> = emptyList(),
    val notes: List<NoteLogEntry> = emptyList(),
    val qaTurns: List<SharedQaTurn> = emptyList(),
    val progress: DeviceProgressShard? = null,
)

/**
 * Unified SHA-256 Manifest 3-Channel Share Engine:
 * 1. Channel 1 — Cloudflare R2 / Worker 24h E2EE Relay (AES-256-GCM; key ONLY in `#k=` fragment).
 * 2. Channel 2 — LAN Direct Transfer (ephemeral local HTTP server with 6-digit pairing code).
 * 3. Channel 3 — Offline Package (`.bcnote` KB-scale companion package or `.bcshare` MB-scale full book + companion).
 *
 * Receiver compares `bookSourceSha256` against local [LibraryVault] first:
 * same SHA-256 skips book binary download completely and merges companion assets losslessly.
 */
class ShareCoordinator internal constructor(
    private val context: Context?,
    private val filesRoot: File,
    private val libraryVault: LibraryVault,
    private val readerEngine: ReaderEngine,
    private val companionEngine: CompanionEngine,
    private val secretVault: SecretVaultPort,
    private val configStore: SyncConfigStore,
    private val httpClient: OkHttpClient = HttpDesktopGatewayAdapter.defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val secureRandom = SecureRandom()

    @Volatile
    private var activeLanServer: EphemeralLanHttpServer? = null

    @Volatile
    private var activeLanSession: LanShareSession? = null

    suspend fun configureR2Relay(relayBaseUrl: String, uploadToken: String? = null) {
        val cleanUrl = SyncEndpointUrlPolicy.normalizeHttpBaseUrl(relayBaseUrl)
        if (!uploadToken.isNullOrBlank()) {
            secretVault.putSecret(SyncConfigStore.ALIAS_R2_RELAY_TOKEN, uploadToken.trim())
        }
        configStore.update { it.copy(r2RelayBaseUrl = cleanUrl) }
    }

    suspend fun clearR2Relay() {
        secretVault.deleteSecret(SyncConfigStore.ALIAS_R2_RELAY_TOKEN)
        configStore.update { it.copy(r2RelayBaseUrl = null) }
    }

    suspend fun currentR2RelayConfig(): R2RelayConfig? {
        val hasToken = !secretVault.getSecret(SyncConfigStore.ALIAS_R2_RELAY_TOKEN).isNullOrBlank()
        return configStore.r2RelayConfigOrNull(hasToken)
    }

    suspend fun estimateShareManifest(
        workId: WorkId,
        selection: ShareScopeSelection,
    ): ShareManifest {
        val prepared = prepareShareData(workId, selection, shareId = generateShareId())
        return prepared.manifest
    }

    // ==================== Channel 1: Cross-Network R2 / Worker 24h E2EE Relay ====================

    suspend fun sendViaR2Relay(
        workId: WorkId,
        selection: ShareScopeSelection,
        shareIdOverride: String? = null,
    ): Result<ShareSendResult> = withContext(ioDispatcher) {
        runCatching {
            val rawRelayBaseUrl = configStore.read().r2RelayBaseUrl?.takeIf { it.isNotBlank() }
                ?: throw AppFailureException(
                    AppFailure.NetworkOrSyncConflict("未配置 R2 / Worker 中转端点，请改用微信 .bcnote 离线包或先配置 R2 地址"),
                )
            val relayBaseUrl = SyncEndpointUrlPolicy.normalizeHttpBaseUrl(rawRelayBaseUrl)
            val uploadToken = secretVault.getSecret(SyncConfigStore.ALIAS_R2_RELAY_TOKEN)?.takeIf { it.isNotBlank() }
            val shareId = shareIdOverride?.takeIf { it.isNotBlank() } ?: generateShareId()
            val prepared = prepareShareData(workId, selection, shareId)

            val aesKey = ShareAesGcmCrypto.generateKey()
            val keyFrag = ShareAesGcmCrypto.encodeKeyBase64Url(aesKey)

            val encManifest = ShareAesGcmCrypto.encrypt(prepared.manifestJsonBytes, aesKey, shareId, "manifest")
            val encCompanion = ShareAesGcmCrypto.encrypt(prepared.companionJsonBytes, aesKey, shareId, "companion")

            var totalUploaded = 0L
            uploadRelayPart("$relayBaseUrl/s/$shareId/manifest", encManifest, uploadToken)
            totalUploaded += encManifest.size
            uploadRelayPart("$relayBaseUrl/s/$shareId/companion", encCompanion, uploadToken)
            totalUploaded += encCompanion.size

            if (selection.includeBookFile) {
                val bookFile = prepared.bookFile
                    ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("找不到原书文件：${workId.raw}"))
                val encBook = ShareAesGcmCrypto.encrypt(bookFile.readBytes(), aesKey, shareId, "book")
                uploadRelayPart("$relayBaseUrl/s/$shareId/book", encBook, uploadToken)
                totalUploaded += encBook.size
            }

            val shareUrl = "${relayBaseUrl.trimEnd('/')}/s/$shareId#k=$keyFrag"
            configStore.update {
                it.copy(lastSyncSummary = "R2 E2EE 已上传《${prepared.manifest.title}》(${formatSize(totalUploaded)})")
            }
            ShareSendResult(
                channel = ShareChannel.R2_RELAY,
                manifest = prepared.manifest,
                shareUrl = shareUrl,
                uploadedBytes = totalUploaded,
            )
        }
    }

    // ==================== Channel 2: LAN Direct Transfer ====================

    suspend fun startLanShareServer(
        workId: WorkId,
        selection: ShareScopeSelection,
        bindHost: String = "0.0.0.0",
        preferredPort: Int = 9721,
    ): Result<LanShareSession> = withContext(ioDispatcher) {
        runCatching {
            stopLanShareServer()
            val shareId = generateShareId()
            val prepared = prepareShareData(workId, selection, shareId)
            val pairingCode = "%06d".format(secureRandom.nextInt(1_000_000))
            val formattedCode = "${pairingCode.substring(0, 3)}-${pairingCode.substring(3)}"

            val server = EphemeralLanHttpServer(
                manifestJsonBytes = prepared.manifestJsonBytes,
                companionJsonBytes = prepared.companionJsonBytes,
                bookFile = if (selection.includeBookFile) prepared.bookFile else null,
                pairingCode = pairingCode,
                bindHost = bindHost,
                preferredPort = preferredPort,
            )
            activeLanServer = server
            val baseUrl = "http://${server.hostForShareUrl}:${server.port}"
            val session = LanShareSession(
                shareId = shareId,
                baseUrl = "$baseUrl/share/v1?code=$pairingCode&sha=${prepared.manifest.bookSourceSha256.take(8)}",
                pairingCode = formattedCode,
                manifest = prepared.manifest,
            )
            activeLanSession = session
            session
        }
    }

    fun currentLanSession(): LanShareSession? = activeLanSession

    fun stopLanShareServer() {
        activeLanServer?.close()
        activeLanServer = null
        activeLanSession = null
    }

    // ==================== Channel 3: Offline Package (.bcnote / .bcshare) ====================

    suspend fun exportOfflinePackage(
        workId: WorkId,
        selection: ShareScopeSelection,
        exportAsBcShare: Boolean,
        outputFile: File? = null,
    ): Result<ShareSendResult> = withContext(ioDispatcher) {
        runCatching {
            val effectiveSelection = selection.copy(includeBookFile = exportAsBcShare)
            val shareId = generateShareId()
            val prepared = prepareShareData(workId, effectiveSelection, shareId)
            val ext = if (exportAsBcShare) "bcshare" else "bcnote"

            val exportsDir = File(filesRoot, "share_exports").apply { mkdirs() }
            val safeTitle = prepared.manifest.title
                .replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5_-]+"), "_")
                .trim('_')
                .ifBlank { workId.raw }
                .take(36)
            val targetFile = outputFile ?: File(
                exportsDir,
                "${safeTitle}_${prepared.manifest.bookSourceSha256.take(8)}.$ext",
            )
            targetFile.parentFile?.mkdirs()

            if (exportAsBcShare && prepared.bookFile == null) {
                throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("找不到原书文件，无法导出 .bcshare"))
            }

            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp.${UUID.randomUUID().toString().take(8)}")
            ZipOutputStream(FileOutputStream(tmpFile)).use { zos ->
                zos.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                zos.write(prepared.manifestJsonBytes)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(ENTRY_COMPANION))
                zos.write(prepared.companionJsonBytes)
                zos.closeEntry()

                if (exportAsBcShare) {
                    val bf = prepared.bookFile!!
                    zos.putNextEntry(ZipEntry("book/${prepared.manifest.bookFileName}"))
                    bf.inputStream().buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }

            configStore.update {
                it.copy(lastSyncSummary = "已导出《${prepared.manifest.title}》.$ext (${formatSize(targetFile.length())})")
            }

            ShareSendResult(
                channel = ShareChannel.OFFLINE_PACKAGE,
                manifest = prepared.manifest,
                packageFilePath = targetFile.absolutePath,
                uploadedBytes = targetFile.length(),
            )
        }
    }

    // ==================== Unified Receiver (Preview & Lossless Confirm Import) ====================

    suspend fun previewIncomingShare(
        sourceInput: String,
        lanCodeOverride: String? = null,
    ): Result<SharePreview> = withContext(ioDispatcher) {
        runCatching {
            val trimmed = sourceInput.trim()
            if (trimmed.isEmpty()) {
                throw AppFailureException(AppFailure.NetworkOrSyncConflict("请输入分享链接或选择 .bcnote / .bcshare 文件"))
            }

            val channel = detectIncomingChannel(trimmed)
            val manifest = when (channel) {
                ShareChannel.R2_RELAY -> fetchR2ManifestOnly(trimmed)
                ShareChannel.LAN_DIRECT -> fetchLanManifestOnly(trimmed, lanCodeOverride)
                ShareChannel.OFFLINE_PACKAGE -> readOfflinePackageManifestOnly(trimmed)
            }

            val localWorks = libraryVault.listWorks()
            val matchedWork = localWorks.firstOrNull { work ->
                !work.bookSourceSha256.isNullOrBlank() &&
                    work.bookSourceSha256.equals(manifest.bookSourceSha256, ignoreCase = true)
            }

            SharePreview(
                channel = channel,
                sourceInput = trimmed,
                manifest = manifest,
                matchedLocalWorkId = matchedWork?.workId,
                matchedLocalTitle = matchedWork?.title,
                willSkipBookDownload = matchedWork != null,
            )
        }
    }

    suspend fun confirmReceiveShare(
        preview: SharePreview,
        lanCodeOverride: String? = null,
    ): Result<ShareReceiveResult> = withContext(ioDispatcher) {
        runCatching {
            val manifest = preview.manifest
            val tempDir = File(filesRoot, "tmp/share_rx_${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val (bundle, downloadedBookFile) = when (preview.channel) {
                    ShareChannel.R2_RELAY -> fetchR2Payload(
                        rawLink = preview.sourceInput,
                        manifest = manifest,
                        skipBook = preview.willSkipBookDownload,
                        tempDir = tempDir,
                    )
                    ShareChannel.LAN_DIRECT -> fetchLanPayload(
                        rawUrl = preview.sourceInput,
                        codeOverride = lanCodeOverride,
                        manifest = manifest,
                        skipBook = preview.willSkipBookDownload,
                        tempDir = tempDir,
                    )
                    ShareChannel.OFFLINE_PACKAGE -> readOfflinePackagePayload(
                        uriOrPath = preview.sourceInput,
                        manifest = manifest,
                        skipBook = preview.willSkipBookDownload,
                        tempDir = tempDir,
                    )
                }

                // Determine target WorkId: existing local match skips book import completely!
                val targetWorkId: WorkId = if (preview.matchedLocalWorkId != null) {
                    preview.matchedLocalWorkId
                } else {
                    val bookFile = downloadedBookFile
                        ?: throw AppFailureException(
                            AppFailure.NetworkOrSyncConflict(
                                "本地无同 SHA-256 原书，且分享包未附带原书（请导入原书或改用 .bcshare / 含原书分享）",
                            ),
                        )
                    val imported = if (manifest.bookFormat.equals("BCBOOK", ignoreCase = true) ||
                        bookFile.name.endsWith(".bcbook", ignoreCase = true)
                    ) {
                        libraryVault.ingestStudyPack(bookFile.absolutePath).getOrThrow()
                    } else {
                        libraryVault.importLocalBook(bookFile.absolutePath).getOrThrow()
                    }
                    imported.workId
                }

                // Merge companion artifacts & Q&A losslessly
                val companionResult = companionEngine.importShareCompanionData(
                    workId = targetWorkId,
                    payload = SharedCompanionPayload(
                        artifacts = bundle.artifacts,
                        qaTurns = bundle.qaTurns,
                    ),
                )

                // Merge notes losslessly (preserving existing local notes)
                val normalizedNotes = bundle.notes.map { it.copy(workId = targetWorkId) }
                readerEngine.mergeRemoteAnnotations(targetWorkId, normalizedNotes)

                configStore.update {
                    val modeText = if (preview.willSkipBookDownload) "同 SHA 跳过原书" else "新书入库"
                    it.copy(lastSyncSummary = "已接收《${manifest.title}》($modeText)")
                }

                ShareReceiveResult(
                    channel = preview.channel,
                    targetWorkId = targetWorkId,
                    title = manifest.title,
                    skippedBookBinary = preview.willSkipBookDownload,
                    importedArtifacts = companionResult.importedArtifacts,
                    importedNotes = normalizedNotes.count { it.deletedAtEpochMs == null },
                    importedQaTurns = companionResult.importedQaTurns,
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    override fun close() {
        stopLanShareServer()
    }

    // ==================== Internal Helpers ====================

    private suspend fun prepareShareData(
        workId: WorkId,
        selection: ShareScopeSelection,
        shareId: String,
    ): PreparedShareData {
        val summary = libraryVault.getWorkSummary(workId)
            ?: throw NoSuchElementException("Work not found: ${workId.raw}")
        val bookFile = libraryVault.resolveOriginalBookFile(workId)
        val sha = summary.bookSourceSha256?.takeIf { it.length == 64 }
            ?: bookFile?.let { sha256Hex(it) }
            ?: "0".repeat(64)

        val companionPayload = companionEngine.exportShareCompanionData(
            workId = workId,
            includeMaps = selection.includeMaps,
            includeQa = selection.includeQa,
        )
        val notes = if (selection.includeNotes) {
            readerEngine.listNoteLogEntries(workId, includeDeleted = false)
        } else {
            emptyList()
        }
        val progress = if (selection.includeProgress) {
            readerEngine.snapshotProgress(workId)
        } else {
            null
        }

        val bundle = ShareCompanionBundle(
            schemaVersion = 1,
            artifacts = companionPayload.artifacts,
            notes = notes,
            qaTurns = companionPayload.qaTurns,
            progress = progress,
        )
        val companionBytes = json.encodeToString(ShareCompanionBundle.serializer(), bundle).toByteArray(Charsets.UTF_8)

        val defaultFileName = bookFile?.name ?: "${workId.raw}.${summary.format.lowercase()}"
        val bookSize = bookFile?.length() ?: 0L

        val manifest = ShareManifest(
            schemaVersion = 1,
            shareId = shareId,
            bookSourceSha256 = sha,
            workId = workId,
            title = summary.title,
            author = summary.author,
            readProfileId = summary.readProfileId,
            literatureSubKind = summary.literatureSubKind,
            clothColor = summary.clothColor,
            bookFormat = summary.format,
            bookFileName = defaultFileName,
            bookSizeBytes = bookSize,
            companionSizeBytes = companionBytes.size.toLong(),
            includeMaps = selection.includeMaps,
            includeNotes = selection.includeNotes,
            includeQa = selection.includeQa,
            includeProgress = selection.includeProgress,
            includeBookFile = selection.includeBookFile,
            mapCount = companionPayload.artifacts.size,
            noteCount = notes.size,
            qaCount = companionPayload.qaTurns.map { it.threadId }.distinct().size,
            senderDeviceId = configStore.currentDeviceId(),
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val manifestBytes = json.encodeToString(ShareManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
        return PreparedShareData(
            manifest = manifest,
            manifestJsonBytes = manifestBytes,
            companionJsonBytes = companionBytes,
            bookFile = bookFile,
        )
    }

    private fun uploadRelayPart(url: String, bytes: ByteArray, uploadToken: String?) {
        val builder = Request.Builder()
            .url(url)
            .put(bytes.toRequestBody(OCTET_MEDIA))
        if (!uploadToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $uploadToken")
        }
        val response = try {
            httpClient.newCall(builder.build()).execute()
        } catch (ex: IOException) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("R2 中转上传失败：${ex.message ?: "IO error"}"))
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw AppFailureException(
                    AppFailure.NetworkOrSyncConflict("R2 中转上传被拒绝 (HTTP ${resp.code}): ${resp.message}"),
                )
            }
        }
    }

    private fun fetchR2ManifestOnly(rawLink: String): ShareManifest {
        val parsed = ShareAesGcmCrypto.parseShareLink(rawLink)
        val encManifest = httpGetBytes("${parsed.fetchBaseUrl}/manifest", headers = emptyMap())
        val plain = ShareAesGcmCrypto.decrypt(encManifest, parsed.keyBytes, parsed.shareId, "manifest")
        return json.decodeFromString(ShareManifest.serializer(), plain.toString(Charsets.UTF_8))
    }

    private fun fetchR2Payload(
        rawLink: String,
        manifest: ShareManifest,
        skipBook: Boolean,
        tempDir: File,
    ): Pair<ShareCompanionBundle, File?> {
        val parsed = ShareAesGcmCrypto.parseShareLink(rawLink)
        val encCompanion = httpGetBytes("${parsed.fetchBaseUrl}/companion", headers = emptyMap())
        val plainCompanion = ShareAesGcmCrypto.decrypt(encCompanion, parsed.keyBytes, parsed.shareId, "companion")
        val bundle = json.decodeFromString(ShareCompanionBundle.serializer(), plainCompanion.toString(Charsets.UTF_8))

        var bookFile: File? = null
        if (!skipBook && manifest.includeBookFile) {
            val encBook = httpGetBytes("${parsed.fetchBaseUrl}/book", headers = emptyMap())
            val plainBook = ShareAesGcmCrypto.decrypt(encBook, parsed.keyBytes, parsed.shareId, "book")
            val safeName = sanitizeFileName(manifest.bookFileName.ifBlank { "${manifest.workId.raw}.bcbook" })
            bookFile = File(tempDir, safeName).apply { writeBytes(plainBook) }
        }
        return bundle to bookFile
    }

    private fun fetchLanManifestOnly(rawUrl: String, codeOverride: String?): ShareManifest {
        val (base, code) = parseLanUrlAndCode(rawUrl, codeOverride)
        val headers = mapOf("X-BanRead-Code" to code)
        val bytes = httpGetBytes("$base/share/v1/manifest", headers)
        return json.decodeFromString(ShareManifest.serializer(), bytes.toString(Charsets.UTF_8))
    }

    private fun fetchLanPayload(
        rawUrl: String,
        codeOverride: String?,
        manifest: ShareManifest,
        skipBook: Boolean,
        tempDir: File,
    ): Pair<ShareCompanionBundle, File?> {
        val (base, code) = parseLanUrlAndCode(rawUrl, codeOverride)
        val headers = mapOf("X-BanRead-Code" to code)
        val companionBytes = httpGetBytes("$base/share/v1/companion", headers)
        val bundle = json.decodeFromString(ShareCompanionBundle.serializer(), companionBytes.toString(Charsets.UTF_8))

        var bookFile: File? = null
        if (!skipBook && manifest.includeBookFile) {
            val safeName = sanitizeFileName(manifest.bookFileName.ifBlank { "${manifest.workId.raw}.bcbook" })
            val dest = File(tempDir, safeName)
            httpGetToFile("$base/share/v1/book", headers, dest)
            bookFile = dest
        }
        return bundle to bookFile
    }

    private fun readOfflinePackageManifestOnly(uriOrPath: String): ShareManifest {
        val localZip = copyShareSourceToTemp(uriOrPath)
        try {
            ZipFile(localZip).use { zf ->
                val entry = zf.getEntry(ENTRY_MANIFEST)
                    ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("离线包缺少 $ENTRY_MANIFEST"))
                val text = zf.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                return json.decodeFromString(ShareManifest.serializer(), text)
            }
        } finally {
            if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")) {
                localZip.delete()
            }
        }
    }

    private fun readOfflinePackagePayload(
        uriOrPath: String,
        manifest: ShareManifest,
        skipBook: Boolean,
        tempDir: File,
    ): Pair<ShareCompanionBundle, File?> {
        val localZip = copyShareSourceToTemp(uriOrPath)
        try {
            ZipFile(localZip).use { zf ->
                val compEntry = zf.getEntry(ENTRY_COMPANION)
                    ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("离线包缺少 $ENTRY_COMPANION"))
                val compText = zf.getInputStream(compEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val bundle = json.decodeFromString(ShareCompanionBundle.serializer(), compText)

                var bookFile: File? = null
                if (!skipBook && manifest.includeBookFile) {
                    val bookEntry = zf.entries().asSequence().firstOrNull { e ->
                        !e.isDirectory && e.name.startsWith("book/") && !e.name.contains("..")
                    }
                    if (bookEntry != null) {
                        val safeName = sanitizeFileName(File(bookEntry.name).name)
                        val dest = File(tempDir, safeName)
                        zf.getInputStream(bookEntry).use { input ->
                            FileOutputStream(dest).use { out -> input.copyTo(out) }
                        }
                        bookFile = dest
                    }
                }
                return bundle to bookFile
            }
        } finally {
            if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")) {
                localZip.delete()
            }
        }
    }

    private fun copyShareSourceToTemp(uriOrPath: String): File {
        if (uriOrPath.startsWith("content://")) {
            val ctx = context ?: throw IllegalStateException("Context required for content:// URI")
            val tmp = File(filesRoot, "tmp/share_pkg_${UUID.randomUUID()}.zip").apply { parentFile?.mkdirs() }
            val stream = ctx.contentResolver.openInputStream(Uri.parse(uriOrPath))
                ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("无法读取文件：$uriOrPath"))
            stream.use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out) } }
            return tmp
        }
        val path = if (uriOrPath.startsWith("file://")) URI(uriOrPath).path else uriOrPath
        val file = File(path)
        if (!file.isFile) {
            throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("找不到离线包文件：$uriOrPath"))
        }
        return file
    }

    private fun httpGetBytes(url: String, headers: Map<String, String>): ByteArray {
        val req = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        val response = try {
            httpClient.newCall(req).execute()
        } catch (ex: IOException) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("网络请求失败：${ex.message ?: "IO error"}"))
        }
        return response.use { resp ->
            when {
                resp.code == 401 -> throw AppFailureException(AppFailure.NetworkOrSyncConflict("配对码或权限校验失败 (401)"))
                resp.code == 404 -> throw AppFailureException(AppFailure.NetworkOrSyncConflict("分享内容已过期或不存在 (404)"))
                !resp.isSuccessful -> throw AppFailureException(
                    AppFailure.NetworkOrSyncConflict("获取分享内容失败 (HTTP ${resp.code})"),
                )
                else -> resp.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    private fun httpGetToFile(url: String, headers: Map<String, String>, dest: File) {
        val req = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        val response = try {
            httpClient.newCall(req).execute()
        } catch (ex: IOException) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("局域网传输中断：${ex.message ?: "IO error"}"))
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw AppFailureException(AppFailure.NetworkOrSyncConflict("下载原书失败 (HTTP ${resp.code})"))
            }
            val body = resp.body ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("Empty book response"))
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
        }
    }

    private fun parseLanUrlAndCode(rawUrl: String, codeOverride: String?): Pair<String, String> {
        val clean = rawUrl.trim()
        val httpUrl = clean.toHttpUrlOrNull()
            ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("局域网链接格式无效：$rawUrl"))
        val codeFromQuery = httpUrl.queryParameter("code").orEmpty()
        val effectiveCode = EphemeralLanHttpServer.normalizePairingCode(
            codeOverride?.takeIf { it.isNotBlank() } ?: codeFromQuery,
        )
        if (effectiveCode.isEmpty()) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("局域网接收需要 6 位配对码"))
        }
        val hostPart = if (':' in httpUrl.host) "[${httpUrl.host}]" else httpUrl.host
        val base = SyncEndpointUrlPolicy.normalizeHttpBaseUrl("${httpUrl.scheme}://$hostPart:${httpUrl.port}")
        return base to effectiveCode
    }

    private fun detectIncomingChannel(input: String): ShareChannel {
        val lower = input.lowercase()
        return when {
            lower.startsWith("content://") || lower.startsWith("file://") ||
                lower.endsWith(".bcnote") || lower.endsWith(".bcshare") -> ShareChannel.OFFLINE_PACKAGE
            "#k=" in input -> ShareChannel.R2_RELAY
            "/share/v1" in lower || "code=" in lower -> ShareChannel.LAN_DIRECT
            else -> {
                val file = File(input)
                if (file.isFile) ShareChannel.OFFLINE_PACKAGE
                else throw AppFailureException(AppFailure.NetworkOrSyncConflict("无法识别的分享链接或文件格式：$input"))
            }
        }
    }

    private fun generateShareId(): String =
        "br_" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun sanitizeFileName(name: String): String =
        File(name).name.replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "_").ifBlank { "book.bcbook" }

    private fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${maxOf(1L, bytes / 1024L)} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private data class PreparedShareData(
        val manifest: ShareManifest,
        val manifestJsonBytes: ByteArray,
        val companionJsonBytes: ByteArray,
        val bookFile: File?,
    )

    companion object {
        private const val ENTRY_MANIFEST = "share_manifest.json"
        private const val ENTRY_COMPANION = "companion.json"
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()
    }
}
