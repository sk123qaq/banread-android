package app.banread.model

import kotlinx.serialization.Serializable

@Serializable
enum class CoverDisplayMode {
    CLOTH_FALLBACK,
    PORTRAIT_CROP,
    WIDE_PAPER_MAT,
}

/**
 * Classifies how a book cover should be rendered in a 2:3 bookshelf slot.
 * Aspect ratios (`width / height`) in `0.45f..0.85f` crop naturally into 2:3 (`PORTRAIT_CROP`);
 * other valid positive dimensions render inside a paper mat (`WIDE_PAPER_MAT`);
 * missing or invalid dimensions fall back to cloth binding (`CLOTH_FALLBACK`).
 */
fun classifyCoverDisplayMode(
    coverRel: String?,
    width: Int?,
    height: Int?,
): CoverDisplayMode {
    if (coverRel.isNullOrBlank() || width == null || height == null || width <= 0 || height <= 0) {
        return CoverDisplayMode.CLOTH_FALLBACK
    }
    val aspect = width.toFloat() / height.toFloat()
    return if (aspect in 0.45f..0.85f) {
        CoverDisplayMode.PORTRAIT_CROP
    } else {
        CoverDisplayMode.WIDE_PAPER_MAT
    }
}

data class WorkSummary(
    val workId: WorkId,
    val title: String,
    val author: String,
    val readProfileId: ReadProfileId,
    val literatureSubKind: String?,
    val clothColor: String,
    val format: String,
    val supportedModes: Set<ReadingSurfaceMode>,
    val isScannedPdf: Boolean = false,
    val coverRel: String? = null,
    val coverWidth: Int? = null,
    val coverHeight: Int? = null,
    val coverDisplayMode: CoverDisplayMode = classifyCoverDisplayMode(coverRel, coverWidth, coverHeight),
    val coverFilePath: String? = null,
    val addedAtMs: Long = 0L,
    val bookSourceSha256: String? = null,
)
