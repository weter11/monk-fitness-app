# Program — target occurrence execution read-back (§30 step 15)

## The gap this closes

Phase 14 made a target occurrence's **semantics** a stored fact. It deliberately stopped there, and
said why in its own words:

```text
PersistedTargetOccurrence   semantic schedule data
ExistingOccurrence          semantic schedule data + execution state + actual results
```

The missing half is everything a workout *did*. Three separate stores hold it, and none of them is
the occurrence:

```text
program_target_occurrence            what the occurrence was
program_workout_slot                 the opportunity that presented it
workout_session (+ snapshot,         what was attempted, and what was performed
  session_exercise, program_set_log)
```

A read-back had exactly three available routes to that information, and all three are inventions:

* **derive the execution from `WorkoutSlot.status`** — the status has no member that distinguishes
  `STARTED` from `CANCELLED`, and `MISSED`/`SUPERSEDED` are opportunity outcomes rather than
  `OccurrenceExecution` values at all;
* **read the current `ProgramRevision`** — which re-explains a finished workout against a plan that
  was not the one it was presented under, and which §19 forbids outright;
* **consult the legacy scheduler** — which generates its own occurrences from a `ProgramSchedule`
  that has nothing to do with the target occurrence.

This phase makes the third store readable, as facts, and stops there.

## The central problem: several attempts, one enum

`ExistingOccurrence` holds a **single** `OccurrenceExecution`. The storage model does not:

```text
slot
 ├─ session A: CANCELLED
 ├─ session B: IN_PROGRESS
 └─ ...
```

A slot may be attempted repeatedly (§19); the one rule that spans attempts — at most one
`IN_PROGRESS` — is enforced by the *start* transaction, not by the data. So `CANCELLED → IN_PROGRESS`
and `CANCELLED → COMPLETED` are both legal stored histories, and **no stored fact says which attempt
decides the occurrence's execution state**. Choosing one would be inventing a precedence rule, and
inventing it here means inventing it twice when the phase that owns the policy arrives.

So the phase's answer is a value with no verdict in it.

## What was added

| File | Role |
| --- | --- |
| `domain/program/target/TargetOccurrenceExecutionRead.kt` | `TargetOccurrenceExecutionRecord`, the pure `TargetOccurrenceExecutionFacts` aggregation, and seven typed refusals |
| `domain/usecase/TargetOccurrenceExecutionReader.kt` | the reader: composes the three repositories and nothing else |

No new table, no migration, no DAO, no mapper, no entity. The phase needed no new storage model,
which is the point: the facts were already stored and merely unreachable as a unit.

## The read model

```kotlin
data class TargetOccurrenceExecutionRecord(
    val occurrence: PersistedTargetOccurrence,   // 1. semantic target occurrence
    val slot: WorkoutSlot,                      // 2. opportunity / slot state
    val attempts: List<WorkoutSession> = emptyList()   // 3. + 4. session attempts and their work
)
```

Four layers, four members, no collapse:

| Layer | Carried as | Preserved because |
| --- | --- | --- |
| semantic occurrence | `PersistedTargetOccurrence` | Phase 14's read-back, returned unchanged |
| opportunity | `WorkoutSlot` | a slot is an opportunity, never an amount of work (§20) |
| attempt state | `List<WorkoutSession>` | several attempts are legal and none is special (§19) |
| performed work | `SessionExercise` / `SetResult` inside each attempt | facts about the captured presentation and execution |

The derived accessors are all *reads*, none is a decision:

| Accessor | Returns | Deliberately not |
| --- | --- | --- |
| `slotStatus` | the stored `SlotStatus` | an `OccurrenceExecution` |
| `attemptStatuses` | `List<SessionStatus>`, in start order | one status, or one derived from `finishedAt` |
| `attemptIds` | the identities, in start order | — |
| `hasAttempts` | whether any is stored | a verdict about the occurrence |
| `confirmedSets()` | every set, attempt order then set order | a sum, an `ActualResult` |
| `skippedExercises()` | every explicit skip | a zero-valued workout (§12) |

### Slot status and session status stay apart

