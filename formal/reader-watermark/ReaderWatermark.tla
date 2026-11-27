--------------------------- MODULE ReaderWatermark ---------------------------
(*
 * Formal TLA+ model of BanRead Android's DefaultReaderEngine anti-spoiler
 * watermark, pending jump, remote watermark sync, and multi-work session
 * lifecycle.
 *
 * Source of truth:
 *   module-reader/src/main/kotlin/app/banread/reader/internal/DefaultReaderEngine.kt
 *   Sync commit: 3bac8183774ae41bc453f5f8a1fb7dd51bfbcf13
 *
 * Every action below maps directly to a concrete mutex-protected block or
 * unlocked step in DefaultReaderEngine.kt.
 *)
EXTENDS Integers, FiniteSets

CONSTANTS
    Works,       \* e.g., {"W1", "W2"}
    Openers,     \* e.g., {"T1", "T2"} - concurrent callers of open()
    MaxChapter   \* e.g., 3 (covers <= w, w+1, and > w+1 multi-chapter jumps)

NONE == "NONE"
Chapters == 1..MaxChapter
OptChapters == 0..MaxChapter
OptWorks == Works \cup {NONE}

Max2(a, b) == IF a >= b THEN a ELSE b
Max3(a, b, c) == Max2(Max2(a, b), c)

VARIABLES
    dbCursor,                \* [Works -> OptChapters]
    dbWatermark,             \* [Works -> OptChapters]
    activeWork,              \* OptWorks
    activeCursor,            \* OptChapters
    activeWatermark,         \* OptChapters
    pendingJump,             \* OptChapters (0 = null)
    collectorWork,           \* OptWorks (captured workId in locationCollectorJob)
    rendererWork,            \* OptWorks (book currently loaded in rendererPort)
    openPc,                  \* [Openers -> {"IDLE", "AFTER_MUTEX", "AFTER_COLLECTOR"}]
    openTarget,              \* [Openers -> OptWorks]
    maxEverWatermark,        \* [Works -> OptChapters]
    crossWorkCorrupted,      \* BOOLEAN
    silentMultiJumpOccurred  \* BOOLEAN

vars == <<
    dbCursor, dbWatermark,
    activeWork, activeCursor, activeWatermark, pendingJump,
    collectorWork, rendererWork,
    openPc, openTarget,
    maxEverWatermark, crossWorkCorrupted, silentMultiJumpOccurred
>>

TypeOK ==
    /\ dbCursor \in [Works -> OptChapters]
    /\ dbWatermark \in [Works -> OptChapters]
    /\ activeWork \in OptWorks
    /\ activeCursor \in OptChapters
    /\ activeWatermark \in OptChapters
    /\ pendingJump \in OptChapters
    /\ collectorWork \in OptWorks
    /\ rendererWork \in OptWorks
    /\ openPc \in [Openers -> {"IDLE", "AFTER_MUTEX", "AFTER_COLLECTOR"}]
    /\ openTarget \in [Openers -> OptWorks]
    /\ maxEverWatermark \in [Works -> OptChapters]
    /\ crossWorkCorrupted \in BOOLEAN
    /\ silentMultiJumpOccurred \in BOOLEAN

Init ==
    /\ dbCursor = [w \in Works |-> 0]
    /\ dbWatermark = [w \in Works |-> 0]
    /\ activeWork = NONE
    /\ activeCursor = 0
    /\ activeWatermark = 0
    /\ pendingJump = 0
    /\ collectorWork = NONE
    /\ rendererWork = NONE
    /\ openPc = [t \in Openers |-> "IDLE"]
    /\ openTarget = [t \in Openers |-> NONE]
    /\ maxEverWatermark = [w \in Works |-> 0]
    /\ crossWorkCorrupted = FALSE
    /\ silentMultiJumpOccurred = FALSE

