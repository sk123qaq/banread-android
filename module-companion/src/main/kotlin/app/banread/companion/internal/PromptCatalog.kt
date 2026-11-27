package app.banread.companion.internal

import app.banread.model.CrossBookHit
import app.banread.model.ReadProfileId
import app.banread.model.SpoilerGate
import app.banread.model.ToolId
import app.banread.model.registry.ProfileRegistry

internal data class BuiltPrompt(
    val systemPrompt: String,
    val userPrompt: String,
    val expectJson: Boolean,
    val effectiveAction: String,
    val effectiveBlueprint: String,
)

internal object PromptCatalog {
    const val PROMPT_VERSION: Int = 1

    val UNIVERSAL_ACTIONS: Set<String> = setOf("diagram", "explain", "translate", "critique", "ask")

    private const val LITERATURE_SPATIAL_SCHEMA_HINT =
        "\"blueprint\":\"spatial_route\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤35字看图引导\",\"summary\":\"2句核心解读（不与reading_note重复）\"," +
            "\"data\":{\"mode\":\"route_map|stage_blocking|not_applicable\",\"stage_desc\":\"≤30字舞台或空间格局\"," +
            "\"not_applicable_reason\":\"仅mode为not_applicable时填写2句原因，否则留空\"," +
            "\"nodes\":[{\"id\":\"S1\",\"role\":\"home|public|threshold|transit|destination|prop|zone|outside\"," +
            "\"zone\":\"≤10字片区或舞台区位\",\"x\":50,\"y\":50,\"label\":\"≤14字地点或舞台锚点\",\"detail\":\"≤45字态势说明\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
            "\"edges\":[{\"from\":\"S1\",\"to\":\"S2\",\"kind\":\"move|gaze|barrier|parallel\",\"actor\":\"人物路线名\",\"label\":\"≤6字动线\",\"note\":\"≤30字说明\"}]}"

    private const val LITERATURE_SPATIAL_GUIDANCE =
        "空间与舞台动线三模式自适应规则（三选一填入 data.mode）：\n" +
            "1. 单一封闭室内或戏剧舞台（如戏剧幕次、固定室内场）：mode=\"stage_blocking\"，节点为房间区域、门窗与核心道具锚点，" +
            "坐标 x（0–100 左→右）、y（0–100 后景/窗=0 → 台口/观众=100）；边用 kind=\"move\"/\"gaze\"/\"barrier\" + actor 标出人物调度。\n" +
            "2. 多地点移动的小说：mode=\"route_map\"，节点为真实地点，role 取 home/public/threshold/transit/destination，" +
            "x（0–100 西→东）、y（0–100 北→南），边用 kind=\"move\"/\"barrier\"/\"parallel\" + actor。\n" +
            "3. 无显著空间转换或舞台调度的抒情诗、议论散文、纯心理独白：mode=\"not_applicable\"，nodes 与 edges 留空数组 []，在 not_applicable_reason 诚实写 2 句原因。"

    val DAG_BLUEPRINTS: Set<String> = setOf(
        "argument_map",
        "lineage_dag",
        "power_hierarchy",
        "spatial_route",
        "historical_causality",
        "relation_graph",
        "flow_swimlane",
        "decision_tree",
        "derivation_dag",
        "system_flow",
        "causal_loop",
    )

    val MATRIX_BLUEPRINTS: Set<String> = setOf("term_contrast", "concept_matrix", "element_matrix")
    val CURVE_BLUEPRINTS: Set<String> = setOf("tension_curve", "metric_chart")

