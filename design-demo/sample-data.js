/* 伴读 Android 原型 · 预置样书、6+1视角配置、真实正文、全学科图谱样图与 BYOK 配置 */
(function () {
  const svgUri = (svg) => 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);

  const COVER_KANT_SVG = svgUri(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 360">' +
      '<rect width="240" height="360" fill="#1F3044"/>' +
      '<rect x="12" y="14" width="216" height="332" fill="#F4EDE0" stroke="#C5A267" stroke-width="2"/>' +
      '<rect x="18" y="20" width="204" height="320" fill="none" stroke="#1F3044" stroke-width="1.2"/>' +
      '<text x="120" y="54" text-anchor="middle" font-family="serif" font-size="10" letter-spacing="2.2" fill="#6F5633">IMMANUEL KANT</text>' +
      '<line x1="46" y1="64" x2="194" y2="64" stroke="#C5A267" stroke-width="1"/>' +
      '<circle cx="120" cy="144" r="54" fill="none" stroke="#1F3044" stroke-width="1.4"/>' +
      '<circle cx="120" cy="144" r="42" fill="none" stroke="#A8442F" stroke-width="1" stroke-dasharray="3 3"/>' +
      '<polygon points="120,96 162,168 78,168" fill="rgba(31,48,68,0.08)" stroke="#1F3044" stroke-width="1.3"/>' +
      '<line x1="66" y1="144" x2="174" y2="144" stroke="#C5A267" stroke-width="1"/>' +
      '<line x1="120" y1="90" x2="120" y2="198" stroke="#C5A267" stroke-width="1"/>' +
      '<text x="120" y="242" text-anchor="middle" font-family="serif" font-size="22" font-weight="700" fill="#1F3044">纯粹理性批判</text>' +
      '<text x="120" y="266" text-anchor="middle" font-family="serif" font-size="10" letter-spacing="1.2" fill="#5A534A">KRITIK DER REINEN VERNUNFT</text>' +
      '<rect x="64" y="286" width="112" height="22" rx="11" fill="#1F3044"/>' +
      '<text x="120" y="301" text-anchor="middle" font-family="sans-serif" font-size="10" fill="#F4EDE0">哲学先验典藏导读本</text>' +
    '</svg>'
  );

  const COVER_MICRO_SVG = svgUri(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 360">' +
      '<rect width="240" height="360" fill="#E8E3D5"/>' +
      '<rect x="0" y="0" width="240" height="128" fill="#3B4D36"/>' +
      '<rect x="18" y="18" width="204" height="324" fill="none" stroke="#2D3B29" stroke-width="1"/>' +
      '<text x="30" y="46" font-family="monospace" font-size="9.5" letter-spacing="1.8" fill="#D5DFCE">SOCIAL SCIENCE · EPUB</text>' +
      '<text x="30" y="84" font-family="serif" font-size="23" font-weight="700" fill="#FAF7F0">微观激励与</text>' +
      '<text x="30" y="112" font-family="serif" font-size="23" font-weight="700" fill="#FAF7F0">制度演进</text>' +
      '<line x1="30" y1="156" x2="210" y2="156" stroke="#B7B09E" stroke-width="0.8"/>' +
      '<path d="M40 278 Q105 266 196 178" fill="none" stroke="#A8442F" stroke-width="2.4"/>' +
      '<path d="M42 192 Q115 248 198 274" fill="none" stroke="#1E5A4C" stroke-width="2.2" stroke-dasharray="5 3"/>' +
      '<circle cx="122" cy="240" r="5" fill="#A8442F"/>' +
      '<circle cx="72" cy="214" r="3.5" fill="#3B4D36"/>' +
      '<circle cx="174" cy="198" r="3.5" fill="#1E5A4C"/>' +
      '<text x="30" y="312" font-family="sans-serif" font-size="11" font-weight="600" fill="#2E362A">陈远 · 制度经济学专著</text>' +
      '<text x="30" y="330" font-family="monospace" font-size="9.5" fill="#6B6559">PATH DEPENDENCE &amp; AGENCY</text>' +
    '</svg>'
  );

  const COVER_EUCLID_SVG = svgUri(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 360">' +
      '<rect width="240" height="360" fill="#F2E7CF"/>' +
      '<rect x="12" y="14" width="216" height="332" fill="none" stroke="#473828" stroke-width="2.2"/>' +
      '<rect x="17" y="19" width="206" height="322" fill="none" stroke="#473828" stroke-width="0.9"/>' +
      '<line x1="184" y1="19" x2="184" y2="341" stroke="#473828" stroke-width="1"/>' +
      '<polygon points="74,158 100,122 146,158" fill="rgba(168,68,47,0.12)" stroke="#2A2118" stroke-width="1.6"/>' +
      '<polygon points="74,158 146,158 146,230 74,230" fill="none" stroke="#2A2118" stroke-width="1.3"/>' +
      '<polygon points="74,158 100,122 64,96 38,132" fill="none" stroke="#2A2118" stroke-width="1.2"/>' +
      '<polygon points="100,122 146,158 180,112 134,76" fill="none" stroke="#2A2118" stroke-width="1.2"/>' +
      '<line x1="100" y1="122" x2="100" y2="230" stroke="#A8442F" stroke-width="1.2" stroke-dasharray="3 2"/>' +
      '<text x="203" y="70" writing-mode="tb" font-family="serif" font-size="16" font-weight="700" fill="#2A2118">幾何原本 · 卷一</text>' +
      '<text x="34" y="276" font-family="serif" font-size="11.5" fill="#473828">明刻徐光啟利瑪竇合譯</text>' +
      '<text x="34" y="296" font-family="serif" font-size="10.5" fill="#6F5E4A">影印本 · 卷首命題圖版</text>' +
    '</svg>'
  );

  const COVER_OSTEP_SVG = svgUri(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 220">' +
      '<rect width="320" height="220" fill="#223242"/>' +
      '<rect x="10" y="10" width="300" height="200" rx="6" fill="#2A3E52" stroke="#53728F" stroke-width="1.2"/>' +
      '<text x="24" y="38" font-family="monospace" font-size="10" letter-spacing="1.4" fill="#9AC0DE">SYSTEMS LECTURE EDITION · TEXT PDF</text>' +
      '<text x="24" y="70" font-family="serif" font-size="22" font-weight="700" fill="#F8F6F0">操作系统导论</text>' +
      '<text x="24" y="92" font-family="sans-serif" font-size="11.5" fill="#C9D8E6">Operating Systems: Three Easy Pieces</text>' +
      '<rect x="24" y="114" width="124" height="72" rx="4" fill="#1C2936" stroke="#6E94B8" stroke-width="1"/>' +
      '<text x="34" y="133" font-family="monospace" font-size="9.5" fill="#E3B35C">VIRTUALIZATION</text>' +
      '<rect x="34" y="142" width="48" height="16" fill="#355570"/><text x="41" y="153" font-family="monospace" font-size="8.5" fill="#FFF">VPN 20b</text>' +
      '<rect x="86" y="142" width="52" height="16" fill="#4D3D2A"/><text x="90" y="153" font-family="monospace" font-size="8.5" fill="#F8E2B8">Off 12b</text>' +
      '<text x="34" y="175" font-family="monospace" font-size="8.5" fill="#9CE0C2">PTE -&gt; PFN Translation</text>' +
      '<rect x="164" y="114" width="132" height="72" rx="4" fill="#1C2936" stroke="#6E94B8" stroke-width="1"/>' +
      '<text x="174" y="133" font-family="monospace" font-size="9.5" fill="#8CD7BC">CONCURRENCY &amp; I/O</text>' +
      '<line x1="174" y1="150" x2="282" y2="150" stroke="#4D6E8A" stroke-width="1"/>' +
      '<rect x="174" y="158" width="66" height="14" rx="2" fill="#A8442F"/><text x="180" y="168" font-family="monospace" font-size="8" fill="#FFF">LOCK GUARD</text>' +
      '<rect x="246" y="158" width="38" height="14" rx="2" fill="#1E5A4C"/><text x="252" y="168" font-family="monospace" font-size="8" fill="#FFF">FS</text>' +
    '</svg>'
  );

  window.DEMO_DATA = {
    books: [
      {
        id: 'wuye',
        title: '雾夜档案',
        author: '林砚',
        readType: 'literature',
        subKind: 'fiction',
        format: '.bcbook',
        cloth: '#1E3A36',
        coverKind: 'cloth',
        curChapter: 3,
        totalChapters: 12,
        progressPct: 25,
        watermark: 3,
        cachedCount: 4,
        kicker: 'LIT-01 · 推理小说',
        currentLabel: '第 3 章 · 钟楼里的第二把钥匙',
        filterGroup: 'literature',
        reading: true,
        isPdf: false
      },
      {
        id: 'shiji',
        title: '史记·项羽本纪选注',
        author: '司马迁',
        readType: 'history',
        subKind: '',
        format: '.bcbook',
        cloth: '#6B2B2B',
        coverKind: 'cloth',
        curChapter: 2,
        totalChapters: 8,
        progressPct: 28,
        watermark: 2,
        cachedCount: 3,
        kicker: 'HIS-02 · 纪传体通史',
        currentLabel: '第 2 章 · 巨鹿破釜与诸侯壁上',
        filterGroup: 'history',
        reading: true,
        isPdf: false
      },
      {
        id: 'kant',
        title: '纯粹理性批判（导读本）',
        author: '康德',
        readType: 'philosophy',
        subKind: '',
        format: '.bcbook',
        cloth: '#2C4056',
        coverKind: 'image-portrait',
        coverSvg: COVER_KANT_SVG,
        curChapter: 1,
        totalChapters: 10,
        progressPct: 12,
        watermark: 1,
        cachedCount: 3,
        kicker: 'PHI-03 · 先验哲学',
        currentLabel: '第 1 章 · 先天综合判断如何可能',
        filterGroup: 'philosophy',
        reading: true,
        isPdf: false
      },
      {
        id: 'micro',
        title: '微观激励与制度演进',
        author: '陈远',
        readType: 'social',
        subKind: '',
        format: 'EPUB',
        cloth: '#4A5839',
        coverKind: 'image-portrait',
        coverSvg: COVER_MICRO_SVG,
        curChapter: 4,
        totalChapters: 9,
        progressPct: 44,
        watermark: 4,
        cachedCount: 2,
        kicker: 'SOC-04 · 制度经济学',
        currentLabel: '第 4 章 · 委托代理与路径锁定',
        filterGroup: 'social',
        reading: true,
        isPdf: false
      },
      {
        id: 'euclid',
        title: '几何原本（明刻影印版）',
        author: '欧几里得',
        readType: 'science',
        subKind: '',
        format: '扫描 PDF',
        cloth: '#A87A3E',
        coverKind: 'image-portrait',
        coverSvg: COVER_EUCLID_SVG,
        curChapter: 14,
        totalChapters: 280,
        progressPct: 5,
        watermark: 14,
        cachedCount: 1,
        kicker: 'SCI-05 · 扫描影印',
        currentLabel: 'p.14 / 280 · 卷一命题四十七',
        filterGroup: 'pdf',
        reading: false,
        isPdf: true,
        pdfMode: 'scan'
      },
      {
        id: 'ostep',
        title: '操作系统导论',
        author: 'Remzi',
        readType: 'practical',
        subKind: '',
        format: '文字 PDF',
        cloth: '#3F4E5A',
        coverKind: 'image-wide',
        coverSvg: COVER_OSTEP_SVG,
        curChapter: 42,
        totalChapters: 320,
        progressPct: 13,
        watermark: 42,
        cachedCount: 2,
        kicker: 'PRAC-06 · 文字版 PDF',
        currentLabel: 'p.42 / 320 · 虚拟内存与页表转换',
        filterGroup: 'pdf',
        reading: true,
        isPdf: true,
        pdfMode: 'text'
      }
    ],

    /* 严格对齐 app/src/main/assets/profiles.generated.json 的 6+1 视角定义 */
    profiles: [
      {
        read_type: 'practical',
        name: '实用型书',
        adler_category: '1. 实用型书',
        goal: '锁定「目的 → 手段 → 成立条件 → 避坑边界」，提炼可执行 SOP、条件决策与失效复盘',
        blueprints: [
          { blueprint: 'flow_swimlane', label: 'SOP 流程图', description: '提炼可复用的先后执行步骤与避坑门禁' },
          { blueprint: 'decision_tree', label: '条件决策树', description: '明确什么场景用哪一招与止损红线' },
          { blueprint: 'quadrant_matrix', label: '优先级四象限', description: '精选高杠杆动作并标明行动口诀' }
        ],
        extensions: [
          { action: 'checklist', label: '行动自检清单', hint: '按启动前、执行中、踩坑红线三区提纯可勾选清单', cross_search: false },
          { action: 'failure_postmortem', label: '反例与失效复盘', hint: '拆解书内反面案例、常见执行误区与纠偏动作', cross_search: false }
        ]
      },
      {
        read_type: 'literature',
        name: '想象文学',
        adler_category: '2. 想象文学',
        goal: '重沉浸与体验：把握叙事张力、人物阵营、舞台/空间动线、伏笔视角与细读意象',
        blueprints: [
          { blueprint: 'tension_curve', label: '叙事张力曲线', description: '提炼 6–9 个关键戏剧节拍与情绪起伏' },
          { blueprint: 'relation_graph', label: '人物与阵营图谱', description: '按阵营分群展示核心人物关系、不对称信息与变化' },
          { blueprint: 'spatial_route', label: '空间与舞台动线', description: '自适应二维地点动线或封闭舞台调度图' }
        ],
        extensions: [
          { action: 'narrative_clues', label: '叙事视角与伏笔线索', hint: '梳理视角切换、时间结构、草蛇灰线与信息差（严禁剧透）', cross_search: false },
          { action: 'imagery', label: '意象、潜台词与细读', hint: '分三区提纯核心意象、舞台潜台词与声景名句细读', cross_search: false }
        ]
      },
      {
        read_type: 'history',
        name: '历史书',
        adler_category: '3. 历史书',
        goal: '多线并行时空对照、权力阵营拆解、历史因果演进与跨史家视角比对',
        blueprints: [
          { blueprint: 'parallel_timeline', label: '多线平行时间轴', description: '同一时期不同行为体、阵营或地区并置对照' },
          { blueprint: 'power_hierarchy', label: '权力与阵营结构', description: '拆解中枢、派系、盟友、敌对与制度约束' },
          { blueprint: 'spatial_route', label: '地理与空间态势', description: '展示真实地理方位、行军动线与地缘格局' },
          { blueprint: 'historical_causality', label: '历史因果演进图', description: '拆解深层结构、触发事件、催化机制与转折' }
        ],
        extensions: [
          { action: 'historical_dossier', label: '人物与名物档案', hint: '速查本章核心人物阵营与官制、条约、名物术语', cross_search: false },
          { action: 'compare_context', label: '多史家视角对照', hint: '三段式对照史观归因、史料盲区与同代世界史锚点', cross_search: true }
        ]
      },
      {
        read_type: 'science',
        name: '科学与数学',
        adler_category: '4. 科学与数学',
        goal: '理清公理/观测推导、临界相变与真实参数响应、物理/演化机制、核心词典与适用边界',
        blueprints: [
          { blueprint: 'derivation_dag', label: '公理-定理推导图', description: '广度汇流拆解前提/观测如何推出定理或科学结论' },
          { blueprint: 'sensitivity_chart', label: '临界条件与参数响应图', description: '呈现定性相变阈值区间或真实定量参数响应' },
          { blueprint: 'system_flow', label: '机制与装置流图', description: '拆解物理过程、实验装置或演化迭代机制' }
        ],
        extensions: [
          { action: 'concept_glossary', label: '核心概念与公式词典', hint: '速查核心科学概念、符号量纲、直觉类比与精确定义', cross_search: false },
          { action: 'boundary_check', label: '演进与适用边界', hint: '分三区梳理旧认知推翻、直观类比与极端失效条件', cross_search: false }
        ]
      },
      {
        read_type: 'philosophy',
        name: '哲学书',
        adler_category: '5. 哲学书',
        goal: '抠紧作者专属词义边界，剥离前提 → 推论 → 结论、批判谱系、论战交锋与反例边界',
        blueprints: [
          { blueprint: 'argument_map', label: '论证逻辑树', description: '剥离核心论点、并行前提、推论与潜在反驳' },
          { blueprint: 'term_contrast', label: '词义辨析双栏表', description: '对比日常汉语直觉与作者专属定义（附原词）' },
          { blueprint: 'lineage_dag', label: '批判谱系图', description: '呈现思想家、学派之间的继承、改造与批判关系' }
        ],
        extensions: [
          { action: 'sparring_dossier', label: '学术交锋档案', hint: '拆解核心论战交锋并剖析经典思想实验与隐喻', cross_search: false },
          { action: 'counterexample_lab', label: '反例与现实检验', hint: '揪出隐含前提、构造极端反例并检验当代适用边界', cross_search: false }
        ]
      },
      {
        read_type: 'social',
        name: '社会科学',
        adler_category: '6. 社会科学',
        goal: '以议题为中心：厘清混杂术语、多变量因果反馈与跨作者对照',
        blueprints: [
          { blueprint: 'causal_loop', label: '因果反馈回路图', description: '标出 + / - 反馈、滞后与锁定回路' },
          { blueprint: 'concept_matrix', label: '跨学派概念矩阵', description: '对照不同学派与政策立场对同一概念的界定分歧' },
          { blueprint: 'metric_chart', label: '实证数据与指标图', description: '还原原文真实统计数字，纯定性章节诚实退场' }
        ],
        extensions: [
          { action: 'method_audit', label: '方法与样本局限', hint: '识别本章研究方法、因果识别偏差与后续演进边界，并联动书库跨书综述', cross_search: true },
          { action: 'actor_incentives', label: '行动者激励与博弈', hint: '拆解各方利益主体的目标激励、约束条件与博弈策略', cross_search: false }
        ]
      },
      {
        read_type: 'visual',
        name: '图册与视觉',
        adler_category: '补充类（图册与设定集）',
        goal: '建立构图元素、文物/工艺原型与时代流派索引',
        blueprints: [
          { blueprint: 'element_matrix', label: '构图元素索引表', description: '整理视觉维度、原型出处与美术气氛' }
        ],
        extensions: [
          { action: 'craft_notes', label: '工艺流派考据', hint: '补足图注背后缺少的年代、技法与源流背景', cross_search: false }
        ]
      }
    ],

    bookChapters: {
      wuye: [
        { n: 1, title: '第 1 章 · 雨夜停摆的旧电车', chars: '8,420 字', min: '16 分钟', cached: true, map: true },
        { n: 2, title: '第 2 章 · 市政档案馆的缺页目录', chars: '9,110 字', min: '18 分钟', cached: true, map: true },
        { n: 3, title: '第 3 章 · 钟楼里的第二把钥匙', chars: '10,360 字', min: '21 分钟', cached: true, map: true },
        { n: 4, title: '第 4 章 · 渡口茶室的旧账本', chars: '8,790 字', min: '17 分钟', cached: false, map: false },
        { n: 5, title: '第 5 章 · 停产钟表厂的编号单', chars: '9,540 字', min: '19 分钟', cached: false, map: false },
        { n: 6, title: '第 6 章 · 雾港医院的值班记录', chars: '11,020 字', min: '22 分钟', cached: false, map: false },
        { n: 7, title: '第 7 章 · 地下蒸汽管道的回声', chars: '9,880 字', min: '19 分钟', cached: false, map: false },
        { n: 8, title: '第 8 章 · 被改写的火警电报', chars: '10,640 字', min: '21 分钟', cached: false, map: false },
        { n: 9, title: '第 9 章 · 铜齿与暗室底片', chars: '9,310 字', min: '18 分钟', cached: false, map: false },
        { n: 10, title: '第 10 章 · 终局：钟楼真相', chars: '12,480 字', min: '25 分钟', cached: false, map: false },
        { n: 11, title: '第 11 章 · 潮汐退去后的证词', chars: '8,950 字', min: '18 分钟', cached: false, map: false },
        { n: 12, title: '第 12 章 · 尾声：重响的整点钟', chars: '7,620 字', min: '15 分钟', cached: false, map: false }
      ],
      shiji: [
        { n: 1, title: '第 1 章 · 下相起兵与渡江', chars: '6,180 字', min: '12 分钟', cached: true, map: true },
        { n: 2, title: '第 2 章 · 巨鹿破釜与诸侯壁上', chars: '7,450 字', min: '15 分钟', cached: true, map: true },
        { n: 3, title: '第 3 章 · 鸿门宴与关中分封', chars: '8,920 字', min: '18 分钟', cached: false, map: false },
        { n: 4, title: '第 4 章 · 彭城奇袭与睢水之役', chars: '6,840 字', min: '14 分钟', cached: false, map: false },
        { n: 5, title: '第 5 章 · 荥阳成皋拉锯战', chars: '7,210 字', min: '14 分钟', cached: false, map: false },
        { n: 6, title: '第 6 章 · 鸿沟之约与背盟东进', chars: '5,960 字', min: '12 分钟', cached: false, map: false },
        { n: 7, title: '第 7 章 · 垓下四面楚歌', chars: '6,630 字', min: '13 分钟', cached: false, map: false },
        { n: 8, title: '第 8 章 · 乌江亭与太史公曰', chars: '5,410 字', min: '11 分钟', cached: false, map: false }
      ],
      kant: [
        { n: 1, title: '第 1 章 · 先天综合判断如何可能', chars: '9,240 字', min: '22 分钟', cached: true, map: true },
        { n: 2, title: '第 2 章 · 先验感性论：时间与空间', chars: '8,750 字', min: '20 分钟', cached: false, map: false },
        { n: 3, title: '第 3 章 · 知性范畴的先验演绎', chars: '11,420 字', min: '26 分钟', cached: false, map: false },
        { n: 4, title: '第 4 章 · 纯粹知性的图型法', chars: '7,890 字', min: '18 分钟', cached: false, map: false },
        { n: 5, title: '第 5 章 · 经验的类比与因果律', chars: '10,120 字', min: '24 分钟', cached: false, map: false },
        { n: 6, title: '第 6 章 · 现象与物自体之界碑', chars: '8,410 字', min: '19 分钟', cached: false, map: false },
        { n: 7, title: '第 7 章 · 纯粹理性的谬误推理', chars: '9,360 字', min: '21 分钟', cached: false, map: false },
        { n: 8, title: '第 8 章 · 四组二律背反', chars: '10,850 字', min: '25 分钟', cached: false, map: false },
        { n: 9, title: '第 9 章 · 纯粹理性的理想与神学批判', chars: '9,190 字', min: '21 分钟', cached: false, map: false },
        { n: 10, title: '第 10 章 · 先验方法论与理性建筑术', chars: '7,540 字', min: '17 分钟', cached: false, map: false }
      ],
      micro: [
        { n: 1, title: '第 1 章 · 交易费用与契约起点', chars: '7,820 字', min: '16 分钟', cached: true, map: true },
        { n: 2, title: '第 2 章 · 信息不对称与逆向选择', chars: '8,340 字', min: '17 分钟', cached: true, map: true },
        { n: 3, title: '第 3 章 · 不完备契约与剩余控制权', chars: '9,120 字', min: '18 分钟', cached: false, map: false },
        { n: 4, title: '第 4 章 · 委托代理与路径锁定', chars: '8,670 字', min: '17 分钟', cached: true, map: true },
        { n: 5, title: '第 5 章 · 组织声誉与隐性激励', chars: '7,950 字', min: '16 分钟', cached: false, map: false },
        { n: 6, title: '第 6 章 · 多任务代理中的指标扭曲', chars: '8,480 字', min: '17 分钟', cached: false, map: false },
        { n: 7, title: '第 7 章 · 地方实验与制度扩散', chars: '9,210 字', min: '19 分钟', cached: false, map: false },
        { n: 8, title: '第 8 章 · 既得利益集团与改革阻力', chars: '8,890 字', min: '18 分钟', cached: false, map: false },
        { n: 9, title: '第 9 章 · 制度均衡与变迁边界', chars: '7,430 字', min: '15 分钟', cached: false, map: false }
      ]
    },

    bookFrontBack: {
      wuye: [
        { kind: '前折页', title: '前折页 · 雾港老街区简图与人物表' },
        { kind: '版权页', title: '版权页 · 出版印次与电子版修订说明' },
        { kind: '总目录', title: '总目录 · 卷一至卷三编目' },
        { kind: '奥付', title: '奥付 · 排印字体与校勘记' }
      ],
      shiji: [
        { kind: '序跋', title: '太史公自序节选与三家注凡例' },
        { kind: '年表', title: '秦楚之际月表对照（前 209–前 202 年）' },
        { kind: '舆图', title: '巨鹿—彭城—垓下地理态势简图' },
        { kind: '校勘', title: '中华书局点校本异文校勘记' }
      ],
      kant: [
        { kind: '导言', title: 'A 版与 B 版序言对照说明' },
        { kind: '术语表', title: '德汉核心先验哲学术语译名表' },
        { kind: '总目录', title: '先验要素论与先验方法论编目' },
        { kind: '版权页', title: '学术导读本修订说明' }
      ],
      micro: [
        { kind: '序言', title: '中文版序：激励机制的微观基础' },
        { kind: '符号表', title: '博弈模型与契约符号索引' },
        { kind: '总目录', title: '上编（契约）与下编（演化）总目' },
        { kind: '参考文献', title: '核心实证文献索引与注释说明' }
      ]
    },

    bookPreviewBlocks: {
      shiji: [
        { id: 'B01', text: '项羽已杀卿子冠军，威震楚国，名闻诸侯。乃遣当阳君、蒲将军将卒二万渡河，救巨鹿。战少利，陈余复请兵。' },
        { id: 'B02', text: '项羽乃悉引兵渡河，皆沉船，破釜甑，烧庐舍，持三日粮，以示士卒必死，无一还心。于是至则围王离，与秦军遇，九战，绝其甬道，大破之。' },
        { id: 'B03', text: '当是时，楚兵冠诸侯。诸侯军救巨鹿下者十余壁，莫敢纵兵。及楚击秦，诸将皆从壁上观。楚战士无不一以当十，楚兵呼声动天，诸侯军无不人人惴恐。' },
        { id: 'B04', text: '于是已破秦军，项羽召见诸侯将，入辕门，无不膝行而前，莫敢仰视。项羽由是始为诸侯上将军，诸侯皆属焉。' }
      ],
      kant: [
        { id: 'B01', text: '我们的一切知识都从经验开始，这是毫无疑问的；因为如果对象不刺激我们的感官，认识能力又何以被唤醒而行动呢？' },
        { id: 'B02', text: '但尽管我们的一切知识都以经验开始，它们却并非都从经验中产生出来。很可能我们的经验知识本身就是由我们通过印象所接受的东西和我们自己的认识能力从自身中提供的东西合成的。' },
        { id: 'B03', text: '在所有包含着理性原则的理论科学中，都包含着作为原则的先天综合判断。例如纯粹数学判断固然全是综合的，但同时具有严格普遍性与必然性，不可能来自单纯经验归纳。' },
        { id: 'B04', text: '因此，纯粹理性的真正课题就包含在这个问题之中：先天综合判断是如何可能的？这一追问直接决定了形而上学能否作为科学立足。' }
      ],
      micro: [
        { id: 'B01', text: '当委托人与代理人之间存在私人信息与监督成本时，正式契约条款往往无法穷尽未来所有或然状态，制度由此产生内生的剩余控制权配置需求。' },
        { id: 'B02', text: '若考核激励过度偏向易于量化的短期产出，代理人便会把注意力从难以观测的长期治理质量抽离，形成经典的多任务扭曲效应。' },
        { id: 'B03', text: '更为隐蔽的是路径锁定机制：围绕旧规则形成的专用性人力资本与既得利益联盟，会系统性地提高制度变迁的边际转换成本。' },
        { id: 'B04', text: '只有在外部冲击打破既有租金分配均衡，或试点机制降低了试错风险时，新的激励相容规则才可能取代低效稳态。' }
      ]
    },

    wuyeChapters: [
      { n: 1, title: '第 1 章 · 雨夜停摆的旧电车', chars: '8,420 字', min: '16 分钟', cached: true, map: true },
      { n: 2, title: '第 2 章 · 市政档案馆的缺页目录', chars: '9,110 字', min: '18 分钟', cached: true, map: true },
      { n: 3, title: '第 3 章 · 钟楼里的第二把钥匙', chars: '10,360 字', min: '21 分钟', cached: true, map: true },
      { n: 4, title: '第 4 章 · 渡口茶室的旧账本', chars: '8,790 字', min: '17 分钟', cached: false, map: false },
      { n: 5, title: '第 5 章 · 停产钟表厂的编号单', chars: '9,540 字', min: '19 分钟', cached: false, map: false },
      { n: 6, title: '第 6 章 · 雾港医院的值班记录', chars: '11,020 字', min: '22 分钟', cached: false, map: false },
      { n: 7, title: '第 7 章 · 地下蒸汽管道的回声', chars: '9,880 字', min: '19 分钟', cached: false, map: false },
      { n: 8, title: '第 8 章 · 被改写的火警电报', chars: '10,640 字', min: '21 分钟', cached: false, map: false },
      { n: 9, title: '第 9 章 · 铜齿与暗室底片', chars: '9,310 字', min: '18 分钟', cached: false, map: false },
      { n: 10, title: '第 10 章 · 终局：钟楼真相', chars: '12,480 字', min: '25 分钟', cached: false, map: false },
      { n: 11, title: '第 11 章 · 潮汐退去后的证词', chars: '8,950 字', min: '18 分钟', cached: false, map: false },
      { n: 12, title: '第 12 章 · 尾声：重响的整点钟', chars: '7,620 字', min: '15 分钟', cached: false, map: false }
    ],

    wuyeFrontBack: [
      { kind: '前折页', title: '前折页 · 雾港老街区简图与人物表' },
      { kind: '版权页', title: '版权页 · 出版印次与电子版修订说明' },
      { kind: '总目录', title: '总目录 · 卷一至卷三编目' },
      { kind: '奥付', title: '奥付 · 排印字体与校勘记' }
    ],

    chapterBlocks: [
      {
        id: 'B01',
        text: '十一点一刻的雾刚漫过石拱桥，顾沉把旧皮箱搁在钟楼底层的湿青砖上。铁门没有上锁，门轴却像是刚被人涂过一层机油，推开时竟没发出半点铁锈摩擦的尖响。'
      },
      {
        id: 'B02',
        text: '手电筒的光柱切开浮尘，落在值班台边缘的一枚黄铜钥匙上。顾沉戴上手套把它拎起来，对着光细看：',
        highlight: {
          id: 'hl-1',
          color: 'amber',
          text: '铜钥匙背面的缺口不是磨损，而是故意锉出的三道齿痕'
        },
        afterHighlight: '，齿距恰好能卡住老式瑞士机芯的发条锁片。',
        footnote: {
          id: 'fn-45',
          num: '45',
          label: '[45]',
          summary: '1990 年代港口公共钟楼多采用“双匙互锁”机械装置，主钥匙管报时校准，副钥匙用于紧急制动摆锤与检修通道。',
          source: '《雾港市政修缮志·卷三》（页 118）'
        }
      },
      {
        id: 'B03',
        text: '楼梯拐角传来一阵极轻的脚步声，停在三层报时台上方。顾沉熄了电筒，贴着冰凉的石墙数到七。楼上的人似乎把一卷图纸塞进了风道，铁皮挡板合拢时发出闷沉的一响。',
        pinNote: {
          id: 'pin-1',
          excerpt: '铁皮挡板合拢时发出闷沉的一响',
          body: '此处风道与 B05 所提“机械报时图纸”形成结构呼应：嫌疑人并非来取走旧物，而是趁雾夜掩护把新伪造或未烧毁的蓝图重新藏回钟楼内部。'
        }
      },
      {
        id: 'B04',
        text: '“沈医生，这么晚还来查对老钟的走时吗？”说话的是守钟人老周，他站在二层栏杆阴影里，手里提着一盏煤油风灯，灯晕把墙面上剥落的油漆照成一片暗红。'
      },
      {
        id: 'B05',
        text: '顾沉没有立刻接话，只是把那枚三道齿痕的铜钥匙摊在掌心，迎着风灯微弱的火苗举高半寸。老周的呼吸明显顿了一下，随口解释道：',
        highlight: {
          id: 'hl-2',
          color: 'pine',
          text: '一九九八年冬夜那场大火烧毁的不是卷宗室，而是旧钟楼的机械报时图纸'
        },
        afterHighlight: '；从那以后，二号备用门就再也没有人从里面打开过。'
      },
      {
        id: 'B06',
        text: '然而顾沉在门闩内侧摸到了一道尚未风干的松烟指印。若二号门真如老周所言封死了二十六年，这道混着松脂气味的油墨印记，便只可能来自最近三天内曾借用过档案馆暗室的人。',
        footnote: {
          id: 'fn-46',
          num: '46',
          label: '[46]',
          summary: '松烟墨混合松香油墨常见于老式市政测绘蓝图晒印补描工艺，不易被普通清水洗净，气味可留存七十二小时以上。',
          source: '法医物证备忘 · 第 2 章第 14 段关联'
        }
      },
      {
        id: 'B07',
        text: '三层的风道口这时又滴下一颗凝结的水珠，刚好砸在顾沉肩头的风衣上。顾沉抬头望去，隐约看见百叶窗缝隙外闪过一道灰色风衣的衣角，正沿着西侧外墙的消防铁梯迅速往下滑去。'
      },
      {
        id: 'B08',
        text: '整点报时的铜锤在零点前三十秒猛然抬起，齿轮咬合的轰鸣瞬间吞没了铁梯上的所有脚步声。顾沉握紧那枚不合年代的铜钥匙，知道自己已经踩进了对方故意留下的下一环。'
      }
    ],

    chapterMap: {
      sections: [
        {
          title: '潜入与物证现形',
          summary: '钟楼铁门异常润滑，发现三道人为锉出的齿痕铜钥匙',
          block_ids: ['B01', 'B02'],
          nodes: [
            { id: 'm1', summary: '雨夜进入未锁钟楼：门轴新涂机油暗示近期有人出入', block_ids: ['B01'] },
            { id: 'm2', summary: '值班台第二把铜钥匙：三道齿痕对应机械发条制动锁', block_ids: ['B02'] }
          ]
        },
        {
          title: '楼上暗影与对峙',
          summary: '三层风道藏匿图纸，与守钟人老周围绕 1998 年大火交锋',
          block_ids: ['B03', 'B04', 'B05'],
          nodes: [
            { id: 'm3', summary: '三层风道异响：神秘人反向藏入图纸后退走', block_ids: ['B03'] },
            { id: 'm4', summary: '老周供出 1998 年火灾真正烧毁的是机械报时图纸', block_ids: ['B05'] }
          ]
        },
        {
          title: '矛盾拆穿与追逐伏笔',
          summary: '松烟指印戳穿封门谎言，灰衣人沿消防铁梯借钟声脱身',
          block_ids: ['B06', 'B07', 'B08'],
          nodes: [
            { id: 'm5', summary: '门闩内侧松烟油墨指印：锁定三天内接触过晒图暗室者', block_ids: ['B06'] },
            { id: 'm6', summary: '零点钟鸣掩护灰衣人撤离，留下新线索钩子', block_ids: ['B07', 'B08'] }
          ]
        }
      ]
    },

    cachedToolsInitial: [
      'chapter_map',
      'tension_curve', 'relation_graph', 'spatial_route', 'narrative_clues',
      'parallel_timeline', 'power_hierarchy', 'historical_causality', 'historical_dossier',
      'argument_map', 'term_contrast', 'lineage_dag',
      'flow_swimlane', 'decision_tree', 'quadrant_matrix', 'checklist',
      'causal_loop', 'concept_matrix', 'metric_chart', 'actor_incentives',
      'derivation_dag', 'sensitivity_chart', 'system_flow', 'concept_glossary',
      'element_matrix'
    ],

    figures: {
      /* ---------------- 想象文学 ---------------- */
      tension_curve: {
        blueprint: 'tension_curve',
        caption: '第 3 章叙事张力曲线：钟楼夜探与试探交锋',
        reading_note: '全章张力呈现「静谧潜入 → 骤停屏息 → 钥匙试探 → 谎言戳穿 → 钟声爆鸣」的阶梯式拉升。',
        audit_log: ['7 个节拍全部绑定 B01–B08 原文锚点', '节拍类型涵盖 setup / foreshadow / escalation / climax / aftermath'],
        data: {
          points: [
            { beat: '门轴无声', beat_kind: 'setup', tension: 24, emotion: '疑窦初生', note: '门轴新涂机油却未上锁，推门瞬间打破荒废假象', blocks: ['B01'], source: '原文' },
            { beat: '铜匙三齿', beat_kind: 'foreshadow', tension: 42, emotion: '专注审视', note: '人为锉出的三道齿痕指向老式发条制动装置', blocks: ['B02'], source: '原文' },
            { beat: '风道闷响', beat_kind: 'escalation', tension: 68, emotion: '屏息蛰伏', note: '熄灯贴墙数到七，听见三层将图纸藏入风道', blocks: ['B03'], source: '原文' },
            { beat: '风灯对峙', beat_kind: 'relief', tension: 52, emotion: '表面试探', note: '老周持风灯现身，用日常寒暄掩盖双方戒心', blocks: ['B04'], source: '原文' },
            { beat: '1998 火案', beat_kind: 'foreshadow', tension: 76, emotion: '震动追问', note: '摊出铜匙逼出真话：当年烧毁的实为报时图纸', blocks: ['B05'], source: '原文' },
            { beat: '松烟指印', beat_kind: 'climax', tension: 90, emotion: '识破伪证', note: '门闩内侧三天内留下的油墨印戳穿“封死二十六年”', blocks: ['B06'], source: '原文' },
            { beat: '零点钟鸣', beat_kind: 'aftermath', tension: 82, emotion: '悬念延续', note: '铜锤轰鸣吞没脚步声，灰衣人沿消防梯脱身', blocks: ['B07', 'B08'], source: '原文' }
          ]
        }
      },
      relation_graph: {
        blueprint: 'relation_graph',
        caption: '第 3 章人物阵营与信息差图谱',
        reading_note: '三方在钟楼内构成「追查者—守门人—暗影藏图者」的不对称信息三角。',
        audit_log: ['阵营分群完成，所有连线均标注 rel_kind 语义'],
        data: {
          nodes: [
            { id: 'gu', label: '顾沉（法医调查者）', role: 'protagonist', camp: '追查方', arc: '从被动勘验转向主动诱供', detail: '持三齿铜钥匙并发现门闩内侧松烟指印，暂未拆穿老周。', blocks: ['B01', 'B02', 'B06'], source: '原文' },
            { id: 'zhou', label: '老周（钟楼守夜人）', role: 'antagonist', camp: '知情守卫方', arc: '隐瞒近期开门事实，抛出 1998 年火灾半真半假供词', detail: '声称二号门封死二十六年，却对铜钥匙露出瞬时失态。', blocks: ['B04', 'B05'], source: '原文' },
            { id: 'gray', label: '灰衣人（三层藏图者）', role: 'catalyst', camp: '未知幕后方', arc: '借风道藏匿图纸后沿消防铁梯撤离', detail: '留下松烟指印与百叶窗外灰色衣角，尚未露面。', blocks: ['B03', 'B07'], source: '原文' },
            { id: 'archive', label: '档案馆暗室关联者', role: 'observer', camp: '未知幕后方', arc: '提供晒图松烟油墨的源头线索', detail: '与第 2 章缺页目录形成跨章指向（不越过第 3 章水位线）。', blocks: ['B06'], source: '补充' }
          ],
          edges: [
            { from: 'gu', to: 'zhou', label: '举匙试探', rel_kind: 'asymmetry', note: '顾沉隐瞒已知的门闩指印，观察老周反应' },
            { from: 'zhou', to: 'gu', label: '谎称封门', rel_kind: 'conflict', note: '以 1998 火灾掩盖近期内部出入' },
            { from: 'gray', to: 'zhou', label: '默契/掩护', rel_kind: 'asymmetry', note: '灰衣人能无声出入润滑铁门且避开老周风灯' },
            { from: 'archive', to: 'gray', label: '油墨溯源', rel_kind: 'bond', note: '松烟指印串联暗室测绘蓝图' }
          ]
        }
      },
      spatial_route: {
        blueprint: 'spatial_route',
        caption: '钟楼夜间空间与垂直动线图',
        reading_note: '同一垂直建筑内三条动线交错：顾沉由底向上，老周守二层阴影，灰衣人由三层风道转消防铁梯下行。',
        audit_log: ['mode=route_map 坐标已归一化到 0..100，碰撞检测 0 重叠'],
        data: {
          mode: 'route_map',
          stage_desc: '雾港旧钟楼 · 一至三层垂直剖面',
          nodes: [
            { id: 'gate', label: '一层湿砖铁门', role: 'threshold', zone: '一层入口', x: 18, y: 78, detail: '门轴刚涂机油，门闩内侧留有松烟指印', blocks: ['B01', 'B06'], source: '原文' },
            { id: 'desk', label: '值班台（铜钥匙）', role: 'prop', zone: '一层大厅', x: 46, y: 72, detail: '手电光柱下发现三道齿痕黄铜钥匙', blocks: ['B02'], source: '原文' },
            { id: 'stair2', label: '二层栏杆阴影', role: 'transit', zone: '二层平台', x: 38, y: 45, detail: '老周提煤油风灯站立处，与顾沉正面试探', blocks: ['B04', 'B05'], source: '原文' },
            { id: 'duct3', label: '三层报时台风道', role: 'destination', zone: '三层机房', x: 64, y: 20, detail: '灰衣人塞入图纸卷筒并合拢铁皮挡板', blocks: ['B03'], source: '原文' },
            { id: 'ladder', label: '西侧消防铁梯', role: 'outside', zone: '外墙西侧', x: 85, y: 48, detail: '零点钟鸣掩护下，灰衣人沿外梯速降脱身', blocks: ['B07', 'B08'], source: '原文' }
          ],
          edges: [
            { from: 'gate', to: 'desk', label: '潜入拾匙', actor: '顾沉', kind: 'move', note: '推门无声，光柱扫出钥匙' },
            { from: 'desk', to: 'stair2', label: '屏息上行', actor: '顾沉', kind: 'move', note: '熄灭电筒贴墙数到七' },
            { from: 'stair2', to: 'desk', label: '风灯对峙', actor: '老周', kind: 'gaze', note: '阴影中居高临下试探顾沉' },
            { from: 'duct3', to: 'ladder', label: '藏图急撤', actor: '灰衣人', kind: 'parallel', note: '水珠滴落瞬间翻窗滑下铁梯' }
          ]
        }
      },
      narrative_clues: {
        blueprint: 'extension_dossier',
        caption: '叙事视角与伏笔线索（严格锁至第 3 章）',
        reading_note: '仅基于第 1–3 章已读文本梳理草蛇灰线，绝不越界泄露后续章节答案。',
        sections: [
          {
            id: 'clues',
            title: '本章埋下的三条硬线索',
            items: [
              { title: '门轴新机油与门闩松烟印', role_badge: '物证矛盾', source: '原文', body: '门外未锁、门内门闩却有三天内的松烟指印，说明出入者是从内部反锁或拥有内侧权限。', blocks: ['B01', 'B06'] },
              { title: '三道人为锉出的钥匙齿痕', role_badge: '关键道具', source: '原文', body: '非自然磨损，专门匹配老式瑞士机芯发条锁片，暗示来者懂得钟楼机械制动。', blocks: ['B02'] },
              { title: '1998 火灾的真正标的物', role_badge: '历史缺口', source: '原文', body: '外界以为烧毁的是市政卷宗，实则是旧钟楼的机械报时图纸，解释了为何有人深夜藏图。', blocks: ['B05'] }
            ]
          },
          {
            id: 'pov',
            title: '有限第三人称信息差控制',
            items: [
              { title: '顾沉“摊匙不摊印”的留白', role_badge: '视角节制', source: '原文', body: '顾沉只出示钥匙试探老周，暂不揭穿松烟印，把推理张力留给后续跨章验证。', blocks: ['B05', 'B06'] }
            ]
          }
        ]
      },
      imagery: {
        blueprint: 'extension_dossier',
        caption: '意象、潜台词与声景细读',
        reading_note: '本章通过“光暗反差”与“金属/水声声景”营造密闭空间压迫感。',
        sections: [
          {
            id: 'imagery_core',
            title: '核心视觉与光影意象',
            items: [
              { title: '电筒冷光 vs 煤油风灯暗红晕', role_badge: '双光源对照', source: '原文', body: '手电筒代表现代法医勘验的理性切片；老周手中的煤油风灯把剥落油漆照成暗红，象征旧日火灾记忆。', blocks: ['B02', 'B04'] }
            ]
          },
          {
            id: 'soundscape',
            title: '声景与动作潜台词',
            items: [
              { title: '无声推门 → 铁皮闷响 → 水珠滴落 → 零点铜锤轰鸣', role_badge: '四重声景阶梯', source: '原文', body: '前段极静放大风道一响，尾段铜锤轰鸣则成为天然消音器，完美掩护灰衣人脱身。', blocks: ['B01', 'B03', 'B07', 'B08'] }
            ]
          }
        ]
      },

      /* ---------------- 历史书 ---------------- */
      parallel_timeline: {
        blueprint: 'parallel_timeline',
        caption: '多线平行时间轴：1998 火案至雾夜钟楼交汇',
        reading_note: '将二十六年前的档案事件与本章实时夜探并置对照，定位时间差与证词冲突。',
        data: {
          lanes: ['顾沉行动线', '守钟人 / 暗影线', '档案与制度背景'],
          rows: [
            {
              time: '1998 冬夜',
              cells: [
                null,
                { title: '老周见证火灾发生', note: '声称二号备用门自此封死', blocks: ['B05'], source: '原文' },
                { title: '钟楼机械报时图纸“焚毁”', note: '对外登记为卷宗室受灾', blocks: ['B05'], source: '原文' }
              ]
            },
            {
              time: '前三日内',
              cells: [
                { title: '查对档案馆缺页目录', note: '发现与旧市政测绘相关线索', blocks: ['B06'], source: '补充' },
                { title: '暗室接触者出入二号门', note: '门轴涂机油并在门闩留松烟指印', blocks: ['B01', 'B06'], source: '原文' },
                { title: '松烟晒图油墨补描', note: '气味与指印留存期 ≤ 72 小时', blocks: ['B06'], source: '原文' }
              ]
            },
            {
              time: '23:15 – 00:00',
              cells: [
                { title: '拾获三齿铜匙并试探老周', note: '发现风道藏图与西侧灰衣人', blocks: ['B02', 'B04', 'B07'], source: '原文' },
                { title: '藏图入风道并借钟声撤离', note: '沿西侧消防铁梯滑下脱身', blocks: ['B03', 'B08'], source: '原文' },
                { title: '零点铜锤整点报时启动', note: '机械齿轮轰鸣掩盖现场声迹', blocks: ['B08'], source: '原文' }
              ]
            }
          ],
          aftermath: [
            { title: '史料矛盾核验结论', body: '“封门二十六年”的口述证词与“门轴新机油 + 三日内松烟印”构成直接物证反证。', blocks: ['B01', 'B05', 'B06'], source: '原文' }
          ]
        }
      },
      power_hierarchy: {
        blueprint: 'power_hierarchy',
        caption: '钟楼事件权力与信息控制结构',
        reading_note: '以钟楼物理控制权与图纸知识权为轴，拆解各方筹码与制约。',
        data: {
          nodes: [
            { id: 'city', label: '市政档案馆档案体系', role: 'sovereign', detail: '掌管官方修缮志与火灾定性记录', blocks: ['B02', 'B05'], source: '补充' },
            { id: 'gu', label: '顾沉（外部调查权）', role: 'ally', detail: '凭借现场勘验技术识别齿痕与松烟印', blocks: ['B02', 'B06'], source: '原文' },
            { id: 'zhou', label: '老周（现场看守权）', role: 'faction', detail: '控制风灯视野与二层通道，掌握火灾口述史', blocks: ['B04', 'B05'], source: '原文' },
            { id: 'gray', label: '灰衣人（图纸掌握方）', role: 'rival', detail: '能自由调度机械锁、风道与消防铁梯', blocks: ['B03', 'B07'], source: '原文' },
            { id: 'clock', label: '双匙互锁机械制度', role: 'constraint', detail: '物理限定唯有懂瑞士机芯者方能制动报时锤', blocks: ['B02', 'B08'], source: '原文' }
          ],
          edges: [
            { from: 'city', to: 'zhou', label: '名义授权看守', relation_type: 'alliance' },
            { from: 'gu', to: 'zhou', label: '物证质询', relation_type: 'hostile' },
            { from: 'gray', to: 'zhou', label: '借道默契/制衡', relation_type: 'balance' },
            { from: 'clock', to: 'gray', label: '需用特制三齿匙', relation_type: 'constraint' }
          ]
        }
      },
      historical_causality: {
        blueprint: 'historical_causality',
        caption: '历史因果演进图：1998 火案余波如何促成今夜交锋',
        data: {
          nodes: [
            { id: 's1', label: '1998 火灾掩盖图纸真相', role: 'structural', detail: '官方记录为卷宗受灾，实际核心机械报时图纸失踪', blocks: ['B05'], source: '原文' },
            { id: 't1', label: '测绘蓝图在暗室被补描复现', role: 'trigger', detail: '三天内有人使用松烟油墨重新处理图纸', blocks: ['B06'], source: '原文' },
            { id: 'm1', label: '深夜潜回钟楼风道藏匿', role: 'mechanism', detail: '提前润滑铁门并准备三齿制动钥匙', blocks: ['B01', 'B02', 'B03'], source: '原文' },
            { id: 'c1', label: '顾沉夜探与松烟印暴露', role: 'turning_point', detail: '原本严密的藏图行动因油墨气味与水珠露馅', blocks: ['B06', 'B07'], source: '原文' },
            { id: 'o1', label: '双方进入明暗博弈下一轮', role: 'consequence', detail: '灰衣人撤离，顾沉握紧钥匙锁定追查方向', blocks: ['B08'], source: '原文' }
          ],
          edges: [
            { from: 's1', to: 't1', label: '遗留谜团' },
            { from: 't1', to: 'm1', label: '驱使藏匿' },
            { from: 'm1', to: 'c1', label: '留下物理破绽' },
            { from: 'c1', to: 'o1', label: '触发正面追查' }
          ]
        }
      },
      historical_dossier: {
        blueprint: 'extension_dossier',
        caption: '人物与名物考据档案',
        sections: [
          {
            id: 'objs',
            title: '器物与制度考据',
            items: [
              { title: '双匙互锁瑞士钟楼机芯', role_badge: '机械名物', source: '原文', body: '三道人为锉出的齿痕专为绕过常规主匙校验、直接锁止发条擒纵片而设。', blocks: ['B02'] },
              { title: '松烟混松香晒图油墨', role_badge: '档案工艺', source: '原文', body: '1990 年代测绘蓝图局部修补常用配方，留味可达 72 小时以上。', blocks: ['B06'] }
            ]
          }
        ]
      },
      compare_context: {
        blueprint: 'extension_dossier',
        caption: '多史家 / 跨书视角对照（已连接桌面书库）',
        reading_note: '跨书检索比对《雾港市政修缮志》与《近代城市消防档案汇编》对 1998 年火灾的三种归因口径。',
        sections: [
          {
            id: 'hist_cmp',
            title: '跨书史观与档案盲区对照',
            items: [
              { title: '口径 A：市政官方修缮志（电路老化说）', role_badge: '官方叙事', source: '补充', body: '将 1998 年火案归结为冬季线路超载，仅列明“卷宗室轻度损毁”，未提钟楼机房。', blocks: ['B05'] },
              { title: '口径 B：老周现场口述（图纸焚毁说）', role_badge: '当事证词', source: '原文', body: '承认烧毁物实为机械报时图纸，但以“二号门封死二十六年”切割后续责任。', blocks: ['B05'] },
              { title: '口径 C：物证交叉校验（图纸转移隐匿说）', role_badge: '实证修正', source: '原文', body: '结合松烟新印与风道藏图声响，推断图纸当年并未全毁，而是被秘密保存并重绘。', blocks: ['B03', 'B06'] }
            ]
          }
        ]
      },

      /* ---------------- 哲学书 ---------------- */
      argument_map: {
        blueprint: 'argument_map',
        caption: '论证逻辑树：如何从物证推翻“封门二十六年”命题',
        reading_note: '剥离经验观察前提、物化衰变规律与老周陈述之间的逻辑矛盾。',
        data: {
          nodes: [
            { id: 'p1', label: '前提一：老周声称二号门封死 26 年', role: 'premise', detail: '若属实，内侧不应出现新鲜人工接触痕迹', blocks: ['B05'], source: '原文' },
            { id: 'p2', label: '前提二：门闩内侧摸到未风干松烟指印', role: 'premise', detail: '油墨指印具有 72 小时内挥发风干的时间窗口', blocks: ['B06'], source: '原文' },
            { id: 'p3', label: '前提三：门轴新涂机油、推门无锈响', role: 'condition', detail: '长期封死铁门必然氧化生锈，润滑是近期维护结果', blocks: ['B01'], source: '原文' },
            { id: 'i1', label: '推论：三天内必有人经由内侧出入并接触暗室', role: 'inference', detail: '油墨溯源指向档案馆晒图暗室', blocks: ['B06'], source: '原文' },
            { id: 'r1', label: '潜在反驳：风道或外部潜入是否可能？', role: 'rebuttal', detail: '灰衣人走风道与外梯，但门闩在“内侧”且门轴新油', blocks: ['B03', 'B07'], source: '原文' },
            { id: 'c1', label: '结论：老周的“封门证词”在经验上不成立', role: 'conclusion', detail: '要么老周撒谎，要么内部另有未被记录的通行通道', blocks: ['B05', 'B06'], source: '原文' }
          ],
          edges: [
            { from: 'p2', to: 'i1', label: '物证时效推出' },
            { from: 'p3', to: 'i1', label: '辅助佐证' },
            { from: 'p1', to: 'c1', label: '对照冲突' },
            { from: 'i1', to: 'c1', label: '否证证词' },
            { from: 'r1', to: 'c1', label: '排除后强化' }
          ]
        }
      },
      term_contrast: {
        blueprint: 'term_contrast',
        caption: '关键概念词义辨析双栏表',
        data: {
          columns: ['核心概念', '日常直觉理解', '本章/推演专属界定', '正文锚点'],
          rows: [
            { key: '封死', original_term: 'Sealed / Inaccessible', everyday: '门锁上以后多年未曾使用', author: '物理锁闭但存在内侧操作痕迹的“话语遮蔽状态”', blocks: ['B05', 'B06'], source: '原文' },
            { key: '磨损 vs 锉痕', original_term: 'Wear vs Intentional Filing', everyday: '旧钥匙表面的缺口坑洼', author: '具有明确功能目的（卡住发条锁片）的人工改装编码', blocks: ['B02'], source: '原文' },
            { key: '报时图纸', original_term: 'Horological Blueprint', everyday: '建筑维修参考的普通图样', author: '同时承载机械结构密码与 1998 火案动机的关键文本', blocks: ['B03', 'B05'], source: '原文' }
          ]
        }
      },
      lineage_dag: {
        blueprint: 'lineage_dag',
        caption: '认识论与推理路径批判谱系图',
        data: {
          nodes: [
            { id: 'emp', label: '古典经验论：直接感官物证（油墨/机油）', role: 'predecessor', detail: '依赖触觉与嗅觉对物理衰变时效的直接把握', blocks: ['B01', 'B06'], source: '补充' },
            { id: 'testimony', label: '口述证词主义（老周的权威陈述）', role: 'target', detail: '以在场守夜人身份宣称 26 年历史事实', blocks: ['B04', 'B05'], source: '原文' },
            { id: 'abduction', label: '皮尔士溯因推理（最佳解释推论）', role: 'mediator', detail: '从反常细节（三道锉痕）反推制造动机', blocks: ['B02'], source: '补充' },
            { id: 'gu_pos', label: '顾沉立场：物证否证 + 延迟揭穿的实践理性', role: 'author', detail: '在证据链闭环前保留审慎沉默，继续追踪行动者', blocks: ['B05', 'B06', 'B08'], source: '原文' }
          ],
          edges: [
            { from: 'emp', to: 'gu_pos', label: '提供物理基准', relation: 'inherit' },
            { from: 'abduction', to: 'gu_pos', label: '转化为侦查方法', relation: 'transform' },
            { from: 'gu_pos', to: 'testimony', label: '否证其自洽性', relation: 'critique' }
          ]
        }
      },
      sparring_dossier: {
        blueprint: 'extension_dossier',
        caption: '交锋与思想实验档案',
        sections: [
          {
            id: 'sparring',
            title: '证词信任 vs 物理痕迹的认识论交锋',
            items: [
              { title: '风灯对话中的“悬置判断”策略', role_badge: '认知博弈', source: '原文', body: '面对老周“自那以后无人打开”的全称否定命题，顾沉不以言语争辩，而以未风干松烟印在心中完成否证。', blocks: ['B05', 'B06'] }
            ]
          }
        ]
      },
      counterexample_lab: {
        blueprint: 'extension_dossier',
        caption: '反例检验与推理边界',
        sections: [
          {
            id: 'hidden_premise',
            title: '隐含前提与极端反例构造',
            items: [
              { title: '反例检验：松烟印是否可能来自外界涂抹？', role_badge: '边界反例', source: '原文', body: '因指印位于“门闩内侧”，外界人员若不先入内便无法触及，故排除了恶作剧涂抹可能。', blocks: ['B06'] }
            ]
          }
        ]
      },

      /* ---------------- 实用型书 ---------------- */
      flow_swimlane: {
        blueprint: 'flow_swimlane',
        caption: '夜间密闭建筑现场勘验 SOP 流程图',
        reading_note: '提炼顾沉在钟楼勘验中展现的标准化五步动作与门禁检验点。',
        data: {
          nodes: [
            { id: 'f1', label: '前置检查：门锁与铰链状态观察', role: 'input', detail: '不急于入内，先判断门轴阻力与异常润滑', blocks: ['B01'], source: '原文' },
            { id: 'f2', label: '物证提取：戴手套收集金属异物', role: 'action', detail: '避免污染指纹，逆光检查齿痕为磨损抑或人工锉痕', blocks: ['B02'], source: '原文' },
            { id: 'f3', label: '声学静默门禁：熄灯贴墙听音辨位', role: 'gate', detail: '遇上方异响立即关闭强光，计数确认目标动作结束', blocks: ['B03'], source: '原文' },
            { id: 'f4', label: '避坑警报：切勿当场亮出全部底牌', role: 'pitfall', detail: '仅用半真线索试探知情人，观察其呼吸微表情', blocks: ['B05', 'B06'], source: '原文' },
            { id: 'f5', label: '阶段产出：交叉锁定物理时间窗', role: 'output', detail: '通过门闩内侧未干松烟印锁定 72 小时内嫌疑范围', blocks: ['B06'], source: '原文' }
          ],
          edges: [
            { from: 'f1', to: 'f2', label: '确认近期出入' },
            { from: 'f2', to: 'f3', label: '突发声响' },
            { from: 'f3', to: 'f4', label: '遭遇守卫' },
            { from: 'f4', to: 'f5', label: '完成印证' }
          ]
        }
      },
      decision_tree: {
        blueprint: 'decision_tree',
        caption: '现场遇袭/遭遇第三人条件决策树',
        data: {
          nodes: [
            { id: 'd0', label: '场景诊断：夜间封闭空间听见上方异响', role: 'diagnosis', detail: '评估光源暴露风险与逃生出口方向', blocks: ['B03'], source: '原文' },
            { id: 'd1', label: '判断条件：对方是否已发现己方位置？', role: 'condition', detail: '听脚步是否停顿及风灯是否照射过来', blocks: ['B03', 'B04'], source: '原文' },
            { id: 'd2', label: '推荐动作：熄灯贴墙静默等待七秒', role: 'strategy', detail: '利用建筑阴影保存隐蔽优势，记录风道声响', blocks: ['B03'], source: '原文' },
            { id: 'd3', label: '止损红线：已被风灯照见时转为口头试探', role: 'fallback', detail: '顺势亮出部分物证观察对方微反应，保留松烟印后手', blocks: ['B04', 'B05'], source: '原文' }
          ],
          edges: [
            { from: 'd0', to: 'd1', label: '立即评估' },
            { from: 'd1', to: 'd2', label: '尚未暴露' },
            { from: 'd1', to: 'd3', label: '已被撞见' }
          ]
        }
      },
      quadrant_matrix: {
        blueprint: 'quadrant_matrix',
        caption: '现场线索价值与验证紧迫度四象限',
        data: {
          xAxis: '物证保鲜紧迫度（低 → 高）',
          yAxis: '推理决定性权重（低 → 高）',
          quadrants: [
            {
              pos: 'q-top-right',
              title: '立即固定 · 核心铁证',
              action_hint: '72h 内优先采样',
              items: [
                { text: '门闩内侧尚未风干的松烟油墨指印', blocks: ['B06'], source: '原文' },
                { text: '三层风道内刚藏入的图纸卷筒', blocks: ['B03'], source: '原文' }
              ]
            },
            {
              pos: 'q-top-left',
              title: '长期留存 · 结构铁证',
              action_hint: '封存比对工具痕',
              items: [
                { text: '三道人为锉出的黄铜发条钥匙', blocks: ['B02'], source: '原文' }
              ]
            },
            {
              pos: 'q-bot-right',
              title: '即时观察 · 环境异常',
              action_hint: '现场拍照记录',
              items: [
                { text: '门轴刚涂抹的新鲜机油与未锁铁门', blocks: ['B01'], source: '原文' },
                { text: '百叶窗外闪过的灰色风衣与消防梯', blocks: ['B07'], source: '原文' }
              ]
            },
            {
              pos: 'q-bot-left',
              title: '存疑待核 · 口头证词',
              action_hint: '勿轻信单一陈述',
              items: [
                { text: '老周关于 1998 火灾及封门的陈述', blocks: ['B04', 'B05'], source: '原文' }
              ]
            }
          ]
        }
      },
      checklist: {
        blueprint: 'extension_checklist',
        caption: '可勾选行动自检清单（支持现场实时打勾）',
        reading_note: '按启动前、执行中、踩坑红线三区提纯可勾选动作项，点击即可切换完成状态。',
        sections: [
          {
            id: 'before',
            title: '启动前 · 勘验准备',
            items: [
              { text: '佩戴无粉丁腈手套后再接触金属物证', detail: '顾沉拎起铜钥匙前先戴手套，防止覆盖原指纹', checked: true, source: '原文', blocks: ['B02'] },
              { text: '准备可瞬时熄灭的聚光手电与计时习惯', detail: '遇异响可第一时间熄灯贴墙静默', checked: true, source: '原文', blocks: ['B03'] }
            ]
          },
          {
            id: 'during',
            title: '执行中 · 线索交叉',
            items: [
              { text: '记录门轴润滑度与门闩内侧残留气味', detail: '用物理时效戳穿“长期封死”假象', checked: false, source: '原文', blocks: ['B01', 'B06'] },
              { text: '辨别工具齿痕属于自然磨损还是刻意锉改', detail: '三道等距齿痕指向特定机芯改造', checked: false, source: '原文', blocks: ['B02'] }
            ]
          },
          {
            id: 'redline',
            title: '踩坑红线 · 保密原则',
            items: [
              { text: '禁止在未确认安全前向看守者泄露全套证据链', detail: '保留松烟指印作后续突破口', checked: false, source: '原文', blocks: ['B05', 'B06'] }
            ]
          }
        ]
      },
      failure_postmortem: {
        blueprint: 'extension_dossier',
        caption: '反例与失效复盘：藏图者为何暴露行迹',
        sections: [
          {
            id: 'pm',
            title: '藏匿行动的三个执行误区',
            items: [
              {
                title: '误区一：过度润滑门轴却忽略声学反差',
                source: '原文',
                pitfall: '为防止推门发出尖响而给旧铁门新涂机油',
                case: '顾沉推门零阻力的瞬间即意识到近期有人维护',
                root_cause: '单点优化噪声却破坏了“荒废二十六年”的环境一致性',
                fix: '保留适度阻力或复原老化表征，避免违背物理常识',
                blocks: ['B01']
              },
              {
                title: '误区二：未清理门闩内侧松烟油墨指印',
                source: '原文',
                pitfall: '接触晒图暗室后直接徒手推合门闩',
                case: '松烟气味留存超过 72 小时并被触觉直接识别',
                root_cause: '跨场景作业未做手部隔离，导致暗室与钟楼产生直接关联',
                fix: '作业全程佩戴隔绝手套并封存油墨残留',
                blocks: ['B06']
              }
            ]
          }
        ]
      },

      /* ---------------- 社会科学 ---------------- */
      causal_loop: {
        blueprint: 'causal_loop',
        caption: '制度掩盖与信息黑箱因果反馈回路图',
        reading_note: '拆解 1998 档案缺位如何诱发私人寻图、暗室复刻与内部默契锁定。',
        data: {
          loops: [
            { id: 'R1', label: '档案掩盖 → 地下流转增强回路' },
            { id: 'B1', label: '物理痕迹暴露 → 外部核查收敛回路' }
          ],
          nodes: [
            { id: 'c_drv', label: '官方档案口径掩盖（1998 火灾）', role: 'driver', detail: '公开目录缺失真实机械图纸去向', blocks: ['B05'], source: '原文' },
            { id: 'c_pol', label: '二号门封禁与看守制度', role: 'policy', detail: '名义封锁降低外部审计频率', blocks: ['B04', 'B05'], source: '原文' },
            { id: 'c_mech', label: '暗室晒图复刻与私下藏匿', role: 'mechanism', detail: '行为体利用信息差在钟楼内转移核心蓝图', blocks: ['B03', 'B06'], source: '原文' },
            { id: 'c_out', label: '局部物证外溢（新油/齿匙/松烟）', role: 'outcome', detail: '高频隐秘维护反而制造出异常物理痕迹', blocks: ['B01', 'B02', 'B06'], source: '原文' },
            { id: 'c_lock', label: '看守者“封门叙事”路径锁定', role: 'lockin', detail: '老周被迫维持 26 年未开谎言以规避追责', blocks: ['B05'], source: '原文' }
          ],
          edges: [
            { from: 'c_drv', to: 'c_mech', label: '刺激地下流转', polarity: '+', delay: false },
            { from: 'c_pol', to: 'c_lock', label: '强化封门叙事', polarity: '+', delay: true },
            { from: 'c_mech', to: 'c_out', label: '累积物理痕迹', polarity: '+', delay: false },
            { from: 'c_out', to: 'c_lock', label: '动摇谎言可信度', polarity: '-', delay: false }
          ]
        }
      },
      concept_matrix: {
        blueprint: 'concept_matrix',
        caption: '跨视角制度概念对照矩阵',
        data: {
          columns: ['概念维度', '正式制度口径', '非正式潜规则运作', '识别盲区/反例', '正文锚点'],
          rows: [
            { key: '门禁管理', original_term: 'Access Governance', everyday: '二号门自 1998 年起永久封闭', author: '门轴定期涂油维护、风道充当秘密交接箱', extra: '门闩内侧松烟印直接证伪“无人通行”', blocks: ['B01', 'B03', 'B06'], source: '原文' },
            { key: '钥匙权限', original_term: 'Key Authority', everyday: '市政存档标准主副钥匙体系', author: '自行锉出三道齿痕绕过既有校准制约', extra: '技术改装使正式借还登记失效', blocks: ['B02'], source: '原文' }
          ]
        }
      },
      metric_chart: {
        blueprint: 'metric_chart',
        caption: '物证时效与气味衰减窗口对比图',
        reading_note: '结合正文给出的“七十二小时”气味窗口与二十六年封门时间跨度对照验证。',
        data: {
          mode: 'comparison',
          yLabel: '痕迹留存辨识度',
          unit: '%',
          series: [
            {
              name: '松烟油墨指印辨识度',
              points: [
                { x: '24h 内', value: 94, blocks: ['B06'] },
                { x: '72h 临界', value: 68, blocks: ['B06'] },
                { x: '30 天后', value: 12, blocks: ['B06'] },
                { x: '26 年(老周口径)', value: 0, blocks: ['B05'] }
              ]
            },
            {
              name: '门轴润滑静音度',
              points: [
                { x: '24h 内', value: 96, blocks: ['B01'] },
                { x: '72h 临界', value: 88, blocks: ['B01'] },
                { x: '30 天后', value: 45, blocks: ['B01'] },
                { x: '26 年(老周口径)', value: 4, blocks: ['B05'] }
              ]
            }
          ]
        }
      },
      method_audit: {
        blueprint: 'extension_dossier',
        caption: '研究方法与样本局限（已连接桌面书库）',
        reading_note: '对比单一现场物证三角验证与跨书档案抽样之间的识别偏差。',
        sections: [
          {
            id: 'audit',
            title: '因果识别与样本边界审计',
            items: [
              { title: '单一夜探观察的幸存者偏差', role_badge: '样本局限', source: '原文', body: '本章仅观察到 23:15–00:00 单一时间切片，尚需联动第 2 章档案馆借阅登记作面板对照。', blocks: ['B06'] },
              { title: '跨书测绘油墨配方检验（桌面网关联动）', role_badge: '三角验证', source: '补充', body: '已从桌面书库检索到 1995–2002 年港务局测绘室采购记录，确认松烟混松香油墨属特定部门专供。', blocks: ['B06'] }
            ]
          }
        ]
      },
      actor_incentives: {
        blueprint: 'extension_dossier',
        variant: 'actor_incentives',
        caption: '行动者激励、硬约束与现实博弈策略',
        items: [
          { title: '顾沉', role_tag: '调查者', incentive: '还原 1998 火灾真相并锁定暗室接触者', constraint: '孤身夜间入塔，尚未取得风道图纸实物', strategy: '亮匙不亮印，先测谎再待增援追踪', source: '原文', blocks: ['B02', 'B05', 'B06'] },
          { title: '老周', role_tag: '守钟人', incentive: '维持表面平静，避免自身被卷入私开钟楼追责', constraint: '无法解释为何二号门门轴涂油与钥匙出现', strategy: '以“26 年前烧毁图纸”部分坦白换取信任', source: '原文', blocks: ['B04', 'B05'] },
          { title: '灰衣人', role_tag: '藏图方', incentive: '在零点前将图纸安全藏入风道并无声撤离', constraint: '必须卡在铜锤整点报时前完成外梯速降', strategy: '利用钟鸣声幕与百叶窗盲区脱离现场', source: '原文', blocks: ['B03', 'B07', 'B08'] }
        ]
      },

      /* ---------------- 科学与数学 ---------------- */
      derivation_dag: {
        blueprint: 'derivation_dag',
        caption: '钟楼双匙机械与声学物理推导图',
        reading_note: '从公理级的物理材料特性推导出嫌疑人行动窗口与机械制动机理。',
        data: {
          paradigm: 'experimental',
          nodes: [
            { id: 'o1', label: '观测 1：门轴零尖响且无铁锈阻滞', role: 'observation', detail: '金属摩擦系数被近期润滑油显著降低', blocks: ['B01'], source: '原文' },
            { id: 'o2', label: '观测 2：铜匙三道等距锉痕与松烟指印', role: 'observation', detail: '油墨尚未干透，挥发时间 < 72h', blocks: ['B02', 'B06'], source: '原文' },
            { id: 'h1', label: '假说模型：存在具备机芯知识的近期潜入者', role: 'hypothesis', detail: '需能匹配瑞士发条锁片并有晒图暗室接触史', blocks: ['B02', 'B06'], source: '原文' },
            { id: 'e1', label: '现场检验：风道合拢声与零点钟鸣共振掩蔽', role: 'experiment', detail: '铜锤冲击声压级远高于消防铁梯脚步声', blocks: ['B03', 'B08'], source: '原文' },
            { id: 'f1', label: '科学结论：推翻 26 年封闭假说，锁定 72h 窗口', role: 'finding', detail: '物理衰减证据独立于口述证词成立', blocks: ['B05', 'B06'], source: '原文' }
          ],
          edges: [
            { from: 'o1', to: 'h1', label: '支持近期维护' },
            { from: 'o2', to: 'h1', label: '锁定时间窗' },
            { from: 'h1', to: 'e1', label: '解释撤离机制' },
            { from: 'e1', to: 'f1', label: '完成证据闭环' }
          ]
        }
      },
      sensitivity_chart: {
        blueprint: 'sensitivity_chart',
        caption: '临界条件与松烟油墨风干相变轴',
        reading_note: '展示松烟混松香油墨随时间推移的挥发相态，精确解释“72 小时”法医判断依据。',
        data: {
          mode: 'regime_threshold',
          parameter: '油墨印记留存时间 t（小时）',
          regimes: [
            { range: '0 – 24 h', state: '湿润可沾染期', tone: 'stable', mechanism: '松香脂未完全交联，触碰即留清晰气味与微粘手感', blocks: ['B06'], source: '原文' },
            { range: '24 – 72 h', threshold: 't = 72 h 临界阈值', state: '半风干余味期', tone: 'critical', mechanism: '表面结膜但松烟芳香烃仍可稳定挥发检出', blocks: ['B06'], source: '原文' },
            { range: '> 72 h', threshold: '超过 72 h 彻底固化', state: '完全风干消散期', tone: 'breakdown', mechanism: '气味散尽、印迹粉化，无法再作为“尚未风干”证据', blocks: ['B06'], source: '补充' }
          ]
        }
      },
      system_flow: {
        blueprint: 'system_flow',
        caption: '钟楼机械报时与双匙制动机制流图',
        data: {
          nodes: [
            { id: 'sf1', label: '输入：主发条储能与擒纵轮驱动', role: 'input_energy', detail: '每逢整点前 30 秒释放铜锤储能机构', blocks: ['B08'], source: '原文' },
            { id: 'sf2', label: '部件：瑞士机芯发条锁片', role: 'component', detail: '常规仅由主匙/副匙标准齿距触发', blocks: ['B02'], source: '原文' },
            { id: 'sf3', label: '过程：三道锉痕铜匙强行卡位', role: 'process', detail: '改变齿距实现非授权机械制动或解锁', blocks: ['B02'], source: '原文' },
            { id: 'sf4', label: '效应：零点铜锤轰鸣掩蔽铁梯声', role: 'output_effect', detail: '声波掩蔽效应使外墙撤离声完全被淹没', blocks: ['B07', 'B08'], source: '原文' }
          ],
          edges: [
            { from: 'sf1', to: 'sf2', label: '动力传递' },
            { from: 'sf2', to: 'sf3', label: '齿距卡合' },
            { from: 'sf3', to: 'sf4', label: '整点触发' }
          ]
        }
      },
      concept_glossary: {
        blueprint: 'extension_glossary',
        caption: '核心科学概念与物证量纲词典',
        items: [
          { term: '松烟油墨挥发窗', symbol: 'τ ≤ 72h', intuition: '像刚刷完松节油的木框，三天内一摸一闻就能认出年份', definition: '松香与松烟混合晒图墨在室温高湿环境下保持挥发气味与未固化表层的最大时间窗口。', source: '原文', blocks: ['B06'] },
          { term: '声学掩蔽效应', symbol: 'SNR < -12dB', intuition: '钟声响起时，连自己口袋里的怀表滴答也听不见', definition: '高声压级低频铜锤冲击声使人耳对相邻中高频金属摩擦声的听阈显著抬升。', source: '补充', blocks: ['B08'] }
        ]
      },
      boundary_check: {
        blueprint: 'extension_dossier',
        caption: '物理推断的演进与适用边界',
        sections: [
          {
            id: 'breakdown_boundary',
            title: '极端条件下的判据失效边界',
            items: [
              { title: '高湿雾夜对风干时间的延展', role_badge: '边界修正', source: '补充', body: '雾夜空气湿度接近饱和会略微延缓油墨氧化风干，但门轴机油与水珠共同印证近期人为活动。', blocks: ['B01', 'B06', 'B07'] }
            ]
          }
        ]
      },

      /* ---------------- 图册与视觉 ---------------- */
      element_matrix: {
        blueprint: 'element_matrix',
        caption: '钟楼场景视觉构图与气氛元素索引表',
        data: {
          columns: ['视觉元素', '材质与时代原型', '画面调度与气氛功能', '正文锚点'],
          rows: [
            { key: '潮湿青砖与旧皮箱', original_term: 'Wet Flagstone & Leather', everyday: '1930–1990 年代港口砖石底座与牛皮箱', author: '冷色湿滑质感确立孤身入局的低照度开场', blocks: ['B01'], source: '原文' },
            { key: '手电光柱切开浮尘', original_term: 'Volumetric Beam', everyday: '定向聚光手电与悬浮微粒丁达尔效应', author: '将读者的视觉焦点精确收缩至值班台黄铜钥匙', blocks: ['B02'], source: '原文' },
            { key: '煤油风灯暗红墙漆', original_term: 'Kerosene Amber Glow', everyday: '暖色调提灯与剥落工业油漆墙面', author: '在二层阴影中勾勒人物半张脸，强化悬疑压迫感', blocks: ['B04'], source: '原文' },
            { key: '百叶窗灰衣剪影', original_term: 'Louvered Silhouette', everyday: '百叶窗栅格与外墙消防铁梯', author: '制造瞬时失焦的动态剪影，完成追逐转场', blocks: ['B07'], source: '原文' }
          ]
        }
      },
      craft_notes: {
        blueprint: 'extension_dossier',
        caption: '道具与建筑工艺源流考据',
        sections: [
          {
            id: 'craft',
            title: '工业器物与建筑细节考据',
            items: [
              { title: '黄铜发条钥匙的锉削痕迹', role_badge: '金工细节', source: '原文', body: '黄铜质地较软，可用三角什锦锉手工加工出精准卡齿，但会在断口留有亮金色新茬。', blocks: ['B02'] },
              { title: '镀锌铁皮通风管道与百叶机房', role_badge: '建筑形制', source: '原文', body: '老式钟楼为防止机芯受潮常设贯通至顶层的镀锌风道与可调百叶，客观上形成隐蔽藏物腔体。', blocks: ['B03', 'B07'] }
            ]
          }
        ]
      }
    },

    byokModels: [
      {
        id: 'deepseek',
        name: 'DeepSeek Reasoner',
        provider: 'DeepSeek API',
        modelId: 'deepseek-reasoner',
        maskedKey: 'sk-••••9f2a',
        capabilities: ['思考流', '长文本'],
        isDefaultCompanion: true,
        isDefaultVision: false,
        latency: '182ms · 连通正常'
      },
      {
        id: 'gemini',
        name: 'Gemini 2.5 Flash',
        provider: 'Google AI Studio',
        modelId: 'gemini-2.5-flash',
        maskedKey: 'AIza••••4k8M',
        capabilities: ['Vision', '快速响应'],
        isDefaultCompanion: false,
        isDefaultVision: true,
        latency: '214ms · VLM 正常'
      },
      {
        id: 'claude',
        name: 'Claude 3.7 Sonnet',
        provider: 'Anthropic',
        modelId: 'claude-3-7-sonnet',
        maskedKey: 'sk-ant-••••71c0',
        capabilities: ['精细文本', 'Vision'],
        isDefaultCompanion: false,
        isDefaultVision: false,
        latency: '未测试'
      },
      {
        id: 'ollama',
        name: 'Ollama 本地 qwen3:14b',
        provider: 'Local HTTP (127.0.0.1:11434)',
        modelId: 'qwen3:14b',
        maskedKey: 'local-no-key',
        capabilities: ['100% 离线', '隐私本地'],
        isDefaultCompanion: false,
        isDefaultVision: false,
        latency: '94ms · 本地就绪'
      }
    ],

    pdfSample: {
      scanTitle: '《几何原本（明刻影印版）》 · p.14 / 280',
      scanCaption: '卷一命题四十七 · 勾股弦方图（明万历刻本影印）',
      vlmAnswer: {
        model: 'Gemini 2.5 Flash · Vision',
        thinking: '识别用户手绘矩形选区包含直角三角形 ABC 与三边外接正方形图形，以及左侧直排注文「勾三股四弦五，弦幂等于勾幂加股幂」。',
        answer: '### 选区图解：欧氏《几何原本》卷一命题四十七（勾股定理面积割补证法）\n- [原文] 图中央为直角三角形（∠BAC 为直角），分别以三边向外作正方形。\n- [原文] 过直角顶点 A 向斜边正方形作垂线，将其分割为左右两个矩形。\n- [补充] 关键全等桥接：利用「同底等高三角形面积等于矩形之半」证明左矩形面积 = 勾边正方形，右矩形面积 = 股边正方形，从而合得弦幂等于勾股二幂之和。'
      },
      textTitle: '《操作系统导论》 · p.42 / 320',
      textHeading: '第 18 章：分页（Paging）与页表地址转换',
      textParagraphs: [
        '与分段（Segmentation）将物理内存切分为可变大小区域不同，分页将进程的地址空间划分为固定大小的单元，称为页（Page）。相应地，物理内存也被视为由固定大小的槽位组成的数组，称为页帧（Page Frame）。',
        '为了记录地址空间中的每一页究竟存放在物理内存的哪一个页帧中，操作系统会为每个进程维护一个核心数据结构——页表（Page Table）。当处理器执行诸如 movl 21, %eax 的访存指令时，硬件首先把虚拟地址拆分为虚拟页号（VPN）和页内偏移（Offset）。',
        '通过查询页表项（PTE），MMU 将 VPN 转换为物理帧号（PFN），并与保持不变的 Offset 拼接，最终形成真实的物理内存地址。这一机制消除了外部碎片，但也带来了额外的内存访存开销。'
      ]
    }
  };
})();
