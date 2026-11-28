package app.banread.library.internal

import app.banread.model.BlockId
import app.banread.model.ChapterRole

internal object RoleAndSplit {
    const val VIRTUAL_SPLIT_TRIGGER_CHARS = 24_000
    const val VIRTUAL_SPLIT_TRIGGER_BLOCKS = 800
    const val VIRTUAL_TARGET_MIN_CHARS = 8_000
    const val VIRTUAL_TARGET_MAX_CHARS = 18_000

    private const val TITLE_PAGE_MAX_CHARS = 120
    private const val BODY_MIN_CHARS = 500
    private const val BODY_STEM_PREFIX = "前置页"
    private const val NON_TOC_FRONT_BACK_BODY_LIMIT = 1_200
    private const val NON_TOC_PRAISE_PREFACE_LIMIT = 6_000
    private const val TOC_BODY_CHECK_MIN_CHARS = 2_000
    private const val TOC_BODY_MAX_CHARS = 15_000

    private val NOTE_CHAPTER_TITLE_RE = Regex(
        """^\s*(?:第\s*[0-9一二三四五六七八九十百千]+\s*[章节回部分]\s*[:：·.\s]*|chapter\s*\d+\s*[:：·.\s]*|附录\s*[0-9A-Za-z一二三四五六七八九十]*\s*[:：·.\s]*|appendix\s*[0-9A-Za-z]*\s*[:：·.\s]*)?(?:(?:原书|全书|本书|本章|本节|各章|分章|末尾|篇末|numbered)\s*)?(?:(?:作者|译者|譯者|编者|編者|校者|校|编|編|译|譯|原)?[注註](?:释|釋)?\s*\d{0,4}|脚注|腳注|尾注|注释|註釋|注解|引注|译注|譯注|备注|備註|注意|(?:版本|校勘)[和与及]?(?:注释|註釋)(?:说明|說明)?|notes|footnotes|endnotes)(?:\s*[（(][^）)]*[）)])?\s*[^\n]{0,16}$""",
        RegexOption.IGNORE_CASE,
    )

    private val BODY_PROTECTED_TITLE_RE = Regex(
        """(?:^第\s*[〇零一二三四五六七八九十百千两0-9]+\s*[章篇部卷回幕场节]|^(?:序幕|楔子|尾声|引子|绪论|导论|引言|前言|后记|结语|chapter\s+\d+|part\s+\d+|act\s+\d+))""",
        RegexOption.IGNORE_CASE,
    )

    private val TOC_TITLE_RE = Regex(
        """^(?:总目录|简目|详细目录|插图目录|图表目录|目\s*录|table\s*of\s*contents|contents|document\s*outline)$""",
        RegexOption.IGNORE_CASE,
    )

    private val PRAISE_OR_PREFACE_TITLE_RE = Regex(
        """^(?:(?:各界|媒体)?赞誉|推荐赞誉|推荐语|译者序|推荐序|代序|序言|praise|endorsements|preface|foreword|translator'?s?\s*(?:note|preface))$""",
        RegexOption.IGNORE_CASE,
    )

    private val FRONT_BACK_TITLE_RE = Regex(
        """^(?:(?:前|后)?折页|勒口|书衣|腰封|奥付|版权(?:页|声明|所有|信息)?|图书在版编目|CIP(?:数据)?|封面|封底|封二|封三|书脊|书名页|扉页|内封|题名页|剧中人物(?:表)?|人物表|序幕人物|登场人物|cover|back\s*cover|title\s*page|half\s*title|copyright|colophon|imprint|cast\s*of\s*characters|dramatis\s*personae)$""",
        RegexOption.IGNORE_CASE,
    )

