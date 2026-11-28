package app.banread.companion.internal

import app.banread.model.BlockId
import app.banread.model.BookBlock
import app.banread.model.BookSlice
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterSlice
import app.banread.model.FootnoteLink
import app.banread.model.LibraryVault
import app.banread.model.ReadingAnchor
import app.banread.model.ToolScope
import app.banread.model.WorkId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.min

internal data class BuiltCompanionContext(
    val workTitle: String,
    val chapterTitle: String,
    val chapterContentSha256: String,
    val scopeKind: String, // "selection" | "chapter" | "full_book"
    val selectedText: String,
    val contextText: String,
    val blockIds: List<BlockId>,
    val validBlockIds: Set<BlockId>,
    val fallbackBlockIds: List<BlockId>,
    val coveredBlocks: Int,
    val sampled: Boolean,
)

internal sealed interface ContextBuildOutcome {
    data class Ready(val context: BuiltCompanionContext) : ContextBuildOutcome
    data class Unavailable(val reason: String) : ContextBuildOutcome
}

internal class ContextBuilder(
    private val libraryVault: LibraryVault,
    private val dao: CompanionDao,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun build(
        workId: WorkId,
        scope: ToolScope,
    ): ContextBuildOutcome {
        val outline = libraryVault.getWorkOutline(workId)
        val slice: BookSlice = libraryVault.loadBookInputs(workId)

        return when (scope) {
            is ToolScope.Selection -> buildSelectionContext(outline.title, slice, scope)
            is ToolScope.Chapter -> buildChapterContext(workId, outline.title, slice, scope.ordinal)
            ToolScope.FullBook -> buildFullBookContext(workId, outline.title, slice)
        }
    }

    private fun buildSelectionContext(
        workTitle: String,
        slice: BookSlice,
        scope: ToolScope.Selection,
    ): ContextBuildOutcome {
        val ordinal = scope.anchor.ordinal
        val ch = slice.chapters.firstOrNull { it.ordinal == ordinal }
            ?: return ContextBuildOutcome.Unavailable("Chapter ${ordinal.value} is unavailable")

        val cleanedBlocks = cleanBlocks(ch.blocks)
        val anchorBid = when (val a = scope.anchor) {
            is ReadingAnchor.ReflowCfi -> a.blockId
            is ReadingAnchor.BlockOffset -> a.blockId
            is ReadingAnchor.PdfPage -> null
        }

        val indices = if (anchorBid != null) {
            val idx = cleanedBlocks.indexOfFirst { it.blockId == anchorBid }
            if (idx >= 0) listOf(idx) else emptyList()
        } else {
            val needle = cleanForModel(scope.selectedText).take(24)
            if (needle.isNotBlank()) {
                val idx = cleanedBlocks.indexOfFirst { needle in it.text }
                if (idx >= 0) listOf(idx) else emptyList()
            } else {
                emptyList()
            }
        }

        val sample = if (indices.isEmpty()) {
            cleanedBlocks.take(4)
        } else {
            val lo = maxOf(0, indices.min() - 2)
            val hi = minOf(cleanedBlocks.size, indices.max() + 3)
            cleanedBlocks.subList(lo, hi)
        }

        val contextBuilder = StringBuilder()
        for (blk in sample) {
            val entry = "[${blk.blockId.value}] ${blk.text.take(240)}"
            if (contextBuilder.length + entry.length + 1 > SELECTION_CONTEXT_MAX_CHARS) {
                if (contextBuilder.isEmpty()) {
                    contextBuilder.append(entry.take(SELECTION_CONTEXT_MAX_CHARS))
                }
                break
            }
            if (contextBuilder.isNotEmpty()) contextBuilder.append('\n')
            contextBuilder.append(entry)
        }

        val cleanSelected = cleanForModel(scope.selectedText).ifBlank {
            indices.firstOrNull()?.let { cleanedBlocks[it].text }
                ?: cleanedBlocks.firstOrNull()?.text.orEmpty()
        }
        val selectedIds = listOfNotNull(anchorBid ?: indices.firstOrNull()?.let { cleanedBlocks[it].blockId })
        val validIds = ch.blocks.map { it.blockId }.toSet()
        val fallbackIds = selectedIds.ifEmpty { listOfNotNull(ch.blocks.firstOrNull()?.blockId) }

        return ContextBuildOutcome.Ready(
            BuiltCompanionContext(
                workTitle = workTitle,
                chapterTitle = ch.title,
                chapterContentSha256 = ch.contentHash.sha256Hex,
                scopeKind = "selection",
                selectedText = cleanSelected,
                contextText = contextBuilder.toString(),
                blockIds = selectedIds,
                validBlockIds = validIds,
                fallbackBlockIds = fallbackIds,
                coveredBlocks = selectedIds.size,
                sampled = false,
            ),
        )
    }

    private suspend fun buildChapterContext(
        workId: WorkId,
        workTitle: String,
        slice: BookSlice,
        ordinal: ChapterOrdinal,
    ): ContextBuildOutcome {
        val ch = slice.chapters.firstOrNull { it.ordinal == ordinal }
            ?: return ContextBuildOutcome.Unavailable("Chapter ${ordinal.value} is unavailable")

        val fnEntries = resolveFootnotes(
            workId = workId,
            footnotes = slice.footnotes.filter { it.sourceChapterOrdinal == ordinal },
        )
        val (contextText, covered, sampled) = formatChapterBodyAndNotes(
            blocks = ch.blocks,
            footnotes = fnEntries,
            budget = CHAPTER_CHAR_BUDGET,
        )
        val validIds = ch.blocks.map { it.blockId }.toSet()
        val fallbackIds = listOfNotNull(ch.blocks.firstOrNull()?.blockId)

        return ContextBuildOutcome.Ready(
            BuiltCompanionContext(
                workTitle = workTitle,
                chapterTitle = ch.title,
                chapterContentSha256 = ch.contentHash.sha256Hex,
                scopeKind = "chapter",
                selectedText = "",
                contextText = contextText,
                blockIds = emptyList(),
                validBlockIds = validIds,
                fallbackBlockIds = fallbackIds,
                coveredBlocks = covered,
                sampled = sampled,
            ),
        )
    }

    private suspend fun buildFullBookContext(
        workId: WorkId,
        workTitle: String,
        slice: BookSlice,
    ): ContextBuildOutcome {
        val chapters = slice.chapters
        if (chapters.isEmpty()) {
            return ContextBuildOutcome.Unavailable("No body chapters available")
        }

        val recentCount = minOf(3, chapters.size)
        val earlier = if (chapters.size > recentCount) chapters.dropLast(recentCount) else emptyList()
        val recent = chapters.takeLast(recentCount)

        val parts = mutableListOf<String>()
        var usedChars = 0

        if (earlier.isNotEmpty()) {
            parts += "【前章 L2 精要摘要】"
            val digestBudget = minOf(8_000, CHAPTER_CHAR_BUDGET / 3)
            for (ch in earlier) {
                val digest = getOrBuildChapterDigest(workId, ch)
                val line = "第${ch.ordinal.value}章《${ch.title}》：${digest.digestText}"
                if (usedChars + line.length + 1 > digestBudget) break
                parts += line
                usedChars += line.length + 1
            }
        }

        val remainingBudget = maxOf(4_000, CHAPTER_CHAR_BUDGET - usedChars)
        val perRecentBudget = maxOf(1_500, remainingBudget / maxOf(1, recent.size))

        val allNotes = resolveFootnotes(
            workId = workId,
            footnotes = slice.footnotes,
        )
        val notesByChapter = allNotes.groupBy { it.sourceOrdinal }

        var coveredBlocks = 0
        var anySampled = false
        for (ch in recent) {
            val chNotes = notesByChapter[ch.ordinal].orEmpty()
            val (chText, chCovered, chSampled) = formatChapterBodyAndNotes(
                blocks = ch.blocks,
                footnotes = chNotes,
                budget = perRecentBudget,
            )
            if (chText.isNotBlank()) {
                parts += "\n=== 第${ch.ordinal.value}章 ${ch.title} ==="
                parts += chText
                coveredBlocks += chCovered
                if (chSampled) anySampled = true
            }
        }

        val combinedHash = chapters.joinToString(":") { "${it.ordinal.value}:${it.contentHash.sha256Hex.take(12)}" }
        val validIds = chapters.flatMap { ch -> ch.blocks.map { it.blockId } }.toSet()
        val fallbackIds = listOfNotNull(recent.lastOrNull()?.blocks?.firstOrNull()?.blockId)

        return ContextBuildOutcome.Ready(
            BuiltCompanionContext(
                workTitle = workTitle,
                chapterTitle = "全书共 ${chapters.size} 章",
                chapterContentSha256 = combinedHash,
                scopeKind = "full_book",
                selectedText = "",
                contextText = parts.joinToString("\n"),
                blockIds = emptyList(),
                validBlockIds = validIds,
                fallbackBlockIds = fallbackIds,
                coveredBlocks = coveredBlocks,
                sampled = anySampled || earlier.isNotEmpty(),
            ),
        )
    }

    private suspend fun getOrBuildChapterDigest(workId: WorkId, ch: ChapterSlice): ChapterDigestEntity {
        val existing = dao.getChapterDigest(workId.raw, ch.ordinal.value)
        if (existing != null && existing.chapterContentSha256 == ch.contentHash.sha256Hex) {
            return existing
        }
        val cleaned = cleanBlocks(ch.blocks)
        val picked = when {
            cleaned.isEmpty() -> emptyList()
            cleaned.size <= 3 -> cleaned
            else -> listOf(cleaned.first(), cleaned[cleaned.size / 2], cleaned.last())
        }
        val digestText = picked.joinToString(" / ") { "[${it.blockId.value}] ${it.text.take(90)}" }.take(320)
        val anchorsJson = json.encodeToString(
            ListSerializer(String.serializer()),
            picked.map { it.blockId.value },
        )
        val entity = ChapterDigestEntity(
            workId = workId.raw,
            ordinal = ch.ordinal.value,
            chapterTitle = ch.title,
            chapterContentSha256 = ch.contentHash.sha256Hex,
            digestText = digestText.ifBlank { ch.title },
            anchorBlockIdsJson = anchorsJson,
            updatedAtMs = System.currentTimeMillis(),
        )
        dao.upsertChapterDigest(entity)
        return entity
    }

    private data class ResolvedNote(
        val sourceOrdinal: ChapterOrdinal,
        val label: String,
        val text: String,
    )

    private suspend fun resolveFootnotes(
        workId: WorkId,
        footnotes: List<FootnoteLink>,
    ): List<ResolvedNote> {
        val result = mutableListOf<ResolvedNote>()
        val seen = hashSetOf<String>()
        for (fn in footnotes) {
            val detail = runCatching {
                libraryVault.resolveFootnote(workId, fn.sourceChapterOrdinal, fn.marker)
            }.getOrNull()
            val fullText = cleanForModel(detail?.fullNoteText ?: fn.previewText)
            if (fullText.isBlank() || isTranslatorOrBibNote(fullText)) continue
            val dedupKey = "${fn.sourceChapterOrdinal.value}:${fn.targetChapterOrdinal.value}:${fn.targetBlockId.value}:${fn.marker}"
            if (!seen.add(dedupKey)) continue
            val markerClean = fn.marker.trim()
            val label = if (markerClean.startsWith("注") || markerClean.startsWith("註")) {
                "${fn.sourceBlockId.value}·$markerClean"
            } else if (markerClean.isNotEmpty()) {
                "${fn.sourceBlockId.value}·注$markerClean"
            } else {
                "${fn.sourceBlockId.value}·跨章注"
            }
            result += ResolvedNote(fn.sourceChapterOrdinal, label, fullText)
        }
        return result
    }

    private data class CleanSemanticBlock(
        val blockId: BlockId,
        val isHeading: Boolean,
        val text: String,
    )

    private fun cleanBlocks(blocks: List<BookBlock>): List<CleanSemanticBlock> {
        val out = ArrayList<CleanSemanticBlock>(blocks.size)
        for (blk in blocks) {
            val cleaned = cleanForModel(blk.text)
            if (cleaned.isEmpty() || isPageBoilerplate(cleaned)) continue
            out += CleanSemanticBlock(
                blockId = blk.blockId,
                isHeading = looksLikeHeading(cleaned),
                text = cleaned,
            )
        }
        return out
    }

    private fun formatChapterBodyAndNotes(
        blocks: List<BookBlock>,
        footnotes: List<ResolvedNote>,
        budget: Int,
    ): Triple<String, Int, Boolean> {
        val bodySemantic = cleanBlocks(blocks)
        if (bodySemantic.isEmpty()) return Triple("", 0, false)

        val bodyChars = bodySemantic.sumOf { it.text.length }
        val fnBudget = minOf((budget * 0.18).toInt(), maxOf(400, (bodyChars * 0.18).toInt()))

        fun appendFootnotes(outLines: MutableList<String>, cap: Int) {
            if (footnotes.isEmpty() || cap <= 0) return
            val fnLines = mutableListOf<String>()
            var fnUsed = 0
            for (note in footnotes) {
                var entry = "[${note.label}] ${note.text.take(500)}"
                if (fnUsed + entry.length > cap && fnLines.isNotEmpty()) break
                if (fnUsed + entry.length > cap) {
                    entry = entry.take(cap)
                }
                fnLines += entry
                fnUsed += entry.length + 1
                if (fnUsed >= cap) break
            }
            if (fnLines.isNotEmpty()) {
                outLines += ""
                outLines += "【本章自带脚注（属原文出处，不可作为独立论证节点）】"
                outLines += "（注：以下脚注属于 [原文] 而非 [补充]，仅用于出处与概念核对，不可单独画作论证树节点。）"
                outLines += fnLines
            }
        }

        if (bodyChars <= budget) {
            val lines = mutableListOf<String>()
            // Collapse consecutive short lines (avg_len < 25) while preserving [Bxx-Byy] ranges
            var pos = 0
            while (pos < bodySemantic.size) {
                val cur = bodySemantic[pos]
                if (!cur.isHeading && cur.text.length <= 40) {
                    val run = mutableListOf(pos)
                    var nxt = pos + 1
                    while (nxt < bodySemantic.size && !bodySemantic[nxt].isHeading && bodySemantic[nxt].text.length <= 40) {
                        run += nxt
                        nxt++
                    }
                    val avgRun = run.sumOf { bodySemantic[it].text.length }.toDouble() / run.size
                    if (run.size >= 3 && avgRun < 25.0) {
                        val joined = run.joinToString(" ") { bodySemantic[it].text }
                        lines += "[${bodySemantic[run.first()].blockId.value}–${bodySemantic[run.last()].blockId.value}] ${snippet(joined)}"
                        pos = nxt
                        continue
                    }
                }
                lines += "[${cur.blockId.value}] ${cur.text}"
                pos++
            }
            appendFootnotes(lines, fnBudget)
            return Triple(lines.joinToString("\n"), bodySemantic.size, false)
        }

        val totalLen = bodySemantic.size
        val avgLen = bodyChars.toDouble() / maxOf(1, totalLen)
        val openMax = if (avgLen >= 25.0) 12 else 48
        val windowMaxBlocks = if (avgLen >= 25.0) 12 else 240

        val parts = mutableListOf<String>()
        var usedChars = 0

        // Headings outline <= 1500 chars
        val headings = bodySemantic.filter { it.isHeading }
        if (headings.isNotEmpty()) {
            parts += "【全章小标题脉络】"
            var outlineUsed = 0
            for (h in headings) {
                val line = "- [${h.blockId.value}] ${h.text}"
                if (outlineUsed + line.length + 1 > 1500) break
                parts += line
                outlineUsed += line.length + 1
            }
            usedChars += outlineUsed
        }

        val pickedIdx = linkedSetOf<Int>()
        bodySemantic.forEachIndexed { idx, b -> if (b.isHeading) pickedIdx += idx }

        fun windowChars(indices: Set<Int>): Int =
            indices.sumOf { minOf(4000, bodySemantic[it].text.length) + 8 }

        // 1) Head window (at least 3 blocks, max 2200 chars)
        var openChars = 0
        var openEnd = 0
        for (idx in 0 until minOf(totalLen, openMax)) {
            val cLen = minOf(4000, bodySemantic[idx].text.length) + 8
            if (idx >= 3 && openChars + cLen > 2200) break
            pickedIdx += idx
            openChars += cLen
            openEnd = idx + 1
        }

        // 2) Tail window (at least 3 blocks, max 2200 chars)
        val closeIndices = mutableListOf<Int>()
        var closeChars = 0
        var idx = totalLen - 1
        while (idx >= openEnd && closeIndices.size < openMax) {
            val cLen = minOf(4000, bodySemantic[idx].text.length) + 8
            if (closeIndices.size >= 3 && closeChars + cLen > 2200) break
            closeIndices += idx
            closeChars += cLen
            idx--
        }
        pickedIdx.addAll(closeIndices)
        val closeStart = closeIndices.minOrNull() ?: totalLen

        // 3) Mid windows (up to 10)
        val reservedChars = usedChars + windowChars(pickedIdx) + if (footnotes.isNotEmpty()) fnBudget else 0
        val midBudget = maxOf(4000, budget - reservedChars)
        if (closeStart > openEnd) {
            val span = closeStart - openEnd
            val numWindows = minOf(10, maxOf(2, span / 12))
            val perWinBudget = maxOf(900, midBudget / maxOf(1, numWindows))
            for (wIdx in 0 until numWindows) {
                val center = openEnd + ((wIdx + 0.5) * span / numWindows).toInt()
                if (center !in openEnd until closeStart) continue
                val winIndices = linkedSetOf(center)
                var winChars = minOf(4000, bodySemantic[center].text.length) + 8
                var step = 1
                while (winChars < perWinBudget && winIndices.size < windowMaxBlocks) {
                    var added = false
                    for (cand in intArrayOf(center - step, center + step)) {
                        if (cand in openEnd until closeStart && cand !in pickedIdx && cand !in winIndices) {
                            val cLen = minOf(4000, bodySemantic[cand].text.length) + 8
                            if (winChars + cLen <= perWinBudget || winIndices.size < 3) {
                                winIndices += cand
                                winChars += cLen
                                added = true
                            }
                        }
                    }
                    if (!added) break
                    step++
                }
                pickedIdx.addAll(winIndices)
            }
        }

        parts += "【全章连续场景窗口节选（保留首尾、全部小标题及中段连续对话/叙事）】"
        val ordered = pickedIdx.sorted()
        var prevIdx: Int? = null
        var pos = 0
        var gapMarkers = 0
        while (pos < ordered.size) {
            val curIdx = ordered[pos]
            if (prevIdx != null && curIdx > prevIdx + 1) {
                val gap = curIdx - prevIdx - 1
                if (gapMarkers < 60) {
                    parts += "……（略去 ${bodySemantic[prevIdx + 1].blockId.value}–${bodySemantic[curIdx - 1].blockId.value} 共 $gap 段）……"
                    gapMarkers++
                }
            }
            val cur = bodySemantic[curIdx]
            if (!cur.isHeading && cur.text.length <= 40) {
                val run = mutableListOf(curIdx)
                var nxt = pos + 1
                while (nxt < ordered.size && ordered[nxt] == run.last() + 1) {
                    val cand = ordered[nxt]
                    if (bodySemantic[cand].isHeading || bodySemantic[cand].text.length > 40) break
                    run += cand
                    nxt++
                }
                if (run.size >= 3 && run.sumOf { bodySemantic[it].text.length }.toDouble() / run.size < 25.0) {
                    val joined = run.joinToString(" ") { bodySemantic[it].text }
                    parts += "[${bodySemantic[run.first()].blockId.value}–${bodySemantic[run.last()].blockId.value}] ${snippet(joined)}"
                    prevIdx = run.last()
                    pos = nxt
                    continue
                }
            }
            parts += "[${cur.blockId.value}] ${snippet(cur.text)}"
            prevIdx = curIdx
            pos++
        }

        appendFootnotes(parts, min(fnBudget, (budget * 0.15).toInt()))
        var text = parts.joinToString("\n")
        if (text.length > (budget * 1.15).toInt()) {
            text = parts.filterNot { it.startsWith("……（略去") }.joinToString("\n")
        }
        return Triple(text, bodySemantic.size, true)
    }

    private fun snippet(text: String, cap: Int = 4000): String =
        if (text.length > cap) text.take(cap) + "…" else text

    companion object {
        private const val SELECTION_CONTEXT_MAX_CHARS = 1_800
        private const val CHAPTER_CHAR_BUDGET = 24_000

        private val puaRegex = Regex("""[\uE000-\uF8FF]""")
        private val controlRegex = Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]""")
        private val multiSpaceRegex = Regex("""[ \t\u00A0]+""")
        private val pageNumRegex = Regex("""^\s*[-–—]?\s*\d{1,4}\s*[-–—]?\s*$""")
        private val pageLabelRegex = Regex("""^\s*第\s*\d{1,4}\s*页\s*$""")
        private val headingRegex = Regex(
            """^(?:#{1,6}\s+|第[零一二三四五六七八九十百千万0-9]+[章节回部卷篇节]\b|(?:Chapter|Section|Part|Act|Scene)\s+\d+)""",
            RegexOption.IGNORE_CASE,
        )

        private val translatorEndRegex = Regex("""(?:——|—|--)\s*(?:译者|编者|校者|译注|编按)\s*$""")
        private val translatorHeadRegex = Regex("""^\s*(?:译者?注|编者?注|校者?注|编按|译注)\s*[:：]""")
        private val translatorMidRegex = Regex("""(?:中译文[采参]?自|译文稍?有改动|编按\s*[:：])""")
        private val bibRefHeadRegex = Regex(
            """^\s*(?:参见|另见|见|这一观点见|关于.{1,36}?的说明[，,]\s*见|对.{1,36}?的(?:一个)?(?:当代)?例子[，,]\s*见)\b""",
        )
        private val bibPureCitRegex = Regex(
            """^\s*[A-Z][A-Za-zÀ-ÿ\s.,'":;()&—–-]{2,120}(?:pp?\.\s*\d+|vol\.\s*\d+)\s*\.?\s*$""",
        )

        fun cleanForModel(text: String): String {
            var t = puaRegex.replace(text, "")
            t = controlRegex.replace(t, "").replace("\uFFFD", "")
            return multiSpaceRegex.replace(t, " ").trim()
        }

        fun isPageBoilerplate(text: String): Boolean =
            pageNumRegex.matches(text) || pageLabelRegex.matches(text)

        fun looksLikeHeading(text: String): Boolean =
            text.startsWith("#") || (text.length <= 45 && headingRegex.containsMatchIn(text))

        fun isTranslatorOrBibNote(text: String): Boolean {
            val txt = text.trim()
            if (txt.isEmpty()) return true
            if (translatorEndRegex.containsMatchIn(txt) ||
                translatorHeadRegex.containsMatchIn(txt) ||
                translatorMidRegex.containsMatchIn(txt)
            ) {
                return true
            }
            return bibRefHeadRegex.containsMatchIn(txt) || bibPureCitRegex.matches(txt)
        }
    }
}
