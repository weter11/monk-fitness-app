# Program System — the Generated Planner, the Focus Planner and regeneration (PR 10)

Scope: **§30 step 10**, *Generated Planner / Editor*. A new, pure, deterministic planner that turns a
Program's configuration into a plan, a Focus Planner that decides what each slot of that plan is
**for**, and §7's regeneration as **reconciliation rather than replacement** — plus the domain and
persistence changes the new Goal/Focus structural facts require.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below
as **§N**). Companions: `docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1), `docs/PROGRAM_ROOM_SCHEMA.md`
(PR 2), `docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md` (PR 2.1), `docs/PROGRAM_DATA_ACCESS.md`
(PR 3), `docs/PROGRAM_COMPOSITION_ROOT.md` (PR 4), `docs/PROGRAM_LIFECYCLE.md` (PR 5),
`docs/PROGRAM_MANUAL_EDITOR.md` (PR 6), `docs/PROGRAM_SCHEDULER.md` (PR 7),
`docs/PROGRAM_SESSION_RUNTIME.md` (PR 8), `docs/PROGRAM_PROGRESS_HISTORY.md` (PR 9).

This stage is **generation, focus planning and reconciliation**. It is *not* the Adaptive Engine, the
Adaptive Policy, the Aggregate Load Guard, adaptive decision persistence, adaptive progression state,
import/export, share, UI wiring or legacy removal: §30 steps 11–15 own those, and §30 step 10 is
deliberately **before** the Adaptive Engine so that the input boundary the later stage will plug into
exists without any of its policy existing here.

---

## 1. The graph

```text
ProgramEditorDraft                                   ← PR 1, extended with `focus`
     ↓ GeneratedPlanner.plan(request)                ← this PR: pure, no identity, no persistence
GeneratedPlan
     ↓ FocusPlanner.allocate(...)                    ← §8's Focus Planner, over the whole horizon
     ↓ ExerciseSelector.select(...)                  ← §9's selection order
     ↓ PlanReconciler.reconcile(draft, plan, ids)    ← §7's reconciliation, four levels of authority
generated ProgramEditorDraft
     ↓ ProgramEditorService.save()                   ← PR 6, unchanged: the ONLY persistence boundary
