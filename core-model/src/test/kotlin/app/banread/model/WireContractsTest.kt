package app.banread.model

import app.banread.model.registry.ProfileRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireContractsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun readFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
            ?: error("Missing test fixture: /fixtures/$name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `parses and round-trips bcbook manifest with merged title page and distinct hashes`() {
        val raw = readFixture("bcbook_manifest_merged_title.json")
        val manifest = json.decodeFromString(BcBookManifest.serializer(), raw)
        val registry = ProfileRegistry.loadFromClasspath()

        assertEquals(1, manifest.schemaVersion)
        assertEquals(WorkId("B0001"), manifest.uid)
        assertEquals(ReadProfileId("history"), manifest.readType)
        assertEquals(2, manifest.blockVersion)
        assertEquals(1, manifest.canonicalVersion)
        assertEquals(registry.clothColor("B0001"), manifest.clothColor)

        val ch1 = manifest.logicalChapters.first()
        assertEquals(ChapterOrdinal(1), ch1.ordinal)
        assertTrue(ch1.merged)
        assertEquals(listOf("00_扉页.md", "01_前置页.md"), ch1.sourceFiles)
        assertEquals(ChapterRole.BODY, ch1.chapterRole)
        assertNotEquals(manifest.bookSourceSha256.sha256Hex, ch1.chapterContentSha256.sha256Hex)

        val roundTripped = json.decodeFromString(
            BcBookManifest.serializer(),
            json.encodeToString(BcBookManifest.serializer(), manifest),
        )
        assertEquals(manifest, roundTripped)
    }

    @Test
    fun `parses notes-at-end fixture and enforces spoiler gate filtering for body and notes`() {
        val raw = readFixture("notes_at_end_spoiler_gate.json")
        val fixture = json.decodeFromString(NotesGateFixture.serializer(), raw)

        assertEquals(20, fixture.chapters.size)
        assertEquals(ChapterRole.NOTES, fixture.chapters.last().chapterRole)
        assertEquals(ChapterOrdinal(20), fixture.chapters.last().ordinal)

        val gate = fixture.gateAtChapter3
        assertEquals("gate:ch03", gate.cacheKey)

        val visibleBody = fixture.chapters.filter {
            it.chapterRole == ChapterRole.BODY && it.ordinal <= gate.unlockedMaxOrdinal
        }
        assertEquals(listOf(ChapterOrdinal(2), ChapterOrdinal(3)), visibleBody.map { it.ordinal })

        val visibleNotes = fixture.footnoteLinks.filter {
            it.sourceChapterOrdinal <= gate.unlockedMaxOrdinal
        }
        assertEquals(1, visibleNotes.size)
        assertEquals("fn_ch03_n45", visibleNotes.single().id)
        assertEquals(ChapterOrdinal(20), visibleNotes.single().targetChapterOrdinal)
    }

    @Test
    fun `parses all 4 diagram_audit envelopes with leaf-level provenance and blocks and round-trips`() {
        val raw = readFixture("audited_artifacts_4_envelopes.json")
        val rawArray = json.parseToJsonElement(raw).jsonArray
        for (entry in rawArray) {
            val obj = entry.jsonObject
            assertNull(obj["blocks"], "Top-level AuditedArtifact must not carry blocks")
            assertNull(obj["source"], "Top-level AuditedArtifact must not carry source")
        }

        val listSerializer = ListSerializer(AuditedArtifact.serializer())
        val artifacts = json.decodeFromString(listSerializer, raw)
        assertEquals(5, artifacts.size)

        val diagram = artifacts[0] as AuditedArtifact.StructuredDiagram
        assertEquals("tension_curve", diagram.blueprint)
        assertTrue("points" in diagram.data)

        val narrativeClues = artifacts[1] as AuditedArtifact.ExtensionDossier
        assertEquals("extension_dossier", narrativeClues.blueprint)
        assertEquals(2, narrativeClues.items.size)
        assertEquals(listOf(BlockId("B01"), BlockId("B04")), narrativeClues.items[0].blocks)
        assertEquals(Provenance.SOURCE_TEXT, narrativeClues.items[0].source)

        val historicalDossier = artifacts[2] as AuditedArtifact.ExtensionDossier
        assertEquals(2, historicalDossier.sections.size)
        assertEquals("胡惟庸", historicalDossier.sections[0].items[0].title)
        assertEquals("中书省左丞相", historicalDossier.sections[0].items[0].extraFields["role_badge"])
        assertEquals(Provenance.SUPPLEMENT, historicalDossier.sections[1].items[0].source)

        val fallback = artifacts[3] as AuditedArtifact.FallbackList
        assertEquals("fallback_list", fallback.blueprint)
        assertEquals(listOf(BlockId("B14"), BlockId("B15")), fallback.items.single().blocks)

        val mdCard = artifacts[4] as AuditedArtifact.MarkdownReadCard
        assertEquals("markdown_read_card", mdCard.blueprint)
        assertTrue(mdCard.markdown.contains("[B14]"))

        val roundTripped = json.decodeFromString(listSerializer, json.encodeToString(listSerializer, artifacts))
        assertEquals(artifacts, roundTripped)
    }

    @Test
    fun `normalizes real 8765 related search response into CrossBookHit list`() {
        val raw = readFixture("cross_search_related_response.json")
        val root = json.parseToJsonElement(raw).jsonObject
        val ranking = root["plan"]!!.jsonObject["ranking"]!!.jsonArray

        val normalized = ranking.map { item ->
            val obj = item.jsonObject
            CrossBookHit(
                uid = WorkId(obj["uid"]!!.jsonPrimitive.content),
                title = obj["title"]!!.jsonPrimitive.content,
                type = obj["type"]!!.jsonPrimitive.content,
                relevance = obj["relevance"]!!.jsonPrimitive.double,
                bestPath = obj["best_path"]!!.jsonPrimitive.content,
                bestLine = obj["best_line"]!!.jsonPrimitive.int,
                snippet = obj["best_text"]!!.jsonPrimitive.content,
            )
        }

        val expected = json.decodeFromJsonElement(
            ListSerializer(CrossBookHit.serializer()),
            root["normalized_hits"]!!,
        )
        assertEquals(expected, normalized)

        val roundTripped = json.decodeFromString(
            ListSerializer(CrossBookHit.serializer()),
            json.encodeToString(ListSerializer(CrossBookHit.serializer()), normalized),
        )
        assertEquals(expected, roundTripped)
    }

    @Test
    fun `merges multi-device progress shards monotonically and preserves note tombstones`() {
        val raw = readFixture("device_shards_concurrent_merge.json")
        val fixture = json.decodeFromString(DeviceShardsFixture.serializer(), raw)

        assertEquals(2, fixture.progressShards.size)
        val mergedWatermark = fixture.progressShards.maxOf { it.watermark }
        assertEquals(ChapterOrdinal(12), mergedWatermark)

        val desktopShard = fixture.progressShards.first { it.deviceId == DeviceId("desktop-linux") }
        assertEquals(ChapterOrdinal(2), desktopShard.cursor.ordinal)
        assertTrue(desktopShard.watermark < mergedWatermark)

        val tombstone = fixture.noteEntries.first { it.noteId == "note-desktop-02" }
        assertNotNull(tombstone.deletedAtEpochMs)

        val roundTripped = json.decodeFromString(
            DeviceShardsFixture.serializer(),
            json.encodeToString(DeviceShardsFixture.serializer(), fixture),
        )
        assertEquals(fixture, roundTripped)
    }

    @Serializable
    private data class NotesGateFixture(
        @SerialName("work_id")
        val workId: WorkId,
        @SerialName("gate_at_chapter_3")
        val gateAtChapter3: SpoilerGate.UpTo,
        val chapters: List<LogicalChapterEntry>,
        @SerialName("footnote_links")
        val footnoteLinks: List<FootnoteLink>,
    )

    @Serializable
    private data class DeviceShardsFixture(
        @SerialName("work_id")
        val workId: WorkId,
        @SerialName("progress_shards")
        val progressShards: List<DeviceProgressShard>,
        @SerialName("note_entries")
        val noteEntries: List<NoteLogEntry>,
    )
}
