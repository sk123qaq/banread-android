package app.banread.app.ui.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.banread.app.reader.VizHostController
import app.banread.model.AppFailure
import app.banread.model.AuditedArtifact
import app.banread.model.BlockId
import app.banread.model.ChapterOrdinal
import app.banread.model.CompanionEngine
import app.banread.model.CompanionEvent
import app.banread.model.CompanionRequest
import app.banread.model.LibraryVault
import app.banread.model.ReadProfileId
import app.banread.model.ReaderEngine
import app.banread.model.ReaderTheme
import app.banread.model.ReadingAnchor
import app.banread.model.SpoilerGate
import app.banread.model.ToolId
import app.banread.model.ToolScope
import app.banread.model.VizHostEvent
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import app.banread.model.registry.ReadProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class CompanionDetent {
    CLOSED,
    PEEK,
    HALF,
    FULL,
}

enum class CompanionScopeChoice(val labelZh: String) {
    SELECTION("选段"),
    CHAPTER("本章"),
    UP_TO_WATERMARK("截至当前进度"),
    FULL_BOOK("全书"),
}

data class CompanionToolItem(
    val toolId: ToolId,
    val label: String,
    val isCrossSearch: Boolean,
    val isUniversal: Boolean = false,
)

data class CompanionQaMessage(
    val id: String,
    val question: String,
    val thinkingText: String,
    val answerMarkdown: String,
    val isStreaming: Boolean,
    val errorText: String? = null,
)

data class CompanionUiState(
    val workId: WorkId? = null,
    val detent: CompanionDetent = CompanionDetent.CLOSED,
    val activeProfile: ReadProfile? = null,
    val literatureSubKind: String? = null,
    val allProfiles: List<ReadProfile> = emptyList(),
    val literatureSubKindHints: Map<String, String> = emptyMap(),
    val scopeChoice: CompanionScopeChoice = CompanionScopeChoice.CHAPTER,
    val currentChapterOrdinal: ChapterOrdinal = ChapterOrdinal(1),
    val currentWatermark: ChapterOrdinal = ChapterOrdinal(1),
    val fullBookUnlocked: Boolean = false,
    val selectionAnchor: ReadingAnchor? = null,
    val selectedText: String = "",
    val tools: List<CompanionToolItem> = emptyList(),
    val activeToolId: ToolId = ToolId("chapter_map"),
    val isRunningTool: Boolean = false,
    val activeArtifact: AuditedArtifact? = null,
    val streamingThinking: String = "",
    val streamingMarkdown: String = "",
    val toolFailure: AppFailure? = null,
    val qaMessages: List<CompanionQaMessage> = emptyList(),
    val showSpoilerUnlockDialog: Boolean = false,
    val showPerspectivePicker: Boolean = false,
    val isFullscreenVizOpen: Boolean = false,
    val toastMessage: String? = null,
)

