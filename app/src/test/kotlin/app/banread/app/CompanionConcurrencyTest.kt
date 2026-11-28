package app.banread.app

import app.banread.app.ui.companion.CompanionDetent
import app.banread.app.ui.companion.CompanionScopeChoice
import app.banread.app.ui.companion.CompanionTaskKey
import app.banread.app.ui.companion.CompanionTaskRegistry
import app.banread.app.ui.companion.CompanionToolSlot
import app.banread.app.ui.companion.CompanionViewModel
import app.banread.model.AppFailure
import app.banread.model.AuditedArtifact
import app.banread.model.BlockId
import app.banread.model.ChapterOrdinal
import app.banread.model.CompanionEvent
import app.banread.model.CompanionRequest
import app.banread.model.ProfileToolbox
import app.banread.model.ReadProfileId
import app.banread.model.ReadingAnchor
import app.banread.model.SharedCompanionPayload
import app.banread.model.TokenEstimate
import app.banread.model.ToolId
import app.banread.model.ToolScope
import app.banread.model.VizHostEvent
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import app.banread.model.CompanionEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionConcurrencyTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val workId = WorkId("w_comp")

    private class Subject(
        val vm: CompanionViewModel,
        val engine: GatedCompanionEngine,
        val viz: FakeVizHostController,
    )

    private fun subject(): Subject {
        val vault = FakeLibraryVault().apply {
            worksFlow.value = listOf(sampleWorkSummary("w_comp", "史记选读", profileId = "history"))
        }
        val registry = loadTestProfileRegistry()
        val engine = GatedCompanionEngine(registry)
        val viz = FakeVizHostController()
        val vm = CompanionViewModel(engine, vault, FakeReaderEngine(), registry, viz)
        return Subject(vm, engine, viz)
    }

    @Test
    fun `repeated taps on the same tool issue a single generation`() = runTest {
        val s = subject()
        s.vm.openSheet(workId, ChapterOrdinal(1))
        val tool = s.vm.uiState.value.activeToolId
        assertEquals(1, s.engine.runToolRequests.size)

        repeat(3) { s.vm.selectTool(tool) }

        assertEquals(1, s.engine.runToolRequests.size)
        assertTrue(s.vm.uiState.value.isRunningTool)
    }

    @Test
    fun `closing and reopening the sheet keeps the running task and its progress`() = runTest {
        val s = subject()
        s.vm.openSheet(workId, ChapterOrdinal(1))
        val tool = s.vm.uiState.value.activeToolId
        assertEquals(1, s.engine.runToolRequests.size)

        s.vm.setDetent(CompanionDetent.CLOSED)
        s.vm.openSheet(workId, ChapterOrdinal(1))

        assertEquals(1, s.engine.runToolRequests.size)
        assertEquals(tool, s.vm.uiState.value.activeToolId)
        assertTrue(s.vm.uiState.value.isRunningTool)
        assertTrue(s.vm.uiState.value.streamingThinking.isNotBlank())
    }

    @Test
    fun `different tools run in parallel and keep their own artifacts`() = runTest {
        val s = subject()
        s.vm.openSheet(workId, ChapterOrdinal(1))
        val blueprint = s.vm.uiState.value.activeToolId

        s.vm.selectTool(ToolId("explain"))

        assertEquals(2, s.engine.runToolRequests.size)
        assertTrue(s.vm.uiState.value.isRunningTool)

        s.engine.release(blueprint)
        assertTrue(s.vm.uiState.value.isRunningTool)
        assertNull(s.vm.uiState.value.activeArtifact)
        assertEquals(2, s.engine.runToolRequests.size)

        s.engine.release(ToolId("explain"))
        assertFalse(s.vm.uiState.value.isRunningTool)
        assertEquals("工具 explain", s.vm.uiState.value.activeArtifact?.caption)

        s.vm.selectTool(blueprint)
        assertEquals(2, s.engine.runToolRequests.size)
        assertEquals("工具 ${blueprint.raw}", s.vm.uiState.value.activeArtifact?.caption)
    }

    @Test
    fun `diagram chip reuses the running profile blueprint task`() = runTest {
        val s = subject()
        s.vm.openSheet(workId, ChapterOrdinal(1))
        val blueprint = s.vm.uiState.value.activeToolId

        s.vm.selectTool(ToolId("diagram"))

        assertEquals(1, s.engine.runToolRequests.size)
        assertEquals(ToolId("diagram"), s.vm.uiState.value.activeToolId)

        s.engine.release(blueprint)
        assertFalse(s.vm.uiState.value.isRunningTool)
        assertNotNull(s.vm.uiState.value.activeArtifact)
    }

    @Test
    fun `in-flight chapter result never appears on another chapter`() = runTest {
        val s = subject()
        s.vm.openSheet(workId, ChapterOrdinal(1))
        val tool = s.vm.uiState.value.activeToolId

        s.vm.onReaderChapterChanged(ChapterOrdinal(2))
        s.engine.release(tool)

        assertEquals(ChapterOrdinal(2), s.vm.uiState.value.currentChapterOrdinal)
        assertFalse(s.vm.uiState.value.isRunningTool)
        assertNull(s.vm.uiState.value.activeArtifact)

        s.vm.onReaderChapterChanged(ChapterOrdinal(1))
        assertEquals("工具 ${tool.raw}", s.vm.uiState.value.activeArtifact?.caption)
        assertEquals(1, s.engine.runToolRequests.size)
    }

    @Test
    fun `chapter change drops a stale selection scope`() = runTest {
        val s = subject()
        val anchor = ReadingAnchor.BlockOffset(ChapterOrdinal(1), BlockId("B01"), 0, 1, "去")
        s.vm.openSheet(workId, ChapterOrdinal(1), selectionAnchor = anchor, selectedText = "去")
        assertEquals(CompanionScopeChoice.SELECTION, s.vm.uiState.value.scopeChoice)

        s.vm.onReaderChapterChanged(ChapterOrdinal(2))

        assertEquals(CompanionScopeChoice.CHAPTER, s.vm.uiState.value.scopeChoice)
        assertNull(s.vm.uiState.value.selectionAnchor)
        assertEquals("", s.vm.uiState.value.selectedText)
    }

    @Test
    fun `failure keeps the task retryable with a forced refresh`() = runTest {
        val s = subject()
        s.engine.failTools += "critique"
        s.vm.openSheet(workId, ChapterOrdinal(1))

        s.vm.selectTool(ToolId("critique"))
        s.engine.release(ToolId("critique"))

        assertTrue(s.vm.uiState.value.toolFailure is AppFailure.DesktopConnectionRequired)
        assertFalse(s.vm.uiState.value.isRunningTool)
        val attempts = s.engine.runToolRequests.size

        s.vm.selectTool(ToolId("critique"), forceRefresh = true)

        assertEquals(attempts + 1, s.engine.runToolRequests.size)
        assertTrue(s.engine.runToolRequests.last().forceRefresh)
    }

    @Test
    fun `concurrent follow-up questions both finish streaming`() = runTest {
        val s = subject()
        s.vm.openSheet(workId, ChapterOrdinal(1))

        s.vm.askFollowUp("Q1")
        s.vm.askFollowUp("Q2")

        assertEquals(listOf("Q1", "Q2"), s.engine.askQuestions)
        assertEquals(2, s.vm.uiState.value.qaMessages.size)
        assertTrue(s.vm.uiState.value.qaMessages.all { it.isStreaming })

        s.engine.qaGates[0].complete(Unit)
        assertFalse(s.vm.uiState.value.qaMessages[0].isStreaming)
        assertEquals("答案：Q1", s.vm.uiState.value.qaMessages[0].answerMarkdown)
        assertTrue(s.vm.uiState.value.qaMessages[1].isStreaming)

        s.engine.qaGates[1].complete(Unit)
        assertFalse(s.vm.uiState.value.qaMessages[1].isStreaming)
        assertEquals("答案：Q2", s.vm.uiState.value.qaMessages[1].answerMarkdown)
    }

    @Test
    fun `viz height follows compact renders and ignores fullscreen renders`() = runTest {
        val s = subject()
        s.vm.openSheet(workId, ChapterOrdinal(1))

        s.viz.hostCallback?.invoke(VizHostEvent.Rendered(800, 520))
        assertEquals(520, s.vm.uiState.value.vizContentHeightDp)

        s.vm.setFullscreenVizOpen(true)
        s.viz.hostCallback?.invoke(VizHostEvent.Rendered(1080, 4_000))
        assertEquals(520, s.vm.uiState.value.vizContentHeightDp)

        s.vm.setFullscreenVizOpen(false)
        s.viz.hostCallback?.invoke(VizHostEvent.Rendered(800, 640))
        assertEquals(640, s.vm.uiState.value.vizContentHeightDp)
    }

    @Test
    fun `viz height resets when the displayed artifact changes or rendering fails`() = runTest {
        val s = subject()
        s.vm.openSheet(workId, ChapterOrdinal(1), initialToolId = ToolId("explain"))
        s.viz.hostCallback?.invoke(VizHostEvent.Rendered(800, 620))
        assertEquals(620, s.vm.uiState.value.vizContentHeightDp)

        s.engine.release(ToolId("explain"))
        advanceUntilIdle()
        assertEquals(0, s.vm.uiState.value.vizContentHeightDp)

        s.viz.hostCallback?.invoke(VizHostEvent.Rendered(800, 620))
        s.vm.selectTool(ToolId("translate"))
        assertEquals(0, s.vm.uiState.value.vizContentHeightDp)

        s.viz.hostCallback?.invoke(VizHostEvent.Rendered(800, 620))
        s.vm.onReaderChapterChanged(ChapterOrdinal(2))
        assertEquals(0, s.vm.uiState.value.vizContentHeightDp)

        s.viz.hostCallback?.invoke(VizHostEvent.Rendered(800, 620))
        s.viz.hostCallback?.invoke(VizHostEvent.Error("budget"))
        assertEquals(0, s.vm.uiState.value.vizContentHeightDp)
        assertNotNull(s.vm.uiState.value.toastMessage)
    }

    @Test
    fun `a thrown generation error fails the slot and stays retryable`() = runTest {
        val s = subject()
        s.engine.throwTools += "explain"
        s.vm.openSheet(workId, ChapterOrdinal(1), initialToolId = ToolId("explain"))

        assertEquals(1, s.engine.runToolRequests.size)
        assertFalse(s.vm.uiState.value.isRunningTool)
        assertNull(s.vm.uiState.value.activeArtifact)
        assertNotNull(s.vm.uiState.value.toolFailure)

        s.engine.throwTools.clear()
        s.vm.selectTool(ToolId("explain"), forceRefresh = true)
        assertEquals(2, s.engine.runToolRequests.size)
        s.engine.release(ToolId("explain"))
        advanceUntilIdle()
        assertFalse(s.vm.uiState.value.isRunningTool)
        assertNull(s.vm.uiState.value.toolFailure)
        assertEquals(
            "工具 explain",
            (s.vm.uiState.value.activeArtifact as AuditedArtifact.MarkdownReadCard).caption,
        )
    }

    @Test
    fun `a stream without a terminal event does not leave the slot running`() = runTest {
        val s = subject()
        s.engine.silentTools += "translate"
        s.vm.openSheet(workId, ChapterOrdinal(1), initialToolId = ToolId("translate"))

        assertFalse(s.vm.uiState.value.isRunningTool)
        assertNotNull(s.vm.uiState.value.toolFailure)
        assertNull(s.vm.uiState.value.activeArtifact)

        s.engine.silentTools.clear()
        s.vm.selectTool(ToolId("translate"), forceRefresh = true)
        assertTrue(s.vm.uiState.value.isRunningTool)
        s.engine.release(ToolId("translate"))
        advanceUntilIdle()
        assertFalse(s.vm.uiState.value.isRunningTool)
        assertEquals(
            "工具 translate",
            (s.vm.uiState.value.activeArtifact as AuditedArtifact.MarkdownReadCard).caption,
        )
    }

    @Test
    fun `a thrown follow-up error ends streaming with a message`() = runTest {
        val s = subject()
        s.vm.openSheet(workId, ChapterOrdinal(1))
        s.engine.throwOnAsk = IllegalStateException("qa boom")
        s.vm.askFollowUp("为什么")

        val message = s.vm.uiState.value.qaMessages.single()
        assertFalse(message.isStreaming)
        assertNotNull(message.errorText)
    }

    @Test
    fun `registry marks a still running slot as failed when the stream ends`() {
        val registry = CompanionTaskRegistry()
        val key = CompanionTaskKey.of(workId, ToolScope.Chapter(ChapterOrdinal(1)), ToolId("explain"))
        val interrupted = AppFailure.NetworkOrSyncConflict("interrupted")

        registry.begin(key, forceRefresh = false)
        assertTrue(registry.finishOrMarkFailed(key, interrupted))
        assertFalse(registry.isRunning(key))
        assertTrue(registry.slot(key) is CompanionToolSlot.Failed)
        assertFalse(registry.finishOrMarkFailed(key, interrupted))
    }

    @Test
    fun `registry dedupes running keys and reuses ready results`() {
        val registry = CompanionTaskRegistry()
        val key = CompanionTaskKey.of(workId, ToolScope.Chapter(ChapterOrdinal(1)), ToolId("explain"))

        assertEquals(CompanionTaskRegistry.Start.Launch, registry.begin(key, forceRefresh = false))
        assertEquals(CompanionTaskRegistry.Start.AlreadyRunning, registry.begin(key, forceRefresh = false))
        assertEquals(CompanionTaskRegistry.Start.AlreadyRunning, registry.begin(key, forceRefresh = true))
        assertEquals(1, registry.pendingCount())

        registry.ready(key, MarkdownReadCard("c", "m"))
        assertFalse(registry.finishOrMarkFailed(key, AppFailure.NetworkOrSyncConflict("interrupted")))

        assertFalse(registry.isRunning(key))
        assertTrue(registry.begin(key, forceRefresh = false) is CompanionTaskRegistry.Start.Reuse)
        assertTrue(registry.begin(key, forceRefresh = true) is CompanionTaskRegistry.Start.Launch)
    }

    @Test
    fun `registry keeps different keys independent`() {
        val registry = CompanionTaskRegistry()
        val first = CompanionTaskKey.of(workId, ToolScope.Chapter(ChapterOrdinal(1)), ToolId("explain"))
        val second = CompanionTaskKey.of(workId, ToolScope.Chapter(ChapterOrdinal(1)), ToolId("translate"))

        assertEquals(CompanionTaskRegistry.Start.Launch, registry.begin(first, forceRefresh = false))
        assertEquals(CompanionTaskRegistry.Start.Launch, registry.begin(second, forceRefresh = false))
        assertEquals(2, registry.pendingCount())

        registry.ready(first, MarkdownReadCard("c", "m"))
        registry.finishOrMarkFailed(first, AppFailure.NetworkOrSyncConflict("interrupted"))
        assertTrue(registry.isRunning(second))
        assertFalse(registry.isRunning(first))
    }

    @Test
    fun `registry reuses failures until a retry is requested`() {
        val registry = CompanionTaskRegistry()
        val key = CompanionTaskKey.of(workId, ToolScope.Chapter(ChapterOrdinal(1)), ToolId("explain"))
        registry.begin(key, forceRefresh = false)
        registry.failed(key, AppFailure.NetworkOrSyncConflict("offline"))
        registry.finishOrMarkFailed(key, AppFailure.NetworkOrSyncConflict("interrupted"))

        assertTrue(registry.begin(key, forceRefresh = false) is CompanionTaskRegistry.Start.Reuse)
        assertEquals(CompanionTaskRegistry.Start.Launch, registry.begin(key, forceRefresh = true))
    }

    @Test
    fun `scope identity separates chapters and selections`() {
        val chapterOne = CompanionTaskKey.of(workId, ToolScope.Chapter(ChapterOrdinal(1)), ToolId("explain"))
        val chapterTwo = CompanionTaskKey.of(workId, ToolScope.Chapter(ChapterOrdinal(2)), ToolId("explain"))
        val anchorA = ReadingAnchor.BlockOffset(ChapterOrdinal(1), BlockId("B01"), 0, 1, "去")
        val anchorB = ReadingAnchor.BlockOffset(ChapterOrdinal(1), BlockId("B02"), 0, 1, "去")
        val selectionA = CompanionTaskKey.of(
            workId,
            ToolScope.Selection(anchorA, "去"),
            ToolId("explain"),
        )
        val selectionB = CompanionTaskKey.of(
            workId,
            ToolScope.Selection(anchorB, "去"),
            ToolId("explain"),
        )
        val fullBook = CompanionTaskKey.of(workId, ToolScope.FullBook, ToolId("explain"))

        assertEquals(5, setOf(chapterOne, chapterTwo, selectionA, selectionB, fullBook).size)
        assertTrue(selectionA.scopeKey != selectionB.scopeKey)
    }

    private fun MarkdownReadCard(caption: String, markdown: String) =
        AuditedArtifact.MarkdownReadCard(caption = caption, markdown = markdown)
}

