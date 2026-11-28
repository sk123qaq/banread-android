package app.banread.library.internal

import java.io.File

internal enum class ImageFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp"),
    JPEG("jpg", "image/jpeg"),
}

internal data class InspectedImage(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
)

internal object ImageHeaderInspector {

    const val MAX_COVER_BYTES: Long = 10L * 1024L * 1024L

    fun inspect(file: File): InspectedImage? {
        if (!file.isFile) return null
        val len = file.length()
        if (len <= 0L || len > MAX_COVER_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return inspect(bytes)
    }

    fun inspect(bytes: ByteArray): InspectedImage? {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_COVER_BYTES) return null
        return parsePng(bytes)
            ?: parseGif(bytes)
            ?: parseWebp(bytes)
            ?: parseJpeg(bytes)
    }

    private fun parsePng(bytes: ByteArray): InspectedImage? {
        if (bytes.size < 24) return null
        if (bytes[0] != 0x89.toByte() ||
            bytes[1] != 0x50.toByte() ||
            bytes[2] != 0x4E.toByte() ||
            bytes[3] != 0x47.toByte() ||
            bytes[4] != 0x0D.toByte() ||
            bytes[5] != 0x0A.toByte() ||
            bytes[6] != 0x1A.toByte() ||
            bytes[7] != 0x0A.toByte()
        ) {
            return null
        }
        // First chunk must be IHDR at offset 12..15
        if (bytes[12] != 'I'.code.toByte() ||
            bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() ||
            bytes[15] != 'R'.code.toByte()
        ) {
            return null
        }
        val width = readIntBE(bytes, 16)
        val height = readIntBE(bytes, 20)
        if (width <= 0 || height <= 0) return null
        return InspectedImage(format = ImageFormat.PNG, width = width, height = height)
    }

    private fun parseGif(bytes: ByteArray): InspectedImage? {
        if (bytes.size < 10) return null
        val header = String(bytes, 0, 6, Charsets.US_ASCII)
        if (header != "GIF87a" && header != "GIF89a") return null
        val width = readUInt16LE(bytes, 6)
        val height = readUInt16LE(bytes, 8)
        if (width <= 0 || height <= 0) return null
        return InspectedImage(format = ImageFormat.GIF, width = width, height = height)
    }

    private fun parseWebp(bytes: ByteArray): InspectedImage? {
        if (bytes.size < 25) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP") return null
        return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
            "VP8 " -> {
                if (bytes.size < 30) return null
                // VP8 key frame sync code at payload+3..5 (bytes 23..25): 9D 01 2A
                if ((bytes[23].toInt() and 0xFF) != 0x9D ||
                    (bytes[24].toInt() and 0xFF) != 0x01 ||
                    (bytes[25].toInt() and 0xFF) != 0x2A
                ) {
                    return null
                }
                val width = readUInt16LE(bytes, 26) and 0x3FFF
                val height = readUInt16LE(bytes, 28) and 0x3FFF
                if (width <= 0 || height <= 0) null else InspectedImage(ImageFormat.WEBP, width, height)
            }
            "VP8L" -> {
                if (bytes[20] != 0x2F.toByte()) return null
                val b0 = bytes[21].toInt() and 0xFF
                val b1 = bytes[22].toInt() and 0xFF
                val b2 = bytes[23].toInt() and 0xFF
                val b3 = bytes[24].toInt() and 0xFF
                val packed = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                val width = (packed and 0x3FFF) + 1
                val height = ((packed ushr 14) and 0x3FFF) + 1
                if (width <= 0 || height <= 0) null else InspectedImage(ImageFormat.WEBP, width, height)
            }
            "VP8X" -> {
                if (bytes.size < 30) return null
                val width = readUInt24LE(bytes, 24) + 1
                val height = readUInt24LE(bytes, 27) + 1
                if (width <= 0 || height <= 0) null else InspectedImage(ImageFormat.WEBP, width, height)
            }
            else -> null
        }
    }

    private fun parseJpeg(bytes: ByteArray): InspectedImage? {
        if (bytes.size < 4) return null
        if ((bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) {
            return null
        }
        var pos = 2
        while (pos < bytes.size) {
            if ((bytes[pos].toInt() and 0xFF) != 0xFF) {
                return null
            }
            while (pos < bytes.size && (bytes[pos].toInt() and 0xFF) == 0xFF) {
                pos++
            }
            if (pos >= bytes.size) return null
            val marker = bytes[pos].toInt() and 0xFF
            pos++

            // End of image / start of scan / stuffed byte -> stop scanning
            if (marker == 0xD9 || marker == 0xDA || marker == 0x00) {
                return null
            }
            // Standalone markers without length field
            if (marker == 0x01 || marker in 0xD0..0xD8) {
                continue
            }

            if (pos + 2 > bytes.size) return null
            val segLen = readUInt16BE(bytes, pos)
            if (segLen < 2 || pos + segLen > bytes.size) return null

            if (marker in SOF_MARKERS) {
                if (segLen < 7) return null
                val height = readUInt16BE(bytes, pos + 3)
                val width = readUInt16BE(bytes, pos + 5)
                if (width <= 0 || height <= 0) return null
                return InspectedImage(format = ImageFormat.JPEG, width = width, height = height)
            }
            pos += segLen
        }
        return null
    }

    private val SOF_MARKERS = setOf(
        0xC0, // SOF0
        0xC1, // SOF1
        0xC2, // SOF2
        0xC3, // SOF3
        0xC5, // SOF5
        0xC6, // SOF6
        0xC7, // SOF7
        0xC9, // SOF9
        0xCA, // SOF10
        0xCB, // SOF11
        0xCD, // SOF13
        0xCE, // SOF14
        0xCF, // SOF15
    )

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readUInt16BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt24LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}
