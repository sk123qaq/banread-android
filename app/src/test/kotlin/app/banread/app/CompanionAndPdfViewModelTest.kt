package app.banread.app

import app.banread.app.data.InMemoryAppPreferencesStore
import app.banread.app.reader.computeNormalizedCropRect
import app.banread.app.reader.mapNormalizedRectToPixelBounds
import app.banread.app.ui.companion.CompanionDetent
import app.banread.app.ui.companion.CompanionScopeChoice
import app.banread.app.ui.companion.CompanionViewModel
import app.banread.app.ui.pdf.PdfReaderViewModel
import app.banread.model.AppFailure
import app.banread.model.BlockId
import app.banread.model.ChapterOrdinal
import app.banread.model.CompanionEvent
import app.banread.model.NormalizedRect
import app.banread.model.ToolId
import app.banread.model.VizHostEvent
import app.banread.model.WorkId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionAndPdfViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun `CompanionViewModel enforces offline zero-token guard, FullBook confirmation, and data-jump roundtrip`() = runTest {
        val workId = WorkId("w_comp")
        val vault = FakeLibraryVault().apply {
            worksFlow.value = listOf(sampleWorkSummary("w_comp", "史记选读", profileId = "history"))
        }
        val reader = FakeReaderEngine()
        val registry = loadTestProfileRegistry()
        val vizHost = FakeVizHostController()
        val companion = FakeCompanionEngine(registry)

        val vm = CompanionViewModel(companion, vault, reader, registry, vizHost)
        var jumpedSignalCount = 0
        vm.openSheet(
            workId = workId,
            currentOrdinal = ChapterOrdinal(3),
            detent = CompanionDetent.HALF,
            onJumpedToReader = { jumpedSignalCount++ },
        )

        // Tool strip contains chapter_map + profile blueprints/extensions + 5 universal actions
        val toolIds = vm.uiState.value.tools.map { it.toolId.raw }
        assertTrue("chapter_map" in toolIds)
        assertTrue("diagram" in toolIds)
        assertTrue("explain" in toolIds)
        assertTrue("translate" in toolIds)
        assertTrue("critique" in toolIds)
        assertTrue("ask" in toolIds)
        assertTrue("compare_context" in toolIds)

        // Switching to FullBook prompts confirmation dialog first
        vm.selectScope(CompanionScopeChoice.FULL_BOOK)
        assertTrue(vm.uiState.value.showSpoilerUnlockDialog)
        assertFalse(vm.uiState.value.fullBookUnlocked)
        vm.confirmFullBookUnlock()
        assertTrue(vm.uiState.value.fullBookUnlocked)
        assertEquals(CompanionScopeChoice.FULL_BOOK, vm.uiState.value.scopeChoice)

        // Offline compare_context consumes 0 LLM tokens and sets DesktopConnectionRequired
        val tokensBefore = companion.llmTokenCalls
        vm.selectTool(ToolId("compare_context"))
        assertEquals(tokensBefore, companion.llmTokenCalls)
        assertTrue(vm.uiState.value.toolFailure is AppFailure.DesktopConnectionRequired)

        // Fullscreen viz event toggles fullscreen mode (first opens, second exits)
        vizHost.hostCallback?.invoke(VizHostEvent.OpenFullscreen)
        assertTrue(vm.uiState.value.isFullscreenVizOpen)
        assertEquals(listOf(true), vizHost.fullscreenHistory)

        vizHost.hostCallback?.invoke(VizHostEvent.OpenFullscreen)
        assertFalse(vm.uiState.value.isFullscreenVizOpen)
        assertEquals(listOf(true, false), vizHost.fullscreenHistory)

        // Re-open fullscreen, then verify [data-jump] resolves ReadingAnchor, drops sheet to PEEK, pulses 2000ms, and closes fullscreen
        vizHost.hostCallback?.invoke(VizHostEvent.OpenFullscreen)
        assertTrue(vm.uiState.value.isFullscreenVizOpen)

        vizHost.hostCallback?.invoke(VizHostEvent.JumpToBlock(BlockId("B04")))
        assertEquals(CompanionDetent.PEEK, vm.uiState.value.detent)
        assertFalse(vm.uiState.value.isFullscreenVizOpen)
        assertEquals(2_000L, reader.goToCalls.last().second)
        assertEquals(1, jumpedSignalCount)
    }

    @Test
    fun `PdfReaderViewModel computes normalized crop bounds, blocks reflow on scanned PDF, and streams VLM answer`() = runTest {
        val workId = WorkId("w_pdf_scan")
        val vault = FakeLibraryVault().apply {
            worksFlow.value = listOf(
                sampleWorkSummary("w_pdf_scan", "几何原本影印", format = "PDF", isScannedPdf = true),
            )
        }
        val reader = FakeReaderEngine()
        val pdfSurface = FakePdfSurfaceController()
        val registry = loadTestProfileRegistry()
        val companion = FakeCompanionEngine(registry)
        val prefs = InMemoryAppPreferencesStore()

        val vm = PdfReaderViewModel(workId, vault, reader, pdfSurface, companion, prefs)

        // Scanned PDF blocks reflow switch cleanly
        vm.switchToTextReflowMode()
        assertFalse(vm.uiState.value.shouldSwitchToReflowScreen)
        assertNotNull(vm.uiState.value.statusBanner)

        // Crop math maps drag coordinates to normalized [0..1] and pixel bounds cleanly
        val norm = computeNormalizedCropRect(
            startX = 100f,
            startY = 200f,
            endX = 300f,
            endY = 500f,
            canvasWidth = 400f,
            canvasHeight = 800f,
        )
        assertNotNull(norm)
        assertEquals(NormalizedRect(0.25f, 0.25f, 0.75f, 0.625f), norm)

        // Tiny accidental tap returns null
        assertNull(computeNormalizedCropRect(10f, 10f, 12f, 12f, 400f, 800f))

        val pxBounds = mapNormalizedRectToPixelBounds(norm!!, bitmapWidth = 1000, bitmapHeight = 1600)
        assertNotNull(pxBounds)
        assertEquals(250, pxBounds!!.x)
        assertEquals(400, pxBounds.y)
        assertEquals(500, pxBounds.width)
        assertEquals(600, pxBounds.height)

        // Submit cropped PNG bytes to askScannedRegion
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        vm.onCropRegionPngReady(fakePng)
        assertTrue(vm.uiState.value.showVlmPromptDialog)

        vm.submitScannedRegionQuestion("解释勾股弦面积割补")
        assertTrue(vm.uiState.value.showVlmResultSheet)
        assertFalse(vm.uiState.value.isVlmStreaming)
        assertEquals("Thinking...", vm.uiState.value.vlmThinkingText)
        assertTrue(vm.uiState.value.vlmAnswerMarkdown.contains("解析结论"))

        // Missing vision provider surfaces actionable failure
        companion.scannedRegionHandler = { _, _, _, _ ->
            flow {
                emit(
                    CompanionEvent.Failure(
                        AppFailure.ProviderAuthOrQuota("none", null, "No active LLM provider configured for vision query"),
                    ),
                )
            }
        }
        vm.submitScannedRegionQuestion("再问一次", pngBytesOverride = fakePng)
        assertTrue(vm.uiState.value.vlmFailure is AppFailure.ProviderAuthOrQuota)
    }
}