immutable ProgramRevision                            ← now carrying its Goals & Focus configuration
```

```text
AppContainer                        ← unchanged: nothing consumes generation yet, so nothing is wired
ProgramEditorService                ← unchanged (PR 6) apart from carrying `focus` through a save
ProgramDraftEditor                  ← extended with `withFocus`: Goals & Focus is an ordinary draft fact
```

| file | layer | responsibility |
| --- | --- | --- |
| `domain/program/FocusPlan.kt` | pure domain | §8's Goal and Focus vocabulary, and the three forms a configuration can take |
| `domain/program/generated/GenerationPolicy.kt` | pure domain | every number §8/§9 leave open, each an explicit owner decision |
| `domain/program/generated/GenerationRequest.kt` | pure domain | the planner's input: candidates, equipment, plain signals, policy |
| `domain/program/generated/GeneratedPlan.kt` | pure domain | the planner's output: slots, assignments, elements, what could not be planned |
| `domain/program/generated/FocusPlanner.kt` | pure domain | §8: 1 primary + 0–2 secondary focuses per slot, over the whole horizon |
| `domain/program/generated/ExerciseSelector.kt` | pure domain | §9's selection order, as a total deterministic ranking |
| `domain/program/generated/GeneratedPlanner.kt` | pure domain | the pipeline: focus allocation, then one element per assigned focus |
| `domain/program/generated/PlanReconciler.kt` | pure domain | §7's four levels, applied per day |
| `domain/program/generated/ProgramGeneratedEditor.kt` | pure domain | `generate` / `regenerate`: draft in, next draft out |
| `domain/program/ProgramStructure.kt` | pure domain | the Goals & Focus configuration as structural content (`FOCUS`) |
| `domain/program/ProgramRevision.kt`, `ProgramEditorDraft.kt` | pure domain | the configuration travels with the plan |
| `domain/program/ProgramDraftEditor.kt` | pure domain | `withFocus` — a draft's working configuration |
| `data/model/ProgramRevisionEntity.kt` | data | two appended, nullable, defaultless columns |
| `data/mapper/PlanMappers.kt` | data | the configuration ⇄ its stored discriminator and tokens |
| `data/local/AppDatabase.kt` | data | version 10, `MIGRATION_9_10` |

## 2. Goals and Focus (§8, §6)

```text
Goal          BALANCED | FOCUSED | CUSTOM           — exactly three, no fourth mode
Focus         PUSH | PULL | LEGS | CORE | MOBILITY | POSTURE | CONDITIONING — exactly seven
CUSTOM share  whole percentages that sum to exactly 100%
```

A configuration is one value (`FocusPlan`) whose **form is its goal**, so the goal is derived and never
stored twice:

| form | what the user stated | what the planner may do |
| --- | --- | --- |
| `Balanced` | *"spread it over everything"* — no share | every focus of the vocabulary is eligible, with the planner's own even spread |
| `Focused(focuses)` | *"train these"* — no share | only the named focuses are eligible |
| `Custom(allocations)` | *"here is every share"* | the stating focuses are eligible, at the user's own percentages |

Three rules are enforced at construction, so an invalid configuration cannot be represented, let alone
saved: a `CUSTOM` configuration's percentages sum to exactly `FocusPlan.FULL_ALLOCATION`; a focus is
named or allocated at most once; and a configuration's focuses are held in the vocabulary's own
canonical order, so equality between two equal configurations cannot depend on the order a caller
built them in.

Two deliberate absences are worth stating, because both are places where it would have been easy to
invent something:

* **No share is invented for the two forms that state none.** The alternative — storing percentages for
  every goal, with defaults for `BALANCED` and `FOCUSED` — was rejected: a default share is a number
  the user never chose, presented as if they had (§33).
* **No ranking is modelled inside `FOCUSED`.** Naming the focuses a plan is built around *is* §8's
  *"explicit priority"*; a rank order between them is not stated by the blueprint and is not invented
  here. A future ranked form would be an additive form, not a re-reading of this one.

## 3. The planner's contract

`GeneratedPlanner.plan(request)` is a pure function from one value to another.

```text
GenerationRequest                              GeneratedPlan
    focus: FocusPlan                               focus: FocusPlan          what it was built for
    schedule, duration                             slots: GeneratedSlot[]    one per plan day, in order
    candidates: GenerationCandidate<E>[]           limitations: …            what could NOT be planned
    availableEquipment: Set<E>
    preferences: GenerationPreferences
    policy: GenerationPolicy
```

Per slot: `FocusAssignment(primary, secondary)` with **one primary and 0–2 secondary focuses** (§8),
and **one element per assigned focus, in the assignment's own order** (primary first) — so a slot's
focuses describe the workout rather than labelling it.

The input admits none of the following, and not by convention: there is no Room type, no DAO, no
repository, no ViewModel, no Android type, no `Context`, no resource, no library port, no clock, no
random source and no legacy `WorkoutGenerator` anywhere in the package — and, for the same reason, no
**date**: a plan is a number of days and what each is for, and which dates those become stays the
Scheduler's decision (§20).

Two facts the planner needs are supplied explicitly rather than inferred, because §6 of the stage's
brief is right that they cannot be inferred honestly:

* **an exercise's focus membership** (`GenerationCandidate.focuses`) — the library's own
  classification, supplied by the caller. Nothing here infers a focus from a `ProgramDayType`, a
  category name or an exercise's presence in a list.
* **the dimension a generated prescription is written in** (`GenerationCandidate.dimension`) — §10's
  per-element progression dimension. The three dimensions §10 names without a subtype make an exercise
  *unusable for generation*: reported, never prescribed as something the domain cannot mean.

### What could not be planned is reported, not worked around

`GeneratedPlan.limitations` names every focus of the configuration that no usable exercise can plan —
`NO_EXERCISE_TRAINS_THE_FOCUS`, `EVERY_EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT` or
`PRESCRIPTION_DIMENSION_NOT_IMPLEMENTED` — plus `NoPlannableFocus` when that leaves nothing at all.
Allocation runs only over the focuses that are plannable, so a hard constraint is honoured by the
plan's own shape: there is no path by which an exercise the equipment forbids, or a dimension the
domain cannot state, reaches an element.

## 4. Determinism (§9)

```text
same request  ⇒  identical plan, in any process, on any device, in any order
```

The pass reads no clock, no random source, no hash and no mutable state: it is integer arithmetic
rolled forward by `fold` over an immutable account, and its only tie-break is the **canonical order**
(`Focus.entries` for focuses, the exercise id ascending for exercises). A caller's collection order
cannot influence the result, which is asserted directly — the same request with the library view
reversed, and with every candidate's focus membership built in the opposite order, produces an equal
plan.

### The allocation arithmetic, in one place

```text
weight(focus)     BALANCED → the same weight for every plannable focus
                  FOCUSED  → the same weight for the named focuses, none for the others
                  CUSTOM   → the user's own percentage, verbatim
