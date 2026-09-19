# Program System — the Session runtime (PR 8)

Scope: the **use-case layer** that runs one attempt at one opportunity — starting a workout with the
complete immutable presentation it was started under, confirming its sets, going back from it,
cancelling it, and completing it together with the adaptive half of §27's completion transaction.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below as
**§N**). Implementation order: §30 step 8, *Session runtime*. Companions:
`docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1), `docs/PROGRAM_ROOM_SCHEMA.md` (PR 2),
`docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md` (PR 2.1), `docs/PROGRAM_DATA_ACCESS.md` (PR 3),
`docs/PROGRAM_COMPOSITION_ROOT.md` (PR 4), `docs/PROGRAM_LIFECYCLE.md` (PR 5),
`docs/PROGRAM_MANUAL_EDITOR.md` (PR 6), `docs/PROGRAM_SCHEDULER.md` (PR 7).

This change is **the session's own state and nothing else**. It adds no entity, no table, no migration
and no schema object: the six session tables PR 2 created (`workout_session`, `session_snapshot`,
`session_snapshot_exercise`, `session_exercise`, `program_set_log`) already hold everything an attempt
consists of. There is no scheduler, no generator, no Focus Planner, no adaptive policy or engine, no
progress calculation, no import/export, no ViewModel and no screen — §30 steps 9–14 own those.

---

## 1. The graph

```text
AppContainer                                        ← PR 4, unchanged apart from one construction
    ↓
    SessionRuntime                                  ← this PR (§30 step 8)
        ├── ProgramPlanRepository                   ┐ read-only: the revision the opportunity names
        ├── ProgramScheduleRepository               │ the opportunity, and the outcome a completion
        │                                           ┘ records. Nothing else is read from a slot
        ├── WorkoutSessionRepository                ← the session graph: start, read, append, finish
        ├── ProgramAdaptiveRepository               ← the decision a completion persists, when it has one
        ├── clock: Clock                            ← when the workout started, and when a set was
        │                                             confirmed (§26)
        ├── idGenerator: IdGenerator                ← the identity of a session, its occurrences and
        │                                             its sets (§26)
        └── inTransaction                           ← one unit per operation that writes more than one
                                                      thing
    ↓
    presentedWorkout / standingAdjustments          (pure domain, §16) ← the presentation composition
    SessionRuntimeResult / SessionRefusal           (pure domain)      ← §28's classes and the rules
    SessionCompletion / AdaptiveCompletion /
    AdaptiveOutcome                                 (pure domain)      ← what a completion reports
    ↓
ViewModels (not wired by this PR)
```

| file | layer | responsibility |
| --- | --- | --- |
| `domain/usecase/SessionRuntime.kt` | use case | the five operations: `startSession`, `restoreSession`, `confirmSet`, `cancelSession`, `finishSession` |
| `domain/workout/SessionRuntimeResult.kt` | pure domain | §28's `Success / Refused / Failure`, and the typed `SessionRefusal` vocabulary of every rule §19, §16 and §20 make about an attempt |
| `domain/workout/SessionCompletion.kt` | pure domain | what one completion produced: the attempt, the opportunity, and what the adaptive half wrote — plus the `AdaptiveCompletion` seam the caller hands in |
| `domain/adaptive/decision/SlotPresentation.kt` | pure domain | §16's composition: which adjustments stand for one slot, and what it presents once they are applied |
| `data/local/WorkoutSessionDao.kt` | data | the **conditional** insert that carries §19's occupancy rule, and the count that reports its effect |
| `data/repository/WorkoutSessionRepository.kt` | data | starting (with the occupancy rule), reading a session from its own rows, appending a set, writing an outcome, finishing |
| `di/AppContainer.kt` | composition root | one construction added (+32 lines, 0 removed); nothing else |

## 2. The five operations, and where each boundary is

```text
start    —               IN_PROGRESS    one transaction: session + complete snapshot + occurrences
restore  any              any            a read of the session's own rows, and of nothing else
confirm  IN_PROGRESS     IN_PROGRESS    one appended set row; the position follows from the stored rows
back     IN_PROGRESS     IN_PROGRESS    nothing at all — leaving the screen is not a fact about the workout
cancel   IN_PROGRESS     CANCELLED      the session row alone; the opportunity is NOT taken
finish   IN_PROGRESS     COMPLETED      one transaction: session + slot + the adaptive decision
```

* **Start** is §27's `Start Workout → Session + complete Snapshot`. It reads the opportunity, checks the
  identity triple, composes the presentation (§16) and writes the session, its captured presentation and
  its occurrences in one unit. A refused start writes **nothing** — including the refusal the write
  itself decides.
* **Restore** is the operation a recreated screen or a restarted process calls. It is
  `WorkoutSessionRepository.sessionById`, which assembles the session from `workout_session`,
  `session_snapshot`, `session_snapshot_exercise`, `session_exercise` and `program_set_log` — and from
  nothing else.
* **Confirm** is §27's `Confirm Set → SetLog`: one appended row, whose position is
  *the stored sets of this occurrence + 1*, so `1..n` with no gap is a consequence of the write rather
  than a rule a caller has to respect. A set that was not performed is **not** written as zero (§12).
* **Back is not an operation.** There is no member of the runtime that ends an attempt without being
  asked, so the API cannot express one; the test named for the rule measures that a restore writes no row
  of any table and leaves the attempt `IN_PROGRESS` and continuable.
* **Cancel** ends the attempt as `CANCELLED` and writes the **session row alone**: the opportunity keeps
  its status and its stamp, and the sets the attempt confirmed stay recorded as partial exposure (§12).
* **Finish** is §27's completion, below.

## 3. The snapshot is the session's source of truth

A started session's presentation is captured once and never re-derived. The claim has two halves:

```text
EffectiveWorkout       = what should be presented for a Slot (computed, changes as adjustments
                         supersede one another)
