package app.banread.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val EXTENSION_BLUEPRINTS = setOf(
    "extension_dossier",
    "extension_checklist",
    "extension_glossary",
)

@Serializable(with = AuditedArtifactSerializer::class)
sealed interface AuditedArtifact {
    val blueprint: String
    val caption: String
    val readingNote: String
    val summary: String
    val auditLog: List<String>

    @Serializable
    data class StructuredDiagram(
        override val blueprint: String,
        override val caption: String,
        @SerialName("reading_note")
        override val readingNote: String = "",
        override val summary: String = "",
        @SerialName("audit_log")
        override val auditLog: List<String> = emptyList(),
        val data: JsonObject,
        val variant: String? = null,
        val action: String? = null,
    ) : AuditedArtifact

    @Serializable
    data class ExtensionDossier(
        override val blueprint: String,
        override val caption: String,
        @SerialName("reading_note")
        override val readingNote: String = "",
        override val summary: String = "",
        @SerialName("audit_log")
        override val auditLog: List<String> = emptyList(),
        val sections: List<ExtSection> = emptyList(),
        val items: List<ExtItem> = emptyList(),
        val variant: String? = null,
        val action: String? = null,
    ) : AuditedArtifact

    @Serializable
    data class FallbackList(
        override val caption: String,
        @SerialName("reading_note")
        override val readingNote: String = "",
        override val summary: String = "",
        @SerialName("audit_log")
        override val auditLog: List<String> = emptyList(),
        @SerialName("fallback_notice")
        val fallbackNotice: String,
        val items: List<FallbackItem>,
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault
        override val blueprint: String = "fallback_list",
    ) : AuditedArtifact

    @Serializable
    data class MarkdownReadCard(
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault
        override val blueprint: String = "markdown_read_card",
        override val caption: String,
        @SerialName("reading_note")
        override val readingNote: String = "",
        override val summary: String = "",
        @SerialName("audit_log")
        override val auditLog: List<String> = emptyList(),
        val markdown: String,
    ) : AuditedArtifact
}

@Serializable
data class ExtSection(
    val id: String,
    val title: String,
    val items: List<ExtItem>,
)

@Serializable(with = ExtItemSerializer::class)
data class ExtItem(
    val title: String,
    val text: String,
    val body: String,
    val detail: String,
    val blocks: List<BlockId>,
    val source: Provenance,
    val extraFields: Map<String, String> = emptyMap(),
    val checked: Boolean? = null,
)

@Serializable
data class FallbackItem(
    val label: String,
    val detail: String,
    val blocks: List<BlockId>,
    val source: Provenance,
)

object AuditedArtifactSerializer : JsonContentPolymorphicSerializer<AuditedArtifact>(AuditedArtifact::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<AuditedArtifact> {
        val obj = element.jsonObject
        val bp = obj["blueprint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when {
            bp == "fallback_list" -> AuditedArtifact.FallbackList.serializer()
            bp in EXTENSION_BLUEPRINTS -> AuditedArtifact.ExtensionDossier.serializer()
            bp == "markdown_read_card" || "markdown" in obj -> AuditedArtifact.MarkdownReadCard.serializer()
            else -> AuditedArtifact.StructuredDiagram.serializer()
        }
    }
}

object ExtItemSerializer : KSerializer<ExtItem> {
    private val reservedKeys = setOf("title", "text", "body", "detail", "blocks", "source", "checked", "extraFields")
    private val blockListSerializer = ListSerializer(BlockId.serializer())

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.banread.model.ExtItem")

    override fun deserialize(decoder: Decoder): ExtItem {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ExtItemSerializer supports JSON decoding only")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        val rawTitle = obj.stringOrEmpty("title").ifEmpty { obj.stringOrEmpty("name") }
        val rawText = obj.stringOrEmpty("text").ifEmpty { rawTitle }
        val rawBody = obj.stringOrEmpty("body")
        val rawDetail = obj.stringOrEmpty("detail").ifEmpty { rawBody }

        val blocks = obj["blocks"]?.let { el ->
            jsonDecoder.json.decodeFromJsonElement(blockListSerializer, el)
        } ?: emptyList()

        val source = obj["source"]?.let { el ->
            jsonDecoder.json.decodeFromJsonElement(Provenance.serializer(), el)
        } ?: Provenance.SOURCE_TEXT

        val checked = obj["checked"]?.jsonPrimitive?.booleanOrNull

        val extras = linkedMapOf<String, String>()
        val explicitExtras = obj["extraFields"] as? JsonObject
        if (explicitExtras != null) {
            for ((k, v) in explicitExtras) {
                (v as? JsonPrimitive)?.contentOrNull?.let { extras[k] = it }
            }
        }
        for ((k, v) in obj) {
            if (k !in reservedKeys && v is JsonPrimitive) {
                v.contentOrNull?.let { extras[k] = it }
            }
        }

        return ExtItem(
            title = rawTitle,
            text = rawText,
            body = rawBody,
            detail = rawDetail,
            blocks = blocks,
            source = source,
            extraFields = extras,
            checked = checked,
        )
    }

    override fun serialize(encoder: Encoder, value: ExtItem) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ExtItemSerializer supports JSON encoding only")
        val fields = linkedMapOf<String, JsonElement>()
        fields["title"] = JsonPrimitive(value.title)
        fields["text"] = JsonPrimitive(value.text)
        fields["body"] = JsonPrimitive(value.body)
        fields["detail"] = JsonPrimitive(value.detail)
        fields["blocks"] = JsonArray(value.blocks.map { JsonPrimitive(it.value) })
        fields["source"] = JsonPrimitive(value.source.wireValue)
        if (value.checked != null) {
            fields["checked"] = JsonPrimitive(value.checked)
        }
        for ((k, v) in value.extraFields) {
            if (k !in reservedKeys) {
                fields[k] = JsonPrimitive(v)
            }
        }
        jsonEncoder.encodeJsonElement(JsonObject(fields))
    }

    private fun JsonObject.stringOrEmpty(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