internal class GatedCompanionEngine(
    private val profileRegistry: ProfileRegistry,
) : CompanionEngine {

    val runToolRequests = mutableListOf<CompanionRequest>()
    val askQuestions = mutableListOf<String>()
    val failTools = mutableSetOf<String>()
    val throwTools = mutableSetOf<String>()
    val silentTools = mutableSetOf<String>()
    var throwOnAsk: Throwable? = null
    val qaGates = mutableListOf<CompletableDeferred<Unit>>()
    private val toolGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    var llmTokenCalls = 0

    fun gate(tool: ToolId): CompletableDeferred<Unit> =
        toolGates.getOrPut(tool.raw) { CompletableDeferred() }

    fun release(tool: ToolId) {
        gate(tool).complete(Unit)
    }

    override suspend fun recommendedTools(workId: WorkId): ProfileToolbox {
        val profile = profileRegistry.profileById(ReadProfileId("history"))
        return ProfileToolbox(
            profileId = profile.readType,
            literatureSubKind = null,
            blueprints = profile.blueprints.map { it.toolId },
            extensions = profile.extensions.map { it.toolId },
        )
    }

    override fun runTool(request: CompanionRequest): Flow<CompanionEvent> = flow {
        runToolRequests += request
        llmTokenCalls++
        emit(CompanionEvent.StreamingDelta("思考 ${request.tool.raw}", isThinking = true))
        if (request.tool.raw in throwTools) throw IllegalStateException("runTool boom")
        if (request.tool.raw in silentTools) return@flow
        gate(request.tool).await()
        if (request.tool.raw in failTools) {
            emit(CompanionEvent.Failure(AppFailure.DesktopConnectionRequired("offline")))
            return@flow
        }
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
        throwOnAsk?.let { throw it }
        askQuestions += question
        llmTokenCalls++
        val gate = CompletableDeferred<Unit>()
        qaGates += gate
        emit(CompanionEvent.StreamingDelta("推理中", isThinking = true))
        gate.await()
        emit(CompanionEvent.StreamingDelta("回答", isThinking = false))
        emit(
            CompanionEvent.Done(
                AuditedArtifact.MarkdownReadCard(
                    caption = "追问",
                    markdown = "答案：$question",
                ),
            ),
        )
    }

    override fun askScannedRegion(
        workId: WorkId,
        pageIndex: Int,
        pngBytes: ByteArray,
        question: String,
    ): Flow<CompanionEvent> = flow {
        emit(CompanionEvent.Done(AuditedArtifact.MarkdownReadCard(caption = "扫描", markdown = question)))
    }

    override suspend fun estimateTokenCost(request: CompanionRequest): TokenEstimate =
        TokenEstimate(100L, 100L, true)

    override suspend fun importDesktopPrecomputedCache(workId: WorkId, bundleCacheDir: String) = Unit

    override suspend fun exportShareCompanionData(
        workId: WorkId,
        includeMaps: Boolean,
        includeQa: Boolean,
    ): SharedCompanionPayload = SharedCompanionPayload()
}
