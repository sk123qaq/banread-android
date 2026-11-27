package app.banread.app.reader

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebView
import app.banread.model.AuditedArtifact
import app.banread.model.NormalizedRect
import app.banread.model.ReaderTheme
import app.banread.model.VizHostEvent
import app.banread.reader.FoliateWebAdapter
import app.banread.reader.FootnoteClickEvent
import app.banread.reader.PaginationMode
import app.banread.reader.PdfAnnotationOverlay
import app.banread.reader.PdfViewportState
import app.banread.reader.PdfiumPageAdapter
import app.banread.reader.RenderedPdfPage
import app.banread.reader.TextSelectionEvent
import app.banread.viz.WebViewVizHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Pure-Kotlin abstraction over [FoliateWebAdapter] events and paging commands
 * so ViewModels remain 100% unit-testable on the JVM.
 */
interface ReflowSurfaceController {
    val footnoteEvents: Flow<FootnoteClickEvent>
    val selectionEvents: Flow<TextSelectionEvent>
    fun prevPage()
    fun nextPage()
    fun setPaginationMode(mode: PaginationMode)
}

class FoliateReflowSurfaceController(
    private val adapter: FoliateWebAdapter,
) : ReflowSurfaceController {
    override val footnoteEvents: Flow<FootnoteClickEvent> = adapter.footnoteEvents
    override val selectionEvents: Flow<TextSelectionEvent> = adapter.selectionEvents
    override fun prevPage() = adapter.prevPage()
    override fun nextPage() = adapter.nextPage()
    override fun setPaginationMode(mode: PaginationMode) = adapter.setPaginationMode(mode)
}

/**
 * Pure-Kotlin abstraction over [PdfiumPageAdapter] viewport and rendering calls.
 */
interface PdfSurfaceController {
    val viewportState: StateFlow<PdfViewportState>
    fun setZoomAndPan(scale: Float, panX: Float, panY: Float)
    fun setAutoCrop(enabled: Boolean)
    suspend fun nextPage()
    suspend fun prevPage()
    suspend fun renderCurrentPage(targetWidthPx: Int): RenderedPdfPage?
    fun annotationsForPage(pageIndex: Int): List<PdfAnnotationOverlay>
}

class PdfiumSurfaceController(
    private val adapter: PdfiumPageAdapter,
) : PdfSurfaceController {
    override val viewportState: StateFlow<PdfViewportState> = adapter.viewportState
    override fun setZoomAndPan(scale: Float, panX: Float, panY: Float) =
        adapter.setZoomAndPan(scale, panX, panY)
    override fun setAutoCrop(enabled: Boolean) = adapter.setAutoCrop(enabled)
    override suspend fun nextPage() = adapter.nextPage()
    override suspend fun prevPage() = adapter.prevPage()
    override suspend fun renderCurrentPage(targetWidthPx: Int): RenderedPdfPage? =
        adapter.renderCurrentPage(targetWidthPx)
    override fun annotationsForPage(pageIndex: Int): List<PdfAnnotationOverlay> =
        adapter.annotationsForPage(pageIndex)
}

/**
 * Pure-Kotlin abstraction over [WebViewVizHost] so [CompanionViewModel] is testable on JVM.
 */
interface VizHostController {
    fun attachWebView(hostCallback: (VizHostEvent) -> Unit)
    suspend fun renderArtifact(artifact: AuditedArtifact, theme: ReaderTheme)
    suspend fun updateTheme(theme: ReaderTheme)
    fun setFullscreenMode(enabled: Boolean)
    fun acquireWebView(context: Context): WebView
    fun detachWebView()
}

class WebViewVizHostController(
    private val vizHost: WebViewVizHost,
) : VizHostController {
    override fun attachWebView(hostCallback: (VizHostEvent) -> Unit) =
        vizHost.attachWebView(hostCallback)

    override suspend fun renderArtifact(artifact: AuditedArtifact, theme: ReaderTheme) =
        vizHost.renderArtifact(artifact, theme)

    override suspend fun updateTheme(theme: ReaderTheme) =
        vizHost.updateTheme(theme)

    override fun setFullscreenMode(enabled: Boolean) =
        vizHost.setFullscreenMode(enabled)

    override fun acquireWebView(context: Context): WebView =
        vizHost.acquireWebView(context)

    override fun detachWebView() =
        vizHost.detachWebView()
}

/**
 * Computes a clamped normalized [0f..1f] crop rectangle from two drag points in canvas coordinates.
 * Returns null when the selected box is too tiny (< 2% width or height) to be intentional.
 */
fun computeNormalizedCropRect(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    minNormalizedSpan: Float = 0.02f,
): NormalizedRect? {
    if (canvasWidth <= 0f || canvasHeight <= 0f) return null
    val left = (minOf(startX, endX) / canvasWidth).coerceIn(0f, 1f)
    val top = (minOf(startY, endY) / canvasHeight).coerceIn(0f, 1f)
    val right = (maxOf(startX, endX) / canvasWidth).coerceIn(0f, 1f)
    val bottom = (maxOf(startY, endY) / canvasHeight).coerceIn(0f, 1f)
    if ((right - left) < minNormalizedSpan || (bottom - top) < minNormalizedSpan) {
        return null
    }
    return NormalizedRect(left = left, top = top, right = right, bottom = bottom)
}

/**
 * Pure pixel-coordinate crop box mapping for a bitmap of size [bitmapWidth] x [bitmapHeight].
 */
data class PixelCropBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

fun mapNormalizedRectToPixelBounds(
    rect: NormalizedRect,
    bitmapWidth: Int,
    bitmapHeight: Int,
): PixelCropBounds? {
    if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
    val left = (rect.left.coerceIn(0f, 1f) * bitmapWidth).roundToInt().coerceIn(0, bitmapWidth - 1)
    val top = (rect.top.coerceIn(0f, 1f) * bitmapHeight).roundToInt().coerceIn(0, bitmapHeight - 1)
    val right = (rect.right.coerceIn(0f, 1f) * bitmapWidth).roundToInt().coerceIn(left + 1, bitmapWidth)
    val bottom = (rect.bottom.coerceIn(0f, 1f) * bitmapHeight).roundToInt().coerceIn(top + 1, bitmapHeight)
    val w = right - left
    val h = bottom - top
    if (w <= 0 || h <= 0) return null
    return PixelCropBounds(x = left, y = top, width = w, height = h)
}

/**
 * Crops the normalized [rect] region from [source] and encodes it as PNG bytes.
 * Intended to be called off the main thread (`Dispatchers.Default` / `Dispatchers.IO`).
 */
fun cropBitmapRegionToPng(source: Bitmap, rect: NormalizedRect): ByteArray {
    val bounds = mapNormalizedRectToPixelBounds(rect, source.width, source.height)
        ?: return ByteArray(0)
    val cropped = Bitmap.createBitmap(source, bounds.x, bounds.y, bounds.width, bounds.height)
    return try {
        ByteArrayOutputStream().use { baos ->
            cropped.compress(Bitmap.CompressFormat.PNG, 100, baos)
            baos.toByteArray()
        }
    } finally {
        if (cropped !== source) {
            cropped.recycle()
        }
    }
}
