package app.banread.viz

import app.banread.model.ReaderTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class VizThemeSpec(
    val theme: ReaderTheme,
    val themeKey: String,
    val colorScheme: String,
    val isDark: Boolean,
    val isEink: Boolean,
    val transitionDurationMs: Int,
    val cssVariables: Map<String, String>,
)

/**
 * Pure Kotlin mapper from `:core-model` [ReaderTheme] to offline CSS variables,
 * `color-scheme`, and E-ink zero-motion / high-contrast flags.
 */
object VizThemeTokens {
    private val compactJson = Json { encodeDefaults = true }

    fun resolve(theme: ReaderTheme): VizThemeSpec = when (theme) {
        ReaderTheme.PAPER_LIGHT -> VizThemeSpec(
            theme = theme,
            themeKey = "paper",
            colorScheme = "light",
            isDark = false,
            isEink = false,
            transitionDurationMs = 180,
            cssVariables = linkedMapOf(
                "--paper" to "#F7F4ED",
                "--paper-reading" to "#FBF9F5",
                "--paper-deep" to "#EFEAE0",
                "--surface" to "#FDFBF7",
                "--surface-subtle" to "#F2EFE7",
                "--surface-hover" to "#F5F1E8",
                "--desk-card" to "#FDFBF7",
                "--ink" to "#1E1E1E",
                "--ink-2" to "#4A4D52",
                "--ink-3" to "#666A70",
                "--line" to "#DCD9D0",
                "--line-strong" to "#C2BEB2",
                "--rule" to "#DCD9D0",
                "--cinnabar" to "#A8442F",
                "--cinnabar-soft" to "rgba(168, 68, 47, 0.11)",
                "--pine" to "#2F6A52",
                "--pine-soft" to "rgba(47, 106, 82, 0.11)",
                "--amber" to "#87500E",
                "--amber-soft" to "rgba(135, 80, 14, 0.14)",
                "--ochre" to "#87500E",
                "--indigo" to "#2C4056",
                "--danger" to "#B42318",
                "--danger-soft" to "rgba(180, 35, 24, 0.11)",
                "--shadow-surface" to "0 0 0 1px rgba(0, 0, 0, 0.065), 0 1px 2px rgba(0, 0, 0, 0.05)",
                "--shadow-control" to "0 0 0 1px rgba(0, 0, 0, 0.10), 0 1px 2px rgba(0, 0, 0, 0.05)",
                "--shadow-floating" to "0 0 0 1px rgba(0, 0, 0, 0.09), 0 8px 20px rgba(0, 0, 0, 0.14)",
            ),
        )

        ReaderTheme.SEPIA_WARM -> VizThemeSpec(
            theme = theme,
            themeKey = "warm",
            colorScheme = "light",
            isDark = false,
            isEink = false,
            transitionDurationMs = 180,
            cssVariables = linkedMapOf(
                "--paper" to "#F4F1E8",
                "--paper-reading" to "#F4ECD8",
                "--paper-deep" to "#EAE4D6",
                "--surface" to "#FCF9F2",
                "--surface-subtle" to "#EFEAE0",
                "--surface-hover" to "#F5F0E4",
                "--desk-card" to "#FCF9F2",
                "--ink" to "#181817",
                "--ink-2" to "#4E4A42",
                "--ink-3" to "#686258",
                "--line" to "#D8D1C2",
                "--line-strong" to "#BEB6A4",
                "--rule" to "#D8D1C2",
                "--cinnabar" to "#A8442F",
                "--cinnabar-soft" to "rgba(168, 68, 47, 0.11)",
                "--pine" to "#2F6A52",
                "--pine-soft" to "rgba(47, 106, 82, 0.11)",
                "--amber" to "#87500E",
                "--amber-soft" to "rgba(135, 80, 14, 0.14)",
                "--ochre" to "#87500E",
                "--indigo" to "#2C4056",
                "--danger" to "#B42318",
                "--danger-soft" to "rgba(180, 35, 24, 0.11)",
                "--shadow-surface" to "0 0 0 1px rgba(0, 0, 0, 0.065), 0 1px 2px rgba(0, 0, 0, 0.05)",
                "--shadow-control" to "0 0 0 1px rgba(0, 0, 0, 0.10), 0 1px 2px rgba(0, 0, 0, 0.05)",
                "--shadow-floating" to "0 0 0 1px rgba(0, 0, 0, 0.09), 0 8px 20px rgba(0, 0, 0, 0.14)",
            ),
        )

        ReaderTheme.SLATE_NIGHT -> VizThemeSpec(
            theme = theme,
            themeKey = "slate",
            colorScheme = "dark",
            isDark = true,
            isEink = false,
            transitionDurationMs = 180,
            cssVariables = linkedMapOf(
                "--paper" to "#131720",
                "--paper-reading" to "#1A1F29",
                "--paper-deep" to "#10141C",
                "--surface" to "#232A37",
                "--surface-subtle" to "#2B3343",
                "--surface-hover" to "#323C4E",
                "--desk-card" to "#232A37",
                "--ink" to "#D8DEE9",
                "--ink-2" to "#B0B9C8",
                "--ink-3" to "#8590A3",
                "--line" to "#313A4B",
                "--line-strong" to "#465268",
                "--rule" to "#313A4B",
                "--cinnabar" to "#DF745D",
                "--cinnabar-soft" to "rgba(223, 116, 93, 0.18)",
                "--pine" to "#6CB8A6",
                "--pine-soft" to "rgba(108, 184, 166, 0.16)",
                "--amber" to "#E4A84E",
                "--amber-soft" to "rgba(228, 168, 78, 0.16)",
                "--ochre" to "#E4A84E",
                "--indigo" to "#7EA5CE",
                "--danger" to "#F07167",
                "--danger-soft" to "rgba(240, 113, 103, 0.18)",
                "--shadow-surface" to "0 0 0 1px rgba(255, 255, 255, 0.08)",
                "--shadow-control" to "0 0 0 1px rgba(255, 255, 255, 0.12)",
                "--shadow-floating" to "0 0 0 1px rgba(255, 255, 255, 0.14), 0 10px 24px rgba(0, 0, 0, 0.55)",
            ),
        )

        ReaderTheme.AMOLED_BLACK -> VizThemeSpec(
            theme = theme,
            themeKey = "amoled",
            colorScheme = "dark",
            isDark = true,
            isEink = false,
            transitionDurationMs = 180,
            cssVariables = linkedMapOf(
                "--paper" to "#000000",
                "--paper-reading" to "#000000",
                "--paper-deep" to "#07080A",
                "--surface" to "#0D0F12",
                "--surface-subtle" to "#15181E",
                "--surface-hover" to "#1C2028",
                "--desk-card" to "#0D0F12",
                "--ink" to "#E5E7EB",
                "--ink-2" to "#B3B9C4",
                "--ink-3" to "#838A96",
                "--line" to "#22262E",
                "--line-strong" to "#353B47",
                "--rule" to "#22262E",
                "--cinnabar" to "#DF745D",
                "--cinnabar-soft" to "rgba(223, 116, 93, 0.18)",
                "--pine" to "#6CB8A6",
                "--pine-soft" to "rgba(108, 184, 166, 0.16)",
                "--amber" to "#E4A84E",
                "--amber-soft" to "rgba(228, 168, 78, 0.16)",
                "--ochre" to "#E4A84E",
                "--indigo" to "#7EA5CE",
                "--danger" to "#F07167",
                "--danger-soft" to "rgba(240, 113, 103, 0.18)",
                "--shadow-surface" to "0 0 0 1px #22262E",
                "--shadow-control" to "0 0 0 1px #2B303A",
                "--shadow-floating" to "0 0 0 1px #353B47",
            ),
        )

        ReaderTheme.EINK_HIGH_CONTRAST -> VizThemeSpec(
            theme = theme,
            themeKey = "eink",
            colorScheme = "light",
            isDark = false,
            isEink = true,
            transitionDurationMs = 0,
            cssVariables = linkedMapOf(
                "--paper" to "#FFFFFF",
                "--paper-reading" to "#FFFFFF",
                "--paper-deep" to "#FFFFFF",
                "--surface" to "#FFFFFF",
                "--surface-subtle" to "#F2F2F2",
                "--surface-hover" to "#EAEAEA",
                "--desk-card" to "#FFFFFF",
                "--ink" to "#000000",
                "--ink-2" to "#000000",
                "--ink-3" to "#222222",
                "--line" to "#000000",
                "--line-strong" to "#000000",
                "--rule" to "#000000",
                "--cinnabar" to "#000000",
                "--cinnabar-soft" to "rgba(0, 0, 0, 0.08)",
                "--pine" to "#000000",
                "--pine-soft" to "rgba(0, 0, 0, 0.08)",
                "--amber" to "#111111",
                "--amber-soft" to "rgba(0, 0, 0, 0.08)",
                "--ochre" to "#111111",
                "--indigo" to "#000000",
                "--danger" to "#000000",
                "--danger-soft" to "rgba(0, 0, 0, 0.10)",
                "--shadow-surface" to "none",
                "--shadow-control" to "none",
                "--shadow-floating" to "none",
            ),
        )
    }

    fun toJsonObject(theme: ReaderTheme): JsonObject {
        val spec = resolve(theme)
        return buildJsonObject {
            put("theme", JsonPrimitive(spec.theme.name))
            put("themeKey", JsonPrimitive(spec.themeKey))
            put("colorScheme", JsonPrimitive(spec.colorScheme))
            put("isDark", JsonPrimitive(spec.isDark))
            put("isEink", JsonPrimitive(spec.isEink))
            put("transitionDurationMs", JsonPrimitive(spec.transitionDurationMs))
            put(
                "cssVariables",
                buildJsonObject {
                    spec.cssVariables.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                },
            )
        }
    }

    fun toJson(theme: ReaderTheme): String = compactJson.encodeToString(JsonObject.serializer(), toJsonObject(theme))
}
