package app.banread.reader.internal

import app.banread.model.ReaderTheme
import app.banread.model.TypographyProfile
import app.banread.reader.TextAlignment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCssGeneratorTest {

    private val typography = TypographyProfile(
        fontFamily = "Noto Serif CJK SC",
        fontSizeSp = 18f,
        lineHeightMultiplier = 1.88f,
        paragraphSpacingDp = 22f,
        horizontalMarginDp = 20f,
        squeezeChinesePunctuation = true,
    )

    @Test
    fun `reading measure keeps zero indent and the nine body treatments`() {
        val css = ReaderCssGenerator.generate(typography, ReaderTheme.SEPIA_WARM, TextAlignment.JUSTIFY)

        assertTrue(css.contains("max-width: 680px"))
        assertTrue(css.contains("text-indent: 0 !important"))
        assertTrue(css.contains("text-justify: inter-ideograph"))
        assertTrue(css.contains("h2 {"))
        assertTrue(css.contains("font-size: 1.22em"))
        assertTrue(css.contains("border-left: 1.5px solid"))
        assertTrue(css.contains(".table-wrap"))
        assertTrue(css.contains("figcaption"))
        assertTrue(css.contains("a[epub|type~=\"noteref\"]"))
        assertTrue(css.contains("mark, .hl-hit"))
        assertTrue(css.contains("details.pin-note"))
        assertTrue(css.contains("hr.section-break"))
    }

    @Test
    fun `theme accent and eink motion rules follow the selected palette`() {
        val sepia = ReaderCssGenerator.generate(typography, ReaderTheme.SEPIA_WARM)
        assertTrue(sepia.contains("#A8442F"))
        assertFalse(sepia.contains("transition: none !important;"))

        val night = ReaderCssGenerator.generate(typography, ReaderTheme.SLATE_NIGHT)
        assertTrue(night.contains("#DF745D"))

        val eink = ReaderCssGenerator.generate(
            typography.copy(fontFamily = "Noto Serif CJK SC"),
            ReaderTheme.EINK_HIGH_CONTRAST,
        )
        assertTrue(eink.contains("#000000"))
        assertTrue(eink.contains("transition: none !important;"))
        assertTrue(eink.contains("animation: none !important;"))
        assertTrue(eink.contains("text-spacing-trim: trim-start;"))
        assertFalse(eink.contains(""""palt""""))
    }

    @Test
    fun `paragraph spacing and line height are emitted from the reader profile`() {
        val css = ReaderCssGenerator.generate(
            typography.copy(paragraphSpacingDp = 0f, lineHeightMultiplier = 2.4f),
            ReaderTheme.PAPER_LIGHT,
        )

        assertTrue(css.contains("line-height: 2.4 !important"))
        assertTrue(css.contains("margin: 0 0 0.0px"))
    }
}
