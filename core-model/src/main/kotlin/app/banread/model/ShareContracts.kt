package app.banread.model

import kotlinx.serialization.Serializable

@Serializable
data class SharedCompanionArtifact(
    val scopeKey: String,
    val toolId: String,
    val promptVersion: Int,
    val chapterContentSha256: String = "",
    val artifact: AuditedArtifact,
    val isPinned: Boolean = false,
    val createdAtMs: Long = 0L,
)

@Serializable
data class SharedQaTurn(
    val threadId: String,
    val scopeKey: String,
    val turnIndex: Int,
    val role: String,
    val content: String,
    val createdAtMs: Long = 0L,
)

@Serializable
data class SharedCompanionPayload(
    val artifacts: List<SharedCompanionArtifact> = emptyList(),
    val qaTurns: List<SharedQaTurn> = emptyList(),
)

data class ShareCompanionMergeResult(
    val importedArtifacts: Int,
    val importedQaTurns: Int,
)
