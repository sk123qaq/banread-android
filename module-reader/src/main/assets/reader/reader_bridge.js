(function () {
    'use strict';

    // ES2020 compatibility polyfills for Android 10-13 WebViews (Chromium 83-95)
    if (!Array.prototype.at) {
        Object.defineProperty(Array.prototype, 'at', {
            value: function (n) {
                var i = Math.trunc(n) || 0;
                if (i < 0) i += this.length;
                if (i < 0 || i >= this.length) return undefined;
                return this[i];
            },
            writable: true,
            configurable: true
        });
    }
    if (!String.prototype.at) {
        Object.defineProperty(String.prototype, 'at', {
            value: function (n) {
                var i = Math.trunc(n) || 0;
                if (i < 0) i += this.length;
                if (i < 0 || i >= this.length) return undefined;
                return this.charAt(i);
            },
            writable: true,
            configurable: true
        });
    }
    if (!Array.prototype.findLastIndex) {
        Object.defineProperty(Array.prototype, 'findLastIndex', {
            value: function (predicate, thisArg) {
                for (var i = this.length - 1; i >= 0; i--) {
                    if (predicate.call(thisArg, this[i], i, this)) return i;
                }
                return -1;
            },
            writable: true,
            configurable: true
        });
    }
    if (!Array.prototype.findLast) {
        Object.defineProperty(Array.prototype, 'findLast', {
            value: function (predicate, thisArg) {
                var idx = this.findLastIndex(predicate, thisArg);
                return idx >= 0 ? this[idx] : undefined;
            },
            writable: true,
            configurable: true
        });
    }
    if (!String.prototype.replaceAll) {
        Object.defineProperty(String.prototype, 'replaceAll', {
            value: function (search, replacement) {
                if (search instanceof RegExp) {
                    if (!search.global) {
                        throw new TypeError('replaceAll requires a global RegExp');
                    }
                    return this.replace(search, replacement);
                }
                return this.split(String(search)).join(String(replacement));
            },
            writable: true,
            configurable: true
        });
    }
    if (!Object.hasOwn) {
        Object.hasOwn = function (obj, prop) {
            return Object.prototype.hasOwnProperty.call(obj, prop);
        };
    }

    var COLOR_MAP = {
        YELLOW: '#FDE047',
        GREEN: '#86EFAC',
        BLUE: '#93C5FD',
        PINK: '#F9A8D4',
        PURPLE: '#D8B4FE'
    };

    var state = {
        view: null,
        currentBookUrl: null,
        currentCss: '',
        paginationMode: 'SLIDE',
        annotations: [],
        annotationsByCfi: new Map(),
        activePulseTimer: null,
        selectionTimer: null
    };

    function notifyNative(method, payload) {
        try {
            var bridge = globalThis.BanReadNative;
            if (bridge && typeof bridge[method] === 'function') {
                bridge[method](typeof payload === 'string' ? payload : JSON.stringify(payload));
            }
        } catch (err) {
            console.error('BanReadNative.' + method + ' error:', err);
        }
    }

    function makeAssetDirectoryLoader(baseUrl) {
        var normalizedBase = baseUrl.endsWith('/') ? baseUrl : baseUrl + '/';
        function fetchAsset(name) {
            var clean = String(name || '').replace(/^\/+/, '');
            return fetch(normalizedBase + clean).then(function (res) {
                return res.ok ? res : null;
            }).catch(function () {
                return null;
            });
        }
        return {
            loadText: function (name) {
                return fetchAsset(name).then(function (res) {
                    return res ? res.text() : null;
                });
            },
            loadBlob: function (name) {
                return fetchAsset(name).then(function (res) {
                    return res ? res.blob() : null;
                });
            },
            getSize: function () {
                return 0;
            }
        };
    }

    function resolveCurrentDoc() {
        if (!state.view || !state.view.renderer || !state.view.renderer.getContents) return null;
        var contents = state.view.renderer.getContents();
        return contents && contents.length ? contents[0] : null;
    }

    function findBlockIdForNode(node) {
        var cur = node;
        while (cur) {
            if (cur.nodeType === 1 && cur.getAttribute && cur.getAttribute('data-block-id')) {
                return cur.getAttribute('data-block-id');
            }
            cur = cur.parentNode;
        }
        return null;
    }

    function extractTextQuoteFromRange(range) {
        if (!range) return { prefix: '', exact: '', suffix: '' };
        try {
            var exact = (range.toString() || '').trim().slice(0, 160);
            var container = range.startContainer;
            var blockEl = container;
            while (blockEl && blockEl.nodeType !== 1) blockEl = blockEl.parentNode;
            var fullText = blockEl && blockEl.textContent ? blockEl.textContent : '';
            if (!exact && fullText) {
                exact = fullText.trim().slice(0, 120);
            }
            var idx = exact && fullText ? fullText.indexOf(exact) : -1;
            var prefix = idx > 0 ? fullText.slice(Math.max(0, idx - 32), idx) : '';
            var suffix = idx >= 0 ? fullText.slice(idx + exact.length, idx + exact.length + 32) : '';
            return { prefix: prefix, exact: exact, suffix: suffix };
        } catch (e) {
            return { prefix: '', exact: '', suffix: '' };
        }
    }

    function getOrdinalFromDoc(doc, sectionIndex) {
        if (doc && doc.body) {
            var attr = doc.body.getAttribute('data-chapter-ordinal');
            var parsed = attr ? parseInt(attr, 10) : NaN;
            if (!isNaN(parsed) && parsed >= 1) return parsed;
        }
        return (typeof sectionIndex === 'number' && sectionIndex >= 0) ? (sectionIndex + 1) : 1;
    }

    function isFootnoteElement(anchorEl, href) {
        if (!anchorEl) return false;
        var epubType = anchorEl.getAttributeNS ? anchorEl.getAttributeNS('http://www.idpf.org/2007/ops', 'type') : '';
        if (!epubType) epubType = anchorEl.getAttribute('epub:type') || '';
        var role = anchorEl.getAttribute('role') || '';
        if (epubType.split(/\s+/).indexOf('noteref') >= 0) return true;
        if (role.split(/\s+/).indexOf('doc-noteref') >= 0) return true;
        if (anchorEl.hasAttribute('data-fn-marker')) return true;
        if (href && /#(fn|noteref|note-)/i.test(href)) return true;
        return false;
    }

    function parseDecorationStyle(colorToken) {
        var raw = String(colorToken || 'YELLOW').trim();
        var underlineStyle = null;
        var colorKey = raw;
        if (raw.indexOf(':') > 0) {
            var parts = raw.split(':');
            var stylePrefix = parts[0].toUpperCase();
            colorKey = parts[1] || 'YELLOW';
            if (stylePrefix === 'SOLID' || stylePrefix === 'SOLID_UNDERLINE') underlineStyle = 'SOLID';
            else if (stylePrefix === 'WAVY' || stylePrefix === 'WAVY_UNDERLINE') underlineStyle = 'WAVY';
        }
        var upper = colorKey.toUpperCase();
        var resolvedColor = COLOR_MAP[upper] || (colorKey.charAt(0) === '#' ? colorKey : COLOR_MAP.YELLOW);
        return { color: resolvedColor, underlineStyle: underlineStyle };
    }

    function applyPaginationMode(mode) {
        state.paginationMode = mode || 'SLIDE';
        var renderer = state.view && state.view.renderer;
        if (!renderer) return;
        if (state.paginationMode === 'VERTICAL_SCROLL') {
            renderer.setAttribute('flow', 'scrolled');
            renderer.removeAttribute('animated');
        } else {
            renderer.setAttribute('flow', 'paginated');
            if (state.paginationMode === 'NONE') {
                renderer.removeAttribute('animated');
            } else {
                // SLIDE or COVER (no page-curl)
                renderer.setAttribute('animated', '');
            }
        }
    }

    function attachDocListeners(doc, index) {
        if (!doc) return;
        var scheduleSelectionEmit = function () {
            if (state.selectionTimer) clearTimeout(state.selectionTimer);
            state.selectionTimer = setTimeout(function () {
                var sel = doc.defaultView && doc.defaultView.getSelection ? doc.defaultView.getSelection() : null;
                if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return;
                var range = sel.getRangeAt(0);
                var text = (sel.toString() || '').trim();
                if (!text) return;
                var rect = range.getBoundingClientRect();
                var iframeRect = { left: 0, top: 0 };
                if (doc.defaultView && doc.defaultView.frameElement) {
                    iframeRect = doc.defaultView.frameElement.getBoundingClientRect();
                }
                var ordinal = getOrdinalFromDoc(doc, index);
                var section = state.view && state.view.book && state.view.book.sections
                    ? state.view.book.sections[index] : null;
                var cfi = state.view ? state.view.getCFI(index, range) : '';
                var blockId = findBlockIdForNode(range.startContainer);
                notifyNative('onSelectionChanged', {
                    ordinal: ordinal,
                    href: section ? section.id : ('OEBPS/ch_' + String(ordinal).padStart(2, '0') + '.xhtml'),
                    cfi: cfi,
                    blockId: blockId,
                    selectedText: text,
                    textQuote: extractTextQuoteFromRange(range),
                    rect: {
                        left: rect.left + iframeRect.left,
                        top: rect.top + iframeRect.top,
                        right: rect.right + iframeRect.left,
                        bottom: rect.bottom + iframeRect.top
                    }
                });
            }, 150);
        };
        doc.addEventListener('pointerup', scheduleSelectionEmit);
        doc.addEventListener('contextmenu', scheduleSelectionEmit);
        doc.addEventListener('selectionchange', scheduleSelectionEmit);
    }

    function applyAllAnnotationsToCurrentOverlay(index) {
        if (!state.view) return;
        for (var i = 0; i < state.annotations.length; i++) {
            var item = state.annotations[i];
            if (item && item.value) {
                state.view.addAnnotation(item).catch(function () {});
            }
        }
    }

    function pulseBlockElement(doc, targetEl, durationMs) {
        if (!doc || !targetEl || !durationMs || durationMs <= 0) return;
        if (state.activePulseTimer) {
            clearTimeout(state.activePulseTimer);
            state.activePulseTimer = null;
        }
        var prevOutline = targetEl.style.outline;
        var prevBg = targetEl.style.backgroundColor;
        var prevTransition = targetEl.style.transition;
        targetEl.style.transition = 'background-color 220ms ease';
        targetEl.style.outline = '2px solid #F59E0B';
        targetEl.style.backgroundColor = 'rgba(253, 224, 71, 0.35)';
        state.activePulseTimer = setTimeout(function () {
            targetEl.style.outline = prevOutline;
            targetEl.style.backgroundColor = prevBg;
            targetEl.style.transition = prevTransition;
            state.activePulseTimer = null;
        }, durationMs);
    }

    async function openBook(bookUrl, initialAnchorJson) {
        try {
            var root = document.getElementById('reader-root');
            if (state.view) {
                try { state.view.close(); } catch (_) {}
                state.view.remove();
                state.view = null;
            }
            state.currentBookUrl = bookUrl;

            var view = document.createElement('foliate-view');
            root.appendChild(view);
            state.view = view;

            view.addEventListener('load', function (e) {
                var detail = e.detail || {};
                applyPaginationMode(state.paginationMode);
                if (state.currentCss && view.renderer && view.renderer.setStyles) {
                    view.renderer.setStyles(state.currentCss);
                }
                attachDocListeners(detail.doc, detail.index);
            });

            view.addEventListener('relocate', function (e) {
                var detail = e.detail || {};
                var current = resolveCurrentDoc();
                var doc = current ? current.doc : null;
                var sectionIdx = detail.section ? detail.section.current : (current ? current.index : 0);
                var ordinal = getOrdinalFromDoc(doc, sectionIdx);
                var section = view.book && view.book.sections ? view.book.sections[sectionIdx] : null;
                var href = section ? section.id : ('OEBPS/ch_' + String(ordinal).padStart(2, '0') + '.xhtml');
                var blockId = detail.range ? findBlockIdForNode(detail.range.startContainer) : null;
                var textQuote = extractTextQuoteFromRange(detail.range);
                notifyNative('onRelocated', {
                    ordinal: ordinal,
                    href: href,
                    progression: typeof detail.size === 'number' && detail.size > 0 ? Math.min(1, Math.max(0, detail.fraction || 0)) : (detail.fraction || 0),
                    totalProgression: typeof detail.fraction === 'number' ? detail.fraction : 0,
                    cfi: detail.cfi || '',
                    blockId: blockId,
                    textQuote: textQuote
                });
            });

            view.addEventListener('link', function (e) {
                var detail = e.detail || {};
                if (isFootnoteElement(detail.a, detail.href)) {
                    e.preventDefault();
                    var current = resolveCurrentDoc();
                    var ordinal = getOrdinalFromDoc(current ? current.doc : null, current ? current.index : 0);
                    var marker = detail.a.getAttribute('data-fn-marker') || detail.a.textContent || '';
                    notifyNative('onFootnoteClicked', {
                        ordinal: ordinal,
                        href: detail.href || '',
                        marker: marker.trim()
                    });
                }
            });

            view.addEventListener('create-overlay', function (e) {
                var detail = e.detail || {};
                applyAllAnnotationsToCurrentOverlay(detail.index);
            });

            view.addEventListener('draw-annotation', function (e) {
                var detail = e.detail || {};
                var draw = detail.draw;
                var annotation = detail.annotation || {};
                var parsed = parseDecorationStyle(annotation.colorToken);
                var Overlayer = globalThis.Foliate && globalThis.Foliate.Overlayer;
                if (!Overlayer || typeof draw !== 'function') return;
                if (parsed.underlineStyle === 'SOLID') {
                    draw(Overlayer.underline, { color: parsed.color });
                } else if (parsed.underlineStyle === 'WAVY') {
                    draw(Overlayer.squiggly, { color: parsed.color });
                } else {
                    draw(Overlayer.highlight, { color: parsed.color });
                }
            });

            var bookOrUrl = bookUrl;
            if (typeof bookUrl === 'string' && bookUrl.endsWith('/')) {
                var loader = makeAssetDirectoryLoader(bookUrl);
                bookOrUrl = await new globalThis.Foliate.EPUB(loader).init();
            }

            await view.open(bookOrUrl);
            if (view.book && view.book.transformTarget) {
                view.book.transformTarget.addEventListener('data', function (ev) {
                    ev.detail.data = Promise.resolve(ev.detail.data).catch(function () { return ''; });
                });
            }

            applyPaginationMode(state.paginationMode);
            if (state.currentCss && view.renderer && view.renderer.setStyles) {
                view.renderer.setStyles(state.currentCss);
            }

            if (initialAnchorJson) {
                await goToAnchor(initialAnchorJson, 0);
            } else {
                await view.init({ showTextStart: false });
            }
        } catch (err) {
            notifyNative('onError', String(err && err.message ? err.message : err));
        }
    }

    async function goToAnchor(anchorJson, highlightPulseMs) {
        if (!state.view) return;
        var anchor = typeof anchorJson === 'string' ? JSON.parse(anchorJson) : anchorJson;
        if (!anchor) return;
        var pulseMs = typeof highlightPulseMs === 'number' ? highlightPulseMs : 0;

        try {
            if (anchor.blockId) {
                var targetHref = anchor.href || ('OEBPS/ch_' + String(anchor.ordinal || 1).padStart(2, '0') + '.xhtml');
                var resolved = state.view.resolveNavigation(targetHref);
                if (resolved) {
                    await state.view.renderer.goTo({
                        index: resolved.index,
                        anchor: function (doc) {
                            var el = doc.querySelector('[data-block-id="' + anchor.blockId + '"]')
                                || doc.getElementById(anchor.blockId);
                            if (el && pulseMs > 0) {
                                setTimeout(function () { pulseBlockElement(doc, el, pulseMs); }, 60);
                            }
                            return el || 0;
                        }
                    });
                    return;
                }
            }
            if (anchor.cfi) {
                await state.view.goTo(anchor.cfi);
                return;
            }
            if (anchor.href) {
                await state.view.goTo(anchor.href);
                return;
            }
            if (typeof anchor.ordinal === 'number' && anchor.ordinal >= 1) {
                await state.view.goTo(anchor.ordinal - 1);
            }
        } catch (err) {
            notifyNative('onError', 'goToAnchor failed: ' + String(err && err.message ? err.message : err));
        }
    }

    function updateStyles(cssPayload, paginationMode) {
        state.currentCss = cssPayload || '';
        if (paginationMode) applyPaginationMode(paginationMode);
        if (state.view && state.view.renderer && state.view.renderer.setStyles) {
            state.view.renderer.setStyles(state.currentCss);
        }
    }

    async function renderDecorations(annotationsJson) {
        var list = typeof annotationsJson === 'string' ? JSON.parse(annotationsJson) : (annotationsJson || []);
        if (state.view) {
            for (var i = 0; i < state.annotations.length; i++) {
                try { await state.view.deleteAnnotation(state.annotations[i]); } catch (_) {}
            }
        }
        state.annotations = [];
        state.annotationsByCfi.clear();
        for (var j = 0; j < list.length; j++) {
            var entry = list[j];
            if (!entry || entry.deletedAtEpochMs != null) continue;
            var anchor = entry.anchor || {};
            var value = anchor.cfi || anchor.href || '';
            if (!value) continue;
            var item = {
                value: value,
                noteId: entry.noteId,
                colorToken: entry.colorToken || 'YELLOW',
                comment: entry.comment || ''
            };
            state.annotations.push(item);
            state.annotationsByCfi.set(value, item);
            if (state.view) {
                try { await state.view.addAnnotation(item); } catch (_) {}
            }
        }
    }

    function prevPage() {
        if (state.view) return state.view.prev();
    }

    function nextPage() {
        if (state.view) return state.view.next();
    }

    globalThis.BanReadBridge = {
        openBook: openBook,
        goToAnchor: goToAnchor,
        updateStyles: updateStyles,
        renderDecorations: renderDecorations,
        prevPage: prevPage,
        nextPage: nextPage,
        isReady: function () { return Boolean(globalThis.Foliate); }
    };

    notifyNative('onBridgeReady', { ready: true });
})();
