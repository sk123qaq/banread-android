package app.banread.app.di

import android.content.Context
import app.banread.ai.AiRuntimeFactory
import app.banread.app.data.AppPreferencesPort
import app.banread.app.data.OfflineDesktopSyncCoordinator
import app.banread.app.data.SharedPrefsAppPreferencesStore
import app.banread.app.reader.FoliateReflowSurfaceController
import app.banread.app.reader.PdfSurfaceController
import app.banread.app.reader.PdfiumSurfaceController
import app.banread.app.reader.ReflowSurfaceController
import app.banread.app.reader.VizHostController
import app.banread.app.reader.WebViewVizHostController
import app.banread.companion.CompanionEngineFactory
import app.banread.library.LibraryVaultFactory
import app.banread.model.AiRuntime
import app.banread.model.CompanionEngine
import app.banread.model.LibraryVault
import app.banread.model.ReaderEngine
import app.banread.model.SyncCoordinator
import app.banread.model.registry.ProfileRegistry
import app.banread.reader.FoliateWebAdapter
import app.banread.reader.FontManager
import app.banread.reader.PdfiumPageAdapter
import app.banread.reader.ReaderEngineFactory
import app.banread.viz.WebViewVizHost
import java.io.Closeable
import java.io.File

/**
 * Production dependency container for `:app`.
 * Wires real implementations from `:core-model`, `:module-library`, `:module-reader`,
 * `:module-ai`, `:module-companion`, and `:module-viz`.
 */
class BanReadAppContainer(context: Context) : Closeable {
    private val appContext: Context = context.applicationContext ?: context
    val filesRoot: File = File(appContext.filesDir, "banread").apply { mkdirs() }

    val profileRegistry: ProfileRegistry by lazy {
        val json = appContext.assets.open("profiles.generated.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        ProfileRegistry.fromJson(json)
    }

    val libraryVault: LibraryVault by lazy {
        LibraryVaultFactory.create(
            context = appContext,
            filesRoot = filesRoot,
            profileRegistry = profileRegistry,
            allowInMemory = false,
        )
    }

    val fontManager: FontManager by lazy {
        ReaderEngineFactory.createFontManager(
            context = appContext,
            filesRoot = filesRoot,
            allowInMemory = false,
        )
    }

    val foliateWebAdapter: FoliateWebAdapter by lazy {
        FoliateWebAdapter(appContext, filesRoot)
    }

    val pdfiumPageAdapter: PdfiumPageAdapter by lazy {
        PdfiumPageAdapter()
    }

    val reflowSurfaceController: ReflowSurfaceController by lazy {
        FoliateReflowSurfaceController(foliateWebAdapter)
    }

    val pdfSurfaceController: PdfSurfaceController by lazy {
        PdfiumSurfaceController(pdfiumPageAdapter)
    }

    val reflowReaderEngine: ReaderEngine by lazy {
        ReaderEngineFactory.create(
            context = appContext,
            filesRoot = filesRoot,
            libraryVault = libraryVault,
            rendererPort = foliateWebAdapter,
            allowInMemory = false,
            fontManager = fontManager,
        )
    }

    val pdfReaderEngine: ReaderEngine by lazy {
        ReaderEngineFactory.create(
            context = appContext,
            filesRoot = filesRoot,
            libraryVault = libraryVault,
            rendererPort = pdfiumPageAdapter,
            allowInMemory = false,
            fontManager = fontManager,
        )
    }

    val aiRuntime: AiRuntime by lazy {
        AiRuntimeFactory.create(context = appContext)
    }

    val offlineDesktopSyncCoordinator: SyncCoordinator by lazy {
        OfflineDesktopSyncCoordinator()
    }

    val companionEngine: CompanionEngine by lazy {
        CompanionEngineFactory.create(
            libraryVault = libraryVault,
            aiRuntime = aiRuntime,
            profileRegistry = profileRegistry,
            syncCoordinator = offlineDesktopSyncCoordinator,
            context = appContext,
            filesRoot = filesRoot,
            allowInMemoryDb = false,
        )
    }

    val webViewVizHost: WebViewVizHost by lazy {
        WebViewVizHost(appContext)
    }

    val vizHostController: VizHostController by lazy {
        WebViewVizHostController(webViewVizHost)
    }

    val appPreferences: AppPreferencesPort by lazy {
        SharedPrefsAppPreferencesStore(appContext)
    }

    override fun close() {
        runCatching { webViewVizHost.release() }
        runCatching { foliateWebAdapter.detachWebView() }
        runCatching { pdfiumPageAdapter.close() }
        runCatching { (reflowReaderEngine as? Closeable)?.close() }
        runCatching { (pdfReaderEngine as? Closeable)?.close() }
        runCatching { fontManager.close() }
        runCatching { (companionEngine as? Closeable)?.close() }
        runCatching { (libraryVault as? Closeable)?.close() }
    }
}
