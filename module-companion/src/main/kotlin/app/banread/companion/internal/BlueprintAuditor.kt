package app.banread.companion.internal

import app.banread.model.AuditedArtifact
import app.banread.model.BlockId
import app.banread.model.ExtItem
import app.banread.model.ExtSection
import app.banread.model.FallbackItem
import app.banread.model.Provenance
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.round

internal object BlueprintAuditor {
    const val NODE_LABEL_LIMIT = 18
    const val EDGE_LABEL_LIMIT = 6
    const val SPATIAL_EDGE_LABEL_LIMIT = 14
    const val MAX_GRAPH_NODES = 16
    const val QUADRANT_MAX_ITEMS = 4
    const val SOCIAL_METRIC_MAX_SERIES = 4
    const val SOCIAL_METRIC_MAX_POINTS = 12
    const val SOCIAL_CONCEPT_MAX_BLOCKS = 3

    private val blockIdRegex = Regex("""^B\d+$""")
    private val sourcePrefixRegex = Regex("""^(?:【(?:原文|补充)】\s*|\[(?:原文|补充)\]\s*|(?:原文|补充对照|补充|延伸)\s*[:：]\s*)+""")
    private val boundaryPrefixRegex = Regex("""^(?:公式/规律失效提示\s*[:：]\s*)+""")
    private val emptyTermValues = setOf("无", "暂无", "无对应", "无原词", "none", "n/a", "na", "-", "—", "/")
    private val timeBcRegex = Regex("""(?:公元前|前)\s*(\d{1,4})\s*年?""")
    private val timeAdRegex = Regex("""(-?\d{1,4})\s*年""")
    private val metricGuessNoteRegex = Regex("""示意|估算|中值|取中|补值|推测|推断|未见具体|未给(?:出)?具体""")
    private val metricNumRegex = Regex("""[+-]?\d+(?:\.\d+)?""")
    private val yLabelUnitRegex = Regex("""[（(]\s*([^（）()]{1,10})\s*[）)]""")
    private val matrixCloneSplitRegex = Regex("""[：:｜|]""")
    private val socialMethodCritiqueRegex = Regex("""(测量误区|测量偏差|测量局限|方法论反思|方法论局限|口径偏差|口径缺陷|统计口径之争|样本局限)""")

    private const val SPARRING_PLACEHOLDER_NOTE = "先看清作者到底在反驳谁"
    private const val BOUNDARY_PLACEHOLDER_NOTE = "公式/规律失效提示"
    private const val METHOD_PLACEHOLDER_NOTE = "切忌把局部样本直接绝对化"
    private val CHECKLIST_PLACEHOLDER_NOTES = listOf("适用前置条件", "动作转化", "行动自检清单")
    private val POSTMORTEM_PLACEHOLDER_NOTES = listOf("避坑指南", "拆解反面案例", "反例与失效复盘")

    private val ARGUMENT_ROLES = setOf("premise", "inference", "rebuttal", "conclusion", "condition")
    private val LINEAGE_ROLES = setOf("predecessor", "ally", "mediator", "target", "author")
    private val LINEAGE_RELATIONS = setOf("inherit", "transform", "critique", "contrast")
    private val FLOW_ROLES = setOf("input", "action", "gate", "pitfall", "output")
    private val DECISION_ROLES = setOf("diagnosis", "condition", "strategy", "fallback")
    private val DERIVATION_ROLES = setOf("axiom", "condition", "lemma", "theorem", "observation", "hypothesis", "experiment", "finding")
    private val DERIVATION_PARADIGMS = setOf("deductive", "experimental")
    private val SYSTEM_FLOW_ROLES = setOf("input_energy", "component", "process", "bottleneck", "output_effect")
    private val SENSITIVITY_TONES = setOf("stable", "critical", "breakdown")
    private val POWER_ROLES = setOf("sovereign", "faction", "ally", "rival", "constraint")
    private val POWER_RELATIONS = setOf("subordinate", "balance", "alliance", "hostile", "constraint")
    private val SOCIAL_CAUSAL_ROLE_SET = setOf("driver", "policy", "mechanism", "outcome", "lockin")
    private val SOCIAL_METRIC_MODES = setOf("series", "comparison", "not_applicable")
    private val SOCIAL_METRIC_MODE_ALIASES = mapOf(
        "trend" to "series",
        "time_series" to "series",
        "timeseries" to "series",
        "line" to "series",
        "compare" to "comparison",
        "bar" to "comparison",
        "bars" to "comparison",
        "grouped_bar" to "comparison",
        "na" to "not_applicable",
        "none" to "not_applicable",
        "not_applicable" to "not_applicable",
    )
    private val SPATIAL_ROLES = setOf(
        "capital", "fortress", "corridor", "neutral", "front",
        "home", "public", "threshold", "transit", "destination", "prop", "zone", "outside",
    )
    private val SPATIAL_EDGE_KINDS = setOf("advance", "flank", "defense", "diplomacy", "move", "gaze", "barrier", "parallel")
    private val BEAT_KINDS = setOf("setup", "foreshadow", "relief", "escalation", "climax", "aftermath")

    private val DEFAULT_NODE_ROLE = mapOf(
        "power_hierarchy" to "faction",
        "historical_causality" to "mechanism",
        "flow_swimlane" to "action",
        "decision_tree" to "condition",
        "derivation_dag" to "lemma",
        "system_flow" to "process",
    )

    private val LEGACY_FAMILY_TO_BLUEPRINT = mapOf(
        "flow" to "flow_swimlane",
        "tree" to "decision_tree",
        "argument" to "argument_map",
        "network" to "relation_graph",
        "timeline" to "flow_swimlane",
        "matrix" to "concept_matrix",
        "quantity" to "metric_chart",
    )

    private val EXTENSION_SECTION_META: Map<String, List<Pair<String, String>>> = mapOf(
        "checklist" to listOf(
            "before" to "启动前准备",
            "during" to "执行与核验",
            "redline" to "失效边界与踩坑红线",
        ),
        "method_audit" to listOf(
            "evidence_method" to "研究方法与证据来源",
            "identification_bias" to "因果识别与样本局限",
            "evolution_boundary" to "后续演进与反例边界",
        ),
        "sparring_dossier" to listOf(
            "debate" to "核心论战交锋",
            "thought_experiment" to "思想实验与核心隐喻",
        ),
        "counterexample_lab" to listOf(
            "hidden_premise" to "隐含未言明前提",
            "counterexample" to "哲学反例与极端压力测试",
            "modern_boundary" to "当代现实映射与适用边界",
        ),
        "boundary_check" to listOf(
            "paradigm_shift" to "旧认知推翻与范式演进",
            "intuition_analogy" to "直观类比与物理直觉",
            "breakdown_boundary" to "极端失效条件与未解边界",
        ),
    )

    fun auditRawResponse(
        rawText: String,
        validBlockIds: Set<BlockId>,
        fallbackBlockIds: List<BlockId>,
        requestedBlueprint: String = "",
        requestedAction: String = "",
    ): AuditedArtifact {
        val parsed = JsonRepair.parseObject(rawText)
        return auditJsonObject(
            parsed = parsed,
            rawText = rawText,
            validBlockIds = validBlockIds,
            fallbackBlockIds = fallbackBlockIds,
            requestedBlueprint = requestedBlueprint,
            requestedAction = requestedAction,
        )
    }