This is the conflation the phase is built to prevent, so it is stated mechanically. `slotStatus`
returns `SlotStatus`; `attemptStatuses` returns `List<SessionStatus>`; neither is derived from the
other; and the two are distinct types, so a read that swapped them would not compile. The suite stores
a case where the two genuinely disagree — a **completed slot** whose second attempt was
**cancelled** — which is legal (§19: a cancel does not take the opportunity) and true at once: the
opportunity was taken, the second workout was not finished.

### The pure aggregation boundary

`TargetOccurrenceExecutionFacts` offers exactly two operations, and neither chooses:

* `countsOf(record)` — how many attempts, and how many per stored status;
* `attemptsWithStatus(record, status)` — a filter that returns all of them or none, in stored order.

It is there so a later phase has a *counting* vocabulary to build on without this phase having to
supply the *decision* vocabulary counting implies.

## Repository dependencies

Exactly three, and the constructor is the whole declared collaborator set — which the architecture
gate asserts as a list, so a fourth cannot appear unnoticed:

| Repository | Asked for | Owns |
| --- | --- | --- |
| `TargetScheduleOccurrenceRepository` | `occurrenceOf(programId, occurrenceKey)` | the semantic occurrence |
| `ProgramScheduleRepository` | `slotByTargetOccurrenceKey(programId, occurrenceKey)` | slot / opportunity facts |
| `WorkoutSessionRepository` | `sessionsOfSlot(slot.slotId)` | the session aggregate and its graph, with its validation |

`slotId` enters **only** at the third step, to follow the established slot → session link (§23)
after the target identity has located the slot. No DAO, no Room, no `AppDatabase`, no transaction
runner: a read that needs a consistent snapshot of a database it does not own is the storage layer's
contract, not something this class may invent.

## `ExistingOccurrence` was intentionally not constructed

Nothing in the phase names `OccurrenceExecution`, `ActualResult` or `ExistingOccurrence` — not in the
value, not in the reader — and the architecture gate asserts their **absence** as whole tokens. The
reasons are factual:

1. **No precedence rule exists.** With several attempts, nothing stored says which one decides. A
   single-enum mapping would be a guess, and a guess in a read-back is a lie dressed as a read.
2. **No `workId` relationship exists.** `ActualResult` is keyed by a `workId`; a session's
   `SessionExercise` is keyed by an `exerciseId` and links to the plan by `programExerciseId`. No
   documented contract relates either to an occurrence's `workId`.
3. **"Sum all sets into one actual" is a rule, not a read.** It would also destroy what §12 protects:
   a skipped exercise observed nothing, and a cancelled attempt's partial work is not a completed set.

## Failure behaviour

