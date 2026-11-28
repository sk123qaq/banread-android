package app.banread.app

import android.content.Context
import android.webkit.WebView
import app.banread.app.reader.PdfSurfaceController
import app.banread.app.reader.ReflowSurfaceController
import app.banread.app.reader.VizHostController
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.AuditedArtifact
import app.banread.model.BlockId
import app.banread.model.BookSlice
import app.banread.model.ChapterContentHash
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterOutlineItem
import app.banread.model.ChapterRole
import app.banread.model.CompanionEngine
import app.banread.model.CompanionEvent
import app.banread.model.CompanionRequest
import app.banread.model.CoverDisplayMode
import app.banread.model.DeviceId
import app.banread.model.DeviceProgressShard
import app.banread.model.FootnoteDetail
import app.banread.model.FootnoteLink
import app.banread.model.ImportedWork
import app.banread.model.LibraryVault
import app.banread.model.NoteLogEntry
import app.banread.model.ProfileToolbox
import app.banread.model.ReadProfileId
import app.banread.model.ReaderEngine
import app.banread.model.ReaderSessionHandle
import app.banread.model.ReaderSessionState
import app.banread.model.ReaderTheme
import app.banread.model.ReadingAnchor
import app.banread.model.ReadingSurfaceMode
import app.banread.model.ReflowPrepProgress
import app.banread.model.TextQuote
import app.banread.model.TokenEstimate
import app.banread.model.ToolId
import app.banread.model.ToolScope
import app.banread.model.TypographyProfile
import app.banread.model.VizHostEvent
import app.banread.model.WorkId
import app.banread.model.WorkOutline
import app.banread.model.WorkSummary
import app.banread.model.registry.ProfileRegistry
import app.banread.reader.FootnoteClickEvent
import app.banread.reader.PaginationMode
import app.banread.reader.PdfAnnotationOverlay
import app.banread.reader.PdfViewportState
import app.banread.reader.RenderedPdfPage
import app.banread.reader.TextSelectionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