    private val PLACEHOLDER_FRONT_TITLE_RE = Regex("""^前置页(?:\s*\d+)?$""", RegexOption.IGNORE_CASE)
    private val IMG_REF_RE = Regex("""!\[[^\]]*\]\((?:<([^>]+)>|([^)]+))\)""")
    private val LINK_REF_RE = Regex("""(?<![!\\])\[[^\]]*\]\((?:<([^>]+)>|([^)]+))\)""")
    private val TOC_LINE_RE = Regex(
        """(?:\.{2,}|…{2,}|·{3,}|(?:^|\s|/|第)\d{1,4}(?:\s|$)|\(\d{1,4}\)$|\[\d{1,4}\]$|^(?:第\s*[〇零一二三四五六七八九十百千两0-9]+\s*[章篇部卷回集辑节幕]|chapter|part|section|appendix|preface|foreword|bibliography|index|[A-Z]\.\d+|[一二三四五六七八九十]+、|\d+(?:\.\d+)+))""",
        RegexOption.IGNORE_CASE,
    )
    private val END_PUNCT_RE = Regex("""[。！？]|(?<![A-Za-z0-9])[A-Za-z]{3,}\.(?=\s|$)""")
    private val PROSE_LINE_END_RE = Regex("""[。！？.!?][”’"')\\]]*$""")
    private val PROSE_LINE_TOC_END_RE = Regex("""(\.{3,}|…{2,}|\s\d{1,4}$)""")
    private val DOTS_RE = Regex("""\.{2,}|…{2,}|·{3,}""")
    private val LEADING_NUM_PREFIX_RE = Regex("""^\d+_""")
    private val NON_WORD_RE = Regex("""[\W_]+""")
    private val H1_LINE_RE = Regex("""^#\s+.*$""", RegexOption.MULTILINE)

    fun isNoteChapterTitle(title: String): Boolean =
        title.isNotBlank() && NOTE_CHAPTER_TITLE_RE.matches(title.trim())

    fun classifyChapterRole(
        title: String,
        body: String,
        fileName: String = "",
        bookTitle: String = "",
        isOnlyChapter: Boolean = false,
    ): ChapterRole {
        if (isOnlyChapter) return ChapterRole.BODY
        val cleanTitle = TextSanitizer.cleanInline(title).trim()
        val stem = fileName.substringAfterLast('/').substringBeforeLast('.').trim()
        val stemClean = LEADING_NUM_PREFIX_RE.replace(stem, "").trim()
        val stemNorm = stemClean.replace('_', ' ').trim()

        if (cleanTitle.isNotEmpty() && NOTE_CHAPTER_TITLE_RE.matches(cleanTitle)) return ChapterRole.NOTES
        if (stemClean.isNotEmpty() && NOTE_CHAPTER_TITLE_RE.matches(stemClean)) return ChapterRole.NOTES
        if (cleanTitle in setOf("评注", "注释与评注", "校注") && TextSanitizer.nonSpaceCount(body) < 30_000) {
            return ChapterRole.NOTES
        }

        if (cleanTitle.isNotEmpty() && BODY_PROTECTED_TITLE_RE.containsMatchIn(cleanTitle)) {
            return ChapterRole.BODY
        }

        val bodyNoH1 = H1_LINE_RE.replace(body, "").trim()
        val bodyNoImgs = IMG_REF_RE.replace(bodyNoH1, "").trim()
        val plainChars = TextSanitizer.nonSpaceCount(bodyNoImgs)

        if ((cleanTitle.isNotEmpty() && TOC_TITLE_RE.matches(cleanTitle)) ||
            (stemNorm.isNotEmpty() && TOC_TITLE_RE.matches(stemNorm))
        ) {
            return if (plainChars < TOC_BODY_CHECK_MIN_CHARS) {
                ChapterRole.FRONT_BACK
            } else if (isRealTocBody(bodyNoImgs, plainChars)) {
                ChapterRole.FRONT_BACK
            } else {
                ChapterRole.BODY
            }
        }

        if ((cleanTitle.isNotEmpty() && FRONT_BACK_TITLE_RE.matches(cleanTitle)) ||
            (stemNorm.isNotEmpty() && FRONT_BACK_TITLE_RE.matches(stemNorm))
        ) {
            return if (plainChars >= NON_TOC_FRONT_BACK_BODY_LIMIT) {
                ChapterRole.BODY
            } else {
                ChapterRole.FRONT_BACK
            }
        }

        if ((cleanTitle.isNotEmpty() && PRAISE_OR_PREFACE_TITLE_RE.matches(cleanTitle)) ||
            (stemNorm.isNotEmpty() && PRAISE_OR_PREFACE_TITLE_RE.matches(stemNorm))
        ) {
            return if (plainChars >= NON_TOC_PRAISE_PREFACE_LIMIT) {
                ChapterRole.BODY
            } else {
                ChapterRole.FRONT_BACK
            }
        }

        if (plainChars < 50) {
            val normBt = NON_WORD_RE.replace(bookTitle.lowercase(), "")
            val normCt = NON_WORD_RE.replace(cleanTitle.lowercase(), "")
            if (plainChars == 0 || (normBt.isNotEmpty() && normCt.isNotEmpty() && (normCt in normBt || normBt in normCt))) {
                return ChapterRole.FRONT_BACK
            }
            if (PLACEHOLDER_FRONT_TITLE_RE.matches(cleanTitle) ||
                PLACEHOLDER_FRONT_TITLE_RE.matches(stemNorm) ||
                cleanTitle.startsWith(BODY_STEM_PREFIX) ||
                stemClean.startsWith(BODY_STEM_PREFIX)
            ) {
                return ChapterRole.FRONT_BACK
            }
        }

        return ChapterRole.BODY
    }

