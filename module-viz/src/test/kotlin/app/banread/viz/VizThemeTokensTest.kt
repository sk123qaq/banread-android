package app.banread.viz

import app.banread.model.ReaderTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VizThemeTokensTest {

    @Test
    fun `all ReaderTheme entries map cleanly with expected colorScheme and palette tokens`() {
        for (theme in ReaderTheme.entries) {
            val spec = VizThemeTokens.resolve(theme)
            assertTrue(spec.themeKey.isNotBlank())
            assertTrue(spec.colorScheme == "light" || spec.colorScheme == "dark")
            assertTrue(spec.cssVariables.containsKey("--paper"))
            assertTrue(spec.cssVariables.containsKey("--surface"))
            assertTrue(spec.cssVariables.containsKey("--ink"))
            assertTrue(spec.cssVariables.containsKey("--cinnabar"))
            assertTrue(spec.cssVariables.containsKey("--pine"))
        }

        val paper = VizThemeTokens.resolve(ReaderTheme.PAPER_LIGHT)
        assertEquals("#F7F4ED", paper.cssVariables["--paper"])
        assertEquals("#FDFBF7", paper.cssVariables["--surface"])
        assertEquals("#A8442F", paper.cssVariables["--cinnabar"])
        assertEquals("#2F6A52", paper.cssVariables["--pine"])
        assertEquals("light", paper.colorScheme)
        assertFalse(paper.isDark)
        assertFalse(paper.isEink)

        val sepia = VizThemeTokens.resolve(ReaderTheme.SEPIA_WARM)
        assertEquals("light", sepia.colorScheme)
        assertEquals("#F4ECD8", sepia.cssVariables["--paper-reading"])

        val slate = VizThemeTokens.resolve(ReaderTheme.SLATE_NIGHT)
        assertEquals("dark", slate.colorScheme)
        assertTrue(slate.isDark)

        val amoled = VizThemeTokens.resolve(ReaderTheme.AMOLED_BLACK)
        assertEquals("#000000", amoled.cssVariables["--paper"])
        assertEquals("dark", amoled.colorScheme)
        assertTrue(amoled.isDark)
    }

    @Test
    fun `EINK_HIGH_CONTRAST enforces 0ms transitions, pure black-white contrast, and zero drop shadows`() {
        val eink = VizThemeTokens.resolve(ReaderTheme.EINK_HIGH_CONTRAST)
        assertTrue(eink.isEink)
        assertFalse(eink.isDark)
        assertEquals("light", eink.colorScheme)
        assertEquals(0, eink.transitionDurationMs)
        assertEquals("#FFFFFF", eink.cssVariables["--paper"])
        assertEquals("#FFFFFF", eink.cssVariables["--surface"])
        assertEquals("#000000", eink.cssVariables["--ink"])
        assertEquals("#000000", eink.cssVariables["--line"])
        assertEquals("none", eink.cssVariables["--shadow-surface"])
        assertEquals("none", eink.cssVariables["--shadow-control"])
        assertEquals("none", eink.cssVariables["--shadow-floating"])

        val obj = Json.parseToJsonElement(VizThemeTokens.toJson(ReaderTheme.EINK_HIGH_CONTRAST)).jsonObject
        assertEquals("eink", obj["themeKey"]?.jsonPrimitive?.content)
        assertEquals(true, obj["isEink"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(0, obj["transitionDurationMs"]?.jsonPrimitive?.intOrNull)
    }
}
