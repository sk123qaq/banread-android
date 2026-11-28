package app.banread.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import app.banread.app.di.BanReadAppContainer
import app.banread.app.ui.bookshelf.BookshelfScreen
import app.banread.app.ui.bookshelf.BookshelfViewModel
import app.banread.app.ui.companion.CompanionDetent
import app.banread.app.ui.companion.CompanionSheetOverlay
import app.banread.app.ui.companion.CompanionViewModel
import app.banread.app.ui.detail.BookDetailScreen
import app.banread.app.ui.detail.BookDetailViewModel
import app.banread.app.ui.pdf.PdfReaderScreen
import app.banread.app.ui.pdf.PdfReaderViewModel
import app.banread.app.ui.reader.ReflowReaderScreen
import app.banread.app.ui.reader.ReflowReaderViewModel
import app.banread.app.ui.settings.SettingsScreen
import app.banread.app.ui.settings.SettingsViewModel
import app.banread.app.ui.theme.BanReadTheme
import app.banread.model.ReadingSurfaceMode
import app.banread.model.WorkId
import app.banread.reader.VolumeKeyPager

sealed interface BanReadRoute {
    data object Bookshelf : BanReadRoute
    data object Settings : BanReadRoute
    data class BookDetail(val workId: WorkId) : BanReadRoute
    data class ReflowReader(val workId: WorkId, val forceReopen: Boolean = false) : BanReadRoute
    data class PdfReader(val workId: WorkId) : BanReadRoute
}

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: BanReadAppContainer
    private var activePrevPageAction: (() -> Unit)? = null
    private var activeNextPageAction: (() -> Unit)? = null
    private val pendingExternalPayload = mutableStateOf<String?>(null)

    private val volumeKeyPager = VolumeKeyPager(
        isReaderActive = false,
        isEnabled = false,
        onPrevPage = { activePrevPageAction?.invoke() },
        onNextPage = { activeNextPageAction?.invoke() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appContainer = (application as BanReadApplication).container
        extractIncomingShareOrPair(intent)?.let { pendingExternalPayload.value = it }

        setContent {
            val prefs by appContainer.appPreferences.state.collectAsState()
            val incomingPayload by pendingExternalPayload

            BanReadTheme(readerTheme = prefs.theme) {
                BanReadAppRoot(
                    container = appContainer,
                    volumeTurnEnabled = prefs.volumeKeyPageTurn,
                    incomingExternalPayload = incomingPayload,
                    onConsumeIncomingPayload = { pendingExternalPayload.value = null },
                    onBindReaderVolumePager = { isActive, isEnabled, prev, next ->
                        volumeKeyPager.isReaderActive = isActive
                        volumeKeyPager.isEnabled = isEnabled
                        activePrevPageAction = prev
                        activeNextPageAction = next
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIncomingShareOrPair(intent)?.let { pendingExternalPayload.value = it }
    }

    private fun extractIncomingShareOrPair(intent: Intent?): String? {
        if (intent == null) return null
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data?.toString()?.trim()
                if (!data.isNullOrEmpty()) return data
            }
            Intent.ACTION_SEND -> {
                val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (streamUri != null) return streamUri.toString()
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                if (!sharedText.isNullOrEmpty()) return sharedText
            }
        }
        return null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyPager.onKeyDown(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun BanReadAppRoot(
    container: BanReadAppContainer,
    volumeTurnEnabled: Boolean,
    incomingExternalPayload: String?,
    onConsumeIncomingPayload: () -> Unit,
    onBindReaderVolumePager: (
        isReaderActive: Boolean,
        isEnabled: Boolean,
        onPrev: (() -> Unit)?,
        onNext: (() -> Unit)?,
    ) -> Unit,
) {
    val backStack = remember { mutableStateListOf<BanReadRoute>(BanReadRoute.Bookshelf) }
    val currentRoute = backStack.last()

    LaunchedEffect(incomingExternalPayload) {
        if (!incomingExternalPayload.isNullOrBlank()) {
            if (backStack.lastOrNull() != BanReadRoute.Settings) {
                backStack.add(BanReadRoute.Settings)
            }
        }
    }

    val companionVm: CompanionViewModel = viewModel(
        key = "companion_shared",
        factory = CompanionViewModel.factory(
            companionEngine = container.companionEngine,
            libraryVault = container.libraryVault,
            readerEngine = container.reflowReaderEngine,
            profileRegistry = container.profileRegistry,
            vizHostController = container.vizHostController,
        ),
    )
    val companionState by companionVm.uiState.collectAsState()

    fun navigateTo(route: BanReadRoute) {
        if (route == BanReadRoute.Bookshelf) {
            backStack.clear()
            backStack.add(BanReadRoute.Bookshelf)
        } else {
            backStack.add(route)
        }
    }

    fun popBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    BackHandler(enabled = backStack.size > 1 || companionState.detent != CompanionDetent.CLOSED) {
        when {
            companionState.isFullscreenVizOpen -> companionVm.setFullscreenVizOpen(false)
            companionState.detent == CompanionDetent.FULL || companionState.detent == CompanionDetent.HALF -> {
                companionVm.setDetent(CompanionDetent.PEEK)
            }
            companionState.detent == CompanionDetent.PEEK -> {
                companionVm.setDetent(CompanionDetent.CLOSED)
            }
            else -> popBack()
        }
    }

    // Ensure volume-key paging is deactivated on non-reader screens
    DisposableEffect(currentRoute, volumeTurnEnabled) {
        if (currentRoute !is BanReadRoute.ReflowReader && currentRoute !is BanReadRoute.PdfReader) {
            onBindReaderVolumePager(false, volumeTurnEnabled, null, null)
        }
        onDispose {}
    }

    when (val route = currentRoute) {
        BanReadRoute.Bookshelf -> {
            val bookshelfVm: BookshelfViewModel = viewModel(
                key = "bookshelf",
                factory = BookshelfViewModel.factory(
                    libraryVault = container.libraryVault,
                    profileRegistry = container.profileRegistry,
                ),
            )
            BookshelfScreen(
                viewModel = bookshelfVm,
                onOpenBook = { summary ->
                    if (ReadingSurfaceMode.REFLOWABLE !in summary.supportedModes &&
                        ReadingSurfaceMode.PDF_FIXED in summary.supportedModes
                    ) {
                        navigateTo(BanReadRoute.PdfReader(summary.workId))
                    } else {
                        navigateTo(BanReadRoute.BookDetail(summary.workId))
                    }
                },
                onOpenSettings = {
                    if (currentRoute != BanReadRoute.Settings) {
                        navigateTo(BanReadRoute.Settings)
                    }
                },
            )
        }

        BanReadRoute.Settings -> {
            val settingsVm: SettingsViewModel = viewModel(
                key = "settings",
                factory = SettingsViewModel.factory(
                    aiRuntime = container.aiRuntime,
                    fontManager = container.fontManager,
                    appPreferences = container.appPreferences,
                    syncCoordinator = container.syncCoordinator,
                    shareCoordinator = container.shareCoordinator,
                    libraryVault = container.libraryVault,
                ),
            )
            LaunchedEffect(incomingExternalPayload) {
                if (!incomingExternalPayload.isNullOrBlank()) {
                    settingsVm.handleIncomingExternalUri(incomingExternalPayload)
                    onConsumeIncomingPayload()
                }
            }
            SettingsScreen(
                viewModel = settingsVm,
                onOpenBookshelf = { navigateTo(BanReadRoute.Bookshelf) },
            )
        }

        is BanReadRoute.BookDetail -> {
            val detailVm: BookDetailViewModel = viewModel(
                key = "detail_${route.workId.raw}",
                factory = BookDetailViewModel.factory(
                    workId = route.workId,
                    libraryVault = container.libraryVault,
                    readerEngine = container.reflowReaderEngine,
                    profileRegistry = container.profileRegistry,
                ),
            )
            LaunchedEffect(route.workId) {
                detailVm.refresh()
            }
            BookDetailScreen(
                viewModel = detailVm,
                onBack = { popBack() },
                onNavigateToReader = { mode ->
                    if (mode == ReadingSurfaceMode.PDF_FIXED) {
                        navigateTo(BanReadRoute.PdfReader(route.workId))
                    } else {
                        navigateTo(BanReadRoute.ReflowReader(route.workId))
                    }
                },
            )
        }

        is BanReadRoute.ReflowReader -> {
            val readerVm: ReflowReaderViewModel = viewModel(
                key = "reflow_${route.workId.raw}",
                factory = ReflowReaderViewModel.factory(
                    workId = route.workId,
                    libraryVault = container.libraryVault,
                    readerEngine = container.reflowReaderEngine,
                    surfaceController = container.reflowSurfaceController,
                    fontManager = container.fontManager,
                    appPreferences = container.appPreferences,
                    syncCoordinator = container.syncCoordinator,
                ),
            )

            LaunchedEffect(route.workId, route.forceReopen) {
                if (route.forceReopen) {
                    readerVm.reopenSurface()
                    val idx = backStack.lastIndex
                    if (idx >= 0 && backStack[idx] == route) {
                        backStack[idx] = route.copy(forceReopen = false)
                    }
                } else {
                    readerVm.syncPreferencesAndOutline()
                }
            }

            DisposableEffect(route.workId, volumeTurnEnabled) {
                onBindReaderVolumePager(
                    true,
                    volumeTurnEnabled,
                    { readerVm.onPrevPage() },
                    { readerVm.onNextPage() },
                )
                onDispose {
                    onBindReaderVolumePager(false, volumeTurnEnabled, null, null)
                }
            }

            ReflowReaderScreen(
                viewModel = readerVm,
                foliateWebAdapter = container.foliateWebAdapter,
                onBackToDetail = {
                    companionVm.setDetent(CompanionDetent.CLOSED)
                    popBack()
                },
                onOpenCompanion = { ordinal, selAnchor, selText, toolId ->
                    companionVm.openSheet(
                        workId = route.workId,
                        currentOrdinal = ordinal,
                        detent = CompanionDetent.HALF,
                        selectionAnchor = selAnchor,
                        selectedText = selText,
                        initialToolId = toolId,
                        onJumpedToReader = {
                            readerVm.markJumpedFromCompanion()
                        },
                    )
                },
                companionOverlay = {
                    CompanionSheetOverlay(
                        viewModel = companionVm,
                        vizHostController = container.vizHostController,
                    )
                },
            )
        }

        is BanReadRoute.PdfReader -> {
            val pdfVm: PdfReaderViewModel = viewModel(
                key = "pdf_${route.workId.raw}",
                factory = PdfReaderViewModel.factory(
                    workId = route.workId,
                    libraryVault = container.libraryVault,
                    readerEngine = container.pdfReaderEngine,
                    pdfSurfaceController = container.pdfSurfaceController,
                    companionEngine = container.companionEngine,
                    appPreferences = container.appPreferences,
                    syncCoordinator = container.syncCoordinator,
                ),
            )

            DisposableEffect(route.workId, volumeTurnEnabled) {
                onBindReaderVolumePager(
                    true,
                    volumeTurnEnabled,
                    { pdfVm.prevPage() },
                    { pdfVm.nextPage() },
                )
                onDispose {
                    onBindReaderVolumePager(false, volumeTurnEnabled, null, null)
                }
            }

            PdfReaderScreen(
                viewModel = pdfVm,
                onBack = { popBack() },
                onSwitchedToReflow = {
                    backStack[backStack.lastIndex] = BanReadRoute.ReflowReader(route.workId, forceReopen = true)
                },
                onOpenSettingsForVision = {
                    navigateTo(BanReadRoute.Settings)
                },
            )
        }
    }
}
