package app.banread.viz

import app.banread.model.BlockId
import app.banread.model.VizHostEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VizBridgeMessageParserTest {

    @Test
    fun `parses valid host_ready, render_ready, jump, fullscreen, and error messages`() {
        assertEquals(
            VizBridgeSignal.HostReady,
            VizBridgeMessageParser.parse("""{"type":"host_ready"}"""),
        )

        assertEquals(
            VizBridgeSignal.RenderReady(blueprint = "tension_curve", width = 344, height = 412),
            VizBridgeMessageParser.parse("""{"type":"render_ready","blueprint":"tension_curve","width":344,"height":412}"""),
        )

        assertEquals(
            VizBridgeSignal.Event(VizHostEvent.JumpToBlock(BlockId("B18"))),
            VizBridgeMessageParser.parse("""{"type":"jump","blockId":"B18"}"""),
        )

        assertEquals(
            VizBridgeSignal.Event(VizHostEvent.OpenFullscreen),
            VizBridgeMessageParser.parse("""{"type":"fullscreen"}"""),
        )

        assertEquals(
            VizBridgeSignal.Event(VizHostEvent.Error("SVG layout overflow")),
            VizBridgeMessageParser.parse("""{"type":"error","message":"SVG layout overflow"}"""),
        )
    }

    @Test
    fun `never crashes on malformed JSON, invalid BlockId, or unknown types`() {
        val malformedInputs = listOf(
            null,
            "",
            "   ",
            "{not valid json",
            "[]",
            "\"just a string\"",
            "{}",
            """{"type":"unknown_event"}""",
            """{"type":"jump"}""",
            """{"type":"jump","blockId":""}""",
            """{"type":"jump","blockId":"invalid_block"}""",
            """{"type":"jump","blockId":"B"}""",
            """{"type":"jump","blockId":"B-1"}""",
            """{"type":"jump","blockId":123}""",
        )

        for (raw in malformedInputs) {
            val signal = VizBridgeMessageParser.parse(raw)
            assertTrue("Expected Ignored for input=$raw, got $signal", signal is VizBridgeSignal.Ignored)
        }

        // Blank error message still parses safely to Error with fallback text
        val blankError = VizBridgeMessageParser.parse("""{"type":"error","message":"  "}""")
        assertTrue(blankError is VizBridgeSignal.Event && blankError.hostEvent is VizHostEvent.Error)
    }
}
