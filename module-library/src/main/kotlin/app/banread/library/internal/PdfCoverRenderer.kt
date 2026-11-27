package app.banread.library.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Internal PDF page-0 cover renderer seam inside `:module-library` (zero `:module-reader` dependency).
 */
internal fun interface PdfCoverRenderer {
    suspend fun renderPageZeroPng(pdfFile: File, maxWidthPx: Int): ByteArray?
}

internal class AndroidPdfCoverRenderer : PdfCoverRenderer {
    override suspend fun renderPageZeroPng(pdfFile: File, maxWidthPx: Int): ByteArray? {
        if (!pdfFile.isFile || maxWidthPx <= 0) return null
        return try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) return null
                    renderer.openPage(0).use { page ->
                        val srcWidth = page.width.coerceAtLeast(1)
                        val srcHeight = page.height.coerceAtLeast(1)
                        val targetWidth = minOf(maxWidthPx, srcWidth)
                        val scale = targetWidth.toFloat() / srcWidth.toFloat()
                        val targetHeight = maxOf(1, (srcHeight * scale).roundToInt())
                        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val out = ByteArrayOutputStream()
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                                return null
                            }
                            out.toByteArray()
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
