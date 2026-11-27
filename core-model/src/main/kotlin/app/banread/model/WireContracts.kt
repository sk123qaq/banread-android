package app.banread.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BcBookManifest(
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
    val uid: WorkId,
    val title: String,
    val author: String = "",
    val subjects: List<String> = emptyList(),
    @SerialName("read_type")
    val readType: ReadProfileId,
    @SerialName("sub_kind")
    val subKind: String = "",
    @SerialName("book_source_sha256")
    val bookSourceSha256: BookSourceHash,
    @SerialName("cloth_color")
    val clothColor: String,
    @SerialName("cover_rel")
    val coverRel: String? = null,
    @SerialName("block_version")
    val blockVersion: Int = 2,
    @SerialName("canonical_version")
    val canonicalVersion: Int = 1,
    @SerialName("logical_chapters")
    val logicalChapters: List<LogicalChapterEntry>,
)

@Serializable
data class LogicalChapterEntry(
    val ordinal: ChapterOrdinal,
    val n: String,
    val title: String,
    @SerialName("chapter_role")
    val chapterRole: ChapterRole,
    val merged: Boolean = false,
    @SerialName("source_files")
    val sourceFiles: List<String>,
    @SerialName("chapter_content_sha256")
    val chapterContentSha256: ChapterContentHash,
    val chars: Int,
)

@Serializable
data class FootnoteLink(
    val id: String,
    val workId: WorkId,
    val sourceChapterOrdinal: ChapterOrdinal,
    val sourceBlockId: BlockId,
    val marker: String,
    val targetChapterOrdinal: ChapterOrdinal,
    val targetBlockId: BlockId,
    val previewText: String = "",
)

@Serializable
data class DeviceProgressShard(
    @SerialName("work_id")
    val workId: WorkId,
    @SerialName("device_id")
    val deviceId: DeviceId,
    val cursor: ReadingAnchor,
    val watermark: ChapterOrdinal,
    @SerialName("updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

@Serializable
data class NoteLogEntry(
    @SerialName("note_id")
    val noteId: String,
    @SerialName("work_id")
    val workId: WorkId,
    @SerialName("device_id")
    val deviceId: DeviceId,
    val anchor: ReadingAnchor,
    @SerialName("color_token")
    val colorToken: String,
    val comment: String,
    @SerialName("updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
    @SerialName("deleted_at_epoch_ms")
    val deletedAtEpochMs: Long? = null,
)

@Serializable
data class OutboxTicket(
    @SerialName("ticket_id")
    val ticketId: String,
    val uid: WorkId,
    @SerialName("include_cache")
    val includeCache: Boolean = true,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
data class DesktopBookSummary(
    val uid: WorkId,
    val title: String,
    val author: String = "",
    val subjects: List<String> = emptyList(),
    @SerialName("read_type")
    val readType: ReadProfileId,
    @SerialName("sub_kind")
    val subKind: String = "",
    @SerialName("cloth_color")
    val clothColor: String,
    @SerialName("logical_chapter_count")
    val logicalChapterCount: Int,
    @SerialName("has_companion_cache")
    val hasCompanionCache: Boolean,
    @SerialName("canonical_version")
    val canonicalVersion: Int,
)

@Serializable
data class CrossBookHit(
    val uid: WorkId,
    val title: String,
    val type: String = "",
    val relevance: Double,
    @SerialName("best_path")
    val bestPath: String,
    @SerialName("best_line")
    val bestLine: Int,
    val snippet: String,
)
