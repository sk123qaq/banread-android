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
data class RoutingRule(
    val subject1: List<String>,
    val subject2: List<String>? = null,
    @SerialName("read_type")
    val readType: ReadProfileId,
    @SerialName("sub_kind")
    val subKind: String = "",
    @SerialName("boundary_hint")
    val boundaryHint: String = "",
)

@Serializable
data class ProfilesCatalogDocument(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("default_read_type")
    val defaultReadType: ReadProfileId,
    @SerialName("cloth_palette")
    val clothPalette: List<String>,
    @SerialName("uncertain_label")
    val uncertainLabel: String,
    @SerialName("no_sub_label")
    val noSubLabel: String,
    val taxonomy: Map<String, List<String>>,
    @SerialName("top_rubric")
    val topRubric: Map<String, String>,
    val profiles: List<ReadProfile>,
    @SerialName("literature_subkind_hints")
    val literatureSubKindHints: Map<String, String>,
    @SerialName("routing_rules")
    val routingRules: List<RoutingRule>,
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
    val clothPalette: List<String> get() = doc.clothPalette
    val uncertainLabel: String get() = doc.uncertainLabel
    val noSubLabel: String get() = doc.noSubLabel
    val taxonomy: Map<String, List<String>> get() = doc.taxonomy
    val topRubric: Map<String, String> get() = doc.topRubric
    val profiles: List<ReadProfile> get() = doc.profiles
    val literatureSubKindHints: Map<String, String> get() = doc.literatureSubKindHints

    fun profileById(id: ReadProfileId): ReadProfile =
        profilesById[id.raw] ?: profilesById.getValue(defaultReadType.raw)

    fun resolveProfile(subject1: String?, subject2: String? = null): ResolvedProfile {
        val cleaned = listOfNotNull(subject1, subject2)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val c1 = cleaned.getOrElse(0) { "" }
        val c2 = cleaned.getOrElse(1) { "" }

        val matchedRule = doc.routingRules.firstOrNull { rule ->
            c1 in rule.subject1 && (rule.subject2 == null || c2 in rule.subject2)
        }
        val readType = matchedRule?.readType ?: doc.defaultReadType
        val subKind = matchedRule?.subKind.orEmpty()
        val boundaryHint = matchedRule?.boundaryHint.orEmpty()
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
        require(doc.clothPalette.isNotEmpty()) { "cloth_palette must not be empty" }
        val codeSum = uid.sumOf { it.code }
        return doc.clothPalette[codeSum % doc.clothPalette.size]
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(jsonText: String): ProfileRegistry {
            val parsed = json.decodeFromString(ProfilesCatalogDocument.serializer(), jsonText)
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
