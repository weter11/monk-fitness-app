# P27 — Production Generation Context

§30 step 27. Base `ecb3702` (the P26 merge). Branch `feat/program-stage27-generation-context`.

```text
Program / revision facts
        ↓
GenerationContextSource
        ↓
GenerationPreferences
        ↓
existing ProgramGenerationService
        ↓
existing GenerationRequest
        ↓
existing planner unchanged
```

---

## 1. The stage in one paragraph

P24 made generation production-callable and stated, honestly, that it had **no** production source for
§8's plain signals — so every request carried `GenerationPreferences.NONE`. P27 replaces that stated
neutrality with a real read: a new application-level **`GenerationContextSource`** assembles
`GenerationPreferences` from facts production already owns, and `ProgramGenerationService` asks it once
per pass and forwards the answer into the **already-existing** `GenerationRequest.preferences`.

Exactly **one** of the six signals could be filled. The other five stay at their neutral values, each for
a reason recorded in §3 and each pinned by a test. That is the stage's result, not a shortfall to be
explained away: the brief's order of preference is *real fact > explicit neutral absence > invented
heuristic*, and five of six fields had no honest owner.

No planner rule changed. `GeneratedPlanner`, `FocusPlanner`, `ExerciseSelector` and `PlanReconciler` are
untouched; `GenerationRequest` gained no field; `GenerationPreferences` semantics are unchanged; P27 adds
no persistence of any kind.

---

## 2. What was added

| file | what it is |
| --- | --- |
| `domain/usecase/ProgramGenerationContext.kt` | `GenerationContextSource` (the port), `ProgramHistoryGenerationContext` (the production source), `GenerationSessionHistory` (the one-method read port) |
| `domain/usecase/ProgramGenerationService.kt` | **modified**: `preferences: GenerationPreferences = NONE` → **required** `context: GenerationContextSource`; `generate`/`regenerate`/`preview` became `suspend`; the read happens once, in the shared private `edit` |
| `di/AppContainer.kt` | **modified**: wires the production source over `workoutSessionRepository.sessionsOfProgram` |
| `domain/program/generated/**` | **unchanged** — no file added, no file edited |

### Why the read is `suspend`

The context is a storage read, so the pass owns a coroutine. This is a signature change, not a semantic
one: a `suspend fun` compiles to a JVM method with a trailing `Continuation`, which is why P24/P26's
reflection assertions needed revision (§7).

### Why `context` is required rather than defaulted

A defaulted `preferences` parameter was the quiet neutrality P27 exists to remove. Making it required
means every construction site states where its facts come from — a service built without one could not
silently plan against nothing. The collaborator *count* is unchanged at five.

---

## 3. The per-signal table