Seven typed refusals, all `IllegalStateException` (they describe stored data that disagrees with
itself, not a caller's bad argument):

| Refusal | Detects |
| --- | --- |
| `MissingTargetOccurrence` | no semantic record for the pair |
| `MissingTargetSlot` | a stored occurrence with no slot presenting it |
| `SlotBelongsToAnotherProgram` | the slot is of another Program |
| `SlotTargetKeyMismatch` | the slot stores another target key |
| `SessionBelongsToAnotherProgram` | an attempt is of another Program |
| `SessionBelongsToAnotherRevision` | an attempt was started under another revision |
| `SessionReferencesAnotherSlot` | an attempt names another slot |

Corrupt data is never skipped, repaired, or turned into an empty record. The reader's only `?:` are
the two absent records; there is no `?: emptyList()` anywhere on the attempt path, and the
architecture gate asserts that.

**A malformed session graph is deliberately not caught here.** `sessionsOfSlot` assembles each
session through the repository's own mapper and snapshot validation (§19), and a graph that does not
hold together fails *there*. Catching it here and returning fewer attempts would silently drop
stored execution facts — the one outcome this phase exists to prevent. The suite proves the
propagation rather than the swallowing.

* **Two tests corrected their own premise during the phase**, and the corrections are the
  interesting part:

* *A re-pointed target key is a **missing** slot, not a mismatched one.* The lookup is keyed on the
  pair, so a row that now names another identity means the asked-about pair has no slot. Reporting a
  mismatch would imply the reader had found a row and disagreed with it. The mismatch guard remains
  as a second line, tested directly on the record's construction invariant.
* *A completed slot whose attempt was deleted is refused by `WorkoutSlot` itself* — a `COMPLETED`
  slot is completed *by a session* (§19/§20), so that state is invalid stored data and the schedule
  repository's own assembly catches it. The suite asserts the propagation instead of pretending the
  record is readable. No domain invariant was weakened in either case; both premises were wrong about
  what the model permits, and the honest fix was to state what it does permit.
* *A third test was renamed rather than rewritten.* `aSessionReferencingAnotherSlotIsATypedFailure`
  asserted a refusal the reader never makes: `sessionsOfSlot` selects on `slotId`, so a row that names
  another slot is simply not returned, and the read succeeds with no attempts. It is now
  `anAttemptMovedToAnotherSlotIsNotAttributedToTheSlotItNoLongerNames`, which is what it actually
  measures. The `SessionReferencesAnotherSlot` refusal remains in the reader as a second line behind
  the record's own invariant, guarding a repository that ever widened that query — and the test says
  so, rather than claiming coverage it does not have.

## Identity

`(programId, occurrenceKey)`, and nothing else. Not `slotId`, not `revisionId`, not `programDayId`,
not a planned date, a weekday, a `ProgramDay` position or name, not a parsed occurrence key, not the
current revision. Two Programs holding the same key stay independent, and the suite measures it.

## No new target identity mechanism

No new key, UUID, hash, composite synthetic identifier or encoded session identity was introduced, and
no second copy of `targetOccurrenceKey` was stored. The record exposes the occurrence's own key
through a derived getter, so the value is not duplicating a field it already holds.

## Legacy isolation

`ProgramSchedule`, `LegacySchedule`, `LegacyScheduleMapper`, `ProgramScheduler`, `SlotPlanner` and
`ScheduleCalendar` are untouched. The architecture gate asserts the absence of each as a **whole
token** — a plain substring scan cannot tell `ProgramSchedule` from the required
`ProgramScheduleRepository`, or `OccurrenceExecution` from this phase's own
`TargetOccurrenceExecutionRecord`, and a gate that fires on either would have to be weakened to pass.

## Closed architecture lists — extended, with a reason

The phase adds **one** file to `domain/program/target`, which §5d predicts fails every suite pinning
that package's exact file set. Eight were extended in one pass, each naming the new file and why it
belongs there; each list's closing comment was updated from "a ninth file still fails here" to "a
tenth file still fails here" so the claim stays true rather than merely satisfiable:

`TargetPlannerArchitectureTest`, `TargetScheduleArchitectureTest`,
`TargetSchedulePolicyArchitectureTest`, `TargetOccurrencePresenterArchitectureTest`,
`TargetOccurrenceCompositionArchitectureTest`, `TargetOccurrenceReconciliationArchitectureTest`,
`TargetSlotMaterializerArchitectureTest`, `TargetScheduleInputAdapterArchitectureTest`.

**Revised, not relaxed:** no assertion was removed and no list was opened. The reason a pure read
value belongs in that package is the same one Phase 14 recorded for `TargetOccurrencePersistence.kt`
— it is schedule *semantics*, with no storage, no clock and no id generator — with the addition that
it holds no execution verdict at all.

No other closed list needed extending: the reader adds no wiring, and the phase introduces no
migration, entity or DAO.

## Architecture gates

`TargetOccurrenceExecutionReadArchitectureTest` — the ten claims, each asserted as a token or a
shape in real code with comments stripped first:

1. semantic target data comes from `TargetScheduleOccurrenceRepository`;
2. slot data comes from `ProgramScheduleRepository`;
3. session data comes from `WorkoutSessionRepository` (and the constructor is exactly those three);
4. no DAO / Room / `AppDatabase` / transaction access in the reader or the value;
5. no planner, resolver, composer, policy, presenter, materializer, persister, orchestrator,
   scheduler, runtime or other-repository dependency;
6. no `ProgramSchedule` → target inference;
7. no `ProgramDay` identity inference;
8. no `occurrenceKey` parsing;
9. no clock, randomness or `IdGenerator`;
10. no `ExistingOccurrence` execution precedence — the verdict types are absent, and the pure
    boundary offers exactly two operations, neither of which chooses.

## Verification

* `TargetOccurrenceExecutionReaderTest` — **36 cases**: the four layers; no attempts; one
  `IN_PROGRESS` / `COMPLETED` / `CANCELLED`; three attempts in start order; `CANCELLED` → later
  `IN_PROGRESS`; `CANCELLED` → later `COMPLETED`; the counting/filtering boundary; `MISSED` and
  `SUPERSEDED` with no session; a completed slot with a completed session; a completed slot whose
  last attempt was cancelled; a slot re-pointed to another target key; an orphaned completed slot
  refused by the slot's own invariant; three confirmed sets in order; sets flattened across attempts;
  a skipped exercise; the snapshot equal to the session repository's own read; the semantic
  occurrence equal to Phase 14's; the slot equal to the stored row; the slot's attempt list equal to
  the assembled attempts; the same key in two Programs; the typed failures; a malformed graph
  propagating; corrupt data never becoming an empty record; no current-revision lookup; a key not
  readable through another Program.
* `TargetOccurrenceExecutionReadArchitectureTest` — **12 cases**: the ten gates, the record's four
  layers, the refusals being thrown as well as declared, the record's own invariants, and the pure
  boundary over an empty record.
* `scripts/program-stage15-red-mutations.sh` — **18 mutations, 18 caught, 0 missed**, source
  restored byte-identically, script exit `0`.

Measured on a fresh `--rerun-tasks` pass, not carried over from any previous run:

| Gate | Result |
| --- | --- |
| Full JVM suite (`:app:cleanTest :app:testDebugUnitTest --rerun-tasks`) | **274 classes / 2503 tests / 0F / 0E / 0S** |
| `TargetOccurrenceExecutionReaderTest` | 36 tests, 0F / 0E / 0S |
| `TargetOccurrenceExecutionReadArchitectureTest` | 12 tests, 0F / 0E / 0S |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:compileReleaseKotlin` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `git diff --check` | clean |
| RED mutation script | caught 18, missed 0, restored byte-identically, exit 0 |

The census is cross-checked against the sources — `grep -rho '@Test' app/src/test/java | wc -l`
reports 2503 and `grep -rl '@Test' app/src/test/java | wc -l` reports 274, matching the XML totals —
and the result directory's newest mtime is seconds old at the moment of parsing, which is what makes
the run fresh rather than cached.

### Why the mutation script rejects a compile error

`mutate()` treats a run that fails at `e: ` as a **failure, not a catch**, and reports
`NOT A CATCH (compile error, no oracle saw it)`. A mutation the compiler rejects was never seen by
an oracle, so calling it caught would make the row a claim about nothing. Three rows were found
doing exactly that during this phase and were rewritten as type-correct code at the layer the oracle
reads:

| Row | Why it did not compile | How it was rewritten |
| --- | --- | --- |
| derive from the current `ProgramDay` | referenced `slot` before it is bound at that point | derives the component from a `ProgramDayId` built from the key |
| derive from the legacy `ProgramSchedule` | same, plus `weekdays` read off the sealed interface | same fix, with the concrete `FixedWeekdays` type |
| encode a single-execution precedence | bare `emptyList()` gave "not enough information to infer" | an explicitly typed `emptyList<SessionStatus>()` after the `when` |

Two further rows were caught as MISSED for the *right* reason — the mutation was a semantic no-op,
so the suite was correctly green:

| Row | The no-op | How it was rewritten |
| --- | --- | --- |
| synthesize an empty record when the slot is missing | the replacement still threw | returns a real synthesized `WorkoutSlot` with no attempts |
| read the sessions from a DAO instead of the repository | `takeIf { it.isNotEmpty() } ?: emptyList()` returns the same list whenever it is non-empty, and an empty list was already the value | rebuilds each attempt from the slot's cached attempt ids, skipping the session repository's validation |
