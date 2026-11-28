package app.banread.library.internal

internal object HardWrapStitcher {

    internal data class HardWrapProfile(
        val isHardWrapped: Boolean,
        val medianLineWidth: Double,
    )

    internal data class StitchInputBlock(
        val block: ParsedBlock,
        val htmlIds: Set<String> = emptySet(),
    )

    private val TERMINAL_PUNCT = charArrayOf('。', '！', '？', '…', '!', '?')
    private val CLOSING_QUOTES_BRACKETS = setOf('”', '’', '"', '\'', ')', '）', '】', '」', '』', '》', '>', '〕', '］', ']')
    private val CONTINUATION_PUNCT = setOf(',', '，', '、', '；', ';', '—', '－', '-', '~', '～')
    private val LEADING_CONTINUATION_PUNCT_RE = Regex("""^[。，、；：！？…）】」』”’,.!?;:\]〕］]""")

    private val CAPTION_OR_SOURCE_RE = Regex(
        """^(?:(?:图|表|Fig\.?|Figure|Table)\s*[A-Za-z0-9]+[-–—.\d]*(?:\s|$)|资料来源[:：]|注\s*[:：])""",
        RegexOption.IGNORE_CASE,
    )
    private val LIST_LIKE_START_RE = Regex(
        """^(?:[·•▪▫●○◆◇]\s*\S|[A-Za-z]\.\S|\d{1,3}[.、]\s*\S)""",
    )
    private val NUMBERED_HEADING_RE = Regex(
        """^(?:第\s*[〇零一二三四五六七八九十百千两0-9]+\s*[章节篇部卷回条](?:\s|$)|(?:Chapter|Part|Section|Appendix)\s+\d+(?:\s|:|$)|案例[〇零一二三四五六七八九十\d]+(?:\s|[:：]|$))""",
        RegexOption.IGNORE_CASE,
    )
    private val METADATA_KV_RE = Regex(
        """^(?:书名|作者|译者|编者|出版社|出版时间|品牌方|ISBN)\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_LINE_RE = Regex("""^\d{4}年\d{1,2}月\d{1,2}日.*$""")
    private val EPIGRAPH_ATTR_RE = Regex("""^(?:——|--|──)\s*\S.*$""")

    fun analyzeProfile(blocks: List<ParsedBlock>): HardWrapProfile {
        val substantialWidths = mutableListOf<Double>()
        var midEndedCount = 0
        val midWidths = mutableListOf<Double>()

        for (blk in blocks) {
            if (blk.kind != "paragraph" || blk.isBoldOnly) continue
            val text = blk.cleanText.trim()
            if (TextSanitizer.nonSpaceCount(text) < 16) continue
            val width = visualWidth(text)
            substantialWidths += width
            if (!endsWithTerminalSentence(text)) {
                midEndedCount++
                midWidths += width
            }
        }

        if (substantialWidths.size < 3) {
            return HardWrapProfile(isHardWrapped = false, medianLineWidth = 0.0)
        }
        val ratio = midEndedCount.toDouble() / substantialWidths.size.toDouble()
        if (ratio < 0.45 || midWidths.isEmpty()) {
            return HardWrapProfile(isHardWrapped = false, medianLineWidth = 0.0)
        }
        val sorted = midWidths.sorted()
        val median = sorted[sorted.size / 2]
        return HardWrapProfile(isHardWrapped = true, medianLineWidth = median)
    }

    fun stitch(
        inputs: List<StitchInputBlock>,
        profile: HardWrapProfile,
        chapterTitle: String = "",
    ): List<StitchInputBlock> {
        if (inputs.size < 2) return inputs

        // First stitch wrapped bold-only title lines (e.g., long bold heading split across 2 <p><b>...</b></p> lines,
        // or bold heading containing an unclosed inline note bracket).
        val afterBoldTitles = stitchWrappedBoldTitles(inputs, chapterTitle)
        if (!profile.isHardWrapped || afterBoldTitles.size < 2) {
            return afterBoldTitles
        }

        val out = mutableListOf<StitchInputBlock>()
        var acc: StitchInputBlock? = null
        var accTailBlock: ParsedBlock? = null

        for (item in afterBoldTitles) {
            val prevAcc = acc
            val prevTail = accTailBlock
            if (prevAcc == null || prevTail == null) {
                acc = item
                accTailBlock = item.block
                continue
            }

            if (shouldMerge(prevAcc.block, prevTail, item.block, profile)) {
                acc = mergePair(prevAcc, item)
                accTailBlock = item.block
            } else {
                out += prevAcc
                acc = item
                accTailBlock = item.block
            }
        }
        if (acc != null) {
            out += acc
        }
        return out
    }

    private fun stitchWrappedBoldTitles(
        inputs: List<StitchInputBlock>,
        chapterTitle: String,
    ): List<StitchInputBlock> {
        val normChapterTitle = normalizeForComparison(chapterTitle)
        val out = mutableListOf<StitchInputBlock>()
        var i = 0
        while (i < inputs.size) {
            val cur = inputs[i]
            if (i + 1 < inputs.size && cur.block.kind == "paragraph" && cur.block.isBoldOnly) {
                val nxt = inputs[i + 1]
                if (nxt.block.kind == "paragraph" && nxt.block.isBoldOnly) {
                    val curText = cur.block.cleanText.trim()
                    val nxtText = nxt.block.cleanText.trim()
                    val combinedNorm = normalizeForComparison(curText + nxtText)
                    val matchesTocTitle = normChapterTitle.isNotEmpty() && combinedNorm == normChapterTitle
                    val hasUnclosed = hasUnclosedDelimiter(curText, includeQuotes = false)
                    val nxtStartsPunct = LEADING_CONTINUATION_PUNCT_RE.containsMatchIn(nxtText)
                    if (matchesTocTitle || hasUnclosed || nxtStartsPunct) {
                        out += mergePair(cur, nxt)
                        i += 2
                        continue
                    }
                }
            }
            out += cur
            i++
        }
        return out
    }

    private fun shouldMerge(
        accBlock: ParsedBlock,
        prevTail: ParsedBlock,
        next: ParsedBlock,
        profile: HardWrapProfile,
    ): Boolean {
        if (prevTail.kind != "paragraph" || next.kind != "paragraph") return false
        if (prevTail.isBoldOnly || next.isBoldOnly) return false

        val prevText = prevTail.cleanText.trim()
        val nextText = next.cleanText.trim()
        if (prevText.isEmpty() || nextText.isEmpty()) return false

        // 1. If next starts with closing/trailing punctuation (e.g., orphan "。"), always attach it.
        if (LEADING_CONTINUATION_PUNCT_RE.containsMatchIn(nextText)) {
            return true
        }

        // 2. Unclosed inline-note / parenthetical bracket across the accumulated paragraph or tail line.
        if (hasUnclosedDelimiter(accBlock.cleanText, includeQuotes = false)) {
            // Avoid swallowing unrelated metadata/headings even if a paren was left dangling across chapters
            if (!NUMBERED_HEADING_RE.containsMatchIn(nextText) && !METADATA_KV_RE.containsMatchIn(nextText)) {
                return true
            }
        }

        // 3. Do not merge if previous tail ended a sentence with terminal punctuation,
        //    or ended with a colon introducing a list/quote/metadata field.
        if (endsWithTerminalSentence(prevText) || endsWithColon(prevText)) {
            return false
        }

        // 4. Structural exclusions on next line (start of a distinct block element).
        if (METADATA_KV_RE.containsMatchIn(nextText) ||
            DATE_LINE_RE.matches(nextText) ||
            LIST_LIKE_START_RE.containsMatchIn(nextText) ||
            NUMBERED_HEADING_RE.containsMatchIn(nextText) ||
            CAPTION_OR_SOURCE_RE.containsMatchIn(nextText)
        ) {
            return false
        }

        // Epigraph attribution line (——Author) after a complete quote or closing quote should never merge.
        // Mid-sentence em-dash continuations (e.g. "发出了指示" + "——让他们最为痛苦的指示。") do merge when prevTail is wrapped.
        if (EPIGRAPH_ATTR_RE.matches(nextText)) {
            val endsWithQuoteOrClose = prevText.last() in CLOSING_QUOTES_BRACKETS
            if (endsWithQuoteOrClose || visualWidth(prevText) < wrapMinWidth(profile)) {
                return false
            }
        }

        // 5. Structural exclusions on previous tail line.
        if (METADATA_KV_RE.containsMatchIn(prevText) ||
            DATE_LINE_RE.matches(prevText) ||
            CAPTION_OR_SOURCE_RE.containsMatchIn(prevText)
        ) {
            return false
        }
        if (LIST_LIKE_START_RE.containsMatchIn(prevText) &&
            (prevText.endsWith("%") || visualWidth(prevText) < wrapMinWidth(profile))
        ) {
            return false
        }
        if (EPIGRAPH_ATTR_RE.matches(prevText)) {
            // Only stitch an epigraph attribution if it hit the right margin and next is a short wrapped name tail.
            val hitMargin = visualWidth(prevText) >= maxOf(24.0, profile.medianLineWidth * 0.78)
            val shortNameTail = TextSanitizer.nonSpaceCount(nextText) <= 8 && !endsWithTerminalSentence(nextText)
            return hitMargin && shortNameTail
        }

        // 6. Continuation punctuation at end of prevText (comma, pause mark, semicolon, dash).
        if (prevText.last() in CONTINUATION_PUNCT) {
            // Guard against short date/title fragments ending with '-' or similar; require reasonable line width or non-heading next
            val prevW = visualWidth(prevText)
            if (prevW >= maxOf(12.0, profile.medianLineWidth * 0.40)) {
                return true
            }
        }

        // 7. Width-based wrap detection: did prevText reach the column wrap margin?
        val prevWidth = visualWidth(prevText)
        val wrapThreshold = wrapMinWidth(profile)
        if (prevWidth >= wrapThreshold) {
            return true
        }

        // Also handle lines that wrapped slightly early before a wide/unbreakable Latin token or parenthetical.
        val nextLeadWidth = leadingTokenVisualWidth(nextText)
        if (nextLeadWidth >= 2.0 && prevWidth >= wrapThreshold * 0.75 && prevWidth + nextLeadWidth >= wrapThreshold) {
            return true
        }

        // Handle short wrapped English lines (like copyright block or Latin citations) where words don't hyphenate.
        if (isPredominantlyAscii(prevText) && prevWidth >= 14.0 && nextLeadWidth >= 2.0 &&
            (isPredominantlyAscii(nextText) || nextText.first().isLowerCase())
        ) {
            return true
        }

        return false
    }

    private fun wrapMinWidth(profile: HardWrapProfile): Double =
        maxOf(16.0, profile.medianLineWidth * 0.835)

    private fun mergePair(prev: StitchInputBlock, next: StitchInputBlock): StitchInputBlock {
        val prevText = prev.block.cleanText.trimEnd()
        val nextText = next.block.cleanText.trimStart()
        val (joined, sepLen, trimmedPrevLen) = joinLines(prevText, nextText)

        val shiftedNextRefs = next.block.fnRefs.map { ref ->
            ref.copy(charOffsetInBlock = trimmedPrevLen + sepLen + ref.charOffsetInBlock)
        }
        val combinedRefs = prev.block.fnRefs + shiftedNextRefs
        val combinedHtmlIds = linkedSetOf<String>().apply {
            addAll(prev.htmlIds)
            addAll(next.htmlIds)
        }
        return StitchInputBlock(
            block = prev.block.copy(
                cleanText = joined,
                charEnd = joined.length,
                fnRefs = combinedRefs,
            ),
            htmlIds = combinedHtmlIds,
        )
    }

    private data class JoinResult(
        val text: String,
        val separatorLength: Int,
        val effectivePrevLength: Int,
    )

    private fun joinLines(prev: String, next: String): JoinResult {
        if (prev.isEmpty()) return JoinResult(next, 0, 0)
        if (next.isEmpty()) return JoinResult(prev, 0, prev.length)

        val last = prev.last()
        val first = next.first()

        // De-hyphenate ASCII word split across line break (e.g. "inter-" + "national")
        if (last == '-' && prev.length >= 2 && prev[prev.length - 2] in 'A'..'z' &&
            prev[prev.length - 2].isLetter() && first in 'a'..'z'
        ) {
            val trimmedPrev = prev.dropLast(1)
            return JoinResult(trimmedPrev + next, 0, trimmedPrev.length)
        }

        val needsAsciiSpace = when {
            isAsciiWordChar(last) && isAsciiWordChar(first) -> true
            last in setOf(',', ';', ':', ')', ']', '.') && isAsciiWordChar(first) -> true
            else -> false
        }
        return if (needsAsciiSpace) {
            JoinResult("$prev $next", 1, prev.length)
        } else {
            JoinResult("$prev$next", 0, prev.length)
        }
    }

    private fun isAsciiWordChar(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9'

    private fun endsWithTerminalSentence(text: String): Boolean {
        if (text.isEmpty()) return false
        var idx = text.lastIndex
        while (idx >= 0 && text[idx] in CLOSING_QUOTES_BRACKETS) {
            idx--
        }
        if (idx < 0) return false
        val ch = text[idx]
        if (ch in TERMINAL_PUNCT) return true
        if (ch == '.') {
            // Avoid treating abbreviations like "A." or "U.S." or decimal numbers "1.5" as sentence end unless preceded by 2+ lowercase letters
            val prev = if (idx > 0) text[idx - 1] else ' '
            val prev2 = if (idx > 1) text[idx - 2] else ' '
            return prev in 'a'..'z' && prev2 in 'a'..'z'
        }
        return false
    }

    private fun endsWithColon(text: String): Boolean {
        if (text.isEmpty()) return false
        val last = text.last()
        return last == '：' || last == ':'
    }

    private fun hasUnclosedDelimiter(text: String, includeQuotes: Boolean): Boolean {
        val pairs = buildList {
            add('【' to '】')
            add('（' to '）')
            add('(' to ')')
            add('[' to ']')
            add('〔' to '〕')
            add('［' to '］')
            add('「' to '」')
            add('『' to '』')
            add('《' to '》')
            if (includeQuotes) add('“' to '”')
        }
        for ((open, close) in pairs) {
            var depth = 0
            for (ch in text) {
                when (ch) {
                    open -> depth++
                    close -> if (depth > 0) depth--
                }
            }
            if (depth > 0) return true
        }
        return false
    }

    internal fun visualWidth(text: String): Double {
        var w = 0.0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            w += if (ch.code < 128) 0.5 else 1.0
        }
        return w
    }

    private fun leadingTokenVisualWidth(text: String): Double {
        if (text.isEmpty()) return 0.0
        var i = 0
        var w = 0.0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isWhitespace()) break
            if (ch.code < 128 && (ch.isLetterOrDigit() || ch in setOf('(', '[', '-', '_'))) {
                w += 0.5
                i++
            } else {
                if (i == 0) w = if (ch.code < 128) 0.5 else 1.0
                break
            }
        }
        return w
    }

    private fun isPredominantlyAscii(text: String): Boolean {
        var ascii = 0
        var nonSpace = 0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            nonSpace++
            if (ch.code < 128) ascii++
        }
        return nonSpace > 0 && ascii * 10 >= nonSpace * 7
    }

    private fun normalizeForComparison(text: String): String =
        text.replace(Regex("""[\s\u3000]+"""), "")
}
