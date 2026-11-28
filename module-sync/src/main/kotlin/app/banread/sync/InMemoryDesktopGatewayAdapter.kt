package app.banread.sync

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.CrossBookHit
import app.banread.model.DesktopBookSummary
import app.banread.model.DesktopBridgePort
import app.banread.model.DeviceProgressShard
import app.banread.model.OutboxTicket
import app.banread.model.WorkId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory deterministic [DesktopBridgePort] adapter for contract tests and multi-device sync simulations.
 */
class InMemoryDesktopGatewayAdapter(
    var expectedToken: String = "test-desktop-token",
    var isOnline: Boolean = true,
) : DesktopBridgePort {

    val catalog = CopyOnWriteArrayList<DesktopBookSummary>()
    val outbox = CopyOnWriteArrayList<OutboxTicket>()
    val ackedTicketIds = CopyOnWriteArrayList<String>()
    val bundlesByWorkId = ConcurrentHashMap<String, ByteArray>()
    val crossSearchHitsByWorkId = ConcurrentHashMap<String, List<CrossBookHit>>()
    val progressShardsByWork = ConcurrentHashMap<String, ConcurrentHashMap<String, DeviceProgressShard>>()

    override suspend fun checkHealth(token: String, baseUrl: String): Boolean {
        ensureReachable(token)
        return true
    }

    override suspend fun fetchCatalog(token: String, baseUrl: String, query: String?): List<DesktopBookSummary> =
        fetchCatalog(token, baseUrl, query, onlyWithCache = false)

    override suspend fun fetchCatalog(
        token: String,
        baseUrl: String,
        query: String?,
        onlyWithCache: Boolean,
    ): List<DesktopBookSummary> {
        ensureReachable(token)
        val q = query?.trim()?.lowercase().orEmpty()
        return catalog.filter { book ->
            val matchesCache = !onlyWithCache || book.hasCompanionCache
            val matchesQ = q.isEmpty() ||
                book.uid.raw.lowercase().contains(q) ||
                book.title.lowercase().contains(q) ||
                book.author.lowercase().contains(q)
            matchesCache && matchesQ
        }
    }

    override suspend fun fetchOutbox(token: String, baseUrl: String): List<OutboxTicket> {
        ensureReachable(token)
        return outbox.toList()
    }

    override suspend fun ackOutbox(token: String, baseUrl: String, ticketIds: List<String>) {
        ensureReachable(token)
        ackedTicketIds += ticketIds
        outbox.removeAll { it.ticketId in ticketIds.toSet() }
    }

    override suspend fun downloadBundleStream(
        token: String,
        baseUrl: String,
        workId: WorkId,
        destFile: String,
        resumeOffset: Long,
    ): Flow<Long> = flow {
        ensureReachable(token)
        val bytes = bundlesByWorkId[workId.raw]
            ?: throw AppFailureException(AppFailure.NetworkOrSyncConflict("Bundle missing on in-memory desktop: ${workId.raw}"))
        val target = File(destFile)
        target.parentFile?.mkdirs()
        val offset = resumeOffset.coerceIn(0L, bytes.size.toLong()).toInt()
        FileOutputStream(target, offset > 0 && target.isFile && target.length() == offset.toLong()).use { out ->
            out.write(bytes, offset, bytes.size - offset)
        }
        emit(bytes.size.toLong())
    }

    override suspend fun searchRelated(
        token: String,
        baseUrl: String,
        excludeWorkId: WorkId,
        query: String,
        sourceText: String?,
    ): List<CrossBookHit> {
        if (!isOnline) {
            throw AppFailureException(AppFailure.DesktopConnectionRequired("cross_book_search"))
        }
        checkAuth(token)
        return crossSearchHitsByWorkId[excludeWorkId.raw].orEmpty()
    }

    override suspend fun pushProgressShard(token: String, baseUrl: String, shard: DeviceProgressShard) {
        ensureReachable(token)
        val map = progressShardsByWork.getOrPut(shard.workId.raw) { ConcurrentHashMap() }
        map[shard.deviceId.raw] = shard
    }

    override suspend fun fetchProgressShards(
        token: String,
        baseUrl: String,
        workId: WorkId,
    ): List<DeviceProgressShard> {
        ensureReachable(token)
        return progressShardsByWork[workId.raw]?.values?.sortedByDescending { it.updatedAtEpochMs }.orEmpty()
    }

    private fun ensureReachable(token: String) {
        if (!isOnline) {
            throw AppFailureException(AppFailure.DesktopConnectionRequired("desktop_gateway"))
        }
        checkAuth(token)
    }

    private fun checkAuth(token: String) {
        if (token != expectedToken) {
            throw AppFailureException(AppFailure.NetworkOrSyncConflict("Unauthorized (401)"))
        }
    }
}