need(focus, t)    weight(focus) · t  −  covered(focus) · total weight
```

`t` is the number of the assignment being made, counted across the whole plan, and `covered` is the
exposure the focus has already had — what the caller supplied **plus** the assignments this pass has
already made. The focus with the largest `need` is chosen; the largest-deficit-first rule gives §8's
three considerations without a special case for any of them:

* **target deficit** — a focus the plan owes more of has a larger `need`, and one that has already had
  more than its share has a *negative* `need` and is left alone;
* **recent exposure** — because `covered` includes what the caller reported, exposure that already
  happened suppresses further allocation;
* **remaining horizon** — because `t` counts across the whole plan, three slots and twelve slots
  produce different plans from the same configuration.

A secondary focus is taken only while its deficit is **strictly positive**, which is what makes `0`
secondary focuses a real outcome rather than a special case: once every plannable focus has had its
share, a slot is built around one focus instead of stacking more.

### The soft rules, and why they are soft

Two inputs may *demote* the focus the arithmetic would choose: a focus that led a slot within
`recoveryWindowSlots` back, and a focus whose recently performed set count has reached
`recentLoadThreshold`. Both demote it **only while another eligible focus still needs exposure**; when
none does, the demoted focus is chosen anyway. That fallback is the whole difference between a
preference and §14's forbidden universal rule: a rule with no fallback would make a focus impossible
on two consecutive slots whatever the plan owed, i.e. a 48-hour rule expressed in slots. The recovery
*context* narrows how many secondary focuses a slot may take (`CAUTIOUS` → at most one) and is the only
effect it has: it never moves a workout, changes a frequency or duration, replaces a focus or cancels
anything.

## 5. Exercise selection (§9)

```text
user choice  >  hard execution constraints  >  adaptive preference
   >  progression need  >  recency/diversity  >  deterministic tie-break
