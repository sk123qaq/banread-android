package app.banread.model

import app.banread.model.registry.ProfileRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileRegistryTest {

    private val registry = ProfileRegistry.loadFromClasspath()

    @Test
    fun `loads 7 profiles with social as default`() {
        assertEquals(1, registry.schemaVersion)
        assertEquals(7, registry.profiles.size)
        assertEquals(ReadProfileId("social"), registry.defaultReadType)
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
        // sum("B0001") = 66 + 48 + 48 + 48 + 49 = 259; 259 % 8 = 3 -> "#A87A3E"
        assertEquals("#A87A3E", registry.clothColor("B0001"))

        val clothes = listOf("#1E3A36", "#2C4056", "#6B2B2B", "#A87A3E", "#4A5839", "#3F4E5A", "#5A3E2B", "#2F4F4F")
        for (uid in listOf("B0001", "B0042", "B0108", "B0215", "B1800")) {
            val expected = clothes[uid.sumOf { it.code } % 8]
            assertEquals(expected, registry.clothColor(uid))
        }
    }

    @Test
    fun `subject routing resolves subKinds and boundary hints deterministically`() {
        val drama = registry.resolveProfile("文学", "戏剧")
        assertEquals(ReadProfileId("literature"), drama.profile.readType)
        assertEquals("drama", drama.subKind)
        assertTrue(drama.subKindHint.contains("戏剧"))

        val essay = registry.resolveProfile("文学", "散文随笔")
        assertEquals("essay", essay.subKind)
        assertTrue(essay.boundaryHint.contains("散文随笔"))

        val techPractical = registry.resolveProfile("科学技术", "计算机与技术")
        assertEquals(ReadProfileId("practical"), techPractical.profile.readType)

        val techScience = registry.resolveProfile("科学技术", "物理与天文")
        assertEquals(ReadProfileId("science"), techScience.profile.readType)

        val hist = registry.resolveProfile("人文社科", "历史")
        assertEquals(ReadProfileId("history"), hist.profile.readType)

        val philo = registry.resolveProfile("人文社科", "思想与哲学")
        assertEquals(ReadProfileId("philosophy"), philo.profile.readType)

        val uncertain = registry.resolveProfile("拿不准", null)
        assertEquals(ReadProfileId("social"), uncertain.profile.readType)
        assertTrue(uncertain.boundaryHint.isNotEmpty())
    }
}
