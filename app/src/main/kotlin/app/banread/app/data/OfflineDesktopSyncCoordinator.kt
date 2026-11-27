package app.banread.app.data

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.BundleTransferState
import app.banread.model.CrossBookHit
import app.banread.model.DesktopBookSummary
import app.banread.model.DesktopEndpointInfo
import app.banread.model.OutboxTicket
import app.banread.model.SyncCoordinator
import app.banread.model.SyncMergeOutcome
import app.banread.model.WorkId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Offline desktop bridge stub used until Slice 6 desktop gateway integration lands.
 * Consistently fails with [AppFailure.DesktopConnectionRequired] while consuming zero LLM tokens.
 */
class OfflineDesktopSyncCoordinator : SyncCoordinator {

    override suspend fun pairDesktopViaQr(qrPayload: String): Result<DesktopEndpointInfo> =
        offlineFailure("pair_desktop")

    override suspend fun fetchDesktopCatalog(
        query: String?,
        onlyWithCache: Boolean,
    ): Result<List<DesktopBookSummary>> = offlineFailure("desktop_catalog")

    override suspend fun fetchDesktopOutbox(): Result<List<OutboxTicket>> =
        offlineFailure("desktop_outbox")

    override fun pullStudyPacks(workIds: List<WorkId>): Flow<BundleTransferState> {
        val first = workIds.firstOrNull() ?: WorkId("offline")
        return flowOf(
            BundleTransferState.Failed(
                workId = first,
                failure = AppFailure.DesktopConnectionRequired("pull_study_packs"),
            ),
        )
    }

    override suspend fun reconcileWork(workId: WorkId): Result<SyncMergeOutcome> =
        offlineFailure("reconcile_work")

    override suspend fun queryCrossBookHits(
        workId: WorkId,
        queryText: String,
        sourcePassage: String?,
    ): Result<List<CrossBookHit>> = offlineFailure("cross_book_search")

    private fun <T> offlineFailure(featureName: String): Result<T> =
        Result.failure(AppFailureException(AppFailure.DesktopConnectionRequired(featureName)))
}
