package app.banread.viz

import app.banread.model.AuditedArtifact
import app.banread.model.BlockId
import app.banread.model.ExtItem
import app.banread.model.ExtSection
import app.banread.model.FallbackItem
import app.banread.model.Provenance
import app.banread.model.ReaderTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VizPayloadSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serializes StructuredDiagram preserving blueprint, reading_note, and data`() {
        val diagram = AuditedArtifact.StructuredDiagram(
            blueprint = "tension_curve",
            caption = "第二章戏剧张力起伏",
            readingNote = "注意密信拆封节拍",
            summary = "冲突逐步升温。",
            auditLog = listOf("字段校验通过"),
            data = buildJsonObject {
                put("yLabel", JsonPrimitive("张力强度"))
                put(
                    "points",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("beat", JsonPrimitive("码头相逢"))
                                put("beat_kind", JsonPrimitive("setup"))
                                put("tension", JsonPrimitive(35))
                                put("blocks", JsonArray(listOf(JsonPrimitive("B02"))))
                                put("source", JsonPrimitive("原文"))
                            },
                        ),
                    ),
                )
            },
        )

        val raw = VizPayloadSerializer.serializeArtifact(diagram)
        val obj = json.parseToJsonElement(raw).jsonObject
        assertEquals("tension_curve", obj["blueprint"]?.jsonPrimitive?.content)
        assertEquals("第二章戏剧张力起伏", obj["caption"]?.jsonPrimitive?.content)
        assertEquals("注意密信拆封节拍", obj["reading_note"]?.jsonPrimitive?.content)
        assertEquals("张力强度", obj["data"]?.jsonObject?.get("yLabel")?.jsonPrimitive?.content)
        assertFalse(obj.containsKey("type"))
    }

    @Test
    fun `serializes ExtensionDossier with sections, checklist state, and extraFields`() {
        val dossier = AuditedArtifact.ExtensionDossier(
            blueprint = "extension_checklist",
            caption = "行前核验清单",
            readingNote = "逐项勾选",
            sections = listOf(
                ExtSection(
                    id = "before",
                    title = "准备阶段",
                    items = listOf(
                        ExtItem(
                            title = "核对密钥指纹",
                            text = "核对密钥指纹",
                            body = "确认两端 SHA-256 一致。",
                            detail = "确认两端 SHA-256 一致。",
                            blocks = listOf(BlockId("B04"), BlockId("B05")),
                            source = Provenance.SOURCE_TEXT,
                            extraFields = mapOf("role_badge" to "安全门禁"),
                            checked = true,
                        ),
                    ),
                ),
            ),
        )

        val obj = VizPayloadSerializer.toJsonObject(dossier)
        assertEquals("extension_checklist", obj["blueprint"]?.jsonPrimitive?.content)
        val itemObj = obj["sections"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray[0].jsonObject
        assertEquals("核对密钥指纹", itemObj["title"]?.jsonPrimitive?.content)
        assertEquals("安全门禁", itemObj["role_badge"]?.jsonPrimitive?.content)
        assertEquals(true, itemObj["checked"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("原文", itemObj["source"]?.jsonPrimitive?.content)
        assertEquals("B04", itemObj["blocks"]?.jsonArray?.get(0)?.jsonPrimitive?.content)
    }

    @Test
    fun `serializes FallbackList with fallback_notice and items`() {
        val fallback = AuditedArtifact.FallbackList(
            caption = "结构化阅读要点（已安全降级）",
            readingNote = "降级说明",
            fallbackNotice = "由于原图节点超限，系统已自动降级。",
            items = listOf(
                FallbackItem(
                    label = "废相分权后的文书流转",
                    detail = "六部直接向皇帝负责。",
                    blocks = listOf(BlockId("B14"), BlockId("B15")),
                    source = Provenance.SUPPLEMENT,
                ),
            ),
        )

        val obj = VizPayloadSerializer.toJsonObject(fallback)
        assertEquals("fallback_list", obj["blueprint"]?.jsonPrimitive?.content)
        assertEquals("由于原图节点超限，系统已自动降级。", obj["fallback_notice"]?.jsonPrimitive?.content)
        val itemObj = obj["items"]!!.jsonArray[0].jsonObject
        assertEquals("废相分权后的文书流转", itemObj["label"]?.jsonPrimitive?.content)
        assertEquals("补充", itemObj["source"]?.jsonPrimitive?.content)
    }

    @Test
    fun `serializes MarkdownReadCard and host envelope cleanly`() {
        val card = AuditedArtifact.MarkdownReadCard(
            caption = "核心段落精读卡",
            readingNote = "点击锚点回跳",
            markdown = "### 核心论断\n- 制度初衷在于防范专擅 `[B14]`。",
        )

        val envelopeJson = VizPayloadSerializer.serializeHostEnvelope(
            artifact = card,
            theme = ReaderTheme.EINK_HIGH_CONTRAST,
            viewportMode = VizViewportMode.FULLSCREEN,
        )
        val env = json.parseToJsonElement(envelopeJson).jsonObject
        assertEquals("fullscreen", env["viewportMode"]?.jsonPrimitive?.content)
        assertEquals(true, env["theme"]?.jsonObject?.get("isEink")?.jsonPrimitive?.booleanOrNull)
        val artifactObj = env["artifact"]!!.jsonObject
        assertEquals("markdown_read_card", artifactObj["blueprint"]?.jsonPrimitive?.content)
        assertTrue(artifactObj["markdown"]?.jsonPrimitive?.content?.contains("[B14]") == true)
    }
}
