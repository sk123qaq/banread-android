package app.banread.sync

import android.content.Context
import app.banread.model.CompanionEngine
import app.banread.model.DesktopBridgePort
import app.banread.model.DeviceId
import app.banread.model.LibraryVault
import app.banread.model.ReaderEngine
import app.banread.model.SecretVaultPort
import app.banread.model.StateSyncPort
import app.banread.sync.internal.NoOpSyncWorkScheduler
import app.banread.sync.internal.PendingSyncQueue
import app.banread.sync.internal.SyncConfigStore
import app.banread.sync.internal.SyncCoordinatorRuntimeRegistry
import app.banread.sync.internal.WorkManagerSyncScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Public factory entry point for `:module-sync`.
 * Keeps HTTP wire DTOs, XML parsers, AWS SigV4 signing, and WorkManager workers `internal`.
 */
object SyncCoordinatorFactory {

    fun registerBackgroundSyncProvider(provider: (Context) -> DefaultSyncCoordinator?) {
        SyncCoordinatorRuntimeRegistry.register(provider)
    }

    fun create(
        context: Context,
        filesRoot: File,
        libraryVault: LibraryVault,
        readerEngine: ReaderEngine,
        companionEngineProvider: () -> CompanionEngine?,
        secretVault: SecretVaultPort,
        deviceId: DeviceId? = null,
        desktopBridge: DesktopBridgePort = HttpDesktopGatewayAdapter(),
    ): DefaultSyncCoordinator {
        val appContext = context.applicationContext ?: context
        val syncDir = File(filesRoot, "sync").apply { mkdirs() }
        val configStore = SyncConfigStore(File(syncDir, "sync_config.json"), deviceId)
        val pendingQueue = PendingSyncQueue(File(syncDir, "pending_ops.json"))
        return DefaultSyncCoordinator(
            filesRoot = filesRoot,
            libraryVault = libraryVault,
            readerEngine = readerEngine,
            companionEngineProvider = companionEngineProvider,
            secretVault = secretVault,
            desktopBridge = desktopBridge,
            configStore = configStore,
            pendingQueue = pendingQueue,
            workScheduler = WorkManagerSyncScheduler(appContext),
            customStateSyncPort = null,
        )
    }

    fun createShareCoordinator(
        context: Context?,
        filesRoot: File,
        libraryVault: LibraryVault,
        readerEngine: ReaderEngine,
        companionEngine: CompanionEngine,
        secretVault: SecretVaultPort,
        deviceId: DeviceId? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ShareCoordinator {
        val appContext = context?.applicationContext ?: context
        val syncDir = File(filesRoot, "sync").apply { mkdirs() }
        val configStore = SyncConfigStore(File(syncDir, "sync_config.json"), deviceId)
        return ShareCoordinator(
            context = appContext,
            filesRoot = filesRoot,
            libraryVault = libraryVault,
            readerEngine = readerEngine,
            companionEngine = companionEngine,
            secretVault = secretVault,
            configStore = configStore,
            ioDispatcher = ioDispatcher,
        )
    }

    fun createForTest(
        filesRoot: File,
        libraryVault: LibraryVault,
        readerEngine: ReaderEngine,
        companionEngine: CompanionEngine?,
        secretVault: SecretVaultPort,
        deviceId: DeviceId,
        desktopBridge: DesktopBridgePort,
        stateSyncPort: StateSyncPort? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): DefaultSyncCoordinator {
        val syncDir = File(filesRoot, "sync_${deviceId.raw}").apply { mkdirs() }
        val configStore = SyncConfigStore(File(syncDir, "sync_config.json"), deviceId)
        val pendingQueue = PendingSyncQueue(File(syncDir, "pending_ops.json"))
        return DefaultSyncCoordinator(
            filesRoot = filesRoot,
            libraryVault = libraryVault,
            readerEngine = readerEngine,
            companionEngineProvider = { companionEngine },
            secretVault = secretVault,
            desktopBridge = desktopBridge,
            configStore = configStore,
            pendingQueue = pendingQueue,
            workScheduler = NoOpSyncWorkScheduler,
            customStateSyncPort = stateSyncPort,
            ioDispatcher = ioDispatcher,
        )
    }
}