    fun auditJsonObject(
        parsed: JsonObject?,
        rawText: String = "",
        validBlockIds: Set<BlockId>,
        fallbackBlockIds: List<BlockId>,
        requestedBlueprint: String = "",
        requestedAction: String = "",
    ): AuditedArtifact {
        val validIds = validBlockIds.map { it.value }.toSet()
        val defaultBlocks = fallbackBlockIds
            .map { it.value }
            .filter { validIds.isEmpty() || it in validIds }
            .ifEmpty { validIds.sorted().take(1) }
            .ifEmpty { listOf("B01") }

        val logs = mutableListOf<String>()
        if (parsed == null) {
            return buildFallbackArtifact(
                caption = "模型输出未能解析为 JSON",
                summary = rawText.take(240),
                rawText = rawText,
                parsed = null,
                validIds = validIds,
                defaultBlocks = defaultBlocks,
                logs = logs,
            )
        }

        val caption = parsed.str("caption").trim().take(36).ifEmpty { "结构图解" }
        var readingNote = parsed.str("reading_note").trim().take(120)
        val summary = parsed.str("summary").trim()

        var bp = parsed.str("blueprint").trim()
        val legacyFamily = parsed.str("family").trim()
        if (bp.isEmpty()) {
            bp = LEGACY_FAMILY_TO_BLUEPRINT[legacyFamily] ?: requestedBlueprint.ifBlank { "flow_swimlane" }
            if (legacyFamily.isNotEmpty()) {
                logs += "兼容旧版 family='$legacyFamily'，已映射至蓝图 '$bp'"
            }
        }

        if (bp == "markdown_read_card" || ("markdown" in parsed && "data" !in parsed && "items" !in parsed && "sections" !in parsed)) {
            val md = parsed.str("markdown").ifBlank { summary }.ifBlank { rawText }
            if (logs.isEmpty()) logs += "Markdown伴读卡校验通过。"
            return AuditedArtifact.MarkdownReadCard(
                blueprint = "markdown_read_card",
                caption = caption,
                readingNote = readingNote,
                summary = summary,
                auditLog = logs,
                markdown = md,
            )
        }

        if (bp == "chapter_map") {
            val dataObj = parsed["data"] as? JsonObject ?: parsed
            val auditedMap = auditChapterMapData(dataObj, validIds, defaultBlocks, logs)
            if (auditedMap != null) {
                if (logs.isEmpty()) logs += "全章知识地图字段与锚点审计通过。"
                return AuditedArtifact.StructuredDiagram(
                    blueprint = "chapter_map",
                    caption = caption,
                    readingNote = readingNote,
                    summary = summary,
                    auditLog = logs,
                    data = auditedMap,
                )
            }
        }

        val hasExtShape = bp in setOf("extension_dossier", "extension_checklist", "extension_glossary") || (
            "data" !in parsed &&
                listOf("sections", "items", "figures", "glossary").any { parsed[it] is JsonArray }
            )
        if (hasExtShape) {
            var rawSections = parsed["sections"] as? JsonArray
            if (rawSections == null && (parsed["figures"] is JsonArray || parsed["glossary"] is JsonArray)) {
                rawSections = buildJsonArray {
                    (parsed["figures"] as? JsonArray)?.let { figs ->
                        add(buildJsonObject {
                            put("id", "figures")
                            put("title", "出场人物与阵营速查")
                            put("items", figs)
                        })
                    }
                    (parsed["glossary"] as? JsonArray)?.let { gloss ->
                        add(buildJsonObject {
                            put("id", "glossary")
                            put("title", "关键制度·条约·名物注")
                            put("items", gloss)
                        })
                    }
                }
            }

            if (rawSections != null) {
                val sections = mutableListOf<ExtSection>()
                val flatItems = mutableListOf<ExtItem>()
                rawSections.forEachIndexed { idx, secEl ->
                    val sec = secEl as? JsonObject ?: return@forEachIndexed
                    val secItems = auditExtensionItems(
                        rawItems = sec["items"] as? JsonArray,
                        bp = bp,
                        validIds = validIds,
                        defaultBlocks = defaultBlocks,
                        logs = logs,
                    )
                    if (secItems.isNotEmpty()) {
                        flatItems += secItems
                        sections += ExtSection(
                            id = sec.str("id").trim().ifEmpty { "sec-${idx + 1}" },
                            title = sec.str("title").trim().ifEmpty { "分区 ${idx + 1}" },
                            items = secItems,
                        )
                    }
                }
                if (sections.isEmpty()) {
                    return buildFallbackArtifact(caption, summary, rawText, parsed, validIds, defaultBlocks, logs)
                }
                readingNote = tailorReadingNote(readingNote, requestedAction, caption, flatItems, logs)
                if (logs.isEmpty()) logs += "扩展分区卡片校验通过。"
                return AuditedArtifact.ExtensionDossier(
                    blueprint = bp.ifEmpty { "extension_dossier" },
                    caption = caption,
                    readingNote = readingNote,
                    summary = summary,
                    auditLog = logs,
                    sections = sections,
                    items = emptyList(),
                    variant = if (requestedAction == "actor_incentives") "actor_incentives" else null,
                    action = if (requestedAction == "actor_incentives") requestedAction else null,
                )
            }

            val items = auditExtensionItems(
                rawItems = parsed["items"] as? JsonArray,
                bp = bp,
                validIds = validIds,
                defaultBlocks = defaultBlocks,
                logs = logs,
            )
            if (items.isEmpty()) {
                return buildFallbackArtifact(caption, summary, rawText, parsed, validIds, defaultBlocks, logs)
            }
            readingNote = tailorReadingNote(readingNote, requestedAction, caption, items, logs)
            if (logs.isEmpty()) logs += "扩展卡片校验通过。"
            val sections = groupExtensionSections(items, requestedAction)
            return AuditedArtifact.ExtensionDossier(
                blueprint = bp.ifEmpty { "extension_dossier" },
                caption = caption,
                readingNote = readingNote,
                summary = summary,
                auditLog = logs,
                sections = sections,
                items = items,
                variant = if (requestedAction == "actor_incentives") "actor_incentives" else null,
                action = if (requestedAction == "actor_incentives") requestedAction else null,
            )
        }

        val rawData = parsed["data"] as? JsonObject
            ?: return buildFallbackArtifact(caption, summary, rawText, parsed, validIds, defaultBlocks, logs)

        var auditedData: JsonObject? = when (bp) {
            "spatial_route" -> auditSpatialData(rawData, validIds, defaultBlocks, logs)
            in PromptCatalog.DAG_BLUEPRINTS -> auditDagData(rawData, validIds, defaultBlocks, logs, bp)
            in PromptCatalog.MATRIX_BLUEPRINTS -> auditMatrixData(rawData, validIds, defaultBlocks, logs, bp)
            "sensitivity_chart" -> auditSensitivityData(rawData, validIds, defaultBlocks, logs)
            "metric_chart" -> auditMetricData(rawData, validIds, defaultBlocks, logs)
            in PromptCatalog.CURVE_BLUEPRINTS -> auditCurveData(rawData, validIds, defaultBlocks, logs)
            "parallel_timeline" -> auditTimelineData(rawData, validIds, defaultBlocks, logs)
            "quadrant_matrix" -> auditQuadrantData(rawData, validIds, defaultBlocks, logs)
            else -> {
                val attempt = auditDagData(rawData, validIds, defaultBlocks, logs, bp)
                if (attempt != null) bp = "flow_swimlane"
                attempt
            }
        }

        if (auditedData == null) {
            return buildFallbackArtifact(caption, summary, rawText, parsed, validIds, defaultBlocks, logs)
        }

        if (logs.isEmpty()) {
            logs += "蓝图字段、文本长度与正文锚点全部审计通过。"
        }
        return AuditedArtifact.StructuredDiagram(
            blueprint = bp,
            caption = caption,
            readingNote = readingNote,
            summary = summary,
            auditLog = logs,
            data = auditedData,
            variant = if (requestedAction == "actor_incentives") "actor_incentives" else null,
            action = if (requestedAction == "actor_incentives") requestedAction else null,
        )
    }

