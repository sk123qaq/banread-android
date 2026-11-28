package app.banread.library.internal

import app.banread.model.ChapterRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XhtmlBookCompilerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `compiles chapter hierarchy and reading blocks into xhtml`() {
        val blocks = listOf(
            block(0, MarkdownBlockKind.HEADING, "慢读的秩序", level = 1),
            block(1, MarkdownBlockKind.HEADING, "一、把速度放下来", level = 2),
            block(2, MarkdownBlockKind.PARAGRAPH, "第一段正文。"),
            block(3, MarkdownBlockKind.QUOTE, "引文也应有自己的呼吸。"),
            block(4, MarkdownBlockKind.LIST, "无序一\n无序二", listItems = listOf("无序一", "无序二")),
            block(5, MarkdownBlockKind.ORDERED_LIST, "第一项\n第二项", listItems = listOf("第一项", "第二项")),
            block(
                6,
                MarkdownBlockKind.TABLE,
                "甲 乙",
                tableHead = listOf("列一", "列二"),
                tableRows = listOf(listOf("甲", "乙")),
            ),
            block(7, MarkdownBlockKind.CODE, "val answer = 42", lang = "kotlin"),
            block(8, MarkdownBlockKind.IMAGE, "", imageRel = "images/figure.png", imageAlt = "插图图注"),
            block(9, MarkdownBlockKind.FOOTNOTE, "脚注正文", fnMarkerDisplay = "1", fnMarkerKey = "1"),
            block(10, MarkdownBlockKind.HR, ""),
            block(11, MarkdownBlockKind.PARAGRAPH, "最后一段。"),
        )
        val chapter = RoleAndSplit.FinalizedChapter(
            ordinal = 1,
            originalOrdinal = 1,
            n = "1",
            title = "慢读的秩序",
            role = ChapterRole.BODY,
            merged = false,
            sourceFiles = listOf("ch1.md"),
            contentSha256 = "",
            chars = blocks.sumOf { it.cleanText.length },
            blocks = blocks,
            virtualSplitParentOrdinal = null,
            oldToNewBlockMap = emptyMap(),
        )
        val compiled = File(tempFolder.root, "compiled")
        XhtmlBookCompiler.compile(
            compiled,
            XhtmlBookCompiler.CompileInput(
                workId = "test-work",
                title = "慢读",
                author = "测试",
                chapters = listOf(chapter),
                inlineTargets = emptyMap(),
                images = emptyMap(),
            ),
        )

        val xhtml = File(compiled, "OEBPS/ch_01.xhtml").readText()
        assertTrue(
            xhtml.contains(
                """<header class="book-chapter-head" id="B01" data-block-id="B01" data-block-index="0">""",
            ),
        )
        assertTrue(xhtml.contains("""<p class="chapter-kicker">第 1 章</p>"""))
        assertTrue(xhtml.contains("""<h1 class="book-chapter-title">慢读的秩序</h1>"""))
        assertEquals(1, Regex("<h1[ >]").findAll(xhtml).count())
        assertTrue(xhtml.contains("""<h2 class="reader-block reader-block-heading" """))
        assertTrue(xhtml.contains("""<blockquote class="reader-block reader-block-quote" """))
        assertTrue(xhtml.contains("""<ul class="reader-block reader-block-list" """))
        assertTrue(xhtml.contains("""<ol class="reader-block reader-block-list" """))
        assertTrue(xhtml.contains("""<div class="table-wrap reader-block">"""))
        assertTrue(xhtml.contains("""<figure class="reader-block reader-block-figure" """))
        assertTrue(xhtml.contains("<figcaption>插图图注</figcaption>"))
        assertTrue(xhtml.contains("""<hr class="section-break" """))
    }

    private fun block(
        index: Int,
        kind: MarkdownBlockKind,
        text: String,
        level: Int? = null,
        lang: String = "",
        imageRel: String = "",
        imageAlt: String = "",
        listItems: List<String> = emptyList(),
        tableHead: List<String> = emptyList(),
        tableRows: List<List<String>> = emptyList(),
        fnMarkerDisplay: String = "",
        fnMarkerKey: String = "",
    ): ParsedBlock = ParsedBlock(
        id = "B${(index + 1).toString().padStart(2, '0')}",
        blockIndex = index,
        kind = kind,
        headingPath = "",
        cleanText = text,
        charStart = 0,
        charEnd = text.length,
        level = level,
        lang = lang,
        imageRel = imageRel,
        imageAlt = imageAlt,
        listItems = listItems,
        tableHead = tableHead,
        tableRows = tableRows,
        fnMarkerDisplay = fnMarkerDisplay,
        fnMarkerKey = fnMarkerKey,
    )
}
