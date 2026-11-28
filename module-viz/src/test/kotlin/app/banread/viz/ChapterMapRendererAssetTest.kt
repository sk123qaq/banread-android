package app.banread.viz

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterMapRendererAssetTest {

    private val renderer = File("src/main/assets/viz/diagram_renderer.js").readText()

    @Test
    fun `chapter map blueprint dispatches to its dedicated renderer`() {
        assertTrue(
            renderer.contains("if (bp === 'chapter_map') return renderChapterMapHtml(payload, options);")
        )
    }

    @Test
    fun `chapter map renderer consumes section nodes and terms`() {
        val body = renderer
            .substringAfter("function renderChapterMapHtml(payload)")
            .substringBefore("function renderBlueprintBody")
        assertTrue(body.contains("data.sections"))
        assertTrue(body.contains("data.terms"))
        assertTrue(body.contains("sec.nodes"))
        assertTrue(body.contains("renderChapterMapNodeHtml"))
        assertTrue(body.contains("renderExtensionItemHtml"))
        val node = renderer.substringAfter("function renderChapterMapNodeHtml(node)")
        assertTrue(node.contains("node.summary"))
        assertTrue(node.contains("node.blocks"))
        assertTrue(node.contains("renderAnchorPills"))
    }
}
