package app.banread.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.banread.app.data.AppPreferencesPort
import app.banread.app.reader.ReflowSurfaceController
import app.banread.model.ChapterOrdinal
import app.banread.model.FootnoteDetail
import app.banread.model.LibraryVault
import app.banread.model.ReaderEngine
import app.banread.model.ReaderSessionState
import app.banread.model.ReaderTheme
import app.banread.model.ReadingAnchor
import app.banread.model.ReadingSurfaceMode
import app.banread.model.RemoteCursorBanner
import app.banread.model.SyncCoordinator
import app.banread.model.TypographyProfile
import app.banread.model.WorkId
import app.banread.model.WorkOutline
import app.banread.reader.FontManager
import app.banread.reader.PaginationMode
import app.banread.reader.RegisteredFont
import app.banread.reader.TextSelectionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReflowReaderUiState(
    val workId: WorkId,
    val bookTitle: String = "",
    val currentChapterTitle: String = "",
    val sessionState: ReaderSessionState = ReaderSessionState(),
    val hudVisible: Boolean = false,
    val typographySheetOpen: Boolean = false,
    val theme: ReaderTheme = ReaderTheme.SEPIA_WARM,
    val typography: TypographyProfile = TypographyProfile(
        fontFamily = "Noto Serif CJK SC",
        fontSizeSp = 17f,
        lineHeightMultiplier = 1.84f,
        paragraphSpacingDp = 12f,
        horizontalMarginDp = 20f,
        squeezeChinesePunctuation = true,
    ),
    val paginationMode: PaginationMode = PaginationMode.SLIDE,
    val volumeKeyPageTurn: Boolean = true,
    val registeredFonts: List<RegisteredFont> = emptyList(),
    val activeSelection: TextSelectionEvent? = null,
    val showNoteComposerForSelection: Boolean = false,
    val activeFootnote: FootnoteDetail? = null,
    val preFootnoteReturnAnchor: ReadingAnchor? = null,
    val preFootnoteReturnLabel: String? = null,
    val showReturnToCompanionPill: Boolean = false,
    val remoteCursorBanner: RemoteCursorBanner? = null,
    val statusBanner: String? = null,
)

