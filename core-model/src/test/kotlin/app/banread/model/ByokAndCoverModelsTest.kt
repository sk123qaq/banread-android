package app.banread.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ByokAndCoverModelsTest {

    @Test
    fun `preset catalog exposes 9 grouped presets matching aggregator and official_or_local`() {
        assertEquals(9, ProviderPresetCatalog.presets.size)
        val aggregators = ProviderPresetCatalog.byGroup(ProviderPresetGroup.AGGREGATOR)
        val officials = ProviderPresetCatalog.byGroup(ProviderPresetGroup.OFFICIAL_OR_LOCAL)

        assertEquals(listOf("opencode", "openrouter", "siliconflow", "custom"), aggregators.map { it.id })
        assertEquals(listOf("deepseek", "openai", "gemini", "anthropic", "ollama"), officials.map { it.id })

        val ollama = ProviderPresetCatalog.findById("ollama")
        assertNotNull(ollama)
        assertFalse(ollama!!.requiresApiKey)
        assertEquals("http://127.0.0.1:11434", ollama.defaultEndpoint)
    }

    @Test
    fun `resolveModelsEndpoint normalizes trailing slashes and avoids double v1`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            resolveModelsEndpoint(ProviderKind.OPENAI_COMPAT, "https://api.openai.com/v1/"),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            resolveModelsEndpoint(ProviderKind.OPENAI_COMPAT, "https://api.openai.com"),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            resolveModelsEndpoint(ProviderKind.OPENAI_COMPAT, "https://openrouter.ai/api/v1"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            resolveModelsEndpoint(ProviderKind.ANTHROPIC, "https://api.anthropic.com"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            resolveModelsEndpoint(ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1/"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            resolveModelsEndpoint(ProviderKind.GEMINI, "https://generativelanguage.googleapis.com/v1beta/"),
        )
        assertEquals(
            "http://127.0.0.1:11434/api/tags",
            resolveModelsEndpoint(ProviderKind.OLLAMA, "http://127.0.0.1:11434/"),
        )
    }

    @Test
    fun `maskApiKey masks secrets cleanly without leaking plaintext`() {
        assertEquals("本地免 Key", maskApiKey("", requiresApiKey = false))
        assertEquals("本地免 Key", maskApiKey("   ", requiresApiKey = true))
        assertEquals("本地免 Key", maskApiKey("anything", requiresApiKey = false))
        assertEquals("••••", maskApiKey("sk-123", requiresApiKey = true))
        assertEquals("sk-a••••efgh", maskApiKey("sk-ant-12345678abcdefgh", requiresApiKey = true))
        assertEquals("1234••••5678", maskApiKey("12345678", requiresApiKey = true))
    }

    @Test
    fun `classifyCoverDisplayMode routes portrait, wide, and missing covers accurately`() {
        assertEquals(CoverDisplayMode.CLOTH_FALLBACK, classifyCoverDisplayMode(null, 240, 360))
        assertEquals(CoverDisplayMode.CLOTH_FALLBACK, classifyCoverDisplayMode("cover.png", null, 360))
        assertEquals(CoverDisplayMode.CLOTH_FALLBACK, classifyCoverDisplayMode("cover.png", 0, 360))
        assertEquals(CoverDisplayMode.PORTRAIT_CROP, classifyCoverDisplayMode("cover.png", 240, 360))
        assertEquals(CoverDisplayMode.PORTRAIT_CROP, classifyCoverDisplayMode("cover.png", 45, 100))
        assertEquals(CoverDisplayMode.PORTRAIT_CROP, classifyCoverDisplayMode("cover.png", 85, 100))
        assertEquals(CoverDisplayMode.WIDE_PAPER_MAT, classifyCoverDisplayMode("cover.png", 400, 400))
        assertEquals(CoverDisplayMode.WIDE_PAPER_MAT, classifyCoverDisplayMode("cover.png", 800, 450))
        assertEquals(CoverDisplayMode.WIDE_PAPER_MAT, classifyCoverDisplayMode("cover.png", 100, 400))
        assertTrue(ThinkingLevel.entries.size == 5)
    }
}
