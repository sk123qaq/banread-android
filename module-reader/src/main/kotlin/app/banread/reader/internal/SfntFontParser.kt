package app.banread.reader.internal

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class SfntFontMetadata(
    val extension: String,
    val familyName: String,
)

internal object SfntFontParser {

    private const val TAG_TRUE_TYPE = 0x00010000
    private const val TAG_OTTO = 0x4F54544F // "OTTO"
    private const val TAG_TTCF = 0x74746366 // "ttcf"
    private const val TAG_NAME = 0x6E616D65 // "name"

    fun parse(file: File, fallbackName: String): SfntFontMetadata {
        if (!file.isFile || file.length() < 12L) {
            throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Font file is missing or too small"))
        }
        val bytes = file.readBytes()
        return parseBytes(bytes, fallbackName)
    }

    fun parseBytes(bytes: ByteArray, fallbackName: String): SfntFontMetadata {
        if (bytes.size < 12) {
            throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid SFNT font: truncated header"))
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.getInt(0)

        val (extension, sfntOffset) = when (magic) {
            TAG_TRUE_TYPE -> "ttf" to 0
            TAG_OTTO -> "otf" to 0
            TAG_TTCF -> {
                if (bytes.size < 16) {
                    throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid TTC font header"))
                }
                val numFonts = buf.getInt(8)
                if (numFonts <= 0 || bytes.size < 16) {
                    throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Empty TTC font collection"))
                }
                val firstOffset = buf.getInt(12)
                if (firstOffset < 0 || firstOffset + 12 > bytes.size) {
                    throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Corrupted TTC font offset"))
                }
                val innerMagic = buf.getInt(firstOffset)
                if (innerMagic != TAG_TRUE_TYPE && innerMagic != TAG_OTTO) {
                    throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid inner SFNT magic in TTC"))
                }
                "ttc" to firstOffset
            }
            else -> throw AppFailureException(
                AppFailure.UnsupportedDrmOrFormat("Unsupported font magic: 0x${magic.toUInt().toString(16)}"),
            )
        }

        val numTables = buf.getShort(sfntOffset + 4).toInt() and 0xFFFF
        if (numTables == 0 || sfntOffset + 12 + numTables * 16 > bytes.size) {
            throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid SFNT table directory"))
        }

        var nameTableOffset = -1
        var nameTableLength = 0
        for (i in 0 until numTables) {
            val recordPos = sfntOffset + 12 + i * 16
            val tag = buf.getInt(recordPos)
            if (tag == TAG_NAME) {
                nameTableOffset = buf.getInt(recordPos + 8)
                nameTableLength = buf.getInt(recordPos + 12)
                break
            }
        }

        val extractedFamily = if (nameTableOffset >= 0 && nameTableLength >= 6 &&
            nameTableOffset.toLong() + nameTableLength.toLong() <= bytes.size.toLong()
        ) {
            extractFamilyFromNameTable(buf, bytes, nameTableOffset, nameTableLength)
        } else {
            null
        }

        val cleanFamily = extractedFamily?.trim()?.takeIf { it.isNotBlank() }
            ?: fallbackName.substringBeforeLast('.').ifBlank { "CustomFont" }

        return SfntFontMetadata(
            extension = extension,
            familyName = cleanFamily,
        )
    }

    private fun extractFamilyFromNameTable(
        buf: ByteBuffer,
        bytes: ByteArray,
        tableOffset: Int,
        tableLength: Int,
    ): String? {
        val count = buf.getShort(tableOffset + 2).toInt() and 0xFFFF
        val stringOffset = buf.getShort(tableOffset + 4).toInt() and 0xFFFF
        val recordsEnd = tableOffset + 6 + count * 12
        if (recordsEnd > tableOffset + tableLength) return null

        val storageStart = tableOffset + stringOffset
        if (storageStart > tableOffset + tableLength) return null

        data class Candidate(val priority: Int, val value: String)
        val candidates = mutableListOf<Candidate>()

        for (i in 0 until count) {
            val recPos = tableOffset + 6 + i * 12
            val platformId = buf.getShort(recPos).toInt() and 0xFFFF
            val nameId = buf.getShort(recPos + 6).toInt() and 0xFFFF
            val strLen = buf.getShort(recPos + 8).toInt() and 0xFFFF
            val strOff = buf.getShort(recPos + 10).toInt() and 0xFFFF

            if (nameId != 1 && nameId != 16) continue
            val absStart = storageStart + strOff
            val absEnd = absStart + strLen
            if (absStart < storageStart || absEnd > tableOffset + tableLength || absEnd > bytes.size || strLen == 0) {
                continue
            }
            val decoded = when (platformId) {
                3, 0 -> String(bytes, absStart, strLen, Charsets.UTF_16BE)
                1 -> String(bytes, absStart, strLen, Charsets.ISO_8859_1)
                else -> String(bytes, absStart, strLen, Charsets.UTF_8)
            }.trim().replace("\u0000", "")

            if (decoded.isBlank()) continue
            val priority = when {
                nameId == 16 && platformId == 3 -> 1
                nameId == 1 && platformId == 3 -> 2
                nameId == 16 -> 3
                nameId == 1 && platformId == 1 -> 4
                else -> 5
            }
            candidates += Candidate(priority, decoded)
        }

        return candidates.minByOrNull { it.priority }?.value
    }
}
