package app.banread.library.internal

import app.banread.model.ChapterRole
import java.text.Normalizer

internal data class InlineFnRef(
    val markerDisplay: String,
    val markerKey: String,
    val targetFile: String,
    val targetFragment: String,
    val charOffsetInBlock: Int,
)

internal data class ParsedBlock(
    val id: String,
    val blockIndex: Int,
    val kind: MarkdownBlockKind,
    val headingPath: String,
    val cleanText: String,
    val charStart: Int,
    val charEnd: Int,
    val level: Int? = null,
    val lang: String = "",
    val imageRel: String = "",
    val imageAlt: String = "",
    val listItems: List<String> = emptyList(),
    val listItemRefs: List<List<InlineFnRef>> = emptyList(),
    val tableHead: List<String> = emptyList(),
    val tableRows: List<List<String>> = emptyList(),
    val fnRefs: List<InlineFnRef> = emptyList(),
    val fnMarkerDisplay: String = "",
    val fnMarkerKey: String = "",
    val fnBackFile: String = "",
    val isTranslatorOrBib: Boolean = false,
    val isBoldOnly: Boolean = false,
)

internal data class ParsedChapterMarkdown(
    val title: String,
    val h1: String,
    val blocks: List<ParsedBlock>,
    val meta: Map<String, String>,
    val nonSpaceChars: Int,
)

