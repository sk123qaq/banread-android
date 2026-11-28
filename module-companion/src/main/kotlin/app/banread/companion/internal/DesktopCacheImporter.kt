package app.banread.companion.internal

import app.banread.model.AuditedArtifact
import app.banread.model.BlockId
import app.banread.model.ChapterOrdinal
import app.banread.model.LibraryVault
import app.banread.model.WorkId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

internal class DesktopCacheImporter(
    private val libraryVault: LibraryVault,
    private val dao: CompanionDao,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    suspend fun import(workId: WorkId, bundleCacheDir: String) {
        val root = File(bundleCacheDir)
        if (!root.isDirectory) return

        val bookSlice = runCatching {
            libraryVault.loadBookInputs(workId)
        }.getOrNull()
        val chaptersByOrd = bookSlice?.chapters?.associateBy { it.ordinal.value }.orEmpty()

        val chDirs = root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("ch_") }
            ?.sortedBy { it.name }
            .orEmpty()

        for (chDir in chDirs) {
            val ordNum = chDir.name.removePrefix("ch_").toIntOrNull()?.takeIf { it >= 1 } ?: continue
            val ordinal = ChapterOrdinal(ordNum)
            val chSlice = chaptersByOrd[ordNum]
            val validBlockIds: Set<BlockId> = chSlice?.blocks?.map { it.blockId }?.toSet() ?: emptySet()
            val fallbackBlocks: List<BlockId> = listOfNotNull(chSlice?.blocks?.firstOrNull()?.blockId)
            val chapterSha = chSlice?.contentHash?.sha256Hex.orEmpty()
            val scopeKey = "chapter:${ordinal.twoDigit()}"

            // 1. chapter_map.json
            val mapFile = File(chDir, "chapter_map.json")
            if (mapFile.isFile) {
                importChapterMap(workId, scopeKey, chapterSha, mapFile, validBlockIds, fallbackBlocks)
            }

            // 2. chapter_tools/<readType>_<action>_<bp>.json
            val toolsDir = File(chDir, "chapter_tools")
            if (toolsDir.isDirectory) {
                toolsDir.listFiles()
                    ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                    ?.sortedBy { it.name }
                    ?.forEach { toolFile ->
                        importChapterTool(workId, scopeKey, chapterSha, toolFile, validBlockIds, fallbackBlocks)
                    }
            }

            // 3. assist_notes.json (pinned notes, exempt from 200MB LRU)
            val notesFile = File(chDir, "assist_notes.json")
            if (notesFile.isFile) {
                importAssistNotes(workId, ordinal, chapterSha, notesFile, validBlockIds, fallbackBlocks)
            }
        }
    }

    private suspend fun importChapterMap(
        workId: WorkId,
        scopeKey: String,
        chapterSha: String,
        file: File,
        validBlockIds: Set<BlockId>,
        fallbackBlocks: List<BlockId>,
    ) {
        val obj = JsonRepair.parseObject(file.readText(Charsets.UTF_8)) ?: return
        val supported = (obj["supported"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (!supported) return

        val effectiveSha = chapterSha.ifEmpty { obj.str("source_sha256") }
        val artifact = BlueprintAuditor.auditJsonObject(
            parsed = obj,
            rawText = file.readText(Charsets.UTF_8),
            validBlockIds = validBlockIds,
            fallbackBlockIds = fallbackBlocks,
            requestedBlueprint = "chapter_map",
            requestedAction = "chapter_map",
        )
        saveCacheRow(
            workId = workId,
            scopeKey = scopeKey,
            toolId = "chapter_map",
            chapterSha = effectiveSha,
            artifact = artifact,
            tokenCost = 0L,
            isPinned = false,
        )
    }

    private suspend fun importChapterTool(
        workId: WorkId,
        scopeKey: String,
        chapterSha: String,
        file: File,
        validBlockIds: Set<BlockId>,
        fallbackBlocks: List<BlockId>,
    ) {
        val rawText = file.readText(Charsets.UTF_8)
        val doc = JsonRepair.parseObject(rawText) ?: return
        val effectiveSha = chapterSha.ifEmpty { doc.str("source_sha256") }
        val tokenCost = (doc["tokens"] as? JsonPrimitive)?.longOrNull ?: 0L

        val (parsedAction, parsedBp, parsedToolId) = resolveToolIdentity(file.nameWithoutExtension, doc)
        val figureObj = (doc["figure"] as? JsonObject) ?: doc

        val artifact = BlueprintAuditor.auditJsonObject(
            parsed = figureObj,
            rawText = rawText,
            validBlockIds = validBlockIds,
            fallbackBlockIds = fallbackBlocks,
            requestedBlueprint = parsedBp,
            requestedAction = parsedAction,
        )
        saveCacheRow(
            workId = workId,
            scopeKey = scopeKey,
            toolId = parsedToolId,
            chapterSha = effectiveSha,
            artifact = artifact,
            tokenCost = tokenCost,
            isPinned = false,
        )
    }

    private suspend fun importAssistNotes(
        workId: WorkId,
        ordinal: ChapterOrdinal,
        chapterSha: String,
        file: File,
        validBlockIds: Set<BlockId>,
        fallbackBlocks: List<BlockId>,
    ) {
        val doc = JsonRepair.parseObject(file.readText(Charsets.UTF_8)) ?: return
        val effectiveSha = chapterSha.ifEmpty { doc.str("source_sha256") }
        val notes = doc["notes"] as? JsonArray ?: return
        notes.forEachIndexed { idx, el ->
            val note = el as? JsonObject ?: return@forEachIndexed
            val noteId = note.str("id").ifBlank { "note-${idx + 1}" }
            val action = note.str("action").ifBlank { "explain" }
            val title = note.str("title").ifBlank { "旁批笔记" }
            val content = note.str("content")
            val figure = note["figure"] as? JsonObject
            val artifact: AuditedArtifact = if (figure != null) {
                BlueprintAuditor.auditJsonObject(
                    parsed = figure,
                    rawText = figure.toString(),
                    validBlockIds = validBlockIds,
                    fallbackBlockIds = fallbackBlocks,
                    requestedBlueprint = figure.str("blueprint"),
                    requestedAction = action,
                )
            } else if (content.isNotBlank()) {
                AuditedArtifact.MarkdownReadCard(
                    caption = title.take(36),
                    readingNote = "桌面同步钉住旁批",
                    summary = content.take(120),
                    auditLog = listOf("从桌面 assist_notes.json 导入钉住旁批"),
                    markdown = content,
                )
            } else {
                return@forEachIndexed
            }

            saveCacheRow(
                workId = workId,
                scopeKey = "note:${ordinal.twoDigit()}:$noteId",
                toolId = action,
                chapterSha = effectiveSha,
                artifact = artifact,
                tokenCost = 0L,
                isPinned = true,
            )
        }
    }

    private suspend fun saveCacheRow(
        workId: WorkId,
        scopeKey: String,
        toolId: String,
        chapterSha: String,
        artifact: AuditedArtifact,
        tokenCost: Long,
        isPinned: Boolean,
    ) {
        val serialized = json.encodeToString(AuditedArtifact.serializer(), artifact)
        val now = System.currentTimeMillis()
        dao.upsertAndEnforceLru(
            CompanionResultEntity(
                workId = workId.raw,
                scopeKey = scopeKey,
                toolId = toolId,
                promptVersion = PromptCatalog.PROMPT_VERSION,
                chapterContentSha256 = chapterSha,
                artifactJson = serialized,
                tokenCost = tokenCost,
                byteSize = serialized.toByteArray(Charsets.UTF_8).size.toLong(),
                isPinned = isPinned,
                createdAtMs = now,
                lastAccessedAtMs = now,
            ),
        )
    }

    private fun resolveToolIdentity(stem: String, doc: JsonObject): Triple<String, String, String> {
        val docAction = doc.str("action").trim().lowercase()
        val docBp = doc.str("blueprint").trim().lowercase()

        val afterReadType = KNOWN_READ_TYPES.firstOrNull { stem.startsWith("${it}_") }
            ?.let { stem.removePrefix("${it}_") }
            ?: stem.substringAfter('_', stem)

        if (afterReadType.startsWith("diagram_")) {
            val bp = afterReadType.removePrefix("diagram_").ifBlank { docBp }
            return Triple("diagram", bp, bp)
        }

        if (docAction == "diagram" && docBp.isNotEmpty()) {
            return Triple("diagram", docBp, docBp)
        }

        val knownExt = PromptCatalog.EXTENSION_PROMPT_SPECS.keys.firstOrNull { ext ->
            afterReadType == "${ext}_${ext}" || afterReadType == ext || docAction == ext
        }
        if (knownExt != null) {
            val bp = docBp.ifBlank { PromptCatalog.EXTENSION_PROMPT_SPECS.getValue(knownExt).blueprint }
            return Triple(knownExt, bp, knownExt)
        }

        val fallbackId = docBp.ifBlank { docAction }.ifBlank { afterReadType }
        return Triple(docAction.ifBlank { fallbackId }, docBp.ifBlank { fallbackId }, fallbackId)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        private val KNOWN_READ_TYPES = listOf(
            "practical", "literature", "history", "science", "philosophy", "social", "visual",
        )
    }
}
