package app.banread.app.di

import android.content.Context
import app.banread.ai.AiRuntimeFactory
import app.banread.ai.TinkKeystoreVaultAdapter
import app.banread.app.data.AppPreferencesPort
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
import app.banread.model.DeviceId
import app.banread.model.LibraryVault
import app.banread.model.ReaderEngine
import app.banread.model.SecretVaultPort
import app.banread.model.registry.ProfileRegistry
import app.banread.reader.FoliateWebAdapter
import app.banread.reader.FontManager
import app.banread.reader.PdfiumPageAdapter
import app.banread.reader.ReaderEngineFactory
import app.banread.sync.DefaultSyncCoordinator
import app.banread.sync.ShareCoordinator
import app.banread.sync.SyncCoordinatorFactory
import app.banread.viz.WebViewVizHost
import java.io.Closeable
import java.io.File

class BanReadAppContainer(context: Context) : Closeable {
    private val appContext: Context = context.applicationContext ?: context
    val filesRoot: File = File(appContext.filesDir, "banread").apply { mkdirs() }

    val appPreferences: AppPreferencesPort by lazy {
        SharedPrefsAppPreferencesStore(appContext)
    }

    val deviceId: DeviceId by lazy {
        appPreferences.state.value.deviceId
    }

    val secretVault: SecretVaultPort by lazy {
        TinkKeystoreVaultAdapter(appContext)
    }

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
            deviceId = deviceId,
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
            deviceId = deviceId,
            allowInMemory = false,
            fontManager = fontManager,
        )
    }

    val aiRuntime: AiRuntime by lazy {
        AiRuntimeFactory.create(
            context = appContext,
            secretVault = secretVault,
        )
    }

    val syncCoordinator: DefaultSyncCoordinator by lazy {
        SyncCoordinatorFactory.create(
            context = appContext,
            filesRoot = filesRoot,
            libraryVault = libraryVault,
            readerEngine = reflowReaderEngine,
            companionEngineProvider = { companionEngine },
            secretVault = secretVault,
            deviceId = deviceId,
        )
    }

    val companionEngine: CompanionEngine by lazy {
        CompanionEngineFactory.create(
            libraryVault = libraryVault,
            aiRuntime = aiRuntime,
            profileRegistry = profileRegistry,
            syncCoordinator = syncCoordinator,
            context = appContext,
            filesRoot = filesRoot,
            allowInMemoryDb = false,
        )
    }

    val shareCoordinator: ShareCoordinator by lazy {
        SyncCoordinatorFactory.createShareCoordinator(
            context = appContext,
            filesRoot = filesRoot,
            libraryVault = libraryVault,
            readerEngine = reflowReaderEngine,
            companionEngine = companionEngine,
            secretVault = secretVault,
            deviceId = deviceId,
        )
    }

    val webViewVizHost: WebViewVizHost by lazy {
        WebViewVizHost(appContext)
    }

    val vizHostController: VizHostController by lazy {
        WebViewVizHostController(webViewVizHost)
    }

    override fun close() {
        runCatching { shareCoordinator.close() }
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
