package app.banread.app

import app.banread.app.ui.components.isMarkdownDivider
import app.banread.app.ui.components.markdownHeadingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDisplayTest {

    @Test
    fun `divider lines are recognized and list dashes are not`() {
        assertTrue(isMarkdownDivider("---"))
        assertTrue(isMarkdownDivider("*****"))
        assertTrue(isMarkdownDivider("___"))
        assertFalse(isMarkdownDivider("--"))
        assertFalse(isMarkdownDivider("- 列表项"))
        assertFalse(isMarkdownDivider("普通段落"))
    }

    @Test
    fun `heading markers up to level six are stripped`() {
        assertEquals("本章主线", markdownHeadingText("### 本章主线"))
        assertEquals("标题", markdownHeadingText("###### 标题"))
        assertEquals("一、本章主线一句话", markdownHeadingText("#### 一、本章主线一句话"))
        assertNull(markdownHeadingText("普通段落"))
        assertNull(markdownHeadingText("#标签"))
    }
}