    private fun isRealTocBody(bodyNoImgs: String, plainChars: Int): Boolean {
        if (plainChars > TOC_BODY_MAX_CHARS) return false
        val lines = bodyNoImgs.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 3) return false
        val noDots = DOTS_RE.replace(bodyNoImgs, " ")
        val endPunct = END_PUNCT_RE.findAll(noDots).count()
        if (endPunct * 1000.0 / maxOf(plainChars, 1) > 8.0) return false
        val proseChars = lines.sumOf { ln ->
            val len = TextSanitizer.nonSpaceCount(ln)
            if (len >= 50 && PROSE_LINE_END_RE.containsMatchIn(ln) && !PROSE_LINE_TOC_END_RE.containsMatchIn(ln)) len else 0
        }
        if (proseChars >= 400) return false
        val half = lines.subList(lines.size / 2, lines.size)
        val tailCount = maxOf(5, lines.size / 4)
        val tail = lines.subList(maxOf(0, lines.size - tailCount), lines.size)

        fun tocRatio(seq: List<String>): Double {
            val hits = seq.count { ln -> TOC_LINE_RE.containsMatchIn(ln) || LINK_REF_RE.containsMatchIn(ln) }
            return hits.toDouble() / maxOf(seq.size, 1)
        }
        return tocRatio(lines) >= 0.35 && tocRatio(half) >= 0.35 && tocRatio(tail) >= 0.30
    }

    internal data class RawChapterCandidate(
        val n: String,
        val stem: String,
        val fileName: String,
        val title: String,
        val role: ChapterRole,
        val chars: Int,
        val blocks: List<ParsedBlock>,
        val rawMarkdown: String,
        val rawContentBytes: ByteArray,
        val blockIdToHtmlIds: Map<String, Set<String>> = emptyMap(),
    )

    internal data class MergedChapterCandidate(
        val n: String,
        val title: String,
        val role: ChapterRole,
        val merged: Boolean,
        val sourceFiles: List<String>,
        val chars: Int,
        val blocks: List<ParsedBlock>,
        val contentSha256: String,
        val blockIdToHtmlIds: Map<String, Set<String>> = emptyMap(),
    )

    fun mergeTitlePages(candidates: List<RawChapterCandidate>): List<MergedChapterCandidate> {
        val merged = mutableListOf<MergedChapterCandidate>()
        var index = 0
        while (index < candidates.size) {
            val current = candidates[index]
            val following = candidates.getOrNull(index + 1)
            if (current.chars <= TITLE_PAGE_MAX_CHARS &&
                following != null &&
                following.stem.startsWith(BODY_STEM_PREFIX) &&
                following.chars >= BODY_MIN_CHARS
            ) {
                val combinedBlocks = renumberBlocks(current.blocks + following.blocks)
                val mergedHtmlIds = linkedMapOf<String, Set<String>>()
                current.blocks.forEachIndexed { i, blk ->
                    current.blockIdToHtmlIds[blk.id]?.let { mergedHtmlIds["B%02d".format(i + 1)] = it }
                }
                following.blocks.forEachIndexed { j, blk ->
                    following.blockIdToHtmlIds[blk.id]?.let {
                        mergedHtmlIds["B%02d".format(current.blocks.size + j + 1)] = it
                    }
                }
                val combinedBytes = current.rawContentBytes + byteArrayOf(0) + following.rawContentBytes
                merged += MergedChapterCandidate(
                    n = current.n,
                    title = current.title.ifBlank { following.title },
                    role = following.role,
                    merged = true,
                    sourceFiles = listOf(current.fileName, following.fileName),
                    chars = current.chars + following.chars,
                    blocks = combinedBlocks,
                    contentSha256 = TextSanitizer.sha256Hex(combinedBytes),
                    blockIdToHtmlIds = mergedHtmlIds,
                )
                index += 2
                continue
            }
            val renumbered = renumberBlocks(current.blocks)
            val mappedHtmlIds = linkedMapOf<String, Set<String>>()
            current.blocks.forEachIndexed { i, blk ->
                current.blockIdToHtmlIds[blk.id]?.let { mappedHtmlIds["B%02d".format(i + 1)] = it }
            }
            merged += MergedChapterCandidate(
                n = current.n,
                title = current.title,
                role = current.role,
                merged = false,
                sourceFiles = listOf(current.fileName),
                chars = current.chars,
                blocks = renumbered,
                contentSha256 = TextSanitizer.sha256Hex(current.rawContentBytes),
                blockIdToHtmlIds = mappedHtmlIds,
            )
            index += 1
        }
        return merged
    }

    internal data class LogicalChapterInput(
        val originalOrdinal: Int,
        val n: String,
        val title: String,
        val role: ChapterRole,
        val merged: Boolean,
        val sourceFiles: List<String>,
        val contentSha256: String,
        val chars: Int,
        val blocks: List<ParsedBlock>,
    )

    internal data class FinalizedChapter(
        val ordinal: Int,
        val originalOrdinal: Int,
        val n: String,
        val title: String,
        val role: ChapterRole,
        val merged: Boolean,
        val sourceFiles: List<String>,
        val contentSha256: String,
        val chars: Int,
        val blocks: List<ParsedBlock>,
        val virtualSplitParentOrdinal: Int?,
        val oldToNewBlockMap: Map<String, Pair<Int, String>>,
    )

    fun applyVirtualSplits(inputs: List<LogicalChapterInput>): List<FinalizedChapter> {
        val rawSlices = mutableListOf<TempSlice>()
        for (ch in inputs) {
            val totalBlockChars = ch.blocks.sumOf { TextSanitizer.nonSpaceCount(it.cleanText) }
            val effectiveChars = maxOf(ch.chars, totalBlockChars)
            val needsSplit = ch.blocks.isNotEmpty() &&
                (effectiveChars > VIRTUAL_SPLIT_TRIGGER_CHARS || ch.blocks.size > VIRTUAL_SPLIT_TRIGGER_BLOCKS)

            if (!needsSplit) {
                rawSlices += TempSlice(
                    originalOrdinal = ch.originalOrdinal,
                    parentOrdinal = null,
                    n = ch.n,
                    title = ch.title,
                    role = ch.role,
                    merged = ch.merged,
                    sourceFiles = ch.sourceFiles,
                    contentSha256 = ch.contentSha256,
                    chars = effectiveChars,
                    blocks = ch.blocks,
                )
                continue
            }

            val chunks = splitBlocksIntoVirtualSlices(ch.blocks)
            chunks.forEachIndexed { idx, sliceBlocks ->
                val sliceChars = sliceBlocks.sumOf { TextSanitizer.nonSpaceCount(it.cleanText) }
                val sliceTitle = if (chunks.size == 1) {
                    ch.title
                } else {
                    val headingTitle = sliceBlocks.firstOrNull { it.kind == MarkdownBlockKind.HEADING }?.cleanText?.takeIf { it.isNotBlank() }
                    if (idx == 0 && ch.title.isNotBlank()) {
                        "${ch.title}（第 1 节）"
                    } else if (headingTitle != null && headingTitle != ch.title) {
                        "${ch.title} · $headingTitle"
                    } else {
                        "${ch.title.ifBlank { "正文" }}（第 ${idx + 1} 节）"
                    }
                }
                rawSlices += TempSlice(
                    originalOrdinal = ch.originalOrdinal,
                    parentOrdinal = ch.originalOrdinal,
                    n = ch.n,
                    title = sliceTitle,
                    role = ch.role,
                    merged = ch.merged,
                    sourceFiles = ch.sourceFiles,
                    contentSha256 = ch.contentSha256,
                    chars = sliceChars,
                    blocks = sliceBlocks,
                )
            }
        }

        return rawSlices.mapIndexed { idx, slice ->
            val newOrdinal = idx + 1
            val mapping = linkedMapOf<String, Pair<Int, String>>()
            val renumbered = renumberBlocks(slice.blocks) { oldId, newId ->
                mapping[oldId] = newOrdinal to newId
            }
            FinalizedChapter(
                ordinal = newOrdinal,
                originalOrdinal = slice.originalOrdinal,
                n = if (slice.parentOrdinal == null) slice.n else newOrdinal.toString().padStart(2, '0'),
                title = slice.title,
                role = slice.role,
                merged = slice.merged,
                sourceFiles = slice.sourceFiles,
                contentSha256 = slice.contentSha256,
                chars = slice.chars,
                blocks = renumbered,
                virtualSplitParentOrdinal = slice.parentOrdinal,
                oldToNewBlockMap = mapping,
            )
        }
    }

    private data class TempSlice(
        val originalOrdinal: Int,
        val parentOrdinal: Int?,
        val n: String,
        val title: String,
        val role: ChapterRole,
        val merged: Boolean,
        val sourceFiles: List<String>,
        val contentSha256: String,
        val chars: Int,
        val blocks: List<ParsedBlock>,
    )

    private fun splitBlocksIntoVirtualSlices(blocks: List<ParsedBlock>): List<List<ParsedBlock>> {
        val slices = mutableListOf<MutableList<ParsedBlock>>()
        var cur = mutableListOf<ParsedBlock>()
        var curChars = 0

        for (blk in blocks) {
            val blkChars = maxOf(1, TextSanitizer.nonSpaceCount(blk.cleanText))
            val isHeading = blk.kind == MarkdownBlockKind.HEADING
            val shouldCutForHeading = cur.isNotEmpty() && isHeading && curChars >= VIRTUAL_TARGET_MIN_CHARS
            val wouldExceed = cur.isNotEmpty() && (
                (curChars >= VIRTUAL_TARGET_MIN_CHARS && curChars + blkChars > VIRTUAL_TARGET_MAX_CHARS) ||
                    cur.size + 1 > VIRTUAL_SPLIT_TRIGGER_BLOCKS ||
                    curChars + blkChars > VIRTUAL_TARGET_MAX_CHARS
                )

            if (shouldCutForHeading || wouldExceed) {
                slices += cur
                cur = mutableListOf(blk)
                curChars = blkChars
            } else {
                cur += blk
                curChars += blkChars
            }
        }
        if (cur.isNotEmpty()) {
            slices += cur
        }

        if (slices.size >= 2) {
            val last = slices.last()
            val prev = slices[slices.size - 2]
            val lastChars = last.sumOf { maxOf(1, TextSanitizer.nonSpaceCount(it.cleanText)) }
            val prevChars = prev.sumOf { maxOf(1, TextSanitizer.nonSpaceCount(it.cleanText)) }
            if (lastChars < VIRTUAL_TARGET_MIN_CHARS) {
                if (prevChars + lastChars <= VIRTUAL_TARGET_MAX_CHARS &&
                    prev.size + last.size <= VIRTUAL_SPLIT_TRIGGER_BLOCKS
                ) {
                    prev.addAll(last)
                    slices.removeAt(slices.lastIndex)
                } else {
                    val combined = (prev + last).toMutableList()
                    val total = prevChars + lastChars
                    val halfTarget = total / 2
                    var acc = 0
                    var splitAt = combined.size / 2
                    for (i in combined.indices) {
                        acc += maxOf(1, TextSanitizer.nonSpaceCount(combined[i].cleanText))
                        if (acc >= halfTarget && i + 1 < combined.size) {
                            splitAt = i + 1
                            break
                        }
                    }
                    val firstHalf = combined.subList(0, splitAt).toMutableList()
                    val secondHalf = combined.subList(splitAt, combined.size).toMutableList()
                    val firstChars = firstHalf.sumOf { maxOf(1, TextSanitizer.nonSpaceCount(it.cleanText)) }
                    val secondChars = secondHalf.sumOf { maxOf(1, TextSanitizer.nonSpaceCount(it.cleanText)) }
                    if (firstChars in VIRTUAL_TARGET_MIN_CHARS..VIRTUAL_TARGET_MAX_CHARS &&
                        secondChars in VIRTUAL_TARGET_MIN_CHARS..VIRTUAL_TARGET_MAX_CHARS
                    ) {
                        slices[slices.size - 2] = firstHalf
                        slices[slices.lastIndex] = secondHalf
                    }
                }
            }
        }

        return slices
    }

    internal fun renumberBlocks(
        blocks: List<ParsedBlock>,
        onRename: ((oldId: String, newId: String) -> Unit)? = null,
    ): List<ParsedBlock> {
        var charCursor = 0
        return blocks.mapIndexed { idx, blk ->
            val newId = BlockId.validate("B${(idx + 1).toString().padStart(2, '0')}").value
            onRename?.invoke(blk.id, newId)
            val len = blk.cleanText.length
            val start = charCursor
            val end = start + len
            charCursor = if (len > 0) end + 1 else end
            blk.copy(
                id = newId,
                blockIndex = idx,
                charStart = start,
                charEnd = end,
            )
        }
    }
}
