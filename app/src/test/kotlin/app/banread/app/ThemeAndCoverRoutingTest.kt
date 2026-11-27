package app.banread.app

import androidx.compose.ui.graphics.Color
import app.banread.app.data.OfflineDesktopSyncCoordinator
import app.banread.app.ui.theme.BanReadPalettes
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.CoverDisplayMode
import app.banread.model.ReaderTheme
import app.banread.model.WorkId
import app.banread.model.classifyCoverDisplayMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeAndCoverRoutingTest {

    @Test
    fun `all 5 ReaderTheme palettes match design invariants and EINK enforces 0ms and zero elevation`() {
        assertEquals(5, ReaderTheme.entries.size)

        val eink = BanReadPalettes.forTheme(ReaderTheme.EINK_HIGH_CONTRAST)
        assertTrue(eink.isEink)
        assertEquals(0, eink.animationDurationMs)
        assertEquals(0f, eink.cardElevation.value, 0.001f)
        assertEquals(1f, eink.borderWidth.value, 0.001f)

        val paper = BanReadPalettes.forTheme(ReaderTheme.PAPER_LIGHT)
        assertEquals(Color(0xFFF7F4ED), paper.paper)
        assertEquals(Color(0xFFFDFBF7), paper.surface)
        assertEquals(Color(0xFF1E1E1E), paper.ink)
        assertEquals(Color(0xFFA8442F), paper.cinnabar)
        assertEquals(Color(0xFF2F6A52), paper.pine)
        assertFalse(paper.isDark)
        assertFalse(paper.isEink)

        val sepia = BanReadPalettes.forTheme(ReaderTheme.SEPIA_WARM)
        assertEquals(Color(0xFFF4ECD8), sepia.paperReading)
        assertEquals(Color(0xFFFCF9F2), sepia.surface)

        val slate = BanReadPalettes.forTheme(ReaderTheme.SLATE_NIGHT)
        assertEquals(Color(0xFF131720), slate.paper)
        assertEquals(Color(0xFF232A37), slate.surface)
        assertEquals(Color(0xFFD8DEE9), slate.ink)
        assertEquals(Color(0xFFDF745D), slate.cinnabar)
        assertTrue(slate.isDark)
        assertFalse(slate.isEink)

        val amoled = BanReadPalettes.forTheme(ReaderTheme.AMOLED_BLACK)
        assertEquals(Color(0xFF000000), amoled.paper)
        assertEquals(Color(0xFF0D0F12), amoled.surface)
        assertEquals(Color(0xFFE5E7EB), amoled.ink)
        assertTrue(amoled.isDark)
    }

    @Test
    fun `cover display mode routing maps portrait, wide, and missing covers accurately`() {
        assertEquals(
            CoverDisplayMode.PORTRAIT_CROP,
            classifyCoverDisplayMode("cover.jpg", 600, 900),
        )
        assertEquals(
            CoverDisplayMode.WIDE_PAPER_MAT,
            classifyCoverDisplayMode("wide.png", 1200, 700),
        )
        assertEquals(
            CoverDisplayMode.CLOTH_FALLBACK,
            classifyCoverDisplayMode(null, null, null),
        )
    }

    @Test
    fun `OfflineDesktopSyncCoordinator returns DesktopConnectionRequired for cross-book search`() = runTest {
        val coord = OfflineDesktopSyncCoordinator()
        val result = coord.queryCrossBookHits(WorkId("w1"), "query", null)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as? AppFailureException
        assertTrue(ex?.failure is AppFailure.DesktopConnectionRequired)
    }

    @Test
    fun `app sources never combine halt 1 and palt 1 in any CSS string`() {
        val appSrcDir = File("src/main/kotlin")
        if (!appSrcDir.isDirectory) return
        val violations = appSrcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("\"halt\" 1") && text.contains("\"palt\" 1")
            }
            .map { it.path }
            .toList()
        assertTrue("Forbidden combined halt+palt CSS found in: $violations", violations.isEmpty())
    }
}
