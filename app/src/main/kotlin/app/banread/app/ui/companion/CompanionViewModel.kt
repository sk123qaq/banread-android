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
import app.banread.model.ToolId
import app.banread.model.ToolScope
import app.banread.model.VizHostEvent
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import app.banread.model.registry.ReadProfile
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
    val selectionAnchor: ReadingAnchor? = null,
    val selectedText: String = "",
    val tools: List<CompanionToolItem> = emptyList(),
    val activeToolId: ToolId = ToolId("chapter_map"),
    val isRunningTool: Boolean = false,
    val vizContentHeightPx: Int = 0,
    val activeArtifact: AuditedArtifact? = null,
    val streamingThinking: String = "",
    val streamingMarkdown: String = "",
    val toolFailure: AppFailure? = null,
    val qaMessages: List<CompanionQaMessage> = emptyList(),
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
    private val tasks = CompanionTaskRegistry()
    private var currentThreadId: String = UUID.randomUUID().toString()
    private var onJumpCallback: ((ReadingAnchor) -> Unit)? = null
    private var renderedArtifact: AuditedArtifact? = null
    private var vizRenderGeneration: Int = 0

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
                is VizHostEvent.Rendered -> {
                    if (!_uiState.value.isFullscreenVizOpen && event.heightPx > 0) {
                        _uiState.value = _uiState.value.copy(vizContentHeightPx = event.heightPx)
                    }
                }
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

            val tools = buildToolStrip(profile)
            val requestedTool = initialToolId ?: if (isNewWork) null else _uiState.value.activeToolId
            val pickedTool = tools.firstOrNull { it.toolId == requestedTool }?.toolId
                ?: tools.firstOrNull { it.toolId.raw != "chapter_map" }?.toolId
                ?: ToolId("chapter_map")

            val hasSelection = selectionAnchor != null && selectedText.isNotBlank()
            val initialScope = when {
                hasSelection -> CompanionScopeChoice.SELECTION
                isNewWork -> CompanionScopeChoice.CHAPTER
                _uiState.value.scopeChoice == CompanionScopeChoice.SELECTION -> CompanionScopeChoice.CHAPTER
                else -> _uiState.value.scopeChoice
            }

            _uiState.value = _uiState.value.copy(
                workId = workId,
                detent = detent,
                activeProfile = profile,
                literatureSubKind = toolbox.literatureSubKind,
                scopeChoice = initialScope,
                currentChapterOrdinal = currentOrdinal,
                selectionAnchor = selectionAnchor.takeIf { hasSelection },
                selectedText = if (hasSelection) selectedText else "",
                tools = tools,
                activeToolId = pickedTool,
                qaMessages = if (isNewWork) emptyList() else _uiState.value.qaMessages,
            )

            runToolInternal(pickedTool, forceRefresh = false)
        }
    }

    fun onReaderChapterChanged(ordinal: ChapterOrdinal) {
        val state = _uiState.value
        if (state.workId == null || state.currentChapterOrdinal == ordinal) return
        val hadSelection = state.selectionAnchor != null || state.selectedText.isNotBlank()
        val nextScope = if (state.scopeChoice == CompanionScopeChoice.SELECTION) {
            CompanionScopeChoice.CHAPTER
        } else {
            state.scopeChoice
        }
        _uiState.value = state.copy(
            currentChapterOrdinal = ordinal,
            scopeChoice = nextScope,
            selectionAnchor = if (hadSelection) null else state.selectionAnchor,
            selectedText = if (hadSelection) "" else state.selectedText,
        )
        publish()
    }

    fun setDetent(detent: CompanionDetent) {
        _uiState.value = _uiState.value.copy(detent = detent)
        if (detent == CompanionDetent.CLOSED) {
            setFullscreenVizOpen(false)
        }
    }

    fun selectScope(choice: CompanionScopeChoice) {
        _uiState.value = _uiState.value.copy(scopeChoice = choice)
        runToolInternal(_uiState.value.activeToolId, forceRefresh = false)
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
        val scope = resolveToolScope(state)
        val key = CompanionTaskKey.of(wId, scope, canonicalToolId(state, toolId))
        if (tasks.begin(key, forceRefresh) != CompanionTaskRegistry.Start.Launch) {
            publish()
            return
        }
        publish()
        viewModelScope.launch {
            try {
                val request = CompanionRequest(
                    workId = wId,
                    tool = toolId,
                    scope = scope,
                    forceRefresh = forceRefresh,
                )
                companionEngine.runTool(request).collect { event ->
                    when (event) {
                        is CompanionEvent.CachedHit -> tasks.ready(key, event.artifact, event.isStale)
                        is CompanionEvent.StreamingDelta -> {
                            if (event.isThinking) {
                                tasks.appendThinking(key, event.delta)
                            } else {
                                tasks.appendMarkdown(key, event.delta)
                            }
                        }
                        is CompanionEvent.Done -> tasks.ready(key, event.artifact)
                        is CompanionEvent.Failure -> tasks.failed(key, event.error)
                    }
                    publish()
                }
            } finally {
                tasks.finish(key)
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

        val questionScope = resolveToolScope(_uiState.value)
        viewModelScope.launch {
            val thinkingBuf = StringBuilder()
            val answerBuf = StringBuilder()
            try {
                companionEngine.askFollowUp(
                    threadId = currentThreadId,
                    workId = wId,
                    scope = questionScope,
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
            } finally {
                updateQaMessage(msgId) {
                    if (it.isStreaming) it.copy(isStreaming = false) else it
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
                CompanionScopeChoice.CHAPTER, CompanionScopeChoice.FULL_BOOK ->
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
                _uiState.value = _uiState.value.copy(toastMessage = "未在当前章节定位到 [${blockId.value}]")
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

    private fun publish() {
        val key = displayedKey(_uiState.value)
        val slot = key?.let { tasks.slot(it) }
        val base = _uiState.value
        val next = when (slot) {
            null -> base.copy(
                isRunningTool = false,
                activeArtifact = null,
                streamingThinking = "",
                streamingMarkdown = "",
                toolFailure = null,
            )
            is CompanionToolSlot.Running -> base.copy(
                isRunningTool = true,
                activeArtifact = null,
                streamingThinking = slot.thinking,
                streamingMarkdown = slot.markdown,
                toolFailure = null,
            )
            is CompanionToolSlot.Ready -> base.copy(
                isRunningTool = false,
                activeArtifact = slot.artifact,
                streamingThinking = "",
                streamingMarkdown = "",
                toolFailure = null,
            )
            is CompanionToolSlot.Failed -> base.copy(
                isRunningTool = false,
                activeArtifact = null,
                streamingThinking = "",
                streamingMarkdown = "",
                toolFailure = slot.failure,
            )
        }
        _uiState.value = next
        syncVizToDisplayedArtifact(next)
    }

    private fun displayedKey(state: CompanionUiState): CompanionTaskKey? {
        val wId = state.workId ?: return null
        return CompanionTaskKey.of(wId, resolveToolScope(state), canonicalToolId(state, state.activeToolId))
    }

    private fun canonicalToolId(state: CompanionUiState, toolId: ToolId): ToolId =
        if (toolId.raw == "diagram") {
            state.activeProfile?.blueprints?.firstOrNull()?.toolId ?: toolId
        } else {
            toolId
        }

    private fun syncVizToDisplayedArtifact(state: CompanionUiState) {
        val artifact = state.activeArtifact
        if (artifact == null) {
            renderedArtifact = null
            return
        }
        if (artifact == renderedArtifact) return
        if (artifact !is AuditedArtifact.StructuredDiagram && artifact !is AuditedArtifact.FallbackList) {
            renderedArtifact = null
            return
        }
        renderedArtifact = artifact
        val generation = ++vizRenderGeneration
        viewModelScope.launch {
            if (generation == vizRenderGeneration) {
                vizHostController.renderArtifact(artifact, activeTheme)
            }
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
        CompanionScopeChoice.FULL_BOOK -> ToolScope.FullBook
    }

    private fun formatFailureMessage(failure: AppFailure): String = when (failure) {
        is AppFailure.DesktopConnectionRequired -> "需连接桌面伴读网关（${failure.featureName}）"
        is AppFailure.ProviderAuthOrQuota -> failure.message
        is AppFailure.CapacityPreflightBlocked -> "当前范围受上下文限制：${failure.reason}"
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
