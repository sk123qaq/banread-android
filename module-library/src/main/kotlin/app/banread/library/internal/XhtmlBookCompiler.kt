package app.banread.library.internal

import app.banread.model.ChapterOrdinal
import java.io.File

internal object XhtmlBookCompiler {

    data class CompileInput(
        val workId: String,
        val title: String,
        val author: String,
        val chapters: List<RoleAndSplit.FinalizedChapter>,
        val inlineTargets: Map<RefLookupKey, ResolvedRefTarget>,
        val images: Map<String, ByteArray>,
    )

    fun compile(compiledRoot: File, input: CompileInput) {
        if (compiledRoot.exists()) {
            compiledRoot.deleteRecursively()
        }
        val metaInfDir = File(compiledRoot, "META-INF").apply { mkdirs() }
        val oebpsDir = File(compiledRoot, "OEBPS").apply { mkdirs() }

        File(metaInfDir, "container.xml").writeText(buildContainerXml(), Charsets.UTF_8)

        val copiedImages = mutableListOf<String>()
        for ((relPath, bytes) in input.images) {
            val cleanRel = relPath.trimStart('/').let {
                if (it.startsWith("images/")) it else "images/$it"
            }
            val dest = File(oebpsDir, cleanRel)
            val destCanonical = dest.canonicalFile.toPath()
            val rootCanonical = oebpsDir.canonicalFile.toPath()
            if (!destCanonical.startsWith(rootCanonical)) continue
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            copiedImages += cleanRel
        }

        for (ch in input.chapters) {
            val chOrd = ChapterOrdinal(ch.ordinal)
            val href = "ch_${chOrd.twoDigit()}.xhtml"
            val xhtml = buildChapterXhtml(
                chapter = ch,
                bookTitle = input.title,
                inlineTargets = input.inlineTargets,
            )
            File(oebpsDir, href).writeText(xhtml, Charsets.UTF_8)
        }

        File(oebpsDir, "nav.xhtml").writeText(buildNavXhtml(input.title, input.chapters), Charsets.UTF_8)
        File(oebpsDir, "content.opf").writeText(
            buildContentOpf(
                workId = input.workId,
                title = input.title,
                author = input.author,
                chapters = input.chapters,
                imageRelPaths = copiedImages,
            ),
            Charsets.UTF_8,
        )
    }

