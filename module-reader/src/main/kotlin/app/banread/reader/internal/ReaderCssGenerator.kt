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
        val clampedParaSpacingDp = typography.paragraphSpacingDp.coerceIn(0f, 28f)

        val palette = ThemePalette.colorsFor(theme)
        val textAlignCss = when (alignment) {
            TextAlignment.LEFT -> "left"
            TextAlignment.JUSTIFY -> "justify"
        }

        val ink2 = rgba(palette.foregroundHex, 0.76)
        val ink3 = rgba(palette.foregroundHex, 0.56)
        val line = if (palette.isEink) "#000000" else rgba(palette.foregroundHex, 0.16)
        val lineStrong = if (palette.isEink) "#000000" else rgba(palette.foregroundHex, 0.26)
        val accent = palette.accentHex
        val accentSoft = rgba(accent, if (palette.isDark) 0.13 else 0.09)
        val codeBg = if (palette.isDark) {
            rgba("#FFFFFF", 0.06)
        } else {
            rgba("#000000", 0.045)
        }
        val highlightBg = if (palette.isDark) {
            rgba("#FDE047", 0.26)
        } else {
            rgba("#FDE047", 0.38)
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
        val sansFamily = """"Noto Sans CJK SC", "PingFang SC", sans-serif"""
        val monoFamily = """ui-monospace, "JetBrains Mono", "Noto Sans Mono CJK SC", monospace"""

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
                margin: 0 auto;
                max-width: 680px;
                padding-top: 20px;
                padding-bottom: 36px;
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
                text-justify: inter-ideograph;
                margin: 0 0 ${clampedParaSpacingDp}px;
                widows: 2;
                orphans: 2;
            }
            p, blockquote p, li p, dd p {
                text-indent: 0 !important;
            }
            p:not(.no-indent) {
                text-indent: 0 !important;
            }
            h1, h2, h3, h4, h5, h6 {
                color: ${palette.foregroundHex};
                font-weight: 700;
                line-height: 1.28;
                text-indent: 0;
                margin: 1.1em 0 0.55em;
            }
            h1 {
                font-size: 1.72em;
            }
            h2 {
                font-size: 1.22em;
                margin-top: 1.35em;
            }
            h3 {
                font-size: 1.08em;
            }
            h4, h5, h6 {
                font-size: 1em;
            }
            .book-chapter-head {
                margin: 0 0 1.5em;
                padding: 0 0 1.05em;
                border-bottom: 1px solid $line;
            }
            .chapter-kicker {
                display: flex;
                align-items: center;
                gap: 8px;
                margin: 0 0 0.6em;
                color: $accent;
                font-family: $sansFamily;
                font-size: 11px;
                font-weight: 600;
                letter-spacing: 0.12em;
            }
            .chapter-kicker::before {
                content: "";
                flex: 0 0 auto;
                width: 6px;
                height: 6px;
                border-radius: 1px;
                background: $accent;
            }
            .book-chapter-title {
                margin: 0 0 0.45em;
                color: ${palette.foregroundHex};
                font-size: 1.72em;
                font-weight: 800;
                line-height: 1.28;
                letter-spacing: -0.01em;
            }
            .chapter-meta {
                margin: 0;
                color: $ink3;
                font-family: $sansFamily;
                font-size: 11.5px;
                line-height: 1.5;
            }
            blockquote {
                margin: 1.15em 0 1.35em;
                padding: 0.1em 0 0.1em 14px;
                border-left: 1.5px solid $lineStrong;
                color: $ink2;
            }
            blockquote p {
                margin-bottom: 0.55em;
            }
            blockquote p:last-child {
                margin-bottom: 0;
            }
            ul, ol {
                margin: 0 0 ${clampedParaSpacingDp}px;
                padding-left: 22px;
            }
            li {
                margin-bottom: 0.18em;
            }
            li:last-child {
                margin-bottom: 0;
            }
            li::marker {
                color: $accent;
            }
            .table-wrap {
                margin: 1.25em 0;
                overflow-x: auto;
                -webkit-overflow-scrolling: touch;
            }
            table {
                width: max-content;
                min-width: 100%;
                margin: 0;
                border-collapse: collapse;
                font-family: $sansFamily;
                font-size: 0.75em;
                line-height: 1.55;
            }
            th, td {
                padding: 7px 10px;
                border: 0;
                border-bottom: 1px solid $line;
                text-align: left;
                vertical-align: top;
            }
            thead th {
                color: $ink2;
                font-weight: 600;
            }
            pre {
                margin: 1.25em 0;
                padding: 12px 14px;
                overflow-x: auto;
                background: $codeBg;
                border: 1px solid $line;
                border-radius: 8px;
                font-family: $monoFamily;
                font-size: 0.82em;
                line-height: 1.55;
                white-space: pre;
            }
            pre code {
                padding: 0;
                background: none;
                font-family: inherit;
                font-size: inherit;
            }
            :not(pre) > code {
                padding: 0.08em 0.3em;
                background: $codeBg;
                border-radius: 3px;
                font-family: $monoFamily;
                font-size: 0.88em;
            }
            figure {
                margin: 1.4em 0;
            }
            img {
                max-width: 100% !important;
                height: auto !important;
                border-radius: 8px;
                outline: 1px solid $line;
                outline-offset: -1px;
            }
            figure img {
                display: block;
                width: 100%;
            }
            figcaption {
                margin-top: 7px;
                color: $ink3;
                font-family: $sansFamily;
                font-size: 12px;
                line-height: 1.5;
                text-align: center;
            }
            a[epub|type~="noteref"] {
                color: $accent !important;
                padding: 0 0.1em;
                font-size: 0.72em;
                font-weight: 600;
                line-height: 0;
                text-decoration: none !important;
                vertical-align: super;
            }
            .fn-marker {
                margin-right: 0.4em;
                color: $accent;
                font-weight: 600;
            }
            body[data-chapter-role="notes"] aside[epub|type~="footnote"],
            body[data-chapter-role="notes"] aside[epub|type~="endnote"],
            body[data-chapter-role="notes"] aside[epub|type~="note"],
            body[data-chapter-role="notes"] aside[epub|type~="rearnote"] {
                display: block !important;
                margin: 0 0 1em;
                padding: 0.65em 0 0.65em 12px;
                border-left: 1.5px solid $lineStrong;
                color: $ink2;
                font-size: 0.86em;
                line-height: 1.65;
            }
            mark, .hl-hit {
                padding: 0 0.08em;
                border-radius: 2px;
                background: $highlightBg;
                color: inherit;
            }
            .reader-block.is-active,
            [data-block-id].is-active {
                border-radius: 0 4px 4px 0;
                background: $accentSoft;
                box-shadow: inset 2px 0 0 $accent;
            }
            details.pin-note {
                margin: -0.35em 0 1em;
                padding: 6px 10px 6px 12px;
                border-left: 1.5px solid $accent;
                border-radius: 0 6px 6px 0;
                background: $accentSoft;
                color: $ink2;
                font-family: $sansFamily;
                font-size: 0.82em;
                line-height: 1.5;
            }
            details.pin-note summary {
                cursor: pointer;
                color: $accent;
                font-weight: 600;
            }
            details.pin-note p {
                margin: 0.4em 0 0;
                text-indent: 0;
            }
            hr.section-break {
                position: relative;
                width: 4px;
                height: 4px;
                margin: 1.8em auto;
                border: 0;
                border-radius: 50%;
                background: $accent;
            }
            hr.section-break::before,
            hr.section-break::after {
                content: "";
                position: absolute;
                top: 0;
                width: 4px;
                height: 4px;
                border-radius: 50%;
                background: $accent;
            }
            hr.section-break::before {
                left: -20px;
            }
            hr.section-break::after {
                right: -20px;
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

    private fun rgba(hex: String, alpha: Double): String {
        val raw = hex.removePrefix("#")
        if (raw.length != 6) return hex
        val red = raw.substring(0, 2).toIntOrNull(16) ?: return hex
        val green = raw.substring(2, 4).toIntOrNull(16) ?: return hex
        val blue = raw.substring(4, 6).toIntOrNull(16) ?: return hex
        return "rgba($red, $green, $blue, $alpha)"
    }

    private fun escapeCssString(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")
}