```

| §9 level | how it is honoured |
| --- | --- |
| user choice | `GenerationPreferences.userPreferredExerciseIds` — the first comparison. The request states *user choice* and *adaptive preference* as two separate lists, so "the user outranks the adaptive layer" is a fact of the input rather than an assumption of the code. |
| hard execution constraints | already applied: selection is only ever asked for the candidates whose requirements are a subset of the available equipment *and* whose dimension is prescribable. A constraint that has already removed a candidate cannot be violated later. |
| adaptive preference | `GenerationPreferences.adaptivePreferredExerciseIds` — the second comparison. |
| progression need | **not modelled in this stage.** Progression is the Adaptive Engine's own result (§30 step 11); inventing one here would be implementing the stage this one is deliberately before. Nothing is placed above it and nothing pretends to be it. |
| recency / diversity | three comparisons: how often the exercise is already used *in this cycle*, how often its *family* is, and how recently it was used at all (`recentExerciseIds`, "most recent first" read backwards so that the *least* recently used comes first and an exercise never used comes first of all). |
| deterministic tie-break | the canonical exercise id, ascending. |

Selection never mutates a library definition and never resolves a resource: it reads the candidates it
was given and returns one of them. Repeated use is allowed, and every occurrence becomes its own plan
element with its own identity.

## 6. Regeneration is reconciliation (§7)

```text
PINNED  >  EXPLICIT USER OVERRIDE  >  COMPATIBLE USER CHANGE  >  PURE GENERATED CONTENT
```

The one sentence `PlanReconciler` implements:

```text
the plan keeps everything the user owns, and may change only what the generator produced
```

| level | what happens | why |
| --- | --- | --- |
| pinned | kept where it is: day, place, prescription, pin and identity. A pinned element the plan disagrees with is **kept and reported as a conflict**, never acted on. | §7's highest level, with no exception |
| explicit user override | kept for the same reasons, whether or not the plan agrees with it; agreement decides only how it is *reported*. | §7's *"never silently replace"*, and its *"an element the user modified inside a Generated Program must be treated as user-authored"* |
| compatible user change | kept, and it **stands in** for the plan element it agrees with, so nothing is added beside it. | §7's *"preserve the user's compatible choice instead of replacing it merely because regenerated output differs"* |
| pure generated content | kept when it still says what the plan says (same exercise, same prescription) — **with its identity** — and replaced with a fresh identity only when it has to be. | §7's *"unchanged generated plan stays unchanged"*; re-minting an unchanged element would be a hidden whole-plan replacement, slowly |

Matching is one-to-one on what an element *says*, greedily in plan order, against **everything** the
day holds: two occurrences of one exercise are matched as two (§9), an occurrence the plan does not
ask for is dropped, and a plan element nothing satisfies is added. The user's own content therefore
leads the day and the generator's follows around it — §8's *"establishes pinned/manual/fixed workouts
first; fills remaining slots around them"*, read one day at a time. A day the plan no longer fills is
removed (and reported as a day, with its identity); a day the plan adds is a **training** day, unnamed.

`generate` and `regenerate` are **the same reconciliation**, and that is an owner decision (§12.1):
§7's precedence has no exception for a user who pressed the other button, so which button a screen
shows cannot decide whose content survives. Both are pure: a request in, the next draft out, with the
plan and a report of what happened.

## 7. Boundaries

| boundary | how it holds |
| --- | --- |
| **adaptive input** | The planner consumes `recentExposureByFocus`, `recentLoadByFocus`, `recovery` and two preference lists, all plain values. It computes no evidence, no confidence, no progression, no recovery policy, no decision and no adjustment — the architecture suite forbids the adaptive types by name, and the only adaptive type in the package is §14's own `RecoveryContext` enum. |
| **scheduler** | No date, weekday, instant, slot or session is expressible in a request or a plan. The planner produces *days*; turning them into `ProgramWorkoutSlot` rows stays the Scheduler's decision (§20), and a generation is measured to create, move, re-point and cancel nothing. |
| **Save / revision** | `ProgramEditorService.save` remains the only persistence boundary and is unchanged apart from carrying `focus`. Generate and Regenerate create no revision, write no row of any table and move no pointer; one Save creates at most one revision; a no-op save creates none; a stale draft still saves relative to what is stored, with no `RevisionConflict`. |
| **identity** | A plan carries no identity at all: it is a value. Draft identities are minted when the plan becomes a draft, `Save` re-mints every one of them into the revision it writes, and an element reused across a regeneration keeps the handle it had. |
| **structure** | The Goals & Focus configuration is part of `ProgramStructure` (`FOCUS`), so changing the goal is a structural difference and therefore exactly one new immutable revision — while a rename, a description, a selection, a lifecycle move, a planned start date and an archive stamp keep their existing non-revision semantics. |

## 8. Persistence: version 9 → version 10

```text
version 9  →  version 10        MIGRATION_9_10, purely additive
```

Two columns are appended to `program_revision`, both nullable and both **without a default**:

| column | type | meaning |
| --- | --- | --- |
| `focusGoal` | `TEXT` (nullable) | the `Goal`, spelled as the domain enum member's own name; `NULL` reads as `BALANCED` |
| `focusTargets` | `TEXT` (nullable) | the focuses the configuration states: focus names for `FOCUSED`, `NAME:percent` entries for `CUSTOM`, `NULL` for `BALANCED` |

Why they exist: §6 lists *goals/focus* among the changes that create a revision, so a revision that
could not state its goal would not be the record of the plan it describes, and §8's `CUSTOM`
percentages would have nowhere to live. Why they are columns and not a table: a focus configuration is
a handful of tokens belonging to exactly one immutable revision, and a second row-shaped entity would
model a relation that does not exist.

Both are nullable because SQLite cannot append a `NOT NULL` column without a default, and a default is
exactly what must not be stated here: a revision written before this step chose no goal, and the
mapper reads that absence as §8's `BALANCED` — the configuration that **states nothing** — rather than
the schema claiming the user picked something. `NULL` is therefore a faithful reading and not a
fallback, and `MIGRATION_9_10` executes two `ALTER TABLE … ADD COLUMN` statements and nothing else: no
`UPDATE`, no `INSERT`, no row touched, no value invented.

The token form is the mapper's, deliberately, and not a `ProgramTypeConverters` method: Room already
declares a `List<String> ⇄ String` conversion for the session snapshot's applied adjustments, and a
second one would be ambiguous. The discriminator on the same row is what gives the tokens their
meaning, exactly as `prescriptionDimension` does for `perSetTargets` (§10). Reading is **strict in
both directions**: an unknown goal token, a `FOCUSED` row with no targets, a share token that is not
`NAME:percent` and a `CUSTOM` row whose shares do not sum to 100% are all invalid persisted data and
fail loudly on load, naming the column and the row.

## 9. What this stage does not do

* **No Adaptive Engine, Adaptive Policy, Aggregate Load Guard, adaptive decision persistence or
  progression state** (§30 steps 11–12). Adaptive signals are *inputs*; no adaptive decision is made.
* **No import/export, no share, no settings or navigation cleanup, no legacy removal** (§30 steps
  13–15).
* **No UI and no ViewModel.** Nothing consumes the planner yet, so the composition root was not asked
  to wire it: a graph node exists because something needs it (§26). The `Notes & Focus` screen is a
  later stage's work.
* **No slot creation.** The Scheduler still owns which dates a plan's days land on.
* **No second persistence model and no revision mechanism of its own.** `Save` is untouched.
* **No pruning of the legacy `WorkoutGenerator`.** It stays exactly what it is: the legacy engine, with
  no part in the new pipeline, and the architecture suite fails if a generated source so much as names
  it.
* **No focus distribution in Progress/History.** PR 9 recorded *focuses* among the vocabularies it did
  not own; this stage creates the vocabulary but deliberately does not change PR 9's measures. Wiring
  focus distribution into `ProgramProgressService` is a follow-up for the owner to schedule, and it is
  recorded here rather than done silently.

## 10. Verification

Baseline, on pristine `origin/main` (`f9d76c1`, tree `314e765`), measured in this tree before any
edit with `:app:cleanTest :app:testDebugUnitTest --rerun-tasks` and the JUnit XML `timestamp`s checked
against `date -u`:

```text
242 classes / 2147 tests / 0 failures / 0 errors / 0 skipped
```

After this change, on the committed bytes, forced fresh the same way:

```text
248 classes / 2245 tests / 0 failures / 0 errors / 0 skipped
```

— exactly `+6 classes / +98 tests`: the six new test classes (`FocusPlanTest`, `FocusPlannerTest`,
`GeneratedPlannerTest`, `PlanReconcilerTest`, `ProgramGeneratedEditorTest`,
`ProgramGeneratedArchitectureTest`) and the tests added to the schema, migration and mapper suites —
revised and extended rather than loosened, as §10 of the stage's brief requires.
`GeneratedPlannerRig` is a fixture and carries no `@Test`, which is why it is not in the count. The
counts are parsed from the JUnit XML and cross-checked against the sources:
`grep -rho '@Test' app/src/test/java | wc -l` = 2245 and `grep -rl '@Test' app/src/test/java | wc -l`
= 248, both equal to the parsed totals, with the XML `timestamp`s (`2026-09-19T13:20Z`) checked
against `date -u` (`13:23Z`).

```text
:app:cleanTest :app:testDebugUnitTest --rerun-tasks     2245 tests, 248 classes, 0F / 0E / 0S
:app:compileDebugKotlin                                 BUILD SUCCESSFUL
:app:compileDebugUnitTestKotlin                         BUILD SUCCESSFUL
:app:compileReleaseKotlin                               BUILD SUCCESSFUL
:app:compileReleaseJavaWithJavac                        BUILD SUCCESSFUL
:app:assembleDebug                                      BUILD SUCCESSFUL
```

(`lintVitalRelease` remains the known pre-existing failure — `res/values/themes.xml:2`'s
`ResourceCycle: Theme.Material3.DayNight.NoActionBar extends itself` and the `ExpiredTargetSdkVersion`
error — so the release evidence is `compileReleaseKotlin` + `compileReleaseJavaWithJavac` +
`assembleDebug`, exactly as the earlier stages recorded it. It is not a P10 failure.)

## 11. RED evidence

`scripts/program-generated-planner-red-mutations.sh` applies one mutation at a time to the *production*
sources, reruns the focused generated-planner suite, restores the file and proves the restoration by
`md5sum -c`. A rule is only proven if breaking it fails a test. Fourteen rules plus a control:

| mutation | expectation |
| --- | --- |
| the selection's deterministic tie-break is removed | caught |
| the focus allocation's tie-break is removed | caught |
| a slot may take more than §8's two secondary focuses | caught |
| custom percentages stop being enforced to sum to 100% | caught |
| pinned elements become replaceable | caught |
| an element the user authored stops being preserved | caught |
| the equipment constraint is ignored | caught |
| a preserved occurrence is re-identified anyway | caught |
| regeneration replaces the whole plan | caught |
| the goal stops being structural | caught |
| the planner imports the adaptive engine's policy | caught |
| the planner reaches for the scheduler | caught |
| the new planner is served by the legacy `WorkoutGenerator` | caught |
| generation gains a persistence collaborator | caught |
| control: the unmutated tree stays GREEN | not caught (as required) |

Two claims are deliberately *not* expressed as mutations, because no single line carries them:

* **"Generate persists nothing"** — `ProgramGeneratedEditor` holds no repository, no DAO, no
  transaction and no `Program`, so there is no line to break. It is asserted structurally (its
  declared fields are exactly `[draft, ids]`, and the architecture suite forbids a data-layer import)
  and behaviourally (`ProgramGeneratedEditorTest` compares the row count of **every** table — the
  fifteen target tables and the ten the app already shipped — before and after a generate and a
  regenerate, and the revision count of the Program).
* **"the planner reads no clock and no random source"** — the property belongs to the whole package,
  so the architecture suite scans every generated source for `Random`, `shuffled`, `currentTimeMillis`,
  `LocalDate.now`, `LocalTime.now`, `Instant.now`, `Clock` and `UUID`.

## 12. Claim → test

| claim | suite |
| --- | --- |
| exactly three goals and seven focuses, and no fourth goal | `FocusPlanTest.theFocusVocabularyIsExactlyTheSevenValuesSectionEightNames` |
| focus is not a day type and is never inferred from one | `FocusPlanTest.focusAndDayTypeAreTwoDifferentDimensionsThatHappenToShareOneWord`, `ProgramGeneratedArchitectureTest` (no `ProgramDayType`-derived focus in the planner) |
| `CUSTOM` percentages sum to exactly 100% and invalid ones are refused | `FocusPlanTest.customPercentagesMustSumToExactlyOneHundred`, `…aShareThatIsNotAPositiveWholePercentageIsRefused` |
| a configuration's focuses are canonical whatever order they were built in | `FocusPlanTest.aConfigurationsFocusesAreHeldInTheVocabularysOwnOrderWhateverOrderTheyWereBuiltIn` |
| the goal is derived from the configuration, never stored beside it | `FocusPlanTest.theGoalIsDerivedFromTheConfigurationRatherThanStoredBesideIt` |
| same request ⇒ same plan; collection order changes nothing | `GeneratedPlannerTest.theSameRequestProducesTheSamePlan`, `…theOrderOfTheLibraryViewAndOfEverySetInsideItChangesNothing` |
| 1 primary + 0–2 secondary focuses per slot; one element per assigned focus | `FocusPlannerTest.everySlotGetsOnePrimaryAndAtMostTwoSecondaryFocuses`, `GeneratedPlannerTest.everySlotPlansOneElementPerAssignedFocusInTheAssignmentsOwnOrder` |
| target deficit / remaining horizon / recent exposure move the allocation | `FocusPlannerTest.aBalancedConfigurationSpreadsOverTheWholeVocabularyAndTheFirstCycleIsWrittenOut`, `…theHorizonIsDecidedAsAWholeAndNotOneWorkoutAtATime`, `…exposureTheUserAlreadyHadSuppressesFurtherAllocationOfThatFocus` |
| recent load and the recovery window are **soft**, never a hard rule | `FocusPlannerTest.recentLoadDemotesAFocusSoftlyAndNeverForbidsIt`, `…theRecoveryWindowIsAPreferenceAndNotATwoDayRule` |
| a cautious context narrows stacking and changes nothing else | `FocusPlannerTest.aCautiousRecoveryContextNarrowsHowMuchAWorkoutStacksAndNothingElse` |
| user choice outranks an adaptive preference; recency and diversity are honoured | `GeneratedPlannerTest.theUsersOwnChoiceOutranksAnAdaptivePreference`, `…anAdaptivePreferenceOutranksTheGeneratorsOwnDiversityRule`, `…anExerciseUsedMostRecentlyIsPassedOverWhileAnAlternativeExists`, `…theDeterministicTieBreakIsTheCanonicalExerciseId` |
| hard constraints are never violated, and what cannot be planned is reported | `GeneratedPlannerTest.anExerciseTheEquipmentCannotSupportIsNeverChosen`, `…aFocusNothingTrainsIsReportedRatherThanFilled`, `…anEquipmentExcludedFocusIsReportedAndNeverSilentlySubstituted`, `…aDimensionWithoutASubtypeIsReportedRatherThanPrescribed` |
| prescriptions are per set, in each element's own dimension, and §10's shapes | `GeneratedPlannerTest.thePlanIsPrescribedInEachExercisesOwnDimensionWithThePolicysTargets`, `…theTwoPrescriptionShapesAreTheOnesSectionTenWritesOut` |
| pinned survives; an override survives; a compatible choice survives; generated may change | `PlanReconcilerTest.aPinnedElementIsNeverAutomaticallyChanged`, `…anElementTheUserAuthoredIsNeverSilentlyReplaced`, `…aUsersCompatibleChoiceIsPreservedRatherThanReplacedBecauseWeRegenerated`, `…oneChangedGeneratedElementIsReplacedAndEverythingElseIsPreserved` |
| a day the plan no longer fills goes, and one it adds is a training day | `PlanReconcilerTest.aDayThePlanNoLongerFillsIsRemovedAndOneItAddsIsAdded`, `…aGeneratedDayIsATrainingDayAndIsUnnamed` |
| Generate/Regenerate persist nothing and create no revision | `ProgramGeneratedEditorTest.generateAndRegenerateWriteNothingAtAll`, `…aGenerateAndARegenerateCreateNoRevision` |
| Save creates at most one revision; a no-op save creates none | `ProgramGeneratedEditorTest.aGeneratedPlanSavesAsExactlyOneRevisionAndComesBackWhole`, `…savingTheSameGeneratedDraftTwiceCreatesOneRevision`, `…aSaveAfterARegenerationThatChangedNothingIsStillANoOp` |
| a goal-only change is structural | `ProgramStructureTest.theVocabularyIsEveryStructuralDimensionOfSectionSix`, `…eachStructuralDimensionIsReportedAsItsOwnDifference`, `ProgramGeneratedEditorTest.changingOnlyTheGoalCreatesAStructuralRevision` |
| a stale draft saves relative to what is stored, with no `RevisionConflict` | `ProgramGeneratedEditorTest.aStaleGeneratedDraftSavesRelativeToWhatIsStoredAndRaisesNoConflict` |
| the scheduler's slots are untouched by a generation | `ProgramGeneratedEditorTest.aGenerationDoesNotTouchTheSchedulerSlotsItWasPlannedBeside` |
| the planner is pure, stateless and free of legacy/adaptive/scheduler reach | `ProgramGeneratedArchitectureTest` (twelve tests: layering, tokens, determinism scan, catalogue, adaptive boundary, scheduler boundary, pure compiled shape, statelessness, plan fields, purity-scan membership, composition root, UI) |
| the configuration survives storage losslessly, in every form | `PlanMapperTest.aConfigurationOfEveryFormSurvivesAStoreAndLoadUnchanged`, `…theBalancedConfigurationIsStoredAsTheGoalItStates`, `…aFocusedConfigurationStoresItsFocusesInTheDomainOrderAndComesBackEqual`, `…aCustomConfigurationStoresEveryShareAndComesBackEqual`, `…aRevisionWrittenBeforeGoalsAndFocusExistedReadsAsBalanced` |
| invalid stored configuration is refused on load, naming what it read | `PlanMapperTest.aStoredConfigurationTheDomainRefusesCannotBeLoaded`, `ProgramSchemaTest.aStoredGoalFocusConfigurationTheDomainRefusesIsRefusedOnLoad` |
| the columns are the intended ones, additive only, with no invented default | `ProgramSchemaTest.theGoalFocusColumnsAreNullableTokensThatInventNoGoal`, `…theAdditiveMigrationAddsExactlyTheDeclaredColumnsAndNothingElse`, `ProgramMigrationPreservationTest.theFocusCorrectionUpgradesAPopulatedVersionNineDatabaseWithoutInventingAGoal` |
| the migrated table is the table the entity emits | `ProgramSchemaTest.theMigratedRevisionTableIsTheTableTheContractDescribes`, `…everyTargetEntityDeclaresItsColumnsInTheStoredOrder` |
| entity and domain tokens agree exactly | `ProgramSchemaTest.theGoalFocusColumnsAreNullableTokensThatInventNoGoal` (the discriminators against `Goal`'s own names) |

## 13. Owner decisions and underdetermined semantics

1. **`Generate` and `Regenerate` are one reconciliation.** §7 states the precedence for
   *regeneration*; this stage applies it to both, so a `Generate` cannot silently discard pinned
   elements. The alternative reading — a `Generate` that refuses a draft which already holds user
   content, and replaces the plan wholesale — is a small change in `ProgramGeneratedEditor.edit` and
   is left to the owner. Consequences of the decision as implemented: generating into a *fresh* draft
   (the §7 `Build for me` path) produces a purely generated plan; generating into an existing
   Program's draft reconciles with what is there and changes only the generator's own elements.
2. **Every open coefficient is an explicit policy constant.** §8 says *"exact coefficients are not yet
   fixed"*; `GenerationPolicy` states each number this stage chose, with a sentence on what it decides,
   and none of them is a load score, a readiness percentage or a per-exercise difficulty. Where the
   blueprint does fix a number — `0–2` secondary focuses, §10's two prescription shapes, the §20
   horizon — the blueprint's own value is used unchanged.
3. **The plan covers the horizon in whole weeks.** An indefinite Program's plan is
   `PLANNING_HORIZON_DAYS / 7` weeks of its weekly rhythm, and a fixed Program's is its own duration in
   whole weeks (at least one). The result is a count of **plan days**, not of dates — a second
   calendar-duration interpretation is deliberately not introduced, and the Scheduler still decides
   which dates those days land on. A single-week template is the alternative reading; it would make
   the horizon irrelevant to the allocation, which §8 explicitly does not want.
4. **Days are numbered `1..n` and are unnamed.** The generated draft's days are `TRAINING` days with
   `name = null`, because §8's focus is not a day type and the domain authors no user-facing text
   (§25). A screen that wants to show a day's primary focus reads it from the `GeneratedPlan`, which is
   why `GeneratedDraftEdit` carries the plan beside the draft.
5. **The report names every element, including the preserved ones.** `ReconciliationReport` is what
   makes "the plan was not replaced wholesale" countable; `changedNothing` is the predicate for
   "nothing moved", and `conflicts` is what is worth showing. A caller that only wants the changes
   filters by kind.
6. **Replacement is reported as its two halves** (a drop and an add on the same day) rather than as an
   inferred pairing, because the planner has no "this new element stands in for that old one" relation
   to report.
7. **`NULL` reads as `BALANCED`.** Recorded as a decision, not an accident: it is the only reading of a
   row written before the columns existed that states nothing the user did not.
8. **Focus distribution in Progress/History is a follow-up.** This stage creates the vocabulary PR 9
   deferred; it does not change PR 9's measures (§9).
9. **The composition root is untouched.** Nothing consumes the planner yet, so nothing was wired;
   `ProgramGeneratedArchitectureTest` asserts that, and it is the test that will have to be revised
   deliberately when a screen arrives.

## 14. Base

This branch is based on `origin/main` at `f9d76c1`, the merge commit of PR 9
(`feat/program-progress-history`), whose tree is identical to its predecessor branch tip's
(`17f00b0`): `314e765b14c9749b2e46b873859d01468156e2c6` on both sides. No rebase was needed.