class CompanionViewModel(
    private val companionEngine: CompanionEngine,
    private val libraryVault: LibraryVault,
    private val readerEngine: ReaderEngine,
    private val profileRegistry: ProfileRegistry,
    private val vizHostController: VizHostController,
) : ViewModel() {

    private val deduplicatedProfiles = profileRegistry.profiles.distinctBy { it.readType }
    private var activeTheme: ReaderTheme = ReaderTheme.SEPIA_WARM
    private var toolJob: Job? = null
    private var qaJob: Job? = null
    private var currentThreadId: String = UUID.randomUUID().toString()
    private var onJumpCallback: ((ReadingAnchor) -> Unit)? = null

    private val _uiState = MutableStateFlow(
        CompanionUiState(
            allProfiles = deduplicatedProfiles,
            literatureSubKindHints = profileRegistry.literatureSubKindHints,
        ),
    )
    val uiState: StateFlow<CompanionUiState> = _uiState.asStateFlow()

    init {
        vizHostController.attachWebView { event ->
            when (event) {
                is VizHostEvent.JumpToBlock -> jumpToBlock(event.blockId)
                VizHostEvent.OpenFullscreen -> setFullscreenVizOpen(!_uiState.value.isFullscreenVizOpen)
                is VizHostEvent.Error -> {
                    _uiState.value = _uiState.value.copy(
                        toastMessage = "图解渲染提示：${event.message}",
                    )
                }
            }
        }
    }

    fun setTheme(theme: ReaderTheme) {
        activeTheme = theme
        viewModelScope.launch {
            vizHostController.updateTheme(theme)
        }
    }

    fun openSheet(
        workId: WorkId,
        currentOrdinal: ChapterOrdinal,
        detent: CompanionDetent = CompanionDetent.HALF,
        selectionAnchor: ReadingAnchor? = null,
        selectedText: String = "",
        initialToolId: ToolId? = null,
        onJumpedToReader: ((ReadingAnchor) -> Unit)? = null,
    ) {
        onJumpCallback = onJumpedToReader
        val isNewWork = _uiState.value.workId != workId
        if (isNewWork) {
            currentThreadId = UUID.randomUUID().toString()
        }
        viewModelScope.launch {
            val toolbox = companionEngine.recommendedTools(workId)
            val profile = profileRegistry.profileById(toolbox.profileId)
            val gate = readerEngine.currentSpoilerGate(workId)
            val watermark = (gate as? SpoilerGate.UpTo)?.unlockedMaxOrdinal ?: currentOrdinal
            val fullUnlocked = gate is SpoilerGate.FullBookUnlocked

            val tools = buildToolStrip(profile)
            val pickedTool = initialToolId
                ?: if (isNewWork) (tools.firstOrNull { it.toolId.raw != "chapter_map" }?.toolId ?: ToolId("chapter_map")) else _uiState.value.activeToolId

            val initialScope = if (selectionAnchor != null && selectedText.isNotBlank()) {
                CompanionScopeChoice.SELECTION
            } else if (isNewWork) {
                CompanionScopeChoice.CHAPTER
            } else {
                _uiState.value.scopeChoice
            }

            _uiState.value = _uiState.value.copy(
                workId = workId,
                detent = detent,
                activeProfile = profile,
                literatureSubKind = toolbox.literatureSubKind,
                scopeChoice = initialScope,
                currentChapterOrdinal = currentOrdinal,
                currentWatermark = watermark,
                fullBookUnlocked = fullUnlocked,
                selectionAnchor = selectionAnchor ?: _uiState.value.selectionAnchor,
                selectedText = selectedText.ifBlank { _uiState.value.selectedText },
                tools = tools,
                activeToolId = pickedTool,
                qaMessages = if (isNewWork) emptyList() else _uiState.value.qaMessages,
            )

            runToolInternal(pickedTool, forceRefresh = false)
        }
    }

    fun updateReaderContext(currentOrdinal: ChapterOrdinal, selectionAnchor: ReadingAnchor?, selectedText: String) {
        _uiState.value = _uiState.value.copy(
            currentChapterOrdinal = currentOrdinal,
            selectionAnchor = selectionAnchor ?: _uiState.value.selectionAnchor,
            selectedText = selectedText.ifBlank { _uiState.value.selectedText },
        )
    }

    fun setDetent(detent: CompanionDetent) {
        _uiState.value = _uiState.value.copy(detent = detent)
        if (detent == CompanionDetent.CLOSED) {
            setFullscreenVizOpen(false)
        }
    }

    fun selectScope(choice: CompanionScopeChoice) {
        if (choice == CompanionScopeChoice.FULL_BOOK && !_uiState.value.fullBookUnlocked) {
            _uiState.value = _uiState.value.copy(showSpoilerUnlockDialog = true)
            return
        }
        _uiState.value = _uiState.value.copy(scopeChoice = choice)
        runToolInternal(_uiState.value.activeToolId, forceRefresh = false)
    }

    fun confirmFullBookUnlock() {
        _uiState.value = _uiState.value.copy(
            fullBookUnlocked = true,
            showSpoilerUnlockDialog = false,
            scopeChoice = CompanionScopeChoice.FULL_BOOK,
        )
        runToolInternal(_uiState.value.activeToolId, forceRefresh = false)
    }

    fun dismissSpoilerUnlockDialog() {
        _uiState.value = _uiState.value.copy(showSpoilerUnlockDialog = false)
    }

    fun relockToWatermark() {
        val nextScope = if (_uiState.value.scopeChoice == CompanionScopeChoice.FULL_BOOK) {
            CompanionScopeChoice.CHAPTER
        } else {
            _uiState.value.scopeChoice
        }
        _uiState.value = _uiState.value.copy(
            fullBookUnlocked = false,
            scopeChoice = nextScope,
        )
    }

    fun openPerspectivePicker() {
        _uiState.value = _uiState.value.copy(showPerspectivePicker = true)
    }

    fun closePerspectivePicker() {
        _uiState.value = _uiState.value.copy(showPerspectivePicker = false)
    }

    fun switchPerspective(profileId: ReadProfileId, literatureSubKind: String?) {
        val wId = _uiState.value.workId ?: return
        viewModelScope.launch {
            val cleanSub = if (profileId.raw == "literature") literatureSubKind?.takeIf { it.isNotBlank() } else null
            libraryVault.overrideProfile(wId, profileId, cleanSub)
            val profile = profileRegistry.profileById(profileId)
            val tools = buildToolStrip(profile)
            val firstTool = profile.blueprints.firstOrNull()?.toolId ?: ToolId("chapter_map")
            _uiState.value = _uiState.value.copy(
                activeProfile = profile,
                literatureSubKind = cleanSub,
                tools = tools,
                activeToolId = firstTool,
                showPerspectivePicker = false,
            )
            runToolInternal(firstTool, forceRefresh = false)
        }
    }

    fun selectTool(toolId: ToolId, forceRefresh: Boolean = false) {
        _uiState.value = _uiState.value.copy(activeToolId = toolId)
        runToolInternal(toolId, forceRefresh = forceRefresh)
    }

    private fun runToolInternal(toolId: ToolId, forceRefresh: Boolean) {
        val state = _uiState.value
        val wId = state.workId ?: return
        toolJob?.cancel()
        toolJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRunningTool = true,
                toolFailure = null,
                streamingThinking = "",
                streamingMarkdown = "",
            )
            val request = CompanionRequest(
                workId = wId,
                tool = toolId,
                scope = resolveToolScope(_uiState.value),
                gate = resolveSpoilerGate(_uiState.value),
                forceRefresh = forceRefresh,
            )
            val thinkingBuf = StringBuilder()
            val mdBuf = StringBuilder()
            companionEngine.runTool(request).collect { event ->
                when (event) {
                    is CompanionEvent.CachedHit -> {
                        renderArtifactIfViz(event.artifact)
                        _uiState.value = _uiState.value.copy(
                            isRunningTool = false,
                            activeArtifact = event.artifact,
                            toolFailure = null,
                        )
                    }
                    is CompanionEvent.StreamingDelta -> {
                        if (event.isThinking) {
                            thinkingBuf.append(event.delta)
                        } else {
                            mdBuf.append(event.delta)
                        }
                        _uiState.value = _uiState.value.copy(
                            streamingThinking = thinkingBuf.toString(),
                            streamingMarkdown = mdBuf.toString(),
                        )
                    }
                    is CompanionEvent.Done -> {
                        renderArtifactIfViz(event.artifact)
                        _uiState.value = _uiState.value.copy(
                            isRunningTool = false,
                            activeArtifact = event.artifact,
                            toolFailure = null,
                        )
                    }
                    is CompanionEvent.Failure -> {
                        _uiState.value = _uiState.value.copy(
                            isRunningTool = false,
                            toolFailure = event.error,
                        )
                    }
                }
            }
        }
    }

    fun askFollowUp(question: String) {
        val cleanQuestion = question.trim()
        val state = _uiState.value
        val wId = state.workId ?: return
        if (cleanQuestion.isEmpty()) return

        if (_uiState.value.detent == CompanionDetent.PEEK) {
            _uiState.value = _uiState.value.copy(detent = CompanionDetent.HALF)
        }

        val msgId = UUID.randomUUID().toString()
        val initialMsg = CompanionQaMessage(
            id = msgId,
            question = cleanQuestion,
            thinkingText = "",
            answerMarkdown = "",
            isStreaming = true,
        )
        _uiState.value = _uiState.value.copy(qaMessages = _uiState.value.qaMessages + initialMsg)

        qaJob?.cancel()
        qaJob = viewModelScope.launch {
            val thinkingBuf = StringBuilder()
            val answerBuf = StringBuilder()
            companionEngine.askFollowUp(
                threadId = currentThreadId,
                workId = wId,
                scope = resolveToolScope(_uiState.value),
                gate = resolveSpoilerGate(_uiState.value),
                question = cleanQuestion,
            ).collect { event ->
                when (event) {
                    is CompanionEvent.StreamingDelta -> {
                        if (event.isThinking) thinkingBuf.append(event.delta) else answerBuf.append(event.delta)
                        updateQaMessage(msgId) {
                            it.copy(
                                thinkingText = thinkingBuf.toString(),
                                answerMarkdown = answerBuf.toString(),
                                isStreaming = true,
                            )
                        }
                    }
                    is CompanionEvent.Done -> {
                        val md = (event.artifact as? AuditedArtifact.MarkdownReadCard)?.markdown
                            ?: answerBuf.toString()
                        updateQaMessage(msgId) {
                            it.copy(
                                thinkingText = thinkingBuf.toString(),
                                answerMarkdown = md,
                                isStreaming = false,
                            )
                        }
                    }
                    is CompanionEvent.CachedHit -> {
                        val md = (event.artifact as? AuditedArtifact.MarkdownReadCard)?.markdown
                            ?: event.artifact.summary
                        updateQaMessage(msgId) {
                            it.copy(answerMarkdown = md, isStreaming = false)
                        }
                    }
                    is CompanionEvent.Failure -> {
                        updateQaMessage(msgId) {
                            it.copy(
                                isStreaming = false,
                                errorText = formatFailureMessage(event.error),
                            )
                        }
                    }
                }
            }
        }
    }

    fun jumpToBlock(blockId: BlockId) {
        val state = _uiState.value
        val wId = state.workId ?: return
        viewModelScope.launch {
            val preferredOrdinal = when (state.scopeChoice) {
                CompanionScopeChoice.SELECTION -> state.selectionAnchor?.ordinal ?: state.currentChapterOrdinal
                CompanionScopeChoice.CHAPTER, CompanionScopeChoice.UP_TO_WATERMARK, CompanionScopeChoice.FULL_BOOK ->
                    state.currentChapterOrdinal
            }

            val anchor = runCatching {
                libraryVault.resolveAnchor(wId, preferredOrdinal, blockId)
            }.getOrElse {
                val outline = runCatching { libraryVault.getWorkOutline(wId) }.getOrNull()
                val candidates = outline?.bodyChapters?.map { it.ordinal }.orEmpty()
                var resolved: ReadingAnchor? = null
                for (ord in candidates) {
                    val attempt = runCatching { libraryVault.resolveAnchor(wId, ord, blockId) }.getOrNull()
                    if (attempt != null) {
                        resolved = attempt
                        break
                    }
                }
                resolved
            }

            if (anchor == null) {
                _uiState.value = _uiState.value.copy(toastMessage = "未在当前解锁章节定位到 [${blockId.value}]")
                return@launch
            }

            setFullscreenVizOpen(false)
            _uiState.value = _uiState.value.copy(
                detent = CompanionDetent.PEEK,
                toastMessage = "已定位至原文 [${blockId.value}]",
            )
            readerEngine.goTo(anchor, highlightPulseMs = 2_000L)
            onJumpCallback?.invoke(anchor)
        }
    }

    fun setFullscreenVizOpen(open: Boolean) {
        if (_uiState.value.isFullscreenVizOpen == open) return
        _uiState.value = _uiState.value.copy(isFullscreenVizOpen = open)
        vizHostController.setFullscreenMode(open)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    private suspend fun renderArtifactIfViz(artifact: AuditedArtifact) {
        if (artifact is AuditedArtifact.StructuredDiagram || artifact is AuditedArtifact.FallbackList) {
            vizHostController.renderArtifact(artifact, activeTheme)
        }
    }

    private fun updateQaMessage(id: String, transform: (CompanionQaMessage) -> CompanionQaMessage) {
        _uiState.value = _uiState.value.copy(
            qaMessages = _uiState.value.qaMessages.map { if (it.id == id) transform(it) else it },
        )
    }

    private fun buildToolStrip(profile: ReadProfile): List<CompanionToolItem> {
        val list = mutableListOf<CompanionToolItem>()
        val seen = mutableSetOf<String>()

        fun addTool(id: String, label: String, isCrossSearch: Boolean, isUniversal: Boolean = false) {
            if (seen.add(id)) {
                list += CompanionToolItem(
                    toolId = ToolId(id),
                    label = label,
                    isCrossSearch = isCrossSearch,
                    isUniversal = isUniversal,
                )
            }
        }

        addTool("chapter_map", "章节导图", isCrossSearch = false)
        profile.blueprints.forEach { bp ->
            addTool(bp.blueprint, bp.label, isCrossSearch = false)
        }
        profile.extensions.forEach { ext ->
            addTool(ext.action, ext.label, isCrossSearch = ext.crossSearch)
        }
        addTool("diagram", "一键图解", isCrossSearch = false, isUniversal = true)
        addTool("explain", "白话解释", isCrossSearch = false, isUniversal = true)
        addTool("translate", "对读翻译", isCrossSearch = false, isUniversal = true)
        addTool("critique", "细读审视", isCrossSearch = false, isUniversal = true)
        addTool("ask", "自由问答", isCrossSearch = false, isUniversal = true)
        return list
    }

    private fun resolveToolScope(state: CompanionUiState): ToolScope = when (state.scopeChoice) {
        CompanionScopeChoice.SELECTION -> {
            val anchor = state.selectionAnchor
            if (anchor != null && state.selectedText.isNotBlank()) {
                ToolScope.Selection(anchor, state.selectedText)
            } else {
                ToolScope.Chapter(state.currentChapterOrdinal)
            }
        }
        CompanionScopeChoice.CHAPTER -> ToolScope.Chapter(state.currentChapterOrdinal)
        CompanionScopeChoice.UP_TO_WATERMARK, CompanionScopeChoice.FULL_BOOK -> ToolScope.FullBook
    }

    private fun resolveSpoilerGate(state: CompanionUiState): SpoilerGate =
        if (state.fullBookUnlocked) {
            SpoilerGate.FullBookUnlocked
        } else {
            SpoilerGate.UpTo(state.currentWatermark)
        }

    private fun formatFailureMessage(failure: AppFailure): String = when (failure) {
        is AppFailure.DesktopConnectionRequired -> "需连接桌面伴读网关（${failure.featureName}）"
        is AppFailure.ProviderAuthOrQuota -> failure.message
        is AppFailure.CapacityPreflightBlocked -> "当前范围受防剧透或上下文限制：${failure.reason}"
        is AppFailure.UnsupportedDrmOrFormat -> failure.reason
        is AppFailure.ScannedPdfNeedsOcr -> "扫描版 PDF 需先 OCR 后才能生成全章文本伴读"
        is AppFailure.NetworkOrSyncConflict -> failure.message
    }

    companion object {
        fun factory(
            companionEngine: CompanionEngine,
            libraryVault: LibraryVault,
            readerEngine: ReaderEngine,
            profileRegistry: ProfileRegistry,
            vizHostController: VizHostController,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CompanionViewModel(
                    companionEngine = companionEngine,
                    libraryVault = libraryVault,
                    readerEngine = readerEngine,
                    profileRegistry = profileRegistry,
                    vizHostController = vizHostController,
                ) as T
        }
    }
}
