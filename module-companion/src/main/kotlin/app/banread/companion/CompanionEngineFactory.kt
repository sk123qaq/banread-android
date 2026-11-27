package app.banread.companion

import android.content.Context
import app.banread.companion.internal.RoomCompanionEngine
import app.banread.model.AiRuntime
import app.banread.model.CompanionEngine
import app.banread.model.LibraryVault
import app.banread.model.SyncCoordinator
import app.banread.model.registry.ProfileRegistry
import java.io.File

/**
 * Public entry point for `:module-companion`.
 * Context builders, BlueprintAuditor, PromptCatalog, and Room L3 SWR cache remain `internal`.
 */
object CompanionEngineFactory {
    fun create(
        libraryVault: LibraryVault,
        aiRuntime: AiRuntime,
        profileRegistry: ProfileRegistry,
        syncCoordinator: SyncCoordinator? = null,
        context: Context,
        filesRoot: File,
        allowInMemoryDb: Boolean = false,
    ): CompanionEngine {
        val appContext = context.applicationContext ?: context
        return RoomCompanionEngine.create(
            libraryVault = libraryVault,
            aiRuntime = aiRuntime,
            profileRegistry = profileRegistry,
            syncCoordinator = syncCoordinator,
            context = appContext,
            filesRoot = filesRoot,
            allowInMemoryDb = allowInMemoryDb,
        )
    }
}
