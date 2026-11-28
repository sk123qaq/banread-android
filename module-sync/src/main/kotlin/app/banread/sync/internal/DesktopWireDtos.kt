package app.banread.sync.internal

import app.banread.model.DesktopBookSummary
import app.banread.model.OutboxTicket
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class HealthResponseDto(
    val ok: Boolean = false,
    val service: String = "",
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
)

@Serializable
internal data class CatalogResponseDto(
    val books: List<DesktopBookSummary> = emptyList(),
    val total: Int = 0,
)

@Serializable
internal data class OutboxResponseDto(
    val tickets: List<OutboxTicket> = emptyList(),
)

@Serializable
internal data class OutboxAckRequestDto(
    @SerialName("ticket_ids")
    val ticketIds: List<String>,
)

@Serializable
internal data class DesktopProgressShardDto(
    val uid: String,
    @SerialName("device_id")
    val deviceId: String,
    val ordinal: Int,
    @SerialName("block_id")
    val blockId: String,
    @SerialName("char_offset")
    val charOffset: Int = 0,
    @SerialName("high_water_mark_ordinal")
    val highWaterMarkOrdinal: Int,
    @SerialName("updated_at_ms")
    val updatedAtMs: Long,
)

@Serializable
internal data class MergedProgressResponseDto(
    val uid: String = "",
    @SerialName("merged_high_water_mark_ordinal")
    val mergedHighWaterMarkOrdinal: Int = 0,
    val shards: List<DesktopProgressShardDto> = emptyList(),
)

@Serializable
internal data class CrossSearchRequestDto(
    val uid: String,
    val question: String,
    @SerialName("exclude_books")
    val excludeBooks: List<String>,
    @SerialName("source_text")
    val sourceText: String = "",
    val threshold: Double = 0.45,
)

@Serializable
internal data class CrossSearchHitWireDto(
    val uid: String = "",
    val title: String = "",
    val type: String = "",
    val relevance: Double = 0.0,
    @SerialName("best_path")
    val bestPath: String = "",
    @SerialName("best_line")
    val bestLine: Int = 0,
    val snippet: String = "",
)

@Serializable
internal data class CrossSearchResponseDto(
    val ok: Boolean = false,
    val hits: List<CrossSearchHitWireDto> = emptyList(),
    val error: String? = null,
)

@Serializable
internal data class GatewayErrorDto(
    val ok: Boolean = false,
    val error: String = "",
)
