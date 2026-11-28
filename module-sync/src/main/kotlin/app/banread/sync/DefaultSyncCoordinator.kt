package app.banread.sync

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.BlockId
import app.banread.model.BundleTransferState
import app.banread.model.ChapterOrdinal
import app.banread.model.CompanionEngine
import app.banread.model.CrossBookHit
import app.banread.model.DesktopBookSummary
import app.banread.model.DesktopBridgePort
import app.banread.model.DesktopEndpointInfo
import app.banread.model.DeviceId
import app.banread.model.DeviceProgressShard
import app.banread.model.LibraryVault
import app.banread.model.NoteLogEntry
import app.banread.model.OutboxTicket
import app.banread.model.ReaderEngine
import app.banread.model.ReadingAnchor
import app.banread.model.RemoteCursorBanner
import app.banread.model.SecretVaultPort
import app.banread.model.StateSyncPort
import app.banread.model.SyncCoordinator
import app.banread.model.SyncMergeOutcome
import app.banread.model.WorkId
import app.banread.sync.internal.NoOpSyncWorkScheduler
import app.banread.sync.internal.PendingSyncOp
import app.banread.sync.internal.PendingSyncQueue
import app.banread.sync.internal.SyncConfigStore
import app.banread.sync.internal.SyncEndpointUrlPolicy
import app.banread.sync.internal.SyncWorkScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Deep-module coordinator for `:module-sync`:
 * - Desktop `--mobile-bind` gateway pairing, catalog, outbox pull (resumable + verified ACK), and cross-book search.
 * - Single-writer per-device shard sync (`progress/<uid>/<deviceId>.json`, `notes/<uid>/<deviceId>.jsonl`).
 * - Tombstone-preserving note merge, non-intrusive [RemoteCursorBanner],
 *   and WorkManager offline retry queue.
 */
