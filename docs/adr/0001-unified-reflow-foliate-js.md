# ADR-0001: Unified Reflowable Rendering via `foliate-js` (`es2020`)

- **Status**: accepted

## Context

The application must render original EPUB/MOBI/AZW3 books, desktop pre-cleaned `.bcbook` packs (Markdown), and reflowable text+image streams extracted from text-layer PDFs. Maintaining three separate rendering stacks (WebView, Compose Markdown, and `LazyColumn`) would force triple implementation and behavioral drift across custom font injection, Chinese punctuation squeeze, 5-color SVG highlights, in-place footnote popups, and `[data-jump]` navigation. In addition, open E-Ink Android 10–13 devices (such as Hanwang N10 Pro) often ship older system WebViews (Chromium 83–95).

## Decision

Standardize all reflowable reading on a single `foliate-js` (MIT) renderer, transpiled at build time via `esbuild` targeting `es2020` for legacy WebView compatibility. Both `.bcbook` Markdown chapters and L1 text+image streams extracted from text PDFs are deterministically compiled on import/preparation into standard XHTML packages carrying `data-block-id="Bxx"` and EPUB3 `noteref` markers and fed into the same engine. Reject Compose-native reflow lists, multiple parallel reflow renderers, and page-curl animations (which cause E-Ink ghosting and depend on API 33+).

## Consequences

All reflowable formats share 100% of typography, font loading, highlighting, and anchor-jumping logic. The trade-off is maintaining a lightweight deterministic MD/L1-to-XHTML compiler and a `WebViewAssetLoader` JS bridge.
