package app.banread.viz

import app.banread.model.AuditedArtifact
import app.banread.model.AuditedArtifactSerializer
import app.banread.model.ReaderTheme
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Pure Kotlin serializer translating all 4 [AuditedArtifact] sealed variants
 * into the JSON payload expected by `mobile_viz_host.js` and `diagram_renderer.js`.
 */
object VizPayloadSerializer {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun serializeArtifact(artifact: AuditedArtifact): String =
        json.encodeToString(AuditedArtifactSerializer, artifact)

    fun toJsonObject(artifact: AuditedArtifact): JsonObject =
        json.encodeToJsonElement(AuditedArtifactSerializer, artifact).jsonObject

    fun serializeHostEnvelope(
        artifact: AuditedArtifact,
        theme: ReaderTheme,
        viewportMode: VizViewportMode = VizViewportMode.COMPACT,
    ): String {
        val envelope = buildJsonObject {
            put("artifact", toJsonObject(artifact))
            put("theme", VizThemeTokens.toJsonObject(theme))
            put("viewportMode", JsonPrimitive(viewportMode.wireValue))
        }
        return json.encodeToString(JsonObject.serializer(), envelope)
    }
}