WorkoutSessionSnapshot = what WAS presented when this Session started (frozen forever)
```

**What "the presentation of a slot" is composed from.** §16's composition is
`ProgramRevision + AdaptiveAdjustment = EffectiveWorkout`, and the revision in that sum is
**the revision the opportunity was scheduled from** — the one its `revisionId` names, never the
Program's current revision. Three facts make that the only composition that is defined:

* a later revision mints **new** day and element identities (§6, §7 — `ProgramEditorService` and
  `ProgramDraftEditor` build new rows, they do not reuse identities), so a slot's plan day is not present
  in a revision saved after it. There is no stored mapping from an opportunity to a day of a newer
  revision, and inventing one (resolving the day by position) would make a slot's presentation a function
  of the live revision — the thing §19 forbids for a session and §16 makes an adjustment, not a plan
  edit, responsible for;
* an adjustment is expressed against the element it changes — its `before` and `after` are plan elements —
  so it can only be applied to a revision that presents that element;
* and the alternative is unreachable rather than merely undesirable: `session_snapshot` stores elements,
  never a pointer at a day of a newer revision.

The consequence is stated plainly because it is a real one: after a §6 Save, an opportunity that survived
keeps presenting the plan day it was scheduled from until the Scheduler supersedes it — which is §20's
rule that a slot is not re-pointed, not this layer's. **Owner decision 1** records the divergence from
the Scheduler document's prose and its cost.

**What the snapshot captures.** The elements of the plan day in their own order, each with its plan
identity, its exercise and its per-set prescription — with the adjustments that stand for the
opportunity applied **in place**, and their ids captured in the order of the presentation they change.
`SessionRuntime.startSession` is the only place the live plan is read at all, and it reads exactly one
revision by the identity the opportunity names.

**What a later change does.** Nothing. A §6 Save, a rewrite of the plan's own rows, and a *superseding*
adjustment all leave an existing session's presentation exactly as captured: the suite starts a session,
changes the live plan under it in all three ways, and compares the restored value to the snapshot the
start produced, element for element.

**What each half of that claim is measured by:**

| the claim | where it is measured |
| --- | --- |
| the current Program revision is not consulted | `theRestoredPresentationIsDomainEquivalentToTheCapturedSnapshotAfterANewRevision` asserts the Program's `currentRevisionId` **is** the new revision while the session still names its own; `aNewAttemptAfterASavePresentsThePlanDayTheOpportunityNames` starts an attempt *after* the save and shows the presentation is the revision-1 day, not the revised one |
| the Slot's stored `revisionId` is authoritative | the same two tests read the slot row back: it still says `revision-r`, and the plan day it names still belongs to `revision-r` |
| the Slot is never re-pointed | the whole slot row is compared before and after the save — byte-identical, status included |
| `programExerciseId`, exercise, prescription and order are unchanged | the captured elements are asserted one by one against both the value and the stored `session_snapshot_exercise` rows, and the plan's own rows are asserted to have changed underneath them |
| the captured adjustment ids are unchanged | a superseding adjustment is planted and the older session still presents the adjustment that was in effect — and still names it — while a new attempt at the same opportunity presents the new one |

## 4. One `IN_PROGRESS` attempt per slot — the mechanism

§19: *"One Slot may have multiple attempts, but no more than one `IN_PROGRESS` Session."* The rule spans
two rows, so it cannot be a check this layer makes before writing, and it cannot be a uniqueness
constraint on `slotId` — that would forbid the repeated attempts §19 allows.

**The mechanism is the statement that stores the session:**

```sql
INSERT INTO `workout_session` (`sessionId`, `slotId`, `programId`, `revisionId`, `status`, `startedAt`,
                               `finishedAt`)
