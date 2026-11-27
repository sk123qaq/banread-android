/* 伴读 Android 原型 · 核心交互状态机、6大页面渲染与 diagram_renderer 真实挂载 */
(function () {
  const DATA = window.DEMO_DATA;
  const RD = (window.RD = window.RD || {});

  RD.esc = (value) =>
    String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');

  RD.toast = (message, options) => {
    let node = document.getElementById('demo-toast');
    if (!node) {
      node = document.createElement('div');
      node.id = 'demo-toast';
      node.className = 'toast';
      node.setAttribute('role', 'status');
      node.setAttribute('aria-live', 'polite');
      document.body.appendChild(node);
    }
    node.innerHTML = '';
    if (options && options.actionLabel && typeof options.onAction === 'function') {
      node.classList.add('has-action');
      const txt = document.createElement('span');
      txt.textContent = message;
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'toast-undo-btn';
      btn.textContent = options.actionLabel;
      btn.addEventListener('click', () => {
        clearTimeout(node._timer);
        node.classList.remove('show', 'has-action');
        options.onAction();
      });
      node.appendChild(txt);
      node.appendChild(btn);
    } else {
      node.classList.remove('has-action');
      node.textContent = message;
    }
    node.classList.add('show');
    clearTimeout(node._timer);
    const delay = (options && options.duration) || (options && options.actionLabel ? 5000 : 2800);
    node._timer = setTimeout(() => node.classList.remove('show', 'has-action'), delay);
  };

  /* 全局原型状态 */
  const state = {
    page: 'bookshelf',        // bookshelf | detail | reader | pdf | settings
    viewport: 'phone',        // phone | eink-pad | foldable
    theme: 'warm',            // warm | paper | slate | amoled | eink
    gatewayOnline: false,

    /* 书架 */
    books: DATA.books.map((b) => Object.assign({}, b)),
    shelfView: 'grid',        // grid | spine
    shelfFilter: 'all',
    shelfSearch: '',
    selectedBookId: 'wuye',
    selectedSpineId: 'wuye',

    /* 详情页 & 防剧透水位线 */
    watermarkChapter: 3,
    activeChapterNum: 3,
    pendingJumpChapter: null,
    fullBookUnlocked: false,

    /* 阅读器与排版 */
    hudVisible: false,
    typoPanelOpen: false,
    bookmarked: false,
    fontSize: 17,
    lineHeight: 1.84,
    marginMode: 'mid',        // narrow | mid | wide
    fontFamily: 'serif',      // serif | kai | sans
    kerningEnabled: true,
    paginationMode: 'slide',  // slide | cover | none | scroll
    volumeKeyTurn: true,
    selectionOpen: false,
    selectedBlockId: 'B02',
    hlColors: { 'hl-1': 'amber', 'hl-2': 'pine' },
    customNotes: [
      {
        id: 'pin-1',
        blockId: 'B03',
        excerpt: '铁皮挡板合拢时发出闷沉的一响',
        body: '此处风道与 B05 所提“机械报时图纸”形成结构呼应：嫌疑人并非来取走旧物，而是趁雾夜掩护把新伪造或未烧毁的蓝图重新藏回钟楼内部。'
      }
    ],
    activeFootnoteId: null,
    fnReturnTargetId: null,
    companionReturnSnap: false,

    /* 伴读抽屉 */
    companionOpen: false,
    companionSnap: 'peek',    // peek | half | full
    activeProfileId: 'literature',
    scopeMode: 'chapter',     // selection | chapter | upToWatermark | fullBook
    activeToolId: 'tension_curve',
    cachedTools: new Set(DATA.cachedToolsInitial),
    buildingToolId: null,
    qaTurns: [],
    selectionContextQuote: '',

    /* PDF 双形态 */
    pdfMode: 'scan',          // scan | text
    pdfTextSubMode: 'crop',   // crop | reflow
    pdfAutoCrop: true,
    pdfInvert: false,
    pdfOcrQueued: false,
    pdfRect: null,            // { x, y, w, h } in %
    pdfVlmTurn: null,

    /* 设置中心 */
    models: DATA.byokModels.map((m) => Object.assign({}, m)),
    lastRemovedModel: null,
    customFonts: ['思源宋体 · 内置', '霞鹜文楷 · LXGWWenKai.ttf'],
    defaultSpoilerLock: true,
    p2pLastLog: '尚未发起双机互传'
  };

  /* 工具查找与辅助 */
  function getProfile(id) {
    return DATA.profiles.find((p) => p.read_type === (id || state.activeProfileId)) || DATA.profiles[1];
  }

  function getToolsForProfile(profileId) {
    const p = getProfile(profileId);
    const list = [{ id: 'chapter_map', label: '章节导图', kind: 'map', cross_search: false }];
    (p.blueprints || []).forEach((bp) => {
      list.push({ id: bp.blueprint, label: bp.label, kind: 'blueprint', cross_search: false });
    });
    (p.extensions || []).forEach((ex) => {
      list.push({ id: ex.action, label: ex.label, kind: 'extension', cross_search: Boolean(ex.cross_search) });
    });
    return list;
  }

  /* 主题与视口联动 */
  function applyTheme(theme) {
    state.theme = theme;
    const isDark = theme === 'slate' || theme === 'amoled';
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = isDark ? 'dark' : 'light';
    if (theme === 'eink') {
      state.paginationMode = 'none';
    }
    syncStageControls();
  }

  function applyViewport(vp) {
    state.viewport = vp;
    document.body.dataset.viewport = vp;
    const frame = document.getElementById('device-shell');
    if (frame) frame.setAttribute('data-viewport', vp);
    if (vp === 'eink-pad') {
      applyTheme('eink');
    } else if (vp === 'foldable' && state.page === 'reader') {
      state.companionOpen = true;
    }
    syncStageControls();
    renderApp();
  }

  function syncStageControls() {
    document.querySelectorAll('#stage-page-nav [data-page]').forEach((btn) => {
      btn.setAttribute('aria-pressed', btn.dataset.page === state.page ? 'true' : 'false');
    });
    document.querySelectorAll('#stage-viewport-nav [data-vp]').forEach((btn) => {
      btn.setAttribute('aria-pressed', btn.dataset.vp === state.viewport ? 'true' : 'false');
    });
    document.querySelectorAll('#stage-theme-nav [data-theme-pick]').forEach((btn) => {
      btn.setAttribute('aria-pressed', btn.dataset.themePick === state.theme ? 'true' : 'false');
    });
    const gwBtn = document.getElementById('trig-gateway');
    if (gwBtn) {
      gwBtn.textContent = state.gatewayOnline ? '桌面网关: 在线' : '桌面网关: 离线';
      gwBtn.classList.toggle('is-online', state.gatewayOnline);
      gwBtn.setAttribute('aria-pressed', String(state.gatewayOnline));
    }
  }

  /* BYOK 弹窗状态与服务商预设工具函数 */
  const THINKING_LABELS = { off: '关', low: '低', medium: '中', high: '高', max: '极高' };
  const byokFormState = {
    editingId: null,
    providerId: 'deepseek',
    protocol: 'openai_compat',
    thinkingLevel: 'high',
    fetchedModels: [],
    fetchState: 'idle',
    fetchHost: '',
    fetchTimer: null,
    filterQuery: '',
    keyVisible: false,
    _prepared: false
  };

  function getByokProvider(id) {
    const list = DATA.byokProviders || [];
    return list.find((p) => p.id === id) || list[0];
  }

  function formatHostPath(url) {
    return String(url || '').trim().replace(/^https?:\/\//i, '').replace(/\/+$/, '');
  }

  function getByokEndpointPreview(baseUrl, protocol) {
    const clean = String(baseUrl || '').trim().replace(/\/+$/, '') || 'https://api.openai.com/v1';
    return '模型查询：GET ' + clean + (protocol === 'ollama' ? '/api/tags' : '/models');
  }

  function getByokThinkingHint(protocol) {
    if (protocol === 'anthropic') return 'Anthropic 映射 → thinking: { type, budget_tokens }';
    if (protocol === 'gemini') return 'Gemini 映射 → generationConfig.thinkingConfig';
    if (protocol === 'ollama') return 'Ollama 映射 → think / options.reasoning';
    return 'OpenAI / OpenCode 映射 → reasoning_effort';
  }

  function thinkingBadgeText(level) {
    return '思考: ' + (THINKING_LABELS[level] || '中');
  }

  function maskByokKey(rawKey, providerPreset) {
    const k = String(rawKey || '').trim();
    if ((!k || k === '本地免 Key') && providerPreset && !providerPreset.requiresKey) return '本地免 Key';
    if (!k) return 'sk-••••0000';
    if (k.includes('••••')) return k;
    const prefix = k.startsWith('sk-ant-') ? 'sk-ant-' : k.startsWith('sk-oc-') ? 'sk-oc-' : k.startsWith('AIza') ? 'AIza' : k.slice(0, 3);
    const tail = k.slice(-4);
    return prefix + '••••' + tail;
  }

  function renderByokProviderGroups() {
    const wrap = document.getElementById('byok-provider-groups');
    if (!wrap) return;
    const list = DATA.byokProviders || [];
    const groups = [
      { key: 'proxy', label: '聚合 / 代理' },
      { key: 'official', label: '官方直连' }
    ];
    wrap.innerHTML = groups
      .map((g) => {
        const items = list.filter((p) => p.group === g.key);
        return (
          '<div class="byok-prov-group">' +
          '<span class="byok-prov-group-lbl">' +
          RD.esc(g.label) +
          '</span>' +
          '<div class="byok-prov-chips">' +
          items
            .map(
              (p) =>
                '<button type="button" class="byok-prov-chip" data-byok-provider="' +
                RD.esc(p.id) +
                '" aria-pressed="' +
                (byokFormState.providerId === p.id ? 'true' : 'false') +
                '">' +
                RD.esc(p.name) +
                '</button>'
            )
            .join('') +
          '</div></div>'
        );
      })
      .join('');
    wrap.querySelectorAll('[data-byok-provider]').forEach((btn) => {
      btn.addEventListener('click', () => applyByokProviderPreset(btn.dataset.byokProvider));
    });
  }

  function syncByokFormControls() {
    const prov = getByokProvider(byokFormState.providerId);
    document.querySelectorAll('#byok-provider-groups [data-byok-provider]').forEach((btn) => {
      btn.setAttribute('aria-pressed', btn.dataset.byokProvider === byokFormState.providerId ? 'true' : 'false');
    });
    document.querySelectorAll('#byok-protocol-seg [data-byok-proto]').forEach((btn) => {
      btn.setAttribute('aria-pressed', btn.dataset.byokProto === byokFormState.protocol ? 'true' : 'false');
    });
    document.querySelectorAll('#byok-thinking-seg [data-byok-think]').forEach((btn) => {
      btn.setAttribute('aria-pressed', btn.dataset.byokThink === byokFormState.thinkingLevel ? 'true' : 'false');
    });

    const baseInp = document.getElementById('byok-base-url');
    const epHint = document.getElementById('byok-endpoint-hint');
    if (epHint && baseInp) {
      epHint.textContent = getByokEndpointPreview(baseInp.value, byokFormState.protocol);
    }

    const thHint = document.getElementById('byok-thinking-hint');
    if (thHint) {
      thHint.textContent = getByokThinkingHint(byokFormState.protocol);
    }

    const keyInp = document.getElementById('byok-key');
    const keyToggle = document.getElementById('btn-byok-toggle-key');
    if (keyInp) {
      const needKey = byokFormState.protocol !== 'ollama' && (!prov || prov.requiresKey);
      if (needKey) {
        keyInp.setAttribute('required', '');
        keyInp.placeholder = 'sk-...';
      } else {
        keyInp.removeAttribute('required');
        keyInp.placeholder = '本地 Ollama 无需填写 Key';
      }
      keyInp.type = byokFormState.keyVisible ? 'text' : 'password';
    }
    if (keyToggle) {
      keyToggle.textContent = byokFormState.keyVisible ? '隐藏' : '显示';
      keyToggle.setAttribute('aria-pressed', String(byokFormState.keyVisible));
    }

    const fetchBtn = document.getElementById('btn-byok-fetch-models');
    if (fetchBtn) {
      fetchBtn.disabled = byokFormState.fetchState === 'loading';
      fetchBtn.textContent = byokFormState.fetchState === 'loading' ? '查询中…' : '查询可用模型';
    }
  }

  function renderByokCandidates() {
    const statusEl = document.getElementById('byok-fetch-status');
    const listEl = document.getElementById('byok-model-candidates');
    if (!statusEl || !listEl) return;

    if (byokFormState.fetchState === 'loading') {
      statusEl.hidden = false;
      statusEl.textContent = '正在向 ' + byokFormState.fetchHost + ' 查询可用模型…';
      listEl.hidden = true;
      listEl.innerHTML = '';
      return;
    }

    if (byokFormState.fetchState === 'done' && byokFormState.fetchedModels.length) {
      const q = byokFormState.filterQuery.trim().toLowerCase();
      const visible = q
        ? byokFormState.fetchedModels.filter((m) => m.id.toLowerCase().includes(q) || m.name.toLowerCase().includes(q))
        : byokFormState.fetchedModels;
      const curModel = (document.getElementById('byok-model-id')?.value || '').trim();
      statusEl.hidden = false;
      statusEl.textContent =
        '已从 ' +
        byokFormState.fetchHost +
        ' 拉取 ' +
        byokFormState.fetchedModels.length +
        ' 个可用模型' +
        (q ? '（匹配 ' + visible.length + ' 项）' : ' · 点击条目直接填入');
      listEl.hidden = false;
      listEl.innerHTML = visible.length
        ? visible
            .map(
              (m) =>
                '<button type="button" role="option" class="byok-cand-item' +
                (curModel === m.id ? ' is-selected' : '') +
                '" aria-selected="' +
                (curModel === m.id ? 'true' : 'false') +
                '" data-cand-model="' +
                RD.esc(m.id) +
                '">' +
                '<span class="byok-cand-id">' +
                RD.esc(m.id) +
                '</span>' +
                '<span class="byok-cand-tags">' +
                (m.tags || [])
                  .map(
                    (tag) =>
                      '<span class="cap-pill' +
                      (tag === 'Vision' ? ' vlm' : tag === '思考' ? ' think' : '') +
                      '">' +
                      RD.esc(tag) +
                      '</span>'
                  )
                  .join('') +
                '</span></button>'
            )
            .join('')
        : '<div style="padding:10px;font-size:11.5px;color:var(--ink-3)">无匹配候选模型，可直接使用当前输入 ID</div>';

      listEl.querySelectorAll('[data-cand-model]').forEach((btn) => {
        btn.addEventListener('click', () => {
          const picked = byokFormState.fetchedModels.find((m) => m.id === btn.dataset.candModel);
          if (!picked) return;
          const modelInp = document.getElementById('byok-model-id');
          const nameInp = document.getElementById('byok-name');
          const visionChk = document.getElementById('byok-vision');
          if (modelInp) modelInp.value = picked.id;
          if (nameInp) nameInp.value = picked.name;
          if (visionChk) visionChk.checked = Boolean(picked.vision);
          byokFormState.thinkingLevel = picked.thinking || 'medium';
          byokFormState.filterQuery = '';
          syncByokFormControls();
          renderByokCandidates();
        });
      });
      return;
    }

    statusEl.hidden = true;
    statusEl.textContent = '';
    listEl.hidden = true;
    listEl.innerHTML = '';
  }

  function applyByokProviderPreset(providerId) {
    const prov = getByokProvider(providerId);
    if (!prov) return;
    if (byokFormState.fetchTimer) {
      clearTimeout(byokFormState.fetchTimer);
      byokFormState.fetchTimer = null;
    }
    byokFormState.providerId = prov.id;
    byokFormState.protocol = prov.protocol;
    byokFormState.fetchedModels = [];
    byokFormState.fetchState = 'idle';
    byokFormState.filterQuery = '';

    const first = (prov.models && prov.models[0]) || { id: '', name: prov.name, vision: false, thinking: 'medium' };
    byokFormState.thinkingLevel = first.thinking || 'medium';

    const baseInp = document.getElementById('byok-base-url');
    const modelInp = document.getElementById('byok-model-id');
    const nameInp = document.getElementById('byok-name');
    const keyInp = document.getElementById('byok-key');
    const visChk = document.getElementById('byok-vision');

    if (baseInp) baseInp.value = prov.baseUrl;
    if (modelInp) modelInp.value = first.id;
    if (nameInp) nameInp.value = first.name;
    if (visChk) visChk.checked = Boolean(first.vision);
    if (keyInp) {
      if (!prov.requiresKey) {
        keyInp.value = '';
      } else if (!keyInp.value.trim()) {
        keyInp.value = 'sk-demo-8842';
      }
    }

    syncByokFormControls();
    renderByokCandidates();
  }

  function prepareByokModal(modelIdToEdit) {
    if (byokFormState.fetchTimer) {
      clearTimeout(byokFormState.fetchTimer);
      byokFormState.fetchTimer = null;
    }
    const existing = modelIdToEdit ? state.models.find((m) => m.id === modelIdToEdit) : null;
    const titleEl = document.getElementById('dlg-byok-title');
    if (titleEl) titleEl.textContent = existing ? '编辑 BYOK 模型' : '添加 BYOK 模型';

    byokFormState.editingId = existing ? existing.id : null;
    byokFormState.fetchedModels = [];
    byokFormState.fetchState = 'idle';
    byokFormState.filterQuery = '';
    byokFormState.keyVisible = false;

    renderByokProviderGroups();

    if (existing) {
      const prov = getByokProvider(existing.providerId || 'custom');
      byokFormState.providerId = prov.id;
      byokFormState.protocol = existing.protocol || prov.protocol;
      byokFormState.thinkingLevel = existing.thinkingLevel || 'medium';

      const baseInp = document.getElementById('byok-base-url');
      const modelInp = document.getElementById('byok-model-id');
      const nameInp = document.getElementById('byok-name');
      const keyInp = document.getElementById('byok-key');
      const visChk = document.getElementById('byok-vision');

      if (baseInp) baseInp.value = existing.baseUrl || prov.baseUrl;
      if (modelInp) modelInp.value = existing.modelId || '';
      if (nameInp) nameInp.value = existing.name || '';
      if (visChk) visChk.checked = Array.isArray(existing.capabilities) && existing.capabilities.includes('Vision');
      if (keyInp) keyInp.value = prov.requiresKey ? existing.maskedKey || 'sk-demo-8842' : '';

      syncByokFormControls();
      renderByokCandidates();
    } else {
      const keyInp = document.getElementById('byok-key');
      if (keyInp) keyInp.value = 'sk-demo-8842';
      applyByokProviderPreset('deepseek');
    }
    const remBtn = document.getElementById('btn-byok-remove');
    if (remBtn) remBtn.hidden = !existing;

    byokFormState._prepared = true;
  }

  function undoRemoveByokModel() {
    const snap = state.lastRemovedModel;
    if (!snap || !snap.item) return;
    state.lastRemovedModel = null;
    if (snap.item.isDefaultCompanion) {
      state.models.forEach((m) => { m.isDefaultCompanion = false; });
    }
    if (snap.item.isDefaultVision) {
      state.models.forEach((m) => { m.isDefaultVision = false; });
    }
    const insertIdx = Math.max(0, Math.min(snap.index, state.models.length));
    state.models.splice(insertIdx, 0, snap.item);
    renderApp();
    RD.toast('已恢复模型：' + snap.item.name);
  }

  function removeByokModel(modelId) {
    const idx = state.models.findIndex((m) => m.id === modelId);
    if (idx < 0) return;
    const removed = state.models.splice(idx, 1)[0];
    state.lastRemovedModel = { item: Object.assign({}, removed), index: idx };

    if (removed.isDefaultCompanion && state.models.length > 0) {
      state.models[0].isDefaultCompanion = true;
    }
    if (removed.isDefaultVision) {
      const nextVision = state.models.find((m) => Array.isArray(m.capabilities) && m.capabilities.includes('Vision'));
      if (nextVision) nextVision.isDefaultVision = true;
    }
    renderApp();
    RD.toast('已移除「' + removed.name + '」', {
      actionLabel: '撤销',
      duration: 5000,
      onAction: undoRemoveByokModel
    });
  }

  function openByokModal(modelIdToEdit) {
    prepareByokModal(modelIdToEdit || null);
    openModal('dlg-byok');
  }

  function bindByokDialogEvents() {
    const remBtn = document.getElementById('btn-byok-remove');
    if (remBtn) {
      remBtn.addEventListener('click', () => {
        const targetId = byokFormState.editingId;
        closeModal('dlg-byok');
        if (targetId) removeByokModel(targetId);
      });
    }
    document.querySelectorAll('#byok-protocol-seg [data-byok-proto]').forEach((btn) => {
      btn.addEventListener('click', () => {
        byokFormState.protocol = btn.dataset.byokProto;
        syncByokFormControls();
      });
    });

    document.querySelectorAll('#byok-thinking-seg [data-byok-think]').forEach((btn) => {
      btn.addEventListener('click', () => {
        byokFormState.thinkingLevel = btn.dataset.byokThink;
        syncByokFormControls();
      });
    });

    const baseInp = document.getElementById('byok-base-url');
    if (baseInp) {
      baseInp.addEventListener('input', () => syncByokFormControls());
    }

    const keyToggle = document.getElementById('btn-byok-toggle-key');
    if (keyToggle) {
      keyToggle.addEventListener('click', () => {
        byokFormState.keyVisible = !byokFormState.keyVisible;
        syncByokFormControls();
      });
    }

    const modelInp = document.getElementById('byok-model-id');
    if (modelInp) {
      modelInp.addEventListener('input', () => {
        if (byokFormState.fetchState === 'done') {
          byokFormState.filterQuery = modelInp.value;
          renderByokCandidates();
        }
      });
    }

    const fetchBtn = document.getElementById('btn-byok-fetch-models');
    if (fetchBtn) {
      fetchBtn.addEventListener('click', () => {
        const prov = getByokProvider(byokFormState.providerId);
        const rawBase = document.getElementById('byok-base-url')?.value || prov.baseUrl;
        const hostLabel = formatHostPath(rawBase) || formatHostPath(prov.baseUrl);
        if (byokFormState.fetchTimer) clearTimeout(byokFormState.fetchTimer);
        byokFormState.fetchHost = hostLabel;
        byokFormState.fetchState = 'loading';
        byokFormState.filterQuery = '';
        syncByokFormControls();
        renderByokCandidates();

        byokFormState.fetchTimer = setTimeout(() => {
          byokFormState.fetchTimer = null;
          byokFormState.fetchedModels = (prov.models || []).map((m) => Object.assign({}, m));
          byokFormState.fetchState = 'done';
          syncByokFormControls();
          renderByokCandidates();
        }, 280);
      });
    }
  }

  /* 原生 <dialog> 统一管理（含 closedby="any" 降级 & returnValue 重置） */
  function openModal(id) {
    const dlg = document.getElementById(id);
    if (!dlg) return;
    if (id === 'dlg-byok' && !byokFormState._prepared) {
      prepareByokModal(null);
    }
    if (id === 'dlg-byok') byokFormState._prepared = false;
    dlg.removeAttribute('data-closing');
    if (typeof dlg.showModal === 'function') {
      if (!dlg.open) dlg.showModal();
    } else {
      dlg.setAttribute('open', '');
    }
  }

  function closeModal(dlgOrId) {
    const dlg = typeof dlgOrId === 'string' ? document.getElementById(dlgOrId) : dlgOrId;
    if (!dlg) return;
    if (typeof dlg.close === 'function' && dlg.open) {
      dlg.close();
    } else {
      dlg.removeAttribute('open');
    }
  }

  function setupDialogs() {
    const supportsClosedBy = 'closedBy' in HTMLDialogElement.prototype;
    document.querySelectorAll('dialog.app-dialog').forEach((dlg) => {
      if (!supportsClosedBy && dlg.getAttribute('closedby') === 'any') {
        let downOutside = false;
        const isOutside = (e) => {
          const r = dlg.getBoundingClientRect();
          return e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
        };
        dlg.addEventListener('pointerdown', (e) => { downOutside = isOutside(e); });
        dlg.addEventListener('pointerup', (e) => { if (downOutside && isOutside(e)) closeModal(dlg); });
      }
      dlg.addEventListener('close', () => {
        const rv = dlg.returnValue;
        dlg.returnValue = '';
        if (dlg.id === 'dlg-spoiler-unlock' && rv === 'unlock') {
          state.fullBookUnlocked = true;
          state.scopeMode = 'fullBook';
          RD.toast('已解锁全书视角（1–12 章均已纳入上下文）');
          renderApp();
        } else if (dlg.id === 'dlg-jump-ahead' && (rv === 'preview' || rv === 'advance')) {
          const book = getCurrentBook();
          const targetCh = state.pendingJumpChapter || 10;
          state.activeChapterNum = targetCh;
          if (rv === 'advance') {
            if (book.id === 'wuye') {
              state.watermarkChapter = Math.max(state.watermarkChapter, targetCh);
              book.watermark = state.watermarkChapter;
            } else {
              book.watermark = Math.max(book.watermark || book.curChapter || 1, targetCh);
            }
            book.curChapter = Math.max(book.curChapter || 1, targetCh);
            RD.toast('已推进防剧透水位线至第 ' + targetCh + ' 章');
          } else {
            RD.toast('预览第 ' + targetCh + ' 章 · 防剧透水位线仍锁至第 ' + getBookWatermark(book) + ' 章');
          }
          state.page = 'reader';
          renderApp();
        } else if (dlg.id === 'dlg-import' && rv === 'confirm') {
          const titleInp = document.getElementById('import-book-title');
          const fmtSel = document.getElementById('import-book-fmt');
          const rawTitle = (titleInp && titleInp.value.trim()) || '《新导入样书》';
          const cleanTitle = rawTitle.replace(/^《|》$/g, '');
          const [fmt, rt] = (fmtSel ? fmtSel.value : '.bcbook|.science').split('|');
          const newBook = {
            id: 'imported-' + Date.now(),
            title: cleanTitle,
            author: '本地导入',
            readType: rt.replace('.', ''),
            subKind: '',
            format: fmt,
            cloth: '#5A3E2B',
            curChapter: 1,
            totalChapters: 6,
            progressPct: 0,
            watermark: 1,
            cachedCount: fmt === '.bcbook' ? 3 : 0,
            kicker: 'NEW · 本地导入',
            currentLabel: '第 1 章 · 刚刚导入',
            filterGroup: fmt.includes('PDF') ? 'pdf' : rt.replace('.', ''),
            reading: false,
            isPdf: fmt.includes('PDF'),
            pdfMode: fmt.includes('扫描') ? 'scan' : 'text'
          };
          state.books.unshift(newBook);
          RD.toast('已导入《' + cleanTitle + '》到书架');
          renderApp();
        } else if (dlg.id === 'dlg-byok' && rv === 'save') {
          const name = document.getElementById('byok-name').value.trim();
          const modelId = document.getElementById('byok-model-id').value.trim();
          const baseUrl = (document.getElementById('byok-base-url').value || '').trim();
          const key = document.getElementById('byok-key').value.trim();
          const vision = document.getElementById('byok-vision').checked;
          const prov = getByokProvider(byokFormState.providerId);
          if (name && modelId) {
            const caps = [];
            if (prov && (prov.group === 'proxy' || !prov.requiresKey)) {
              caps.push(prov.requiresKey ? prov.badge : '100% 离线');
            }
            if (vision) caps.push('Vision');
            const maskedKey = maskByokKey(key, prov);
            const payload = {
              name,
              providerId: prov ? prov.id : 'custom',
              provider: prov ? prov.name : 'Custom BYOK',
              protocol: byokFormState.protocol,
              baseUrl: baseUrl || (prov ? prov.baseUrl : 'https://api.openai.com/v1'),
              modelId,
              thinkingLevel: byokFormState.thinkingLevel || 'medium',
              maskedKey,
              capabilities: caps
            };
            if (byokFormState.editingId) {
              const idx = state.models.findIndex((m) => m.id === byokFormState.editingId);
              if (idx >= 0) {
                Object.assign(state.models[idx], payload, { latency: '配置已更新' });
                if (!vision) state.models[idx].isDefaultVision = false;
                RD.toast('已更新模型配置：' + name);
              }
            } else {
              state.models.push(
                Object.assign(
                  {
                    id: 'm-' + Date.now(),
                    isDefaultCompanion: false,
                    isDefaultVision: false,
                    latency: '刚刚加密保存'
                  },
                  payload
                )
              );
              RD.toast('已加密保存模型：' + name);
            }
            renderApp();
          }
        } else if (dlg.id === 'dlg-qr-bind' && rv === 'connect') {
          state.gatewayOnline = true;
          syncStageControls();
          RD.toast('已通过 --mobile-bind 配对并连接桌面网关 192.168.1.18:8765');
          renderApp();
        }
      });
    });

    bindByokDialogEvents();

    document.querySelectorAll('[data-close-dialog]').forEach((btn) => {
      btn.addEventListener('click', () => closeModal(btn.dataset.closeDialog));
    });
  }

  function openPerspectiveDialog() {
    const body = document.getElementById('dlg-persp-list');
    if (!body) return;
    body.innerHTML = DATA.profiles
      .map((p) => {
        const m = String(p.adler_category || '').match(/^(\d+)\./);
        const idxBadge = m ? String(Number(m[1])).padStart(2, '0') : '补充';
        const subHint = p.read_type === 'visual' ? '<span class="muted" style="font-size:11.5px;font-weight:500">图册与设定集</span>' : '';
        return (
          '<button type="button" class="settings-card" data-pick-profile="' +
          RD.esc(p.read_type) +
          '" style="text-align:left;width:100%;border:' +
          (p.read_type === state.activeProfileId ? '1.5px solid var(--cinnabar)' : '1px solid var(--line)') +
          '">' +
          '<div style="display:flex;justify-content:space-between;align-items:center;gap:8px">' +
          '<span style="display:inline-flex;align-items:center;gap:7px;min-width:0">' +
          '<span class="mono persp-idx">' +
          RD.esc(idxBadge) +
          '</span>' +
          '<strong>' +
          RD.esc(p.name) +
          '</strong>' +
          subHint +
          '</span>' +
          (p.read_type === state.activeProfileId ? '<span class="cap-pill">当前</span>' : '') +
          '</div>' +
          '<span style="font-size:12px;color:var(--ink-2)">' +
          RD.esc(p.goal) +
          '</span></button>'
        );
      })
      .join('');
    body.querySelectorAll('[data-pick-profile]').forEach((btn) => {
      btn.addEventListener('click', () => {
        switchProfile(btn.dataset.pickProfile);
        closeModal('dlg-perspective');
      });
    });
    openModal('dlg-perspective');
  }

  function switchProfile(profileId) {
    state.activeProfileId = profileId;
    const tools = getToolsForProfile(profileId);
    const firstBp = tools.find((t) => t.kind === 'blueprint') || tools[0];
    state.activeToolId = firstBp ? firstBp.id : 'chapter_map';
    const p = getProfile(profileId);
    RD.toast('已切换伴读视角：' + p.name);
    renderApp();
  }

  function getCurrentBook() {
    return state.books.find((b) => b.id === state.selectedBookId) || state.books[0];
  }

  function getBookChapters(book) {
    if (book && DATA.bookChapters && DATA.bookChapters[book.id]) {
      return DATA.bookChapters[book.id];
    }
    const total = Math.max(1, (book && book.totalChapters) || 6);
    const cur = (book && book.curChapter) || 1;
    return Array.from({ length: total }, (_, idx) => {
      const n = idx + 1;
      return {
        n,
        title: '第 ' + n + ' 章 · ' + (n === cur ? '当前在读章节' : '样章结构节选 ' + n),
        chars: '7,200 字',
        min: '15 分钟',
        cached: n <= cur,
        map: n <= cur
      };
    });
  }

  function getBookFrontBack(book) {
    if (book && DATA.bookFrontBack && DATA.bookFrontBack[book.id]) {
      return DATA.bookFrontBack[book.id];
    }
    return [
      { kind: '扉页', title: '扉页 · ' + ((book && book.title) || '本书') + ' 导读说明' },
      { kind: '版权页', title: '版权页 · 本地导入元信息' },
      { kind: '总目录', title: '总目录 · 全书结构索引' },
      { kind: '奥付', title: '奥付 · 排印校勘记' }
    ];
  }

  function getBookWatermark(book) {
    if (!book) return state.watermarkChapter;
    if (book.id === 'wuye') return state.watermarkChapter;
    return book.watermark || book.curChapter || 1;
  }

  function positionFnPopover() {
    const pop = document.getElementById('fn-popover');
    const col = document.querySelector('.reader-main-col');
    if (!pop || !col || !state.activeFootnoteId) return;
    const colRect = col.getBoundingClientRect();
    const left = Math.round(colRect.left + 12);
    const width = Math.max(240, Math.round(colRect.width - 24));
    const bottom = Math.max(8, Math.round(window.innerHeight - colRect.bottom + 14));
    pop.style.left = left + 'px';
    pop.style.width = width + 'px';
    pop.style.bottom = bottom + 'px';
  }

  function openJumpAheadDialog(chapterNum) {
    const book = getCurrentBook();
    const chapters = getBookChapters(book);
    const wm = getBookWatermark(book);
    state.pendingJumpChapter = chapterNum;
    const ch = chapters.find((c) => c.n === chapterNum);
    const desc = document.getElementById('dlg-jump-desc');
    const advBtn = document.getElementById('btn-jump-advance');
    if (desc) {
      desc.textContent =
        '《' +
        book.title +
        '》即将跳转至「' +
        (ch ? ch.title : '第 ' + chapterNum + ' 章') +
        '」，它超出了当前防剧透水位线（第 ' +
        wm +
        ' 章）。请选择处理方式：';
    }
    if (advBtn) {
      advBtn.textContent = '推进阅读进度至第 ' + chapterNum + ' 章（解锁 1–' + chapterNum + ' 章视角）';
    }
    openModal('dlg-jump-ahead');
  }

  function renderCoverSlotHtml(book) {
    const kind = book && book.coverSvg ? book.coverKind || 'image-portrait' : 'cloth';
    const formatStamp = '<span class="cv-stamp">' + RD.esc(book.format) + '</span>';
    if (kind === 'image-portrait') {
      return (
        '<div class="cover-slot is-image-portrait" style="--cloth:' +
        RD.esc(book.cloth) +
        '">' +
        '<img class="cover-img is-portrait" src="' +
        RD.esc(book.coverSvg) +
        '" alt="' +
        RD.esc(book.title) +
        ' 封面">' +
        '<span class="cover-spine-sheen" aria-hidden="true"></span>' +
        formatStamp +
        '</div>'
      );
    }
    if (kind === 'image-wide') {
      return (
        '<div class="cover-slot is-image-wide" style="--cloth:' +
        RD.esc(book.cloth) +
        '">' +
        '<span class="cv-wide-tag">宽幅自适应</span>' +
        '<div class="cover-wide-frame">' +
        '<img class="cover-img is-wide" src="' +
        RD.esc(book.coverSvg) +
        '" alt="' +
        RD.esc(book.title) +
        ' 宽幅封面">' +
        '</div>' +
        formatStamp +
        '</div>'
      );
    }
    return (
      '<div class="cover-slot is-cloth" style="--cloth:' +
      RD.esc(book.cloth) +
      '">' +
      '<span class="cv-kicker">' +
      RD.esc(book.kicker) +
      '</span>' +
      '<span class="cv-title">' +
      RD.esc(book.title) +
      '</span>' +
      '<span class="cv-author">' +
      RD.esc(book.author) +
      '</span>' +
      formatStamp +
      '</div>'
    );
  }

  /* ==========================================================================
     页面 1：书架渲染
     ========================================================================== */
  function renderBookshelf(root) {
    const heroBook = state.books.find((b) => b.id === 'wuye') || state.books[0];
    const q = state.shelfSearch.trim().toLowerCase();
    const filtered = state.books.filter((b) => {
      const matchText = !q || b.title.toLowerCase().includes(q) || b.author.toLowerCase().includes(q);
      if (!matchText) return false;
      if (state.shelfFilter === 'all') return true;
      if (state.shelfFilter === 'reading') return b.reading;
      return b.filterGroup === state.shelfFilter;
    });
    const inspected = state.books.find((b) => b.id === state.selectedSpineId) || filtered[0] || heroBook;

    const filters = [
      ['all', '全部'],
      ['reading', '在读'],
      ['literature', '想象文学'],
      ['history', '历史书'],
      ['philosophy', '哲学书'],
      ['social', '社会科学'],
      ['pdf', 'PDF']
    ];

    const gridHtml = filtered.length
      ? '<div class="cover-grid">' +
        filtered
          .map((b) => {
            const p = getProfile(b.readType);
            return (
              '<button type="button" class="book-grid-card" data-open-book="' +
              RD.esc(b.id) +
              '">' +
              renderCoverSlotHtml(b) +
              '<div class="book-card-info">' +
              '<div class="book-card-title">' +
              RD.esc(b.title) +
              '</div>' +
              '<div class="book-card-author">' +
              RD.esc(b.author) +
              '</div></div>' +
              '<div class="book-card-meta">' +
              '<span class="persp-pill">' +
              (b.cachedCount > 0 ? '<i class="cache-dot" aria-hidden="true"></i>' : '') +
              RD.esc(p.name) +
              '</span>' +
              '<span class="tnum">' +
              (b.isPdf ? 'p.' + b.curChapter : b.curChapter + '/' + b.totalChapters + '章') +
              '</span>' +
              '</div></button>'
            );
          })
          .join('') +
        '</div>'
      : '<div class="wb-sub" style="padding:28px 0;text-align:center">未找到匹配的藏书，请更换关键词或筛选条件。</div>';

    const spineHtml =
      '<div class="shelf-wrap"><div class="shelf">' +
      filtered
        .map((b, idx) => {
          const isSel = inspected && b.id === inspected.id;
          const onTicks = Math.max(1, Math.min(4, Math.ceil((b.curChapter / Math.max(1, b.totalChapters)) * 4)));
          const ticks = [0, 1, 2, 3].map((i) => '<i class="' + (i < onTicks ? 'on' : '') + '"></i>').join('');
          return (
            '<button type="button" class="spine' +
            (isSel ? ' is-selected' : '') +
            '" style="--cloth:' +
            RD.esc(b.cloth) +
            ';--h:' +
            (180 + ((idx * 11) % 24)) +
            'px;--w:48px" data-pick-spine="' +
            RD.esc(b.id) +
            '">' +
            '<span class="edge" aria-hidden="true">' +
            ticks +
            '</span>' +
            '<span class="n">0' +
            (idx + 1) +
            '</span>' +
            '<span class="t">' +
            RD.esc(b.title) +
            '</span>' +
            '<span class="a">' +
            RD.esc(b.author) +
            '</span></button>'
          );
        })
        .join('') +
      '</div>' +
      (inspected
        ? '<div class="shelf-inspector">' +
          '<div class="shelf-inspector-top"><strong>《' +
          RD.esc(inspected.title) +
          '》</strong><span class="fmt-badge">' +
          RD.esc(inspected.format) +
          '</span></div>' +
          '<div class="wb-sub">' +
          RD.esc(inspected.author) +
          ' · ' +
          RD.esc(getProfile(inspected.readType).name) +
          ' · ' +
          RD.esc(inspected.currentLabel) +
          '</div>' +
          '<div class="shelf-inspector-acts">' +
          '<button type="button" class="btn btn-primary btn-sm" data-open-book="' +
          RD.esc(inspected.id) +
          '">' +
          (inspected.isPdf ? '进入 PDF 伴读 →' : '打开书页目录 →') +
          '</button>' +
          (!inspected.isPdf
            ? '<button type="button" class="btn btn-surface btn-sm" data-continue-wuye>继续阅读</button>'
            : '') +
          '</div></div>'
        : '') +
      '</div>';

    root.innerHTML =
      '<header class="mobile-topbar">' +
      '<div class="brand-row"><span class="brand-seal" aria-hidden="true">伴</span><span class="brand-name">伴读</span></div>' +
      '<div style="display:flex;gap:6px">' +
      '<button type="button" class="icon-btn" id="btn-shelf-import">+ 导入</button>' +
      '<button type="button" class="icon-btn" data-nav="settings" aria-label="设置中心">' +
      '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9c.26.6.79 1 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>' +
      '</button></div></header>' +
      '<div class="app-scroll">' +
      '<div class="shelf-search-row">' +
      '<label class="search-box" aria-label="搜索书名或作者">' +
      '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>' +
      '<input type="search" id="shelf-search-inp" placeholder="搜索书名、作者…" value="' +
      RD.esc(state.shelfSearch) +
      '">' +
      '</label></div>' +
      '<div class="filter-pills" role="group" aria-label="藏书筛选">' +
      filters
        .map(
          ([k, lbl]) =>
            '<button type="button" class="filter-pill" data-shelf-filter="' +
            k +
            '" aria-pressed="' +
            (state.shelfFilter === k ? 'true' : 'false') +
            '">' +
            lbl +
            '</button>'
        )
        .join('') +
      '</div>' +
      '<div class="hero-reading-card" style="--hero-cloth:' +
      RD.esc(heroBook.cloth) +
      '">' +
      '<div class="hero-reading-body">' +
      '<div class="hero-eyebrow"><span>近期在读 · ' +
      RD.esc(getProfile(state.activeProfileId).name) +
      '</span><span class="spoiler-chip">防剧透锁至第 ' +
      state.watermarkChapter +
      ' 章</span></div>' +
      '<div class="hero-book-title">《' +
      RD.esc(heroBook.title) +
      '》</div>' +
      '<div class="hero-ch-sub">第 ' +
      state.watermarkChapter +
      ' 章 · 钟楼里的第二把钥匙</div>' +
      '<div class="progress-track"><div class="progress-fill" style="width:' +
      heroBook.progressPct +
      '%"></div></div>' +
      '<div class="hero-foot">' +
      '<span style="display:flex;align-items:center;gap:6px"><span class="fmt-badge">.bcbook</span><span class="tnum wb-sub">' +
      heroBook.progressPct +
      '% · ' +
      heroBook.cachedCount +
      ' 项伴读已缓存</span></span>' +
      '<button type="button" class="btn btn-primary btn-sm" id="btn-hero-continue">继续阅读 →</button>' +
      '</div></div></div>' +
      '<div class="shelf-head-row">' +
      '<h2>个人藏书（' +
      filtered.length +
      '）</h2>' +
      '<div class="view-toggle" role="group" aria-label="书架视图切换">' +
      '<button type="button" data-shelf-view="grid" aria-pressed="' +
      (state.shelfView === 'grid' ? 'true' : 'false') +
      '">封面网格</button>' +
      '<button type="button" data-shelf-view="spine" aria-pressed="' +
      (state.shelfView === 'spine' ? 'true' : 'false') +
      '">实体书脊墙</button>' +
      '</div></div>' +
      (state.shelfView === 'grid' ? gridHtml : spineHtml) +
      '</div>' +
      renderBottomNav('bookshelf');

    const searchInp = root.querySelector('#shelf-search-inp');
    if (searchInp) {
      searchInp.addEventListener('input', () => {
        const pos = searchInp.selectionStart;
        state.shelfSearch = searchInp.value;
        renderApp();
        const nextInp = document.getElementById('shelf-search-inp');
        if (nextInp) {
          nextInp.focus();
          nextInp.setSelectionRange(pos, pos);
        }
      });
    }
    root.querySelectorAll('[data-shelf-filter]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.shelfFilter = btn.dataset.shelfFilter;
        renderApp();
      });
    });
    root.querySelectorAll('[data-shelf-view]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.shelfView = btn.dataset.shelfView;
        renderApp();
      });
    });
    root.querySelectorAll('[data-pick-spine]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.selectedSpineId = btn.dataset.pickSpine;
        renderApp();
      });
    });
    root.querySelectorAll('[data-open-book]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const b = state.books.find((item) => item.id === btn.dataset.openBook);
        if (!b) return;
        state.selectedBookId = b.id;
        if (b.isPdf) {
          state.pdfMode = b.pdfMode || 'scan';
          state.page = 'pdf';
          renderApp();
          return;
        }
        state.activeChapterNum = b.curChapter || 1;
        state.activeProfileId = b.readType;
        state.page = 'detail';
        renderApp();
      });
    });
    const contBtn = root.querySelector('#btn-hero-continue');
    if (contBtn) {
      contBtn.addEventListener('click', () => {
        state.selectedBookId = 'wuye';
        state.activeProfileId = 'literature';
        state.activeChapterNum = state.watermarkChapter;
        state.page = 'reader';
        renderApp();
      });
    }
    root.querySelectorAll('[data-continue-wuye]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const b = state.books.find((x) => x.id === state.selectedSpineId) || getCurrentBook();
        state.selectedBookId = b.id;
        state.activeProfileId = b.readType;
        state.activeChapterNum = b.curChapter || 1;
        state.page = 'reader';
        renderApp();
      });
    });
    const impBtn = root.querySelector('#btn-shelf-import');
    if (impBtn) impBtn.addEventListener('click', () => openModal('dlg-import'));
    bindBottomNav(root);
  }

  /* ==========================================================================
     页面 2：书籍详情与目录
     ========================================================================== */
  function renderBookDetail(root) {
    const book = getCurrentBook();
    const profile = getProfile(state.activeProfileId || book.readType);
    const wm = getBookWatermark(book);
    const chapters = getBookChapters(book);
    const frontBack = getBookFrontBack(book);

    const beforeWm = chapters.filter((c) => c.n <= wm);
    const afterWm = chapters.filter((c) => c.n > wm);

    const makeRow = (ch, locked) => {
      const isCur = ch.n === (book.id === 'wuye' ? state.activeChapterNum : book.curChapter);
      const badge = isCur
        ? '<span class="ch-state cur">在读</span>'
        : ch.cached
          ? '<span class="ch-state has">导图 · ' + Math.max(2, book.cachedCount || 2) + ' 项伴读</span>'
          : '<span class="ch-state">🔒 未读</span>';
      return (
        '<button type="button" class="ch-row' +
        (isCur ? ' is-current' : '') +
        (locked ? ' is-locked' : '') +
        '" data-ch-num="' +
        ch.n +
        '" data-ch-locked="' +
        (locked ? '1' : '0') +
        '">' +
        '<span class="ch-n">' +
        String(ch.n).padStart(2, '0') +
        '</span>' +
        '<span class="ch-title">' +
        RD.esc(ch.title) +
        '<small>' +
        RD.esc(ch.chars) +
        ' · ' +
        RD.esc(ch.min) +
        '</small></span>' +
        badge +
        '<span style="color:var(--ink-3)">›</span></button>'
      );
    };

    root.innerHTML =
      '<header class="mobile-topbar">' +
      '<button type="button" class="icon-btn" data-nav="bookshelf">← 书架</button>' +
      '<span style="font-weight:700;font-size:13.5px">书页详情与目录</span>' +
      '<button type="button" class="icon-btn" id="btn-detail-persp">' +
      RD.esc(profile.name) +
      ' ▾</button></header>' +
      '<div class="app-scroll">' +
      '<div class="book-hero-mobile">' +
      renderCoverSlotHtml(book) +
      '<div class="book-hero-copy">' +
      '<h1>《' +
      RD.esc(book.title) +
      '》</h1>' +
      '<div class="book-hero-sub">' +
      RD.esc(book.author) +
      ' · <button type="button" class="persp-pill" id="btn-hero-persp">' +
      RD.esc(profile.name) +
      '（6+1 视角 ▾）</button></div>' +
      '<div class="book-stats-3">' +
      '<div><span>章节</span><b class="tnum">' +
      book.totalChapters +
      ' 章</b></div>' +
      '<div><span>进度</span><b class="tnum">第 ' +
      wm +
      ' 章</b></div>' +
      '<div><span>已缓存</span><b class="tnum">' +
      book.cachedCount +
      ' 项</b></div></div>' +
      '<div class="book-hero-actions">' +
      '<button type="button" class="btn btn-primary btn-sm" id="btn-detail-continue">继续阅读 →</button>' +
      '<button type="button" class="btn btn-surface btn-sm" id="btn-detail-map">全章导图概览</button>' +
      '</div></div></div>' +
      '<div class="ch-list">' +
      beforeWm.map((c) => makeRow(c, false)).join('') +
      '<div class="watermark-divider">' +
      '<span>🔒 防剧透水位线 · 以上 1–' +
      wm +
      ' 章已纳入全局推理</span>' +
      '<span class="mono">WATERMARK</span>' +
      '</div>' +
      afterWm.map((c) => makeRow(c, true)).join('') +
      '<button type="button" class="ch-row" id="btn-notes-ch">' +
      '<span class="ch-n">注</span>' +
      '<span class="ch-title">全书注释与考据汇编<small>归并全书释义、修缮志引文与法医物证备忘</small></span>' +
      '<span class="ch-state notes">注释</span>' +
      '<span>›</span></button>' +
      '<details class="ch-fold-group">' +
      '<summary class="ch-fold-summary">' +
      '<span>前置 / 后置页（前折页、版权页、总目录、奥付 · 4 项 · 默认折叠）</span>' +
      '<span>展开 ▾</span>' +
      '</summary>' +
      '<div class="ch-fold-body">' +
      frontBack
        .map(
          (fb, idx) =>
            '<button type="button" class="ch-row" data-fb-item="' +
            RD.esc(fb.title) +
            '">' +
            '<span class="ch-n">F' +
            (idx + 1) +
            '</span><span class="ch-title">' +
            RD.esc(fb.title) +
            '</span><span class="ch-state">' +
            RD.esc(fb.kind) +
            '</span><span>›</span></button>'
        )
        .join('') +
      '</div></details></div></div>' +
      renderBottomNav('bookshelf');

    root.querySelector('#btn-detail-persp').addEventListener('click', openPerspectiveDialog);
    root.querySelector('#btn-hero-persp').addEventListener('click', openPerspectiveDialog);
    root.querySelector('#btn-detail-continue').addEventListener('click', () => {
      state.activeChapterNum = wm;
      state.page = 'reader';
      renderApp();
    });
    root.querySelector('#btn-detail-map').addEventListener('click', () => {
      state.activeChapterNum = wm;
      state.page = 'reader';
      state.companionOpen = true;
      state.companionSnap = 'half';
      state.activeToolId = 'chapter_map';
      renderApp();
    });
    root.querySelectorAll('.ch-row[data-ch-num]').forEach((row) => {
      row.addEventListener('click', () => {
        const n = Number(row.dataset.chNum);
        const locked = row.dataset.chLocked === '1';
        if (locked) {
          openJumpAheadDialog(n);
        } else {
          state.activeChapterNum = n;
          state.page = 'reader';
          renderApp();
        }
      });
    });
    root.querySelector('#btn-notes-ch').addEventListener('click', () => {
      state.activeChapterNum = wm;
      state.page = 'reader';
      renderApp();
      setTimeout(() => {
        const endnotes = document.getElementById('reader-endnotes');
        if (endnotes) endnotes.scrollIntoView({ behavior: state.theme === 'eink' ? 'auto' : 'smooth' });
      }, 60);
    });
    root.querySelectorAll('[data-fb-item]').forEach((btn) => {
      btn.addEventListener('click', () => RD.toast('已打开水页：' + btn.dataset.fbItem));
    });
    bindBottomNav(root);
  }

  /* ==========================================================================
     页面 3 + 4：沉浸式流式阅读器 + 三档伴读抽屉
     ========================================================================== */
  function renderReader(root) {
    const isFoldable = state.viewport === 'foldable';
    const dockCompanion = isFoldable;
    const book = getCurrentBook();
    const chapters = getBookChapters(book);
    const wm = getBookWatermark(book);
    const chObj = chapters.find((c) => c.n === state.activeChapterNum) || chapters[Math.min(2, chapters.length - 1)] || chapters[0];
    const profile = getProfile(state.activeProfileId);

    const fontMap = {
      serif: 'var(--serif)',
      kai: 'var(--kai)',
      sans: 'var(--sans)'
    };
    const marginMap = { narrow: '12px', mid: '20px', wide: '28px' };
    document.documentElement.style.setProperty('--reader-size', state.fontSize + 'px');
    document.documentElement.style.setProperty('--reader-line-height', String(state.lineHeight));
    document.documentElement.style.setProperty('--reader-pad-x', marginMap[state.marginMode]);
    document.documentElement.style.setProperty('--reader-font', fontMap[state.fontFamily]);

    const isWuyeCh3 = book.id === 'wuye' && state.activeChapterNum === 3;
    const rawBlocks = isWuyeCh3
      ? DATA.chapterBlocks
      : (DATA.bookPreviewBlocks && DATA.bookPreviewBlocks[book.id]) || [
          { id: 'B01', text: '本章为《' + book.title + '》' + (chObj ? chObj.title : '第 ' + state.activeChapterNum + ' 章') + ' 的流式正文样章预览，已绑定 ' + profile.name + ' 视角与防剧透水位（锁至第 ' + wm + ' 章）。' },
          { id: 'B02', text: '在离线本地阅读器中，正文采用首行缩进 2em、两端对齐与标点挤压（palt）排印，单击中央可呼出上下 HUD 与 Aa 排版面板。' }
        ];
    const previewBanner = isWuyeCh3
      ? ''
      : '<div style="margin-bottom:14px;padding:8px 10px;border-radius:8px;background:var(--amber-soft);color:var(--amber);font-size:12px">样章预览 · 当前显示《' +
        RD.esc(book.title) +
        '》（' +
        RD.esc(chObj ? chObj.title : '') +
        '）' +
        (state.activeChapterNum > wm ? '，防剧透水位线仍锁至第 ' + wm + ' 章' : '') +
        '</div>';

    const blocksHtml =
      previewBanner +
      rawBlocks
        .map((blk) => {
        let inner = RD.esc(blk.text);
        if (blk.highlight) {
          const hlColor = state.hlColors[blk.highlight.id] || blk.highlight.color;
          inner +=
            '<span class="hl-span" data-hl-id="' +
            RD.esc(blk.highlight.id) +
            '" data-blk="' +
            RD.esc(blk.id) +
            '" data-hl-color="' +
            RD.esc(hlColor) +
            '" tabindex="0">' +
            RD.esc(blk.highlight.text) +
            '</span>' +
            RD.esc(blk.afterHighlight || '');
        }
        if (blk.footnote) {
          inner +=
            '<span class="fn-ref-wrap"><button type="button" class="fn-ref" id="fn-ref-' +
            RD.esc(blk.footnote.num) +
            '" data-fn-id="' +
            RD.esc(blk.footnote.id) +
            '">' +
            RD.esc(blk.footnote.label) +
            '</button></span>';
        }
        const notes = state.customNotes.filter((n) => n.blockId === blk.id);
        const notesHtml = notes
          .map(
            (n) =>
              '<details class="pin-card pin-note">' +
              '<summary><span class="pin-sum-tag">眉批</span><span class="pin-sum-excerpt">' +
              RD.esc(n.excerpt) +
              '</span></summary>' +
              '<div class="pin-note-body">' +
              RD.esc(n.body) +
              '<div class="pin-note-foot"><button type="button" class="pin-del" data-del-note="' +
              RD.esc(n.id) +
              '">移除</button></div></div></details>'
          )
          .join('');
        return (
          '<article class="reader-block" id="blk-' +
          RD.esc(blk.id) +
          '" data-block-id="' +
          RD.esc(blk.id) +
          '">' +
          '<p><span class="blk-id-tag">' +
          RD.esc(blk.id) +
          '</span>' +
          inner +
          '</p>' +
          notesHtml +
          '</article>'
        );
      })
      .join('');

    const fnDefs = DATA.chapterBlocks.filter((b) => b.footnote).map((b) => b.footnote);
    const endnotesHtml =
      '<section class="reader-endnotes" id="reader-endnotes">' +
      '<h3>本章注释（点注号原地查看，不跳走正文）</h3>' +
      fnDefs
        .map(
          (fn) =>
            '<div class="reader-footnote-item" id="fn-def-' +
            RD.esc(fn.num) +
            '">' +
            '<div><span class="fn-def-num">[' +
            RD.esc(fn.num) +
            ']</span>' +
            RD.esc(fn.summary) +
            ' <span class="muted">（' +
            RD.esc(fn.source) +
            '）</span></div>' +
            '<button type="button" class="fn-back-inline" data-fn-return="' +
            RD.esc(fn.num) +
            '">↩ 返回正文</button></div>'
        )
        .join('') +
      '</section>';

    const hudTopHtml = state.hudVisible
      ? '<div class="hud-top">' +
        '<button type="button" class="icon-btn" data-nav="detail">← 目录</button>' +
        '<span class="hud-title">' +
        RD.esc(chObj.title) +
        '</span>' +
        '<button type="button" class="persp-pill" id="btn-hud-persp">' +
        RD.esc(profile.name) +
        ' ▾</button>' +
        '<button type="button" class="icon-btn" id="btn-hud-bookmark" aria-pressed="' +
        (state.bookmarked ? 'true' : 'false') +
        '" aria-label="书签">🔖</button>' +
        '</div>'
      : '';

    const hudBottomHtml = state.hudVisible
      ? '<div class="hud-bottom">' +
        '<div class="hud-slider-row"><span>第 ' +
        state.activeChapterNum +
        ' 章</span><input type="range" min="1" max="' +
        chapters.length +
        '" value="' +
        state.activeChapterNum +
        '" id="hud-ch-slider" aria-label="章节进度"><span class="tnum">42%</span></div>' +
        '<div class="hud-btn-row">' +
        '<button type="button" data-nav="detail">目录</button>' +
        '<button type="button" id="btn-hud-typo" aria-pressed="' +
        (state.typoPanelOpen ? 'true' : 'false') +
        '">Aa 排版</button>' +
        '<button type="button" id="btn-hud-theme">主题</button>' +
        '<button type="button" id="btn-hud-companion">✦ 伴读</button>' +
        '</div></div>'
      : '';

    const pagModes =
      state.theme === 'eink'
        ? [['none', '无动画（墨水屏锁定）']]
        : [
            ['slide', '滑动'],
            ['cover', '覆盖'],
            ['none', '无动画'],
            ['scroll', '滚屏']
          ];

    const typoPanelHtml =
      state.hudVisible && state.typoPanelOpen
        ? '<div class="hud-panel" role="region" aria-label="Aa 排版设置">' +
          '<div class="typo-row"><label for="typo-font-size">字号 ' +
          state.fontSize +
          'px</label><input id="typo-font-size" type="range" min="14" max="26" value="' +
          state.fontSize +
          '"></div>' +
          '<div class="typo-row"><label for="typo-line-height">行高 ' +
          state.lineHeight.toFixed(2) +
          '</label><input id="typo-line-height" type="range" min="1.5" max="2.2" step="0.05" value="' +
          state.lineHeight +
          '"></div>' +
          '<div class="typo-row"><label>页边距</label><div class="typo-seg">' +
          [
            ['narrow', '窄'],
            ['mid', '中'],
            ['wide', '宽']
          ]
            .map(([k, l]) => '<button type="button" data-margin="' + k + '" aria-pressed="' + (state.marginMode === k ? 'true' : 'false') + '">' + l + '</button>')
            .join('') +
          '</div></div>' +
          '<div class="typo-row"><label>字体</label><div class="typo-seg">' +
          [
            ['serif', '思源宋体'],
            ['kai', '仿宋楷韵'],
            ['sans', '现代黑体']
          ]
            .map(([k, l]) => '<button type="button" data-font="' + k + '" aria-pressed="' + (state.fontFamily === k ? 'true' : 'false') + '">' + l + '</button>')
            .join('') +
          '<button type="button" id="btn-import-ttf">+ 导入 .ttf</button></div></div>' +
          '<div class="typo-row"><label>标点挤压 (palt)</label><button type="button" class="wb-btn" id="btn-toggle-kern" aria-pressed="' +
          (state.kerningEnabled ? 'true' : 'false') +
          '">' +
          (state.kerningEnabled ? '已开启' : '已关闭') +
          '</button></div>' +
          '<div class="typo-row"><label>翻页模式</label><div class="typo-seg">' +
          pagModes
            .map(([k, l]) => '<button type="button" data-pag="' + k + '" aria-pressed="' + (state.paginationMode === k ? 'true' : 'false') + '">' + l + '</button>')
            .join('') +
          '</div></div>' +
          '<div class="typo-row"><label>音量键翻页</label><button type="button" class="wb-btn" id="btn-toggle-vol" aria-pressed="' +
          (state.volumeKeyTurn ? 'true' : 'false') +
          '">' +
          (state.volumeKeyTurn ? '开启' : '关闭') +
          '</button></div>' +
          '<div class="typo-row" style="flex-direction:column;align-items:flex-start"><label>主题色板</label><div class="theme-dots-row">' +
          [
            ['warm', '暖纸', '#F4ECD8'],
            ['paper', '素白', '#FBF9F5'],
            ['slate', '夜墨', '#1A1F29'],
            ['amoled', '纯黑', '#000000'],
            ['eink', '墨水屏', '#FFFFFF']
          ]
            .map(
              ([k, l, hex]) =>
                '<button type="button" class="theme-swatch-btn" data-reader-theme="' +
                k +
                '" aria-pressed="' +
                (state.theme === k ? 'true' : 'false') +
                '"><i class="swatch-dot" style="background:' +
                hex +
                '"></i>' +
                l +
                '</button>'
            )
            .join('') +
          '</div></div></div>'
        : '';

    const selectionBarHtml = state.selectionOpen
      ? '<div class="selection-bar" role="toolbar" aria-label="划词操作栏">' +
        [
          ['amber', '#E4A84E'],
          ['pine', '#1E5A4C'],
          ['cinnabar', '#A8442F'],
          ['indigo', '#2C4056'],
          ['slate', '#686258']
        ]
          .map(
            ([c, hex]) =>
              '<button type="button" class="sel-color-dot" style="background:' +
              hex +
              '" data-sel-color="' +
              c +
              '" aria-label="高亮颜色 ' +
              c +
              '"></button>'
          )
          .join('') +
        '<span class="sel-sep"></span>' +
        '<button type="button" class="sel-act-btn" id="btn-sel-note">笔记</button>' +
        '<button type="button" class="sel-act-btn" data-sel-ai="细读">✦ 细读</button>' +
        '<button type="button" class="sel-act-btn" data-sel-ai="解释">✦ 解释</button>' +
        '<button type="button" class="sel-act-btn" data-sel-ai="翻译">✦ 翻译</button>' +
        '<button type="button" class="sel-act-btn" id="btn-sel-copy">复制</button>' +
        '</div>'
      : '';

    const activeFn = state.activeFootnoteId
      ? fnDefs.find((f) => f.id === state.activeFootnoteId)
      : null;

    const fnPopoverHtml =
      '<div class="fn-popover" id="fn-popover" popover="manual">' +
      (activeFn
        ? '<div class="fn-pop-head"><span>原注 ' +
          RD.esc(activeFn.label) +
          ' · 不打断正文位置</span><button type="button" class="wb-btn" id="btn-fn-close">关闭</button></div>' +
          '<div class="fn-pop-body"><p style="margin:0 0 4px">' +
          RD.esc(activeFn.summary) +
          '</p><div class="muted" style="font-size:11px">出处：' +
          RD.esc(activeFn.source) +
          '</div></div>' +
          '<div class="fn-pop-actions">' +
          '<button type="button" class="btn btn-surface btn-sm" id="btn-fn-jump-end">跳至原注</button>' +
          '<button type="button" class="btn btn-primary btn-sm" id="btn-fn-ai">✦ AI 延伸解释</button>' +
          '</div>'
        : '') +
      '</div>';

    const returnPillHtml = state.fnReturnTargetId
      ? '<div class="return-pill-bar"><span>已跳转至注释 [' +
        RD.esc(state.fnReturnTargetId) +
        ']</span><button type="button" class="return-pill-btn" id="btn-fn-ret-main">↩ 返回原阅读处 [' +
        RD.esc(state.fnReturnTargetId) +
        ']</button></div>'
      : state.companionReturnSnap
        ? '<div class="return-pill-bar"><span>已定位至正文锚点</span><button type="button" class="return-pill-btn" id="btn-ret-companion">↩ 返回伴读图谱</button></div>'
        : '';

    const companionHtml =
      state.companionOpen || dockCompanion ? renderCompanionSheetHtml(dockCompanion) : '';

    root.innerHTML =
      '<div class="reader-shell' +
      (dockCompanion ? ' has-dock-companion' : '') +
      '">' +
      '<div class="reader-main-col">' +
      '<div class="reading-progress-line"><i></i></div>' +
      '<div class="reader-running-head"><span>' +
      RD.esc(book.title) +
      ' · 第 ' +
      state.activeChapterNum +
      ' 章</span><span>' +
      (state.bookmarked ? '🔖 已书签' : '防剧透锁至第 ' + wm + ' 章') +
      '</span></div>' +
      '<div class="reader-scroll-area" id="reader-scroll-area">' +
      '<div class="reader-ch-header"><div class="reader-ch-kicker">' +
      RD.esc(book.author) +
      ' · ' +
      RD.esc(getProfile(book.readType).name) +
      '（' +
      RD.esc(profile.name) +
      '视角）</div><h1>' +
      RD.esc(chObj.title) +
      '</h1></div>' +
      '<div class="reader-body' +
      (state.kerningEnabled ? '' : ' no-kerning') +
      '" id="reader-body">' +
      blocksHtml +
      (isWuyeCh3 ? endnotesHtml : '') +
      '</div></div>' +
      '<div class="reader-running-foot">' +
      '<span>第 ' +
      state.activeChapterNum +
      ' 章 · 42%</span>' +
      (!state.companionOpen && !dockCompanion
        ? '<button type="button" class="reader-fab-companion" id="btn-fab-companion">✦ 伴读</button>'
        : '<span></span>') +
      '</div>' +
      hudTopHtml +
      hudBottomHtml +
      typoPanelHtml +
      selectionBarHtml +
      fnPopoverHtml +
      returnPillHtml +
      '</div>' +
      companionHtml +
      '</div>';

    /* 挂载真蓝图或导图树 */
    if (state.companionOpen || dockCompanion) {
      mountCompanionContent(root);
    }

    /* 若脚注浮卡激活，使用原生 popover showPopover / 降级并定位到当前阅读列内 */
    const fnPopEl = root.querySelector('#fn-popover');
    if (fnPopEl && activeFn) {
      if (typeof fnPopEl.showPopover === 'function') {
        try { fnPopEl.showPopover(); } catch (e) { fnPopEl.classList.add(':popover-open'); }
      } else {
        fnPopEl.classList.add(':popover-open');
      }
      positionFnPopover();
    }

    bindReaderEvents(root, dockCompanion);
  }

  function bindReaderEvents(root, dockCompanion) {
    const scrollArea = root.querySelector('#reader-scroll-area');
    if (scrollArea) {
      scrollArea.addEventListener('click', (e) => {
        if (e.target.closest('button, a, summary, details, .hl-span, .fn-ref, .selection-bar')) return;
        if (state.selectionOpen) {
          state.selectionOpen = false;
          renderApp();
          return;
        }
        state.hudVisible = !state.hudVisible;
        if (!state.hudVisible) state.typoPanelOpen = false;
        renderApp();
      });
    }

    root.querySelectorAll('.hl-span').forEach((span) => {
      span.addEventListener('click', (e) => {
        e.stopPropagation();
        state.selectedBlockId = span.dataset.blk || 'B02';
        state.selectionContextQuote = span.textContent.trim();
        state.selectionOpen = true;
        state.hudVisible = false;
        renderApp();
      });
    });

    root.querySelectorAll('.fn-ref').forEach((ref) => {
      ref.addEventListener('click', (e) => {
        e.stopPropagation();
        state.activeFootnoteId = ref.dataset.fnId;
        state.hudVisible = false;
        if (state.companionOpen && state.viewport !== 'foldable') {
          state.companionOpen = false;
        }
        renderApp();
      });
    });

    root.querySelectorAll('[data-del-note]').forEach((btn) => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        state.customNotes = state.customNotes.filter((n) => n.id !== btn.dataset.delNote);
        RD.toast('已移除行内眉批');
        renderApp();
      });
    });

    const fab = root.querySelector('#btn-fab-companion');
    if (fab) {
      fab.addEventListener('click', (e) => {
        e.stopPropagation();
        state.companionOpen = true;
        state.companionSnap = 'half';
        state.companionReturnSnap = false;
        renderApp();
      });
    }

    /* HUD 绑定 */
    const hudPersp = root.querySelector('#btn-hud-persp');
    if (hudPersp) hudPersp.addEventListener('click', openPerspectiveDialog);
    const hudBm = root.querySelector('#btn-hud-bookmark');
    if (hudBm) {
      hudBm.addEventListener('click', () => {
        state.bookmarked = !state.bookmarked;
        RD.toast(state.bookmarked ? '已添加第 ' + state.activeChapterNum + ' 章书签' : '已移除书签');
        renderApp();
      });
    }
    const chSlider = root.querySelector('#hud-ch-slider');
    if (chSlider) {
      chSlider.addEventListener('change', () => {
        const val = Number(chSlider.value);
        const wm = getBookWatermark(getCurrentBook());
        if (val > wm) openJumpAheadDialog(val);
        else { state.activeChapterNum = val; renderApp(); }
      });
    }
    const btnTypo = root.querySelector('#btn-hud-typo');
    if (btnTypo) {
      btnTypo.addEventListener('click', () => {
        state.typoPanelOpen = !state.typoPanelOpen;
        renderApp();
      });
    }
    const btnTheme = root.querySelector('#btn-hud-theme');
    if (btnTheme) {
      btnTheme.addEventListener('click', () => {
        const order = ['warm', 'paper', 'slate', 'amoled', 'eink'];
        const next = order[(order.indexOf(state.theme) + 1) % order.length];
        applyTheme(next);
        renderApp();
      });
    }
    const btnHudComp = root.querySelector('#btn-hud-companion');
    if (btnHudComp) {
      btnHudComp.addEventListener('click', () => {
        state.hudVisible = false;
        state.typoPanelOpen = false;
        state.companionOpen = true;
        state.companionSnap = 'half';
        renderApp();
      });
    }

    /* Aa 排版控件 */
    const fsRange = root.querySelector('#typo-font-size');
    if (fsRange) {
      fsRange.addEventListener('input', () => {
        state.fontSize = Number(fsRange.value);
        renderApp();
      });
    }
    const lhRange = root.querySelector('#typo-line-height');
    if (lhRange) {
      lhRange.addEventListener('input', () => {
        state.lineHeight = Number(lhRange.value);
        renderApp();
      });
    }
    root.querySelectorAll('[data-margin]').forEach((b) => {
      b.addEventListener('click', () => { state.marginMode = b.dataset.margin; renderApp(); });
    });
    root.querySelectorAll('[data-font]').forEach((b) => {
      b.addEventListener('click', () => { state.fontFamily = b.dataset.font; renderApp(); });
    });
    const impTtf = root.querySelector('#btn-import-ttf');
    if (impTtf) impTtf.addEventListener('click', () => RD.toast('已模拟选择本地字体：霞鹜文楷 LXGWWenKai.ttf'));
    const togKern = root.querySelector('#btn-toggle-kern');
    if (togKern) togKern.addEventListener('click', () => { state.kerningEnabled = !state.kerningEnabled; renderApp(); });
    root.querySelectorAll('[data-pag]').forEach((b) => {
      b.addEventListener('click', () => { state.paginationMode = b.dataset.pag; renderApp(); });
    });
    const togVol = root.querySelector('#btn-toggle-vol');
    if (togVol) togVol.addEventListener('click', () => { state.volumeKeyTurn = !state.volumeKeyTurn; renderApp(); });
    root.querySelectorAll('[data-reader-theme]').forEach((b) => {
      b.addEventListener('click', () => { applyTheme(b.dataset.readerTheme); renderApp(); });
    });

    /* 划词操作条 */
    root.querySelectorAll('[data-sel-color]').forEach((dot) => {
      dot.addEventListener('click', () => {
        const targetId = state.selectedBlockId === 'B05' ? 'hl-2' : 'hl-1';
        state.hlColors[targetId] = dot.dataset.selColor;
        RD.toast('已变更高亮色');
        renderApp();
      });
    });
    const btnSelNote = root.querySelector('#btn-sel-note');
    if (btnSelNote) {
      btnSelNote.addEventListener('click', () => {
        const quote = state.selectionContextQuote || '铜钥匙背面的缺口不是磨损';
        state.customNotes.push({
          id: 'pin-' + Date.now(),
          blockId: state.selectedBlockId || 'B02',
          excerpt: quote.slice(0, 18),
          body: '【读者眉批】注意这里的物理细节与后文老周的陈述存在直接张力。'
        });
        state.selectionOpen = false;
        RD.toast('已插入折叠眉批到 [' + (state.selectedBlockId || 'B02') + '] 段下');
        renderApp();
      });
    }
    root.querySelectorAll('[data-sel-ai]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const act = btn.dataset.selAi;
        const quote = state.selectionContextQuote || '铜钥匙背面的缺口不是磨损，而是故意锉出的三道齿痕';
        state.selectionOpen = false;
        state.companionOpen = true;
        state.companionSnap = 'half';
        state.scopeMode = 'selection';
        state.qaTurns.push({
          question: '✦ ' + act + '选段：「' + quote.slice(0, 18) + '…」',
          quote,
          thinking: '基于选段所属 [' + state.selectedBlockId + '] 语境，结合第 1–3 章防剧透水位线完成' + act + '分析（耗时 1.6s）。',
          answer:
            '### ' +
            act +
            '结果（' +
            state.selectedBlockId +
            '）\n- [原文] 这句话揭示了物证的「刻意性」：三道齿痕并非岁月磨损，而是专门针对瑞士机芯发条锁片的改装。\n- [补充] 在叙事功能上，它为后文逼问守钟人老周提供了硬物证支点。',
          blocks: [state.selectedBlockId || 'B02']
        });
        renderApp();
      });
    });
    const btnCopy = root.querySelector('#btn-sel-copy');
    if (btnCopy) {
      btnCopy.addEventListener('click', () => {
        state.selectionOpen = false;
        RD.toast('已复制选中引文');
        renderApp();
      });
    }

    /* 原地脚注浮卡交互 */
    const fnClose = root.querySelector('#btn-fn-close');
    if (fnClose) {
      fnClose.addEventListener('click', () => {
        state.activeFootnoteId = null;
        renderApp();
      });
    }
    const fnJumpEnd = root.querySelector('#btn-fn-jump-end');
    if (fnJumpEnd) {
      fnJumpEnd.addEventListener('click', () => {
        const num = state.activeFootnoteId === 'fn-46' ? '46' : '45';
        state.activeFootnoteId = null;
        state.fnReturnTargetId = num;
        renderApp();
        setTimeout(() => {
          const defEl = document.getElementById('fn-def-' + num);
          if (defEl) {
            defEl.classList.add('fn-active-def');
            defEl.scrollIntoView({ behavior: state.theme === 'eink' ? 'auto' : 'smooth', block: 'center' });
          }
        }, 40);
      });
    }
    const fnAi = root.querySelector('#btn-fn-ai');
    if (fnAi) {
      fnAi.addEventListener('click', () => {
        const fn = DATA.chapterBlocks.map((b) => b.footnote).find((f) => f && f.id === state.activeFootnoteId);
        state.activeFootnoteId = null;
        state.companionOpen = true;
        state.companionSnap = 'half';
        if (fn) {
          state.qaTurns.push({
            question: '✦ 延伸解释原注 ' + fn.label,
            quote: fn.summary,
            thinking: '关联正文注释与第 3 章物证结构（耗时 1.3s）。',
            answer: '### 注释延伸：' + fn.label + '\n- [原文] ' + fn.summary + '\n- [补充] 出处为《' + fn.source + '》，它使 B02 / B06 的机械与化学细节具备可核查的历史工艺依据。',
            blocks: [fn.num === '45' ? 'B02' : 'B06']
          });
        }
        renderApp();
      });
    }

    /* 返回正文原处 */
    const returnFnMain = (num) => {
      state.fnReturnTargetId = null;
      renderApp();
      setTimeout(() => {
        const refEl = document.getElementById('fn-ref-' + num);
        if (refEl) {
          refEl.scrollIntoView({ behavior: state.theme === 'eink' ? 'auto' : 'smooth', block: 'center' });
          refEl.classList.add('fn-return-flash');
          setTimeout(() => refEl.classList.remove('fn-return-flash'), 1800);
        }
      }, 40);
    };
    root.querySelectorAll('[data-fn-return]').forEach((b) => {
      b.addEventListener('click', () => returnFnMain(b.dataset.fnReturn));
    });
    const btnFnRetMain = root.querySelector('#btn-fn-ret-main');
    if (btnFnRetMain) btnFnRetMain.addEventListener('click', () => returnFnMain(state.fnReturnTargetId));

    const btnRetComp = root.querySelector('#btn-ret-companion');
    if (btnRetComp) {
      btnRetComp.addEventListener('click', () => {
        state.companionReturnSnap = false;
        state.companionOpen = true;
        state.companionSnap = 'half';
        renderApp();
      });
    }

    root.querySelectorAll('[data-nav]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.page = btn.dataset.nav;
        renderApp();
      });
    });

    if (state.companionOpen || dockCompanion) {
      bindCompanionEvents(root, dockCompanion);
    }
  }

  /* ==========================================================================
     页面 4：三档伴读抽屉 HTML + 图谱渲染 + 事件绑定
     ========================================================================== */
  function renderCompanionSheetHtml(isDocked) {
    const book = getCurrentBook();
    const profile = getProfile(state.activeProfileId);
    const tools = getToolsForProfile(state.activeProfileId);
    const subGenre =
      book && book.readType === 'literature' && profile.read_type === 'literature' && book.subKind === 'fiction'
        ? profile.name + ' · 小说'
        : profile.name;
    const scopeLabels = [
      ['selection', '选段'],
      ['chapter', '本章'],
      ['upToWatermark', '截至第 ' + state.watermarkChapter + ' 章'],
      ['fullBook', '全书']
    ];

    return (
      '<aside class="comp-sheet' +
      (isDocked ? ' is-docked' : '') +
      '" id="comp-sheet" data-snap="' +
      RD.esc(state.companionSnap) +
      '" aria-label="伴读抽屉">' +
      '<div class="comp-drag-zone" id="comp-drag-zone">' +
      (!isDocked ? '<div class="comp-handle-bar" aria-hidden="true"></div>' : '') +
      '<div class="comp-head">' +
      '<div class="comp-head-left">' +
      '<span class="comp-seal" aria-hidden="true">伴</span>' +
      '<button type="button" class="comp-persp-btn" id="btn-comp-persp">' +
      RD.esc(subGenre) +
      ' ▾</button></div>' +
      '<div class="comp-snap-btns">' +
      (!isDocked
        ? '<button type="button" data-snap-to="peek" aria-pressed="' +
          (state.companionSnap === 'peek' ? 'true' : 'false') +
          '">20%</button>' +
          '<button type="button" data-snap-to="half" aria-pressed="' +
          (state.companionSnap === 'half' ? 'true' : 'false') +
          '">55%</button>' +
          '<button type="button" data-snap-to="full" aria-pressed="' +
          (state.companionSnap === 'full' ? 'true' : 'false') +
          '">92%</button>' +
          '<button type="button" id="btn-comp-close" aria-label="收起伴读抽屉">✕</button>'
        : '<span class="wb-sub">双栏伴读常驻</span>') +
      '</div></div></div>' +
      '<div class="comp-deck">' +
      '<div class="comp-scope-row">' +
      '<div class="scope-seg" role="group" aria-label="伴读上下文作用域">' +
      scopeLabels
        .map(
          ([k, lbl]) =>
            '<button type="button" data-scope="' +
            k +
            '" aria-pressed="' +
            (state.scopeMode === k ? 'true' : 'false') +
            '">' +
            lbl +
            '</button>'
        )
        .join('') +
      '</div>' +
      '<button type="button" class="lock-chip' +
      (state.fullBookUnlocked ? ' is-unlocked' : '') +
      '" id="btn-lock-chip">' +
      (state.fullBookUnlocked ? '🔓 全书已解锁' : '🔒 锁至第 ' + state.watermarkChapter + ' 章') +
      '</button></div>' +
      '<div class="tool-tablist" id="tool-tablist" role="tablist" aria-label="伴读工具栏">' +
      tools
        .map((t) => {
          const isSel = state.activeToolId === t.id;
          const isOfflineLocked = Boolean(t.cross_search && !state.gatewayOnline);
          const isCached = !isOfflineLocked && state.cachedTools.has(t.id);
          const isBuilding = !isOfflineLocked && state.buildingToolId === t.id;
          return (
            '<button type="button" role="tab" id="tab-' +
            RD.esc(t.id) +
            '" class="marg-tool-btn' +
            (isOfflineLocked ? ' is-offline-locked' : isCached ? '' : ' is-uncached') +
            (isSel ? ' active' : '') +
            '" aria-selected="' +
            (isSel ? 'true' : 'false') +
            '" data-tool-id="' +
            RD.esc(t.id) +
            '">' +
            (isOfflineLocked
              ? '<span aria-hidden="true">🔒</span>'
              : isBuilding
                ? '<i class="tool-spin-dot" aria-hidden="true"></i>'
                : isCached
                  ? '<i class="tool-cache-ico" aria-hidden="true"></i>'
                  : '') +
            '<span>' +
            RD.esc(t.label) +
            '</span></button>'
          );
        })
        .join('') +
      '</div></div>' +
      '<div class="comp-body" id="comp-body" role="tabpanel" aria-labelledby="tab-' +
      RD.esc(state.activeToolId) +
      '">' +
      '<div id="comp-stage-mount"></div>' +
      '<div id="comp-qa-stream"></div>' +
      '</div>' +
      '<div class="comp-foot">' +
      '<div class="quick-prompts">' +
      '<button type="button" class="quick-prompt-pill" data-quick-q="为什么第二把钥匙与 1998 年火灾有关？">为什么第二把钥匙与 1998 年火灾有关？</button>' +
      '<button type="button" class="quick-prompt-pill" data-quick-q="把这段翻成克制的剧本台词">把这段翻成克制的剧本台词</button>' +
      '</div>' +
      '<form class="drawer-ask" id="form-comp-ask">' +
      '<input type="text" id="comp-ask-input" placeholder="基于当前视角与水位线继续追问…">' +
      '<button type="submit" class="drawer-send-btn">发送</button>' +
      '</form></div></aside>'
    );
  }

  function focusBlockInReader(blockId) {
    if (!blockId) return;
    if (state.page === 'pdf') {
      RD.toast('定位至 PDF 页面锚点：' + blockId);
      return;
    }
    if (state.viewport !== 'foldable') {
      state.companionSnap = 'peek';
      state.companionReturnSnap = true;
    }
    renderApp();
    setTimeout(() => {
      const target = document.getElementById('blk-' + blockId);
      if (target) {
        target.scrollIntoView({ behavior: state.theme === 'eink' ? 'auto' : 'smooth', block: 'center' });
        target.classList.add('flash');
        setTimeout(() => target.classList.remove('flash'), 2000);
      }
      RD.toast('已定位至正文 [' + blockId + ']');
    }, 50);
  }

  function mountCompanionContent(root) {
    const mountEl = root.querySelector('#comp-stage-mount');
    const qaEl = root.querySelector('#comp-qa-stream');
    if (!mountEl) return;

    const tools = getToolsForProfile(state.activeProfileId);
    const activeTool = tools.find((t) => t.id === state.activeToolId) || tools[0];

    if (state.page === 'pdf' && state.pdfMode === 'scan' && state.pdfVlmTurn) {
      const turn = state.pdfVlmTurn;
      mountEl.innerHTML =
        '<div class="ai-turn-card">' +
        '<div style="display:flex;align-items:center;justify-content:space-between;gap:8px">' +
        '<span class="cap-pill vlm">' +
        RD.esc(turn.model) +
        '</span>' +
        '<span class="mono muted" style="font-size:11px">裁剪选区已附带</span></div>' +
        '<div style="display:flex;gap:10px;align-items:center;padding:8px;border-radius:6px;background:var(--surface);border:1px solid var(--line)">' +
        '<svg width="72" height="54" viewBox="0 0 120 90" style="flex:none;border-radius:4px;border:1px solid var(--line-strong);background:#F6EFE0">' +
        '<polygon points="35,62 35,24 84,62" fill="none" stroke="#6B2B2B" stroke-width="2.2"/>' +
        '<rect x="35" y="62" width="49" height="22" fill="none" stroke="#1E5A4C" stroke-width="1.6"/>' +
        '<line x1="50" y1="36" x2="50" y2="84" stroke="#A8442F" stroke-dasharray="3 2" stroke-width="1.5"/>' +
        '</svg>' +
        '<div style="font-size:12px;color:var(--ink-2)">已框选《几何原本》p.14 勾股弦方图区域，正在以图解视角解析面积割补全等证明。</div></div>' +
        '<details class="thinking-box"><summary>思考过程 (1.4s)</summary><p>' +
        RD.esc(turn.thinking) +
        '</p></details>' +
        '<div style="font-size:12.5px;line-height:1.65">' +
        formatAnswerText(turn.answer) +
        '</div></div>';
    } else if (activeTool.cross_search && !state.gatewayOnline) {
      mountEl.innerHTML =
        '<div class="offline-gateway-card">' +
        '<strong>需连接桌面伴读网关进行跨书检索</strong>' +
        '<p style="margin:0;font-size:12px;color:var(--ink-2)">「' +
        RD.esc(activeTool.label) +
        '」属于跨书对照工具（<code class="mono">cross_search: true</code>），需联动桌面书库全库检索。本地已缓存的单书蓝图仍可离线查看。</p>' +
        '<div><button type="button" class="btn btn-pine btn-sm" id="btn-connect-gateway-inline">一键模拟连接桌面网关</button></div>' +
        '</div>';
      const connBtn = mountEl.querySelector('#btn-connect-gateway-inline');
      if (connBtn) {
        connBtn.addEventListener('click', () => {
          state.gatewayOnline = true;
          syncStageControls();
          RD.toast('桌面网关已上线（192.168.1.18:8765）');
          renderApp();
        });
      }
    } else if (activeTool.id === 'chapter_map') {
      mountEl.innerHTML =
        '<div class="bp-card">' +
        '<div class="bp-card-head"><h4 class="bp-title">全章脉络导图（第 3 章 · 8 个语义块）</h4></div>' +
        RD.diagrams.renderChapterMapTree(DATA.chapterMap) +
        '</div>';
      mountEl.querySelectorAll('[data-jump]').forEach((btn) => {
        btn.addEventListener('click', () => focusBlockInReader(btn.dataset.jump));
      });
    } else {
      const fig = DATA.figures[activeTool.id];
      if (fig && RD.diagrams && RD.diagrams.renderBlueprintCard) {
        RD.diagrams.renderBlueprintCard(mountEl, fig, { onFocusBlock: focusBlockInReader });
      }
    }

    if (qaEl) {
      qaEl.innerHTML = state.qaTurns
        .map(
          (t) =>
            '<div style="display:flex;flex-direction:column;gap:8px;margin-top:8px">' +
            '<div class="ai-user-bubble">' +
            RD.esc(t.question) +
            '</div>' +
            '<div class="ai-turn-card">' +
            (t.quote ? '<div class="drawer-quote">' + RD.esc(t.quote) + '</div>' : '') +
            '<details class="thinking-box"><summary>思考过程 (1.6s)</summary><p>' +
            RD.esc(t.thinking) +
            '</p></details>' +
            '<div style="font-size:12.5px;line-height:1.65">' +
            formatAnswerText(t.answer) +
            '</div>' +
            '<div style="display:flex;gap:5px;flex-wrap:wrap">' +
            (t.blocks || []).map((b) => '<button type="button" class="blk-anchor" data-qa-jump="' + RD.esc(b) + '">↗ ' + RD.esc(b) + '</button>').join('') +
            '</div></div></div>'
        )
        .join('');
      qaEl.querySelectorAll('[data-qa-jump]').forEach((b) => {
        b.addEventListener('click', () => focusBlockInReader(b.dataset.qaJump));
      });
    }
  }

  function formatAnswerText(text) {
    return String(text || '')
      .split('\n')
      .map((line) => {
        const s = line.trim();
        if (!s) return '';
        let html = RD.esc(s)
          .replace(/\[(原文|补充|举例)\]/g, (_, tag) => '<span class="src-tag ' + (tag === '原文' ? 'tag-src' : 'tag-ext') + '">[' + tag + ']</span>');
        if (s.startsWith('### ')) return '<h4 style="margin:6px 0 4px;font-family:var(--serif)">' + html.slice(4) + '</h4>';
        if (s.startsWith('- ')) return '<div style="padding-left:8px;margin:3px 0">• ' + html.slice(2) + '</div>';
        return '<p style="margin:4px 0">' + html + '</p>';
      })
      .join('');
  }

  function bindCompanionEvents(root, isDocked) {
    const sheet = root.querySelector('#comp-sheet');
    const dragZone = root.querySelector('#comp-drag-zone');
    const tablist = root.querySelector('#tool-tablist');

    const perspBtn = root.querySelector('#btn-comp-persp');
    if (perspBtn) perspBtn.addEventListener('click', openPerspectiveDialog);

    root.querySelectorAll('[data-snap-to]').forEach((b) => {
      b.addEventListener('click', () => {
        state.companionSnap = b.dataset.snapTo;
        renderApp();
      });
    });

    const closeBtn = root.querySelector('#btn-comp-close');
    if (closeBtn) {
      closeBtn.addEventListener('click', () => {
        state.companionOpen = false;
        state.pdfVlmTurn = null;
        renderApp();
      });
    }

    /* Pointer 拖拽高度并吸附三档 */
    if (sheet && dragZone && !isDocked) {
      let dragging = false;
      let startY = 0;
      let startH = 0;
      const parentH = () => (sheet.parentElement ? sheet.parentElement.clientHeight : 760);

      dragZone.addEventListener('pointerdown', (e) => {
        if (e.target.closest('button')) return;
        dragging = true;
        startY = e.clientY;
        startH = sheet.getBoundingClientRect().height;
        sheet.classList.add('is-dragging');
        dragZone.setPointerCapture(e.pointerId);
      });
      dragZone.addEventListener('pointermove', (e) => {
        if (!dragging) return;
        const dy = startY - e.clientY;
        const pH = parentH();
        const nextPx = Math.max(pH * 0.16, Math.min(pH * 0.94, startH + dy));
        sheet.style.height = nextPx + 'px';
      });
      const finishDrag = () => {
        if (!dragging) return;
        dragging = false;
        sheet.classList.remove('is-dragging');
        const ratio = sheet.getBoundingClientRect().height / parentH();
        sheet.style.height = '';
        state.companionSnap = ratio < 0.36 ? 'peek' : ratio < 0.74 ? 'half' : 'full';
        renderApp();
      };
      dragZone.addEventListener('pointerup', finishDrag);
      dragZone.addEventListener('pointercancel', finishDrag);
    }

    /* 作用域 & 防剧透锁 */
    root.querySelectorAll('[data-scope]').forEach((b) => {
      b.addEventListener('click', () => {
        const next = b.dataset.scope;
        if (next === 'fullBook' && !state.fullBookUnlocked) {
          openModal('dlg-spoiler-unlock');
          return;
        }
        state.scopeMode = next;
        renderApp();
      });
    });
    const lockChip = root.querySelector('#btn-lock-chip');
    if (lockChip) {
      lockChip.addEventListener('click', () => {
        if (state.fullBookUnlocked) {
          state.fullBookUnlocked = false;
          if (state.scopeMode === 'fullBook') state.scopeMode = 'chapter';
          RD.toast('已重新上锁至第 ' + state.watermarkChapter + ' 章');
          renderApp();
        } else {
          openModal('dlg-spoiler-unlock');
        }
      });
    }

    /* WAI-ARIA Tabs 工具条切换、键盘导航与滚动溢出边缘降级 */
    if (tablist) {
      const tabs = Array.from(tablist.querySelectorAll('[role="tab"]'));
      const activateTab = (tab) => {
        const tid = tab.dataset.toolId;
        if (!tid) return;
        tab.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        const tools = getToolsForProfile(state.activeProfileId);
        const tool = tools.find((t) => t.id === tid);
        if (tool && tool.cross_search && !state.gatewayOnline) {
          state.activeToolId = tid;
          renderApp();
          return;
        }
        if (!state.cachedTools.has(tid)) {
          state.buildingToolId = tid;
          state.activeToolId = tid;
          renderApp();
          setTimeout(() => {
            state.cachedTools.add(tid);
            state.buildingToolId = null;
            RD.toast('已生成并缓存：' + tab.textContent.trim());
            renderApp();
          }, 420);
          return;
        }
        state.activeToolId = tid;
        renderApp();
      };

      tabs.forEach((tab, idx) => {
        tab.addEventListener('click', () => activateTab(tab));
        tab.addEventListener('keydown', (e) => {
          let nextIdx = -1;
          if (e.key === 'ArrowRight') nextIdx = (idx + 1) % tabs.length;
          else if (e.key === 'ArrowLeft') nextIdx = (idx - 1 + tabs.length) % tabs.length;
          else if (e.key === 'Home') nextIdx = 0;
          else if (e.key === 'End') nextIdx = tabs.length - 1;
          if (nextIdx >= 0) {
            e.preventDefault();
            tabs[nextIdx].focus();
            tabs[nextIdx].scrollIntoView({ block: 'nearest', inline: 'nearest' });
          }
        });
      });

      if (!CSS.supports('animation-timeline', 'scroll()') && tabs.length > 1) {
        const first = tabs[0];
        const last = tabs[tabs.length - 1];
        const obs = new IntersectionObserver(
          (entries) => {
            for (const entry of entries) {
              tablist.classList.toggle(entry.target === first ? 'more-start' : 'more-end', entry.intersectionRatio < 1);
            }
          },
          { root: tablist, threshold: 1, rootMargin: '1px' }
        );
        obs.observe(first);
        obs.observe(last);
      }
    }

    /* 多轮追问发送 */
    const submitQuestion = (qText) => {
      const q = String(qText || '').trim();
      if (!q) return;
      const answer = q.includes('台词')
        ? '### 剧本对白改写（B04–B05 克制版）\n- **老周**（风灯微偏）：沈医生，这么晚还来核对走时？\n- **顾沉**（摊开掌心三齿铜匙）：九八年烧掉的不是卷宗室，是老钟的图纸。\n- **老周**（停顿半拍）：二号门打那以后，就再没从里头开过。'
        : '### 关于「' + q + '」的伴读推演\n- [原文] 三道齿痕专门匹配老式瑞士发条锁片（B02），而老周主动将话题引向 1998 年烧毁的“机械报时图纸”（B05），两者指向同一机械控制链。\n- [原文] 门闩内侧三天内的松烟指印（B06）证明有人仍在利用旧图纸出入钟楼，第 3 章水位线内暂未揭晓幕后委托人。';
      state.qaTurns.push({
        question: q,
        thinking: '锁定当前视角（' + getProfile(state.activeProfileId).name + '）与防剧透水位（第 ' + state.watermarkChapter + ' 章）检索原文块 B01–B08（耗时 1.2s）。',
        answer,
        blocks: ['B02', 'B05', 'B06']
      });
      if (state.companionSnap === 'peek') state.companionSnap = 'half';
      renderApp();
      setTimeout(() => {
        const cb = document.getElementById('comp-body');
        if (cb) cb.scrollTop = cb.scrollHeight;
      }, 40);
    };

    const formAsk = root.querySelector('#form-comp-ask');
    const inpAsk = root.querySelector('#comp-ask-input');
    if (formAsk && inpAsk) {
      formAsk.addEventListener('submit', (e) => {
        e.preventDefault();
        submitQuestion(inpAsk.value);
      });
    }
    root.querySelectorAll('[data-quick-q]').forEach((b) => {
      b.addEventListener('click', () => submitQuestion(b.dataset.quickQ));
    });
  }

  /* ==========================================================================
     页面 5：PDF 双形态视图
     ========================================================================== */
  function renderPdf(root) {
    const isScan = state.pdfMode === 'scan';
    const rectStyle = state.pdfRect
      ? 'left:' + state.pdfRect.x + '%;top:' + state.pdfRect.y + '%;width:' + state.pdfRect.w + '%;height:' + state.pdfRect.h + '%'
      : '';

    const scanSvg =
      '<svg viewBox="0 0 360 490" width="100%" style="display:block" aria-label="几何原本卷一命题四十七明刻影印页">' +
      '<rect x="0" y="0" width="360" height="490" fill="#EFE4CC"/>' +
      '<rect x="14" y="16" width="332" height="458" fill="none" stroke="#3B2F23" stroke-width="2.2"/>' +
      '<rect x="19" y="21" width="322" height="448" fill="none" stroke="#3B2F23" stroke-width="0.9"/>' +
      '<line x1="312" y1="21" x2="312" y2="469" stroke="#3B2F23" stroke-width="0.8"/>' +
      '<text x="326" y="54" font-family="serif" font-size="14" font-weight="700" fill="#2A1E14" writing-mode="tb">幾何原本卷一 · 命題四十七</text>' +
      '<g stroke="#2A1E14" stroke-width="1.4" fill="none">' +
      '<polygon points="105,195 141,147 205,195" fill="rgba(168,68,47,0.10)" stroke-width="2"/>' +
      '<polygon points="105,195 205,195 205,295 105,295" fill="rgba(44,64,86,0.08)"/>' +
      '<polygon points="105,195 141,147 93,111 57,159" fill="rgba(30,90,76,0.09)"/>' +
      '<polygon points="141,147 205,195 253,131 189,83" fill="rgba(135,80,14,0.09)"/>' +
      '<line x1="141" y1="147" x2="141" y2="295" stroke="#A8442F" stroke-dasharray="4 3" stroke-width="1.5"/>' +
      '<line x1="105" y1="195" x2="253" y2="131" stroke="#1E5A4C" stroke-width="1.1"/>' +
      '<line x1="205" y1="195" x2="93" y2="111" stroke="#1E5A4C" stroke-width="1.1"/>' +
      '<line x1="141" y1="147" x2="105" y2="295" stroke="#2C4056" stroke-width="1.1"/>' +
      '<line x1="141" y1="147" x2="205" y2="295" stroke="#2C4056" stroke-width="1.1"/>' +
      '</g>' +
      '<g fill="#2A1E14" font-family="serif" font-size="11.5">' +
      '<text x="137" y="139">甲</text><text x="90" y="206">乙</text><text x="211" y="206">丙</text>' +
      '<text x="294" y="54" writing-mode="tb">凡直角三角形</text>' +
      '<text x="276" y="54" writing-mode="tb">對直角之邊上方形</text>' +
      '<text x="258" y="54" writing-mode="tb">與餘兩邊上兩方形等</text>' +
      '<text x="48" y="346">勾三股四弦五 · 面積割補證法</text>' +
      '<text x="48" y="370">以甲為頂點作垂線平分弦上方形為二矩形</text>' +
      '<text x="48" y="394">由全等三角形知兩矩形各等於勾股方形</text>' +
      '</g></svg>';

    const textCropSvg =
      '<svg viewBox="0 0 360 440" width="100%" style="display:block;background:#FFFFFF">' +
      '<g fill="#1E1E1E" font-family="sans-serif">' +
      '<text x="' + (state.pdfAutoCrop ? '16' : '38') + '" y="36" font-size="11" fill="#686258">Operating Systems: Three Easy Pieces · Chapter 18 (p.42)</text>' +
      '<text x="' + (state.pdfAutoCrop ? '16' : '38') + '" y="64" font-size="16" font-weight="700">18.1 Paging &amp; Page Table Translation</text>' +
      '<text x="' + (state.pdfAutoCrop ? '16' : '38') + '" y="94" font-size="12">Unlike segmentation, paging chops up address space into fixed-size</text>' +
      '<text x="' + (state.pdfAutoCrop ? '16' : '38') + '" y="112" font-size="12">units called pages. Physical memory is an array of fixed slots (frames).</text>' +
      '<rect x="' + (state.pdfAutoCrop ? '16' : '38') + '" y="130" width="' + (state.pdfAutoCrop ? '328' : '284') + '" height="18" fill="rgba(228,168,78,0.34)"/>' +
      '<text x="' + (state.pdfAutoCrop ? '16' : '38') + '" y="143" font-size="12">Each virtual address splits into VPN (top bits) and Offset (low bits).</text>' +
      '<rect x="38" y="170" width="284" height="110" rx="6" fill="#F4F1E8" stroke="#2C4056" stroke-width="1.4"/>' +
      '<text x="56" y="196" font-size="11" font-weight="700" fill="#2C4056">32-bit Virtual Address → Physical Address</text>' +
      '<rect x="56" y="208" width="100" height="24" fill="#FCF9F2" stroke="#1E5A4C"/><text x="78" y="224" font-size="11">VPN (20b)</text>' +
      '<rect x="156" y="208" width="120" height="24" fill="#FCF9F2" stroke="#87500E"/><text x="186" y="224" font-size="11">Offset (12b)</text>' +
      '<text x="148" y="256" font-size="11" fill="#A8442F">↓ Page Table Lookup (PTE → PFN)</text>' +
      '<rect x="56" y="266" width="100" height="22" fill="rgba(30,90,76,0.14)" stroke="#1E5A4C"/><text x="78" y="281" font-size="11">PFN (20b)</text>' +
      '<rect x="156" y="266" width="120" height="22" fill="#FCF9F2" stroke="#87500E"/><text x="186" y="281" font-size="11">Offset (12b)</text>' +
      '<text x="' + (state.pdfAutoCrop ? '16' : '38') + '" y="320" font-size="11.5" fill="#4E4A42">已保留 1 条跨形态高亮锚点（Page / VPN 分割机制）</text>' +
      '</g></svg>';

    const scanBody =
      '<div class="pdf-banner">' +
      '<span>扫描版 PDF · 支持手绘框选提问</span>' +
      '<button type="button" class="btn btn-surface btn-sm" id="btn-pdf-ocr">' +
      (state.pdfOcrQueued ? '已入队 OCR 任务 #01 · 等待桌面处理' : '推送桌面 / MinerU 异步 OCR') +
      '</button></div>' +
      '<div class="pdf-stage">' +
      '<div style="display:flex;gap:8px;flex-wrap:wrap;justify-content:center">' +
      '<button type="button" class="btn btn-surface btn-sm" id="btn-pdf-preset-box">示例框选：勾股图示区</button>' +
      '<span class="fmt-badge">' +
      RD.esc(DATA.pdfSample.scanTitle) +
      '</span></div>' +
      '<div class="pdf-page-box" id="pdf-scan-canvas">' +
      scanSvg +
      (state.pdfRect ? '<div class="pdf-selection-rect" style="' + rectStyle + '"></div>' : '') +
      '</div>' +
      (state.pdfRect
        ? '<div class="pdf-sel-actions">' +
          '<button type="button" class="btn btn-primary btn-sm" id="btn-pdf-vlm-ask">✦ 框选提问 (VLM)</button>' +
          '<button type="button" class="sel-act-btn" id="btn-pdf-clear-rect">清除选区</button>' +
          '</div>'
        : '<div class="wb-sub">提示：用鼠标或手指在影印页面上拖拽框选，或点「示例框选」体验 VLM 图解</div>') +
      '</div>';

    const textBody =
      '<div class="pdf-banner">' +
      '<div class="scope-seg">' +
      '<button type="button" data-pdf-sub="crop" aria-pressed="' +
      (state.pdfTextSubMode === 'crop' ? 'true' : 'false') +
      '">原版裁边</button>' +
      '<button type="button" data-pdf-sub="reflow" aria-pressed="' +
      (state.pdfTextSubMode === 'reflow' ? 'true' : 'false') +
      '">流式文+图</button>' +
      '</div>' +
      '<div style="display:flex;gap:6px;flex-wrap:wrap">' +
      '<button type="button" class="wb-btn" id="btn-pdf-autocrop" aria-pressed="' +
      (state.pdfAutoCrop ? 'true' : 'false') +
      '">自动裁白边</button>' +
      '<button type="button" class="wb-btn" id="btn-pdf-invert" aria-pressed="' +
      (state.pdfInvert ? 'true' : 'false') +
      '">暗色反白</button>' +
      '<button type="button" class="btn btn-primary btn-sm" id="btn-pdf-text-comp">✦ 伴读</button>' +
      '</div></div>' +
      '<div class="pdf-stage">' +
      '<span class="fmt-badge">' +
      RD.esc(DATA.pdfSample.textTitle) +
      ' · 锚点同步中</span>' +
      (state.pdfTextSubMode === 'crop'
        ? '<div class="pdf-page-box' +
          (state.pdfInvert ? ' is-inverted' : '') +
          (state.pdfAutoCrop ? ' is-cropped' : '') +
          '">' +
          textCropSvg +
          '</div>'
        : '<div class="pdf-reflow-card' +
          (state.pdfInvert ? ' is-inverted' : '') +
          '">' +
          '<h3 style="margin-bottom:10px;font-family:var(--serif)">' +
          RD.esc(DATA.pdfSample.textHeading) +
          '</h3>' +
          DATA.pdfSample.textParagraphs
            .map(
              (p, i) =>
                '<p style="text-indent:2em;margin:0 0 10px">' +
                (i === 1
                  ? '<span class="hl-span" data-hl-color="amber">' + RD.esc(p) + '</span>'
                  : RD.esc(p)) +
                '</p>'
            )
            .join('') +
          '<div class="wb-sub" style="border-top:1px dashed var(--line);padding-top:8px">✓ 跨形态高亮保留：1 处高亮标记已从原版页面同步至流式视图</div></div>') +
      '</div>';

    const companionHtml = state.companionOpen ? renderCompanionSheetHtml(false) : '';

    root.innerHTML =
      '<div class="reader-shell">' +
      '<header class="pdf-toolbar">' +
      '<button type="button" class="icon-btn" data-nav="bookshelf">← 书架</button>' +
      '<div class="view-toggle" role="group" aria-label="PDF 形态切换">' +
      '<button type="button" data-pdf-mode="scan" aria-pressed="' +
      (isScan ? 'true' : 'false') +
      '">扫描版 · 几何原本</button>' +
      '<button type="button" data-pdf-mode="text" aria-pressed="' +
      (!isScan ? 'true' : 'false') +
      '">文字版 · 操作系统导论</button>' +
      '</div></header>' +
      (isScan ? scanBody : textBody) +
      companionHtml +
      '</div>';

    if (state.companionOpen) mountCompanionContent(root);

    root.querySelectorAll('[data-nav]').forEach((b) => {
      b.addEventListener('click', () => { state.page = b.dataset.nav; state.companionOpen = false; renderApp(); });
    });
    root.querySelectorAll('[data-pdf-mode]').forEach((b) => {
      b.addEventListener('click', () => {
        state.pdfMode = b.dataset.pdfMode;
        state.companionOpen = false;
        state.activeProfileId = state.pdfMode === 'scan' ? 'science' : 'practical';
        renderApp();
      });
    });

    if (isScan) {
      const ocrBtn = root.querySelector('#btn-pdf-ocr');
      if (ocrBtn) {
        ocrBtn.addEventListener('click', () => {
          state.pdfOcrQueued = !state.pdfOcrQueued;
          RD.toast(state.pdfOcrQueued ? '已推送至桌面 MinerU 队列 (#01)' : '已取消 OCR 队列');
          renderApp();
        });
      }
      const presetBtn = root.querySelector('#btn-pdf-preset-box');
      if (presetBtn) {
        presetBtn.addEventListener('click', () => {
          state.pdfRect = { x: 10, y: 12, w: 58, h: 58 };
          renderApp();
        });
      }
      const scanCanvas = root.querySelector('#pdf-scan-canvas');
      if (scanCanvas) {
        let drawing = false;
        let sx = 0;
        let sy = 0;
        const relPt = (e) => {
          const r = scanCanvas.getBoundingClientRect();
          return {
            x: Math.max(0, Math.min(100, ((e.clientX - r.left) / r.width) * 100)),
            y: Math.max(0, Math.min(100, ((e.clientY - r.top) / r.height) * 100))
          };
        };
        const syncRectEl = () => {
          if (!state.pdfRect) return;
          let rectEl = scanCanvas.querySelector('.pdf-selection-rect');
          if (!rectEl) {
            rectEl = document.createElement('div');
            rectEl.className = 'pdf-selection-rect';
            scanCanvas.appendChild(rectEl);
          }
          rectEl.style.left = state.pdfRect.x + '%';
          rectEl.style.top = state.pdfRect.y + '%';
          rectEl.style.width = state.pdfRect.w + '%';
          rectEl.style.height = state.pdfRect.h + '%';
        };
        scanCanvas.addEventListener('pointerdown', (e) => {
          drawing = true;
          const pt = relPt(e);
          sx = pt.x;
          sy = pt.y;
          state.pdfRect = { x: sx, y: sy, w: 1, h: 1 };
          syncRectEl();
          if (scanCanvas.setPointerCapture && e.pointerId != null) {
            try { scanCanvas.setPointerCapture(e.pointerId); } catch (err) {}
          }
        });
        scanCanvas.addEventListener('pointermove', (e) => {
          if (!drawing) return;
          const pt = relPt(e);
          state.pdfRect = {
            x: Math.min(sx, pt.x),
            y: Math.min(sy, pt.y),
            w: Math.max(4, Math.abs(pt.x - sx)),
            h: Math.max(4, Math.abs(pt.y - sy))
          };
          syncRectEl();
        });
        const endDraw = () => {
          if (!drawing) return;
          drawing = false;
          renderApp();
        };
        scanCanvas.addEventListener('pointerup', endDraw);
        scanCanvas.addEventListener('pointercancel', endDraw);
      }
      const vlmAskBtn = root.querySelector('#btn-pdf-vlm-ask');
      if (vlmAskBtn) {
        vlmAskBtn.addEventListener('click', () => {
          state.pdfVlmTurn = DATA.pdfSample.vlmAnswer;
          state.companionOpen = true;
          state.companionSnap = 'half';
          renderApp();
        });
      }
      const clearBtn = root.querySelector('#btn-pdf-clear-rect');
      if (clearBtn) {
        clearBtn.addEventListener('click', () => { state.pdfRect = null; state.pdfVlmTurn = null; renderApp(); });
      }
    } else {
      root.querySelectorAll('[data-pdf-sub]').forEach((b) => {
        b.addEventListener('click', () => { state.pdfTextSubMode = b.dataset.pdfSub; renderApp(); });
      });
      const autoCropBtn = root.querySelector('#btn-pdf-autocrop');
      if (autoCropBtn) autoCropBtn.addEventListener('click', () => { state.pdfAutoCrop = !state.pdfAutoCrop; renderApp(); });
      const invBtn = root.querySelector('#btn-pdf-invert');
      if (invBtn) invBtn.addEventListener('click', () => { state.pdfInvert = !state.pdfInvert; renderApp(); });
      const textCompBtn = root.querySelector('#btn-pdf-text-comp');
      if (textCompBtn) {
        textCompBtn.addEventListener('click', () => {
          state.activeProfileId = 'practical';
          state.activeToolId = 'flow_swimlane';
          state.companionOpen = true;
          state.companionSnap = 'half';
          renderApp();
        });
      }
    }

    if (state.companionOpen) bindCompanionEvents(root, false);
  }

  /* ==========================================================================
     页面 6：设置中心（BYOK 右滑移除 + 手机互联 P2P 传书与伴读资料分享）
     ========================================================================== */
  const p2pSendState = {
    mode: 'qr',
    bookId: 'wuye',
    peerHasSha: true,
    incMaps: true,
    incNotes: true,
    incQa: true,
    incProgress: false,
    incOriginalBook: false
  };

  const p2pScanState = {
    linkText: '',
    preview: null
  };

  function getSelectedP2pBook() {
    return state.books.find((b) => b.id === p2pSendState.bookId) || state.books[0];
  }

  function computeP2pBundleStats(book) {
    const mapKb = p2pSendState.incMaps ? book.mapKb || 96 : 0;
    const noteKb = p2pSendState.incNotes ? book.noteKb || 18 : 0;
    const qaKb = p2pSendState.incQa ? book.qaKb || 24 : 0;
    const progKb = p2pSendState.incProgress ? 4 : 0;
    const dataKb = mapKb + noteKb + qaKb + progKb;
    const includeOriginal = !p2pSendState.peerHasSha && p2pSendState.incOriginalBook;
    const bookMb = Number(book.bookSizeMb || 12.0);
    const totalLabel = includeOriginal
      ? (bookMb + dataKb / 1024).toFixed(1) + ' MB（含原书 + 伴读资料）'
      : dataKb + ' KB（仅传伴读资料 · 免传原书）';
    return { dataKb, includeOriginal, bookMb, totalLabel };
  }

  function renderP2pSendModal() {
    const body = document.getElementById('dlg-p2p-send-body');
    if (!body) return;
    const book = getSelectedP2pBook();
    const shortSha = (book.sha256 || '8f3a91c2').slice(0, 8) + '…';
    const stats = computeP2pBundleStats(book);
    const shareUrl = 'http://192.168.1.24:9721/share/br_7k9m2?sha=' + (book.sha256 || '8f3a91c2');

    const transferPanel =
      p2pSendState.mode === 'qr'
        ? '<div class="p2p-qr-panel">' +
          '<svg width="128" height="128" viewBox="0 0 148 148" aria-label="局域网传书二维码">' +
          '<rect x="2" y="2" width="144" height="144" rx="8" fill="#FFFFFF" stroke="#BEB6A4"/>' +
          '<g fill="#181817">' +
          '<rect x="14" y="14" width="36" height="36" rx="3"/><rect x="20" y="20" width="24" height="24" fill="#FFFFFF"/><rect x="26" y="26" width="12" height="12"/>' +
          '<rect x="98" y="14" width="36" height="36" rx="3"/><rect x="104" y="20" width="24" height="24" fill="#FFFFFF"/><rect x="110" y="26" width="12" height="12"/>' +
          '<rect x="14" y="98" width="36" height="36" rx="3"/><rect x="20" y="104" width="24" height="24" fill="#FFFFFF"/><rect x="26" y="110" width="12" height="12"/>' +
          '<rect x="60" y="18" width="8" height="8"/><rect x="74" y="18" width="8" height="8"/><rect x="60" y="34" width="16" height="8"/>' +
          '<rect x="58" y="58" width="28" height="28" rx="4" fill="#1E5A4C"/><rect x="66" y="66" width="12" height="12" fill="#FFFFFF"/>' +
          '<rect x="98" y="60" width="12" height="8"/><rect x="116" y="60" width="14" height="8"/><rect x="60" y="98" width="14" height="8"/><rect x="98" y="104" width="22" height="10"/>' +
          '</g></svg>' +
          '<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;justify-content:center">' +
          '<span class="cap-pill">配对码 <b class="mono">482-915</b></span>' +
          '<span class="fmt-badge mono">192.168.1.24:9721</span>' +
          '</div>' +
          '<button type="button" class="btn btn-primary btn-sm" id="btn-p2p-sim-send">模拟对方扫码接收</button>' +
          '</div>'
        : '<div class="p2p-link-box">' +
          '<div style="display:flex;align-items:center;justify-content:space-between;gap:6px;flex-wrap:wrap">' +
          '<strong style="font-size:12px">局域网直传链接</strong>' +
          '<span class="cap-pill">15 分钟有效 · 局域网直连</span>' +
          '</div>' +
          '<div class="p2p-url-code" id="p2p-share-url">' +
          RD.esc(shareUrl) +
          '</div>' +
          '<div style="display:flex;gap:8px;flex-wrap:wrap">' +
          '<button type="button" class="btn btn-surface btn-sm" id="btn-p2p-copy-link">复制链接</button>' +
          '<button type="button" class="btn btn-primary btn-sm" id="btn-p2p-sim-link">模拟对方打开链接</button>' +
          '</div></div>';

    body.innerHTML =
      '<div class="byok-seg-row" role="group" aria-label="分享方式">' +
      '<button type="button" data-p2p-send-mode="qr" aria-pressed="' +
      (p2pSendState.mode === 'qr' ? 'true' : 'false') +
      '">生成二维码</button>' +
      '<button type="button" data-p2p-send-mode="link" aria-pressed="' +
      (p2pSendState.mode === 'link' ? 'true' : 'false') +
      '">生成分享链接</button></div>' +
      '<label class="dlg-field"><span>选择要分享的书籍</span>' +
      '<select id="p2p-send-book-sel">' +
      state.books
        .map(
          (b) =>
            '<option value="' +
            RD.esc(b.id) +
            '"' +
            (b.id === book.id ? ' selected' : '') +
            '>《' +
            RD.esc(b.title) +
            '》 · sha256:' +
            RD.esc((b.sha256 || '00000000').slice(0, 8)) +
            '…</option>'
        )
        .join('') +
      '</select></label>' +
      '<div class="dlg-field"><span>对方设备 SHA-256 握手比对</span>' +
      '<div class="byok-seg-row" role="group" aria-label="对方 SHA 状态">' +
      '<button type="button" data-p2p-sha="match" aria-pressed="' +
      (p2pSendState.peerHasSha ? 'true' : 'false') +
      '">对方已有同 SHA 原书（免传书体）</button>' +
      '<button type="button" data-p2p-sha="miss" aria-pressed="' +
      (!p2pSendState.peerHasSha ? 'true' : 'false') +
      '">对方暂无此书（整书 + 资料）</button>' +
      '</div></div>' +
      '<div class="p2p-sha-banner ' +
      (p2pSendState.peerHasSha ? 'is-match' : 'is-miss') +
      '" id="p2p-sha-banner">' +
      (p2pSendState.peerHasSha
        ? '✓ 握手命中同 SHA（' +
          RD.esc(shortSha) +
          '）：对方已有此书，已自动跳过 ' +
          RD.esc(book.bookSizeMb || 18.4) +
          ' MB 原书文件，仅传所选伴读资料'
        : '对方书库无此 SHA：将连同原书文件（' +
          RD.esc(book.bookSizeMb || 18.4) +
          ' MB）与所选资料一并打包传输') +
      '</div>' +
      '<div class="p2p-checklist">' +
      '<div class="p2p-check-row' +
      (p2pSendState.peerHasSha ? ' is-disabled' : '') +
      '"><label><input type="checkbox" id="p2p-chk-book"' +
      (stats.includeOriginal ? ' checked' : '') +
      (p2pSendState.peerHasSha ? ' disabled' : '') +
      '><span>包含原书文件（' +
      RD.esc(book.format) +
      ' · ' +
      RD.esc(book.bookSizeMb || 18.4) +
      ' MB）</span></label>' +
      (p2pSendState.peerHasSha ? '<span class="cap-pill">同 SHA 跳过</span>' : '') +
      '</div>' +
      '<div class="p2p-check-row"><label><input type="checkbox" id="p2p-chk-maps"' +
      (p2pSendState.incMaps ? ' checked' : '') +
      '><span>章节导图与结构蓝图（' +
      (book.mapCount || 4) +
      ' 张已生成蓝图 + 全章脉络树 · ' +
      (book.mapKb || 96) +
      ' KB）</span></label></div>' +
      '<div class="p2p-check-row"><label><input type="checkbox" id="p2p-chk-notes"' +
      (p2pSendState.incNotes ? ' checked' : '') +
      '><span>划词高亮与行内眉批（' +
      (book.highlightCount || 12) +
      ' 条高亮 · ' +
      (book.noteCount || 5) +
      ' 条批注 · ' +
      (book.noteKb || 18) +
      ' KB）</span></label></div>' +
      '<div class="p2p-check-row"><label><input type="checkbox" id="p2p-chk-qa"' +
      (p2pSendState.incQa ? ' checked' : '') +
      '><span>多轮伴读问答记录（' +
      (book.qaCount || 3) +
      ' 段会话 · ' +
      (book.qaKb || 24) +
      ' KB）</span></label></div>' +
      '<div class="p2p-check-row"><label><input type="checkbox" id="p2p-chk-progress"' +
      (p2pSendState.incProgress ? ' checked' : '') +
      '><span>当前阅读进度与防剧透水位（默认不覆盖对方阅读进度）</span></label></div>' +
      '</div>' +
      '<div class="p2p-summary-bar" id="p2p-send-summary"><span>预计传输：' +
      RD.esc(stats.totalLabel) +
      '</span></div>' +
      transferPanel;

    body.querySelectorAll('[data-p2p-send-mode]').forEach((btn) => {
      btn.addEventListener('click', () => {
        p2pSendState.mode = btn.dataset.p2pSendMode;
        renderP2pSendModal();
      });
    });
    const bookSel = body.querySelector('#p2p-send-book-sel');
    if (bookSel) {
      bookSel.addEventListener('change', () => {
        p2pSendState.bookId = bookSel.value;
        renderP2pSendModal();
      });
    }
    body.querySelectorAll('[data-p2p-sha]').forEach((btn) => {
      btn.addEventListener('click', () => {
        p2pSendState.peerHasSha = btn.dataset.p2pSha === 'match';
        p2pSendState.incOriginalBook = !p2pSendState.peerHasSha;
        renderP2pSendModal();
      });
    });
    const chkMap = [
      ['#p2p-chk-book', 'incOriginalBook'],
      ['#p2p-chk-maps', 'incMaps'],
      ['#p2p-chk-notes', 'incNotes'],
      ['#p2p-chk-qa', 'incQa'],
      ['#p2p-chk-progress', 'incProgress']
    ];
    chkMap.forEach(([sel, key]) => {
      const el = body.querySelector(sel);
      if (el) {
        el.addEventListener('change', () => {
          p2pSendState[key] = el.checked;
          renderP2pSendModal();
        });
      }
    });

    const copyBtn = body.querySelector('#btn-p2p-copy-link');
    if (copyBtn) {
      copyBtn.addEventListener('click', () => {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(shareUrl).catch(() => {});
        }
        RD.toast('已复制局域网直传链接：' + shareUrl);
      });
    }

    const completeSend = () => {
      state.p2pLastLog = stats.includeOriginal
        ? '最近传输：已发送《' + book.title + '》整书 + 资料 (' + stats.totalLabel.split('（')[0] + ')'
        : '最近传输：已向对方跳过原书仅发送《' + book.title + '》资料包 (' + stats.dataKb + ' KB)';
      closeModal('dlg-p2p-send');
      renderApp();
      RD.toast(state.p2pLastLog);
    };
    const simQrBtn = body.querySelector('#btn-p2p-sim-send');
    if (simQrBtn) simQrBtn.addEventListener('click', completeSend);
    const simLinkBtn = body.querySelector('#btn-p2p-sim-link');
    if (simLinkBtn) simLinkBtn.addEventListener('click', completeSend);
  }

  function openP2pSendModal(mode) {
    p2pSendState.mode = mode || 'qr';
    renderP2pSendModal();
    openModal('dlg-p2p-send');
  }

  function renderP2pScanModal() {
    const body = document.getElementById('dlg-p2p-scan-body');
    if (!body) return;
    const prev = p2pScanState.preview;
    const localMatch = prev ? state.books.find((b) => b.sha256 === prev.sha256) : null;

    const previewHtml = prev
      ? '<div class="p2p-preview-card" id="p2p-scan-preview">' +
        '<div style="display:flex;align-items:center;justify-content:space-between;gap:6px;flex-wrap:wrap">' +
        '<strong>《' +
        RD.esc(prev.title) +
        '》</strong><span class="fmt-badge">' +
        RD.esc(prev.device) +
        '</span></div>' +
        '<div class="wb-sub mono" style="font-size:11px">sha256:' +
        RD.esc(prev.sha256) +
        ' · ' +
        RD.esc(prev.author) +
        '</div>' +
        '<div class="p2p-sha-banner ' +
        (localMatch ? 'is-match' : 'is-miss') +
        '">' +
        (localMatch
          ? '✓ 本地已存在同 SHA 原书，跳过 ' + prev.bookSizeMb + ' MB 原书下载，仅导入伴读资料'
          : '＋ 本地无此书，将下载 ' + prev.bookSizeMb + ' MB 原书并入库') +
        '</div>' +
        '<div style="display:flex;flex-wrap:wrap;gap:5px">' +
        '<span class="cap-pill">导图 · ' +
        prev.mapCount +
        '</span>' +
        '<span class="cap-pill">批注 · ' +
        prev.noteCount +
        '</span>' +
        '<span class="cap-pill">问答 · ' +
        prev.qaCount +
        '</span></div>' +
        '<div class="wb-sub">好友的批注与问答将以「好友分享」来源无损合并，不覆盖你的个人笔记</div>' +
        '<button type="button" class="btn btn-primary btn-sm" id="btn-p2p-confirm-import">确认接收导入</button>' +
        '</div>'
      : '';

    body.innerHTML =
      '<div class="p2p-scan-frame">' +
      '<span class="p2p-scan-corner tl" aria-hidden="true"></span>' +
      '<span class="p2p-scan-corner tr" aria-hidden="true"></span>' +
      '<span class="p2p-scan-corner bl" aria-hidden="true"></span>' +
      '<span class="p2p-scan-corner br" aria-hidden="true"></span>' +
      '<strong style="font-size:12.5px">局域网相机扫码取景框</strong>' +
      '<span style="font-size:11px;opacity:0.78">对准对方手机二维码，或在下方粘贴分享链接</span>' +
      '</div>' +
      '<div class="byok-input-row">' +
      '<input type="text" class="mono" id="p2p-scan-link-inp" placeholder="http://192.168.1.24:9721/share/br_..." value="' +
      RD.esc(p2pScanState.linkText) +
      '">' +
      '<button type="button" class="btn btn-surface btn-sm" id="btn-p2p-parse-link">解析连接</button>' +
      '</div>' +
      '<div class="p2p-sim-row">' +
      '<span class="byok-prov-group-lbl">一键模拟扫入剧本</span>' +
      '<button type="button" class="btn btn-surface btn-sm" id="btn-p2p-sim-wuye" style="justify-content:flex-start;white-space:normal;text-align:left;height:auto;padding:7px 10px">模拟扫入：《雾夜档案》资料包（同 SHA 命中 → 仅收资料）</button>' +
      '<button type="button" class="btn btn-surface btn-sm" id="btn-p2p-sim-hawking" style="justify-content:flex-start;white-space:normal;text-align:left;height:auto;padding:7px 10px">模拟扫入：《时间简史》整书 + 导图（新 SHA → 整书入库）</button>' +
      '</div>' +
      previewHtml;

    const presets = DATA.p2pIncomingPresets || {};
    const simWuye = body.querySelector('#btn-p2p-sim-wuye');
    if (simWuye) {
      simWuye.addEventListener('click', () => {
        p2pScanState.preview = presets.wuye_match;
        renderP2pScanModal();
      });
    }
    const simHawking = body.querySelector('#btn-p2p-sim-hawking');
    if (simHawking) {
      simHawking.addEventListener('click', () => {
        p2pScanState.preview = presets.hawking_new;
        renderP2pScanModal();
      });
    }
    const parseBtn = body.querySelector('#btn-p2p-parse-link');
    const linkInp = body.querySelector('#p2p-scan-link-inp');
    if (parseBtn && linkInp) {
      parseBtn.addEventListener('click', () => {
        const val = linkInp.value.trim();
        p2pScanState.linkText = val;
        p2pScanState.preview = /5e9c|hawking|time/i.test(val) ? presets.hawking_new : presets.wuye_match;
        renderP2pScanModal();
      });
    }

    const confirmBtn = body.querySelector('#btn-p2p-confirm-import');
    if (confirmBtn && prev) {
      confirmBtn.addEventListener('click', () => {
        const matched = state.books.find((b) => b.sha256 === prev.sha256);
        if (matched) {
          if (prev.sharedNote && !state.customNotes.some((n) => n.id === prev.sharedNote.id)) {
            state.customNotes.push(Object.assign({}, prev.sharedNote));
          }
          if (prev.sharedQa && !state.qaTurns.some((q) => q.question === prev.sharedQa.question)) {
            state.qaTurns.push(Object.assign({}, prev.sharedQa));
          }
          state.p2pLastLog = '最近传输：同 SHA 跳过原书，已合并《' + prev.title + '》资料包 (' + prev.bundleKb + ' KB)';
          closeModal('dlg-p2p-scan');
          renderApp();
          RD.toast('已跳过原书，直接合并《' + prev.title + '》导图/批注/问答（' + prev.bundleKb + ' KB）');
        } else {
          const totalMb = (prev.bookSizeMb + prev.bundleKb / 1024).toFixed(1);
          state.books.unshift({
            id: prev.bookId + '-' + Date.now(),
            title: prev.title,
            author: prev.author,
            readType: prev.readType || 'science',
            subKind: '',
            format: prev.format || '.bcbook',
            cloth: prev.cloth || '#1D334A',
            coverKind: 'cloth',
            sha256: prev.sha256,
            bookSizeMb: prev.bookSizeMb,
            mapCount: prev.mapCount,
            mapKb: prev.bundleKb,
            highlightCount: 0,
            noteCount: prev.noteCount,
            noteKb: 16,
            qaCount: prev.qaCount,
            qaKb: 18,
            curChapter: prev.curChapter || 1,
            totalChapters: prev.totalChapters || 12,
            progressPct: 8,
            watermark: prev.curChapter || 1,
            cachedCount: prev.mapCount || 3,
            kicker: prev.kicker || 'SCI-07 · 好友传入',
            currentLabel: '第 1 章 · 刚刚通过双机直连导入',
            filterGroup: prev.readType || 'science',
            reading: true,
            isPdf: false
          });
          state.p2pLastLog = '最近传输：已接收新书《' + prev.title + '》及伴读导图 (' + totalMb + ' MB)';
          closeModal('dlg-p2p-scan');
          renderApp();
          RD.toast('已接收新书《' + prev.title + '》及伴读导图（' + totalMb + ' MB）');
        }
      });
    }
  }

  function openP2pScanModal() {
    p2pScanState.preview = null;
    renderP2pScanModal();
    openModal('dlg-p2p-scan');
  }

  function renderSettings(root) {
    root.innerHTML =
      '<header class="mobile-topbar">' +
      '<div class="brand-row"><span class="brand-seal" aria-hidden="true">伴</span><span class="brand-name">设置中心</span></div>' +
      '<button type="button" class="icon-btn" id="btn-add-byok">+ 添加模型</button>' +
      '</header>' +
      '<div class="app-scroll">' +
      '<section class="settings-section">' +
      '<div class="settings-section-head"><div><h2>BYOK 模型与加密密钥库</h2><span class="wb-sub">右滑卡片可移除</span></div><span class="cap-pill">Tink + Android Keystore</span></div>' +
      state.models
        .map((m) => {
          const isVision = Array.isArray(m.capabilities) && m.capabilities.includes('Vision');
          const thinkLabel = thinkingBadgeText(m.thinkingLevel || 'medium');
          const pills = [thinkLabel].concat(m.capabilities || []);
          return (
            '<div class="swipe-card-shell" data-swipe-shell="' +
            RD.esc(m.id) +
            '">' +
            '<div class="swipe-del-rail" aria-hidden="false">' +
            '<button type="button" class="swipe-del-btn" data-swipe-remove="' +
            RD.esc(m.id) +
            '">移除</button>' +
            '</div>' +
            '<div class="settings-card swipe-card-fg" data-model-card="' +
            RD.esc(m.id) +
            '">' +
            '<div class="model-head-row">' +
            '<div class="model-title-wrap"><span class="prov-badge">' +
            RD.esc(m.provider) +
            '</span><strong>' +
            RD.esc(m.name) +
            '</strong> <span class="mono muted" style="font-size:11px">' +
            RD.esc(m.maskedKey) +
            '</span></div>' +
            '<div class="model-caps">' +
            pills
              .map(
                (c) =>
                  '<span class="cap-pill' +
                  (c === 'Vision' ? ' vlm' : c.startsWith('思考:') ? ' think' : '') +
                  '">' +
                  RD.esc(c) +
                  '</span>'
              )
              .join('') +
            '</div></div>' +
            '<div class="wb-sub">' +
            '<span class="mono">' +
            RD.esc(formatHostPath(m.baseUrl || '')) +
            '</span> · <code class="mono">' +
            RD.esc(m.modelId) +
            '</code> · <span style="color:var(--pine)">' +
            RD.esc(m.latency) +
            '</span></div>' +
            '<div style="display:flex;align-items:center;justify-content:space-between;gap:6px;flex-wrap:wrap">' +
            '<div style="display:flex;gap:8px;font-size:11.5px">' +
            '<label><input type="radio" name="def-comp" data-def-comp="' +
            RD.esc(m.id) +
            '"' +
            (m.isDefaultCompanion ? ' checked' : '') +
            '> 默认伴读</label>' +
            (isVision
              ? '<label><input type="radio" name="def-vlm" data-def-vlm="' +
                RD.esc(m.id) +
                '"' +
                (m.isDefaultVision ? ' checked' : '') +
                '> 默认视觉 VLM</label>'
              : '') +
            '</div>' +
            '<div style="display:flex;gap:6px">' +
            '<button type="button" class="wb-btn" data-edit-model="' +
            RD.esc(m.id) +
            '">编辑</button>' +
            '<button type="button" class="wb-btn" data-test-model="' +
            RD.esc(m.id) +
            '">测试连通性</button>' +
            '</div></div></div></div>'
          );
        })
        .join('') +
      '</section>' +
      '<section class="settings-section">' +
      '<div class="settings-section-head"><h2>阅读器、字体与墨水屏偏好</h2><button type="button" class="wb-btn" id="btn-set-add-font">+ 导入 .ttf/.otf</button></div>' +
      '<div class="settings-card">' +
      '<div style="display:flex;flex-wrap:wrap;gap:6px">' +
      state.customFonts.map((f) => '<span class="fmt-badge">' + RD.esc(f) + '</span>').join('') +
      '</div>' +
      '<div class="toggle-row"><span>音量键翻页</span><button type="button" class="wb-btn" id="btn-set-vol" aria-pressed="' +
      (state.volumeKeyTurn ? 'true' : 'false') +
      '">' +
      (state.volumeKeyTurn ? '已开启' : '已关闭') +
      '</button></div>' +
      '<div class="toggle-row"><span>墨水屏零动画（高对比瞬切）</span><button type="button" class="wb-btn" id="btn-set-eink" aria-pressed="' +
      (state.theme === 'eink' ? 'true' : 'false') +
      '">' +
      (state.theme === 'eink' ? '已启用' : '未启用') +
      '</button></div>' +
      '<div class="toggle-row"><span>默认开启防剧透水位锁</span><button type="button" class="wb-btn" id="btn-set-spoiler" aria-pressed="' +
      (state.defaultSpoilerLock ? 'true' : 'false') +
      '">' +
      (state.defaultSpoilerLock ? '已开启' : '已关闭') +
      '</button></div>' +
      '</div></section>' +
      '<section class="settings-section" id="sec-p2p-share">' +
      '<div class="settings-section-head"><h2>手机互联 · 传书与资料分享</h2><span class="cap-pill">同 SHA 免传原书</span></div>' +
      '<div class="settings-card">' +
      '<div class="p2p-entry-grid">' +
      '<button type="button" class="p2p-entry-btn" id="btn-p2p-scan">' +
      '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 7V5a2 2 0 0 1 2-2h2"/><path d="M17 3h2a2 2 0 0 1 2 2v2"/><path d="M21 17v2a2 2 0 0 1-2 2h-2"/><path d="M7 21H5a2 2 0 0 1-2-2v-2"/><line x1="7" y1="12" x2="17" y2="12"/></svg>' +
      '<span>扫码连接</span></button>' +
      '<button type="button" class="p2p-entry-btn" id="btn-p2p-qr">' +
      '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><path d="M14 14h3v3h-3z"/><path d="M18 18h3v3h-3z"/></svg>' +
      '<span>生成二维码</span></button>' +
      '<button type="button" class="p2p-entry-btn" id="btn-p2p-link">' +
      '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>' +
      '<span>生成链接</span></button>' +
      '</div>' +
      '<div class="p2p-status-strip">' +
      '<span>本机书库：' +
      state.books.length +
      ' 本书已算 SHA-256 指纹</span>' +
      '<span class="muted" id="p2p-last-log">' +
      RD.esc(state.p2pLastLog) +
      '</span></div></div></section>' +
      '<section class="settings-section">' +
      '<div class="settings-section-head"><h2>桌面网关与云端同步</h2><span class="fmt-badge">Slice 6 联调预览</span></div>' +
      '<div class="settings-card">' +
      '<div class="model-head-row"><strong>桌面伴读网关</strong><span class="cap-pill" style="' +
      (state.gatewayOnline ? '' : 'background:var(--amber-soft);color:var(--amber)') +
      '">' +
      (state.gatewayOnline ? '在线 · 192.168.1.18:8765' : '离线 · 未连接') +
      '</span></div>' +
      '<div class="wb-sub">配对后支持从桌面拉取 `.bcbook` 预生成包、调用桌面跨书全文检索、以及提交异步 MinerU OCR 任务。</div>' +
      '<div style="display:flex;gap:8px;flex-wrap:wrap">' +
      '<button type="button" class="btn btn-primary btn-sm" id="btn-open-qr">扫码配对 (--mobile-bind)</button>' +
      '<button type="button" class="btn btn-surface btn-sm" id="btn-pull-bcbook">从桌面拉取 .bcbook</button>' +
      '</div></div>' +
      '<div class="settings-card">' +
      '<div class="model-head-row"><strong>WebDAV / R2 分片 Outbox 队列</strong><span class="fmt-badge">Slice 6 联调启用</span></div>' +
      '<div class="wb-sub">待同步增量：0 条批注 · 0 条进度锚点 · 端到端加密已就绪</div>' +
      '</div></section></div>' +
      renderBottomNav('settings');

    root.querySelector('#btn-add-byok').addEventListener('click', () => openByokModal(null));
    root.querySelector('#btn-p2p-scan').addEventListener('click', openP2pScanModal);
    root.querySelector('#btn-p2p-qr').addEventListener('click', () => openP2pSendModal('qr'));
    root.querySelector('#btn-p2p-link').addEventListener('click', () => openP2pSendModal('link'));

    root.querySelectorAll('[data-swipe-remove]').forEach((btn) => {
      btn.addEventListener('click', () => removeByokModel(btn.dataset.swipeRemove));
    });

    root.querySelectorAll('.swipe-card-shell').forEach((shell) => {
      const id = shell.dataset.swipeShell;
      const fg = shell.querySelector('.swipe-card-fg');
      if (!fg || !id) return;
      let active = false;
      let startX = 0;
      let startY = 0;
      let baseOffset = 0;
      let intent = null;
      let curX = 0;

      fg.addEventListener('pointerdown', (e) => {
        if (e.target.closest('button, input, label, select, a')) return;
        active = true;
        intent = null;
        startX = e.clientX;
        startY = e.clientY;
        baseOffset = fg.dataset.snapped === '1' ? 84 : 0;
        curX = baseOffset;
      });
      fg.addEventListener('pointermove', (e) => {
        if (!active) return;
        const dx = e.clientX - startX;
        const dy = e.clientY - startY;
        if (!intent) {
          if (Math.abs(dy) > 10 && Math.abs(dy) >= Math.abs(dx)) {
            intent = 'v';
            active = false;
            return;
          }
          const wantsH = (dx > 8 || (baseOffset > 0 && Math.abs(dx) > 8)) && Math.abs(dx) > Math.abs(dy) * 1.2;
          if (!wantsH) return;
          intent = 'h';
          fg.classList.add('is-swiping');
          if (fg.setPointerCapture && e.pointerId != null) {
            try { fg.setPointerCapture(e.pointerId); } catch (err) {}
          }
        }
        if (intent === 'h') {
          curX = Math.max(0, Math.min(156, baseOffset + dx));
          fg.style.transform = 'translateX(' + curX + 'px)';
        }
      });
      const finishSwipe = () => {
        if (!active && intent !== 'h') return;
        active = false;
        if (intent !== 'h') {
          if (fg.dataset.snapped === '1') {
            fg.style.transform = '';
            delete fg.dataset.snapped;
          }
          return;
        }
        intent = null;
        fg.classList.remove('is-swiping');
        if (curX >= 118) {
          removeByokModel(id);
        } else if (curX >= 42) {
          fg.style.transform = 'translateX(84px)';
          fg.dataset.snapped = '1';
        } else {
          fg.style.transform = '';
          delete fg.dataset.snapped;
        }
      };
      fg.addEventListener('pointerup', finishSwipe);
      fg.addEventListener('pointercancel', finishSwipe);
    });

    root.querySelectorAll('[data-edit-model]').forEach((b) => {
      b.addEventListener('click', () => openByokModal(b.dataset.editModel));
    });
    root.querySelectorAll('[data-def-comp]').forEach((r) => {
      r.addEventListener('change', () => {
        state.models.forEach((m) => { m.isDefaultCompanion = m.id === r.dataset.defComp; });
        RD.toast('已切换默认伴读模型');
        renderApp();
      });
    });
    root.querySelectorAll('[data-def-vlm]').forEach((r) => {
      r.addEventListener('change', () => {
        state.models.forEach((m) => { m.isDefaultVision = m.id === r.dataset.defVlm; });
        RD.toast('已切换默认视觉 VLM 模型');
        renderApp();
      });
    });
    root.querySelectorAll('[data-test-model]').forEach((b) => {
      b.addEventListener('click', () => {
        const m = state.models.find((x) => x.id === b.dataset.testModel);
        if (!m) return;
        const ms = 120 + Math.floor(Math.random() * 140);
        m.latency = ms + 'ms · 连通正常';
        RD.toast(m.name + ' 延迟测试：' + m.latency);
        renderApp();
      });
    });
    root.querySelector('#btn-set-add-font').addEventListener('click', () => {
      state.customFonts.push('方正仿宋 · FZFS.otf');
      RD.toast('已模拟导入本地字体：方正仿宋 · FZFS.otf');
      renderApp();
    });
    root.querySelector('#btn-set-vol').addEventListener('click', () => { state.volumeKeyTurn = !state.volumeKeyTurn; renderApp(); });
    root.querySelector('#btn-set-eink').addEventListener('click', () => {
      applyTheme(state.theme === 'eink' ? 'warm' : 'eink');
      renderApp();
    });
    root.querySelector('#btn-set-spoiler').addEventListener('click', () => { state.defaultSpoilerLock = !state.defaultSpoilerLock; renderApp(); });
    root.querySelector('#btn-open-qr').addEventListener('click', () => openModal('dlg-qr-bind'));
    root.querySelector('#btn-pull-bcbook').addEventListener('click', () => {
      if (!state.gatewayOnline) {
        RD.toast('桌面网关当前离线，请先扫码配对或切换网关在线');
      } else {
        RD.toast('已从桌面拉取《雾夜档案》增量图谱包 (.bcbook · 412 KB)');
      }
    });
    bindBottomNav(root);
  }

  function renderBottomNav(active) {
    return (
      '<nav class="bottom-nav" aria-label="主导航">' +
      '<button type="button" class="bottom-nav-btn" data-bnav="bookshelf" aria-current="' +
      (active === 'bookshelf' ? 'page' : 'false') +
      '"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg><span>书架</span></button>' +
      '<button type="button" class="bottom-nav-btn" data-bnav="settings" aria-current="' +
      (active === 'settings' ? 'page' : 'false') +
      '"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9c.26.6.79 1 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg><span>设置</span></button>' +
      '</nav>'
    );
  }

  function bindBottomNav(root) {
    root.querySelectorAll('[data-bnav]').forEach((b) => {
      b.addEventListener('click', () => { state.page = b.dataset.bnav; renderApp(); });
    });
    root.querySelectorAll('[data-nav]').forEach((b) => {
      b.addEventListener('click', () => { state.page = b.dataset.nav; renderApp(); });
    });
  }

  function renderApp() {
    syncStageControls();
    const vp = document.getElementById('app-viewport');
    if (!vp) return;
    if (state.page === 'bookshelf') renderBookshelf(vp);
    else if (state.page === 'detail') renderBookDetail(vp);
    else if (state.page === 'reader') renderReader(vp);
    else if (state.page === 'pdf') renderPdf(vp);
    else if (state.page === 'settings') renderSettings(vp);
  }

  /* 演示台顶部控制条事件 */
  function bindStageBar() {
    document.querySelectorAll('#stage-page-nav [data-page]').forEach((b) => {
      b.addEventListener('click', () => {
        state.page = b.dataset.page;
        if (state.page === 'reader' && state.viewport === 'foldable') state.companionOpen = true;
        renderApp();
      });
    });
    document.querySelectorAll('#stage-viewport-nav [data-vp]').forEach((b) => {
      b.addEventListener('click', () => applyViewport(b.dataset.vp));
    });
    document.querySelectorAll('#stage-theme-nav [data-theme-pick]').forEach((b) => {
      b.addEventListener('click', () => { applyTheme(b.dataset.themePick); renderApp(); });
    });
    document.getElementById('trig-selection').addEventListener('click', () => {
      state.page = 'reader';
      state.hudVisible = false;
      state.companionOpen = false;
      state.selectedBlockId = 'B02';
      state.selectionContextQuote = '铜钥匙背面的缺口不是磨损，而是故意锉出的三道齿痕';
      state.selectionOpen = true;
      renderApp();
    });
    document.getElementById('trig-footnote').addEventListener('click', () => {
      state.page = 'reader';
      state.selectedBookId = 'wuye';
      state.activeChapterNum = 3;
      state.hudVisible = false;
      if (state.viewport !== 'foldable') {
        state.companionOpen = false;
      }
      state.selectionOpen = false;
      state.activeFootnoteId = 'fn-45';
      renderApp();
    });
    document.getElementById('trig-jump-ahead').addEventListener('click', () => {
      state.selectedBookId = 'wuye';
      state.page = 'detail';
      renderApp();
      openJumpAheadDialog(10);
    });
    document.getElementById('trig-companion-half').addEventListener('click', () => {
      state.page = 'reader';
      state.hudVisible = false;
      state.selectionOpen = false;
      state.activeFootnoteId = null;
      state.companionOpen = true;
      state.companionSnap = 'half';
      renderApp();
    });
    document.getElementById('trig-gateway').addEventListener('click', () => {
      state.gatewayOnline = !state.gatewayOnline;
      syncStageControls();
      RD.toast(state.gatewayOnline ? '桌面网关已模拟上线' : '桌面网关已模拟离线');
      renderApp();
    });
  }

  /* 供无头测试与调试使用 */
  window.__DEMO_APP__ = {
    state,
    applyTheme,
    applyViewport,
    switchProfile,
    renderApp,
    openModal,
    closeModal
  };

  document.addEventListener('DOMContentLoaded', () => {
    setupDialogs();
    bindStageBar();
    window.addEventListener('resize', positionFnPopover);
    window.addEventListener('scroll', positionFnPopover, { passive: true, capture: true });
    applyTheme(state.theme);
    renderApp();
  });
})();
