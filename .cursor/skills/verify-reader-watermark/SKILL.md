---
name: verify-reader-watermark
description: Formally verify BanRead Android's DefaultReaderEngine anti-spoiler watermark, pending jump, remote watermark sync, and multi-work session lifecycle with TLA+ (TLC). Use when changing DefaultReaderEngine.kt, ReaderDatabase.kt, open(), applyCursorObservation(), goTo(), confirmWatermarkAdvance(), raiseWatermarkFromRemote(), switchPdfSurface(), or locationEvents collection.
---

# Verify Reader Anti-Spoiler Watermark & Session Lifecycle

## What this verifies

- **Property 1 (`WatermarkMonotonic`)**: `spoilerWatermark` never decreases in Room (`reading_progress`) or `_activeState`, across backward flips, surface switches, re-opens, or remote watermark syncs.
- **Harm if broken**: Readers get spoilers in AI companion responses because `SpoilerGate.UpTo(spoilerWatermark)` silently unlocks unread chapters or loses reading progress.
- **Property 2 (`NoSilentMultiChapterJump`)**: A local cursor move more than 1 chapter ahead (`newOrd > currentWatermark + 1`) never raises `spoilerWatermark` automatically; it holds the watermark and flags `pendingWatermarkJump`.
- **Harm if broken**: Accidental TOC taps or footnote jumps silently unlock later chapters for the AI companion.
- **Property 3 (`PendingJumpAboveWatermark`)**: Whenever `pendingWatermarkJump` is non-null, it is strictly greater than `activeWatermark`.
- **Harm if broken**: Stale jump-confirmation banners linger after the watermark has already caught up via sequential reading or remote sync.
- **Property 4 (`NoCrossWorkProgressCorruption`)**: `locationEvents` collected for a previous or inactive book never write to another book's Room progress row.
- **Harm if broken**: Switching books silently overwrites another book's cursor or advances its `spoilerWatermark`.
- **Property 5 (`ActiveSessionMatchesRendererAndCollector`)**: Once all in-flight `open()` calls finish, `_activeState.workId`, `locationCollectorJob`, and `rendererPort` all refer to the same book, and `_activeState` matches Room.
- **Harm if broken**: The screen renders Book A while UI state and `goTo()` think Book B is open, routing page turns into the wrong book's progress.

## Coverage Record

- **Sync commit**: `3bac8183774ae41bc453f5f8a1fb7dd51bfbcf13`
- **Covered files & functions**:
  - `module-reader/src/main/kotlin/app/banread/reader/internal/DefaultReaderEngine.kt`
    - `open(workId, modePreference)` (lines 77–165)
    - `switchPdfSurface(workId, targetMode)` (lines 167–235)
    - `goTo(anchor, highlightPulseMs)` (lines 237–245)
    - `confirmWatermarkAdvance(workId, targetOrdinal)` (lines 247–280)
    - `raiseWatermarkFromRemote(workId, remoteMaxOrdinal)` (lines 327–368)
    - `startCollectingLocationEvents(workId)` (lines 370–377)
    - `applyCursorObservation(workId, anchor)` (lines 379–434)
- **Model files**:
  - `formal/reader-watermark/ReaderWatermark.tla`
  - `formal/reader-watermark/ReaderWatermark.cfg` (all 5 invariants)
  - `formal/reader-watermark/ReaderWatermarkCore.cfg` (Properties 1–3 only)
  - `formal/reader-watermark/run-tlc.sh` (pins `tla2tools.jar` `v1.8.0`)

Before trusting a pass, check whether the covered files changed after the sync commit:

```bash
git log --oneline 3bac8183774ae41bc453f5f8a1fb7dd51bfbcf13..HEAD -- \
  module-reader/src/main/kotlin/app/banread/reader/internal/DefaultReaderEngine.kt
```

If that command prints commits, inspect the diff, confirm whether `formal/reader-watermark/ReaderWatermark.tla` still matches the code's locking boundaries and state transitions, update the model if needed, rerun TLC, and **only then** move the sync commit above forward to the verified commit.