SELECT :sessionId, :slotId, :programId, :revisionId, :status, :startedAt, :finishedAt
WHERE NOT EXISTS (
    SELECT 1 FROM `workout_session` WHERE `slotId` = :occupiedSlotId AND `status` = :occupiedStatus
)
```

* The predicate is evaluated **by the engine, inside the write**, so there is no read-then-write window
  for two starts to pass through. `WorkoutSessionRepository.startSession` contains no read before the
  write at all.
* Whether the row was stored is read back with **`SELECT changes()`** — SQLite's own count for the
  statement just executed on this connection — immediately after it and inside the same transaction
  (`WorkoutSessionRepository.startSession`, `WorkoutSessionDao.changedRowCount`). Room gives an insert no
  return value that could say it (`INSERT` methods may return `void` or a rowid, and a rowid is the last
  one on the connection, not this statement's), which is why the count is the signal.
* `0` rows means the slot is occupied: the repository raises `SessionAlreadyInProgress` **inside the
  transaction**, so the whole start rolls back — no session, no snapshot, no occurrence — and the runtime
  reports `SessionRefusal.SlotIsAlreadyBeingWorkedOut`, naming the attempt that holds the slot when it can
  read it back.
* `status` is a parameter, not a literal, so the vocabulary stays in the domain; and the status a session
  is *stored* with is the status that *occupies* a slot because a session is started `IN_PROGRESS`
  (`startSession` requires it). The two occurrences are separate named parameters, so the statement says
  which is which.

**Why not a partial unique index** (`... ON workout_session(slotId) WHERE status = 'IN_PROGRESS'`), which
would enforce it for every writer: Room 2.6.1 cannot declare one. `androidx.room.Index` has no `partial`
attribute (verified against `room-common-2.6.1.jar`), and `androidx.room.util.TableInfo$Index` has no
`partial` field (verified against `room-runtime-2.6.1-runtime.jar`), so an index the migration created but
Room had not generated would appear in `TableInfo.read`'s index set and fail Room's open-time schema
validation on every device. Adding it through a hand-written callback would also diverge a fresh database
from an upgraded one, which is the second thing the schema suites pin.

**Proved where it is enforced.** `WorkoutSessionRepositoryTest` calls the repository **directly**, with no
use case above it, and asserts the refusal and the untouched row census;
`SessionRuntimeTest.twoStartsRacingForOneOpportunityDoNotBothSucceed` runs two runtimes over **two
independent connections to one file database**, started from a `CountDownLatch`, and asserts that exactly
one attempt succeeds and that the database holds one row, `IN_PROGRESS`, with the whole graph of one
attempt. The loser is either refused by the predicate (it saw the committed row) or rejected by the
engine's writer lock (it did not); the test asserts the invariant rather than which of the two happened,
because which one it is depends on real timing. Both outcomes are the same answer to the same question.

## 5. The completion transaction (§27)

```text
Complete Workout → Session + Slot + Adaptive state + Decisions + Adjustments
```

`finishSession` opens **one** transaction and writes, in order:

1. the session row, `COMPLETED` with its finish stamp (`WorkoutSessionRepository.finishSession`);
2. the opportunity, `COMPLETED` with the same stamp (same method, same unit);
3. the adaptive decision the caller handed over, with the adjustment it produced
   (`ProgramAdaptiveRepository.persistDecision`, which enforces the `APPLIED ⇔ adjustment` pairing).

The transaction is opened by the runtime rather than by either repository because each of them owns its
own rows and it is this layer that owns the composition; a failure anywhere inside the unit leaves the
pre-completion state exactly as it was. The suite plants a failure at the snapshot, at the occurrences, at
the set log, at the slot's outcome write and at the adjustment write, and asserts a whole-database row
census after each.

### The explicit no-decision outcome

**The runtime never invents an adaptive decision.** A completion is not evidence for one, and a fabricated
`NOT_APPLIED` row would claim a decision nobody made. The seam is `AdaptiveCompletion`:

| the adaptive stage | what the completion persists | what it reports |
| --- | --- | --- |
| `NothingDecided` — there is no decision | no adaptive row at all | `AdaptiveOutcome.NothingDecided` |
| `Decided(decision)` with `NOT_APPLIED` — the aggregate load guard filtered it out | the decision row, as `NOT_APPLIED`, and no adjustment (§18 keeps a filtered-out decision rather than dropping it) | `AdaptiveOutcome.Stored(decisionId, null)` |
| `Decided(decision, adjustment)` with `APPLIED` | the decision row and the adjustment, as one fact | `AdaptiveOutcome.Stored(decisionId, adjustmentId)` |

So *"no decision applies"* has an explicit, stored-or-reported answer in both of its meanings: a decision
that applied nothing is stored with its own outcome, and a completion with no decision stores nothing and
says so. A decision must be about **this** opportunity — the same Program, revision and slot as the
session — because it is stored as part of that opportunity's history (§16), and any pair the adaptive
contract rejects fails loudly rather than being repaired.

## 6. The rules, and where each is enforced

| rule | enforcement |
| --- | --- |
| A Session is bound to exactly one `ProgramId + RevisionId + SlotId` | `WorkoutSession`'s own `init` requires the snapshot's identity triple to equal the session's, and the runtime refuses a slot whose revision belongs to another Program or whose plan day the revision does not present |
| Starting captures a complete immutable `SessionSnapshot` | One transaction (§27), and the snapshot is written from the value the start composed — `session_snapshot_exercise` rows carry the exercise, the dimension and the per-set targets per element, in presentation order |
| A loaded Session is reconstructed from its stored snapshot and runtime rows | The read path is `sessionById`, which touches the five session tables and no plan or adaptive table; the architecture suite pins that the runtime reads one revision, once, and only on the start path |
| Later Revision edits or Adaptive Adjustments never change an already-started Session | The §3 suite: a §6 Save, a rewrite of the plan's rows and a superseding adjustment, each measured against the captured snapshot |
| Snapshot preserves identity, order, prescription and captured adjustment ids | `presentedWorkout` (pure) and the captured rows; the runtime captures the standing adjustments' `after` forms in place and their ids in presentation order |
| One Slot may have multiple Session attempts | Several rows are legal, and the suite proves two coexist — one cancelled, one running |
| At most one `IN_PROGRESS` Session per Slot, refused atomically | The conditional insert + `changes()` (§4) |
| Confirmed sets autosave immediately as `SetLog` rows | `confirmSet` appends one row per confirmation and re-reads the session it stored |
| A missing SetLog row means the set was not performed | There is no zero-valued write path: the unit check refuses the set, and `SetResult` itself cannot represent zero work |
| Set indices are ordered, and gaps are refused when loading | The index is derived from the stored rows; a gap is invalid persisted data and fails the read loudly |
| `Back` does not cancel or delete | No operation exists for it, and a restore writes nothing |
| Explicit cancel preserves the Session and its history as `CANCELLED` | `cancelSession` writes the session row alone; its sets stay, and the opportunity is untouched |
| Finish means `COMPLETED`; cancellation never becomes completion | `finishSession` refuses any session that is not `IN_PROGRESS`, `CANCELLED` included; nothing rewrites a finished attempt |
| The Session survives process/screen recreation | The restart case: a second connection, fresh repositories and a fresh runtime restore the attempt from the rows the first process wrote |
| `Complete Workout` is one transaction | §5 |
| Any failure in completion leaves the pre-completion state intact | The planted-failure suite, with a whole-database census |
| Historical Session/Snapshot data is never rewritten by later adaptation | `finishSession` writes two outcome columns and no more; the snapshot tables have no update path at all |
| The Scheduler never creates the Session | The runtime has no scheduler and no calendar; PR 7's own guard pins the reverse direction |
| No lifecycle policy beyond the Session operation | The runtime has no `ProgramRepository`: whether a Program is paused, archived or completed is §3's and §29's |
| No Revision mutation | One read (`revisionById`), no `saveNewRevision`, pinned by the architecture suite and by a RED mutation |
| No `RevisionConflict` mechanism | No case of `SessionRefusal` is a conflict, and no draft or staleness token exists here |

## 7. Verification

Measured on the final bytes, with a forced-fresh run whose JUnit XML `timestamp` was checked against
`date -u`:

| gate | result |
| --- | --- |
| pristine `origin/main` baseline (`6d9d6bd`, detached worktree, `:app:cleanTest :app:testDebugUnitTest --rerun-tasks`) | **234 classes / 1998 tests / 0 failures / 0 errors / 0 skipped** |
| branch, same command | **238 classes / 2080 tests / 0 failures / 0 errors / 0 skipped** — **+4 classes / +82 tests** |
| branch after the two snapshot assertions and the post-save start case (§9 decision 1) | **238 classes / 2081 tests / 0 failures / 0 errors / 0 skipped** — **+4 classes / +83 tests**; newest XML `timestamp` `2026-09-19T11:13:16Z` vs `date -u` `11:13:16Z`, `BUILD SUCCESSFUL in 1m 19s` |
| structural cross-check | `grep -rl '@Test' app/src/test/java \| wc -l` = 238; `grep -rho '@Test' … \| wc -l` = 2081 |
| focused PR-8 suites | `SessionRuntimeTest` 47, `SessionRuntimeArchitectureTest` 15, `SessionRuntimeResultTest` 7, `SlotPresentationTest` 11 — 80 tests, 0 failures; plus the two suites that own the occupancy boundary: `WorkoutSessionRepositoryTest` 16 and `ProgramDataAccessArchitectureTest` 11 |
| `:app:compileDebugKotlin` / `:app:compileReleaseKotlin` / `:app:assembleDebug` | BUILD SUCCESSFUL in 18s |

The **+83 tests are exactly the four new classes (80) plus three tests added to the pre-existing
`WorkoutSessionRepositoryTest`** (the two occupancy tests and the finished-attempt one). No pre-existing
test was deleted, disabled or loosened, and no golden, RFC or plan document was touched.

`lintVitalRelease` remains excluded for the known pre-existing `themes.xml` resource cycle; the release
evidence is `compileReleaseKotlin` plus the assembled debug APK.

### Two pre-existing guards were revised, not relaxed

* `ProgramDataAccessArchitectureTest` asserted that `ProgramDao.updateProgram` was *the only* hand-written
  write. §30 step 8's occupancy rule is a second one — a conditional insert has no entity to generate from
  — so the list is still exact (two entries, one per statement), and the statement's shape is now asserted
  where it was only named: its table, its columns in the table's order, its `WHERE NOT EXISTS` predicate,
  the pair it guards, and that it changes no existing row. The suite also asserts the report of its
  effect is `SELECT changes()`.
* `WorkoutSessionRepositoryTest.twoAttemptsAtOneSlotBothLoadAndTheSlotReportsBoth` started two attempts in
  progress on one slot and asserted that both loaded — which is exactly the state §19 forbids, and which
  the stage that owns the rule removes. It is **corrected** rather than deleted: it now ends the first
  attempt before starting the second, and the occupancy rule has its own two tests beside it.

## 8. RED evidence

`scripts/program-session-runtime-red-mutations.sh` applies one mutation at a time to the *production*
sources, reruns the focused PR-8 suites (plus the repository suite and the data-access architecture suite
that own the occupancy boundary), restores the file, and proves the restoration by `md5sum -c`. Seventeen
rules plus a control: **18 caught / 0 missed** on the final bytes, with the three mutated sources verified
byte-identical afterwards.

The control run is the un-mutated tree and it must be **GREEN** — it is, which is what makes the other
seventeen rows evidence rather than noise.

| mutation | rule it breaks | caught by |
| --- | --- | --- |
| `restore-recomposes-from-the-live-plan` | the snapshot is the session's truth (§19, §16) | 3 tests: `theSessionsPresentationIsNeverReadFromTheLivePlan`, `aSupersedingAdjustmentDoesNotChangeWhatAStartedSessionPresented`, `theRestoredPresentationIsDomainEquivalentToTheCapturedSnapshotAfterANewRevision` |
| `opening-a-workout-cancels-it` | `Back` does not cancel (§19) | 4 tests, `leavingTheWorkoutScreenWritesNothingAndTheAttemptSurvives` among them |
| `the-occupancy-predicate-is-dropped` | at most one `IN_PROGRESS` attempt per slot (§19) | `everyDeclaredDaoQueryIsTheOneTheRepositorySuitesExecute` |
| `the-refused-write-is-treated-as-stored` | a refused start is reported as one that happened (§19, §28) | 3 tests: `aSecondInProgressAttemptAtOneSlotIsRefusedByTheWriteAndLeavesNothingBehind`, `aSecondInProgressAttemptIsRefusedAndNamesTheOneThatHoldsTheSlot`, `aFinishedAttemptIsStoredAndDoesNotHoldTheOpportunity` |
| `start-is-not-one-unit` | §27: Session + complete Snapshot is one write | 3 tests: `startingASessionIsAtomic`, `aStartThatFailsBeforeTheSnapshotLeavesNoSessionBehind`, `aStartThatFailsBeforeTheOccurrencesLeavesNoSnapshotBehind` |
| `sets-are-not-autosaved` | confirmed sets autosave (§27) | 11 tests |
| `a-set-outside-its-prescription-is-logged-anyway` | a set is logged in its prescription's unit, and zero work is not a set (§10, §12) | 2 tests |
| `every-set-takes-the-first-position` | sets accumulate `1..n` | 3 tests, `setPositionsAreNeverRenumberedAndAGapIsRefusedWhenLoading` among them |
| `cancel-completes-the-attempt` | cancelled is never completed (§19) | 3 tests, `aCancelledAttemptCanNeverBecomeACompletedOne` among them |
| `finish-leaves-the-opportunity-open` | Finish persists `COMPLETED` **and** takes the opportunity (§27) | 5 tests |
| `the-completion-is-split` | `Complete Workout + Adaptive` is one transaction (§27) | `aCompletionThatFailsOnTheAdaptiveWriteLeavesThePreCompletionStateIntact` |
| `the-start-captures-no-adjustment` | the snapshot captures the adjustments in effect (§16) | 3 tests, `anAdjustmentStandingForTheOpportunityIsAppliedAndItsIdCaptured` among them |
| `an-invalid-adjustment-is-silently-dropped` | an adjustment the revision cannot present is refused, never skipped (§16) | `anAdjustmentThatNamesAnElementThisRevisionDoesNotPresentRefusesTheStart` |
| `a-finished-or-withdrawn-opportunity-is-started` | a taken or withdrawn opportunity is not started (§19, §20) | `anOpportunityThatWasAlreadyTakenIsRefused`, `aSupersededOpportunityIsRefused` |
| `a-rest-day-is-started-with-an-empty-presentation` | a rest day has no workout to start (§20) | `aRestDayHasNoWorkoutToStart`, `aRefusedOperationWritesNothingAtAll` |
| `the-revisions-program-is-not-checked` | the identity triple holds (§19) | `anOpportunityWhoseRevisionBelongsToAnotherProgramIsRefused` |
| `a-decision-about-another-opportunity-is-accepted` | a decision belongs to the completion it is stored with (§16, §27) | `aDecisionAboutAnotherOpportunityIsRefusedAndNothingIsCompleted` |

**One mutation is caught structurally rather than behaviourally, and it is worth saying out loud.**
Dropping the `WHERE NOT EXISTS` predicate from the DAO's insert is caught by
`ProgramDataAccessArchitectureTest`, which asserts that the statement the DAO declares is the statement the
repository suites execute — because on this JVM the DAO's SQL is *never executed by Room*: the harness
drives its own copy of it on a real SQLite engine, and the architecture test is what keeps the two in sync
(PR 3's design). The rule's behavioural pinning is mutation 4 instead: removing the *report* of the refused
write — one line of the repository — fails the two behavioural occupancy tests. Between them, the mechanism
is proven both as the statement it is and as the behaviour it produces.

## 9. Decisions for the owner

1. **A slot presents the revision it was scheduled from, not the Program's current revision.** §16
   composes `ProgramRevision + AdaptiveAdjustment`, and the Scheduler document says in prose that *"the
   effective presentation of a slot is the current revision plus adjustments"*. The two cannot both hold:
   a later revision mints new day and element identities, so a surviving opportunity's plan day is not
   present in it, and an adjustment names an element of the revision it was made against. This stage
   implements the composition the data supports — the opportunity's own revision — and the cost is that
   after a §6 Save a surviving opportunity keeps presenting the plan day it was scheduled from until the
   Scheduler supersedes it. The alternative (resolve the day by position in the current revision) needs a
   mapping nothing stores, and would make an opportunity's presentation a function of the live plan, which
   is what a snapshot exists to prevent. If the owner wants the other reading, the change is confined to
   `SlotPresentation` plus the start path's revision lookup, and it needs a decision about what happens to
   adjustments that name elements the new revision does not have.
2. **A completed or withdrawn opportunity is not started again.** `PLANNED` and `MISSED` are startable; a
   `COMPLETED` opportunity is not (a second attempt would have to overwrite the stamp of the workout that
   took it) and a `SUPERSEDED` one is not (the user was not expected to train it). Multiple attempts are
   therefore supported through attempts that ended without taking the opportunity — a cancellation, or a
   missed opportunity trained late. The alternative reading, that a user may repeat a completed workout as
   a new attempt, is a small change (`startRefusal`) plus a decision about what completing the second
   attempt means for the slot's single `completedAt` stamp.
3. **A missed opportunity is startable.** Missed detection is attempt-agnostic and date-based (§20), so a
   user who missed Tuesday and trains on Wednesday attempted that opportunity, and the completion records
   it as taken. The alternative — refusing a missed opportunity — would leave the user unable to train it
   at all, since nothing in the architecture un-misses a slot.
4. **A finished attempt takes no more sets.** `confirmSet` refuses a session that is not `IN_PROGRESS`,
   including a `CANCELLED` one; a screen that still shows a cancelled workout therefore cannot autosave
   into it. Whether a cancelled attempt's remaining sets should be loggable is a product question, and the
   current answer is no: the attempt ended.
5. **Skipping is not one of the five operations.** §12's `skipped` flag exists on the occurrence and is
   honoured by `confirmSet` (a skipped occurrence takes no set), but nothing in this stage sets it: the
   API the brief specifies is start, restore, confirm, cancel and finish. A screen's "skip this exercise"
   action belongs to the UI integration step, and the refusal is already in place for when it arrives.
6. **The `SlotIsAlreadyBeingWorkedOut` refusal names the holding attempt only when it can be read back.**
   A refused start rolls its transaction back before the runtime can look the holder up, so the lookup is a
   second, best-effort read on the refusal path; if it returns nothing the refusal still carries the slot.
7. **`SlotIsAlreadyCompleted` on the finish path is defensive.** Two attempts cannot be in progress at
   once, so a slot cannot be completed by another attempt while one is running; the check exists so a
   hand-written write to `program_workout_slot` cannot make a completion overwrite a stamp.
8. **The occupancy guard counts `IN_PROGRESS`, not the status of the row being written.** The first version
   of this stage made `startSession` require an `IN_PROGRESS` session, which made "no other attempt in the
   same status" and "no other unfinished attempt" the same sentence — and the full suite found the cost:
   `ProgramLifecycleTest.aProgramWithACompletedSessionCanBeDeleted` stores a *completed* attempt through
   the repository, and the require rejected it. The rule §19 states is about **unfinished** attempts, so
   the guard's status is now the rule's own (`WorkoutSessionEntity.IN_PROGRESS`) and no session a caller
   may legitimately store is refused. The reading is pinned by
   `WorkoutSessionRepositoryTest.aFinishedAttemptIsStoredAndDoesNotHoldTheOpportunity`: a finished attempt
   is stored, it does not hold the opportunity, a new attempt may be started beside it, and only then is a
   third attempt refused. No pre-existing test was changed to accommodate the guard.
