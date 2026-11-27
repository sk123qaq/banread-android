package app.banread.model.registry

import app.banread.model.ReadProfileId
import app.banread.model.ToolId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BlueprintToolMeta(
    val blueprint: String,
    val label: String,
    val description: String,
) {
    val toolId: ToolId get() = ToolId(blueprint)
}

@Serializable
data class ExtensionToolMeta(
    val action: String,
    val label: String,
    val hint: String,
    @SerialName("cross_search")
    val crossSearch: Boolean = false,
) {
    val toolId: ToolId get() = ToolId(action)
}

@Serializable
data class ReadProfile(
    @SerialName("read_type")
    val readType: ReadProfileId,
    val name: String,
    @SerialName("adler_category")
    val adlerCategory: String,
    val goal: String,
    @SerialName("sub_kind")
    val subKind: String = "",
    @SerialName("sub_kind_hint")
    val subKindHint: String = "",
    val blueprints: List<BlueprintToolMeta>,
    val extensions: List<ExtensionToolMeta>,
)

@Serializable
data class RuleOutcome(
    @SerialName("read_type")
    val readType: ReadProfileId,
    @SerialName("sub_kind")
    val subKind: String = "",
    @SerialName("boundary_hint")
    val boundaryHint: String = "",
)

@Serializable
data class Sub2Override(
    @SerialName("subject2_in")
    val subject2In: List<String>,
    @SerialName("read_type")
    val readType: ReadProfileId,
    @SerialName("sub_kind")
    val subKind: String = "",
    @SerialName("boundary_hint")
    val boundaryHint: String = "",
)

@Serializable
data class Subject1RoutingRule(
    @SerialName("subject1_in")
    val subject1In: List<String>,
    val default: RuleOutcome,
    @SerialName("sub2_overrides")
    val sub2Overrides: List<Sub2Override> = emptyList(),
)

@Serializable
data class ResolvedSubjectPair(
    val subject1: String,
    val subject2: String,
    @SerialName("read_type")
    val readType: ReadProfileId,
    @SerialName("sub_kind")
    val subKind: String = "",
    @SerialName("boundary_hint")
    val boundaryHint: String = "",
)

@Serializable
data class RoutingRulesSpec(
    @SerialName("default_read_type")
    val defaultReadType: ReadProfileId,
    @SerialName("uncertain_label")
    val uncertainLabel: String,
    @SerialName("no_sub_label")
    val noSubLabel: String,
    @SerialName("subject_sets")
    val subjectSets: Map<String, List<String>>,
    val rules: List<Subject1RoutingRule>,
    @SerialName("resolved_pairs")
    val resolvedPairs: List<ResolvedSubjectPair>,
)

@Serializable
data class ProfilesCatalogDocument(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("default_read_type")
    val defaultReadType: ReadProfileId,
    val taxonomy: Map<String, List<String>>,
    val profiles: List<ReadProfile>,
    @SerialName("literature_subkind_hints")
    val literatureSubKindHints: Map<String, String>,
    @SerialName("routing_rules")
    val routingRules: RoutingRulesSpec,
    @SerialName("cloth_palette")
    val clothPalette: List<String>? = null,
)

data class ResolvedProfile(
    val profile: ReadProfile,
    val subKind: String,
    val subKindHint: String,
    val boundaryHint: String,
    val ledgerSubject: String,
)

class ProfileRegistry private constructor(
    private val doc: ProfilesCatalogDocument,
) {
    private val profilesById: Map<String, ReadProfile> = doc.profiles.associateBy { it.readType.raw }
    private val crossSearchToolIds: Set<String> = doc.profiles
        .flatMap { it.extensions }
        .filter { it.crossSearch }
        .map { it.action }
        .toSet()

    val schemaVersion: Int get() = doc.schemaVersion
    val defaultReadType: ReadProfileId get() = doc.defaultReadType
    val clothPalette: List<String> get() = doc.clothPalette ?: DEFAULT_CLOTH_PALETTE
    val uncertainLabel: String get() = doc.routingRules.uncertainLabel
    val noSubLabel: String get() = doc.routingRules.noSubLabel
    val taxonomy: Map<String, List<String>> get() = doc.taxonomy
    val profiles: List<ReadProfile> get() = doc.profiles
    val literatureSubKindHints: Map<String, String> get() = doc.literatureSubKindHints
    val routingRules: RoutingRulesSpec get() = doc.routingRules

    fun profileById(id: ReadProfileId): ReadProfile =
        profilesById[id.raw] ?: profilesById.getValue(defaultReadType.raw)

    fun resolveProfile(subject1: String?, subject2: String? = null): ResolvedProfile {
        val cleaned = listOfNotNull(subject1, subject2)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val c1 = cleaned.getOrElse(0) { "" }
        val c2 = cleaned.getOrElse(1) { "" }

        val matchedRule = doc.routingRules.rules.firstOrNull { c1 in it.subject1In }
        val matchedOverride = if (matchedRule != null && c2.isNotEmpty()) {
            matchedRule.sub2Overrides.firstOrNull { c2 in it.subject2In }
        } else {
            null
        }

        val readType: ReadProfileId
        val subKind: String
        val boundaryHint: String
        when {
            matchedOverride != null -> {
                readType = matchedOverride.readType
                subKind = matchedOverride.subKind
                boundaryHint = matchedOverride.boundaryHint
            }
            matchedRule != null -> {
                readType = matchedRule.default.readType
                subKind = matchedRule.default.subKind
                boundaryHint = matchedRule.default.boundaryHint
            }
            else -> {
                readType = doc.routingRules.defaultReadType
                subKind = ""
                boundaryHint = ""
            }
        }

        val baseProfile = profileById(readType)
        val subKindHint = if (baseProfile.readType.raw == "literature" && subKind.isNotEmpty()) {
            doc.literatureSubKindHints[subKind].orEmpty()
        } else {
            ""
        }
        val ledgerSubject = when {
            c2.isNotEmpty() -> "$c1 / $c2"
            c1.isNotEmpty() -> c1
            else -> "未分类"
        }
        return ResolvedProfile(
            profile = baseProfile.copy(subKind = subKind, subKindHint = subKindHint),
            subKind = subKind,
            subKindHint = subKindHint,
            boundaryHint = boundaryHint,
            ledgerSubject = ledgerSubject,
        )
    }

    fun isCrossSearchTool(toolId: ToolId): Boolean = toolId.raw in crossSearchToolIds

    fun clothColor(uid: String): String {
        val palette = clothPalette
        require(palette.isNotEmpty()) { "cloth_palette must not be empty" }
        val codeSum = uid.sumOf { it.code }
        return palette[codeSum % palette.size]
    }

    companion object {
        val DEFAULT_CLOTH_PALETTE: List<String> = listOf(
            "#1E3A36",
            "#2C4056",
            "#6B2B2B",
            "#A87A3E",
            "#4A5839",
            "#3F4E5A",
            "#5A3E2B",
            "#2F4F4F",
        )

        private val strictJson = Json { ignoreUnknownKeys = false }

        fun fromJson(jsonText: String): ProfileRegistry {
            val parsed = strictJson.decodeFromString(ProfilesCatalogDocument.serializer(), jsonText)
            return ProfileRegistry(parsed)
        }

        fun loadFromClasspath(resourcePath: String = "/profiles.generated.json"): ProfileRegistry {
            val stream = ProfileRegistry::class.java.getResourceAsStream(resourcePath)
                ?: error("Missing classpath resource: $resourcePath")
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return fromJson(text)
        }
    }
}