class DefaultSyncCoordinator internal constructor(
    private val filesRoot: File,
    private val libraryVault: LibraryVault,
    private val readerEngine: ReaderEngine,
    private val companionEngineProvider: () -> CompanionEngine?,
    private val secretVault: SecretVaultPort,
    private val desktopBridge: DesktopBridgePort,
    private val configStore: SyncConfigStore,
    private val pendingQueue: PendingSyncQueue,
    private val workScheduler: SyncWorkScheduler = NoOpSyncWorkScheduler,
    private val customStateSyncPort: StateSyncPort? = null,
    private val httpClient: OkHttpClient = HttpDesktopGatewayAdapter.defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncCoordinator {

    private val reconcileMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun currentDeviceId(): DeviceId = configStore.currentDeviceId()

    suspend fun getSyncStatus(): SyncStatusSnapshot {
        val cfg = configStore.read()
        val hasDesktopToken = !secretVault.getSecret(SyncConfigStore.ALIAS_DESKTOP_TOKEN).isNullOrBlank()
        val hasCloudSecret = when (cfg.cloudBackend) {
            CloudSyncBackend.NONE -> false
            CloudSyncBackend.WEBDAV -> !secretVault.getSecret(SyncConfigStore.ALIAS_WEBDAV_SECRET).isNullOrBlank()
            CloudSyncBackend.S3_R2 -> !secretVault.getSecret(SyncConfigStore.ALIAS_S3R2_SECRET).isNullOrBlank()
        }
        val hasR2RelayToken = !secretVault.getSecret(SyncConfigStore.ALIAS_R2_RELAY_TOKEN).isNullOrBlank()
        return SyncStatusSnapshot(
            deviceId = DeviceId(cfg.deviceId),
            desktop = DesktopPairState(
                isPaired = !cfg.desktopBaseUrl.isNullOrBlank() && hasDesktopToken,
                baseUrl = cfg.desktopBaseUrl,
                pairedDeviceId = DeviceId(cfg.deviceId),
                isReachable = null,
            ),
            cloudBackend = cfg.cloudBackend,
            webDavConfig = cfg.webDavConfig,
            s3R2Config = cfg.s3R2Config,
            hasCloudSecret = hasCloudSecret || customStateSyncPort != null,
            r2RelayConfig = configStore.r2RelayConfigOrNull(hasR2RelayToken),
            pendingOpCount = pendingQueue.count(),
            lastSyncSummary = cfg.lastSyncSummary,
        )
    }

    override suspend fun pairDesktopViaQr(qrPayload: String): Result<DesktopEndpointInfo> = runCatching {
        val parsed = PairingUriParser.parse(qrPayload)
        secretVault.putSecret(SyncConfigStore.ALIAS_DESKTOP_TOKEN, parsed.token)
        val devId = configStore.currentDeviceId()
        configStore.update { cfg ->
            cfg.copy(
                desktopBaseUrl = parsed.baseUrl,
                desktopPairedAtMs = System.currentTimeMillis(),
                lastSyncSummary = "已配对桌面网关：${parsed.baseUrl}",
            )
        }
        DesktopEndpointInfo(
            baseUrl = parsed.baseUrl,
            pairedDeviceId = devId,
        )
    }

    suspend fun unpairDesktop() {
        secretVault.deleteSecret(SyncConfigStore.ALIAS_DESKTOP_TOKEN)
        configStore.update { cfg ->
            cfg.copy(
                desktopBaseUrl = null,
                desktopPairedAtMs = null,
                lastSyncSummary = "已解除桌面网关配对",
            )
        }
    }

    suspend fun checkDesktopHealth(): Result<Boolean> = runCatching {
        val (baseUrl, token) = requirePairedDesktop("desktop_health")
        desktopBridge.checkHealth(token, baseUrl)
    }

    override suspend fun fetchDesktopCatalog(
        query: String?,
        onlyWithCache: Boolean,
    ): Result<List<DesktopBookSummary>> = runCatching {
        val (baseUrl, token) = requirePairedDesktop("desktop_catalog")
        desktopBridge.fetchCatalog(token, baseUrl, query, onlyWithCache)
    }

    override suspend fun fetchDesktopOutbox(): Result<List<OutboxTicket>> = runCatching {
        val (baseUrl, token) = requirePairedDesktop("desktop_outbox")
        desktopBridge.fetchOutbox(token, baseUrl)
    }

    override fun pullStudyPacks(workIds: List<WorkId>): Flow<BundleTransferState> = flow {
        if (workIds.isEmpty()) return@flow
        val pair = runCatching { requirePairedDesktop("pull_study_packs") }
        if (pair.isFailure) {
            val failure = (pair.exceptionOrNull() as? AppFailureException)?.failure
                ?: AppFailure.DesktopConnectionRequired("pull_study_packs")
            workIds.forEach { emit(BundleTransferState.Failed(it, failure)) }
            return@flow
        }
        val (baseUrl, token) = pair.getOrThrow()

        val outboxTickets = runCatching { desktopBridge.fetchOutbox(token, baseUrl) }.getOrDefault(emptyList())
        val downloadsDir = File(filesRoot, "sync/downloads").apply { mkdirs() }

        for (workId in workIds) {
            val matchingTickets = outboxTickets.filter { it.uid == workId }
            val includeCache = matchingTickets.firstOrNull()?.includeCache ?: true
            val partFile = File(downloadsDir, "${workId.raw}.bcbook.part")
            val etagFile = File(downloadsDir, "${partFile.name}.etag")
            val finalFile = File(downloadsDir, "${workId.raw}.bcbook")
            val resumeOffset = if (partFile.isFile) partFile.length() else 0L

            try {
                emit(BundleTransferState.Downloading(workId, resumeOffset, null))
                val stream = if (desktopBridge is HttpDesktopGatewayAdapter) {
                    desktopBridge.downloadBundleStreamWithCacheFlag(
                        token = token,
                        baseUrl = baseUrl,
                        workId = workId,
                        destFile = partFile.absolutePath,
                        resumeOffset = resumeOffset,
                        includeCache = includeCache,
                    )
                } else {
                    desktopBridge.downloadBundleStream(
                        token = token,
                        baseUrl = baseUrl,
                        workId = workId,
                        destFile = partFile.absolutePath,
                        resumeOffset = resumeOffset,
                    )
                }
                stream.collect { received ->
                    emit(BundleTransferState.Downloading(workId, received, null))
                }

                moveFileAtomically(partFile, finalFile)
                etagFile.delete()

                val imported = libraryVault.ingestStudyPack(finalFile.absolutePath).getOrThrow()

                // Inherit desktop companion_cache if present in study pack unpack output
                val cacheDir = File(filesRoot, "study_packs/${imported.workId.raw}/companion_cache")
                val comp = companionEngineProvider()
                if (cacheDir.isDirectory && comp != null) {
                    comp.importDesktopPrecomputedCache(imported.workId, cacheDir.absolutePath)
                }

                // Preserve cursor and annotations if a local EPUB/MOBI was superseded
                imported.supersededWorkId?.let { oldWorkId ->
                    readerEngine.migrateWorkState(oldWorkId, imported.workId)
                }

                // ACK ONLY after verified local ingestion succeeds
                if (matchingTickets.isNotEmpty()) {
                    desktopBridge.ackOutbox(token, baseUrl, matchingTickets.map { it.ticketId })
                }

                configStore.update { it.copy(lastSyncSummary = "已拉取并入库《${imported.title}》") }
                emit(BundleTransferState.Ingested(workId))
            } catch (ex: Throwable) {
                val failure = (ex as? AppFailureException)?.failure
                    ?: AppFailure.NetworkOrSyncConflict(ex.message ?: "拉取失败")
                if (failure is AppFailure.NetworkOrSyncConflict || failure is AppFailure.DesktopConnectionRequired) {
                    pendingQueue.enqueuePullStudyPack(workId, includeCache = includeCache)
                    workScheduler.schedulePendingFlush()
                }
                emit(BundleTransferState.Failed(workId, failure))
            }
        }
    }.flowOn(ioDispatcher)

    override suspend fun reconcileWork(workId: WorkId): Result<SyncMergeOutcome> = reconcileMutex.withLock {
        runCatching {
            val localDevice = currentDeviceId()
            val cloudPort = resolveStateSyncPort()
            val pairedDesktop = getPairedDesktopOrNull()

            val localProgress = readerEngine.snapshotProgress(workId)
            val localNotes = readerEngine.listNoteLogEntries(workId, includeDeleted = true)

            // If no remote transport is configured at all, return clean local snapshot
            if (cloudPort == null && pairedDesktop == null) {
                return@runCatching SyncMergeOutcome(
                    workId = workId,
                    mergedNoteCount = localNotes.count { it.deletedAtEpochMs == null },
                    remoteCursorBanner = null,
                )
            }

            // 1. Push ONLY this device's shards (single-writer ownership)
            if (cloudPort != null) {
                if (localProgress != null) {
                    val shardPath = "progress/${workId.raw}/${localDevice.raw}.json"
                    val shardJson = json.encodeToString(DeviceProgressShard.serializer(), localProgress)
                    cloudPort.putShard(shardPath, shardJson.toByteArray(Charsets.UTF_8))
                }
                val ownNotes = localNotes.filter { it.deviceId == localDevice }
                if (ownNotes.isNotEmpty()) {
                    val notesPath = "notes/${workId.raw}/${localDevice.raw}.jsonl"
                    val jsonl = ownNotes.joinToString("\n") {
                        json.encodeToString(NoteLogEntry.serializer(), it)
                    } + "\n"
                    cloudPort.putShard(notesPath, jsonl.toByteArray(Charsets.UTF_8))
                }
            }

            if (pairedDesktop != null && localProgress != null) {
                runCatching {
                    desktopBridge.pushProgressShard(pairedDesktop.second, pairedDesktop.first, localProgress)
                }.onFailure { err ->
                    if (cloudPort == null) throw err
                    // Desktop might be asleep while cloud shard succeeded; queue desktop retry silently
                    pendingQueue.enqueueReconcile(workId)
                    workScheduler.schedulePendingFlush()
                }
            }

            // 2. Pull all device progress shards
            val allRemoteProgress = mutableListOf<DeviceProgressShard>()
            if (cloudPort != null) {
                val keys = cloudPort.listShards("progress/${workId.raw}/")
                for (key in keys) {
                    val bytes = cloudPort.getShard(key) ?: continue
                    runCatching {
                        json.decodeFromString(DeviceProgressShard.serializer(), bytes.toString(Charsets.UTF_8))
                    }.getOrNull()?.let { shard ->
                        allRemoteProgress += shard
                    }
                }
            }
            if (pairedDesktop != null) {
                runCatching {
                    desktopBridge.fetchProgressShards(pairedDesktop.second, pairedDesktop.first, workId)
                }.getOrNull()?.let { desktopShards ->
                    allRemoteProgress += desktopShards
                }
            }

            // 3. Pull & merge NoteLogEntry union by noteId (latest updatedAt wins, preserving deletedAt tombstones)
            val pulledNotes = mutableListOf<NoteLogEntry>()
            if (cloudPort != null) {
                val noteKeys = cloudPort.listShards("notes/${workId.raw}/")
                for (key in noteKeys) {
                    val bytes = cloudPort.getShard(key) ?: continue
                    val lines = bytes.toString(Charsets.UTF_8).lineSequence()
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        runCatching {
                            json.decodeFromString(NoteLogEntry.serializer(), trimmed)
                        }.getOrNull()?.let { pulledNotes += it }
                    }
                }
            }
            val mergedNoteCount = readerEngine.mergeRemoteAnnotations(workId, pulledNotes)

            // 5. Non-intrusive RemoteCursorBanner ONLY when another device has a strictly newer cursor at a different location
            val newestOther = allRemoteProgress
                .filter { it.deviceId != localDevice }
                .maxByOrNull { it.updatedAtEpochMs }
            val localTs = localProgress?.updatedAtEpochMs ?: 0L
            val banner = if (newestOther != null &&
                newestOther.updatedAtEpochMs > localTs &&
                !isSameLocation(localProgress?.cursor, newestOther.cursor)
            ) {
                val detailSuffix = when (val c = newestOther.cursor) {
                    is ReadingAnchor.BlockOffset -> " ${c.blockId.value}"
                    is ReadingAnchor.ReflowCfi -> c.blockId?.let { " ${it.value}" }.orEmpty()
                    is ReadingAnchor.PdfPage -> " 第 ${c.pageIndex + 1} 页"
                }
                RemoteCursorBanner(
                    sourceDeviceId = newestOther.deviceId,
                    remoteCursor = newestOther.cursor,
                    message = "${newestOther.deviceId.raw} 刚读至第 ${newestOther.cursor.ordinal.value} 章$detailSuffix",
                )
            } else {
                null
            }

            configStore.update {
                it.copy(lastSyncSummary = "已同步 ${workId.raw} · $mergedNoteCount 条批注")
            }

            SyncMergeOutcome(
                workId = workId,
                mergedNoteCount = mergedNoteCount,
                remoteCursorBanner = banner,
            )
        }.onFailure { err ->
            val failure = (err as? AppFailureException)?.failure
            if (failure is AppFailure.NetworkOrSyncConflict || failure is AppFailure.DesktopConnectionRequired) {
                pendingQueue.enqueueReconcile(workId)
                workScheduler.schedulePendingFlush()
            }
        }
    }

    override suspend fun queryCrossBookHits(
        workId: WorkId,
        queryText: String,
        sourcePassage: String?,
    ): Result<List<CrossBookHit>> {
        val paired = getPairedDesktopOrNull()
            ?: return Result.failure(AppFailureException(AppFailure.DesktopConnectionRequired("cross_book_search")))
        return runCatching {
            desktopBridge.searchRelated(
                token = paired.second,
                baseUrl = paired.first,
                excludeWorkId = workId,
                query = queryText,
                sourceText = sourcePassage,
            )
        }.recoverCatching { err ->
            if (err is AppFailureException && err.failure is AppFailure.DesktopConnectionRequired) {
                throw err
            }
            throw AppFailureException(AppFailure.DesktopConnectionRequired("cross_book_search"))
        }
    }

    suspend fun configureWebDav(config: WebDavConfig, rawSecret: String?) {
        val normalized = SyncEndpointUrlPolicy.normalizeHttpBaseUrl(config.baseUrl)
        val cleanCfg = config.copy(baseUrl = normalized)
        if (!rawSecret.isNullOrBlank()) {
            secretVault.putSecret(SyncConfigStore.ALIAS_WEBDAV_SECRET, rawSecret.trim())
        }
        configStore.update { cfg ->
            cfg.copy(
                cloudBackend = CloudSyncBackend.WEBDAV,
                webDavConfig = cleanCfg,
                lastSyncSummary = "已启用 WebDAV 分片同步",
            )
        }
    }

    suspend fun configureS3R2(config: S3R2Config, rawSecretAccessKey: String?) {
        val normalized = SyncEndpointUrlPolicy.normalizeHttpBaseUrl(config.endpointUrl)
        require(config.bucket.isNotBlank()) { "Bucket 不能为空" }
        require(config.accessKeyId.isNotBlank()) { "Access Key ID 不能为空" }
        val cleanCfg = config.copy(endpointUrl = normalized, bucket = config.bucket.trim())
        if (!rawSecretAccessKey.isNullOrBlank()) {
            secretVault.putSecret(SyncConfigStore.ALIAS_S3R2_SECRET, rawSecretAccessKey.trim())
        }
        configStore.update { cfg ->
            cfg.copy(
                cloudBackend = CloudSyncBackend.S3_R2,
                s3R2Config = cleanCfg,
                lastSyncSummary = "已启用 S3 / R2 分片同步",
            )
        }
    }

    suspend fun disableCloudSync() {
        configStore.update { cfg ->
            cfg.copy(
                cloudBackend = CloudSyncBackend.NONE,
                lastSyncSummary = "已关闭云端分片同步",
            )
        }
    }

    fun scheduleWorkSync(workId: WorkId) {
        pendingQueue.enqueueReconcile(workId)
        workScheduler.schedulePendingFlush()
    }

    /**
     * Drains the persistent offline queue sequentially. Returns `true` if all queued operations
     * succeeded (or queue is empty), `false` if any item failed and remains queued for retry.
     */
    suspend fun flushPendingQueue(): Boolean {
        val snapshot = pendingQueue.list()
        if (snapshot.isEmpty()) return true
        var allSucceeded = true
        for (op in snapshot) {
            when (op) {
                is PendingSyncOp.ReconcileWork -> {
                    val res = reconcileWork(WorkId(op.workId))
                    if (res.isSuccess) {
                        pendingQueue.remove(op.id)
                    } else {
                        allSucceeded = false
                    }
                }
                is PendingSyncOp.PullStudyPack -> {
                    var ingested = false
                    pullStudyPacks(listOf(WorkId(op.workId))).collect { state ->
                        if (state is BundleTransferState.Ingested) ingested = true
                    }
                    if (ingested) {
                        pendingQueue.remove(op.id)
                    } else {
                        allSucceeded = false
                    }
                }
            }
        }
        return allSucceeded
    }

    suspend fun deleteAnnotationAndQueueSync(workId: WorkId, noteId: String) {
        readerEngine.deleteAnnotation(workId, noteId)
        scheduleWorkSync(workId)
    }

    private fun resolveStateSyncPort(): StateSyncPort? {
        if (customStateSyncPort != null) return customStateSyncPort
        val cfg = configStore.read()
        return when (cfg.cloudBackend) {
            CloudSyncBackend.NONE -> null
            CloudSyncBackend.WEBDAV -> cfg.webDavConfig?.let { wd ->
                WebDavShardAdapter(
                    config = wd,
                    secretVault = secretVault,
                    secretAlias = SyncConfigStore.ALIAS_WEBDAV_SECRET,
                    client = httpClient,
                )
            }
            CloudSyncBackend.S3_R2 -> cfg.s3R2Config?.let { s3 ->
                S3R2ShardAdapter(
                    config = s3,
                    secretVault = secretVault,
                    secretAlias = SyncConfigStore.ALIAS_S3R2_SECRET,
                    client = httpClient,
                )
            }
        }
    }

    private suspend fun getPairedDesktopOrNull(): Pair<String, String>? {
        val baseUrl = configStore.read().desktopBaseUrl?.takeIf { it.isNotBlank() } ?: return null
        val token = secretVault.getSecret(SyncConfigStore.ALIAS_DESKTOP_TOKEN)?.takeIf { it.isNotBlank() }
            ?: return null
        return baseUrl to token
    }

    private suspend fun requirePairedDesktop(featureName: String): Pair<String, String> =
        getPairedDesktopOrNull()
            ?: throw AppFailureException(AppFailure.DesktopConnectionRequired(featureName))

    private fun isSameLocation(local: ReadingAnchor?, remote: ReadingAnchor): Boolean {
        if (local == null || local.ordinal != remote.ordinal) return false
        val localBlk = when (local) {
            is ReadingAnchor.BlockOffset -> local.blockId.value
            is ReadingAnchor.ReflowCfi -> local.blockId?.value
            is ReadingAnchor.PdfPage -> "page:${local.pageIndex}"
        }
        val remoteBlk = when (remote) {
            is ReadingAnchor.BlockOffset -> remote.blockId.value
            is ReadingAnchor.ReflowCfi -> remote.blockId?.value
            is ReadingAnchor.PdfPage -> "page:${remote.pageIndex}"
        }
        return localBlk != null && localBlk == remoteBlk
    }

    private suspend fun moveFileAtomically(src: File, dest: File) = withContext(ioDispatcher) {
        dest.parentFile?.mkdirs()
        runCatching {
            Files.move(
                src.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
    }
}
