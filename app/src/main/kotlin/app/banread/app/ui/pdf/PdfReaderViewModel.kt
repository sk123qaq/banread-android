package app.banread.app.ui.pdf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.banread.app.data.AppPreferencesPort
import app.banread.app.reader.PdfSurfaceController
import app.banread.model.AppFailure
import app.banread.model.AuditedArtifact
import app.banread.model.CompanionEngine
import app.banread.model.CompanionEvent
import app.banread.model.LibraryVault
import app.banread.model.NormalizedRect
import app.banread.model.ReaderEngine
import app.banread.model.ReadingSurfaceMode
import app.banread.model.WorkId
import app.banread.reader.PdfViewportState
import app.banread.reader.RenderedPdfPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PdfReaderUiState(
    val workId: WorkId,
    val title: String = "",
    val isScannedPdf: Boolean = false,
    val canReflow: Boolean = false,
    val viewportState: PdfViewportState = PdfViewportState(),
    val renderedPage: RenderedPdfPage? = null,
    val cropModeEnabled: Boolean = false,
    val pendingCropRect: NormalizedRect? = null,
    val activeCropPngBytes: ByteArray? = null,
    val showVlmPromptDialog: Boolean = false,
    val isVlmStreaming: Boolean = false,
    val vlmThinkingText: String = "",
    val vlmAnswerMarkdown: String = "",
    val vlmFailure: AppFailure? = null,
    val showVlmResultSheet: Boolean = false,
    val statusBanner: String? = null,
    val shouldSwitchToReflowScreen: Boolean = false,
)