class ReflowReaderViewModel(
    private val workId: WorkId,
    private val libraryVault: LibraryVault,
    private val readerEngine: ReaderEngine,
    private val surfaceController: ReflowSurfaceController,
    private val fontManager: FontManager?,
    private val appPreferences: AppPreferencesPort,
    private val syncCoordinator: SyncCoordinator? = null,
) : ViewModel() {

    private var cachedOutline: WorkOutline? = null

    private val _uiState = MutableStateFlow(
        ReflowReaderUiState(
            workId = workId,
            theme = appPreferences.state.value.theme,
            typography = appPreferences.state.value.typography,
            paginationMode = appPreferences.state.value.paginationMode,
            volumeKeyPageTurn = appPreferences.state.value.volumeKeyPageTurn,
        ),
    )
    val uiState: StateFlow<ReflowReaderUiState> = _uiState.asStateFlow()

    init {
        observeEngineAndSurface()
        openSession()
    }

    private fun observeEngineAndSurface() {
        viewModelScope.launch {
            readerEngine.activeState.collect { session ->
                val chTitle = resolveChapterTitle(session.currentCursor?.ordinal)
                _uiState.value = _uiState.value.copy(
                    sessionState = session,
                    currentChapterTitle = chTitle,
                )
            }
        }
        viewModelScope.launch {
            appPreferences.state.collect { prefs ->
                _uiState.value = _uiState.value.copy(
                    theme = prefs.theme,
                    typography = prefs.typography,
                    paginationMode = prefs.paginationMode,
                    volumeKeyPageTurn = prefs.volumeKeyPageTurn,
                )
            }
        }
        viewModelScope.launch {
            surfaceController.footnoteEvents.collect { event ->
                val detail = libraryVault.resolveFootnote(workId, event.ordinal, event.markerOrHref)
                if (detail != null) {
                    _uiState.value = _uiState.value.copy(
                        activeFootnote = detail,
                        hudVisible = false,
                        typographySheetOpen = false,
                        activeSelection = null,
                    )
                }
            }
        }
        viewModelScope.launch {
            surfaceController.selectionEvents.collect { sel ->
                _uiState.value = _uiState.value.copy(
                    activeSelection = sel,
                    hudVisible = false,
                    typographySheetOpen = false,
                )
            }
        }
    }

    private fun openSession() {
        viewModelScope.launch {
            val active = readerEngine.activeState.value
            val needsOpen = active.workId != workId || active.mode != ReadingSurfaceMode.REFLOWABLE
            openOrSyncSession(callOpen = needsOpen)
        }
    }

    fun reopenSurface() {
        viewModelScope.launch {
            openOrSyncSession(callOpen = true)
        }
    }

    fun syncPreferencesAndOutline() {
        viewModelScope.launch {
            val outline = runCatching { libraryVault.getWorkOutline(workId) }.getOrNull()
            if (outline != null) {
                cachedOutline = outline
            }
            val fonts = runCatching { fontManager?.listFonts().orEmpty() }.getOrDefault(emptyList())
            val prefs = appPreferences.state.value
            readerEngine.applyTheme(prefs.theme)
            readerEngine.applyTypography(prefs.typography)
            surfaceController.setPaginationMode(prefs.paginationMode)
            val chTitle = resolveChapterTitle(readerEngine.activeState.value.currentCursor?.ordinal)
            _uiState.value = _uiState.value.copy(
                bookTitle = (outline ?: cachedOutline)?.title.orEmpty(),
                currentChapterTitle = chTitle,
                registeredFonts = fonts,
            )
        }
    }

    private suspend fun openOrSyncSession(callOpen: Boolean) {
        val outline = runCatching { libraryVault.getWorkOutline(workId) }.getOrNull()
        cachedOutline = outline
        val fonts = runCatching { fontManager?.listFonts().orEmpty() }.getOrDefault(emptyList())
        val prefs = appPreferences.state.value

        if (callOpen) {
            readerEngine.open(workId, ReadingSurfaceMode.REFLOWABLE)
        }
        readerEngine.applyTheme(prefs.theme)
        readerEngine.applyTypography(prefs.typography)
        surfaceController.setPaginationMode(prefs.paginationMode)

        val chTitle = resolveChapterTitle(readerEngine.activeState.value.currentCursor?.ordinal)
        _uiState.value = _uiState.value.copy(
            bookTitle = outline?.title.orEmpty(),
            currentChapterTitle = chTitle,
            registeredFonts = fonts,
        )
        reconcileRemoteProgress()
    }

    fun reconcileRemoteProgress() {
        val coord = syncCoordinator ?: return
        viewModelScope.launch {
            coord.reconcileWork(workId).onSuccess { outcome ->
                if (outcome.remoteCursorBanner != null) {
                    _uiState.value = _uiState.value.copy(remoteCursorBanner = outcome.remoteCursorBanner)
                }
            }
        }
    }

    fun jumpToRemoteCursor() {
        val banner = _uiState.value.remoteCursorBanner ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(remoteCursorBanner = null)
            readerEngine.goTo(banner.remoteCursor, highlightPulseMs = 1_500L)
        }
    }

    fun dismissRemoteCursorBanner() {
        _uiState.value = _uiState.value.copy(remoteCursorBanner = null)
    }

    private fun resolveChapterTitle(ordinal: ChapterOrdinal?): String {
        val outline = cachedOutline ?: return ""
        val ord = ordinal ?: return outline.bodyChapters.firstOrNull()?.title.orEmpty()
        val all = outline.bodyChapters + outline.notesChapters + outline.frontBackChapters
        return all.firstOrNull { it.ordinal == ord }?.title ?: "第 ${ord.value} 章"
    }

    fun onTapCenterZone() {
        if (_uiState.value.activeSelection != null) {
            _uiState.value = _uiState.value.copy(activeSelection = null, showNoteComposerForSelection = false)
            return
        }
        if (_uiState.value.activeFootnote != null) {
            _uiState.value = _uiState.value.copy(activeFootnote = null)
            return
        }
        val nextHud = !_uiState.value.hudVisible
        _uiState.value = _uiState.value.copy(
            hudVisible = nextHud,
            typographySheetOpen = if (nextHud) _uiState.value.typographySheetOpen else false,
        )
    }

    fun onPrevPage() {
        surfaceController.prevPage()
    }

    fun onNextPage() {
        surfaceController.nextPage()
    }

    fun toggleTypographySheet() {
        _uiState.value = _uiState.value.copy(
            hudVisible = true,
            typographySheetOpen = !_uiState.value.typographySheetOpen,
        )
    }

    fun dismissTypographySheet() {
        _uiState.value = _uiState.value.copy(typographySheetOpen = false)
    }

    fun applyTheme(theme: ReaderTheme) {
        appPreferences.setTheme(theme)
        viewModelScope.launch {
            readerEngine.applyTheme(theme)
        }
    }

    fun updateTypography(profile: TypographyProfile) {
        val clamped = profile.copy(
            fontSizeSp = profile.fontSizeSp.coerceIn(12f, 36f),
            lineHeightMultiplier = profile.lineHeightMultiplier.coerceIn(1.2f, 2.4f),
            paragraphSpacingDp = profile.paragraphSpacingDp.coerceAtLeast(0f),
            horizontalMarginDp = profile.horizontalMarginDp.coerceAtLeast(0f),
        )
        appPreferences.updateTypography(clamped)
        viewModelScope.launch {
            readerEngine.applyTypography(clamped)
        }
    }

    fun setPaginationMode(mode: PaginationMode) {
        appPreferences.setPaginationMode(mode)
        surfaceController.setPaginationMode(mode)
    }

    fun setVolumeKeyPageTurn(enabled: Boolean) {
        appPreferences.setVolumeKeyPageTurn(enabled)
    }

    fun importCustomFont(sourceUri: String) {
        val fm = fontManager ?: return
        viewModelScope.launch {
            fm.importFont(sourceUri).fold(
                onSuccess = { registered ->
                    val updatedList = fm.listFonts()
                    _uiState.value = _uiState.value.copy(
                        registeredFonts = updatedList,
                        statusBanner = "已导入字体：${registered.displayName}",
                    )
                    updateTypography(_uiState.value.typography.copy(fontFamily = registered.cssFontFamily))
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        statusBanner = "字体导入失败：${err.message ?: "无效字体文件"}",
                    )
                },
            )
        }
    }

    fun highlightSelection(colorToken: String) {
        val sel = _uiState.value.activeSelection ?: return
        viewModelScope.launch {
            readerEngine.saveAnnotation(workId, sel.anchor, colorToken, "")
            _uiState.value = _uiState.value.copy(
                activeSelection = null,
                showNoteComposerForSelection = false,
                statusBanner = "已添加高亮批注",
            )
        }
    }

    fun openNoteComposer() {
        if (_uiState.value.activeSelection != null) {
            _uiState.value = _uiState.value.copy(showNoteComposerForSelection = true)
        }
    }

    fun dismissNoteComposer() {
        _uiState.value = _uiState.value.copy(showNoteComposerForSelection = false)
    }

    fun saveSelectionNote(comment: String) {
        val sel = _uiState.value.activeSelection ?: return
        viewModelScope.launch {
            readerEngine.saveAnnotation(workId, sel.anchor, "YELLOW", comment.trim())
            _uiState.value = _uiState.value.copy(
                activeSelection = null,
                showNoteComposerForSelection = false,
                statusBanner = "已保存阅读笔记",
            )
        }
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            activeSelection = null,
            showNoteComposerForSelection = false,
        )
    }

    fun dismissFootnotePopover() {
        _uiState.value = _uiState.value.copy(activeFootnote = null)
    }

    fun jumpToFullFootnoteNote() {
        val detail = _uiState.value.activeFootnote ?: return
        val returnAnchor = _uiState.value.sessionState.currentCursor
        viewModelScope.launch {
            val targetAnchor = libraryVault.resolveAnchor(
                workId,
                detail.link.targetChapterOrdinal,
                detail.link.targetBlockId,
            )
            readerEngine.goTo(targetAnchor, highlightPulseMs = 1_500L)
            _uiState.value = _uiState.value.copy(
                activeFootnote = null,
                preFootnoteReturnAnchor = returnAnchor,
                preFootnoteReturnLabel = detail.link.marker,
            )
        }
    }

    fun returnToPreFootnoteLocation() {
        val anchor = _uiState.value.preFootnoteReturnAnchor ?: return
        viewModelScope.launch {
            readerEngine.goTo(anchor, highlightPulseMs = 800L)
            _uiState.value = _uiState.value.copy(
                preFootnoteReturnAnchor = null,
                preFootnoteReturnLabel = null,
            )
        }
    }

    fun markJumpedFromCompanion() {
        _uiState.value = _uiState.value.copy(showReturnToCompanionPill = true)
    }

    fun clearReturnToCompanionPill() {
        _uiState.value = _uiState.value.copy(showReturnToCompanionPill = false)
    }

    fun confirmCurrentPendingWatermark() {
        val pending = _uiState.value.sessionState.pendingWatermarkJump ?: return
        viewModelScope.launch {
            readerEngine.confirmWatermarkAdvance(workId, pending)
            _uiState.value = _uiState.value.copy(statusBanner = "已推进防剧透水位线至第 ${pending.value} 章")
        }
    }

    fun clearStatusBanner() {
        _uiState.value = _uiState.value.copy(statusBanner = null)
    }

    companion object {
        fun factory(
            workId: WorkId,
            libraryVault: LibraryVault,
            readerEngine: ReaderEngine,
            surfaceController: ReflowSurfaceController,
            fontManager: FontManager?,
            appPreferences: AppPreferencesPort,
            syncCoordinator: SyncCoordinator? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReflowReaderViewModel(
                    workId = workId,
                    libraryVault = libraryVault,
                    readerEngine = readerEngine,
                    surfaceController = surfaceController,
                    fontManager = fontManager,
                    appPreferences = appPreferences,
                    syncCoordinator = syncCoordinator,
                ) as T
        }
    }
}
