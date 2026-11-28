package app.banread.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.banread.model.BookRendererPort
import app.banread.model.ChapterOrdinal
import app.banread.model.NormalizedRect
import app.banread.model.NoteLogEntry
import app.banread.model.ReadingAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Native fixed-layout PDF [BookRendererPort] backed by Android's built-in [PdfRenderer].
 * Provides auto-margin content bbox detection, zoom/pan viewport state, and normalized
 * `0f..1f` annotation overlay mapping without third-party native `.so` binaries.
 */
class PdfiumPageAdapter : BookRendererPort, Closeable {

    private val mutex = Mutex()
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    private val _locationEvents = MutableSharedFlow<ReadingAnchor>(extraBufferCapacity = 32)
    override val locationEvents: Flow<ReadingAnchor> = _locationEvents.asSharedFlow()

    private val _viewportState = MutableStateFlow(PdfViewportState())
    val viewportState: StateFlow<PdfViewportState> = _viewportState.asStateFlow()

    @Volatile
    private var overlaysByPage: Map<Int, List<PdfAnnotationOverlay>> = emptyMap()

    override suspend fun load(compiledAssetRoot: String, initialAnchor: ReadingAnchor?) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                closeLocked()
                val pdfFile = File(compiledAssetRoot)
                if (pdfFile.isFile) {
                    runCatching {
                        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(pfd)
                        fileDescriptor = pfd
                        pdfRenderer = renderer
                    }
                }
                val count = pdfRenderer?.pageCount ?: 0
                val initialPage = when (initialAnchor) {
                    is ReadingAnchor.PdfPage -> initialAnchor.pageIndex
                    else -> ((initialAnchor?.ordinal?.value ?: 1) - 1)
                }.coerceIn(0, maxOf(0, count - 1))

