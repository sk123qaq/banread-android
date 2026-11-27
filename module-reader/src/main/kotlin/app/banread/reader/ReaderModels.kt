package app.banread.reader

import android.graphics.Bitmap
import app.banread.model.ChapterOrdinal
import app.banread.model.NormalizedRect
import app.banread.model.ReaderTheme
import app.banread.model.ReadingAnchor

enum class PaginationMode {
    SLIDE,
    COVER,
    NONE,
    VERTICAL_SCROLL,
}

enum class TextAlignment {
    LEFT,
    JUSTIFY,
}

data class ThemeColorSpec(
    val backgroundHex: String,
    val foregroundHex: String,
    val linkHex: String,
    val selectionHex: String,
    val isDark: Boolean,
    val isEink: Boolean,
)

object ThemePalette {
    fun colorsFor(theme: ReaderTheme): ThemeColorSpec = when (theme) {
        ReaderTheme.PAPER_LIGHT -> ThemeColorSpec(
            backgroundHex = "#FBF9F5",
            foregroundHex = "#1E1E1E",
            linkHex = "#1D4ED8",
            selectionHex = "rgba(37, 99, 235, 0.22)",
            isDark = false,
            isEink = false,
        )
        ReaderTheme.SEPIA_WARM -> ThemeColorSpec(
            backgroundHex = "#F4ECD8",
            foregroundHex = "#3B2F23",
            linkHex = "#7C4A1D",
            selectionHex = "rgba(180, 120, 40, 0.24)",
            isDark = false,
            isEink = false,
        )
        ReaderTheme.SLATE_NIGHT -> ThemeColorSpec(
            backgroundHex = "#1A1F29",
            foregroundHex = "#D8DEE9",
            linkHex = "#7DD3FC",
            selectionHex = "rgba(125, 211, 252, 0.24)",
            isDark = true,
            isEink = false,
        )
        ReaderTheme.AMOLED_BLACK -> ThemeColorSpec(
            backgroundHex = "#000000",
            foregroundHex = "#E5E7EB",
            linkHex = "#60A5FA",
            selectionHex = "rgba(96, 165, 250, 0.26)",
            isDark = true,
            isEink = false,
        )
        ReaderTheme.EINK_HIGH_CONTRAST -> ThemeColorSpec(
            backgroundHex = "#FFFFFF",
            foregroundHex = "#000000",
            linkHex = "#000000",
            selectionHex = "rgba(0, 0, 0, 0.18)",
            isDark = false,
            isEink = true,
        )
    }

    fun effectivePaginationMode(theme: ReaderTheme, requested: PaginationMode): PaginationMode {
        if (theme == ReaderTheme.EINK_HIGH_CONTRAST && requested != PaginationMode.VERTICAL_SCROLL) {
            return PaginationMode.NONE
        }
        return requested
    }
}

data class RegisteredFont(
    val fontId: String,
    val displayName: String,
    val cssFontFamily: String,
    val assetUrl: String,
)

data class FootnoteClickEvent(
    val ordinal: ChapterOrdinal,
    val markerOrHref: String,
)

data class SelectionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class TextSelectionEvent(
    val anchor: ReadingAnchor.ReflowCfi,
    val selectedText: String,
    val boundingRect: SelectionRect,
)

data class WebViewPackageStatus(
    val packageName: String?,
    val versionName: String?,
    val isAvailable: Boolean,
)

data class PdfViewportState(
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val zoomScale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val autoCropEnabled: Boolean = true,
    val contentBox: NormalizedRect = NormalizedRect(0f, 0f, 1f, 1f),
    val invertColors: Boolean = false,
)

data class PdfAnnotationOverlay(
    val noteId: String,
    val colorToken: String,
    val comment: String,
    val normalizedBoxes: List<NormalizedRect>,
)

data class RenderedPdfPage(
    val pageIndex: Int,
    val bitmap: Bitmap,
    val contentBox: NormalizedRect,
    val overlays: List<PdfAnnotationOverlay>,
)
