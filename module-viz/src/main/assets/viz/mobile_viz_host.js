/* 伴读 Android :module-viz 离线图解宿主运行时（零外部依赖，兼容 WebMessageListener 与 JavascriptInterface） */
(function () {
  'use strict';

  var BLOCK_ID_RE = /^B\d+$/;
  var MIN_WIDTH_PX = 40;
  var WIDTH_DELTA_THRESHOLD_PX = 12;

  var RD = (window.RD = window.RD || {});

  RD.esc = function (value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  };

  var toastTimer = null;
  RD.toast = function (message) {
    var toastEl = document.getElementById('viz-toast');
    if (!toastEl) return;
    toastEl.textContent = String(message == null ? '' : message);
    toastEl.hidden = false;
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      toastEl.hidden = true;
    }, 2200);
  };

  var THEME_PRESETS = {
    PAPER_LIGHT: { themeKey: 'paper', colorScheme: 'light' },
    SEPIA_WARM: { themeKey: 'warm', colorScheme: 'light' },
    SLATE_NIGHT: { themeKey: 'slate', colorScheme: 'dark' },
    AMOLED_BLACK: { themeKey: 'amoled', colorScheme: 'dark' },
    EINK_HIGH_CONTRAST: { themeKey: 'eink', colorScheme: 'light' },
    paper: { themeKey: 'paper', colorScheme: 'light' },
    warm: { themeKey: 'warm', colorScheme: 'light' },
    slate: { themeKey: 'slate', colorScheme: 'dark' },
    amoled: { themeKey: 'amoled', colorScheme: 'dark' },
    eink: { themeKey: 'eink', colorScheme: 'light' },
  };

  var shellEl = document.getElementById('viz-shell');
  var stageViewportEl = document.getElementById('viz-stage-viewport');
  var panLayerEl = document.getElementById('viz-pan-layer');
  var mountEl = document.getElementById('viz-mount');
  var badgeEl = document.getElementById('viz-blueprint-badge');
  var btnOutEl = document.getElementById('viz-btn-out');
  var btnResetEl = document.getElementById('viz-btn-reset');
  var btnInEl = document.getElementById('viz-btn-in');
  var btnFullscreenEl = document.getElementById('viz-btn-fullscreen');
  var inspectorEl = document.getElementById('viz-inspector');
  var inspectorRoleEl = document.getElementById('viz-inspector-role');
  var inspectorSrcEl = document.getElementById('viz-inspector-src');
  var inspectorTitleEl = document.getElementById('viz-inspector-title');
  var inspectorDetailEl = document.getElementById('viz-inspector-detail');
  var inspectorBlocksEl = document.getElementById('viz-inspector-blocks');
  var inspectorCloseEl = document.getElementById('viz-inspector-close');
  var errorCardEl = document.getElementById('viz-error-card');
  var errorMsgEl = document.getElementById('viz-error-msg');
  var errorFallbackEl = document.getElementById('viz-error-fallback');
  var zoomModalEl = document.getElementById('zoom-modal');
  var zoomCloseBtnEl = document.getElementById('btn-zoom-close');

  var state = {
    currentArtifact: null,
    currentTheme: null,
    viewportMode: 'compact',
    scale: 1,
    panX: 0,
    panY: 0,
    pendingRender: null,
    lastRenderedWidth: 0,
    rafId: 0,
    hostReadyEmitted: false,
  };

  function emitToHost(payload) {
    try {
      if (typeof window.__vizHostOnMessage === 'function') {
        window.__vizHostOnMessage(payload);
      }
      if (!Array.isArray(window.__vizHostMessages)) {
        window.__vizHostMessages = [];
      }
      window.__vizHostMessages.push(payload);
      if (window.__vizHostMessages.length > 200) {
        window.__vizHostMessages.shift();
      }
      var json = JSON.stringify(payload);
      if (window.BanReadVizBridge && typeof window.BanReadVizBridge.postMessage === 'function') {
        window.BanReadVizBridge.postMessage(json);
      } else if (window.BanReadVizLegacy && typeof window.BanReadVizLegacy.postMessage === 'function') {
        window.BanReadVizLegacy.postMessage(json);
      }
    } catch (_) {
      // Never crash host page if bridge is unavailable
    }
  }

  function emitJump(rawBlockId) {
    var clean = String(rawBlockId == null ? '' : rawBlockId).trim();
    if (!BLOCK_ID_RE.test(clean)) return false;
    emitToHost({ type: 'jump', blockId: clean });
    return true;
  }

  function emitFullscreen() {
    emitToHost({ type: 'fullscreen' });
  }

  function emitError(message) {
    emitToHost({ type: 'error', message: String(message || 'Unknown visualization error') });
  }

  function parseJsonIfString(value) {
    if (typeof value !== 'string') return value;
    var trimmed = value.trim();
    if (!trimmed) return null;
    return JSON.parse(trimmed);
  }

  function applyTheme(themeInput) {
    if (!themeInput) return;
    try {
      var parsed = typeof themeInput === 'string' && !themeInput.trim().startsWith('{')
        ? THEME_PRESETS[themeInput.trim()] || { themeKey: themeInput.trim() }
        : parseJsonIfString(themeInput);

      if (!parsed || typeof parsed !== 'object') return;
      state.currentTheme = parsed;

      var preset = THEME_PRESETS[parsed.theme] || THEME_PRESETS[parsed.themeKey] || null;
      var themeKey = parsed.themeKey || (preset && preset.themeKey) || 'paper';
      var colorScheme = parsed.colorScheme || (preset && preset.colorScheme) || 'light';

      var root = document.documentElement;
      root.setAttribute('data-theme', themeKey);
      root.style.colorScheme = colorScheme === 'dark' ? 'dark' : 'light';

      var vars = parsed.cssVariables || parsed.variables || null;
      if (vars && typeof vars === 'object') {
        Object.keys(vars).forEach(function (k) {
          if (k && k.indexOf('--') === 0 && typeof vars[k] === 'string') {
            root.style.setProperty(k, vars[k]);
          }
        });
      }
    } catch (err) {
      emitError('Theme application failed: ' + (err && err.message ? err.message : String(err)));
    }
  }

  function clampScale(next) {
    return Math.max(0.5, Math.min(2.5, Number(Number(next).toFixed(2))));
  }

  function applyTransform() {
    if (!panLayerEl) return;
    if (Math.abs(state.scale - 1) < 0.01 && state.panX === 0 && state.panY === 0) {
      panLayerEl.style.transform = '';
    } else {
      panLayerEl.style.transform =
        'translate3d(' + state.panX.toFixed(1) + 'px, ' + state.panY.toFixed(1) + 'px, 0) scale(' + state.scale + ')';
    }
    if (btnResetEl) {
      btnResetEl.textContent = Math.round(state.scale * 100) + '%';
    }
  }

  function setZoom(nextScale) {
    state.scale = clampScale(nextScale);
    if (state.scale <= 1) {
      state.panX = 0;
      state.panY = 0;
    }
    applyTransform();
  }

  function hideError() {
    if (errorCardEl) errorCardEl.hidden = true;
    if (errorMsgEl) errorMsgEl.textContent = '';
    if (errorFallbackEl) errorFallbackEl.innerHTML = '';
  }

  function showError(message, artifact) {
    if (errorMsgEl) errorMsgEl.textContent = String(message || '图解渲染出现异常');
    if (errorFallbackEl) {
      var fallbackHtml = '';
      if (artifact && typeof artifact === 'object') {
        if (artifact.caption) {
          fallbackHtml += '<div class="ext-sub"><strong>' + RD.esc(artifact.caption) + '</strong></div>';
        }
        if (artifact.summary) {
          fallbackHtml += '<div class="ext-sub">' + RD.esc(artifact.summary) + '</div>';
        }
      }
      errorFallbackEl.innerHTML = fallbackHtml;
    }
    if (errorCardEl) errorCardEl.hidden = false;
  }

  function hideInspector() {
    if (inspectorEl) inspectorEl.hidden = true;
  }

  function showInspector(info) {
    if (!inspectorEl || !info) return;
    if (inspectorRoleEl) {
      inspectorRoleEl.textContent = info.role || '节点详情';
    }
    if (inspectorSrcEl) {
      if (info.source === '原文') {
        inspectorSrcEl.innerHTML = '<span class="src-tag tag-src">[原文]</span>';
      } else if (info.source) {
        inspectorSrcEl.innerHTML = '<span class="src-tag tag-ext">[' + RD.esc(info.source) + ']</span>';
      } else {
        inspectorSrcEl.innerHTML = '';
      }
    }
    if (inspectorTitleEl) {
      inspectorTitleEl.textContent = info.label || '';
    }
    if (inspectorDetailEl) {
      inspectorDetailEl.textContent = info.detail || '';
      inspectorDetailEl.hidden = !info.detail;
    }
    if (inspectorBlocksEl) {
      var blocks = Array.isArray(info.blocks) ? info.blocks.filter(function (b) { return BLOCK_ID_RE.test(String(b || '').trim()); }) : [];
      inspectorBlocksEl.innerHTML = blocks
        .map(function (b) {
          var clean = String(b).trim();
          return (
            '<button type="button" class="blk-anchor" data-jump="' +
            RD.esc(clean) +
            '">↗ ' +
            RD.esc(clean) +
            '</button>'
          );
        })
        .join('');
      inspectorBlocksEl.hidden = blocks.length === 0;
    }
    inspectorEl.hidden = false;
  }

  function extractInlineMarkdown(rawText) {
    var escaped = RD.esc(rawText);
    // Replace `[B14]` or [B14] with clickable jump pills
    escaped = escaped.replace(/`?\[(B\d+)\]`?/g, function (_, bid) {
      return (
        '<button type="button" class="blk-anchor" data-jump="' +
        bid +
        '">↗ ' +
        bid +
        '</button>'
      );
    });
    escaped = escaped.replace(/`([^`]+)`/g, '<code>$1</code>');
    escaped = escaped.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    escaped = escaped.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    return escaped;
  }

  function renderSafeMarkdown(markdownText) {
    var lines = String(markdownText == null ? '' : markdownText).replace(/\r\n/g, '\n').split('\n');
    var html = [];
    var inCodeBlock = false;
    var codeLines = [];
    var listType = null;

    function closeList() {
      if (listType) {
        html.push('</' + listType + '>');
        listType = null;
      }
    }

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var trimmed = line.trim();

      if (trimmed.indexOf('```') === 0) {
        closeList();
        if (inCodeBlock) {
          html.push('<pre><code>' + RD.esc(codeLines.join('\n')) + '</code></pre>');
          codeLines = [];
          inCodeBlock = false;
        } else {
          inCodeBlock = true;
        }
        continue;
      }
      if (inCodeBlock) {
        codeLines.push(line);
        continue;
      }
      if (!trimmed) {
        closeList();
        continue;
      }
      if (/^---+$/.test(trimmed)) {
        closeList();
        html.push('<hr>');
        continue;
      }
      var headingMatch = trimmed.match(/^(#{1,4})\s+(.+)$/);
      if (headingMatch) {
        closeList();
        var level = headingMatch[1].length;
        html.push('<h' + level + '>' + extractInlineMarkdown(headingMatch[2]) + '</h' + level + '>');
        continue;
      }
      var ulMatch = trimmed.match(/^[-*]\s+(.+)$/);
      if (ulMatch) {
        if (listType !== 'ul') {
          closeList();
          listType = 'ul';
          html.push('<ul>');
        }
        html.push('<li>' + extractInlineMarkdown(ulMatch[1]) + '</li>');
        continue;
      }
      var olMatch = trimmed.match(/^\d+\.\s+(.+)$/);
      if (olMatch) {
        if (listType !== 'ol') {
          closeList();
          listType = 'ol';
          html.push('<ol>');
        }
        html.push('<li>' + extractInlineMarkdown(olMatch[1]) + '</li>');
        continue;
      }
      if (trimmed.indexOf('> ') === 0) {
        closeList();
        html.push('<blockquote>' + extractInlineMarkdown(trimmed.slice(2)) + '</blockquote>');
        continue;
      }
      closeList();
      html.push('<p>' + extractInlineMarkdown(trimmed) + '</p>');
    }

    if (inCodeBlock) {
      html.push('<pre><code>' + RD.esc(codeLines.join('\n')) + '</code></pre>');
    }
    closeList();
    return html.join('');
  }

  function renderMarkdownCard(containerEl, artifact) {
    var caption = RD.esc(artifact.caption || '伴读精读卡');
    var noteHtml = artifact.reading_note
      ? '<div class="bp-note">' + RD.esc(artifact.reading_note) + '</div>'
      : '';
    var summaryHtml = artifact.summary
      ? '<div class="bp-summary-foot">' + extractInlineMarkdown(artifact.summary) + '</div>'
      : '';
    containerEl.innerHTML =
      '<div class="bp-card">' +
      '<div class="bp-card-head"><h4 class="bp-title">' +
      caption +
      '</h4></div>' +
      noteHtml +
      '<div class="bp-stage-inner"><div class="md-card-body">' +
      renderSafeMarkdown(artifact.markdown || '') +
      '</div></div>' +
      summaryHtml +
      '</div>';
  }

  function findArtifactNodeInfo(nodeId, ptIdx) {
    var art = state.currentArtifact;
    if (!art || !art.data) return null;
    if (nodeId && Array.isArray(art.data.nodes)) {
      for (var i = 0; i < art.data.nodes.length; i++) {
        var n = art.data.nodes[i];
        if (n && String(n.id) === String(nodeId)) {
          return {
            role: n.role || '节点',
            label: n.label || nodeId,
            detail: n.detail || '',
            source: n.source || '',
            blocks: Array.isArray(n.blocks) ? n.blocks : [],
          };
        }
      }
    }
    if (ptIdx !== null && ptIdx !== undefined && Array.isArray(art.data.points)) {
      var pt = art.data.points[Number(ptIdx)];
      if (pt) {
        return {
          role: pt.beat_kind || '节拍',
          label: (pt.beat || '') + (pt.tension !== undefined ? ' (' + pt.tension + ')' : ''),
          detail: [pt.emotion, pt.note].filter(Boolean).join(' · '),
          source: pt.source || '',
          blocks: Array.isArray(pt.blocks) ? pt.blocks : [],
        };
      }
    }
    return null;
  }

  function getContainerMeasure() {
    var width = Math.floor((mountEl && mountEl.clientWidth) || (shellEl && shellEl.clientWidth) || 0);
    var height = Math.floor(
      (shellEl && shellEl.clientHeight) ||
        document.documentElement.clientHeight ||
        window.innerHeight ||
        0
    );
    return { width: width, height: height };
  }

  function handleFocusBlock(blockId) {
    emitJump(blockId);
  }

  function renderNow(artifact, width, height) {
    hideError();
    hideInspector();

    var bp = String((artifact && artifact.blueprint) || 'diagram');
    if (badgeEl) badgeEl.textContent = bp;

    try {
      if (bp === 'markdown_read_card' || typeof artifact.markdown === 'string') {
        if (zoomModalEl) {
          zoomModalEl.classList.remove('open');
          zoomModalEl.hidden = true;
        }
        renderMarkdownCard(mountEl, artifact);
      } else if (state.viewportMode === 'fullscreen' && RD.diagrams && typeof RD.diagrams.openZoomModal === 'function') {
        if (zoomModalEl) zoomModalEl.hidden = false;
        RD.diagrams.renderBlueprintCard(mountEl, artifact, { onFocusBlock: handleFocusBlock });
        RD.diagrams.openZoomModal(artifact, handleFocusBlock);
      } else {
        if (zoomModalEl) {
          zoomModalEl.classList.remove('open');
          zoomModalEl.hidden = true;
        }
        if (!RD.diagrams || typeof RD.diagrams.renderBlueprintCard !== 'function') {
          throw new Error('diagram_renderer.js failed to register RD.diagrams.renderBlueprintCard');
        }
        RD.diagrams.renderBlueprintCard(mountEl, artifact, { onFocusBlock: handleFocusBlock });
      }

      state.lastRenderedWidth = width;
      var renderedRect = mountEl.getBoundingClientRect();
      var outHeight = Math.max(height, Math.ceil(renderedRect.height || 0));
      emitToHost({
        type: 'render_ready',
        blueprint: bp,
        width: width,
        height: outHeight,
      });
    } catch (err) {
      var msg = err && err.message ? err.message : String(err);
      mountEl.innerHTML = '';
      showError(msg, artifact);
      emitError(msg);
    }
  }

  function renderArtifact(artifactInput, themeInput) {
    if (themeInput) {
      applyTheme(themeInput);
    }
    var artifact;
    try {
      artifact = parseJsonIfString(artifactInput);
      if (!artifact || typeof artifact !== 'object') {
        throw new Error('Artifact payload must be a JSON object');
      }
    } catch (err) {
      var msg = 'Invalid artifact payload: ' + (err && err.message ? err.message : String(err));
      showError(msg, null);
      emitError(msg);
      return;
    }

    state.currentArtifact = artifact;
    var dims = getContainerMeasure();
    if (dims.width < MIN_WIDTH_PX || dims.height === 0) {
      state.pendingRender = { artifact: artifact, theme: state.currentTheme };
      return;
    }

    state.pendingRender = null;
    renderNow(artifact, dims.width, dims.height);
  }

  function setViewportMode(mode) {
    var nextMode = mode === 'fullscreen' ? 'fullscreen' : 'compact';
    if (state.viewportMode === nextMode) return;
    state.viewportMode = nextMode;
    if (shellEl) {
      shellEl.setAttribute('data-mode', nextMode);
    }
    if (btnFullscreenEl) {
      btnFullscreenEl.textContent = nextMode === 'fullscreen' ? '退出' : '全屏';
    }
    if (nextMode === 'compact' && zoomModalEl) {
      zoomModalEl.classList.remove('open');
      zoomModalEl.hidden = true;
    }
    if (state.currentArtifact) {
      var dims = getContainerMeasure();
      if (dims.width >= MIN_WIDTH_PX && dims.height > 0) {
        renderNow(state.currentArtifact, dims.width, dims.height);
      } else {
        state.pendingRender = { artifact: state.currentArtifact, theme: state.currentTheme };
      }
    }
  }

  function dispatch(envelopeInput) {
    try {
      var env = parseJsonIfString(envelopeInput);
      if (!env || typeof env !== 'object') return;
      if (env.theme) applyTheme(env.theme);
      if (env.viewportMode) {
        state.viewportMode = env.viewportMode === 'fullscreen' ? 'fullscreen' : 'compact';
        if (shellEl) shellEl.setAttribute('data-mode', state.viewportMode);
      }
      if (env.artifact) {
        renderArtifact(env.artifact, null);
      }
    } catch (err) {
      emitError('Bridge dispatch failed: ' + (err && err.message ? err.message : String(err)));
    }
  }

  function scheduleResizeFlush() {
    if (state.rafId) return;
    state.rafId = window.requestAnimationFrame(function () {
      state.rafId = 0;
      var dims = getContainerMeasure();
      if (dims.width < MIN_WIDTH_PX || dims.height === 0) {
        return;
      }
      if (state.pendingRender) {
        var pending = state.pendingRender;
        state.pendingRender = null;
        if (pending.theme) applyTheme(pending.theme);
        renderNow(pending.artifact, dims.width, dims.height);
        return;
      }
      if (state.currentArtifact && Math.abs(dims.width - state.lastRenderedWidth) >= WIDTH_DELTA_THRESHOLD_PX) {
        renderNow(state.currentArtifact, dims.width, dims.height);
      }
    });
  }

  function setupResizeObserver() {
    if (typeof ResizeObserver === 'function') {
      var observer = new ResizeObserver(function () {
        scheduleResizeFlush();
      });
      if (shellEl) observer.observe(shellEl);
      if (mountEl) observer.observe(mountEl);
    }
    window.addEventListener('resize', scheduleResizeFlush);
  }

  function setupClickDelegation() {
    // Capture-phase interception for zoom triggers in compact mode so diagram_renderer's modal does not pop inside BottomSheet
    if (shellEl) {
      shellEl.addEventListener(
        'click',
        function (e) {
          var zoomBtn = e.target && e.target.closest ? e.target.closest('[data-bp-zoom], [data-bp-zoom-inline], [data-open-zoom]') : null;
          if (zoomBtn && state.viewportMode === 'compact') {
            e.preventDefault();
            e.stopPropagation();
            emitFullscreen();
          }
        },
        true
      );

      // Capture-phase listener for interactive node inspection (runs even when diagram_renderer stops propagation)
      shellEl.addEventListener(
        'click',
        function (e) {
          var target = e.target;
          if (!target || !target.closest) return;
          if (target.closest('.blk-anchor') || target.closest('[data-expand-anchors]') || target.hasAttribute('data-toggle-detail')) {
            return;
          }
          var nodeEl = target.closest('.dag-node[data-node-id], .spatial-node[data-node-id], .chart-pt-grp');
          if (!nodeEl) return;
          var nodeId = nodeEl.getAttribute('data-node-id');
          var ptIdx = nodeEl.getAttribute('data-pt-idx');
          var info = findArtifactNodeInfo(nodeId, ptIdx);
          if (!info && nodeEl.classList.contains('chart-pt-grp')) {
            var jumpAttr = nodeEl.getAttribute('data-jump') || '';
            info = {
              role: nodeEl.getAttribute('data-pt-kind') || '节拍',
              label: (nodeEl.getAttribute('data-pt-beat') || '') + ' (' + (nodeEl.getAttribute('data-pt-val') || '') + ')',
              detail: [nodeEl.getAttribute('data-pt-emotion'), nodeEl.getAttribute('data-pt-note')].filter(Boolean).join(' · '),
              source: '',
              blocks: BLOCK_ID_RE.test(jumpAttr) ? [jumpAttr] : [],
            };
          }
          if (!info) {
            var lblEl = nodeEl.querySelector('.dag-node-label, .spatial-node-label');
            var detEl = nodeEl.querySelector('.dag-node-detail, .spatial-node-detail');
            var fallbackJump = nodeEl.getAttribute('data-jump') || '';
            info = {
              role: '节点',
              label: lblEl ? lblEl.textContent.trim() : nodeId || '',
              detail: detEl ? detEl.textContent.trim() : '',
              source: '',
              blocks: BLOCK_ID_RE.test(fallbackJump) ? [fallbackJump] : [],
            };
          }
          showInspector(info);
        },
        true
      );

      shellEl.addEventListener('click', function (e) {
        var target = e.target;
        if (!target || !target.closest) return;

        // Explicit [data-block-id] or [data-jump] outside diagram_renderer's internal bindings (e.g. inspector, markdown)
        var jumpEl = target.closest('[data-block-id], [data-jump]');
        if (jumpEl) {
          if (target.closest('[data-expand-anchors]') || target.hasAttribute('data-toggle-detail')) {
            return;
          }
          if (jumpEl.closest('#viz-inspector') || jumpEl.closest('.md-card-body') || jumpEl.hasAttribute('data-block-id')) {
            e.stopPropagation();
            var bid = jumpEl.getAttribute('data-block-id') || jumpEl.getAttribute('data-jump');
            emitJump(bid);
            return;
          }
        }
      });
    }

    if (inspectorCloseEl) {
      inspectorCloseEl.addEventListener('click', hideInspector);
    }
    if (btnOutEl) {
      btnOutEl.addEventListener('click', function () {
        setZoom(state.scale - 0.15);
      });
    }
    if (btnInEl) {
      btnInEl.addEventListener('click', function () {
        setZoom(state.scale + 0.15);
      });
    }
    if (btnResetEl) {
      btnResetEl.addEventListener('click', function () {
        state.scale = 1;
        state.panX = 0;
        state.panY = 0;
        applyTransform();
      });
    }
    if (btnFullscreenEl) {
      btnFullscreenEl.addEventListener('click', function () {
        emitFullscreen();
      });
    }
    if (zoomCloseBtnEl) {
      zoomCloseBtnEl.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        emitFullscreen();
      });
    }
  }

  function setupTouchPinchPan() {
    if (!stageViewportEl) return;
    var pinchStartDist = 0;
    var pinchStartScale = 1;
    var panStartX = 0;
    var panStartY = 0;
    var panOriginX = 0;
    var panOriginY = 0;
    var isPanning = false;

    function touchDistance(touches) {
      var dx = touches[0].clientX - touches[1].clientX;
      var dy = touches[0].clientY - touches[1].clientY;
      return Math.hypot(dx, dy);
    }

    stageViewportEl.addEventListener(
      'touchstart',
      function (e) {
        if (e.touches.length === 2) {
          pinchStartDist = touchDistance(e.touches);
          pinchStartScale = state.scale;
          isPanning = false;
        } else if (e.touches.length === 1 && state.scale > 1.02) {
          isPanning = true;
          panStartX = e.touches[0].clientX;
          panStartY = e.touches[0].clientY;
          panOriginX = state.panX;
          panOriginY = state.panY;
        }
      },
      { passive: true }
    );

    stageViewportEl.addEventListener(
      'touchmove',
      function (e) {
        if (e.touches.length === 2 && pinchStartDist > 0) {
          var dist = touchDistance(e.touches);
          setZoom(pinchStartScale * (dist / pinchStartDist));
        } else if (e.touches.length === 1 && isPanning && state.scale > 1.02) {
          var dx = e.touches[0].clientX - panStartX;
          var dy = e.touches[0].clientY - panStartY;
          state.panX = Math.max(-240, Math.min(240, panOriginX + dx));
          state.panY = Math.max(-240, Math.min(240, panOriginY + dy));
          applyTransform();
        }
      },
      { passive: true }
    );

    stageViewportEl.addEventListener(
      'touchend',
      function (e) {
        if (e.touches.length < 2) pinchStartDist = 0;
        if (e.touches.length === 0) isPanning = false;
      },
      { passive: true }
    );
  }

  function setupWebMessageReceiver() {
    if (window.BanReadVizBridge && typeof window.BanReadVizBridge === 'object') {
      try {
        window.BanReadVizBridge.onmessage = function (event) {
          dispatch(event && event.data ? event.data : event);
        };
      } catch (_) {}
    }
  }

  window.BanReadVizHost = {
    renderArtifact: renderArtifact,
    applyTheme: applyTheme,
    setViewportMode: setViewportMode,
    setZoom: setZoom,
    dispatch: dispatch,
    getState: function () {
      return {
        viewportMode: state.viewportMode,
        scale: state.scale,
        hasPendingRender: Boolean(state.pendingRender),
        lastRenderedWidth: state.lastRenderedWidth,
        hostReadyEmitted: state.hostReadyEmitted,
        currentBlueprint: state.currentArtifact ? state.currentArtifact.blueprint : null,
      };
    },
  };

  function boot() {
    setupResizeObserver();
    setupClickDelegation();
    setupTouchPinchPan();
    setupWebMessageReceiver();
    if (!state.hostReadyEmitted) {
      state.hostReadyEmitted = true;
      emitToHost({ type: 'host_ready' });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