## How to run

Requires Java 17+ (`java -version`). The runner downloads and caches `tla2tools.jar` `v1.8.0` automatically (or uses `$TLA2TOOLS_JAR` if set).

```bash
# Full check (all 5 invariants, including multi-work open/collector races)
./formal/reader-watermark/run-tlc.sh ReaderWatermark.cfg

# Core watermark check (Properties 1–3 only; ~2s exhaustive check over 22,357 distinct states)
./formal/reader-watermark/run-tlc.sh ReaderWatermarkCore.cfg
```

Read every TLC result with the pstack `/statechart` skill: when TLC reports an invariant violation, `/statechart` turns the shortest counterexample into plain steps; when TLC passes, `/statechart` draws the state transition diagram of the checked model.

## Known baseline findings at sync commit `3bac8183`

At `3bac8183774ae41bc453f5f8a1fb7dd51bfbcf13`, `ReaderWatermarkCore.cfg` passes (0 errors across 22,357 distinct states), while `ReaderWatermark.cfg` reports two real concurrency defects in `DefaultReaderEngine.kt`:

1. **`NoCrossWorkProgressCorruption` (6-state counterexample)**: `open()` releases `mutex` at line 150 before cancelling/restarting `locationCollectorJob` at line 152, and `applyCursorObservation()` writes `dao.upsertProgress(workId = workId.raw, ...)` at line 415 without checking `current.workId == workId`.
2. **`ActiveSessionMatchesRendererAndCollector` (7-state counterexample)**: Because `startCollectingLocationEvents()` (line 152) and `rendererPort.load()` (line 153) run outside `mutex`, two overlapping `open(W1)` and `open(W2)` calls can finish with `_activeState.workId == W2` while `rendererPort` displays `W1`.

## Telling a real bug from a modeling error

When TLC fails after a code change:

1. Map every step in the TLC counterexample trace (`OpenAcquireMutex`, `OpenStartCollector`, `OpenLoadRenderer`, `LocationEventObserved`, `GoTo`, `ConfirmWatermarkAdvance`, `RaiseWatermarkFromRemote`, `SwitchPdfSurface`) directly to its corresponding lines in `DefaultReaderEngine.kt`.
2. Ask: *Can the real Kotlin coroutines and `Mutex` execute this exact interleaving?*
   - **Yes** -> Real bug. Report the counterexample in `DefaultReaderEngine` method/field terms; do not weaken the invariant to make it pass.
   - **No** (for example, `DefaultReaderEngine.open()` now holds a serialized session lock or monotonic `openSeq` generation token across `startCollectingLocationEvents` and `rendererPort.load`, or `applyCursorObservation` rejects stale `workId` / `openSeq`) -> Modeling drift. Update `ReaderWatermark.tla` to reflect the new guard, prove the invariant fails if that guard is removed, rerun TLC, and update the sync commit.

## Bounds, assumptions, and what is NOT covered

- **Bounds**: `Works = {"W1", "W2"}`, `Openers = {"T1", "T2"}`, `MaxChapter = 3` (covers backward `<= w`, sequential `w + 1`, and multi-chapter jump `1 -> 3`).
- **Assumptions**:
  - `mutex.withLock` in `DefaultReaderEngine` provides mutual exclusion across coroutines.
  - `dao.findProgress` and `dao.upsertProgress` are atomic per Room query and have no external writer outside `DefaultReaderEngine` (single-writer ownership).
  - First open of a book with no DB row starts at chapter 1 (abstracting `FRONT_BACK` vs `BODY` offset).
- **Not covered**:
  - `currentSpoilerGate()` unlocked reads racing between `dao.upsertProgress` (line 415) and `_activeState.value` update (line 427).
  - `FoliateWebAdapter` / `reader_bridge.js` JavaScript-side `openSeq` guarding or WebView JS execution errors.
  - `RoomCompanionEngine` / `ContextBuilder` text slicing, `saveAnnotation`, font/CSS styling, or `module-sync` network transport (not yet implemented).