    val BLUEPRINT_SCHEMA_HINTS: Map<String, String> = mapOf(
        "argument_map" to (
            "\"blueprint\":\"argument_map\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读（勿复读reading_note）\"," +
                "\"data\":{\"nodes\":[{\"id\":\"A1\",\"role\":\"premise|inference|rebuttal|conclusion|condition\",\"label\":\"≤16字短标题\"," +
                "\"detail\":\"≤55字展开论证与依据\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"A1\",\"to\":\"A2\",\"label\":\"≤5字逻辑关系\"}]}"
            ),
        "term_contrast" to (
            "\"blueprint\":\"term_contrast\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读（勿复读reading_note）\"," +
                "\"data\":{\"columns\":[\"核心术语\",\"日常汉语直觉\",\"作者专属定义\",\"出处\"]," +
                "\"rows\":[{\"key\":\"术语\",\"original_term\":\"原文外文或古文原词(无则留空)\",\"everyday\":\"日常义\",\"author\":\"作者专义\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}"
            ),
        "lineage_dag" to (
            "\"blueprint\":\"lineage_dag\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读（勿复读reading_note）\"," +
                "\"data\":{\"mode\":\"lineage|not_applicable\",\"not_applicable_reason\":\"仅当mode为not_applicable时写2句说明\"," +
                "\"nodes\":[{\"id\":\"L1\",\"role\":\"predecessor|ally|mediator|target|author\",\"label\":\"≤16字学者/学派/作者立场\"," +
                "\"detail\":\"≤55字思想关联与张力\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"L1\",\"to\":\"L2\",\"relation\":\"inherit|transform|critique|contrast\",\"label\":\"≤5字关系\"}]}"
            ),
        "parallel_timeline" to (
            "\"blueprint\":\"parallel_timeline\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读\"," +
                "\"data\":{\"lanes\":[\"行为体/阵营/地区1\",\"行为体/阵营/地区2\"]," +
                "\"rows\":[{\"sort_year\":317,\"time\":\"展示时间\",\"cells\":[{\"title\":\"≤14字事件\",\"note\":\"≤35字说明\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"},null]}]," +
                "\"aftermath\":[{\"title\":\"≤18字后世回响/史论\",\"body\":\"≤55字说明\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}"
            ),
        "power_hierarchy" to (
            "\"blueprint\":\"power_hierarchy\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读\"," +
                "\"data\":{\"nodes\":[{\"id\":\"H1\",\"role\":\"sovereign|faction|ally|rival|constraint\",\"label\":\"≤16字权力实体/机构/约束\"," +
                "\"detail\":\"≤45字权责与制衡\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"H1\",\"to\":\"H2\",\"relation_type\":\"subordinate|balance|alliance|hostile|constraint\",\"label\":\"≤5字关系\"}]}"
            ),
        "spatial_route" to (
            "\"blueprint\":\"spatial_route\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读\"," +
                "\"data\":{\"mode\":\"campaign|geopolitics|not_applicable\",\"scope_note\":\"≤24字地理范围\"," +
                "\"not_applicable_reason\":\"仅当mode为not_applicable时填写2句说明\"," +
                "\"nodes\":[{\"id\":\"S1\",\"label\":\"≤12字真实地名/要塞/区域\",\"x\":50,\"y\":70,\"zone\":\"阵营/战区\"," +
                "\"role\":\"capital|fortress|corridor|neutral|front\",\"detail\":\"≤45字地理与战略态势\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"S1\",\"to\":\"S2\",\"label\":\"≤12字动线(含兵力/日程)\",\"kind\":\"advance|flank|defense|diplomacy\",\"route_group\":\"路线组名\"}]}"
            ),
        "historical_causality" to (
            "\"blueprint\":\"historical_causality\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读\"," +
                "\"data\":{\"nodes\":[{\"id\":\"C1\",\"role\":\"structural|trigger|mechanism|turning_point|consequence\",\"label\":\"≤16字因果节点\"," +
                "\"detail\":\"≤45字因果机制说明\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"C1\",\"to\":\"C2\",\"label\":\"≤5字(引发/催化/倒逼/反噬)\"}]}"
            ),
        "relation_graph" to (
            "\"blueprint\":\"relation_graph\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤35字看图引导\",\"summary\":\"2句核心解读（不与reading_note重复）\"," +
                "\"data\":{\"nodes\":[{\"id\":\"P1\",\"role\":\"protagonist|ally|antagonist|catalyst|observer\",\"camp\":\"阵营/家庭/阶层\"," +
                "\"label\":\"≤14字人物与身份\",\"arc\":\"≤25字本章心理或处境变化\",\"detail\":\"≤55字行为动机与交互\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"P1\",\"to\":\"P2\",\"rel_kind\":\"bond|conflict|asymmetry|family\",\"label\":\"≤6字关系\",\"note\":\"≤35字说明\"}]}"
            ),
        "tension_curve" to (
            "\"blueprint\":\"tension_curve\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤35字看图引导\",\"summary\":\"2句核心解读（不与reading_note重复）\"," +
                "\"data\":{\"yLabel\":\"张力强度\",\"points\":[{\"beat\":\"≤8字戏剧节拍\"," +
                "\"beat_kind\":\"setup|foreshadow|relief|escalation|climax|aftermath\",\"tension\":65," +
                "\"emotion\":\"≤12字情绪状态\",\"note\":\"25–45字解释这一拍为何拉高或压低张力\",\"blocks\":[\"B01\"],\"source\":\"原文\"}]}"
            ),
        "flow_swimlane" to (
            "\"blueprint\":\"flow_swimlane\",\"caption\":\"≤18字SOP图题\",\"reading_note\":\"≤40字实操导读(点出适用场景与前提)\"," +
                "\"summary\":\"2句实操总评（勿复读reading_note）\"," +
                "\"data\":{\"mode\":\"flow|not_applicable\",\"not_applicable_reason\":\"仅当mode为not_applicable时写2句原因，否则留空\"," +
                "\"nodes\":[{\"id\":\"F1\",\"role\":\"input|action|gate|pitfall|output\",\"label\":\"≤16字动作步骤\"," +
                "\"detail\":\"≤55字具体做法、工具或判断标准\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"F1\",\"to\":\"F2\",\"label\":\"≤5字流转\"}]}"
            ),
        "decision_tree" to (
            "\"blueprint\":\"decision_tree\",\"caption\":\"≤18字决策树题\",\"reading_note\":\"≤40字决策场景导读\"," +
                "\"summary\":\"2句决策总括（勿复读reading_note）\"," +
                "\"data\":{\"nodes\":[{\"id\":\"D1\",\"role\":\"diagnosis|condition|strategy|fallback\",\"label\":\"≤16字诊断问题或策略动作\"," +
                "\"detail\":\"≤55字判断标准、适用条件或止损阈值\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"D1\",\"to\":\"D2\",\"label\":\"≤5字判断条件\"}]}"
            ),
        "quadrant_matrix" to (
            "\"blueprint\":\"quadrant_matrix\",\"caption\":\"≤18字四象限图题\",\"reading_note\":\"≤40字坐标取舍导读\"," +
                "\"summary\":\"2句取舍总评（勿复读reading_note）\"," +
                "\"data\":{\"xAxis\":\"横轴维度（低 → 高）\",\"yAxis\":\"纵轴维度（低 → 高）\"," +
                "\"quadrants\":[{\"pos\":\"q-top-left|q-top-right|q-bot-left|q-bot-right\",\"title\":\"≤10字象限名称\"," +
                "\"action_hint\":\"≤14字行动口诀(如 立即重仓投入)\"," +
                "\"items\":[{\"text\":\"≤26字高杠杆条目\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}]}"
            ),
        "derivation_dag" to (
            "\"blueprint\":\"derivation_dag\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句核心推导解读（勿复读reading_note）\"," +
                "\"data\":{\"paradigm\":\"deductive|experimental\"," +
                "\"nodes\":[{\"id\":\"M1\",\"role\":\"axiom|condition|lemma|theorem|observation|hypothesis|experiment|finding\"," +
                "\"label\":\"≤16字核心节点\",\"detail\":\"≤55字推导或检验含义\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"M1\",\"to\":\"M2\",\"label\":\"≤5字依据\"}]}"
            ),
        "sensitivity_chart" to (
            "\"blueprint\":\"sensitivity_chart\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句临界阈值或参数响应解读（勿复读reading_note）\"," +
                "\"data\":{\"mode\":\"regime_threshold|numeric_curve|not_applicable\"," +
                "\"parameter\":\"控制变量名与单位(regime_threshold时必填)\"," +
                "\"regimes\":[{\"range\":\"区间如 < 1.5 M☉\",\"threshold\":\"临界阈值(无则留空)\",\"state\":\"≤12字相态或结果\"," +
                "\"mechanism\":\"≤55字物理或数学机制\",\"tone\":\"stable|critical|breakdown\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"xLabel\":\"自变量与单位(仅numeric_curve)\",\"yLabel\":\"因变量与单位(仅numeric_curve)\",\"unit\":\"真实物理单位\"," +
                "\"points\":[{\"x\":\"自变量真实刻度\",\"y\":0.0,\"note\":\"≤45字物理意义\",\"blocks\":[\"B01\"],\"source\":\"原文\"}]," +
                "\"not_applicable_reason\":\"仅mode为not_applicable时写2句原因\"," +
                "\"highlights\":[{\"title\":\"≤18字定性制约\",\"detail\":\"≤55字说明\",\"blocks\":[\"B01\"],\"source\":\"原文\"}]}"
            ),
        "system_flow" to (
            "\"blueprint\":\"system_flow\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句机制运作解读（勿复读reading_note）\"," +
                "\"data\":{\"mode\":\"flow|not_applicable\",\"not_applicable_reason\":\"仅当mode为not_applicable时写2句说明\"," +
                "\"nodes\":[{\"id\":\"C1\",\"role\":\"input_energy|component|process|bottleneck|output_effect\"," +
                "\"label\":\"≤16字装置部件/物理或演化步骤\",\"detail\":\"≤55字作用机制与参数\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"C1\",\"to\":\"C2\",\"label\":\"≤5字流转\"}]}"
            ),
        "causal_loop" to (
            "\"blueprint\":\"causal_loop\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读\"," +
                "\"data\":{\"loops\":[{\"id\":\"R1\",\"label\":\"≤18字反馈环名\"}]," +
                "\"nodes\":[{\"id\":\"S1\",\"role\":\"driver|policy|mechanism|outcome|lockin\",\"label\":\"≤16字变量或制度机制\"," +
                "\"detail\":\"≤45字因果作用\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]," +
                "\"edges\":[{\"from\":\"S1\",\"to\":\"S2\",\"label\":\"≤5字因果动作\",\"polarity\":\"+\",\"delay\":false}]}"
            ),
        "concept_matrix" to (
            "\"blueprint\":\"concept_matrix\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读\"," +
                "\"data\":{\"columns\":[\"学派/作者口径\",\"核心界定\",\"关键机制\",\"盲区/反例\",\"出处\"]," +
                "\"rows\":[{\"key\":\"学派、学者或政策立场\",\"everyday\":\"核心界定\",\"author\":\"关键机制\"," +
                "\"extra\":\"盲区/反例\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}"
            ),
        "metric_chart" to (
            "\"blueprint\":\"metric_chart\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读\"," +
                "\"data\":{\"mode\":\"series|comparison|not_applicable\",\"yLabel\":\"纵轴指标名\",\"unit\":\"%|亿元|万人\"," +
                "\"series\":[{\"name\":\"系列名\",\"points\":[{\"x\":\"1994\",\"value\":22,\"note\":\"≤35字原文口径\",\"blocks\":[\"B01\"],\"source\":\"原文\"}]}]," +
                "\"reason\":\"仅 not_applicable 时填写：全章除年份页码外无可比数字\"," +
                "\"highlights\":[{\"text\":\"≤30字定性史例或观察锚点\",\"blocks\":[\"B01\"],\"source\":\"原文\"}]}"
            ),
        "element_matrix" to (
            "\"blueprint\":\"element_matrix\",\"caption\":\"≤18字图题\",\"reading_note\":\"≤40字读图引导\",\"summary\":\"2句文字解读\"," +
                "\"data\":{\"columns\":[\"视觉维度\",\"文化/文物原型\",\"材质色彩特征\",\"美术作用\",\"出处\"]," +
                "\"rows\":[{\"key\":\"维度\",\"everyday\":\"原型\",\"author\":\"材质特征\",\"extra\":\"作用\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}"
            ),
    )

    val BLUEPRINT_GUIDANCE: Map<String, String> = mapOf(
        "parallel_timeline" to "专项约束（多线平行时间轴）：lanes 按并行行为体/阵营划分（2–4 条），每行必填整数 sort_year（公元前用负数如 -221）与展示 time，按从早到晚排列。",
        "power_hierarchy" to "专项约束（权力与阵营结构）：只放权力实体/机构/派系/约束，节点 role 限 sovereign/faction/ally/rival/constraint，建议 6–12 节点（上限 16）。",
        "spatial_route" to "专项约束（地理与空间态势）：节点必须是真实地名/要塞/通道并附归一化坐标 x(0–100)、y(0–100)；若无空间转换请设 mode=not_applicable。",
        "historical_causality" to "专项约束（历史因果演进图）：节点 role 限 structural/trigger/mechanism/turning_point/consequence，聚焦深层结构与因果催化链。",
        "tension_curve" to "张力曲线要求：提炼 6–9 个关键戏剧节拍（setup/foreshadow/relief/escalation/climax/aftermath），解释为何拉高或压低张力。",
        "relation_graph" to "人物与阵营图谱要求：role 限 protagonist/ally/antagonist/catalyst/observer，严禁使用非人物节点，填写 camp/arc/rel_kind。",
        "argument_map" to "专项约束（论证逻辑树）：按结论 ← 并行前提 ← 质疑与回应组织广度汇流树（上限 16 节点），每节点精选 1–3 个块号。",
        "term_contrast" to "专项约束（词义辨析双栏表）：6–9 个核心术语，突出日常直觉与作者专义差异，无原词时 original_term 留空。",
        "lineage_dag" to "专项约束（批判谱系图）：节点为具体思想家/学派/作者立场，边标 relation（inherit/transform/critique/contrast）；无外部对话则设 mode=not_applicable。",
        "flow_swimlane" to "专项约束（SOP 流程图）：提炼可复用的先后执行步骤（input/action/gate/pitfall/output），严禁画成章节流水账。",
        "decision_tree" to "专项约束（条件决策树）：呈真正树形分叉（diagnosis → condition → strategy/fallback），避免无分叉单链。",
        "quadrant_matrix" to "专项约束（优先级四象限）：每象限精选 2–4 条高杠杆动作并给出 action_hint。",
        "derivation_dag" to "专项约束（公理-定理推导图）：按演绎（deductive）或实验（experimental）二选一组织广度汇流推导树。",
        "sensitivity_chart" to "专项约束（临界条件与参数响应图）：严禁编造 0–100 假百分比；按 regime_threshold / numeric_curve / not_applicable 三选一。",
        "system_flow" to "专项约束（机制与装置流图）：专画物理过程/装置/演化迭代机制（input_energy/component/process/bottleneck/output_effect）。",
        "causal_loop" to "专项约束（社会科学因果反馈回路）：节点限 driver/policy/mechanism/outcome/lockin，边必填 polarity（+ / -）与可选 delay。",
        "concept_matrix" to "专项约束（跨学派概念矩阵）：5–8 行有立场张力的学派/学者对照，每行最多 3 个块号。",
        "metric_chart" to "专项约束（真实实证数据）：严禁编造数字，每个 value 必须 100% 来自原文且 source=\"原文\"，无量化数据时返回 mode=\"not_applicable\"。",
    )

    internal data class ExtensionPromptSpec(
        val blueprint: String,
        val title: String,
        val guidance: String,
        val instruction: String,
    )

    val EXTENSION_PROMPT_SPECS: Map<String, ExtensionPromptSpec> = mapOf(
        "checklist" to ExtensionPromptSpec(
            blueprint = "extension_checklist",
            title = "行动自检清单",
            guidance = "结构与口径要求（三分区 before/during/redline，共 8–12 条可勾选清单，每条 1–3 个块号，reading_note 须针对本章定制）。",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_checklist\",\"caption\":\"≤18字清单名\",\"reading_note\":\"≤40字本章定制导读\",\"summary\":\"2句落地总评\",\"items\":[{\"section\":\"before|during|redline\",\"text\":\"≤26字可执行检查项\",\"detail\":\"≤45字核验标准或踩坑边界\",\"checked\":false,\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
        "failure_postmortem" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "反例与失效复盘",
            guidance = "结构与口径要求（4–6 张复盘卡，必填 pitfall/case/root_cause/fix 与 body）。",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字失效复盘题\",\"reading_note\":\"≤40字本章定制警示导读\",\"summary\":\"2句总括规律\",\"items\":[{\"title\":\"≤20字失效模式标签\",\"pitfall\":\"≤35字常见误区\",\"case\":\"≤55字书内反例\",\"root_cause\":\"≤45字根因\",\"fix\":\"≤45字纠偏动作\",\"body\":\"≤75字兼容摘要\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
        "narrative_clues" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "叙事视角与伏笔线索",
            guidance = "",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字视角与线索题\",\"reading_note\":\"≤35字观察引导\",\"summary\":\"2句总体线索综述\",\"items\":[{\"section\":\"叙事视角与时间结构|关键伏笔、线索与信息差|本章悬念与待观察线\",\"title\":\"≤20字视角或线索标签\",\"body\":\"≤75字具体细节\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
        "imagery" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "意象、潜台词与细读",
            guidance = "",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字意象与细读题\",\"reading_note\":\"≤35字细读引导\",\"summary\":\"2句基调总结\",\"items\":[{\"section\":\"核心意象与象征物|潜台词、反讽与舞台重音|声景氛围、叙事修辞与名句细读\",\"title\":\"≤20字意象或潜台词标签\",\"body\":\"≤75字细读解析\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
        "historical_dossier" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "人物与名物档案",
            guidance = "",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字档案题\",\"reading_note\":\"≤40字导读提示\",\"summary\":\"2句人物与制度总览\",\"sections\":[{\"id\":\"figures\",\"title\":\"出场人物与阵营速查\",\"items\":[{\"name\":\"人物姓名\",\"role_badge\":\"身份/阵营\",\"body\":\"≤60字关键作用\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]},{\"id\":\"glossary\",\"title\":\"关键制度·条约·名物注\",\"items\":[{\"name\":\"专名/制度\",\"body\":\"≤60字释义\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}]}",
        ),
        "compare_context" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "多史家视角对照",
            guidance = "结合跨书检索参考片段，输出三分区共 6–9 条，严禁编造虚构引文。",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字史学对照题\",\"reading_note\":\"≤40字史料视角提醒\",\"summary\":\"2句多视角摘要\",\"sections\":[{\"id\":\"historiography\",\"title\":\"史观与归因对撞\",\"items\":[{\"stance\":\"学派\",\"title\":\"≤18字核心论断\",\"body\":\"≤70字说法与局限\",\"blocks\":[\"B01\"],\"source\":\"原文\"}]},{\"id\":\"blindspots\",\"title\":\"作者视角与史料盲区\",\"items\":[{\"title\":\"≤18字盲区\",\"body\":\"≤70字说明\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]},{\"id\":\"world_anchors\",\"title\":\"同代世界史横向锚点\",\"items\":[{\"title\":\"≤20字年份与坐标\",\"body\":\"≤70字对照意义\",\"blocks\":[\"B01\"],\"source\":\"补充\"}]}]}",
        ),
        "concept_glossary" to ExtensionPromptSpec(
            blueprint = "extension_glossary",
            title = "核心概念与公式词典",
            guidance = "6–9 条核心科学词条，无明确公式时 symbol 留空，勿杜撰。",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_glossary\",\"caption\":\"≤18字科学词典题\",\"reading_note\":\"≤40字概念速查导读\",\"summary\":\"2句总体概念主线\",\"items\":[{\"term\":\"≤18字概念名\",\"symbol\":\"可选符号\",\"intuition\":\"≤40字直觉\",\"definition\":\"≤75字精确定义\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
        "boundary_check" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "演进与适用边界",
            guidance = "三分区 paradigm_shift / intuition_analogy / breakdown_boundary，共 6–9 条。",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字演进与边界题\",\"reading_note\":\"≤40字定制边界提醒\",\"summary\":\"2句边界综述\",\"items\":[{\"section\":\"paradigm_shift|intuition_analogy|breakdown_boundary\",\"title\":\"≤20字标签\",\"stance\":\"≤8字标签\",\"body\":\"≤80字具体分析\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
        "sparring_dossier" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "学术交锋档案",
            guidance = "两分区 debate / thought_experiment，共 6–8 条，reading_note 须点出具体论战对象。",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字思想交锋题\",\"reading_note\":\"≤40字定制导读\",\"summary\":\"2句总评\",\"items\":[{\"section\":\"debate|thought_experiment\",\"title\":\"≤20字对象或思想实验\",\"stance\":\"≤8字标签\",\"body\":\"≤90字拆解\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
        "counterexample_lab" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "反例与现实检验",
            guidance = "三分区 hidden_premise / counterexample / modern_boundary，共 5–7 条。",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字反例检验题\",\"reading_note\":\"≤40字定制导读\",\"summary\":\"2句检验结论\",\"items\":[{\"section\":\"hidden_premise|counterexample|modern_boundary\",\"title\":\"≤20字标签\",\"stance\":\"≤8字标签\",\"body\":\"≤95字分析\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
        "method_audit" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "方法与样本局限",
            guidance = "结合跨书检索参考片段，按 evidence_method / identification_bias / evolution_boundary 三区输出 6–8 条。",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字方法与局限\",\"reading_note\":\"≤30字本章专属方法提醒\",\"summary\":\"2句方法论综述\",\"items\":[{\"section\":\"evidence_method|identification_bias|evolution_boundary\",\"title\":\"≤20字维度\",\"body\":\"≤70字分析\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
        "actor_incentives" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "行动者激励与博弈",
            guidance = "拆出 4–6 个核心行动者，填写 role_tag/incentive/constraint/strategy 与 body。",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字博弈格局\",\"reading_note\":\"≤30字本章核心利益冲突\",\"summary\":\"2句博弈综述\",\"items\":[{\"title\":\"≤14字行动者名\",\"role_tag\":\"核心决策方|博弈响应方|制度承受方|外部约束者\",\"incentive\":\"≤40字激励\",\"constraint\":\"≤40字约束\",\"strategy\":\"≤45字策略\",\"body\":\"≤80字摘要\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
        "craft_notes" to ExtensionPromptSpec(
            blueprint = "extension_dossier",
            title = "工艺流派考据",
            guidance = "",
            instruction = "输出严格 JSON：{\"blueprint\":\"extension_dossier\",\"caption\":\"≤18字技法考据题\",\"reading_note\":\"观图时留心年代与材料工艺\",\"summary\":\"2句风格综述\",\"items\":[{\"title\":\"≤20字工艺维度\",\"body\":\"≤70字源流或技法背景\",\"blocks\":[\"B01\"],\"source\":\"原文|补充\"}]}",
        ),
    )

    fun isStructuredTool(toolId: ToolId, profileRegistry: ProfileRegistry, readProfileId: ReadProfileId): Boolean {
        val raw = toolId.raw.trim().lowercase()
        if (raw == "diagram" || raw == "chapter_map") return true
        if (raw in BLUEPRINT_SCHEMA_HINTS || raw in EXTENSION_PROMPT_SPECS) return true
        val profile = profileRegistry.profileById(readProfileId)
        return profile.blueprints.any { it.blueprint == raw } || profile.extensions.any { it.action == raw }
    }

    fun antiSpoilerInstruction(gate: SpoilerGate, readType: String): String {
        return when (gate) {
            is SpoilerGate.UpTo -> {
                val ord = gate.unlockedMaxOrdinal.value
                "【严禁剧透硬规则（已解锁进度门控：gateOrdinal=$ord，仅限第 1–$ord 章）】" +
                    "严禁调用训练记忆剧透第 $ord 章之后尚未揭晓的真凶、血缘秘密、人物结局或隐藏反转；" +
                    "所有分析严格限定在第 1–$ord 章已呈现的文本事实内。"
            }
            SpoilerGate.FullBookUnlocked -> {
                if (readType == "literature") {
                    "【严禁剧透硬规则】严禁调用训练记忆剧透当前范围之外尚未揭晓的真凶、血缘秘密、人物结局或隐藏反转；所有分析严格限定在已呈现的文本事实内。"
                } else {
                    ""
                }
            }
        }
    }

    fun buildPrompt(
        toolId: ToolId,
        readProfileId: ReadProfileId,
        literatureSubKind: String?,
        profileRegistry: ProfileRegistry,
        ctx: BuiltCompanionContext,
        gate: SpoilerGate,
        question: String = "",
        crossBookHits: List<CrossBookHit> = emptyList(),
    ): BuiltPrompt {
        val rawTool = toolId.raw.trim().lowercase()
        val readType = readProfileId.raw
        val profile = profileRegistry.profileById(readProfileId)

        val effectiveAction: String
        val effectiveBlueprint: String
        when {
            rawTool == "diagram" -> {
                effectiveAction = "diagram"
                effectiveBlueprint = profile.blueprints.firstOrNull()?.blueprint ?: "argument_map"
            }
            rawTool in BLUEPRINT_SCHEMA_HINTS -> {
                effectiveAction = "diagram"
                effectiveBlueprint = rawTool
            }
            rawTool in EXTENSION_PROMPT_SPECS -> {
                effectiveAction = rawTool
                effectiveBlueprint = EXTENSION_PROMPT_SPECS.getValue(rawTool).blueprint
            }
            rawTool == "chapter_map" -> {
                effectiveAction = "chapter_map"
                effectiveBlueprint = "chapter_map"
            }
            rawTool in UNIVERSAL_ACTIONS -> {
                effectiveAction = rawTool
                effectiveBlueprint = "markdown_read_card"
            }
            else -> {
                effectiveAction = "ask"
                effectiveBlueprint = "markdown_read_card"
            }
        }

        val antiSpoiler = antiSpoilerInstruction(gate, readType)
        val crossHitsBlock = if (crossBookHits.isNotEmpty()) {
            buildString {
                append("\n\n【书库跨书检索参考片段（属 [补充] 参照，严禁冒充本书原文）】\n")
                crossBookHits.take(8).forEachIndexed { idx, hit ->
                    append("${idx + 1}. 《${hit.title}》（${hit.bestPath}:${hit.bestLine}，相关度=${hit.relevance}）：${hit.snippet}\n")
                }
            }
        } else {
            ""
        }

        if (effectiveAction == "chapter_map") {
            val system = buildString {
                append("你是阅读伴读专家，负责提炼全章知识地图（节拍分节、核心单元与术语表）。\n")
                if (antiSpoiler.isNotBlank()) append(antiSpoiler).append('\n')
                append(
                    "只输出一个严格 JSON 对象（不要 markdown 围栏）：" +
                        "{\"blueprint\":\"chapter_map\",\"caption\":\"≤18字知识地图题\",\"reading_note\":\"≤40字导读\",\"summary\":\"2句全章主旨\"," +
                        "\"data\":{\"sections\":[{\"id\":\"sec1\",\"title\":\"节拍标题\",\"summary\":\"≤50字节拍摘要\",\"block_ids\":[\"B01\"]," +
                        "\"nodes\":[{\"id\":\"u1\",\"kind\":\"claim|evidence|case|term\",\"importance\":\"core|support\",\"summary\":\"≤45字要点\",\"blocks\":[\"B01\"],\"source\":\"原文\"}]}]," +
                        "\"terms\":[{\"term\":\"术语名\",\"definition\":\"≤60字界定\",\"blocks\":[\"B01\"],\"source\":\"原文\"}]}}",
                )
            }
            val user = buildUserBlock(ctx, question = question, crossHitsBlock = crossHitsBlock)
            return BuiltPrompt(system, user, expectJson = true, effectiveAction = effectiveAction, effectiveBlueprint = effectiveBlueprint)
        }

        if (effectiveAction == "diagram") {
            val isLitSpatial = effectiveBlueprint == "spatial_route" && readType == "literature"
            val schemaHint = if (isLitSpatial) LITERATURE_SPATIAL_SCHEMA_HINT else BLUEPRINT_SCHEMA_HINTS[effectiveBlueprint] ?: BLUEPRINT_SCHEMA_HINTS.getValue("argument_map")
            val scopeHint = when (ctx.scopeKind) {
                "chapter" -> "当前作用范围：整章全局（涵盖 ${ctx.coveredBlocks} 个正文段落${if (ctx.sampled) "，长章已连续场景窗口抽样覆盖" else ""}）。每个节点 blocks 必须引用真实块号。"
                "full_book" -> "当前作用范围：已解锁全书门控范围（${ctx.chapterTitle}）。严禁越过当前进度门控剧透。"
                else -> "当前作用范围：读者选中的段落（${ctx.blockIds.joinToString(", ") { it.value }.ifBlank { "局部" }}）。"
            }
            val extraLines = buildList {
                if (antiSpoiler.isNotBlank()) add(antiSpoiler)
                if (isLitSpatial) add(LITERATURE_SPATIAL_GUIDANCE)
                else BLUEPRINT_GUIDANCE[effectiveBlueprint]?.let { add(it) }
            }
            val coordRule = if (effectiveBlueprint == "spatial_route") {
                "2. 严禁输出 HTML/SVG 代码或像素宽高；必须给出归一化坐标 x(0–100) 与 y(0–100)。\n"
            } else {
                "2. 严禁输出任何像素坐标、宽高、颜色或 HTML/SVG 代码。\n"
            }
            val edgeLimitHint = if (effectiveBlueprint == "spatial_route" && !isLitSpatial) "12 字以内" else "5 字以内"
            val system = buildString {
                append("你是阅读伴读助手，擅长把复杂文字转成清晰的关系图与结构化蓝图（当前阅读视角：$readType）。\n")
                append(scopeHint).append('\n')
                if (extraLines.isNotEmpty()) append(extraLines.joinToString("\n")).append('\n')
                append("严格要求：\n")
                append("1. 只输出一个合法 JSON 对象，不要 markdown 代码围栏，不要多余解释。\n")
                append(coordRule)
                append("3. 每个节点主标题严格控制在 16 字以内，详细解释放到 detail；边标签控制在 $edgeLimitHint。\n")
                append("4. 每个节点或要点都必须填写来源于本段实际编号的 blocks 数组（例如 [\"B02\"]），并注明 source（\"原文\" 或 \"补充\"）；summary 勿复读 reading_note。\n")
                append("请按以下 JSON 槽位输出：{$schemaHint}")
            }
            val user = buildUserBlock(ctx, question = question, crossHitsBlock = crossHitsBlock)
            return BuiltPrompt(system, user, expectJson = true, effectiveAction = effectiveAction, effectiveBlueprint = effectiveBlueprint)
        }

        if (effectiveAction in EXTENSION_PROMPT_SPECS) {
            val spec = EXTENSION_PROMPT_SPECS.getValue(effectiveAction)
            val subHint = literatureSubKind?.takeIf { it.isNotBlank() }?.let { "（文学子类侧重点：$it）" }.orEmpty()
            val scopeHint = when (ctx.scopeKind) {
                "chapter" -> "作用范围：整章全局（涵盖 ${ctx.coveredBlocks} 个正文段落${if (ctx.sampled) "，已连续场景窗口覆盖" else ""}）。"
                "full_book" -> "作用范围：已解锁全书门控范围（${ctx.chapterTitle}）。"
                else -> "作用范围：读者选中的段落（${ctx.blockIds.joinToString(", ") { it.value }.ifBlank { "局部" }}）。"
            }
            val system = buildString {
                append("你是阅读伴读专家，负责生成「${spec.title}」结构卡片（阅读视角：$readType）$subHint。\n")
                append(scopeHint).append('\n')
                if (antiSpoiler.isNotBlank()) append(antiSpoiler).append('\n')
                if (spec.guidance.isNotBlank()) append(spec.guidance).append('\n')
                append("严格区分 [原文] 与 [补充]（source 为 \"补充\" 时 body 开头禁止写\"补充对照：\"或\"补充：\"），严禁输出坐标或样式，只输出一个严格 JSON 对象（不要围栏）：\n")
                append(spec.instruction)
            }
            val user = buildUserBlock(ctx, question = question, crossHitsBlock = crossHitsBlock)
            return BuiltPrompt(system, user, expectJson = true, effectiveAction = effectiveAction, effectiveBlueprint = spec.blueprint)
        }

        // Universal Markdown actions: explain | translate | critique | ask
        val actionSystem = when (effectiveAction) {
            "explain" ->
                "你是耐心的伴读老师。读者选中了一段觉得抽象或不好懂的文字。请用大白话拆解它到底在说什么，并举一个具体例子。\n" +
                    "1. **一句话说透**\n2. **举个例子**\n3. **为什么这里要提它**\n" +
                    "源于原文标 `[原文]`，补充类比标 `[补充]`。"
            "translate" ->
                "你是双语、古今汉语及方言旧词对读编辑。聚焦读者选中的文本本身提供直接对读辅助：\n" +
                    "- 文言/古文/旧词：给出流畅【现代汉语直译】+【关键实词/典故注】；\n" +
                    "- 中译本：给出更自然的重述并还原【英文原词/概念】；\n" +
                    "- 英文原文：给出信达雅中文翻译并标注关键术语。\n原文词义标 `[原文]`，背景延展标 `[补充]`。"
            "critique" ->
                "你是严谨的文本细读同伴。请克制、具体地列出：\n" +
                    "1. **明面表述** `[原文]`\n2. **未明说的隐含前提** `[补充]`\n3. **适用边界 / 反例情景** `[补充]`"
            else ->
                "你是随身伴读助手。紧扣读者选中的原文与上下文回答问题。引用书中事实标 `[原文]`，书外推理补充标 `[补充]`。"
        }
        val system = buildString {
            append(actionSystem)
            if (antiSpoiler.isNotBlank()) {
                append('\n').append(antiSpoiler)
            }
        }
        val user = buildUserBlock(ctx, question = question, crossHitsBlock = crossHitsBlock)
        return BuiltPrompt(system, user, expectJson = false, effectiveAction = effectiveAction, effectiveBlueprint = "markdown_read_card")
    }

    private fun buildUserBlock(
        ctx: BuiltCompanionContext,
        question: String,
        crossHitsBlock: String,
    ): String = buildString {
        val scopeDesc = when (ctx.scopeKind) {
            "chapter" -> "全章全局"
            "full_book" -> "全书门控范围"
            else -> ctx.blockIds.joinToString(", ") { it.value }.ifBlank { "局部选段" }
        }
        append("《${ctx.workTitle}》· ${ctx.chapterTitle}（范围：$scopeDesc）\n")
        if (ctx.scopeKind == "chapter" || ctx.scopeKind == "full_book") {
            append("\n全章/门控正文骨架（带真实块号）：\n")
            append(ctx.contextText.ifBlank { ctx.selectedText })
        } else {
            if (ctx.contextText.isNotBlank()) {
                append("\n相邻上下文：\n").append(ctx.contextText).append('\n')
            }
            if (ctx.selectedText.isNotBlank()) {
                append("\n读者选中的原文：\n").append(ctx.selectedText)
            } else {
                append("\n请针对当前所选内容进行分析。")
            }
        }
        if (crossHitsBlock.isNotBlank()) {
            append(crossHitsBlock)
        }
        if (question.isNotBlank()) {
            append("\n\n读者追问：").append(question)
        }
    }
}