OpenAcquireMutex(t, w) ==
    /\ openPc[t] = "IDLE"
    /\ LET hasSaved == dbWatermark[w] > 0
           resolvedCursor == IF hasSaved THEN dbCursor[w] ELSE 1
           resolvedWm == IF hasSaved THEN Max2(1, dbWatermark[w]) ELSE 1
       IN
       /\ dbCursor' = IF hasSaved THEN dbCursor ELSE [dbCursor EXCEPT ![w] = resolvedCursor]
       /\ dbWatermark' = IF hasSaved THEN dbWatermark ELSE [dbWatermark EXCEPT ![w] = resolvedWm]
       /\ activeWork' = w
       /\ activeCursor' = resolvedCursor
       /\ activeWatermark' = resolvedWm
       /\ pendingJump' = 0
       /\ openPc' = [openPc EXCEPT ![t] = "AFTER_MUTEX"]
       /\ openTarget' = [openTarget EXCEPT ![t] = w]
       /\ maxEverWatermark' = [maxEverWatermark EXCEPT ![w] = Max2(@, resolvedWm)]
       /\ UNCHANGED <<collectorWork, rendererWork, crossWorkCorrupted, silentMultiJumpOccurred>>

OpenStartCollector(t) ==
    /\ openPc[t] = "AFTER_MUTEX"
    /\ collectorWork' = openTarget[t]
    /\ openPc' = [openPc EXCEPT ![t] = "AFTER_COLLECTOR"]
    /\ UNCHANGED <<dbCursor, dbWatermark, activeWork, activeCursor, activeWatermark,
                   pendingJump, rendererWork, openTarget, maxEverWatermark,
                   crossWorkCorrupted, silentMultiJumpOccurred>>

OpenLoadRenderer(t) ==
    /\ openPc[t] = "AFTER_COLLECTOR"
    /\ rendererWork' = openTarget[t]
    /\ openPc' = [openPc EXCEPT ![t] = "IDLE"]
    /\ openTarget' = [openTarget EXCEPT ![t] = NONE]
    /\ UNCHANGED <<dbCursor, dbWatermark, activeWork, activeCursor, activeWatermark,
                   pendingJump, collectorWork, maxEverWatermark,
                   crossWorkCorrupted, silentMultiJumpOccurred>>

