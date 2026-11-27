package app.banread.viz

import app.banread.model.BlockId
import app.banread.model.VizHostEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

sealed interface VizBridgeSignal {
    data object HostReady : VizBridgeSignal
    data class RenderReady(val blueprint: String, val width: Int, val height: Int) : VizBridgeSignal
    data class Event(val hostEvent: VizHostEvent) : VizBridgeSignal
    data class Ignored(val reason: String) : VizBridgeSignal
}

/**
 * Pure Kotlin parser converting raw bridge JSON strings from `mobile_viz_host.js`
 * into [VizBridgeSignal] / [VizHostEvent] without ever throwing on malformed input.
 */
object VizBridgeMessageParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawJson: String?): VizBridgeSignal {
        if (rawJson.isNullOrBlank()) {
            return VizBridgeSignal.Ignored("Empty bridge message")
        }

        val obj = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: return VizBridgeSignal.Ignored("Malformed or non-object bridge JSON")

        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return when (type) {
            "host_ready" -> VizBridgeSignal.HostReady

            "render_ready" -> {
                val blueprint = (obj["blueprint"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val width = (obj["width"] as? JsonPrimitive)?.intOrNull ?: 0
                val height = (obj["height"] as? JsonPrimitive)?.intOrNull ?: 0
                VizBridgeSignal.RenderReady(blueprint = blueprint, width = width, height = height)
            }

            "jump" -> {
                val rawBlockId = (obj["blockId"] as? JsonPrimitive)?.contentOrNull?.trim()
                if (rawBlockId.isNullOrEmpty()) {
                    return VizBridgeSignal.Ignored("Missing blockId in jump message")
                }
                val blockId = runCatching { BlockId(rawBlockId) }.getOrNull()
                    ?: return VizBridgeSignal.Ignored("Invalid blockId format: $rawBlockId")
                VizBridgeSignal.Event(VizHostEvent.JumpToBlock(blockId))
            }

            "fullscreen" -> VizBridgeSignal.Event(VizHostEvent.OpenFullscreen)

            "error" -> {
                val message = (obj["message"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown visualization error"
                VizBridgeSignal.Event(VizHostEvent.Error(message))
            }

            else -> VizBridgeSignal.Ignored("Unknown bridge message type: $type")
        }
    }
}