    private fun buildContainerXml(): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""")
        appendLine("""  <rootfiles>""")
        appendLine("""    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>""")
        appendLine("""  </rootfiles>""")
        appendLine("""</container>""")
    }

    private fun buildContentOpf(
        workId: String,
        title: String,
        author: String,
        chapters: List<RoleAndSplit.FinalizedChapter>,
        imageRelPaths: List<String>,
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">""")
        appendLine("""  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
        appendLine("""    <dc:identifier id="pub-id">urn:banread:${xmlEscape(workId)}</dc:identifier>""")
        appendLine("""    <dc:title>${xmlEscape(title)}</dc:title>""")
        if (author.isNotBlank()) {
            appendLine("""    <dc:creator>${xmlEscape(author)}</dc:creator>""")
        }
        appendLine("""    <dc:language>zh</dc:language>""")
        appendLine("""  </metadata>""")
        appendLine("""  <manifest>""")
        appendLine("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
        for (ch in chapters) {
            val chOrd = ChapterOrdinal(ch.ordinal)
            val id = "ch_${chOrd.twoDigit()}"
            appendLine("""    <item id="$id" href="$id.xhtml" media-type="application/xhtml+xml"/>""")
        }
        imageRelPaths.forEachIndexed { idx, rel ->
            val mediaType = guessImageMime(rel)
            appendLine("""    <item id="img_$idx" href="${xmlEscape(rel)}" media-type="$mediaType"/>""")
        }
        appendLine("""  </manifest>""")
        appendLine("""  <spine>""")
        for (ch in chapters) {
            val chOrd = ChapterOrdinal(ch.ordinal)
            appendLine("""    <itemref idref="ch_${chOrd.twoDigit()}"/>""")
        }
        appendLine("""  </spine>""")
        appendLine("""</package>""")
    }

    private fun buildNavXhtml(
        bookTitle: String,
        chapters: List<RoleAndSplit.FinalizedChapter>,
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<!DOCTYPE html>""")
        appendLine("""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh">""")
        appendLine("""<head><meta charset="utf-8"/><title>${xmlEscape(bookTitle)}</title></head>""")
        appendLine("""<body>""")
        appendLine("""  <nav epub:type="toc" id="toc">""")
        appendLine("""    <h1>目录</h1>""")
        appendLine("""    <ol>""")
        for (ch in chapters) {
            val chOrd = ChapterOrdinal(ch.ordinal)
            val href = "ch_${chOrd.twoDigit()}.xhtml"
            val label = ch.title.ifBlank { "第 ${ch.ordinal} 章" }
            appendLine("""      <li><a href="$href">${xmlEscape(label)}</a></li>""")
        }
        appendLine("""    </ol>""")
        appendLine("""  </nav>""")
        appendLine("""</body>""")
        appendLine("""</html>""")
    }

    private fun buildChapterXhtml(
        chapter: RoleAndSplit.FinalizedChapter,
        bookTitle: String,
        inlineTargets: Map<RefLookupKey, ResolvedRefTarget>,
    ): String = buildString {
        val chOrd = ChapterOrdinal(chapter.ordinal)
        val chTitle = chapter.title.ifBlank { bookTitle }
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<!DOCTYPE html>""")
        appendLine("""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh">""")
        appendLine("""<head><meta charset="utf-8"/><title>${xmlEscape(chTitle)}</title></head>""")
        appendLine("""<body data-chapter-ordinal="${chOrd.value}" data-chapter-role="${chapter.role.name.lowercase()}">""")
        val leadingTitle = chapter.blocks.firstOrNull()?.takeIf { blk ->
            chTitle.isNotBlank() &&
                blk.kind == MarkdownBlockKind.HEADING &&
                (blk.level ?: 0) == 1 &&
                blk.cleanText.trim() == chTitle.trim()
        }
        if (chTitle.isNotBlank()) {
            val headerId = leadingTitle?.let {
                """id="${xmlEscape(it.id)}" data-block-id="${xmlEscape(it.id)}" data-block-index="${it.blockIndex}""""
            }.orEmpty()
            val headerAttrs = if (headerId.isEmpty()) "" else " $headerId"
            appendLine("""  <header class="book-chapter-head"$headerAttrs>""")
            appendLine("""    <p class="chapter-kicker">第 ${chOrd.value} 章</p>""")
            if (leadingTitle != null) {
                val inner = renderTextWithNoterefs(
                    chapter.ordinal,
                    leadingTitle.id,
                    leadingTitle.cleanText,
                    leadingTitle.fnRefs,
                    inlineTargets,
                )
                appendLine("""    <h1 class="book-chapter-title">$inner</h1>""")
            } else {
                appendLine("""    <h1 class="book-chapter-title">${xmlEscape(chTitle)}</h1>""")
            }
            appendLine("""    <p class="chapter-meta">${xmlEscape(bookTitle)} · 伴读排版</p>""")
            appendLine("""  </header>""")
        }

        appendLine("""  <div class="reader-body">""")
        for (blk in chapter.blocks) {
            if (leadingTitle != null && blk.id == leadingTitle.id) continue
            val idAttr = """id="${xmlEscape(blk.id)}" data-block-id="${xmlEscape(blk.id)}" data-block-index="${blk.blockIndex}""""
            when (blk.kind) {
                MarkdownBlockKind.HEADING -> {
                    val lvl = (blk.level ?: 2).coerceIn(1, 6)
                    val inner = renderTextWithNoterefs(chapter.ordinal, blk.id, blk.cleanText, blk.fnRefs, inlineTargets)
                    appendLine("""  <h$lvl class="reader-block reader-block-heading" $idAttr>$inner</h$lvl>""")
                }
                MarkdownBlockKind.QUOTE -> {
                    val inner = renderTextWithNoterefs(chapter.ordinal, blk.id, blk.cleanText, blk.fnRefs, inlineTargets)
                    appendLine("""  <blockquote class="reader-block reader-block-quote" $idAttr><p>$inner</p></blockquote>""")
                }
                MarkdownBlockKind.LIST, MarkdownBlockKind.ORDERED_LIST -> {
                    val listTag = if (blk.kind == MarkdownBlockKind.ORDERED_LIST) "ol" else "ul"
                    appendLine("""  <$listTag class="reader-block reader-block-list" $idAttr>""")
                    val items = blk.listItems.ifEmpty { blk.cleanText.split('\n') }
                    items.forEachIndexed { idx, itemText ->
                        val itemRefs = blk.listItemRefs.getOrNull(idx).orEmpty()
                        val inner = renderTextWithNoterefs(
                            chapterOrdinal = chapter.ordinal,
                            blockId = blk.id,
                            cleanText = itemText,
                            refs = itemRefs,
                            inlineTargets = inlineTargets,
                            listItemIndex = idx,
                        )
                        appendLine("""    <li>$inner</li>""")
                    }
                    appendLine("""  </$listTag>""")
                }
                MarkdownBlockKind.TABLE -> {
                    appendLine("""  <div class="table-wrap reader-block">""")
                    appendLine("""  <table $idAttr>""")
                    if (blk.tableHead.isNotEmpty()) {
                        append("""    <thead><tr>""")
                        for (cell in blk.tableHead) {
                            append("""<th>${xmlEscape(cell)}</th>""")
                        }
                        appendLine("""</tr></thead>""")
                    }
                    appendLine("""    <tbody>""")
                    for (row in blk.tableRows) {
                        append("""      <tr>""")
                        for (cell in row) {
                            append("""<td>${xmlEscape(cell)}</td>""")
                        }
                        appendLine("""</tr>""")
                    }
                    appendLine("""    </tbody>""")
                    appendLine("""  </table>""")
                    appendLine("""  </div>""")
                }
                MarkdownBlockKind.CODE -> {
                    val langAttr = if (blk.lang.isNotBlank()) """ class="language-${xmlEscape(blk.lang)}"""" else ""
                    appendLine("""  <pre class="reader-block reader-block-code" $idAttr><code$langAttr>${xmlEscape(blk.cleanText)}</code></pre>""")
                }
                MarkdownBlockKind.IMAGE -> {
                    val src = blk.imageRel.trimStart('/').let {
                        if (it.startsWith("images/") || it.startsWith("../")) it else "images/$it"
                    }
                    val caption = if (blk.imageAlt.isNotBlank()) {
                        """<figcaption>${xmlEscape(blk.imageAlt)}</figcaption>"""
                    } else {
                        ""
                    }
                    appendLine("""  <figure class="reader-block reader-block-figure" $idAttr><img src="${xmlEscape(src)}" alt="${xmlEscape(blk.imageAlt)}"/>$caption</figure>""")
                }
                MarkdownBlockKind.FOOTNOTE -> {
                    val marker = blk.fnMarkerDisplay.ifEmpty { blk.fnMarkerKey }
                    val inner = renderTextWithNoterefs(chapter.ordinal, blk.id, blk.cleanText, blk.fnRefs, inlineTargets)
                    appendLine(
                        """  <aside class="reader-block reader-block-footnote" $idAttr epub:type="footnote" role="doc-footnote" data-fn-marker="${xmlEscape(marker)}"><span class="fn-marker">[${xmlEscape(marker)}]</span> $inner</aside>""",
                    )
                }
                MarkdownBlockKind.HR -> {
                    appendLine("""  <hr class="section-break" $idAttr/>""")
                }
                else -> {
                    val inner = renderTextWithNoterefs(chapter.ordinal, blk.id, blk.cleanText, blk.fnRefs, inlineTargets)
                    appendLine("""  <p class="reader-block reader-block-paragraph" $idAttr>$inner</p>""")
                }
            }
        }
        appendLine("""  </div>""")
        appendLine("""</body>""")
        appendLine("""</html>""")
    }

    private fun renderTextWithNoterefs(
        chapterOrdinal: Int,
        blockId: String,
        cleanText: String,
        refs: List<InlineFnRef>,
        inlineTargets: Map<RefLookupKey, ResolvedRefTarget>,
        listItemIndex: Int = -1,
    ): String {
        if (refs.isEmpty()) return xmlEscape(cleanText)

        val perMarkerCounter = mutableMapOf<String, Int>()
        val indexedRefs = refs.map { ref ->
            val occ = perMarkerCounter.getOrDefault(ref.markerKey, 0)
            perMarkerCounter[ref.markerKey] = occ + 1
            ref to occ
        }.sortedBy { it.first.charOffsetInBlock }

        val sb = StringBuilder()
        var cursor = 0
        for ((ref, occ) in indexedRefs) {
            val offset = ref.charOffsetInBlock.coerceIn(cursor, cleanText.length)
            if (offset > cursor) {
                sb.append(xmlEscape(cleanText.substring(cursor, offset)))
            }
            val resolved = inlineTargets[RefLookupKey(chapterOrdinal, blockId, ref.markerKey, occ, listItemIndex)]
            val href = resolved?.compiledHref ?: "#fn-${xmlEscape(ref.markerKey)}"
            val markerEsc = xmlEscape(ref.markerDisplay)
            sb.append(
                """<a epub:type="noteref" role="doc-noteref" href="${xmlEscape(href)}" data-fn-marker="$markerEsc">[$markerEsc]</a>""",
            )
            cursor = offset
        }
        if (cursor < cleanText.length) {
            sb.append(xmlEscape(cleanText.substring(cursor)))
        }
        return sb.toString()
    }

    private fun guessImageMime(rel: String): String = when (rel.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private fun xmlEscape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> {
                    val code = ch.code
                    val validXmlChar = code == 0x9 || code == 0xA || code == 0xD || (code >= 0x20 && code != 0xFFFE && code != 0xFFFF)
                    if (validXmlChar) sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}
