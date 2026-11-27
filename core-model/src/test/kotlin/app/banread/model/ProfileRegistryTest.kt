package app.banread.model

import app.banread.model.registry.ProfileRegistry
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileRegistryTest {

    private val registry = ProfileRegistry.loadFromClasspath()

    @Test
    fun `loads 7 profiles with social as default under strict schema parsing`() {
        assertEquals(1, registry.schemaVersion)
        assertEquals(7, registry.profiles.size)
        assertEquals(ReadProfileId("social"), registry.defaultReadType)
        assertEquals(ReadProfileId("social"), registry.routingRules.defaultReadType)
        assertEquals(10, registry.routingRules.subjectSets.size)
        assertEquals(10, registry.routingRules.rules.size)
        assertTrue(registry.routingRules.resolvedPairs.isNotEmpty())
    }

    @Test
    fun `core-model resource and app asset profiles json are byte-identical`() {
        val coreBytes = ProfileRegistry::class.java.getResourceAsStream("/profiles.generated.json")!!
            .use { it.readAllBytes() }
        val appAssetPath = Path.of("..", "app", "src", "main", "assets", "profiles.generated.json")
            .takeIf { Files.isRegularFile(it) }
            ?: Path.of("app", "src", "main", "assets", "profiles.generated.json")
        assertTrue(Files.isRegularFile(appAssetPath), "Missing app asset file at $appAssetPath")
        assertArrayEquals(coreBytes, Files.readAllBytes(appAssetPath))
    }

    @Test
    fun `verifies 19 unique blueprint IDs across 20 slots and 13 extension IDs`() {
        val allBlueprints = registry.profiles.flatMap { it.blueprints.map { bp -> bp.blueprint } }
        assertEquals(20, allBlueprints.size)
        assertEquals(19, allBlueprints.toSet().size)

        val litBlueprints = registry.profileById(ReadProfileId("literature")).blueprints.map { it.blueprint }
        val histBlueprints = registry.profileById(ReadProfileId("history")).blueprints.map { it.blueprint }
        assertTrue("spatial_route" in litBlueprints)
        assertTrue("spatial_route" in histBlueprints)

        val allExtensions = registry.profiles.flatMap { it.extensions.map { ext -> ext.action } }
        assertEquals(13, allExtensions.size)
        assertEquals(13, allExtensions.toSet().size)
    }

    @Test
    fun `literature profile includes all 5 required tools`() {
        val lit = registry.profileById(ReadProfileId("literature"))
        val allLitTools = lit.blueprints.map { it.blueprint } + lit.extensions.map { it.action }
        assertEquals(
            listOf("tension_curve", "relation_graph", "spatial_route", "narrative_clues", "imagery"),
            allLitTools,
        )
    }

    @Test
    fun `taxonomy has 11 top-level categories and 57 subcategories plus uncertain and no_sub labels`() {
        assertEquals(11, registry.taxonomy.size)
        val subcategoryCount = registry.taxonomy.values.sumOf { it.size }
        assertEquals(57, subcategoryCount)
        assertEquals("拿不准", registry.uncertainLabel)
        assertEquals("不细分", registry.noSubLabel)
    }

    @Test
    fun `only compare_context and method_audit are marked cross_search`() {
        val allTools = registry.profiles.flatMap { profile ->
            profile.blueprints.map { it.toolId } + profile.extensions.map { it.toolId }
        }.toSet()
        val crossSearchTools = allTools.filter { registry.isCrossSearchTool(it) }.map { it.raw }.toSet()
        assertEquals(setOf("compare_context", "method_audit"), crossSearchTools)
        assertFalse(registry.isCrossSearchTool(ToolId("tension_curve")))
        assertFalse(registry.isCrossSearchTool(ToolId("historical_dossier")))
    }

    @Test
    fun `clothColor matches desktop deterministic CLOTHES formula`() {
        assertEquals("#A87A3E", registry.clothColor("B0001"))

        val clothes = ProfileRegistry.DEFAULT_CLOTH_PALETTE
        for (uid in listOf("B0001", "B0042", "B0108", "B0215", "B1800")) {
            val expected = clothes[uid.sumOf { it.code } % 8]
            assertEquals(expected, registry.clothColor(uid))
        }
    }

    @Test
    fun `resolveProfile matches every entry in resolved_pairs and edge cases`() {
        for (pair in registry.routingRules.resolvedPairs) {
            val resolved = registry.resolveProfile(pair.subject1, pair.subject2)
            assertEquals(pair.readType, resolved.profile.readType, "readType mismatch for $pair")
            assertEquals(pair.subKind, resolved.subKind, "subKind mismatch for $pair")
            assertEquals(pair.boundaryHint, resolved.boundaryHint, "boundaryHint mismatch for $pair")
        }

        val empty = registry.resolveProfile("", "")
        assertEquals(ReadProfileId("social"), empty.profile.readType)
        assertTrue(empty.boundaryHint.isNotEmpty())

        val uncertain = registry.resolveProfile("拿不准", "")
        assertEquals(ReadProfileId("social"), uncertain.profile.readType)
        assertTrue(uncertain.boundaryHint.isNotEmpty())

        val essay = registry.resolveProfile("文学", "散文随笔")
        assertEquals(ReadProfileId("literature"), essay.profile.readType)
        assertEquals("essay", essay.subKind)
        assertTrue(essay.boundaryHint.isNotEmpty())
        assertTrue(essay.subKindHint.contains("散文随笔"))

        val drama = registry.resolveProfile("文学", "戏剧")
        assertEquals("drama", drama.subKind)
        assertTrue(drama.subKindHint.contains("戏剧"))
    }
}