    private fun cleanBlocks(
        rawBlocks: JsonElement?,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        hint: String,
        logs: MutableList<String>,
    ): List<BlockId> {
        val candidates = when (rawBlocks) {
            is JsonPrimitive -> listOfNotNull(rawBlocks.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
            is JsonArray -> rawBlocks.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            else -> emptyList()
        }
        val cleaned = mutableListOf<String>()
        for (bid in candidates) {
            if (!blockIdRegex.matches(bid)) {
                logs += "过滤非法块号格式 \"$bid\"（条目：$hint）"
                continue
            }
            if (validIds.isEmpty() || bid in validIds) {
                if (bid !in cleaned) cleaned += bid
            } else {
                logs += "过滤不存在块号 \"$bid\"（条目：$hint）"
            }
        }
        if (cleaned.isEmpty() && defaultBlocks.isNotEmpty()) {
            val fallback = defaultBlocks.first()
            cleaned += fallback
            if (candidates.isNotEmpty()) {
                logs += "\"$hint\" 锚点无效，已自动回退到 $fallback"
            }
        }
        return cleaned.mapNotNull { runCatching { BlockId(it) }.getOrNull() }
    }

    private fun normProvenance(raw: JsonElement?): Provenance {
        val txt = (raw as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return if (txt in setOf("补充", "ext", "external", "SUPPLEMENT")) {
            Provenance.SUPPLEMENT
        } else {
            Provenance.SOURCE_TEXT
        }
    }

    private fun stripSourcePrefix(text: String): String =
        sourcePrefixRegex.replace(text, "").trim()

    private fun cleanOriginalTerm(raw: JsonElement?): String {
        val txt = (raw as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (txt.isEmpty() || txt.lowercase() in emptyTermValues) return ""
        return txt.take(80)
    }

    private fun parseSortYear(rawVal: JsonElement?, timeLabel: String): Int? {
        val prim = rawVal as? JsonPrimitive
        if (prim != null && prim.booleanOrNull == null) {
            prim.doubleOrNull?.let { return it.toInt() }
            prim.contentOrNull?.trim()?.toDoubleOrNull()?.let { return it.toInt() }
        }
        timeBcRegex.find(timeLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return -it }
        timeAdRegex.find(timeLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun normLineageRole(raw: String): String {
        val r = raw.trim().lowercase()
        if (r in LINEAGE_ROLES) return r
        return mapOf(
            "premise" to "predecessor",
            "axiom" to "predecessor",
            "condition" to "mediator",
            "inference" to "mediator",
            "rebuttal" to "target",
            "rival" to "target",
            "conclusion" to "author",
        )[r] ?: "mediator"
    }

    private fun normLineageRelation(raw: String, label: String): String {
        val r = raw.trim().lowercase()
        if (r in LINEAGE_RELATIONS) return r
        return when {
            listOf("批", "驳", "反", "敌", "拒", "破").any { it in label } -> "critique"
            listOf("改", "转", "修", "重", "化", "发展").any { it in label } -> "transform"
            listOf("对", "比", "异", "分", "参").any { it in label } -> "contrast"
            else -> "inherit"
        }
    }

    private fun normFlowRole(raw: String): String {
        val r = raw.trim().lowercase()
        if (r in FLOW_ROLES) return r
        return mapOf(
            "premise" to "input", "axiom" to "input", "structural" to "input",
            "step" to "action", "inference" to "action", "lemma" to "action",
            "condition" to "gate", "mediator" to "gate",
            "rebuttal" to "pitfall", "risk" to "pitfall", "target" to "pitfall",
            "conclusion" to "output", "theorem" to "output", "outcome" to "output", "author" to "output",
        )[r] ?: "action"
    }

    private fun normDecisionRole(raw: String): String {
        val r = raw.trim().lowercase()
        if (r in DECISION_ROLES) return r
        return mapOf(
            "premise" to "diagnosis", "axiom" to "diagnosis", "sovereign" to "diagnosis",
            "structural" to "diagnosis", "input" to "diagnosis",
            "condition" to "condition", "inference" to "condition", "gate" to "condition", "mediator" to "condition",
            "conclusion" to "strategy", "theorem" to "strategy", "action" to "strategy", "output" to "strategy", "author" to "strategy",
            "rebuttal" to "fallback", "risk" to "fallback", "pitfall" to "fallback", "target" to "fallback",
        )[r] ?: "condition"
    }

    private fun normDerivationRole(raw: String): String {
        val r = raw.trim().lowercase()
        if (r in DERIVATION_ROLES) return r
        return mapOf(
            "premise" to "axiom", "inference" to "lemma", "conclusion" to "theorem",
            "rebuttal" to "condition", "constraint" to "condition",
            "structural" to "axiom", "trigger" to "observation", "mechanism" to "experiment", "consequence" to "finding",
        )[r] ?: "lemma"
    }

    private fun normSystemFlowRole(raw: String): String {
        val r = raw.trim().lowercase()
        if (r in SYSTEM_FLOW_ROLES) return r
        return mapOf(
            "axiom" to "input_energy", "premise" to "input_energy", "observation" to "input_energy", "trigger" to "input_energy",
            "condition" to "bottleneck", "constraint" to "bottleneck", "rebuttal" to "bottleneck",
            "inference" to "process", "lemma" to "process", "mechanism" to "process", "experiment" to "process", "step" to "process",
            "conclusion" to "output_effect", "theorem" to "output_effect", "finding" to "output_effect",
            "consequence" to "output_effect", "outcome" to "output_effect",
        )[r] ?: "process"
    }

    private fun normCausalPolarity(raw: String, label: String): String {
        val text = raw.trim().lowercase()
        if (text in setOf("+", "positive", "pos", "增强", "正向", "促进")) return "+"
        if (text in setOf("-", "negative", "neg", "抑制", "负向", "削弱")) return "-"
        val probe = "$text$label"
        return if (listOf("抑制", "削弱", "降低", "挤压", "负向", "递减", "抵消").any { it in probe }) "-" else "+"
    }

    private fun isTruthy(el: JsonElement?): Boolean {
        val p = el as? JsonPrimitive ?: return false
        p.booleanOrNull?.let { return it }
        return p.contentOrNull?.trim()?.lowercase() in setOf("true", "1", "yes", "y", "是", "滞后")
    }

    private fun auditDagData(
        rawData: JsonObject,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
        blueprint: String,
    ): JsonObject? {
        if (blueprint in setOf("lineage_dag", "flow_swimlane", "system_flow") &&
            rawData.str("mode").trim().lowercase() == "not_applicable"
        ) {
            val defaultNa = when (blueprint) {
                "flow_swimlane" -> "本章属理念综述或概念阐发，未包含可复用的先后操作步骤。"
                "system_flow" -> "本章属纯公理或概念推演，未涉及实体实验装置、物理过程或演化迭代机制。"
                else -> "本章集中展开内部论证或对话，未涉及外部思想家或学派谱系。"
            }
            val reason = rawData.str("not_applicable_reason").ifBlank { rawData.str("reason") }.trim().ifBlank { defaultNa }
            logs += "$blueprint 判定当前章节不适用该流程模型，已返回 not_applicable 提示"
            return buildJsonObject {
                put("mode", "not_applicable")
                put("reason", reason)
                put("not_applicable_reason", reason)
                put("nodes", buildJsonArray { })
                put("edges", buildJsonArray { })
            }
        }

        val rawNodes = rawData["nodes"] as? JsonArray ?: return null
        if (rawNodes.isEmpty()) return null
        if (rawNodes.size > MAX_GRAPH_NODES) {
            logs += "节点数 ${rawNodes.size} 超出单图上限 $MAX_GRAPH_NODES，触发结构化清单降级"
            return null
        }

        val fallbackRole = if (blueprint == "causal_loop") "mechanism" else DEFAULT_NODE_ROLE[blueprint] ?: "inference"
        val nodes = mutableListOf<JsonObject>()
        val nodeIds = hashSetOf<String>()
        val droppedCritique = mutableListOf<String>()

        rawNodes.forEachIndexed { idx, el ->
            val item = el as? JsonObject ?: return@forEachIndexed
            val nid = item.str("id").trim().ifEmpty { "N${idx + 1}" }
            val prov = normProvenance(item["source"])
            val rawLabel = stripSourcePrefix(item.str("label").ifBlank { item.str("title") }.ifBlank { nid })
            if (blueprint == "causal_loop" && socialMethodCritiqueRegex.containsMatchIn(rawLabel)) {
                droppedCritique += rawLabel
                return@forEachIndexed
            }
            var detail = stripSourcePrefix(item.str("detail").ifBlank { item.str("note") })
            val label = if (rawLabel.length > NODE_LABEL_LIMIT) {
                detail = if (detail.isNotEmpty()) "$rawLabel · $detail" else rawLabel
                logs += "节点 \"$nid\" 标题超过 $NODE_LABEL_LIMIT 字，已自动分流至 detail"
                rawLabel.take(NODE_LABEL_LIMIT) + "…"
            } else {
                rawLabel
            }
            val blocks = cleanBlocks(item["blocks"] ?: item["block"], validIds, defaultBlocks, label, logs)
            val rawRole = item.str("role").trim().ifEmpty { fallbackRole }
            val role = when (blueprint) {
                "argument_map" -> rawRole.lowercase().takeIf { it in ARGUMENT_ROLES } ?: "inference"
                "lineage_dag" -> normLineageRole(rawRole)
                "flow_swimlane" -> normFlowRole(rawRole)
                "decision_tree" -> normDecisionRole(rawRole)
                "derivation_dag" -> normDerivationRole(rawRole)
                "system_flow" -> normSystemFlowRole(rawRole)
                "power_hierarchy" -> if (rawRole in POWER_ROLES) rawRole else fallbackRole
                "causal_loop" -> rawRole.lowercase().takeIf { it in SOCIAL_CAUSAL_ROLE_SET } ?: "mechanism"
                else -> rawRole.ifEmpty { fallbackRole }
            }
            nodeIds += nid
            nodes += buildJsonObject {
                put("id", nid)
                put("role", role)
                put("label", label)
                put("detail", detail)
                put("blocks", blocks.toJsonArray())
                put("source", prov.wireValue)
                item.str("camp").trim().take(24).takeIf { it.isNotEmpty() }?.let { put("camp", it) }
                item.str("arc").trim().take(60).takeIf { it.isNotEmpty() }?.let { put("arc", it) }
            }
        }
        if (nodes.isEmpty()) return null
        if (droppedCritique.isNotEmpty()) {
            logs += "已剔除混入因果链的方法论评价/测量误区节点：${droppedCritique.take(3).joinToString("、")}"
        }

        val edges = mutableListOf<JsonObject>()
        (rawData["edges"] as? JsonArray)?.forEach { edgeEl ->
            val edge = edgeEl as? JsonObject ?: return@forEach
            val src = edge.str("from").trim()
            val dst = edge.str("to").trim()
            if (src !in nodeIds || dst !in nodeIds) return@forEach
            val rawLbl = edge.str("label").trim()
            var note = edge.str("note").trim()
            val shortLbl = if (rawLbl.length > EDGE_LABEL_LIMIT) {
                note = if (note.isNotEmpty()) "$rawLbl · $note" else rawLbl
                val s = rawLbl.take(EDGE_LABEL_LIMIT) + "…"
                logs += "边 \"$src→$dst\" 标签超长，已收缩为 \"$s\""
                s
            } else {
                rawLbl
            }
            edges += buildJsonObject {
                put("from", src)
                put("to", dst)
                put("label", shortLbl)
                put("note", note)
                val relType = edge.str("relation_type").trim()
                if (relType in POWER_RELATIONS) put("relation_type", relType)
                val relKind = edge.str("rel_kind").trim()
                if (relKind.isNotEmpty()) put("rel_kind", relKind)
                if (blueprint == "lineage_dag") {
                    put("relation", normLineageRelation(edge.str("relation").ifBlank { relType }, shortLbl))
                }
                if (blueprint == "causal_loop") {
                    put("polarity", normCausalPolarity(edge.str("polarity"), shortLbl))
                    if (isTruthy(edge["delay"])) put("delay", true)
                }
            }
        }

        if (blueprint == "decision_tree" && nodes.size >= 5) {
            val outDeg = edges.groupingBy { it.str("from") }.eachCount()
            if (outDeg.values.none { it >= 2 }) {
                logs += "决策树未检出多分支条件节点，已保留结构供核对"
            }
        }

        return buildJsonObject {
            put("nodes", JsonArray(nodes))
            put("edges", JsonArray(edges))
            when (blueprint) {
                "lineage_dag" -> put("mode", "lineage")
                "flow_swimlane", "system_flow" -> put("mode", "flow")
                "derivation_dag" -> {
                    val p = rawData.str("paradigm").trim().lowercase()
                    val paradigm = if (p in DERIVATION_PARADIGMS) {
                        p
                    } else {
                        val expRoles = setOf("observation", "hypothesis", "experiment", "finding")
                        if (nodes.any { it.str("role") in expRoles }) "experimental" else "deductive"
                    }
                    put("paradigm", paradigm)
                }
                "causal_loop" -> {
                    val rawLoops = rawData["loops"] as? JsonArray
                    if (rawLoops != null && rawLoops.isNotEmpty()) {
                        val loops = mutableListOf<JsonObject>()
                        rawLoops.take(4).forEachIndexed { idx, lEl ->
                            val lbl: String
                            val lid: String
                            when (lEl) {
                                is JsonPrimitive -> {
                                    lbl = lEl.contentOrNull?.trim().orEmpty()
                                    lid = ""
                                }
                                is JsonObject -> {
                                    lbl = lEl.str("label").ifBlank { lEl.str("name") }.ifBlank { lEl.str("title") }.trim()
                                    lid = lEl.str("id").trim()
                                }
                                else -> return@forEachIndexed
                            }
                            if (lbl.isNotEmpty()) {
                                loops += buildJsonObject {
                                    put("id", lid.ifEmpty { "L${idx + 1}" }.take(8))
                                    put("label", lbl.take(24))
                                }
                            }
                        }
                        if (rawLoops.size > 4) {
                            logs += "反馈环 ${rawLoops.size} 条超出上限 4，已保留前 4 条"
                        }
                        if (loops.isNotEmpty()) put("loops", JsonArray(loops))
                    }
                }
            }
        }
    }

    private fun clampPct(raw: JsonElement?, defaultVal: Double = 50.0): Double {
        val num = (raw as? JsonPrimitive)?.doubleOrNull
            ?: (raw as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: defaultVal
        val clamped = num.coerceIn(0.0, 100.0)
        return round(clamped * 10.0) / 10.0
    }

    private fun auditSpatialData(
        rawData: JsonObject,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
    ): JsonObject? {
        val mode = rawData.str("mode").trim().lowercase()
        val rawNodes = rawData["nodes"] as? JsonArray
        if (mode == "not_applicable" || (rawData.str("not_applicable_reason").isNotBlank() && (rawNodes == null || rawNodes.isEmpty()))) {
            val reason = rawData.str("not_applicable_reason").trim()
                .ifEmpty { "本章无显著物理空间转换、地缘约束或舞台调度，无需强行绘制空间图。" }
                .take(180)
            return buildJsonObject {
                put("mode", "not_applicable")
                put("not_applicable_reason", reason)
                put("nodes", buildJsonArray { })
                put("edges", buildJsonArray { })
            }
        }

        val litMode = mode in setOf("route_map", "stage_blocking")
        val hasCoords = rawNodes?.any { el ->
            val nd = el as? JsonObject ?: return@any false
            nd["x"] != null && nd["y"] != null && nd["x"] !is JsonNull && nd["y"] !is JsonNull
        } == true

        if (!litMode && !hasCoords) {
            if (rawNodes == null || rawNodes.isEmpty()) return null
            logs += "兼容旧版无坐标空间图缓存，按拓扑模式渲染"
            return auditDagData(rawData, validIds, defaultBlocks, logs, "spatial_route")
        }

        if (rawNodes == null || rawNodes.isEmpty()) return null
        if (rawNodes.size > MAX_GRAPH_NODES) {
            logs += "空间节点数 ${rawNodes.size} 超出单图上限 $MAX_GRAPH_NODES，触发结构化清单降级"
            return null
        }

        val nodes = mutableListOf<JsonObject>()
        val nodeIds = hashSetOf<String>()
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()

        rawNodes.forEachIndexed { idx, el ->
            val item = el as? JsonObject ?: return@forEachIndexed
            val rawX = item["x"] as? JsonPrimitive
            val rawY = item["y"] as? JsonPrimitive
            val x: Double
            val y: Double
            if (!litMode) {
                val parsedX = rawX?.takeIf { it.booleanOrNull == null }?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
                val parsedY = rawY?.takeIf { it.booleanOrNull == null }?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
                if (parsedX == null || parsedY == null) {
                    logs += "空间节点 ${item.str("id").ifBlank { "${idx + 1}" }} 坐标缺失或非数字，已跳过"
                    return@forEachIndexed
                }
                x = clampPct(JsonPrimitive(parsedX))
                y = clampPct(JsonPrimitive(parsedY))
            } else {
                x = clampPct(rawX, 18.0 + (idx % 4) * 21.0)
                y = clampPct(rawY, 20.0 + (idx / 4) * 28.0)
            }

            val nid = item.str("id").trim().ifEmpty { "S${idx + 1}" }
            val prov = normProvenance(item["source"])
            val rawLabel = stripSourcePrefix(item.str("label").ifBlank { item.str("title") }.ifBlank { nid })
            var detail = stripSourcePrefix(item.str("detail").ifBlank { item.str("note") })
            val label = if (rawLabel.length > NODE_LABEL_LIMIT) {
                detail = if (detail.isNotEmpty()) "$rawLabel · $detail" else rawLabel
                logs += "空间节点 \"$nid\" 标题超过 $NODE_LABEL_LIMIT 字，已自动分流至 detail"
                rawLabel.take(NODE_LABEL_LIMIT) + "…"
            } else {
                rawLabel
            }
            val blocks = cleanBlocks(item["blocks"] ?: item["block"], validIds, defaultBlocks, label, logs)
            val defaultRole = when {
                mode == "stage_blocking" -> "zone"
                litMode -> "public"
                else -> "corridor"
            }
            val role = item.str("role").trim().takeIf { it in SPATIAL_ROLES } ?: defaultRole
            val zone = item.str("zone").trim().take(20)
            nodeIds += nid
            xs += x
            ys += y
            nodes += buildJsonObject {
                put("id", nid)
                put("label", label)
                put("x", x)
                put("y", y)
                put("role", role)
                put("detail", detail)
                put("blocks", blocks.toJsonArray())
                put("source", prov.wireValue)
                if (zone.isNotEmpty() || !litMode) put("zone", zone)
            }
        }

        if (nodes.isEmpty()) return null
        if (nodes.size >= 3 && (xs.max() - xs.min() < 8.0) && (ys.max() - ys.min() < 8.0)) {
            logs += "空间节点坐标过度聚拢且缺少二维分布，触发结构化清单降级"
            return null
        }

        val edgeLimit = if (!litMode) SPATIAL_EDGE_LABEL_LIMIT else EDGE_LABEL_LIMIT
        val defaultKind = if (litMode) "move" else "advance"
        val edges = mutableListOf<JsonObject>()
        (rawData["edges"] as? JsonArray)?.forEach { edgeEl ->
            val edge = edgeEl as? JsonObject ?: return@forEach
            val src = edge.str("from").trim()
            val dst = edge.str("to").trim()
            if (src !in nodeIds || dst !in nodeIds) return@forEach
            val rawLbl = edge.str("label").trim()
            var note = edge.str("note").trim()
            val shortLbl = if (rawLbl.length > edgeLimit) {
                note = if (note.isNotEmpty()) "$rawLbl · $note" else rawLbl
                val s = rawLbl.take(edgeLimit) + "…"
                if (litMode) logs += "边 \"$src→$dst\" 标签超长，已收缩为 \"$s\""
                s
            } else {
                rawLbl
            }
            val kind = edge.str("kind").trim().takeIf { it in SPATIAL_EDGE_KINDS } ?: defaultKind
            val routeGroup = edge.str("route_group").trim()
            val actor = edge.str("actor").trim().take(16)
            edges += buildJsonObject {
                put("from", src)
                put("to", dst)
                put("label", shortLbl)
                put("note", note)
                put("kind", kind)
                if (routeGroup.isNotEmpty() || !litMode) put("route_group", routeGroup)
                if (actor.isNotEmpty()) put("actor", actor)
            }
        }

        val cleanMode = if (mode in setOf("campaign", "geopolitics", "route_map", "stage_blocking")) mode else "geopolitics"
        return buildJsonObject {
            put("mode", cleanMode)
            put("nodes", JsonArray(nodes))
            put("edges", JsonArray(edges))
            rawData.str("scope_note").trim().takeIf { it.isNotEmpty() }?.let { put("scope_note", it) }
            rawData.str("stage_desc").trim().take(60).takeIf { it.isNotEmpty() }?.let { put("stage_desc", it) }
        }
    }

    private fun auditMatrixData(
        rawData: JsonObject,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
        blueprint: String,
    ): JsonObject? {
        var cols = (rawData["columns"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()
        if (cols.isEmpty() && blueprint == "concept_matrix") {
            cols = listOf("学派/学者/政策立场", "核心界定", "关键机制", "盲区/反例", "出处")
        }
        val rawRows = rawData["rows"] as? JsonArray
        if (cols.isEmpty() || rawRows == null || rawRows.isEmpty()) return null

        var rows = mutableListOf<JsonObject>()
        for (rowEl in rawRows) {
            when (rowEl) {
                is JsonObject -> {
                    val prov = normProvenance(rowEl["source"])
                    val key = stripSourcePrefix(rowEl.str("key").ifBlank { rowEl.str("term") })
                    val origTerm = cleanOriginalTerm(rowEl["original_term"] ?: rowEl["orig_term"])
                    val everyday = stripSourcePrefix(rowEl.str("everyday").ifBlank { rowEl.str("col1") })
                    val author = stripSourcePrefix(rowEl.str("author").ifBlank { rowEl.str("col2") })
                    val extra = stripSourcePrefix(rowEl.str("extra").ifBlank { rowEl.str("col3") })
                    val blocks = cleanBlocks(rowEl["blocks"], validIds, defaultBlocks, key.ifEmpty { "矩阵行" }, logs)
                    rows += buildJsonObject {
                        put("key", key)
                        put("everyday", everyday)
                        put("author", author)
                        put("extra", extra)
                        put("blocks", blocks.toJsonArray())
                        put("source", prov.wireValue)
                        if (origTerm.isNotEmpty() || "original_term" in rowEl || "orig_term" in rowEl) {
                            put("original_term", origTerm)
                        }
                    }
                }
                is JsonArray -> if (rowEl.isNotEmpty()) {
                    val cells = rowEl.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    rows += buildJsonObject {
                        put("key", cells[0])
                        put("everyday", cells.getOrElse(1) { "" })
                        put("author", cells.getOrElse(2) { "" })
                        put("extra", if (cells.size > 3) cells.drop(3).joinToString(" | ") else "")
                        put("blocks", buildJsonArray { defaultBlocks.firstOrNull()?.let { add(JsonPrimitive(it)) } })
                        put("source", Provenance.SOURCE_TEXT.wireValue)
                    }
                }
                else -> Unit
            }
        }
        if (rows.isEmpty()) return null
        if (blueprint == "concept_matrix") {
            rows = tidyConceptMatrixRows(rows, logs).toMutableList()
        }
        return buildJsonObject {
            put("columns", JsonArray(cols.map { JsonPrimitive(it) }))
            put("rows", JsonArray(rows))
        }
    }

    private fun tidyConceptMatrixRows(rows: List<JsonObject>, logs: MutableList<String>): List<JsonObject> {
        var trimmed = 0
        val afterTrim = rows.map { row ->
            val blocks = row["blocks"] as? JsonArray
            if (blocks != null && blocks.size > SOCIAL_CONCEPT_MAX_BLOCKS) {
                trimmed++
                buildJsonObject {
                    row.forEach { (k, v) ->
                        if (k == "blocks") put(k, JsonArray(blocks.take(SOCIAL_CONCEPT_MAX_BLOCKS))) else put(k, v)
                    }
                }
            } else {
                row
            }
        }
        if (trimmed > 0) {
            logs += "概念矩阵有 $trimmed 行块号超过 $SOCIAL_CONCEPT_MAX_BLOCKS 个，已收缩到最核心的 $SOCIAL_CONCEPT_MAX_BLOCKS 个"
        }
        val pairs = afterTrim.map { matrixCloneSplitRegex.split(it.str("key"), limit = 2) }
        val prefixes = pairs.filter { it.size == 2 && it[0].trim().isNotEmpty() && it[1].trim().isNotEmpty() }.map { it[0].trim() }
        if (afterTrim.size >= 4 && prefixes.size == afterTrim.size && prefixes.toSet().size == 1) {
            logs += "概念矩阵 ${afterTrim.size} 行均以同一小节名「${prefixes[0]}：」开头，已剥离公共前缀"
            return afterTrim.zip(pairs) { row, pair ->
                buildJsonObject {
                    row.forEach { (k, v) ->
                        if (k == "key") put("key", pair[1].trim().take(24)) else put(k, v)
                    }
                }
            }
        }
        return afterTrim
    }

    private fun auditCurveData(
        rawData: JsonObject,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
    ): JsonObject? {
        var rawPoints = rawData["points"] as? JsonArray
        if (rawPoints == null) {
            val items = rawData["items"] as? JsonArray
            if (items != null) {
                rawPoints = buildJsonArray {
                    items.forEachIndexed { idx, el ->
                        val it = el as? JsonObject ?: return@forEachIndexed
                        add(
                            buildJsonObject {
                                put("beat", it.str("label").ifBlank { "点${idx + 1}" })
                                put("tension", (it["value"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
                                put("emotion", it.str("note").ifBlank { it.str("label") })
                                it["blocks"]?.let { b -> put("blocks", b) }
                                it["source"]?.let { s -> put("source", s) }
                            },
                        )
                    }
                }
            }
        }
        if (rawPoints == null || rawPoints.isEmpty()) return null

        val points = mutableListOf<JsonObject>()
        rawPoints.forEachIndexed { idx, el ->
            val pt = el as? JsonObject ?: return@forEachIndexed
            val prov = normProvenance(pt["source"])
            val beat = stripSourcePrefix(pt.str("beat").ifBlank { "节拍${idx + 1}" }).take(12)
            val rawVal = pt["tension"] ?: pt["value"]
            val tension = ((rawVal as? JsonPrimitive)?.doubleOrNull
                ?: (rawVal as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: 50.0).coerceIn(0.0, 100.0)
            val emotion = stripSourcePrefix(pt.str("emotion").ifBlank { pt.str("note") }).take(28)
            val note = stripSourcePrefix(pt.str("note")).take(120)
            val beatKind = pt.str("beat_kind").trim().lowercase().takeIf { it in BEAT_KINDS }.orEmpty()
            val blocks = cleanBlocks(pt["blocks"], validIds, defaultBlocks, beat, logs)
            points += buildJsonObject {
                put("beat", beat)
                put("tension", tension)
                put("emotion", emotion)
                put("blocks", blocks.toJsonArray())
                put("source", prov.wireValue)
                if (beatKind.isNotEmpty()) put("beat_kind", beatKind)
                if (note.isNotEmpty() && note != emotion) put("note", note)
            }
        }
        if (points.isEmpty()) return null
        return buildJsonObject {
            put("yLabel", rawData.str("yLabel").ifBlank { "强度" })
            put("points", JsonArray(points))
        }
    }

    private fun normSensitivityTone(rawTone: String, state: String = ""): String {
        val t = rawTone.trim().lowercase()
        if (t in SENSITIVITY_TONES) return t
        val txt = "$t $state"
        return when {
            listOf("崩溃", "失效", "坍缩", "灭绝", "消亡", "奇点", "breakdown").any { it in txt } -> "breakdown"
            listOf("临界", "阈值", "极限", "相变", "转折", "critical").any { it in txt } -> "critical"
            else -> "stable"
        }
    }

    private fun auditSensitivityData(
        rawData: JsonObject,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
    ): JsonObject? {
        val mode = rawData.str("mode").trim().lowercase()
        val rawRegimes = rawData["regimes"] as? JsonArray
        val rawPoints = rawData["points"] as? JsonArray

        if (mode == "not_applicable" || (rawData.str("not_applicable_reason").isNotBlank() && rawRegimes == null && rawPoints == null)) {
            val reason = rawData.str("not_applicable_reason").ifBlank { rawData.str("reason") }
                .trim().ifEmpty { "本章集中展开定性公理或概念推演，未出现定量参数响应或相变临界阈值。" }.take(200)
            val highlights = mutableListOf<JsonObject>()
            (rawData["highlights"] as? JsonArray)?.forEachIndexed { idx, el ->
                val hl = el as? JsonObject ?: return@forEachIndexed
                val prov = normProvenance(hl["source"])
                val title = stripSourcePrefix(hl.str("title").ifBlank { hl.str("label") }.ifBlank { "定性制约 ${idx + 1}" }).take(24)
                val detail = stripSourcePrefix(hl.str("detail").ifBlank { hl.str("body") }.ifBlank { hl.str("note") }).take(90)
                val blocks = cleanBlocks(hl["blocks"], validIds, defaultBlocks, title, logs)
                highlights += buildJsonObject {
                    put("title", title)
                    put("detail", detail)
                    put("blocks", blocks.toJsonArray())
                    put("source", prov.wireValue)
                }
            }
            logs += "本章无定量曲线或临界阈值，已按 not_applicable 呈现定性制约"
            return buildJsonObject {
                put("mode", "not_applicable")
                put("reason", reason)
                put("not_applicable_reason", reason)
                put("highlights", JsonArray(highlights))
            }
        }

        if (mode == "regime_threshold" || (rawRegimes != null && rawRegimes.isNotEmpty())) {
            if (rawRegimes == null || rawRegimes.isEmpty()) return null
            val parameter = rawData.str("parameter").trim().ifEmpty { "控制参数" }.take(40)
            val regimes = mutableListOf<JsonObject>()
            rawRegimes.forEachIndexed { idx, el ->
                val rg = el as? JsonObject ?: return@forEachIndexed
                val prov = normProvenance(rg["source"])
                val rng = stripSourcePrefix(rg.str("range").ifBlank { rg.str("condition") }.ifBlank { "区间 ${idx + 1}" }).take(28)
                val threshold = stripSourcePrefix(rg.str("threshold")).take(28)
                val state = stripSourcePrefix(rg.str("state").ifBlank { rg.str("outcome") }).take(24)
                val mechanism = stripSourcePrefix(rg.str("mechanism").ifBlank { rg.str("detail") }).take(100)
                if (state.isEmpty() && mechanism.isEmpty()) return@forEachIndexed
                val blocks = cleanBlocks(rg["blocks"], validIds, defaultBlocks, rng, logs)
                regimes += buildJsonObject {
                    put("range", rng)
                    put("threshold", threshold)
                    put("state", state.ifEmpty { rng })
                    put("mechanism", mechanism)
                    put("tone", normSensitivityTone(rg.str("tone"), "$state $threshold"))
                    put("blocks", blocks.toJsonArray())
                    put("source", prov.wireValue)
                }
            }
            if (regimes.isEmpty()) return null
            return buildJsonObject {
                put("mode", "regime_threshold")
                put("parameter", parameter)
                put("regimes", JsonArray(regimes))
            }
        }

        if (rawPoints != null && rawPoints.isNotEmpty()) {
            val yLabel = rawData.str("yLabel").trim()
            val xLabel = rawData.str("xLabel").trim()
            val unit = rawData.str("unit").trim().take(20)
            val hasRealXy = rawPoints.any { el ->
                val pt = el as? JsonObject ?: return@any false
                "y" in pt || "x" in pt
            }
            val looksFakePct = !hasRealXy && ("变化幅度" in yLabel || yLabel in setOf("", "强度", "变化幅度(%)"))
            if (looksFakePct) {
                val converted = mutableListOf<JsonObject>()
                rawPoints.forEachIndexed { idx, el ->
                    val pt = el as? JsonObject ?: return@forEachIndexed
                    val prov = normProvenance(pt["source"])
                    val rng = stripSourcePrefix(pt.str("beat").ifBlank { "档位 ${idx + 1}" }).take(28)
                    val state = stripSourcePrefix(pt.str("emotion").ifBlank { rng }).take(24)
                    val mech = stripSourcePrefix(pt.str("note")).take(100)
                    val blocks = cleanBlocks(pt["blocks"], validIds, defaultBlocks, rng, logs)
                    converted += buildJsonObject {
                        put("range", rng)
                        put("threshold", "")
                        put("state", state)
                        put("mechanism", mech)
                        put("tone", normSensitivityTone("", state))
                        put("blocks", blocks.toJsonArray())
                        put("source", prov.wireValue)
                    }
                }
                if (converted.isNotEmpty()) {
                    logs += "检测到无实测单位的 0–100 假分数，已剥除伪量化并转为临界分档视图"
                    return buildJsonObject {
                        put("mode", "regime_threshold")
                        put("parameter", xLabel.ifEmpty { "参数变化档位" })
                        put("regimes", JsonArray(converted))
                    }
                }
                return null
            }

            val points = mutableListOf<JsonObject>()
            rawPoints.forEachIndexed { idx, el ->
                val pt = el as? JsonObject ?: return@forEachIndexed
                val prov = normProvenance(pt["source"])
                val xVal = stripSourcePrefix(pt.str("x").ifBlank { pt.str("beat") }.ifBlank { "点${idx + 1}" }).take(24)
                val rawY = (pt["y"] ?: pt["value"]) as? JsonPrimitive ?: return@forEachIndexed
                if (rawY.booleanOrNull != null) return@forEachIndexed
                val yVal = rawY.doubleOrNull ?: rawY.contentOrNull?.toDoubleOrNull() ?: return@forEachIndexed
                val note = stripSourcePrefix(pt.str("note").ifBlank { pt.str("emotion") }).take(100)
                val blocks = cleanBlocks(pt["blocks"], validIds, defaultBlocks, xVal, logs)
                points += buildJsonObject {
                    put("x", xVal)
                    put("y", yVal)
                    put("note", note)
                    put("blocks", blocks.toJsonArray())
                    put("source", prov.wireValue)
                }
            }
            if (points.isEmpty()) return null
            return buildJsonObject {
                put("mode", "numeric_curve")
                put("xLabel", xLabel.ifEmpty { "自变量" })
                put("yLabel", yLabel.ifEmpty { "响应量" })
                put("unit", unit)
                put("points", JsonArray(points))
            }
        }
        return null
    }

    private fun metricNumber(raw: JsonElement?): Double? {
        val prim = raw as? JsonPrimitive ?: return null
        if (prim.booleanOrNull != null) return null
        val num = prim.doubleOrNull ?: run {
            val cleaned = prim.contentOrNull?.trim()
                ?.replace(",", "")
                ?.replace("，", "")
                ?.replace("％", "%")
                ?: return null
            val match = metricNumRegex.find(cleaned) ?: return null
            match.value.toDoubleOrNull() ?: return null
        }
        if (!num.isFinite()) return null
        return round(num * 10_000.0) / 10_000.0
    }

    private fun auditMetricData(
        rawData: JsonObject,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
    ): JsonObject? {
        val rawMode = rawData.str("mode").trim().lowercase()
        var mode = SOCIAL_METRIC_MODE_ALIASES[rawMode] ?: rawMode
        if (mode !in SOCIAL_METRIC_MODES) {
            mode = when {
                rawData["series"] is JsonArray || rawData["points"] is JsonArray -> "series"
                rawData["rows"] is JsonArray -> "comparison"
                rawData.str("reason").isNotBlank() || rawData.str("not_applicable_reason").isNotBlank() -> "not_applicable"
                else -> "series"
            }
        }

        if (mode == "not_applicable") {
            val reason = rawData.str("reason").ifBlank { rawData.str("not_applicable_reason") }.ifBlank { rawData.str("summary") }.trim()
            if (reason.isEmpty()) {
                logs += "not_applicable 缺少 reason，无法诚实退场，已转安全降级"
                return null
            }
            val highlights = mutableListOf<JsonObject>()
            val rawHighlights = (rawData["highlights"] as? JsonArray) ?: (rawData["items"] as? JsonArray)
            rawHighlights?.forEach { el ->
                if (highlights.size >= 4) return@forEach
                val item = el as? JsonObject ?: return@forEach
                val prov = normProvenance(item["source"])
                val txt = stripSourcePrefix(item.str("text").ifBlank { item.str("title") }.ifBlank { item.str("label") })
                if (txt.isEmpty()) return@forEach
                val blocks = cleanBlocks(item["blocks"] ?: item["block"], validIds, defaultBlocks, txt.take(10), logs)
                highlights += buildJsonObject {
                    put("text", txt.take(40))
                    put("blocks", blocks.toJsonArray())
                    put("source", prov.wireValue)
                }
            }
            if (highlights.isEmpty()) {
                logs += "not_applicable 缺少定性史例锚点，已补本章默认块号"
                highlights += buildJsonObject {
                    put("text", "本章以定性论述为主，无可比量化统计")
                    put("blocks", buildJsonArray { defaultBlocks.firstOrNull()?.let { add(JsonPrimitive(it)) } })
                    put("source", Provenance.SOURCE_TEXT.wireValue)
                }
            }
            logs += "本章未提供可对比的量化统计数据，已诚实返回 not_applicable"
            return buildJsonObject {
                put("mode", "not_applicable")
                put("reason", reason.take(160))
                put("highlights", JsonArray(highlights))
                put("yLabel", "")
                put("unit", "")
                put("series", buildJsonArray { })
            }
        }

        val rawYLabel = stripSourcePrefix(rawData.str("yLabel").ifBlank { rawData.str("ylabel") }.ifBlank { rawData.str("y_label") }).trim().take(24)
        var unit = rawData.str("unit").trim().take(12)
        if (unit.isEmpty()) {
            yLabelUnitRegex.find(rawYLabel)?.groupValues?.getOrNull(1)?.trim()?.let { unit = it }
        }
        val yLabel = rawYLabel.ifEmpty { "指标" }

        var rawSeries: List<JsonObject> = when (val s = rawData["series"]) {
            is JsonArray -> s.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(s)
            else -> emptyList()
        }
        if (rawSeries.isEmpty() && rawData["points"] is JsonArray) {
            val legacyPts = (rawData["points"] as JsonArray).mapNotNull { el ->
                val pt = el as? JsonObject ?: return@mapNotNull null
                buildJsonObject {
                    put("x", pt.str("beat").ifBlank { pt.str("x") }.ifBlank { pt.str("label") })
                    (pt["value"] ?: pt["tension"])?.let { put("value", it) }
                    put("note", pt.str("note").ifBlank { pt.str("emotion") })
                    pt["blocks"]?.let { put("blocks", it) }
                    pt["source"]?.let { put("source", it) }
                }
            }
            rawSeries = listOf(
                buildJsonObject {
                    put("name", rawData.str("seriesName").ifBlank { "指标" })
                    put("points", JsonArray(legacyPts))
                },
            )
            logs += "检测到旧缓存 points/beat/tension 结构，已按真实数值转为 series"
        }

        val auditedSeries = mutableListOf<JsonObject>()
        var totalPoints = 0
        rawSeries.take(SOCIAL_METRIC_MAX_SERIES).forEachIndexed { sIdx, rawS ->
            val rawPoints = rawS["points"] as? JsonArray ?: return@forEachIndexed
            val points = mutableListOf<JsonObject>()
            rawPoints.take(SOCIAL_METRIC_MAX_POINTS).forEachIndexed { pIdx, pEl ->
                val pt = pEl as? JsonObject ?: return@forEachIndexed
                val prov = normProvenance(pt["source"])
                val label = pt.str("x").ifBlank { pt.str("beat") }.ifBlank { pt.str("label") }
                    .ifBlank { pt.str("category") }.ifBlank { pt.str("time") }.ifBlank { "点${pIdx + 1}" }.trim()
                val value = metricNumber(pt["value"] ?: pt["tension"])
                if (value == null) {
                    logs += "指标图第 ${pIdx + 1} 个数据点缺少真实数值，已剔除（绝不补造）"
                    return@forEachIndexed
                }
                val note = stripSourcePrefix(pt.str("note").ifBlank { pt.str("emotion") }).take(60)
                if (prov != Provenance.SOURCE_TEXT || metricGuessNoteRegex.containsMatchIn(note)) {
                    logs += "指标图第 ${pIdx + 1} 个数据点不是原文直引数值（来源或备注含推测），已剔除：${label.take(12)}"
                    return@forEachIndexed
                }
                val blocks = cleanBlocks(pt["blocks"] ?: pt["block"], validIds, defaultBlocks, label.take(12).ifEmpty { "指标点" }, logs)
                points += buildJsonObject {
                    put("x", label.take(24))
                    put("value", value)
                    put("note", note)
                    put("blocks", blocks.toJsonArray())
                    put("source", prov.wireValue)
                }
            }
            if (points.isNotEmpty()) {
                val name = rawS.str("name").ifBlank { rawS.str("label") }.ifBlank { if (sIdx == 0) "指标" else "序列${sIdx + 1}" }
                auditedSeries += buildJsonObject {
                    put("name", name.trim().take(20))
                    put("points", JsonArray(points))
                }
                totalPoints += points.size
            }
        }

        if (rawSeries.size > SOCIAL_METRIC_MAX_SERIES) {
            logs += "指标序列超过 $SOCIAL_METRIC_MAX_SERIES 条，已保留前 $SOCIAL_METRIC_MAX_SERIES 条"
        }
        if (totalPoints < 2) {
            logs += "有效真实数据点不足 2 个，无法成图"
            return null
        }
        if (mode == "comparison" && auditedSeries.size < 2) {
            logs += "对比模式至少需要两组数字，已按单序列渲染"
            mode = "series"
        }
        return buildJsonObject {
            put("mode", mode)
            put("yLabel", yLabel)
            put("unit", unit)
            put("series", JsonArray(auditedSeries))
        }
    }

    private fun auditTimelineData(
        rawData: JsonObject,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
    ): JsonObject? {
        val lanes = (rawData["lanes"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()
        val rawRows = rawData["rows"] as? JsonArray
        if (lanes.isEmpty() || rawRows == null || rawRows.isEmpty()) return null

        val rows = mutableListOf<Pair<Int?, JsonObject>>()
        for (rEl in rawRows) {
            val row = rEl as? JsonObject ?: continue
            val timeLbl = row.str("time").trim().ifEmpty { "时间点" }
            val sortYear = parseSortYear(row["sort_year"], timeLbl)
            val rawCells = row["cells"] as? JsonArray
            val cells = buildJsonArray {
                for (idx in lanes.indices) {
                    val cell = rawCells?.getOrNull(idx) as? JsonObject
                    if (cell == null) {
                        add(JsonNull)
                    } else {
                        val prov = normProvenance(cell["source"])
                        val title = stripSourcePrefix(cell.str("title")).ifEmpty { "事件" }
                        val note = stripSourcePrefix(cell.str("note"))
                        val blocks = cleanBlocks(cell["blocks"], validIds, defaultBlocks, title, logs)
                        add(
                            buildJsonObject {
                                put("title", title)
                                put("note", note)
                                put("blocks", blocks.toJsonArray())
                                put("source", prov.wireValue)
                            },
                        )
                    }
                }
            }
            val rowObj = buildJsonObject {
                put("time", timeLbl)
                put("cells", cells)
                if (sortYear != null) put("sort_year", sortYear)
            }
            rows += sortYear to rowObj
        }
        if (rows.isEmpty()) return null

        val datedYears = rows.mapNotNull { it.first }
        val isOutOfOrder = datedYears.size >= 2 && (1 until datedYears.size).any { datedYears[it] < datedYears[it - 1] }
        val sortedRows = if (isOutOfOrder) {
            logs += "时间轴检测到年份逆序，已按 sort_year 自动重排"
            rows.withIndex()
                .sortedWith(compareBy({ if (it.value.first != null) 0 else 1 }, { it.value.first ?: 0 }, { it.index }))
                .map { it.value.second }
        } else {
            rows.map { it.second }
        }

        val aftermathList = mutableListOf<JsonObject>()
        (rawData["aftermath"] as? JsonArray)?.forEach { el ->
            val af = el as? JsonObject ?: return@forEach
            val prov = normProvenance(af["source"])
            val title = stripSourcePrefix(af.str("title").ifBlank { af.str("name") })
            val body = stripSourcePrefix(af.str("body").ifBlank { af.str("detail") }.ifBlank { af.str("note") })
            if (title.isEmpty() && body.isEmpty()) return@forEach
            val blocks = cleanBlocks(af["blocks"], validIds, defaultBlocks, title.ifEmpty { "后世回响" }, logs)
            aftermathList += buildJsonObject {
                put("title", title)
                put("body", body)
                put("blocks", blocks.toJsonArray())
                put("source", prov.wireValue)
            }
        }

        return buildJsonObject {
            put("lanes", JsonArray(lanes.map { JsonPrimitive(it) }))
            put("rows", JsonArray(sortedRows))
            if (aftermathList.isNotEmpty()) put("aftermath", JsonArray(aftermathList))
        }
    }

    private fun auditQuadrantData(
        rawData: JsonObject,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
    ): JsonObject? {
        val rawQuads = rawData["quadrants"] as? JsonArray ?: return null
        if (rawQuads.isEmpty()) return null
        val validPos = listOf("q-top-left", "q-top-right", "q-bot-left", "q-bot-right")
        val quads = mutableListOf<JsonObject>()
        rawQuads.take(4).forEachIndexed { idx, el ->
            val q = el as? JsonObject ?: return@forEachIndexed
            val pos = q.str("pos").takeIf { it in validPos } ?: validPos[idx]
            val title = q.str("title").trim().ifEmpty { pos }
            val actionHint = stripSourcePrefix(q.str("action_hint").ifBlank { q.str("action") }).take(20)
            val items = mutableListOf<JsonObject>()
            (q["items"] as? JsonArray)?.forEach { itEl ->
                val it = itEl as? JsonObject ?: return@forEach
                val prov = normProvenance(it["source"])
                val txt = stripSourcePrefix(it.str("text"))
                if (txt.isEmpty()) return@forEach
                val blocks = cleanBlocks(it["blocks"], validIds, defaultBlocks, txt.take(10), logs)
                items += buildJsonObject {
                    put("text", txt)
                    put("blocks", blocks.toJsonArray())
                    put("source", prov.wireValue)
                }
            }
            val finalItems = if (items.size > QUADRANT_MAX_ITEMS) {
                logs += "象限 \"$title\" 原有 ${items.size} 条，已精简截断为前 $QUADRANT_MAX_ITEMS 条"
                items.take(QUADRANT_MAX_ITEMS)
            } else {
                items
            }
            quads += buildJsonObject {
                put("pos", pos)
                put("title", title)
                put("items", JsonArray(finalItems))
                if (actionHint.isNotEmpty()) put("action_hint", actionHint)
            }
        }
        return buildJsonObject {
            put("xAxis", rawData.str("xAxis").ifBlank { "横轴（低 → 高）" })
            put("yAxis", rawData.str("yAxis").ifBlank { "纵轴（低 → 高）" })
            put("quadrants", JsonArray(quads))
        }
    }

    private fun auditChapterMapData(
        rawData: JsonObject,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
    ): JsonObject? {
        val rawSections = rawData["sections"] as? JsonArray
        val rawTerms = rawData["terms"] as? JsonArray
        if (rawSections == null && rawTerms == null) return null

        val sections = mutableListOf<JsonObject>()
        rawSections?.forEachIndexed { sIdx, el ->
            val sec = el as? JsonObject ?: return@forEachIndexed
            val secBlocks = cleanBlocks(sec["block_ids"] ?: sec["blocks"], validIds, defaultBlocks, sec.str("title").ifBlank { "节拍${sIdx + 1}" }, logs)
            val nodes = mutableListOf<JsonObject>()
            (sec["nodes"] as? JsonArray)?.forEachIndexed { nIdx, nEl ->
                val nd = nEl as? JsonObject ?: return@forEachIndexed
                val nBlocks = cleanBlocks(nd["block_ids"] ?: nd["blocks"], validIds, defaultBlocks, nd.str("summary").take(12), logs)
                val prov = normProvenance(nd["source"])
                nodes += buildJsonObject {
                    put("id", nd.str("id").ifBlank { "u${nIdx + 1}" })
                    put("kind", nd.str("kind").ifBlank { "claim" })
                    put("importance", nd.str("importance").ifBlank { "support" })
                    put("summary", nd.str("summary"))
                    put("blocks", nBlocks.toJsonArray())
                    put("source", prov.wireValue)
                }
            }
            sections += buildJsonObject {
                put("id", sec.str("id").ifBlank { "sec-${sIdx + 1}" })
                put("title", sec.str("title").ifBlank { "未命名节拍" })
                put("summary", sec.str("summary"))
                put("blocks", secBlocks.toJsonArray())
                put("source", Provenance.SOURCE_TEXT.wireValue)
                put("nodes", JsonArray(nodes))
            }
        }

        val terms = mutableListOf<JsonObject>()
        rawTerms?.forEach { el ->
            val t = el as? JsonObject ?: return@forEach
            val termName = t.str("term").trim()
            if (termName.isEmpty()) return@forEach
            val tBlocks = cleanBlocks(t["blocks"] ?: t["source_block"], validIds, defaultBlocks, termName, logs)
            val prov = normProvenance(t["source"])
            terms += buildJsonObject {
                put("term", termName)
                put("definition", t.str("definition"))
                put("blocks", tBlocks.toJsonArray())
                put("source", prov.wireValue)
            }
        }
        return buildJsonObject {
            put("sections", JsonArray(sections))
            put("terms", JsonArray(terms))
        }
    }

    private fun normChecklistSection(raw: String): String {
        val sec = raw.trim().lowercase()
        if (sec.isEmpty()) return ""
        if (sec in setOf("before", "during", "redline")) return sec
        return when {
            listOf("前", "准备", "启动", "prereq", "pre").any { it in sec } -> "before"
            listOf("红线", "失效", "避坑", "边界", "警告", "stop", "risk", "pitfall").any { it in sec } -> "redline"
            listOf("中", "执行", "核验", "步骤", "操作", "check", "exec").any { it in sec } -> "during"
            else -> "during"
        }
    }

    private fun auditExtensionItems(
        rawItems: JsonArray?,
        bp: String,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
    ): List<ExtItem> {
        if (rawItems == null) return emptyList()
        val items = mutableListOf<ExtItem>()
        for (el in rawItems) {
            val it = el as? JsonObject ?: continue
            val prov = normProvenance(it["source"])
            var term = stripSourcePrefix(it.str("term"))
            val name = stripSourcePrefix(it.str("name"))
            val title = stripSourcePrefix(it.str("title").ifBlank { it.str("text") }.ifBlank { term }.ifBlank { name })
            val pitfall = stripSourcePrefix(it.str("pitfall"))
            val caseText = stripSourcePrefix(it.str("case"))
            val rootCause = stripSourcePrefix(it.str("root_cause"))
            val fix = stripSourcePrefix(it.str("fix"))
            val rawBody = stripSourcePrefix(it.str("body").ifBlank { it.str("detail") })
            val intuition = stripSourcePrefix(it.str("intuition").ifBlank { it.str("analogy") })
            var definition = stripSourcePrefix(it.str("definition"))
            val symbol = cleanOriginalTerm(it["symbol"] ?: it["formula"] ?: it["original_term"])

            var body = if (bp == "extension_glossary" || term.isNotEmpty() || definition.isNotEmpty() || intuition.isNotEmpty()) {
                if (term.isEmpty()) term = title
                if (definition.isEmpty()) definition = rawBody
                if (intuition.isNotEmpty() && definition.isNotEmpty()) {
                    "$intuition — $definition".trim(' ', '—')
                } else {
                    definition.ifEmpty { intuition }.ifEmpty { rawBody }
                }
            } else {
                boundaryPrefixRegex.replace(rawBody, "").trim()
            }

            if (body.isEmpty() && (pitfall.isNotEmpty() || caseText.isNotEmpty() || rootCause.isNotEmpty() || fix.isNotEmpty())) {
                body = listOfNotNull(
                    pitfall.takeIf { it.isNotEmpty() }?.let { "误区：$it" },
                    caseText.takeIf { it.isNotEmpty() }?.let { "反例：$it" },
                    rootCause.takeIf { it.isNotEmpty() }?.let { "根因：$it" },
                    fix.takeIf { it.isNotEmpty() }?.let { "纠偏：$it" },
                ).joinToString(" · ")
            }

            val roleTag = stripSourcePrefix(it.str("role_tag")).take(16)
            val incentive = stripSourcePrefix(it.str("incentive")).take(60)
            val constraint = stripSourcePrefix(it.str("constraint")).take(60)
            val strategy = stripSourcePrefix(it.str("strategy")).take(70)
            if (body.isEmpty() && (incentive.isNotEmpty() || constraint.isNotEmpty() || strategy.isNotEmpty())) {
                body = listOfNotNull(
                    incentive.takeIf { it.isNotEmpty() }?.let { "激励：$it" },
                    constraint.takeIf { it.isNotEmpty() }?.let { "约束：$it" },
                    strategy.takeIf { it.isNotEmpty() }?.let { "策略：$it" },
                ).joinToString("；")
            }

            if (title.isEmpty() && body.isEmpty() && pitfall.isEmpty() && caseText.isEmpty() && rootCause.isEmpty() && fix.isEmpty()) {
                continue
            }

            val blocks = cleanBlocks(it["blocks"], validIds, defaultBlocks, title.ifEmpty { "扩展项" }, logs)
            val extras = linkedMapOf<String, String>()
            if (bp == "extension_glossary" || term.isNotEmpty() || definition.isNotEmpty() || intuition.isNotEmpty()) {
                extras["term"] = term
                extras["symbol"] = symbol
                extras["intuition"] = intuition
                extras["definition"] = definition
            }
            if (name.isNotEmpty()) extras["name"] = name
            if (pitfall.isNotEmpty()) extras["pitfall"] = pitfall
            if (caseText.isNotEmpty()) extras["case"] = caseText
            if (rootCause.isNotEmpty()) extras["root_cause"] = rootCause
            if (fix.isNotEmpty()) extras["fix"] = fix
            if (roleTag.isNotEmpty()) extras["role_tag"] = roleTag
            if (incentive.isNotEmpty()) extras["incentive"] = incentive
            if (constraint.isNotEmpty()) extras["constraint"] = constraint
            if (strategy.isNotEmpty()) extras["strategy"] = strategy
            val roleBadge = it.str("role_badge").trim().ifEmpty { roleTag }
            if (roleBadge.isNotEmpty()) extras["role_badge"] = roleBadge
            val stance = it.str("stance").trim()
            if (stance.isNotEmpty()) extras["stance"] = stance
            val rawSection = it.str("section").ifBlank { it.str("group") }.trim().take(24)
            val section = if (bp == "extension_checklist") normChecklistSection(rawSection) else rawSection
            if (section.isNotEmpty()) extras["section"] = section

            val checked = if (bp == "extension_checklist") {
                (it["checked"] as? JsonPrimitive)?.booleanOrNull ?: false
            } else {
                null
            }

            items += ExtItem(
                title = title,
                text = title,
                body = body,
                detail = body,
                blocks = blocks,
                source = prov,
                extraFields = extras,
                checked = checked,
            )
        }
        return items
    }

    private fun tailorReadingNote(
        readingNote: String,
        action: String,
        caption: String,
        items: List<ExtItem>,
        logs: MutableList<String>,
    ): String {
        val note = readingNote.trim()
        val titles = items.take(2).mapNotNull { it.title.ifBlank { it.text }.trim().takeIf { s -> s.isNotEmpty() } }
        if (action == "method_audit" && (note.isEmpty() || METHOD_PLACEHOLDER_NOTE in note)) {
            if (METHOD_PLACEHOLDER_NOTE in note) logs += "检测到方法局限的通用占位 reading_note，已按本章证据链定制替换"
            return if (titles.isNotEmpty()) {
                "先看本章用「${titles.joinToString(" / ")}」等证据能推到哪一步".take(40)
            } else {
                "先看「$caption」的证据链与反事实缺口".take(40)
            }
        }
        if (action == "actor_incentives" && (note.isEmpty() || METHOD_PLACEHOLDER_NOTE in note)) {
            return if (titles.isNotEmpty()) {
                "盯住「${titles.joinToString(" / ")}」之间的激励错位与非预期后果".take(40)
            } else {
                "盯住「$caption」里的利益错位".take(40)
            }
        }
        if ((action == "sparring_dossier" || SPARRING_PLACEHOLDER_NOTE in note) && (note.isEmpty() || SPARRING_PLACEHOLDER_NOTE in note)) {
            if (SPARRING_PLACEHOLDER_NOTE in note) logs += "检测到泛泛占位 reading_note，已按本章交锋对象定制替换"
            return if (titles.isNotEmpty()) {
                "围绕「${titles.joinToString(" / ")}」把握本章论战靶子与思想实验".take(40)
            } else {
                "聚焦「$caption」拆解论战交锋与核心隐喻".take(40)
            }
        }
        if (action == "checklist" && (note.isEmpty() || CHECKLIST_PLACEHOLDER_NOTES.any { it in note })) {
            if (note.isNotEmpty()) logs += "检测到模板占位 reading_note，已按本章自检重点定制替换"
            return if (titles.isNotEmpty()) {
                "围绕「${titles.joinToString(" / ")}」核验启动条件与踩坑红线".take(40)
            } else {
                "对照「$caption」逐项核验落地前置条件与红线".take(40)
            }
        }
        if (action == "failure_postmortem" && (note.isEmpty() || POSTMORTEM_PLACEHOLDER_NOTES.any { it in note })) {
            if (note.isNotEmpty()) logs += "检测到模板占位 reading_note，已按本章失效案例定制替换"
            return if (titles.isNotEmpty()) {
                "聚焦「${titles.joinToString(" / ")}」反例，先排查常见误区再行动".take(40)
            } else {
                "对照「$caption」反例定位失效根因与纠偏动作".take(40)
            }
        }
        if (action == "boundary_check" || BOUNDARY_PLACEHOLDER_NOTE in note) {
            val stripped = boundaryPrefixRegex.replace(note, "").trim()
            if (stripped.isEmpty() || stripped == BOUNDARY_PLACEHOLDER_NOTE) {
                logs += "已剥离模板前缀「公式/规律失效提示」，定制本章边界导读"
                return if (titles.isNotEmpty()) {
                    "围绕「${titles.joinToString(" / ")}」把握范式演进与失效边界".take(40)
                } else {
                    "聚焦「$caption」厘清适用条件与极端边界".take(40)
                }
            }
            if (stripped != note) logs += "已剥离模板前缀「公式/规律失效提示：」"
            return stripped
        }
        return note
    }

    private fun groupExtensionSections(items: List<ExtItem>, requestedAction: String): List<ExtSection> {
        if (requestedAction == "checklist" && items.none { !it.extraFields["section"].isNullOrBlank() }) {
            return emptyList()
        }
        var meta = EXTENSION_SECTION_META[requestedAction]
        if (meta == null) {
            val itemSecs = items.mapNotNull { it.extraFields["section"]?.trim()?.takeIf { s -> s.isNotEmpty() } }.toSet()
            for (actMeta in EXTENSION_SECTION_META.values) {
                val known = actMeta.map { it.first }.toSet()
                if (itemSecs.intersect(known).isNotEmpty()) {
                    meta = actMeta
                    break
                }
            }
        }
        if (meta == null) return emptyList()
        if (requestedAction == "boundary_check" && items.none { !it.extraFields["section"].isNullOrBlank() }) {
            return emptyList()
        }
        val titleById = meta.toMap()
        val idByTitle = meta.associate { it.second to it.first }
        val buckets = linkedMapOf<String, MutableList<ExtItem>>()
        meta.forEach { (sid, _) -> buckets[sid] = mutableListOf() }
        val fallbackSid = meta.first().first

        for (it in items) {
            var sid = it.extraFields["section"].orEmpty().trim()
            if (sid !in buckets) sid = idByTitle[sid].orEmpty()
            if (sid !in buckets) sid = fallbackSid
            buckets.getValue(sid) += it
        }
        return meta.mapNotNull { (sid, title) ->
            val list = buckets.getValue(sid)
            if (list.isEmpty()) null else ExtSection(id = sid, title = titleById.getValue(sid), items = list)
        }
    }

    private fun buildFallbackArtifact(
        caption: String,
        summary: String,
        rawText: String,
        parsed: JsonObject?,
        validIds: Set<String>,
        defaultBlocks: List<String>,
        logs: MutableList<String>,
    ): AuditedArtifact.FallbackList {
        val items = mutableListOf<FallbackItem>()
        val dataObj = parsed?.get("data") as? JsonObject
        if (dataObj != null) {
            val rawList = (dataObj["nodes"] as? JsonArray)
                ?: (dataObj["regimes"] as? JsonArray)
                ?: (dataObj["points"] as? JsonArray)
                ?: (dataObj["rows"] as? JsonArray)
                ?: (dataObj["highlights"] as? JsonArray)
            rawList?.forEach { el ->
                val entry = el as? JsonObject ?: return@forEach
                val prov = normProvenance(entry["source"])
                val label = stripSourcePrefix(
                    entry.str("label").ifBlank { entry.str("title") }.ifBlank { entry.str("state") }
                        .ifBlank { entry.str("range") }.ifBlank { entry.str("beat") }
                        .ifBlank { entry.str("x") }.ifBlank { entry.str("key") },
                )
                if (label.isEmpty()) return@forEach
                val detail = stripSourcePrefix(
                    entry.str("detail").ifBlank { entry.str("mechanism") }.ifBlank { entry.str("note") }
                        .ifBlank { entry.str("emotion") }.ifBlank {
                            listOf(entry.str("everyday"), entry.str("author")).filter { it.isNotEmpty() }.joinToString(" · ")
                        },
                )
                val blocks = cleanBlocks(entry["blocks"] ?: entry["block"], validIds, defaultBlocks, label.take(12), logs)
                items += FallbackItem(label = label, detail = detail, blocks = blocks, source = prov)
            }
        }
        if (items.isEmpty()) {
            val snippet = summary.ifBlank { rawText }.trim().ifEmpty { "模型未返回完整图表结构" }.take(160)
            val fallbackBlock = defaultBlocks.mapNotNull { runCatching { BlockId(it) }.getOrNull() }.take(1)
            items += FallbackItem(
                label = snippet,
                detail = "点击下方块号可直跳对应原文复核。",
                blocks = fallbackBlock,
                source = Provenance.SOURCE_TEXT,
            )
        }
        val notice = "由于原图节点超限或字段不完整，系统已自动降级为保留原段落锚点的结构化清单。"
        logs += "已触发 fallback_list 安全降级，确保不白屏且保留各条原文块锚点"
        return AuditedArtifact.FallbackList(
            caption = caption.ifBlank { "结构化阅读要点（已安全降级）" },
            readingNote = notice,
            summary = summary,
            auditLog = logs,
            fallbackNotice = notice,
            items = items,
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun List<BlockId>.toJsonArray(): JsonArray =
        JsonArray(map { JsonPrimitive(it.value) })
}