ApplyCursorObservation(w, ch, fromCollector) ==
    LET currentWm ==
            IF dbWatermark[w] > 0 THEN dbWatermark[w]
            ELSE IF activeWork = w /\ activeWatermark > 0 THEN activeWatermark
            ELSE 1
        nextWm ==
            IF ch <= currentWm THEN currentWm
            ELSE IF ch = currentWm + 1 THEN ch
            ELSE currentWm
        nextPending ==
            IF ch <= currentWm THEN 0
            ELSE IF ch = currentWm + 1 THEN 0
            ELSE ch
        isCrossWork == fromCollector /\ (activeWork # w \/ (rendererWork # NONE /\ rendererWork # w))
    IN
    /\ dbCursor' = [dbCursor EXCEPT ![w] = ch]
    /\ dbWatermark' = [dbWatermark EXCEPT ![w] = nextWm]
    /\ IF activeWork = w THEN
           /\ activeCursor' = ch
           /\ activeWatermark' = nextWm
           /\ pendingJump' = nextPending
       ELSE
           UNCHANGED <<activeCursor, activeWatermark, pendingJump>>
    /\ maxEverWatermark' = [maxEverWatermark EXCEPT ![w] = Max2(@, nextWm)]
    /\ crossWorkCorrupted' = (crossWorkCorrupted \/ isCrossWork)
    /\ silentMultiJumpOccurred' = (silentMultiJumpOccurred \/ (ch > currentWm + 1 /\ nextWm > currentWm))
    /\ UNCHANGED <<activeWork, collectorWork, rendererWork, openPc, openTarget>>

LocationEventObserved(ch) ==
    /\ collectorWork # NONE
    /\ rendererWork # NONE
    /\ ApplyCursorObservation(collectorWork, ch, TRUE)

GoTo(ch) ==
    /\ activeWork # NONE
    /\ ApplyCursorObservation(activeWork, ch, FALSE)

ConfirmWatermarkAdvance(w, target) ==
    /\ LET currentWm == IF dbWatermark[w] > 0 THEN dbWatermark[w] ELSE 1
           promoted == Max2(currentWm, target)
       IN
       /\ dbWatermark' = [dbWatermark EXCEPT ![w] = promoted]
       /\ dbCursor' = IF dbCursor[w] > 0 THEN dbCursor ELSE [dbCursor EXCEPT ![w] = promoted]
       /\ IF activeWork = w THEN
              /\ activeWatermark' = promoted
              /\ pendingJump' = IF pendingJump > promoted THEN pendingJump ELSE 0
          ELSE
              UNCHANGED <<activeWatermark, pendingJump>>
       /\ maxEverWatermark' = [maxEverWatermark EXCEPT ![w] = Max2(@, promoted)]
       /\ UNCHANGED <<activeWork, activeCursor, collectorWork, rendererWork,
                      openPc, openTarget, crossWorkCorrupted, silentMultiJumpOccurred>>

RaiseWatermarkFromRemote(w, remoteMax) ==
    /\ LET localOrd == IF dbWatermark[w] > 0 THEN dbWatermark[w] ELSE 1
           raised == Max2(localOrd, remoteMax)
       IN
       /\ dbWatermark' = [dbWatermark EXCEPT ![w] = raised]
       /\ dbCursor' = IF dbCursor[w] > 0 THEN dbCursor ELSE [dbCursor EXCEPT ![w] = raised]
       /\ IF activeWork = w THEN
              /\ activeWatermark' = raised
              /\ pendingJump' = IF pendingJump > raised THEN pendingJump ELSE 0
          ELSE
              UNCHANGED <<activeWatermark, pendingJump>>
       /\ maxEverWatermark' = [maxEverWatermark EXCEPT ![w] = Max2(@, raised)]
       /\ UNCHANGED <<activeWork, activeCursor, collectorWork, rendererWork,
                      openPc, openTarget, crossWorkCorrupted, silentMultiJumpOccurred>>

SwitchPdfSurface(w) ==
    /\ dbWatermark[w] > 0
    /\ LET currentOrd ==
               IF activeWork = w /\ activeCursor > 0 THEN activeCursor
               ELSE IF dbCursor[w] > 0 THEN dbCursor[w]
               ELSE 1
           wm == Max3(1, dbWatermark[w], IF activeWork = w THEN activeWatermark ELSE 1)
       IN
       /\ dbCursor' = [dbCursor EXCEPT ![w] = currentOrd]
       /\ dbWatermark' = [dbWatermark EXCEPT ![w] = wm]
       /\ IF activeWork = w THEN
              /\ activeCursor' = currentOrd
              /\ activeWatermark' = wm
          ELSE
              UNCHANGED <<activeCursor, activeWatermark>>
       /\ maxEverWatermark' = [maxEverWatermark EXCEPT ![w] = Max2(@, wm)]
       /\ UNCHANGED <<activeWork, pendingJump, collectorWork, rendererWork,
                      openPc, openTarget, crossWorkCorrupted, silentMultiJumpOccurred>>

Next ==
    \/ \E t \in Openers, w \in Works : OpenAcquireMutex(t, w)
    \/ \E t \in Openers : OpenStartCollector(t)
    \/ \E t \in Openers : OpenLoadRenderer(t)
    \/ \E ch \in Chapters : LocationEventObserved(ch)
    \/ \E ch \in Chapters : GoTo(ch)
    \/ \E w \in Works, target \in Chapters : ConfirmWatermarkAdvance(w, target)
    \/ \E w \in Works, remoteMax \in Chapters : RaiseWatermarkFromRemote(w, remoteMax)
    \/ \E w \in Works : SwitchPdfSurface(w)

Spec == Init /\ [][Next]_vars

WatermarkMonotonic ==
    /\ \A w \in Works : dbWatermark[w] >= maxEverWatermark[w]
    /\ (activeWork # NONE) => (activeWatermark >= maxEverWatermark[activeWork])

NoSilentMultiChapterJump ==
    ~silentMultiJumpOccurred

PendingJumpAboveWatermark ==
    (pendingJump # 0) => (activeWork # NONE /\ pendingJump > activeWatermark)

NoCrossWorkProgressCorruption ==
    ~crossWorkCorrupted

ActiveSessionMatchesRendererAndCollector ==
    (\A t \in Openers : openPc[t] = "IDLE") /\ (activeWork # NONE) =>
        /\ collectorWork = activeWork
        /\ rendererWork = activeWork
        /\ activeWatermark = dbWatermark[activeWork]
        /\ activeCursor = dbCursor[activeWork]

=============================================================================
