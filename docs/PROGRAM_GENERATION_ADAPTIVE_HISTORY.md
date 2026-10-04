# P29 — Adaptive Generation History Attribution

§30 step 29. Base `37fef75` (the P28 merge, PR #330). Branch
`feat/program-stage29-adaptive-history`.

```text
GeneratedPlan → FocusAssignment (1 primary + 0..2 secondary)
        ↓  GeneratedSlot asserts one element per assigned focus, in order
GeneratedElement.focus                    ← the fact that already existed
        ↓  PlanReconciler — the ONE line P29 adds inside the pure package
ProgramExercise.focus: Focus?             ← nullable
        ↓  Save → program_exercise.focus   ← nullable, TEXT, no DEFAULT
        ↓  SessionRuntime.startSession → presentedWorkout (the existing boundary)
SessionSnapshotExerciseEntity.focus       ← a COPY, frozen at start
        ↓  restore reads the snapshot and nothing else (§19)
GenerationContextSource
        ↓
recentExposureByFocus   performed occurrences, per recorded focus
recentLoadByFocus       confirmed SETS, per recorded focus
```

---

## 1. The stage in one paragraph

P27 filled **one** of §8's six plain signals and recorded five as intentional gaps. Two of those five were
the focus-keyed pair, and this stage closes them. The interesting part is not the two signals — it is
**where the missing link turned out to be**, because it was not where P27's record put it, and the
difference decided the entire shape of the change.

P27 wrote: *"no object on the way from a plan to a performed set carries a focus."* That was **true and
incomplete**. The focus *did* exist on that path — and was being thrown away one step into it.

No planner rule changed. `GenerationPreferences` gained no field. `GenerationRequest` gained no field.
§8's vocabulary is untouched. `adaptivePreferredExerciseIds` and `recovery` remain explicitly neutral,
and §5 and §6 below say why that is the correct answer rather than an unfinished one.

---

## 2. The audit finding, stated exactly

```text
GeneratedElement.focus existed transiently,
but PlanReconciler dropped it while creating ProgramExercise.

Therefore P29 includes one narrow, intentional exception
to the P23/P24 generated-package freeze:
PlanReconciler preserves the already-existing focus fact.

No new semantic inference is introduced.
```

`GeneratedElement.focus` is not a derived value. The Focus Planner assigns it, and `GeneratedSlot`
asserts at construction that:

```kotlin
require(elements.map { it.focus } == assignment.focuses)
```

So a slot's elements *are* its assignment, in order — the fact is already checked by the domain. Then
`PlanReconciler.asGeneratedElement` built a `ProgramExercise` and did not carry it across:

```kotlin
// before P29 — element.focus silently dropped
private fun GeneratedElement.asGeneratedElement(ids: DraftIdSource): ProgramExercise = ProgramExercise(
    programExerciseId = ProgramExerciseId(ids.newId()),
    exerciseId = exerciseId,
    prescription = prescription,
    origin = ProgramExerciseOrigin.GENERATED,
    isPinned = false
)
```

From that line onward the fact existed nowhere. `ProgramExercise`, `EffectiveExercise`,
`SessionExercise` and `SessionSnapshotExerciseEntity` all carried no focus, which is exactly what P27's
audit found and recorded.

### Why that changes the fix

```text
missing fact   → the only way forward is a proxy, i.e. an invention
discarded fact → the only way forward is to stop discarding it
```

The available proxy was `ProductionFocusClassification`: classify each performed exercise by what the
shipped catalogue says it trains. P27 refused it, and the refusal was right. It answers a **different
question** — *"what does this exercise train?"* — and reports it under the name of a field that asks
*"which focus was this occurrence assigned?"*. The two diverge in three ways that matter:

| | catalogue classification | a recorded assignment |
| --- | --- | --- |
| `burpees` | `PUSH + LEGS + CONDITIONING` | exactly one element, one focus |
| a manual program's exercise | whatever the catalogue says, if anything | **nothing was assigned** |
| after a regeneration | today's catalogue entry | what the user was shown *then* |

So P29 stores the fact instead. Nothing is inferred, and `ProductionFocusClassification` is
structurally unreachable from the context source — banned as a token in
`AdaptiveHistoryArchitectureTest.theContextSourceNeverReconstructsAFocusFromTheCatalogue`, and proved
behaviourally in `…focusIsNeverReconstructedFromTheExerciseCatalogue`, where two catalogue-classified
exercises (one of them deliberately three-focus) both come back as **absence**.

---

## 3. The one exception to the freeze, and how it is held

Every stage from P23 to P28 could assert *"the generated package is untouched"*. P29 cannot, because it
legitimately is not. What it asserts instead is stronger in the way that matters — **cardinality and
identity**, not absence:

```text
domain/program/generated
    frozen except:
        PlanReconciler.kt
            exactly one semantic responsibility:
            preserve GeneratedElement.focus when creating ProgramExercise
```

| gate | what it holds |
| --- | --- |
| `theGeneratedPackageStillHoldsExactlyItsEightFiles` | the closed list is **not** relaxed: no file added, none removed |
| `theReconcilersOnlyNewSemanticLineIsTheFocusCopy` | `focus = focus` appears **exactly once** in the file, on the `ProgramExercise` construction, reading the element's own property |
| `theOnlyFileThatMaterialisesAGeneratedElementIsTheReconciler` | `GeneratedElement(` appears in three files, all inside the package, and only `PlanReconciler` converts one into a stored element |
| `thePlannerAndTheSelectorAreUnchanged` | each planner unit named individually, plus `GenerationPreferences` still exactly six fields and `GenerationRequest` exactly seven |
| `theGeneratedPackageReachesNoPersistenceAndNoApplicationLayer` | no `Repository` / `Dao` / `AppDatabase` / `androidx` gained anywhere |

A blanket *"the package may change"* permission is deliberately **not** granted: from inside the package
it is indistinguishable from a second generation semantic. RED rows 17 and 18 exist for this alone — row 17
widens the approved line into an inference, row 18 changes a *different* file in the package — and both are
caught by the architecture gate.

---

## 4. Focus assignment semantics

§8's contract is used unchanged, and `Focus` is the existing vocabulary value, compared by identity — not
a new token type and not a string.

```text
one slot:      primary focus + 0..2 secondary focuses
one element:   corresponds to exactly ONE assigned focus, in assignment order
```

| claim | test |
| --- | --- |
| the plan element's focus survives materialisation | `theGeneratedElementsFocusSurvivesMaterialisation` |
| the **primary** is what is recorded | `thePrimaryFocusOfAnAssignmentIsTheRecordedFocus` |
| each **secondary** is recorded on its own element | `eachSecondaryFocusIsRecordedOnItsOwnElement` |
| **assignment order** survives into the presentation | `theAssignmentOrderSurvivesIntoThePresentation` |
| all seven values round-trip by identity | `everyFocusInTheVocabularySurvivesTheRoundTrip` |

### Primary and secondary handling

A secondary is recorded as a secondary — it is never folded into the primary. That is what makes "which
focus was this exercise for" have **one** answer per exercise rather than a set, and it is why
`recentExposureByFocus` can be counted per element without double-counting one assignment.

An **adjustment** does not reassign. §16 says an adjustment supersedes the *presentation* of an element;
it does not re-decide what the slot is for. `presentedWorkout` therefore takes the focus from the **day's**
element even when an adjustment supplies the exercise and the prescription:

```kotlin
// the day's element is the authority, even when an adjustment supplies the rest
val adjusted = byElement[element.programExerciseId]?.after
EffectiveExercise(
    programExerciseId = element.programExerciseId,
    exerciseId = adjusted?.exerciseId ?: element.exerciseId,
    prescription = adjusted?.prescription ?: element.prescription,
    focus = element.focus
)
```

This is not a detail. The stored adjustment's `after` value carries **no focus**, so reading the presented
element from the adjustment would silently produce a focus-less element — and §19 would then freeze that
absence into the snapshot **forever**. Pinned by `aStandingAdjustmentNeverErasesTheRecordedFocus` and
RED row 19.

---

## 5. Why both columns are nullable, with no default

```text
program_exercise.focus              TEXT NULL   ← MIGRATION_16_17, no DEFAULT
session_snapshot_exercise.focus     TEXT NULL   ← MIGRATION_16_17, no DEFAULT
```

| situation | stored | read back as |
| --- | --- | --- |
| a **generated** element | `PUSH` | `Focus.PUSH` |
| a **user-authored** or **pinned** element | `NULL` | `null` — the generator never assigned one |
| a row written before the column existed | `NULL` | `null` |
| a **manual** program | `NULL` | `null` |

**No default focus exists anywhere.** A default would stamp a claim — *this element trains something* —
onto every pre-existing row, including manual programs and elements the generator never assigned. That is
the same fabrication P27 refused, moved one layer down into storage, and §33's *no silent substitution*
applies to stored history exactly as it applies to a plan.

`null` means **no focus was ever recorded**, which is a different statement from *this element trains
PUSH*. Generation then reads it as **absence**, never as a zero:

* `anAbsentFocusIsStoredAsNullAndReadsBackAsAbsence` — the raw column, and then the context;
* `aProgramWhoseHistoryRecordedNoFocusStatesBothMapsEmpty` — six real confirmed sets performed, and two
  empty maps;
* `aRowThatPredatesTheColumnReadsAsAbsenceRatherThanADefault` — the upgrade case, written as an INSERT
  that omits the column, which is the only way to produce a genuine pre-P29 row.

---

## 6. The exact definition of "performed", and the load unit

### Performed

One occurrence is **exposure** when it has **at least one confirmed set** — the same rule
`ProgramAdaptiveIntegration.observationsOf` applies, and the one `ExposureObservation` states in its own
invariant (*"a skipped exercise or a missed slot produces no observation rather than a zero one"*).

Absent, as absence: a skipped occurrence, an occurrence with no confirmed set, a `MISSED` slot, a `PLANNED`
slot nobody attempted, and an occurrence whose snapshot recorded no focus.

A session's own `status` is **not** a filter. A cancelled or still-running attempt holds real confirmed
sets and §19 preserves that partial work, so dropping it would invent a rule no domain statement owns
(`aCancelledSessionWithConfirmedWorkStillContributesItsSets`).

### The load unit is a **set**

```text
PUSH → 5 sets
PULL → 2 sets
LEGS → absent          ← not 0
```

* sets only — `SetResult` rows, which is what §19 stores and §10 prescribes;
* repetitions are never summed: 4 sets of 12 reps is **4**, not 48;
* seconds are never summed: 2 timed sets of 45 s and 60 s is **2**, not 105;
* no scalar `LoadProfile` — it is family-scoped and multi-dimensional, and collapsing it to one number per
  focus would be a cross-dimension conversion rather than a measurement;
* partial execution contributes exactly the sets it confirmed, never the sets it was prescribed.

| claim | test |
| --- | --- |
| assignments, not workouts | `exposureCountsAssignmentsAndNotWorkouts` |
| one occurrence = one exposure, whatever its set count | `onePerformedOccurrenceContributesExactlyOneExposureHoweverManySetsItRan` |
| sets, never repetitions | `loadCountsConfirmedSetsAndNeverRepetitions` |
| sets, never seconds | `timedWorkContributesItsConfirmedSetsAndNotItsSeconds` |
| partial execution contributes confirmed sets only | `partialExecutionContributesOneExposureAndOnlyItsConfirmedSets` |
| each occurrence attributes to **its own** focus | `loadAndExposureAreKeyedByEachOccurrencesOwnRecordedFocus` |

The workout-count test deserves a note. Its first form used one PUSH per workout, which produced the *same*
map whether the implementation counted workouts or assignments — so it could not have failed a
workout-counting implementation at all. A focus trained **twice inside one session** is the smallest case
where the two readings diverge, and that is what the fixture now states.

---

## 7. Program and revision scoping

**Program scope is the scope of history.** The read is `sessionsOfProgram(programId)` and nothing else.
Two Programs in one database file never see each other's focus history —
`twoProgramsInOneDatabaseNeverSeeEachOthersFocusHistory`, over real rows, because a `WHERE` is where a
scope actually has to hold.

**Revision scoping is the harder half**, and it is why the second column exists:

```text
old Revision A session  ≠  reinterpreted through Revision B
```

A session's focus is read from **its own snapshot**, never from the live plan. There is no join to
`program_revision` in the read path at all, so a current revision *cannot* relabel old history — and
`aNewerRevisionCannotRelabelAnOlderSession` proves it against a real database containing both sessions,
with distinct start instants so "newer" is a fact rather than an accident of the session id.

A revision that re-presents the same exercise under a different focus records its own focus for its own
new session, and leaves the old one alone. Regeneration does not rewrite history any more than adjustment
does.

---

## 8. Why classification reconstruction is forbidden, permanently

Three independent gates, because the temptation grows the moment a focus-keyed signal becomes fillable:

| gate | mechanism |
| --- | --- |
| `theContextSourceNeverReconstructsAFocusFromTheCatalogue` | bans `ProductionFocusClassification`, `GenerationCandidate`, `focusesOf(`, `eligibleFocuses`, `WorkoutGenerator` … as tokens in the context file |
| `…focusIsNeverReconstructedFromTheExerciseCatalogue` | behavioural: `pullups` and `burpees` are catalogue-classified, and the source still states absence |
| `theGeneratedPackageReachesNoPersistenceAndNoApplicationLayer` | the pure package cannot reach the catalogue at all |

RED row 6 writes the real reconstruction — `ProductionFocusClassification.focusesOf(...)` into the context
source — and the behavioural suite catches it.

---

## 9. Why `adaptivePreferredExerciseIds` remains neutral

`FamilyProgressionState.currentExerciseId` is persisted, revision-scoped, and would produce a *plausible*
preference. It means **"the exercise this family is currently on"** — family progression state. No
existing contract makes it an exercise-selection preference for generation, and no ranking heuristic was
added to bridge the gap: inventing §9's precedence would be inventing a rule, not reading one.

So the field stays `emptyList()`, pinned by:

* `adaptivePreferredExerciseIds` is not read by the context source — the type is **not even named**
  (`theContextSourceNamesNoAdaptiveStateAtAll`);
* the answer is empty even over a deliberately rich history
  (`adaptivePreferenceRemainsNeutralAndIsNeverReadFromFamilyState`);
* RED rows 14 and 13 fabricate it, and both are caught.

This matters more than it looks: **P29 filled two of six signals, and the correct response to that is to
leave the other four alone.** A filled signal proves the *pipeline* works; it says nothing about whether the
unfilled ones have owners.

---

## 10. Why `recovery` remains `UNKNOWN`

`RecoveryContext` is produced by the adaptive stage's own `AdaptiveJudgementRule` for **one decision
window of one family**, and that rule itself receives `UNKNOWN` as its documented absence. Taking the
latest recovery decision and promoting it into global Program generation context would convert a
per-family, per-window judgement into a Program-wide fact — a different claim, made by no owner.

No substitute was used: not elapsed time, not time since the last workout, not `currentExerciseId`. The
audit found **no generation-scoped recovery contract**, so `recovery` stays `RecoveryContext.UNKNOWN`, and
`AdaptiveHistoryArchitectureTest` keeps P27's ban on `FAVORABLE` and `CAUTIOUS` in the context source.

`AdaptiveTarget.Focus(focusId)` does exist and is *focus*-scoped, but it is a target for an **adaptive
change**, not a generation-scoped recovery reading — so it is not an owner either.

RED row 15 fabricates `FAVORABLE`/`CAUTIOUS` from "the user trained recently" and is caught.

---

## 11. Snapshot correctness

P27's invariant is unchanged and re-pinned:

```text
one generation operation → one context snapshot → one GenerationRequest
```

`ProgramGenerationService` still holds **five** collaborators, its `context` is still **required**, and
`generate` / `regenerate` / `preview` still delegate to the same private `edit` with the preferences
passed down as a parameter. P29 needed no new dependency at all: the focus arrives through the context the
service already reads. RED row 12 re-reads the context inside one pass and is caught.

| claim | test |
| --- | --- |
| Generate / Preview / regenerate each read one snapshot | `ProgramGenerationContextSnapshotTest` (8), re-pinned by `theServiceStillReadsTheContextExactlyOnceInsideTheSharedPass` |
| equal stored history → equal focus facts | `equalStoredHistoryProducesEqualFocusFacts` |
| a later revision does not change historical attribution | `aNewerRevisionCannotRelabelAnOlderSession` |
| one performed set contributes to exactly the recorded focus | `loadAndExposureAreKeyedByEachOccurrencesOwnRecordedFocus` |
| partial execution contributes only confirmed sets | `partialExecutionContributesOneExposureAndOnlyItsConfirmedSets` |
| one snapshot boundary, no repair pass | `theSessionSnapshotIsStillTheOnlyProducerOfTheHistoricalPresentation` |

The boundary test asserts there is **one** production construction site
(`SessionRuntime.startSession`) and that the only other place one is built is the mapper's read, which is
the same boundary traversed backwards. No post-hoc repair: the focus is captured when the presentation is
composed, and never written afterwards — which `theContextSourceWritesNothing` also proves, by sweeping
the whole schema's row counts.

---

## 12. Claim → test

| claim | test |
| --- | --- |
| the discarded fact is now kept | `AdaptiveFocusAttributionTest.theGeneratedElementsFocusSurvivesMaterialisation` |
| primary / secondary / order each survive | `…thePrimaryFocusOfAnAssignmentIsTheRecordedFocus`, `…eachSecondaryFocusIsRecordedOnItsOwnElement`, `…theAssignmentOrderSurvivesIntoThePresentation` |
| an adjustment never reassigns | `…anAdjustmentChangesWhatIsShownAndNeverWhichFocusItServes`, `…aStandingAdjustmentNeverErasesTheRecordedFocus` |
| absence is never a default | `…anElementWithNoAssignmentRecordsNoFocus`, `AdaptiveHistoryStorageTest.anAbsentFocusIsStoredAsNullAndReadsBackAsAbsence`, `…aRowThatPredatesTheColumnReadsAsAbsenceRatherThanADefault` |
| focus survives storage exactly | `…theRecordedFocusSurvivesTheRoundTripThroughStorage`, `…everyFocusInTheVocabularySurvivesTheRoundTrip` |
| exposure = assignments, load = sets | `AdaptiveHistoryContextTest.exposureCountsAssignmentsAndNotWorkouts`, `…loadCountsConfirmedSetsAndNeverRepetitions`, `…timedWorkContributesItsConfirmedSetsAndNotItsSeconds` |
| not performed = absent | `…aSkippedOccurrenceIsNotAZeroExposure`, `…anOccurrenceWithNoConfirmedSetContributesNothingAtAll` |
| cancelled / in-progress still count their sets | `…aCancelledSessionWithConfirmedWorkStillContributesItsSets`, `…anInProgressSessionWithConfirmedWorkContributesToo` |
| missing ≠ zero | `…anUnrecordedFocusIsAbsentAndNeverZero`, `…aProgramWhoseHistoryRecordedNoFocusStatesBothMapsEmpty` |
| no reconstruction | `…focusIsNeverReconstructedFromTheExerciseCatalogue`, `AdaptiveHistoryArchitectureTest.theContextSourceNeverReconstructsAFocusFromTheCatalogue` |
| Program isolation | `…twoProgramsNeverSeeEachOthersFocusHistory`, `AdaptiveHistoryStorageTest.twoProgramsInOneDatabaseNeverSeeEachOthersFocusHistory` |
| revision cannot relabel history | `…aCurrentRevisionCannotRelabelAnOlderSession`, `…aNewerRevisionCannotRelabelAnOlderSession` |
| the snapshot is the only source | `…theSnapshotElementIsTheOnlySourceOfTheHistoricalFocus` |
| one read per operation | `AdaptiveHistoryArchitectureTest.theServiceStillReadsTheContextExactlyOnceInsideTheSharedPass` |
| service gained no collaborator | `…theServiceGainedNoCollaboratorAndReachesNoStorage` |
| adaptive preference neutral | `…adaptivePreferenceRemainsNeutralAndIsNeverReadFromFamilyState`, `…theContextSourceNamesNoAdaptiveStateAtAll` |
| recovery `UNKNOWN` | `…recoveryRemainsUnknownEvenWithACompleteHistory` |
| the freeze holds, narrowly | `…theGeneratedPackageStillHoldsExactlyItsEightFiles`, `…theReconcilersOnlyNewSemanticLineIsTheFocusCopy`, `…theOnlyFileThatMaterialisesAGeneratedElementIsTheReconciler`, `…thePlannerAndTheSelectorAreUnchanged` |
| two columns, no third, no default | `…theHistoricalFocusIsStoredInTwoPlacesAndNoOthers`, `…theNullableFocusHasNoDefaultAnywhereInTheDataLayer` |
| migration shape | `ProgramSchemaTest.theHistoricalFocusMigrationAddsTwoNullableColumnsAndNoDefault`, `…theHistoricalFocusColumnsAreNullableAndNeverBackfilled` |

---

## 12a. A test-harness finding worth recording

P29 hit a failure mode that is worth writing down, because it is invisible by construction and it would
have made the whole stage look green while proving nothing.

The repository suites do not use Room. They use hand-written SQLite DAOs in `ProgramTestDoubles.kt`, and
their **row mappers build each entity field by field**:

```kotlin
private fun Map<String, String?, String?>.exerciseEntity() = ProgramExerciseEntity(
    programExerciseId = text("programExerciseId"),
    /* … */
    origin = text("origin"),
    isPinned = flag("isPinned")
)
```

A mapper that omits a **nullable** column does not fail. It defaults. `ProgramExerciseEntity.focus` would
have come back `null` for every row on every read, so:

* `program_exercise.focus` would have been written correctly and read back as absent, always;
* every snapshot test would still pass, because the snapshot is composed in-process and never re-read
  from this path;
* and the suite would have reported "no focus was ever recorded" as though that were the truth.

The only thing that exposed it was a RED row asserting the **plan** column specifically, and then a probe
that printed the three stages side by side:

```text
domain focuses:  [PUSH, LEGS, PUSH, LEGS, PUSH]
raw rows:        [{…, focus=PUSH}, {…, focus=LEGS}, …]
joined row:      {…, isPinned=0, focus=PUSH}
read back:       [null, null, null, null, null]
```

`nullableText` was added beside the existing `text`, which **refuses** a missing column — so the two now
differ in exactly the direction that matters, and the mappers that carry a nullable focus are pinned by
`ProgramDataAccessArchitectureTest`'s existing "every field" convention.

The general rule, which is why it is in this document rather than in a commit message:

> **a test double that binds fields by name must be updated when an entity gains a field, and the only
> safe way to find out is a mutation that removes the write.** A new column with no reader is
> indistinguishable from a new column that is never read.

---

## 13. RED mutation evidence

`scripts/program-stage29-red-mutations.sh` — 19 rows, one per architecturally dangerous place.

| row | mutation | oracle |
| --- | --- | --- |
| 1 | the reconciler stops preserving the focus | focus suite |
| 2 | a secondary focus is recorded as the primary | focus suite |
| 3 | the plan element's focus is not persisted | storage suite |
| 4 | the snapshot's focus is not persisted | storage suite |
| 5 | the current revision is read instead of the snapshot | context suite |
| 6 | the focus is reconstructed from the catalogue | context suite |
| 7 | workouts are counted instead of assignments | context suite |
| 8 | repetitions are counted instead of sets | context suite |
| 9 | seconds are counted as though they were sets | context suite |
| 10 | an absent focus becomes a zero | context suite |
| 11 | another Program's history enters the context | context suite |
| 12 | the context is re-read during one pass | snapshot suite |
| 13 | a repository is injected into the service | P27 architecture gate |
| 14 | an adaptive preference is fabricated from family state | context suite |
| 15 | recovery is fabricated from elapsed time | context suite |
| 16 | the focus column is given a `DEFAULT` | schema suite |
| 17 | **the reconciler also infers a focus** | architecture gate |
| 18 | **another generated-package file is changed** | architecture gate |
| 19 | a standing adjustment erases the recorded focus | focus suite |

Rows 17 and 18 are the ones that make the narrow exception auditable. Without them a widened freeze would
pass every other row.

Two rows were **rewritten rather than kept** after a first run, and both for the same reason the script
exists — they were charging a compile error as evidence:

* row 10 first wrote the fabricated zeros with `keys`, which is not in scope on a `Map<Focus, Int>`
  receiver. The tree stopped compiling, so the row was scored **NOT A CATCH** and rewritten to build the
  map explicitly and add each absent focus with `containsKey`. Verified independently: it compiles, and it
  fails 7 tests in the context suite.
* row 12 first targeted a call site that no longer existed (`preferences = …`, P27's shape rather than
  P28's), so the mutation did not apply and the script **aborted** rather than scoring a verdict. Rewritten
  against the real call site; verified independently to compile and to fail 5 tests in the snapshot suite.

An oracle that scores its own broken rows is worse than no oracle, so both rewrites are recorded here
rather than quietly folded in.

A **third** row was caught by the oracle's own blind spot, and it is the one worth remembering. Row 14
(fabricating an adaptive preference from family state) pointed at the behavioural suite, which **passed
on the mutation** — not because the fabrication was invisible, but because the fabricated table it reads
is keyed by exercise ids those fixtures do not use, so the answer came out `emptyList()` anyway. A
behavioural oracle cannot distinguish *"this field is neutral because nothing was read"* from *"this
field is neutral because the write was neutralised by an unrelated coincidence"*.

So the claim had to move to the level it is actually made at: §9's precedence must not be **invented**,
not merely happen to come out empty on today's fixtures. `theContextSourceNeverFillsTheTwoSignalsThatStayNeutral`
bans the *write* — no line in the context source may assign `adaptivePreferredExerciseIds =` or
`recovery =` at all — and row 14 now targets the architecture gate. It catches on the write, on every
fixture, forever.

The same reasoning retired a weaker version of the gate that P27 had: asserting `GenerationPreferences`
still *declares* six fields notices nothing about whether a source fills one of them.

---

## 14. Scope boundary — what P29 does NOT do

No new adaptive engine · no new adaptive decisions · no ranking heuristic · no recovery policy · no Focus
Planner / Exercise Selector / GeneratedPlanner change · no new `GenerationPreferences` field · no new
`GenerationRequest` field · no new table · no new repository · no new DAO · no change to FK or cascade
semantics · no new Program lifecycle model · no scheduler change · no session-runtime change beyond the
existing snapshot boundary · no legacy removal · no UI change · no export/import field.

### Still true after P30

**P30** (`docs/PROGRAM_ADAPTIVE_FAMILY_CLASSIFICATION.md`) later supplied §9's exercise→family
classification on the *adaptive* side, by reading the family each shipped catalogue exercise already
states. That closes one gap **one layer above this one** and therefore changes nothing recorded here:

* `adaptivePreferredExerciseIds` is **still** `emptyList()`, and the reason §3 gives is unchanged — a
  family membership is not a ranking, and knowing *which family* an exercise is in says nothing about
  whether the user's own configuration prefers it. P30 read a catalogue field; it did not read a
  preference, and it added no ranking to bridge the difference.
* `recovery` is **still** `RecoveryContext.UNKNOWN`. `RecoveryContext` comes from the adaptive stage's
  per-window, per-family judgement, and that judgement cannot run in production while the ladder is
  undeclared — which is still true after P30.

---

## 15. Verification

| | |
| --- | --- |
| full JVM (fresh, `--rerun-tasks`) | **315 classes / 3129 tests / 0 failures / 0 errors / 0 skipped** |
| baseline before this branch | 301 / 2953 / 0 / 0 / 0 |
| new tests | 56 across four suites (9 focus · 21 context · 10 storage · 16 architecture) |
| RED mutations | `scripts/program-stage29-red-mutations.sh` — see §13 |