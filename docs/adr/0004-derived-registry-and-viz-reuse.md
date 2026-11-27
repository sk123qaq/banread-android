# ADR-0004: Derived Tool Registry (`profiles.generated.json`) and Reuse of `diagram_renderer.js`

- **Status**: accepted

## Context

Hand-copying `book_types.py` into desktop frontend `chapter.js` already caused drift (dropping `actor_incentives`). Moreover, `book-corpus`'s `diagram_renderer.js` (~5,943 lines) is a zero-dependency pure JS/SVG/DOM renderer; reimplementing all 19 diagram layouts in ECharts or Mermaid on Android would be expensive and risk losing domain-specific layout rules.

## Decision

Generate `profiles.generated.json` directly from `book_types.py` and `classify_model.py` as the single source of truth for Android (guarded by parity tests). Package `diagram_renderer.js` verbatim as an offline asset inside `:module-viz`, hosted by a `mobile_viz_host.html` adapter shell (`ResizeObserver` against `0x0` collapse, 5-theme CSS variables, tap-to-inspect details, and gesture locking) inside a single pooled offline WebView. Text dossiers and multi-turn chat remain native Jetpack Compose.

## Consequences

Zero duplicate reimplementation of the 19 SVG diagram families, visual parity with desktop, and elimination of multi-client tool definition drift.
