package app.banread.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val WORK_ID_REGEX = Regex("^[A-Za-z0-9_-]+$")
private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")
private val BLOCK_ID_REGEX = Regex("^B\\d+$")

@Serializable
@JvmInline
value class WorkId(val raw: String) {
    init {
        require(WORK_ID_REGEX.matches(raw)) { "Invalid WorkId: $raw" }
    }
}

@Serializable
@JvmInline
value class BookSourceHash(val sha256Hex: String) {
    init {
        require(SHA256_HEX_REGEX.matches(sha256Hex)) { "Invalid BookSourceHash (expected 64-char SHA-256 hex): $sha256Hex" }
    }
}

@Serializable
@JvmInline
value class ChapterContentHash(val sha256Hex: String) {
    init {
        require(SHA256_HEX_REGEX.matches(sha256Hex)) {
            "Invalid ChapterContentHash (expected 64-char SHA-256 hex): $sha256Hex"
        }
    }
}

@Serializable
@JvmInline
value class ChapterOrdinal(val value: Int) : Comparable<ChapterOrdinal> {
    init {
        require(value >= 1) { "ChapterOrdinal must be >= 1, got $value" }
    }

    fun twoDigit(): String = value.toString().padStart(2, '0')

    override fun compareTo(other: ChapterOrdinal): Int = value.compareTo(other.value)
}

@Serializable
@JvmInline
value class BlockId(val value: String) {
    init {
        require(isValid(value)) { "Invalid BlockId: $value" }
    }

    companion object {
        fun isValid(raw: String): Boolean = BLOCK_ID_REGEX.matches(raw)

        fun validate(raw: String): BlockId = BlockId(raw)
    }
}

@Serializable
@JvmInline
value class DeviceId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "DeviceId must not be blank" }
    }
}

@Serializable
@JvmInline
value class ReadProfileId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "ReadProfileId must not be blank" }
    }
}

@Serializable
@JvmInline
value class ToolId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "ToolId must not be blank" }
    }
}

@Serializable
enum class ChapterRole {
    @SerialName("body")
    BODY,

    @SerialName("notes")
    NOTES,

    @SerialName("front_back")
    FRONT_BACK,
}

@Serializable
enum class ReadingSurfaceMode {
    @SerialName("reflowable")
    REFLOWABLE,

    @SerialName("pdf_fixed")
    PDF_FIXED,
}

@Serializable
enum class Provenance(val wireValue: String) {
    @SerialName("原文")
    SOURCE_TEXT("原文"),

    @SerialName("补充")
    SUPPLEMENT("补充"),
}

@Serializable
data class TextQuote(
    val prefix: String,
    val exact: String,
    val suffix: String,
)

@Serializable
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

@Serializable
sealed interface ReadingAnchor {
    val ordinal: ChapterOrdinal

    @Serializable
    @SerialName("reflow_cfi")
    data class ReflowCfi(
        override val ordinal: ChapterOrdinal,
        val href: String,
        val progression: Float,
        val totalProgression: Float,
        val cfi: String,
        val textQuote: TextQuote,
        val blockId: BlockId? = null,
    ) : ReadingAnchor

    @Serializable
    @SerialName("block_offset")
    data class BlockOffset(
        override val ordinal: ChapterOrdinal,
        val blockId: BlockId,
        val charStart: Int,
        val charEnd: Int,
        val snippet: String,
    ) : ReadingAnchor

    @Serializable
    @SerialName("pdf_page")
    data class PdfPage(
        override val ordinal: ChapterOrdinal,
        val pageIndex: Int,
        val normalizedBoxes: List<NormalizedRect>,
    ) : ReadingAnchor
}

sealed interface AppFailure {
    data class UnsupportedDrmOrFormat(val reason: String) : AppFailure
    data class DesktopConnectionRequired(val featureName: String) : AppFailure
    data class ProviderAuthOrQuota(val providerId: String, val httpCode: Int?, val message: String) : AppFailure
    data class CapacityPreflightBlocked(val estimatedTokens: Long, val reason: String) : AppFailure
    data class ScannedPdfNeedsOcr(val workId: WorkId) : AppFailure
    data class NetworkOrSyncConflict(val message: String) : AppFailure
}

class AppFailureException(
    val failure: AppFailure,
    override val message: String = failure.toString(),
) : RuntimeException(message)
