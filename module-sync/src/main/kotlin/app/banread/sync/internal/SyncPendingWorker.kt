package app.banread.sync.internal

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.banread.sync.DefaultSyncCoordinator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object SyncCoordinatorRuntimeRegistry {
    private val providerRef = AtomicReference<((Context) -> DefaultSyncCoordinator?)?>(null)

    fun register(provider: (Context) -> DefaultSyncCoordinator?) {
        providerRef.set(provider)
    }

    fun resolve(context: Context): DefaultSyncCoordinator? =
        providerRef.get()?.invoke(context)
}

internal interface SyncWorkScheduler {
    fun schedulePendingFlush()
}

internal class WorkManagerSyncScheduler(
    private val context: Context,
) : SyncWorkScheduler {
    override fun schedulePendingFlush() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncPendingWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "banread_sync_pending_queue"
    }
}

internal object NoOpSyncWorkScheduler : SyncWorkScheduler {
    override fun schedulePendingFlush() = Unit
}

internal class SyncPendingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val coordinator = SyncCoordinatorRuntimeRegistry.resolve(applicationContext)
            ?: return Result.success()
        val allDrained = coordinator.flushPendingQueue()
        return if (allDrained) Result.success() else Result.retry()
    }
}
