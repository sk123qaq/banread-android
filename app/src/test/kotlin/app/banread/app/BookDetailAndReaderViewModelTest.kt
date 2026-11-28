package app.banread.app

import app.banread.app.data.InMemoryAppPreferencesStore
import app.banread.app.ui.detail.BookDetailViewModel
import app.banread.app.ui.reader.ReflowReaderViewModel
import app.banread.model.BlockId
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterOutlineItem
import app.banread.model.ChapterRole
import app.banread.model.FootnoteDetail
import app.banread.model.FootnoteLink
import app.banread.model.ReadProfileId
import app.banread.model.ReaderTheme
import app.banread.model.ReadingAnchor
import app.banread.model.ReadingSurfaceMode
import app.banread.model.TextQuote
import app.banread.model.WorkId
import app.banread.reader.FootnoteClickEvent
import app.banread.reader.SelectionRect
import app.banread.reader.TextSelectionEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailAndReaderViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun `BookDetailViewModel handles perspective override, collapsed frontBack, and direct TOC jump without anti-spoiler dialog`() = runTest {
        val workId = WorkId("w_detail")
        val vault = FakeLibraryVault().apply {
            worksFlow.value = listOf(sampleWorkSummary("w_detail", "测试小说"))
        }
        val reader = FakeReaderEngine()
        reader.initialOrdinalByWork[workId.raw] = ChapterOrdinal(3)
        val registry = loadTestProfileRegistry()

        val vm = BookDetailViewModel(workId, vault, reader, registry)

        assertFalse(vm.uiState.value.frontBackExpanded)
        vm.toggleFrontBackExpanded()
        assertTrue(vm.uiState.value.frontBackExpanded)

        assertEquals("03", ChapterOrdinal(3).twoDigit())
        assertEquals("10", ChapterOrdinal(10).twoDigit())

        vm.selectPerspective(ReadProfileId("literature"), "drama")
        assertEquals(ReadProfileId("literature"), vm.uiState.value.activeProfile?.readType)
        assertEquals("drama", vm.uiState.value.outline?.literatureSubKind)

        val ch10 = ChapterOutlineItem(ChapterOrdinal(10), "第 10 章", ChapterRole.BODY, 15)
        vm.onChapterClicked(ch10)
        assertEquals(ChapterOrdinal(10), reader.activeState.value.currentCursor?.ordinal)
        assertEquals(ChapterOrdinal(10), reader.goToCalls.last().first.ordinal)
        assertEquals(ReadingSurfaceMode.REFLOWABLE, vm.uiState.value.pendingNavigationMode)
        assertEquals(ChapterOrdinal(10), vm.uiState.value.currentOrdinal)
    }

    @Test
    fun `ReflowReaderViewModel supports zero-reload theme-typo updates, selection highlight, and footnote roundtrip`() = runTest {
        val workId = WorkId("w_reader")
        val vault = FakeLibraryVault().apply {
            worksFlow.value = listOf(sampleWorkSummary("w_reader", "钟楼秘事"))
            footnotes["#fn-45"] = FootnoteDetail(
                link = FootnoteLink(
                    id = "fn-45",
                    workId = workId,
                    sourceChapterOrdinal = ChapterOrdinal(2),
                    sourceBlockId = BlockId("B02"),
                    marker = "45",
                    targetChapterOrdinal = ChapterOrdinal(11),
                    targetBlockId = BlockId("B03"),
                    previewText = "修缮志引文",
                ),
                targetChapterTitle = "全书注释",
                fullNoteText = "民国十三年《沪西修缮志》载，钟楼发条曾更换瑞士机芯。",
            )
        }
        val reader = FakeReaderEngine()
        val surface = FakeReflowSurfaceController()
        val prefs = InMemoryAppPreferencesStore()

        val vm = ReflowReaderViewModel(workId, vault, reader, surface, null, prefs)

        vm.applyTheme(ReaderTheme.EINK_HIGH_CONTRAST)
        assertEquals(ReaderTheme.EINK_HIGH_CONTRAST, reader.appliedThemes.last())
        vm.updateTypography(vm.uiState.value.typography.copy(fontSizeSp = 22f, squeezeChinesePunctuation = true))
        assertEquals(22f, reader.appliedTypographies.last().fontSizeSp, 0.01f)

        val selAnchor = ReadingAnchor.ReflowCfi(
            ordinal = ChapterOrdinal(2),
            href = "OEBPS/ch_02.xhtml",
            progression = 0.4f,
            totalProgression = 0.2f,
            cfi = "epubcfi(/6/4!/4/4)",
            textQuote = TextQuote("", "铜钥匙背面的缺口", ""),
            blockId = BlockId("B02"),
        )
        surface.emitSelection(TextSelectionEvent(selAnchor, "铜钥匙背面的缺口", SelectionRect(0f, 0f, 100f, 20f)))
        assertNotNull(vm.uiState.value.activeSelection)
        vm.highlightSelection("YELLOW")
        assertNull(vm.uiState.value.activeSelection)
        assertEquals("YELLOW", reader.savedAnnotations.last().third.first)

        surface.emitFootnote(FootnoteClickEvent(ChapterOrdinal(2), "#fn-45"))
        assertNotNull(vm.uiState.value.activeFootnote)
        vm.jumpToFullFootnoteNote()
        assertNull(vm.uiState.value.activeFootnote)
        assertNotNull(vm.uiState.value.preFootnoteReturnAnchor)
        assertEquals(ChapterOrdinal(11), reader.goToCalls.last().first.ordinal)

        vm.returnToPreFootnoteLocation()
        assertNull(vm.uiState.value.preFootnoteReturnAnchor)
        assertEquals(ChapterOrdinal(2), reader.goToCalls.last().first.ordinal)
    }

    @Test
    fun `ReflowReaderViewModel skips redundant open when already open on REFLOWABLE and preserves currentCursor`() = runTest {
        val workId = WorkId("w_preopen")
        val vault = FakeLibraryVault().apply {
            worksFlow.value = listOf(sampleWorkSummary("w_preopen", "预打开小说"))
        }
        val reader = FakeReaderEngine()
        reader.initialOrdinalByWork[workId.raw] = ChapterOrdinal(3)
        reader.open(workId, ReadingSurfaceMode.REFLOWABLE)
        val jumpedAnchor = vault.resolveAnchor(workId, ChapterOrdinal(10), BlockId("B01"))
        reader.goTo(jumpedAnchor)
        assertEquals(1, reader.openCalls)

        val surface = FakeReflowSurfaceController()
        val prefs = InMemoryAppPreferencesStore()
        val vm = ReflowReaderViewModel(workId, vault, reader, surface, null, prefs)

        assertEquals(1, reader.openCalls)
        assertEquals(ChapterOrdinal(10), vm.uiState.value.sessionState.currentCursor?.ordinal)

        vm.reopenSurface()
        assertEquals(2, reader.openCalls)

        prefs.setTheme(ReaderTheme.SLATE_NIGHT)
        vm.syncPreferencesAndOutline()
        assertEquals(2, reader.openCalls)
        assertEquals(ReaderTheme.SLATE_NIGHT, reader.appliedThemes.last())
    }

    @Test
    fun `BookDetailViewModel chapter click opens and navigates directly to target chapter ordinal`() = runTest {
        val workId = WorkId("w_toc_direct")
        val vault = FakeLibraryVault().apply {
            worksFlow.value = listOf(sampleWorkSummary(workId.raw, "目录跳转测试"))
        }
        val reader = FakeReaderEngine()
        reader.initialOrdinalByWork[workId.raw] = ChapterOrdinal(10)
        val registry = loadTestProfileRegistry()

        val vm = BookDetailViewModel(workId, vault, reader, registry)
        val ch3 = ChapterOutlineItem(ChapterOrdinal(3), "第 3 章", ChapterRole.BODY, 12)
        vm.onChapterClicked(ch3)

        assertEquals(ReadingSurfaceMode.REFLOWABLE, vm.uiState.value.pendingNavigationMode)
        assertEquals(ChapterOrdinal(3), vm.uiState.value.currentOrdinal)
        assertEquals(1, reader.openCalls)
        assertEquals(1, reader.goToCalls.size)
        val targetAnchor = reader.goToCalls.single().first as ReadingAnchor.ReflowCfi
        assertEquals(ChapterOrdinal(3), targetAnchor.ordinal)
        assertEquals("OEBPS/ch_03.xhtml", targetAnchor.href)
    }
}