internal object MarkdownBlockParser {
    private val HEADING_RE = Regex("""^(#{1,6})\s+(.+?)\s*$""")
    private val HR_RE = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
    private val QUOTE_RE = Regex("""^>\s?(.*)$""")
    private val LIST_RE = Regex("""^(?:[-*+]|\d+[.)])\s+(.*)$""")
    private val CODE_FENCE_RE = Regex("""^```(\S*)\s*$""")
    private val TABLE_ROW_RE = Regex("""^\|.*\|\s*$""")
    private val TABLE_ALIGN_RE = Regex(""":?-{1,}:?""")
    private val IMAGE_RE = Regex("""!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
    private val FOOTNOTE_DEF_STD_RE = Regex("""^\[\^([^\]]+)\]:\s*(.*)$""")
    private val ICON_STEM_RE = Regex("""^(?:note|footnote|tip|icon|fn)[_-]?\d*$""", RegexOption.IGNORE_CASE)

    private val CIRCLED_MAP: Map<Char, String> = buildMap {
        (0x2460..0x2473).forEachIndexed { i, cp -> put(cp.toChar(), (i + 1).toString()) }
        (0x3251..0x325F).forEachIndexed { i, cp -> put(cp.toChar(), (i + 21).toString()) }
        (0x32B1..0x32BF).forEachIndexed { i, cp -> put(cp.toChar(), (i + 36).toString()) }
    }
    private const val CIRCLED_CHARS = "\u2460-\u2473\u3251-\u325F\u32B1-\u32BF"
    private const val CORE_NUM = """\d{1,4}(?:[.-]\d{1,3}|[a-zA-Z])?"""
    private const val ZHU_CORE = """(?:卢梭|编者|译|原)?[注註]释?\s*[0-9一二三四五六七八九十*]*"""
    private const val CORE_LABEL = """(?:$CORE_NUM|[a-zA-Z]|[$CIRCLED_CHARS]|[*†‡]+|$ZHU_CORE)"""
    private const val BRACKETED_OR_CORE = """(?:\[$CORE_LABEL\]|[（(]$CORE_LABEL[）)]|[〔［【]$CORE_LABEL[〕］】]|$CORE_LABEL)"""
    private const val DBL_LABEL = """(?:$CORE_LABEL|[^\[\]\n]{1,12})"""
    private const val TARGET = """(<[^>\n]*>|[^)\n]*?)"""
    private const val EMPH = """(?:\*\*|\*)?"""

    private val INLINE_FOOTNOTE_RE = Regex(
        """(?<hatDbl>\^\[\[$EMPH($DBL_LABEL)$EMPH\.?\]\]\($TARGET\)\^)""" +
            """|(?<dblHat>\^\[\^$EMPH($BRACKETED_OR_CORE)$EMPH\^\]\($TARGET\)\^)""" +
            """|(?<hatSgl>\^\[$EMPH($BRACKETED_OR_CORE)$EMPH\.?\]\($TARGET\)\^)""" +
            """|(?<innerHat>\[\^$EMPH($BRACKETED_OR_CORE)$EMPH\^\]\($TARGET\))""" +
            """|(?<dblBr>(?<!\!)\[\[$EMPH($DBL_LABEL)$EMPH\.?\]\]\($TARGET\))""" +
            """|(?<sglLink>(?<![!\[\^])\[$EMPH($BRACKETED_OR_CORE)$EMPH\.?\]\($TARGET\))""" +
            """|(?<stdMd>(?<!\[)\[\^([0-9A-Za-z_-]{1,20})\](?![:(]))""" +
            """|(?<bareSup>(?<!\[)\^(?:\[(?:$CORE_NUM)\]|[（(](?:$CORE_NUM)[）)]|[〔［【](?:$CORE_NUM)[〕］】]|[$CIRCLED_CHARS]|(?:$ZHU_CORE))\^(?!\]))""",
    )

    private val DEF_DBL_BRACKET_RE = Regex(
        """^\[\[$EMPH($DBL_LABEL)$EMPH[.。:：]?\]\]\($TARGET\)(?:\.(?!\.)\s*|、\s*|\s*)(.*)$""",
    )
    private val DEF_MD_BACKLINK_RE = Regex(
        """^\[$EMPH($BRACKETED_OR_CORE)$EMPH[.。:：]?\]\($TARGET\)(?:\.(?!\.)\s*|、\s*|\s*)(.+)$""",
    )
    private val DEF_CARET_BACKLINK_RE = Regex(
        """^\[\^$EMPH($BRACKETED_OR_CORE)$EMPH\^[.。:：]?\]\($TARGET\)(?:\.(?!\.)\s*|、\s*|\s*)(.+)$""",
    )

    private val TIER2_PATTERNS = listOf(
        Regex("""^(\[(\d{1,4}(?:[.-]\d{1,3})?)\]\s*)(\S.*)$"""),
        Regex("""^([〔［【](\d{1,4})[〕］】]\s*)(\S.*)$"""),
        Regex("""^(\[\[(\d{1,4})\]\]\(\)\s*)(\S.*)$"""),
        Regex("""^(($ZHU_CORE)(?:[：:.、]\s*|\s+)?)(\S.*)$"""),
        Regex("""^([（(](\d{1,4})[）)]\s*)(\S.*)$"""),
        Regex("""^(([$CIRCLED_CHARS])\s*)(\S.*)$"""),
    )
    private val TIER2_DOT_RE = Regex("""^(\s*(\d{1,4})\s*[.、．]\s*)(\S.*)$""")
    private val PLAIN_ENDNOTE_LINE_RE = Regex("""^\s*(\d{1,4})\s+(\S.*)$""")

    private val NOTE_HEADING_RE = Regex(
        """^\s*[【\[(（〔［〈《]?\s*(?:(?:作者|译者|譯者|编者|編者|校者|校|编|編|译|譯|原)?[注註](?:释|釋)?\s*\d{0,4}|(?:原书|全书|本书|本章|本节|各章|分章|附录)\s*(?:脚注|腳注|尾注|注释|註釋|注解|引注|译注|譯注|备注|備註)[^\n]{0,20}|(?:脚注|腳注|尾注|注释|註釋|注解|引注|译注|譯注|备注|備註)|notes?|footnotes?|endnotes?)\s*(?:[（(][^）)]*[）)])?\s*[】\])）〕］〉》]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val TRANSLATOR_OR_BIB_RE = Regex(
        """(?:^|[\s\[【(（])(?:译注|譯注|译者注|譯者注|编者注|編者注|校注|校者注|编者按|\[译\]|\[编\]|参考文献|参考书目|bibliography|works\s+cited|further\s+reading)(?:$|[\s\]】)）：:，,])""",
        RegexOption.IGNORE_CASE,
    )

    private val OUTER_PAIRS = listOf(
        "[" to "]",
        "(" to ")",
        "（" to "）",
        "〔" to "〕",
        "［" to "］",
        "【" to "】",
    )

    fun normalizeMarker(raw: String): Pair<String, String> {
        var text = raw.trim().trim('*').trim()
        var changed = true
        while (changed && text.isNotEmpty()) {
            changed = false
            for ((left, right) in OUTER_PAIRS) {
                if (text.startsWith(left) && text.endsWith(right) && text.length > left.length + right.length) {
                    text = text.substring(left.length, text.length - right.length).trim().trim('*').trim()
                    changed = true
                    break
                }
            }
        }
        text = text.replace(Regex("""[.。、:：]+$"""), "").trim()
        val display = text.ifEmpty { raw.trim() }
        val nfkc = Normalizer.normalize(display, Normalizer.Form.NFKC).trim().lowercase().replace(Regex("""\s+"""), "")
        val key = if (display.length == 1 && display[0] in CIRCLED_MAP) {
            CIRCLED_MAP[display[0]] ?: nfkc
        } else {
            nfkc
        }
        return display to key
    }

    fun isTranslatorOrBibNote(marker: String, noteText: String, headingPath: String): Boolean {
        return TRANSLATOR_OR_BIB_RE.containsMatchIn(marker) ||
            TRANSLATOR_OR_BIB_RE.containsMatchIn(noteText) ||
            TRANSLATOR_OR_BIB_RE.containsMatchIn(headingPath)
    }

    fun parse(
        rawMarkdown: String,
        fallbackTitle: String = "",
        chapterRoleHint: ChapterRole? = null,
    ): ParsedChapterMarkdown {
        val sanitized = TextSanitizer.sanitizeRawPreservingMath(rawMarkdown)
        val (meta, bodyAfterFm) = TextSanitizer.stripYamlFrontMatter(sanitized)
        val (h1, rest) = takeFirstH1(bodyAfterFm)
        val resolvedTitle = TextSanitizer.cleanInline(meta["title"] ?: h1.ifEmpty { fallbackTitle })

        val rawBlocks = mutableListOf<ParsedBlock>()
        val segments = splitCodeSegments(rest)
        for (seg in segments) {
            when (seg) {
                is Segment.Code -> {
                    rawBlocks += makeBlock(
                        index = rawBlocks.size,
                        kind = MarkdownBlockKind.CODE,
                        cleanText = seg.code,
                        lang = seg.lang,
                    )
                }
                is Segment.Md -> {
                    for (chunk in splitByBlankLines(seg.text)) {
                        parseChunk(rawBlocks, chunk)
                    }
                }
            }
        }

        val promoted = promoteNotesIfApplicable(rawBlocks, chapterRoleHint)
        val withHeadings = assignHeadingPaths(promoted, resolvedTitle)
        val numbered = RoleAndSplit.renumberBlocks(withHeadings)
        val nonSpaceChars = numbered.sumOf { TextSanitizer.nonSpaceCount(it.cleanText) }

        return ParsedChapterMarkdown(
            title = resolvedTitle,
            h1 = h1,
            blocks = numbered,
            meta = meta,
            nonSpaceChars = nonSpaceChars,
        )
    }

    private fun takeFirstH1(body: String): Pair<String, String> {
        val lines = body.split('\n')
        for (i in lines.indices) {
            val trimmed = lines[i].trim().trimStart('\uFEFF')
            if (trimmed.isEmpty()) continue
            return if (trimmed.startsWith("# ")) {
                val h1 = trimmed.substring(2).trim()
                val rest = if (i + 1 < lines.size) lines.subList(i + 1, lines.size).joinToString("\n") else ""
                h1 to rest
            } else {
                "" to body
            }
        }
        return "" to body
    }

    private sealed interface Segment {
        data class Md(val text: String) : Segment
        data class Code(val lang: String, val code: String) : Segment
    }

    private fun splitCodeSegments(body: String): List<Segment> {
        val lines = body.split('\n')
        val out = mutableListOf<Segment>()
        val mdBuf = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val fence = CODE_FENCE_RE.matchEntire(lines[i].trim())
            if (fence != null) {
                if (mdBuf.isNotEmpty()) {
                    out += Segment.Md(mdBuf.joinToString("\n"))
                    mdBuf.clear()
                }
                val lang = fence.groupValues[1]
                i++
                val codeBuf = mutableListOf<String>()
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeBuf += lines[i]
                    i++
                }
                i++
                out += Segment.Code(lang, codeBuf.joinToString("\n").trim('\n'))
            } else {
                mdBuf += lines[i]
                i++
            }
        }
        if (mdBuf.isNotEmpty()) {
            out += Segment.Md(mdBuf.joinToString("\n"))
        }
        return out
    }

    private fun splitByBlankLines(text: String): List<String> {
        val out = mutableListOf<String>()
        val buf = mutableListOf<String>()
        for (line in text.split('\n')) {
            if (line.isBlank()) {
                if (buf.isNotEmpty()) {
                    out += buf.joinToString("\n")
                    buf.clear()
                }
            } else {
                buf += line
            }
        }
        if (buf.isNotEmpty()) out += buf.joinToString("\n")
        return out
    }

    private fun parseChunk(out: MutableList<ParsedBlock>, chunk: String) {
        val lines = chunk.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        if (lines.all { HR_RE.matches(it.trim()) }) return

        val heading = HEADING_RE.matchEntire(lines[0].trim())
        if (heading != null && lines.size == 1) {
            val level = heading.groupValues[1].length
            val (cleanHeading, refs) = extractInlineFootnotes(heading.groupValues[2].replace("**", ""))
            out += makeBlock(
                index = out.size,
                kind = MarkdownBlockKind.HEADING,
                cleanText = cleanHeading,
                level = level,
                fnRefs = refs,
            )
            return
        }

        val stdDef = FOOTNOTE_DEF_STD_RE.matchEntire(lines[0].trim())
        if (stdDef != null) {
            val rawLabel = stdDef.groupValues[1]
            val (display, key) = normalizeMarker(rawLabel)
            val rawBody = (listOf(stdDef.groupValues[2].trim()) + lines.drop(1).map { it.trim() })
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            val (cleanBody, refs) = extractInlineFootnotes(rawBody)
            out += makeBlock(
                index = out.size,
                kind = MarkdownBlockKind.FOOTNOTE,
                cleanText = cleanBody,
                fnRefs = refs,
                fnMarkerDisplay = display,
                fnMarkerKey = key,
            )
            return
        }

        val joined = lines.joinToString(" ") { it.trim() }
        val explicitDef = parseExplicitFootnoteDef(joined)
        if (explicitDef != null) {
            val (cleanBody, refs) = extractInlineFootnotes(explicitDef.body)
            out += makeBlock(
                index = out.size,
                kind = MarkdownBlockKind.FOOTNOTE,
                cleanText = cleanBody,
                fnRefs = refs,
                fnMarkerDisplay = explicitDef.display,
                fnMarkerKey = explicitDef.key,
                fnBackFile = explicitDef.backFile,
            )
            return
        }

        if (lines.all { QUOTE_RE.matches(it.trim()) }) {
            val quoteText = lines.joinToString(" ") { ln ->
                QUOTE_RE.matchEntire(ln.trim())?.groupValues?.get(1)?.trim().orEmpty()
            }
            if (quoteText.startsWith("`") && quoteText.endsWith("`") && quoteText.length >= 2 &&
                '`' !in quoteText.substring(1, quoteText.length - 1)
            ) {
                out += makeBlock(
                    index = out.size,
                    kind = MarkdownBlockKind.CODE,
                    cleanText = quoteText.substring(1, quoteText.length - 1).trim(),
                )
            } else {
                val (cleanQuote, refs) = extractInlineFootnotes(quoteText)
                out += makeBlock(
                    index = out.size,
                    kind = MarkdownBlockKind.QUOTE,
                    cleanText = cleanQuote,
                    fnRefs = refs,
                )
            }
            return
        }

        if (lines.all { LIST_RE.matches(it.trim()) }) {
            val items = mutableListOf<String>()
            val itemRefs = mutableListOf<List<InlineFnRef>>()
            for (line in lines) {
                val itemRaw = LIST_RE.matchEntire(line.trim())?.groupValues?.get(1)?.trim() ?: line.trim()
                val (cleanItem, refs) = extractInlineFootnotes(itemRaw)
                items += cleanItem
                itemRefs += refs
            }
            out += makeBlock(
                index = out.size,
                kind = MarkdownBlockKind.LIST,
                cleanText = items.joinToString("\n"),
                listItems = items,
                listItemRefs = itemRefs,
                fnRefs = itemRefs.flatten(),
            )
            return
        }

        if (lines.all { TABLE_ROW_RE.matches(it.trim()) }) {
            val grid = lines.map { row -> row.trim().trim('|').split('|').map { it.trim() } }
            val hasHeader = grid.size >= 2 && grid[1].isNotEmpty() && grid[1].all { TABLE_ALIGN_RE.matches(it) }
            val head = if (hasHeader) grid[0].map { TextSanitizer.cleanInline(it) } else emptyList()
            val bodyRows = (if (hasHeader) grid.drop(2) else grid).map { r -> r.map { TextSanitizer.cleanInline(it) } }
            val linearized = buildString {
                if (head.isNotEmpty() && bodyRows.isEmpty()) {
                    append(head.filter { it.isNotEmpty() }.joinToString("; "))
                } else {
                    bodyRows.forEachIndexed { rIdx, row ->
                        if (rIdx > 0) append('\n')
                        val parts = if (head.isNotEmpty()) {
                            row.mapIndexedNotNull { cIdx, cell ->
                                if (cell.isEmpty()) null
                                else {
                                    val h = head.getOrElse(cIdx) { "" }
                                    if (h.isNotEmpty()) "$h: $cell" else cell
                                }
                            }
                        } else {
                            row.filter { it.isNotEmpty() }
                        }
                        append(parts.joinToString("; "))
                    }
                }
            }
            out += makeBlock(
                index = out.size,
                kind = MarkdownBlockKind.TABLE,
                cleanText = linearized,
                tableHead = head,
                tableRows = bodyRows,
            )
            return
        }

        val onlyImg = IMAGE_RE.matchEntire(chunk.trim())
        if (onlyImg != null) {
            val alt = onlyImg.groupValues[1]
            val rel = onlyImg.groupValues[2]
            if (!isFootnoteIcon(rel)) {
                out += makeBlock(
                    index = out.size,
                    kind = MarkdownBlockKind.IMAGE,
                    cleanText = TextSanitizer.cleanInline(alt),
                    imageRel = rel,
                    imageAlt = TextSanitizer.cleanInline(alt),
                )
            }
            return
        }

        emitInlineParagraphsAndImages(out, joined)
    }

    private fun emitInlineParagraphsAndImages(out: MutableList<ParsedBlock>, text: String) {
        var pos = 0
        for (m in IMAGE_RE.findAll(text)) {
            val alt = m.groupValues[1]
            val rel = m.groupValues[2]
            if (isFootnoteIcon(rel)) continue
            val before = text.substring(pos, m.range.first)
            if (before.isNotBlank()) {
                val (clean, refs) = extractInlineFootnotes(before)
                if (clean.isNotEmpty() || refs.isNotEmpty()) {
                    out += makeBlock(index = out.size, kind = MarkdownBlockKind.PARAGRAPH, cleanText = clean, fnRefs = refs)
                }
            }
            out += makeBlock(
                index = out.size,
                kind = MarkdownBlockKind.IMAGE,
                cleanText = TextSanitizer.cleanInline(alt),
                imageRel = rel,
                imageAlt = TextSanitizer.cleanInline(alt),
            )
            pos = m.range.last + 1
        }
        val tail = text.substring(pos)
        if (tail.isNotBlank()) {
            val (clean, refs) = extractInlineFootnotes(tail)
            if (clean.isNotEmpty() || refs.isNotEmpty()) {
                out += makeBlock(index = out.size, kind = MarkdownBlockKind.PARAGRAPH, cleanText = clean, fnRefs = refs)
            }
        }
    }

    private fun isFootnoteIcon(rel: String): Boolean {
        val fileName = rel.substringAfterLast('/')
        val stem = fileName.substringBeforeLast('.').trim()
        return ICON_STEM_RE.matches(stem) ||
            stem.lowercase().endsWith("_icon") ||
            stem.lowercase().endsWith("-icon")
    }

    private data class ExplicitDef(
        val display: String,
        val key: String,
        val body: String,
        val backFile: String,
    )

    private fun parseExplicitFootnoteDef(text: String): ExplicitDef? {
        DEF_DBL_BRACKET_RE.matchEntire(text)?.let { m ->
            val target = cleanTarget(m.groupValues[2])
            if (isExternalUrl(target)) return null
            val (display, key) = normalizeMarker(m.groupValues[1])
            if (key.isEmpty()) return null
            return ExplicitDef(display, key, m.groupValues[3].trim(), target.substringAfterLast('/'))
        }
        DEF_MD_BACKLINK_RE.matchEntire(text)?.let { m ->
            val target = cleanTarget(m.groupValues[2])
            if (target.isEmpty() || isExternalUrl(target) || !target.lowercase().endsWith(".md")) return null
            val (display, key) = normalizeMarker(m.groupValues[1])
            val body = m.groupValues[3].trim()
            if (key.isEmpty() || body.isEmpty()) return null
            return ExplicitDef(display, key, body, target.substringAfterLast('/'))
        }
        DEF_CARET_BACKLINK_RE.matchEntire(text)?.let { m ->
            val target = cleanTarget(m.groupValues[2])
            if (target.isEmpty() || isExternalUrl(target) || !target.lowercase().endsWith(".md")) return null
            val (display, key) = normalizeMarker(m.groupValues[1])
            val body = m.groupValues[3].trim()
            if (key.isEmpty() || body.isEmpty()) return null
            return ExplicitDef(display, key, body, target.substringAfterLast('/'))
        }
        return null
    }

    fun extractInlineFootnotes(rawText: String): Pair<String, List<InlineFnRef>> {
        val source = rawText.trim()
        if (source.isEmpty()) return "" to emptyList()

        var prefix = "BCFNREF"
        while (prefix in source) {
            prefix += "X"
        }

        data class CapturedRef(
            val markerDisplay: String,
            val markerKey: String,
            val targetFile: String,
            val targetFragment: String,
        )

        val captured = mutableListOf<CapturedRef>()
        val replaced = INLINE_FOOTNOTE_RE.replace(source) { match ->
            val fields = extractMatchFields(match) ?: return@replace match.value
            val (rawLabel, rawTarget, isSglLink) = fields
            val cleanedTarget = cleanTargetFull(rawTarget)
            val filePart = cleanedTarget.substringBefore('#').trim()
            val fragmentPart = cleanedTarget.substringAfter('#', "").trim()

            if (filePart.isNotEmpty() && isExternalUrl(filePart)) return@replace match.value
            if (isSglLink && filePart.isNotEmpty() &&
                !filePart.lowercase().endsWith(".md") &&
                !filePart.lowercase().endsWith(".xhtml") &&
                !filePart.lowercase().endsWith(".html")
            ) {
                return@replace match.value
            }

            val (display, key) = normalizeMarker(rawLabel)
            if (key.isEmpty() || display.length > 20) return@replace match.value
            val idx = captured.size
            captured += CapturedRef(
                markerDisplay = display,
                markerKey = key,
                targetFile = filePart.substringAfterLast('/'),
                targetFragment = fragmentPart,
            )
            "$prefix${idx}END"
        }

        val cleaned = TextSanitizer.cleanInline(replaced)
        if (captured.isEmpty()) return cleaned to emptyList()

        val sentinelRegex = Regex("${Regex.escape(prefix)}(\\d+)END")
        val outBuilder = StringBuilder()
        val refs = mutableListOf<InlineFnRef>()
        var cursor = 0
        for (m in sentinelRegex.findAll(cleaned)) {
            outBuilder.append(cleaned, cursor, m.range.first)
            val idx = m.groupValues[1].toIntOrNull()
            if (idx != null && idx in captured.indices) {
                val cap = captured[idx]
                refs += InlineFnRef(
                    markerDisplay = cap.markerDisplay,
                    markerKey = cap.markerKey,
                    targetFile = cap.targetFile,
                    targetFragment = cap.targetFragment,
                    charOffsetInBlock = outBuilder.length,
                )
            }
            cursor = m.range.last + 1
        }
        outBuilder.append(cleaned, cursor, cleaned.length)
        return outBuilder.toString().trim() to refs
    }

    private fun extractMatchFields(match: MatchResult): Triple<String, String, Boolean>? {
        val groups = match.groups
        groups["stdMd"]?.let {
            val inner = Regex("""\[\^([0-9A-Za-z_-]{1,20})\]""").find(it.value)
            if (inner != null) return Triple(inner.groupValues[1], "", false)
        }
        groups["bareSup"]?.let {
            val raw = it.value
            return Triple(raw.substring(1, raw.length - 1), "", false)
        }
        val linkGroupNames = listOf("hatDbl", "dblHat", "hatSgl", "innerHat", "dblBr", "sglLink")
        for (name in linkGroupNames) {
            val g = groups[name] ?: continue
            val raw = g.value
            val linkRegex = Regex("""\[+(?:\^)?(?:\*\*|\*)?([^\[\]\n]+?)(?:\*\*|\*)?(?:\^)?\.?\]+\(([^)\n]*)\)""")
            val inner = linkRegex.find(raw)
            if (inner != null) {
                return Triple(inner.groupValues[1], inner.groupValues[2], name == "sglLink")
            }
        }
        return null
    }

    private fun cleanTargetFull(raw: String): String {
        var t = raw.trim()
        if (t.length >= 2 && t.startsWith("<") && t.endsWith(">")) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    private fun cleanTarget(raw: String): String =
        cleanTargetFull(raw).substringBefore('#').trim()

    private fun isExternalUrl(target: String): Boolean {
        val lower = target.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")
    }

    private fun promoteNotesIfApplicable(
        blocks: List<ParsedBlock>,
        chapterRoleHint: ChapterRole?,
    ): List<ParsedBlock> {
        val isNotesChapter = chapterRoleHint == ChapterRole.NOTES
        var inNoteSection = isNotesChapter
        return blocks.map { blk ->
            if (blk.kind == MarkdownBlockKind.HEADING) {
                if (NOTE_HEADING_RE.matches(blk.cleanText)) {
                    inNoteSection = true
                }
                return@map blk
            }
            if (blk.kind == MarkdownBlockKind.PARAGRAPH && (inNoteSection || hasInlineRefToKey(blocks, blk))) {
                val promoted = tryPromoteParagraphNote(blk, allowDot = inNoteSection)
                if (promoted != null) return@map promoted
            }
            blk
        }
    }

    private fun hasInlineRefToKey(blocks: List<ParsedBlock>, candidate: ParsedBlock): Boolean {
        val m = TIER2_PATTERNS.firstNotNullOfOrNull { it.matchEntire(candidate.cleanText) } ?: return false
        val (_, key) = normalizeMarker(m.groupValues[2])
        return blocks.any { b -> b.fnRefs.any { it.markerKey == key } }
    }

    private fun tryPromoteParagraphNote(blk: ParsedBlock, allowDot: Boolean): ParsedBlock? {
        for (pat in TIER2_PATTERNS) {
            val m = pat.matchEntire(blk.cleanText) ?: continue
            val prefixLen = m.groupValues[1].length
            val (display, key) = normalizeMarker(m.groupValues[2])
            if (key.isEmpty()) continue
            val stripped = blk.cleanText.substring(prefixLen).trim()
            val adjustedRefs = blk.fnRefs.mapNotNull { ref ->
                val newPos = ref.charOffsetInBlock - prefixLen
                if (newPos >= 0) ref.copy(charOffsetInBlock = newPos) else null
            }
            return blk.copy(
                kind = MarkdownBlockKind.FOOTNOTE,
                cleanText = stripped,
                fnMarkerDisplay = display,
                fnMarkerKey = key,
                fnRefs = adjustedRefs,
            )
        }
        if (allowDot) {
            val mDot = TIER2_DOT_RE.matchEntire(blk.cleanText) ?: PLAIN_ENDNOTE_LINE_RE.matchEntire(blk.cleanText)
            if (mDot != null) {
                val (display, key) = normalizeMarker(mDot.groupValues[mDot.groupValues.size - 2])
                val stripped = mDot.groupValues.last().trim()
                if (key.isNotEmpty() && stripped.isNotEmpty()) {
                    return blk.copy(
                        kind = MarkdownBlockKind.FOOTNOTE,
                        cleanText = stripped,
                        fnMarkerDisplay = display,
                        fnMarkerKey = key,
                        fnRefs = emptyList(),
                    )
                }
            }
        }
        return null
    }

    private fun assignHeadingPaths(blocks: List<ParsedBlock>, chapterTitle: String): List<ParsedBlock> {
        val stack = mutableListOf<Pair<Int, String>>()
        val cleanTitle = TextSanitizer.cleanInline(chapterTitle)
        if (cleanTitle.isNotEmpty()) {
            stack += 1 to cleanTitle
        }
        return blocks.map { blk ->
            if (blk.kind == MarkdownBlockKind.HEADING) {
                val lvl = blk.level ?: 2
                val headingText = blk.cleanText.trim()
                while (stack.isNotEmpty() && stack.last().first >= lvl) {
                    stack.removeAt(stack.lastIndex)
                }
                if (headingText.isNotEmpty()) {
                    stack += lvl to headingText
                }
            }
            val path = stack.joinToString(" > ") { it.second }
            blk.copy(headingPath = path)
        }
    }

    private fun makeBlock(
        index: Int,
        kind: MarkdownBlockKind,
        cleanText: String,
        level: Int? = null,
        lang: String = "",
        imageRel: String = "",
        imageAlt: String = "",
        listItems: List<String> = emptyList(),
        listItemRefs: List<List<InlineFnRef>> = emptyList(),
        tableHead: List<String> = emptyList(),
        tableRows: List<List<String>> = emptyList(),
        fnRefs: List<InlineFnRef> = emptyList(),
        fnMarkerDisplay: String = "",
        fnMarkerKey: String = "",
        fnBackFile: String = "",
    ): ParsedBlock = ParsedBlock(
        id = "B${(index + 1).toString().padStart(2, '0')}",
        blockIndex = index,
        kind = kind,
        headingPath = "",
        cleanText = cleanText,
        charStart = 0,
        charEnd = cleanText.length,
        level = level,
        lang = lang,
        imageRel = imageRel,
        imageAlt = imageAlt,
        listItems = listItems,
        listItemRefs = listItemRefs,
        tableHead = tableHead,
        tableRows = tableRows,
        fnRefs = fnRefs,
        fnMarkerDisplay = fnMarkerDisplay,
        fnMarkerKey = fnMarkerKey,
        fnBackFile = fnBackFile,
    )
}
