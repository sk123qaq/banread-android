package app.banread.library.internal

import app.banread.model.ChapterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlockParserTest {

    @Test
    fun `parses section separators and ordered lists into distinct blocks`() {
        val parsed = MarkdownBlockParser.parse(
            """
            ## 小节标题

            第一段正文。

            ---

            1. 第一项
            2. 第二项

            - 无序一
            - 无序二

            > 引文段落

            | 列一 | 列二 |
            | --- | --- |
            | 甲 | 乙 |

            ![插图图注](images/figure.png)

            ```kotlin
            val answer = 42
            ```

            [^1]: 脚注正文
            """.trimIndent(),
            fallbackTitle = "慢读的秩序",
            chapterRoleHint = ChapterRole.BODY,
        )

        val kinds = parsed.blocks.map { it.kind }
        assertTrue("section separator should survive parsing", kinds.contains(MarkdownBlockKind.HR))
        assertTrue("numbered list should be ordered", kinds.contains(MarkdownBlockKind.ORDERED_LIST))
        assertTrue("bullet list should stay unordered", kinds.contains(MarkdownBlockKind.LIST))
        assertTrue(kinds.contains(MarkdownBlockKind.QUOTE))
        assertTrue(kinds.contains(MarkdownBlockKind.TABLE))
        assertTrue(kinds.contains(MarkdownBlockKind.IMAGE))
        assertTrue(kinds.contains(MarkdownBlockKind.CODE))
        assertTrue(kinds.contains(MarkdownBlockKind.FOOTNOTE))

        val ordered = parsed.blocks.first { it.kind == MarkdownBlockKind.ORDERED_LIST }
        assertEquals(listOf("第一项", "第二项"), ordered.listItems)
        val image = parsed.blocks.first { it.kind == MarkdownBlockKind.IMAGE }
        assertEquals("插图图注", image.imageAlt)
        val footnote = parsed.blocks.first { it.kind == MarkdownBlockKind.FOOTNOTE }
        assertEquals("1", footnote.fnMarkerDisplay)
    }
}