class PdfReaderViewModel(
    private val workId: WorkId,
    private val libraryVault: LibraryVault,
    private val readerEngine: ReaderEngine,
    private val pdfSurfaceController: PdfSurfaceController,
    private val companionEngine: CompanionEngine,
    private val appPreferences: AppPreferencesPort,
) : ViewModel() {

    private var lastTargetWidthPx: Int = 1080
    private var vlmJob: Job? = null

    private val _uiState = MutableStateFlow(PdfReaderUiState(workId = workId))
    val uiState: StateFlow<PdfReaderUiState> = _uiState.asStateFlow()

    init {
        observeViewport()
        openSession()
    }

    private fun observeViewport() {
        viewModelScope.launch {
            pdfSurfaceController.viewportState.collect { vp ->
                _uiState.value = _uiState.value.copy(viewportState = vp)
            }
        }
        viewModelScope.launch {
            appPreferences.state.collect { prefs ->
                readerEngine.applyTheme(prefs.theme)
            }
        }
    }

    private fun openSession() {
        viewModelScope.launch {
            val summary = libraryVault.getWorkSummary(workId)
            readerEngine.open(workId, ReadingSurfaceMode.PDF_FIXED)
            readerEngine.applyTheme(appPreferences.state.value.theme)
            val page = pdfSurfaceController.renderCurrentPage(lastTargetWidthPx)
            _uiState.value = _uiState.value.copy(
                title = summary?.title ?: workId.raw,
                isScannedPdf = summary?.isScannedPdf ?: false,
                canReflow = summary != null && !summary.isScannedPdf,
                renderedPage = page,
            )
        }
    }

    fun onRenderViewportWidth(widthPx: Int) {
        val clamped = widthPx.coerceAtLeast(320)
        if (kotlin.math.abs(clamped - lastTargetWidthPx) >= 48 || _uiState.value.renderedPage == null) {
            lastTargetWidthPx = clamped
            rerenderCurrentPage()
        }
    }

    fun rerenderCurrentPage() {
        viewModelScope.launch {
            val page = pdfSurfaceController.renderCurrentPage(lastTargetWidthPx)
            _uiState.value = _uiState.value.copy(renderedPage = page)
        }
    }

    fun nextPage() {
        viewModelScope.launch {
            pdfSurfaceController.nextPage()
            val page = pdfSurfaceController.renderCurrentPage(lastTargetWidthPx)
            _uiState.value = _uiState.value.copy(
                renderedPage = page,
                pendingCropRect = null,
            )
        }
    }

    fun prevPage() {
        viewModelScope.launch {
            pdfSurfaceController.prevPage()
            val page = pdfSurfaceController.renderCurrentPage(lastTargetWidthPx)
            _uiState.value = _uiState.value.copy(
                renderedPage = page,
                pendingCropRect = null,
            )
        }
    }

    fun toggleAutoCrop() {
        val next = !_uiState.value.viewportState.autoCropEnabled
        pdfSurfaceController.setAutoCrop(next)
        rerenderCurrentPage()
    }

    fun setZoomAndPan(scale: Float, panX: Float, panY: Float) {
        pdfSurfaceController.setZoomAndPan(scale, panX, panY)
    }

    fun switchToTextReflowMode() {
        val state = _uiState.value
        if (state.isScannedPdf) {
            _uiState.value = state.copy(
                statusBanner = "扫描版 PDF 暂不支持流式重排，可使用上方「框选图问 (VLM)」直接识图问答",
            )
            return
        }
        viewModelScope.launch {
            libraryVault.prepareTextPdfReflow(workId).collect { }
            readerEngine.switchPdfSurface(workId, ReadingSurfaceMode.REFLOWABLE).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(shouldSwitchToReflowScreen = true)
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        statusBanner = err.message ?: "该 PDF 尚未准备流式重排文本资产",
                    )
                },
            )
        }
    }

    fun consumeSwitchToReflowScreen() {
        _uiState.value = _uiState.value.copy(shouldSwitchToReflowScreen = false)
    }

    fun toggleCropMode() {
        val next = !_uiState.value.cropModeEnabled
        _uiState.value = _uiState.value.copy(
            cropModeEnabled = next,
            pendingCropRect = if (!next) null else _uiState.value.pendingCropRect,
        )
    }

    fun setPendingCropRect(rect: NormalizedRect?) {
        _uiState.value = _uiState.value.copy(pendingCropRect = rect)
    }

    fun clearCropSelection() {
        _uiState.value = _uiState.value.copy(
            pendingCropRect = null,
            activeCropPngBytes = null,
            showVlmPromptDialog = false,
        )
    }

    fun onCropRegionPngReady(pngBytes: ByteArray) {
        if (pngBytes.isEmpty()) {
            _uiState.value = _uiState.value.copy(statusBanner = "裁剪区域无效，请重新框选")
            return
        }
        _uiState.value = _uiState.value.copy(
            activeCropPngBytes = pngBytes,
            showVlmPromptDialog = true,
        )
    }

    fun dismissVlmPromptDialog() {
        _uiState.value = _uiState.value.copy(showVlmPromptDialog = false)
    }

    fun submitScannedRegionQuestion(question: String, pngBytesOverride: ByteArray? = null) {
        val cleanQuestion = question.trim()
        val png = pngBytesOverride ?: _uiState.value.activeCropPngBytes
        if (cleanQuestion.isEmpty() || png == null || png.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                statusBanner = "请先框选区域并输入问题",
            )
            return
        }

        val pageIndex = _uiState.value.viewportState.pageIndex
        _uiState.value = _uiState.value.copy(
            showVlmPromptDialog = false,
            showVlmResultSheet = true,
            isVlmStreaming = true,
            vlmThinkingText = "",
            vlmAnswerMarkdown = "",
            vlmFailure = null,
        )

        vlmJob?.cancel()
        vlmJob = viewModelScope.launch {
            val thinkingBuf = StringBuilder()
            val answerBuf = StringBuilder()
            companionEngine.askScannedRegion(
                workId = workId,
                pageIndex = pageIndex,
                pngBytes = png,
                question = cleanQuestion,
            ).collect { event ->
                when (event) {
                    is CompanionEvent.StreamingDelta -> {
                        if (event.isThinking) thinkingBuf.append(event.delta) else answerBuf.append(event.delta)
                        _uiState.value = _uiState.value.copy(
                            vlmThinkingText = thinkingBuf.toString(),
                            vlmAnswerMarkdown = answerBuf.toString(),
                        )
                    }
                    is CompanionEvent.Done -> {
                        val md = (event.artifact as? AuditedArtifact.MarkdownReadCard)?.markdown
                            ?: answerBuf.toString()
                        _uiState.value = _uiState.value.copy(
                            isVlmStreaming = false,
                            vlmAnswerMarkdown = md,
                        )
                    }
                    is CompanionEvent.CachedHit -> {
                        val md = (event.artifact as? AuditedArtifact.MarkdownReadCard)?.markdown
                            ?: event.artifact.summary
                        _uiState.value = _uiState.value.copy(
                            isVlmStreaming = false,
                            vlmAnswerMarkdown = md,
                        )
                    }
                    is CompanionEvent.Failure -> {
                        _uiState.value = _uiState.value.copy(
                            isVlmStreaming = false,
                            vlmFailure = event.error,
                        )
                    }
                }
            }
        }
    }

    fun dismissVlmResultSheet() {
        _uiState.value = _uiState.value.copy(showVlmResultSheet = false)
    }

    fun clearStatusBanner() {
        _uiState.value = _uiState.value.copy(statusBanner = null)
    }

    companion object {
        fun factory(
            workId: WorkId,
            libraryVault: LibraryVault,
            readerEngine: ReaderEngine,
            pdfSurfaceController: PdfSurfaceController,
            companionEngine: CompanionEngine,
            appPreferences: AppPreferencesPort,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PdfReaderViewModel(
                    workId = workId,
                    libraryVault = libraryVault,
                    readerEngine = readerEngine,
                    pdfSurfaceController = pdfSurfaceController,
                    companionEngine = companionEngine,
                    appPreferences = appPreferences,
                ) as T
        }
    }
}