internal fun loadTestProfileRegistry(): ProfileRegistry {
    val assetFile = File("src/main/assets/profiles.generated.json")
    val json = if (assetFile.isFile) {
        assetFile.readText(Charsets.UTF_8)
    } else {
        ProfileRegistry::class.java.getResourceAsStream("/profiles.generated.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    return ProfileRegistry.fromJson(json)
}

internal class FakeLibraryVault : LibraryVault {
    val worksFlow = MutableStateFlow<List<WorkSummary>>(emptyList())
    val outlines = mutableMapOf<String, WorkOutline>()
    val footnotes = mutableMapOf<String, FootnoteDetail>()
    var importLocalHandler: suspend (String) -> Result<ImportedWork> = {
        Result.failure(AppFailureException(AppFailure.UnsupportedDrmOrFormat("Not configured")))
    }
    var ingestPackHandler: suspend (String) -> Result<ImportedWork> = {
        Result.failure(AppFailureException(AppFailure.UnsupportedDrmOrFormat("Not configured")))
    }
    val deletedWorkIds = mutableListOf<WorkId>()
    val overriddenProfiles = mutableListOf<Triple<WorkId, ReadProfileId, String?>>()

    override suspend fun importLocalBook(sourceUri: String): Result<ImportedWork> =
        importLocalHandler(sourceUri)

    override suspend fun ingestStudyPack(zipUriOrFile: String): Result<ImportedWork> =
        ingestPackHandler(zipUriOrFile)

    override suspend fun prepareTextPdfReflow(workId: WorkId): Flow<ReflowPrepProgress> =
        flowOf(ReflowPrepProgress(workId, 1, 1, completed = true))

    override suspend fun getWorkOutline(workId: WorkId): WorkOutline =
        outlines[workId.raw] ?: WorkOutline(
            workId = workId,
            title = worksFlow.value.firstOrNull { it.workId == workId }?.title ?: workId.raw,
            bodyChapters = listOf(
                ChapterOutlineItem(ChapterOrdinal(2), "第 2 章", ChapterRole.BODY, 10),
                ChapterOutlineItem(ChapterOrdinal(3), "第 3 章", ChapterRole.BODY, 12),
                ChapterOutlineItem(ChapterOrdinal(10), "第 10 章", ChapterRole.BODY, 14),
            ),
            notesChapters = listOf(
                ChapterOutlineItem(ChapterOrdinal(11), "全书注释", ChapterRole.NOTES, 4),
            ),
            frontBackChapters = listOf(
                ChapterOutlineItem(ChapterOrdinal(1), "扉页与版权", ChapterRole.FRONT_BACK, 1),
            ),
            readProfileId = worksFlow.value.firstOrNull { it.workId == workId }?.readProfileId,
            literatureSubKind = worksFlow.value.firstOrNull { it.workId == workId }?.literatureSubKind,
        )

    override suspend fun resolveAnchor(
        workId: WorkId,
        ordinal: ChapterOrdinal,
        blockId: BlockId,
    ): ReadingAnchor = ReadingAnchor.ReflowCfi(
        ordinal = ordinal,
        href = "OEBPS/ch_${ordinal.twoDigit()}.xhtml",
        progression = 0.25f,
        totalProgression = 0.25f,
        cfi = "epubcfi(/6/${ordinal.value * 2}!/4/2)",
        textQuote = TextQuote("", "sample-${blockId.value}", ""),
        blockId = blockId,
    )

    override suspend fun resolveFootnote(
        workId: WorkId,
        currentOrdinal: ChapterOrdinal,
        markerOrHref: String,
    ): FootnoteDetail? = footnotes[markerOrHref]

    override suspend fun overrideProfile(
        workId: WorkId,
        profile: ReadProfileId,
        literatureSubKind: String?,
    ) {
        overriddenProfiles += Triple(workId, profile, literatureSubKind)
        worksFlow.value = worksFlow.value.map {
            if (it.workId == workId) it.copy(readProfileId = profile, literatureSubKind = literatureSubKind) else it
        }
        outlines[workId.raw]?.let { old ->
            outlines[workId.raw] = old.copy(readProfileId = profile, literatureSubKind = literatureSubKind)
        }
    }

    override suspend fun loadBookInputs(workId: WorkId): BookSlice =
        BookSlice(workId, emptyList(), emptyList())

    override suspend fun listWorks(): List<WorkSummary> = worksFlow.value

    override fun observeWorks(): Flow<List<WorkSummary>> = worksFlow.asStateFlow()

    override suspend fun getWorkSummary(workId: WorkId): WorkSummary? =
        worksFlow.value.firstOrNull { it.workId == workId }

    override suspend fun resolveCoverFile(workId: WorkId): File? = null

    override suspend fun deleteWork(workId: WorkId): Result<Unit> {
        deletedWorkIds += workId
        worksFlow.value = worksFlow.value.filterNot { it.workId == workId }
        return Result.success(Unit)
    }
}

internal class FakeReaderEngine : ReaderEngine {
    private val _activeState = MutableStateFlow(ReaderSessionState())
    override val activeState: StateFlow<ReaderSessionState> = _activeState.asStateFlow()

    var initialOrdinalByWork = mutableMapOf<String, ChapterOrdinal>()
    var openCalls = 0
    val appliedThemes = mutableListOf<ReaderTheme>()
    val appliedTypographies = mutableListOf<TypographyProfile>()
    val goToCalls = mutableListOf<Pair<ReadingAnchor, Long>>()
    val savedAnnotations = mutableListOf<Triple<WorkId, ReadingAnchor, Pair<String, String>>>()

    override suspend fun open(workId: WorkId, modePreference: ReadingSurfaceMode): ReaderSessionHandle {
        openCalls++
        val startOrd = initialOrdinalByWork.getOrPut(workId.raw) { ChapterOrdinal(2) }
        val anchor = ReadingAnchor.ReflowCfi(
            ordinal = startOrd,
            href = "OEBPS/ch_${startOrd.twoDigit()}.xhtml",
            progression = 0f,
            totalProgression = 0f,
            cfi = "",
            textQuote = TextQuote("", "", ""),
            blockId = BlockId("B01"),
        )
        _activeState.value = ReaderSessionState(
            workId = workId,
            mode = modePreference,
            currentCursor = anchor,
        )
        return ReaderSessionHandle(workId, modePreference, anchor)
    }

    override suspend fun switchPdfSurface(workId: WorkId, targetMode: ReadingSurfaceMode): Result<Unit> {
        _activeState.value = _activeState.value.copy(mode = targetMode)
        return Result.success(Unit)
    }

    override suspend fun goTo(anchor: ReadingAnchor, highlightPulseMs: Long) {
        goToCalls += anchor to highlightPulseMs
        _activeState.value = _activeState.value.copy(currentCursor = anchor)
    }

    override suspend fun applyTypography(profile: TypographyProfile) {
        appliedTypographies += profile
    }

    override suspend fun applyTheme(theme: ReaderTheme) {
        appliedThemes += theme
    }

    override suspend fun saveAnnotation(
        workId: WorkId,
        anchor: ReadingAnchor,
        colorToken: String,
        comment: String,
    ) {
        savedAnnotations += Triple(workId, anchor, colorToken to comment)
    }

    val mergedRemoteNotes = mutableListOf<NoteLogEntry>()

    override suspend fun snapshotProgress(workId: WorkId): DeviceProgressShard? {
        val state = _activeState.value
        val cursor = state.takeIf { it.workId == workId }?.currentCursor ?: return null
        return DeviceProgressShard(
            workId = workId,
            deviceId = DeviceId("fake-android"),
            cursor = cursor,
            updatedAtEpochMs = 1_000L,
        )
    }

    override suspend fun listNoteLogEntries(workId: WorkId, includeDeleted: Boolean): List<NoteLogEntry> =
        mergedRemoteNotes.filter { it.workId == workId && (includeDeleted || it.deletedAtEpochMs == null) }

    override suspend fun mergeRemoteAnnotations(workId: WorkId, entries: List<NoteLogEntry>): Int {
        mergedRemoteNotes += entries
        return mergedRemoteNotes.count { it.workId == workId && it.deletedAtEpochMs == null }
    }
}

internal class FakeReflowSurfaceController : ReflowSurfaceController {
    private val _footnotes = MutableSharedFlow<FootnoteClickEvent>(extraBufferCapacity = 8)
    override val footnoteEvents: Flow<FootnoteClickEvent> = _footnotes.asSharedFlow()

    private val _selections = MutableSharedFlow<TextSelectionEvent>(extraBufferCapacity = 8)
    override val selectionEvents: Flow<TextSelectionEvent> = _selections.asSharedFlow()

    var prevCount = 0
    var nextCount = 0
    var lastPaginationMode: PaginationMode? = null

    override fun prevPage() {
        prevCount++
    }

    override fun nextPage() {
        nextCount++
    }

    override fun setPaginationMode(mode: PaginationMode) {
        lastPaginationMode = mode
    }

    fun emitFootnote(event: FootnoteClickEvent) {
        _footnotes.tryEmit(event)
    }

    fun emitSelection(event: TextSelectionEvent) {
        _selections.tryEmit(event)
    }
}

internal class FakePdfSurfaceController : PdfSurfaceController {
    private val _viewport = MutableStateFlow(PdfViewportState(pageIndex = 0, pageCount = 5))
    override val viewportState: StateFlow<PdfViewportState> = _viewport.asStateFlow()

    var renderCalls = 0

    override fun setZoomAndPan(scale: Float, panX: Float, panY: Float) {
        _viewport.value = _viewport.value.copy(zoomScale = scale, panX = panX, panY = panY)
    }

    override fun setAutoCrop(enabled: Boolean) {
        _viewport.value = _viewport.value.copy(autoCropEnabled = enabled)
    }

    override suspend fun nextPage() {
        val next = (_viewport.value.pageIndex + 1).coerceAtMost(_viewport.value.pageCount - 1)
        _viewport.value = _viewport.value.copy(pageIndex = next)
    }

    override suspend fun prevPage() {
        val prev = (_viewport.value.pageIndex - 1).coerceAtLeast(0)
        _viewport.value = _viewport.value.copy(pageIndex = prev)
    }

    override suspend fun renderCurrentPage(targetWidthPx: Int): RenderedPdfPage? {
        renderCalls++
        return null
    }

    override fun annotationsForPage(pageIndex: Int): List<PdfAnnotationOverlay> = emptyList()
}

internal class FakeVizHostController : VizHostController {
    var hostCallback: ((VizHostEvent) -> Unit)? = null
    val renderedArtifacts = mutableListOf<Pair<AuditedArtifact, ReaderTheme>>()
    val fullscreenHistory = mutableListOf<Boolean>()
    var updatedThemes = mutableListOf<ReaderTheme>()

    override fun attachWebView(hostCallback: (VizHostEvent) -> Unit) {
        this.hostCallback = hostCallback
    }

    override suspend fun renderArtifact(artifact: AuditedArtifact, theme: ReaderTheme) {
        renderedArtifacts += artifact to theme
    }

    override suspend fun updateTheme(theme: ReaderTheme) {
        updatedThemes += theme
    }

    override fun setFullscreenMode(enabled: Boolean) {
        fullscreenHistory += enabled
    }

    override fun acquireWebView(context: Context): WebView {
        error("Not used in JVM unit tests")
    }

    override fun detachWebView() = Unit
}

internal class FakeCompanionEngine(
    private val profileRegistry: ProfileRegistry,
) : CompanionEngine {

    var llmTokenCalls = 0
    val runToolRequests = mutableListOf<CompanionRequest>()
    var scannedRegionHandler: (WorkId, Int, ByteArray, String) -> Flow<CompanionEvent> = { _, pageIdx, _, _ ->
        flow {
            emit(CompanionEvent.StreamingDelta("Thinking...", isThinking = true))
            emit(CompanionEvent.StreamingDelta("解析结论", isThinking = false))
            emit(
                CompanionEvent.Done(
                    AuditedArtifact.MarkdownReadCard(
                        caption = "第 ${pageIdx + 1} 页框选图问",
                        markdown = "解析结论 [原文]",
                    ),
                ),
            )
        }
    }

    override suspend fun recommendedTools(workId: WorkId): ProfileToolbox {
        val p = profileRegistry.profileById(ReadProfileId("history"))
        return ProfileToolbox(
            profileId = p.readType,
            literatureSubKind = null,
            blueprints = p.blueprints.map { it.toolId },
            extensions = p.extensions.map { it.toolId },
        )
    }

    override fun runTool(request: CompanionRequest): Flow<CompanionEvent> = flow {
        runToolRequests += request
        if (profileRegistry.isCrossSearchTool(request.tool)) {
            // Strict 0 LLM tokens consumed when desktop gateway is offline
            emit(CompanionEvent.Failure(AppFailure.DesktopConnectionRequired(request.tool.raw)))
            return@flow
        }
        llmTokenCalls++
        emit(
            CompanionEvent.Done(
                AuditedArtifact.MarkdownReadCard(
                    caption = "工具 ${request.tool.raw}",
                    markdown = "分析段落 [B02]",
                ),
            ),
        )
    }

    override fun askFollowUp(
        threadId: String?,
        workId: WorkId,
        scope: ToolScope,
        question: String,
    ): Flow<CompanionEvent> = flow {
        llmTokenCalls++
        emit(CompanionEvent.StreamingDelta("推理中", isThinking = true))
        emit(CompanionEvent.StreamingDelta("回答：$question [B02]", isThinking = false))
        emit(
            CompanionEvent.Done(
                AuditedArtifact.MarkdownReadCard(
                    caption = "追问",
                    markdown = "回答：$question [B02]",
                ),
            ),
        )
    }

    override fun askScannedRegion(
        workId: WorkId,
        pageIndex: Int,
        pngBytes: ByteArray,
        question: String,
    ): Flow<CompanionEvent> = scannedRegionHandler(workId, pageIndex, pngBytes, question)

    override suspend fun estimateTokenCost(request: CompanionRequest): TokenEstimate =
        TokenEstimate(100L, 100L, true)

    override suspend fun importDesktopPrecomputedCache(workId: WorkId, bundleCacheDir: String) = Unit
}

internal fun sampleWorkSummary(
    id: String,
    title: String,
    author: String = "测试作者",
    profileId: String = "literature",
    format: String = "EPUB",
    isScannedPdf: Boolean = false,
    coverDisplayMode: CoverDisplayMode = CoverDisplayMode.CLOTH_FALLBACK,
): WorkSummary = WorkSummary(
    workId = WorkId(id),
    title = title,
    author = author,
    readProfileId = ReadProfileId(profileId),
    literatureSubKind = if (profileId == "literature") "fiction" else null,
    clothColor = "#1E3A36",
    format = format,
    supportedModes = if (format == "PDF") setOf(ReadingSurfaceMode.PDF_FIXED) else setOf(ReadingSurfaceMode.REFLOWABLE),
    isScannedPdf = isScannedPdf,
    coverDisplayMode = coverDisplayMode,
)
