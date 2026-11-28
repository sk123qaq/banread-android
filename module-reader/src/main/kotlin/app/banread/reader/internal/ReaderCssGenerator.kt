package app.banread.reader.internal

import app.banread.model.ReaderTheme
import app.banread.model.TypographyProfile
import app.banread.reader.RegisteredFont
import app.banread.reader.TextAlignment
import app.banread.reader.ThemePalette

internal object ReaderCssGenerator {

    fun generate(
        typography: TypographyProfile,
        theme: ReaderTheme,
        alignment: TextAlignment = TextAlignment.JUSTIFY,
        registeredFonts: List<RegisteredFont> = emptyList(),
    ): String {
        val clampedFontSizeSp = typography.fontSizeSp.coerceIn(12f, 36f)
        val clampedLineHeight = typography.lineHeightMultiplier.coerceIn(1.2f, 2.4f)
        val clampedParaSpacingDp = typography.paragraphSpacingDp.coerceAtLeast(0f)

        val palette = ThemePalette.colorsFor(theme)
        val textAlignCss = when (alignment) {
            TextAlignment.LEFT -> "left"
            TextAlignment.JUSTIFY -> "justify"
        }

        val sb = StringBuilder()
        sb.appendLine("""@namespace epub "http://www.idpf.org/2007/ops";""")

        for (font in registeredFonts) {
            val safeFamily = escapeCssString(font.cssFontFamily)
            val safeUrl = escapeCssString(font.assetUrl)
            sb.appendLine(
                """
                @font-face {
                    font-family: "$safeFamily";
                    src: url("$safeUrl");
                    font-display: swap;
                }
                """.trimIndent(),
            )
        }

        val fontFamilyCss = if (typography.fontFamily.isNotBlank()) {
            """"${escapeCssString(typography.fontFamily)}", "Noto Serif CJK SC", serif"""
        } else {
            """"Noto Serif CJK SC", serif"""
        }

        sb.appendLine(
            """
            html, body {
                background-color: ${palette.backgroundHex} !important;
                color: ${palette.foregroundHex} !important;
                font-family: $fontFamilyCss !important;
                font-size: ${clampedFontSizeSp}px !important;
                line-height: $clampedLineHeight !important;
                color-scheme: ${if (palette.isDark) "dark" else "light"};
                ${if (palette.isEink) "font-weight: 500 !important;" else ""}
            }
            body {
                margin: 0;
            }
            a:link, a:visited {
                color: ${palette.linkHex} !important;
            }
            ::selection {
                background: ${palette.selectionHex};
            }
            p, li, blockquote, dd {
                line-height: $clampedLineHeight !important;
                text-align: $textAlignCss;
                margin-bottom: ${clampedParaSpacingDp}px;
                widows: 2;
                orphans: 2;
            }
            p:not(.no-indent) {
                text-indent: 2em;
            }
            """.trimIndent(),
        )

        if (typography.squeezeChinesePunctuation) {
            // Avoid combining "palt" and "halt": on Android/Samsung CJK system fonts, HarfBuzz
            // applies both GPOS advance reductions cumulatively and collapses punctuation width to 0.
            sb.appendLine(
                """
                body, p, li, blockquote, dd, h1, h2, h3, h4, h5, h6 {
                    text-spacing-trim: trim-start;
                    font-feature-settings: "chws" 1;
                }
                """.trimIndent(),
            )
        } else {
            sb.appendLine(
                """
                body, p, li, blockquote, dd, h1, h2, h3, h4, h5, h6 {
                    text-spacing-trim: normal;
                    font-feature-settings: normal;
                }
                """.trimIndent(),
            )
        }

        if (palette.isEink) {
            sb.appendLine(
                """
                *, *::before, *::after {
                    transition: none !important;
                    animation: none !important;
                    scroll-behavior: auto !important;
                }
                img {
                    filter: contrast(1.12);
                }
                """.trimIndent(),
            )
        }

        sb.appendLine(
            """
            body:not([data-chapter-role="notes"]) aside[epub|type~="endnote"],
            body:not([data-chapter-role="notes"]) aside[epub|type~="footnote"],
            body:not([data-chapter-role="notes"]) aside[epub|type~="note"],
            body:not([data-chapter-role="notes"]) aside[epub|type~="rearnote"] {
                display: none !important;
            }
            """.trimIndent(),
        )

        return sb.toString()
    }

    private fun escapeCssString(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")
}