                _viewportState.value = _viewportState.value.copy(
                    pageIndex = initialPage,
                    pageCount = count,
                    zoomScale = 1f,
                    panX = 0f,
                    panY = 0f,
                )
            }
        }
    }

    override suspend fun navigate(anchor: ReadingAnchor) {
        val targetPage = when (anchor) {
            is ReadingAnchor.PdfPage -> anchor.pageIndex
            else -> (anchor.ordinal.value - 1).coerceAtLeast(0)
        }
        val pageCount = _viewportState.value.pageCount
        val clamped = if (pageCount > 0) targetPage.coerceIn(0, pageCount - 1) else targetPage
        _viewportState.value = _viewportState.value.copy(pageIndex = clamped)
        _locationEvents.tryEmit(
            ReadingAnchor.PdfPage(
                ordinal = ChapterOrdinal(clamped + 1),
                pageIndex = clamped,
                normalizedBoxes = emptyList(),
            ),
        )
    }

    override suspend fun updateStyles(cssPayload: String) {
        val isDark = cssPayload.contains("color-scheme: dark")
        _viewportState.value = _viewportState.value.copy(invertColors = isDark)
    }

    override suspend fun renderDecorations(annotations: List<NoteLogEntry>) {
        val grouped = annotations
            .asSequence()
            .filter { it.deletedAtEpochMs == null }
            .mapNotNull { entry ->
                val pdfAnchor = entry.anchor as? ReadingAnchor.PdfPage ?: return@mapNotNull null
                val clampedBoxes = pdfAnchor.normalizedBoxes.map { box ->
                    NormalizedRect(
                        left = box.left.coerceIn(0f, 1f),
                        top = box.top.coerceIn(0f, 1f),
                        right = box.right.coerceIn(0f, 1f),
                        bottom = box.bottom.coerceIn(0f, 1f),
                    )
                }
                pdfAnchor.pageIndex to PdfAnnotationOverlay(
                    noteId = entry.noteId,
                    colorToken = entry.colorToken,
                    comment = entry.comment,
                    normalizedBoxes = clampedBoxes,
                )
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        overlaysByPage = grouped
    }

    fun annotationsForPage(pageIndex: Int): List<PdfAnnotationOverlay> =
        overlaysByPage[pageIndex].orEmpty()

    fun setZoomAndPan(scale: Float, panX: Float, panY: Float) {
        _viewportState.value = _viewportState.value.copy(
            zoomScale = scale.coerceIn(1f, 5f),
            panX = panX,
            panY = panY,
        )
    }

    fun setAutoCrop(enabled: Boolean) {
        _viewportState.value = _viewportState.value.copy(autoCropEnabled = enabled)
    }

    suspend fun nextPage() {
        val current = _viewportState.value
        val maxPage = maxOf(0, current.pageCount - 1)
        if (current.pageIndex < maxPage) {
            val next = current.pageIndex + 1
            navigate(ReadingAnchor.PdfPage(ChapterOrdinal(next + 1), next, emptyList()))
        }
    }

    suspend fun prevPage() {
        val current = _viewportState.value
        if (current.pageIndex > 0) {
            val prev = current.pageIndex - 1
            navigate(ReadingAnchor.PdfPage(ChapterOrdinal(prev + 1), prev, emptyList()))
        }
    }

    suspend fun renderCurrentPage(targetWidthPx: Int): RenderedPdfPage? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val renderer = pdfRenderer ?: return@withLock null
            if (renderer.pageCount <= 0) return@withLock null
            val pageIdx = _viewportState.value.pageIndex.coerceIn(0, renderer.pageCount - 1)
            val safeWidth = targetWidthPx.coerceAtLeast(64)

            renderer.openPage(pageIdx).use { page ->
                val aspect = page.height.toFloat() / maxOf(1, page.width).toFloat()
                val outHeight = maxOf(64, (safeWidth * aspect).toInt())
                val bitmap = Bitmap.createBitmap(safeWidth, outHeight, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val contentBox = if (_viewportState.value.autoCropEnabled) {
                    detectContentBoundingBox(bitmap)
                } else {
                    NormalizedRect(0f, 0f, 1f, 1f)
                }
                _viewportState.value = _viewportState.value.copy(contentBox = contentBox)

                RenderedPdfPage(
                    pageIndex = pageIdx,
                    bitmap = bitmap,
                    contentBox = contentBox,
                    overlays = overlaysByPage[pageIdx].orEmpty(),
                )
            }
        }
    }

    override fun close() {
        closeLocked()
    }

    private fun closeLocked() {
        runCatching { pdfRenderer?.close() }
        runCatching { fileDescriptor?.close() }
        pdfRenderer = null
        fileDescriptor = null
    }

    companion object {
        fun detectContentBoundingBox(
            bitmap: Bitmap,
            backgroundLumaThreshold: Int = 245,
            paddingRatio: Float = 0.02f,
        ): NormalizedRect {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return NormalizedRect(0f, 0f, 1f, 1f)

            val step = maxOf(1, minOf(w, h) / 256)
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1

            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            var y = 0
            while (y < h) {
                val rowOffset = y * w
                var x = 0
                while (x < w) {
                    val px = pixels[rowOffset + x]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    val luma = (r * 299 + g * 587 + b * 114) / 1000
                    if (luma < backgroundLumaThreshold) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                    x += step
                }
                y += step
            }

            if (maxX < minX || maxY < minY) {
                return NormalizedRect(0f, 0f, 1f, 1f)
            }

            val left = (minX.toFloat() / w.toFloat() - paddingRatio).coerceIn(0f, 1f)
            val top = (minY.toFloat() / h.toFloat() - paddingRatio).coerceIn(0f, 1f)
            val right = ((maxX + step).toFloat() / w.toFloat() + paddingRatio).coerceIn(0f, 1f)
            val bottom = ((maxY + step).toFloat() / h.toFloat() + paddingRatio).coerceIn(0f, 1f)

            return if (right > left && bottom > top) {
                NormalizedRect(left, top, right, bottom)
            } else {
                NormalizedRect(0f, 0f, 1f, 1f)
            }
        }
    }
}
