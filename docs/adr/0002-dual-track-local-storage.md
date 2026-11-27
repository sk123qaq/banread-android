# ADR-0002: Dual-Track Local Persistence (Immutable Source Files + Room L1/L2/L3 & Annotations)

- **Status**: accepted

## Context

Imported source e-books (especially styled EPUB/KF8 archives and desktop `.bcbook` Study Packs) should never be mutated repeatedly. Meanwhile, AI companion features depend on clean semantic block segmentation, chapter role isolation, and high-hit-rate caching—and expensive companion artifacts cannot be invalidated aggressively as reading progresses.

## Decision

Decouple immutable source files/compiled packages on disk from parsed indices in Room SQLite. Source archives live read-only in app storage, while Room hosts three cache/index layers: `L1` (canonical blocks, cross-chapter footnotes, `chapter_role`), `L2` (chapter micro-summaries), and `L3` (companion artifacts keyed by `(workId, scopeKey, progressGate, toolId, promptVersion)` with Stale-While-Revalidate and a 200MB LRU budget, strictly exempting user `Annotation` records and pinned notes). Reject injecting caches back into book archives or re-scraping the DOM on every request.

## Consequences

Books open instantaneously, cached companion artifacts reload immediately across sessions, and future local FTS5 indexing can consume the `L1` tables directly without re-splitting books.