| signal | owner / source | exact semantic unit | scope | present? | reason |
| --- | --- | --- | --- | --- | --- |
| `userPreferredExerciseIds` | **none** | exercises **the user** asked for, most preferred first | — | **no** | No persisted, user-authored preference ordering exists. The draft's `USER_AUTHORED` / pinned elements are *plan content* that reconciliation preserves (§7), not a ranked wish list; reading them as a preference would be a different meaning the codebase does not state — and would break `GenerationPreferences`' own "preferred once" invariant the moment an element was also adaptive-preferred. |
| `adaptivePreferredExerciseIds` | `ProgramAdaptiveRepository.familyStates(revisionId)` — **not read** | exercises the adaptive layer would prefer, most preferred first | — | **no** | The stored `FamilyProgressionState.currentExerciseId` means *"the exercise id the family is currently on"*, and is family-scoped **and** revision-scoped (§23). No existing contract makes it an exercise-selection preference for generation, so promoting it would fabricate the preference the field is named for. `AdaptiveDecision` / `AdaptiveAdjustment` carry no preference either. |
| `recentExerciseIds` | `WorkoutSessionRepository.sessionsOfProgram(programId)` → `SessionExercise.exerciseId` where `results.isNotEmpty()` | the exercises **actually performed**, most recent first | `ProgramId` | **YES** | The only signal whose semantic unit the session graph already records. §19's snapshot guarantee means the value comes from the session's own stored rows, not from a live plan. |
| `recentExposureByFocus` | **none** | **focus assignments** the recent context was loaded with — the unit the Focus Planner itself allocates (§8) | — | **no** | Not a count of workouts, slots or exercises. No link exists: `WorkoutSession`, `SessionExercise`, `EffectiveExercise` and `ProgramExercise` carry **no focus field**, and a slot's `FocusAssignment` is a generated-plan value that reconciliation does not keep per element. Classifying performed exercises through `ProductionFocusClassification` would be exactly the reconstruction algorithm P27 must not invent — and an exercise that *trains* two focuses is not two assignments. |
| `recentLoadByFocus` | **none** | **recent performed sets, by focus** (§8 / §17) | — | **no** | The same missing link one dimension up: there is no focus to attribute a performed set to. A `LoadProfile` is family-scoped and multi-dimensional (sets / repetitions / seconds side by side, never summed); converting it to a scalar, adding repetitions to seconds, or attributing sets to a focus would each be a cross-dimension conversion. |
| `recovery` | `AdaptiveJudgementRule` — **not reachable** | the recovery **context** generation is planned in (§14's vocabulary) | — | **no** | `RecoveryContext` is produced by the adaptive stage's own judgement rule for **one decision window of one family**, and that rule itself receives `UNKNOWN` as its documented absence (`ProgramAdaptiveIntegration:293`). There is no production-owned recovery context for a generation request, so there is none to read. |

### What "neutral" means here, precisely

```text
userPreferredExerciseIds    = emptyList()
adaptivePreferredExerciseIds = emptyList()
recentExerciseIds           = <filled from this Program's performed history>
recentExposureByFocus       = emptyMap()
recentLoadByFocus           = emptyMap()
recovery                    = RecoveryContext.UNKNOWN
```

**`missing ≠ zero`.** No focus appears in either map with a `0` entry, because `0` is a claim that the
Program trained that focus as often as nothing, and no such claim is supported. `UNKNOWN` is used only
where §14's own domain explicitly defines it as *"not knowing yet"* rather than as a missing value.

---

## 4. Scope discipline

**`ProgramId` is the scope of history.** The read is `sessionsOfProgram(programId)` and nothing else.
Never mixed in: another Program's sessions, an All-Programs aggregation, global progress, or an older
revision's state read as a fact of the current revision. A draft with no `programId` has no Program to
scope to and receives `GenerationPreferences.NONE` **without issuing a read at all** — which is a
distinction the tests make observable by counting the calls.

**`RevisionId`** is a separate scope only where existing adaptive state is already revision-scoped. P27
reads no adaptive state, so no revision scoping applies; the one place a revision appears is
`FamilyProgressionState`, which is not read.

### What counts as a performed occurrence

An occurrence is exposure when it has **at least one confirmed set** — the same rule
`ProgramAdaptiveIntegration.observationsOf` applies, and the one `ExposureObservation` states in its own
invariant (*"a skipped exercise or a missed slot produces no observation rather than a zero one"*).

Excluded, as absent rather than as zero: a skipped occurrence, an occurrence with no confirmed set, a
`MISSED` slot, a `PLANNED` slot nobody attempted, planned-but-never-started content, and any
zero-work pseudo-observation.

A session's own `status` is deliberately **not** a filter. A cancelled or in-progress attempt holds real
confirmed sets and §19 preserves that partial work; dropping it would invent a rule no domain statement
owns.

### Ordering

`recentExerciseIds` is **most recent first**. The repository already returns a Program's sessions in
start order (`ORDER BY startedAt ASC, sessionId ASC`), so the list is the reverse of that and nothing is
re-sorted by a comparison of its own. Within a session, occurrences keep presentation order. A repeated
exercise keeps its **most recent** position and is stated once — `GenerationPreferences` refuses a
duplicate here, since "most recent first" is an order.

---

## 5. Snapshot consistency

```text
Generate  → read context once → build request → generate → reconcile
Preview   → read context once → build request → generate → hold the result
Apply     → the held GeneratedDraftEdit, unchanged (P26's own rule)
```

The read happens in the shared private `edit`, once, and the value is passed to `planned` as an argument
rather than re-read there. There is no second request-assembly path and no parallel Preview pipeline —
`generate`, `regenerate` and `preview` all hand to the same private pass, which P26's suite pinned and
P27 re-pins in the `suspend` form.

`ProgramGenerationContextSnapshotTest` proves this with a source that answers **differently on every
call**: a stateless double cannot distinguish "read once" from "read many times", and cannot distinguish
"the same snapshot" from "two coincidentally equal snapshots". It asserts one read per `generate`, per
`preview` and per `regenerate`; that three operations take three snapshots (so the fix for a stale-context
bug is not "hold the preferences in a field"); and that Generate and Preview over unchanged state see
equal snapshots and produce equal plans.

---

## 6. Architecture boundaries

| boundary | how it is held |
| --- | --- |
| `domain/program/generated` stays pure | no file added, no file edited; the gate asserts the exact eight-file list and that the package imports nothing from `domain.usecase`, `data` or `di` |
| no new persistence | no table, entity, DAO, migration or generation-snapshot row; P27 adds no schema version. The port declares one read method, so "the context source performs no writes" is a property of its **shape** |
| the service holds no storage | the ban is on persistence collaborators (`*Repository`, `*Dao`, `AppDatabase`, `Entity`, `Room`); `GenerationContextSource` is a **port**, and the narrow `GenerationSessionHistory` port is reached by the composition root, never by the service |
| one construction site | `ProgramHistoryGenerationContext` is built only in `AppContainer` |
| no clock in the source | the class names no `Clock`, `Duration`, `ChronoUnit` or `Instant.now`, which is the structural form of "recovery is never derived from elapsed time" |
| no adaptive state in the source | the file names no `FamilyProgressionState`, `AdaptiveDecision`, `AdaptiveInputSnapshot`, `ExposureObservation`, `LoadProfile` or `ProgramAdaptiveRepository` |

---

## 7. Tests: revised, not relaxed

P27 changed three P24/P26 claims. Each was **revised to the new contract**, never weakened:

| test | was | now |
| --- | --- | --- |
| `ProgramGenerationFlowArchitectureTest.theServiceStatesTheNeutralPreferencesRatherThanDefaultingThemQuietly` → **renamed** `theServiceReadsItsContextThroughOneRequiredCollaboratorAndNeverBuildsIt` | asserted `preferences = GenerationPreferences.NONE` was declared, and called it one of two optional collaborators | asserts the exact five collaborator **types**, that `context` is **required** (no default), that the service neither holds nor constructs a `GenerationPreferences`, and that the source's value is forwarded unchanged into `planned` |
| `ProgramGenerationFlowArchitectureTest.theServiceSpeaksInTheDomainsOwnDraftValues` | read `(ProgramEditorDraft, Set)` by equality | filters the compiler's `Continuation` out and still asserts `(ProgramEditorDraft, Set)` **by equality**, for all three entry points, plus that each is `suspend`; the return-type claim moved from the JVM `Object` (which `suspend` erases) to the declared Kotlin type in the source |
| `ProgramGenerationPreviewServiceTest.previewIsOneCallIntoTheSamePrivatePassRatherThanAPipelineOfItsOwn` | `parameterCount == 2` | asserts the exact parameter list including the continuation, **plus** three source-shape claims that are stronger than before: three delegations to `edit`, exactly one `preferencesFor` call in the file, exactly one `generationRequest` call in the file |
| `ProgramGenerationPreviewServiceTest.theServiceStillDeclaresNoPersistenceCollaborator` | banned `*Repository`/`*Dao` only; listed `GenerationPreferences` as a collaborator | same ban **widened** to `AppDatabase`/`Entity`/`Room`, `GenerationPreferences` replaced by `GenerationContextSource`, and a new assertion that the service does **not** hold `GenerationSessionHistory` |
| `GenerationPreviewArchitectureTest.theServiceStillDeclaresNoPersistenceCollaborator` | identical shape, UI-side copy | same two revisions as above |

New suites: `ProgramGenerationContextTest` (18), `ProgramGenerationContextStorageTest` (8, over real
SQLite), `ProgramGenerationContextSnapshotTest` (7), `ProgramGenerationContextArchitectureTest` (17).

---

## 8. Recorded gaps — what P27 could not honestly fill

These are **intentional neutral inputs**, not failures. Each is a fact production does not have, and each
is now pinned by a test so that filling it later is a deliberate revision.

1. **No user exercise preference.** No persisted, user-authored ordering exists. Filling this needs a
   real source (a user-facing preference surface, or a persisted ordering) — not an inference from draft
   content.
2. **No adaptive generation preference.** `currentExerciseId` is family-scoped progression state, not a
   selection preference. Filling this needs an explicit domain contract stating that a stored adaptive
   value *is* a generation preference — a decision, not a derivation.
3. **No focus exposure.** Requires a stored link between a performed occurrence and the focus assignment
   it was loaded with. Neither `program_exercise` nor `session_exercise` nor `session_snapshot_exercise`
   carries a focus. Filling this needs a schema decision (persist the focus on the element), which P27
   explicitly does not make.
4. **No focus load.** Same missing link; and once the link exists, only *sets* may be aggregated per
   focus — never repetitions, seconds, or a collapsed `LoadProfile` scalar.
5. **No recovery context for generation.** `RecoveryContext` belongs to the adaptive stage's per-window,
   per-family judgement. A generation-scoped recovery context would be new recovery semantics, which is
   outside P27's scope boundary.
6. **No exposure/load for a *new* Program.** Neutral by construction: there is no history to read, and
   the read is not even issued.

---

## 9. Scope boundary — what P27 does NOT do

No new adaptive engine · no new adaptive decisions · no new recovery heuristics · no Focus Planner
rewrite · no Exercise Selector rewrite · no Plan Reconciler rewrite · no new persistence · no new
revision · no scheduler changes · no session-runtime changes · no legacy removal · no UI change beyond the
minimal wiring that passes the existing context source · no automatic recommendations.

---

## 10. Verification

Measured on `feat/program-stage27-generation-context`, base `ecb3702`:

| | |
| --- | --- |
| full JVM (fresh) | **305 classes / 3006 tests / 0 failures / 0 errors / 0 skipped** |
| baseline before this branch | 301 / 2953 / 0 / 0 / 0 |
| RED mutations | `scripts/program-stage27-red-mutations.sh` — see §11 |

### Claim → test

| claim | test |
| --- | --- |
| context scoped to one Program | `ProgramGenerationContextTest.theContextIsScopedToTheDraftsOwnProgram`, `…anAllProgramsAggregationIsNeverAssembled` |
| another Program's history never enters | `…anotherProgramsHistoryNeverEntersTheRequest`, `ProgramGenerationContextStorageTest.twoProgramsInOneDatabaseNeverSeeEachOthersHistory` |
| a new Program gets neutral context, with no read | `…aNewProgramGetsTheNeutralContextAndIsNeverReadAtAll` |
| recent exercises most-recent-first, once each | `…recentExercisesAreOrderedMostRecentFirst`, `…aRepeatedExerciseKeepsItsMostRecentPositionAndIsStatedOnce`, `StorageTest.theOrderingSurvivesTheRoundTripThroughStorage` |
| skipped / missed / non-executed is not exposure | `…skippedAndUnperformedOccurrencesDoNotBecomeExposure`, `…aSkippedOccurrenceIsNotAZeroExposure`, `StorageTest.aSkippedOccurrenceStoredAsSkippedIsNotReported` |
| exposure is not derived from the session count | `…exposureIsNotDerivedFromTheSessionCount` |
| load counts sets only, no cross-dimension conversion | `…loadIsNotDerivedFromRepetitions`, `…noCrossDimensionScalarConversionReachesTheRequest` |
| no fabricated adaptive preference | `…aStoredFamiliesCurrentExerciseIsNeverReadAsAGenerationPreference`, `ArchitectureTest.theContextSourceNamesNoAdaptiveStateAtAll` |
| recovery stays UNKNOWN | `…recoveryIsNeverDerivedFromElapsedTime`, `ArchitectureTest.theContextSourceStatesNoRecoveryAndNoFocusDerivedSignal` |
| missing is never zero | `…aProgramWithNoHistoryStatesAbsenceRatherThanZero`, `ArchitectureTest.theSourceTurnsMissingIntoAnExplicitNeutralValueAndNeverAZero`, `StorageTest.aProgramWithStoredRowsButNoPerformedSetGetsTheNeutralContext` |
| Generate and Preview receive equal preferences from equal state | `ProgramGenerationContextSnapshotTest.oneGenerateReadsTheContextExactlyOnce`, `…onePreviewReadsTheContextExactlyOnce`, `…generateAndPreviewOverTheSameSourceStateSeeTheSameSnapshot`, `StorageTest.generateAndPreviewOverTheSameStoredStateSeeTheSameFacts` |
| one coherent snapshot per operation | `SnapshotTest.thePlanIsBuiltFromTheSnapshotThatWasReadAndNotFromAnother`, `…threeOperationsReadThreeSnapshotsAndNotOneCachedValue`, `…aSourceThatChangesBetweenOperationsIsHonouredRatherThanCached`, `ArchitectureTest.theServiceReadsTheContextExactlyOnceInsideTheSharedPass` |
| the context source performs no writes | `StorageTest.theContextSourceWritesNothing` (whole-schema row-count sweep), `ArchitectureTest.theHistoryPortCanOnlyRead`, `…theContextSourceHoldsExactlyOneCollaboratorAndItIsTheReadPort` |
| generated domain untouched | `ArchitectureTest.thePureGeneratedPackageGainedNoFileAndReachesNothingNew`, `…theGeneratedPreferencesTypeIsUnchanged`, `…theRequestTypeGainedNoFieldForThisStage` |
| no second request-assembly path | `ArchitectureTest.allThreeEntryPointsRemainOneSharedPass`, `ProgramGenerationPreviewServiceTest.previewIsOneCallIntoTheSamePrivatePassRatherThanAPipelineOfItsOwn` |
| the service reaches no storage | `ArchitectureTest.theServiceHoldsNoRepositoryAndNoStorageStill`, `…theServiceStillDeclaresNoPersistenceCollaborator` (both copies) |

---

## 11. RED mutation evidence

`scripts/program-stage27-red-mutations.sh` — one row per architecturally dangerous place, each with a
real behavioural or architectural oracle:

| row | mutation | oracle |
| --- | --- | --- |
| 1 | remove Program scoping (read every Program) | context + storage suites |
| 2 | substitute another Program's history | context + storage suites |
| 3 | turn missing into zero | context suite + the `?:` gate |
| 4 | derive exposure from the workout count | context suite |
| 5 | derive load from repetitions | context suite |
| 6 | fabricate an adaptive preference from `currentExerciseId` | context suite + the adaptive-token gate |
| 7 | fabricate recovery from elapsed time | context suite + the recovery-token gate |
| 8 | make Preview and Generate use different context | snapshot suite + the single-read gate |
| 9 | add a repository/DAO dependency to the context source | architecture suite |
| 10 | make the context source write | the whole-schema row-count sweep |
| 11 | silently replace `UNKNOWN` with `FAVORABLE`/`CAUTIOUS` | context suite + the recovery-token gate |
| 12 | touch the pure generated package | the eight-file list gate |
| 13 | add a second request-assembly path | the single-assembly gate |

Run result: **control GREEN · caught 13 · missed 0 · not-a-catch 0 · byte-identical restoration** — the
numbers as printed by the script, reproduced in the PR body.