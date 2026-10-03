# P28 — Generation User Exercise Preferences

§30 step 28. Base `feabff3` (the P27 merge, PR #329). Branch
`feat/program-stage28-user-preferences`.

```text
user states a ranking in the Program editor
        ↓
ExercisePreference  (ordered, no duplicates, absence is empty)
        ↓
ProgramEditorDraft.preferredExercises
        ↓ Save → ProgramRevision.preferredExercises
        ↓        → program_revision.preferredExerciseIds
GenerationContextSource.preferencesFor(draft)
        ↓
GenerationPreferences.userPreferredExerciseIds   ← the field that already existed
        ↓
existing ProgramGenerationService → existing GenerationRequest → existing planner
```

---

## 1. The stage in one paragraph

`GenerationPreferences.userPreferredExerciseIds` was always-neutral: P27 recorded that *"no persisted,
user-authored preference ordering exists"* and left it `emptyList()`. This stage gives it a real owner, an
authoring surface, and persistence — and changes **nothing** in `domain/program/generated/**`. The
preference is revision content, exactly like Goals & Focus, and it reaches the planner through the
application-level `GenerationContextSource` P27 established.

`GenerationRequest` gained **no** field. The planner did not change. §9's ranking algorithm is untouched.

---

## 2. The audit that decided the owner

The brief asked for a read-only audit of Program / Revision / Editor persistence contracts *before*
assuming a new entity. What it found:

| question | what decided it |
| --- | --- |
| Is the preference part of the **plan**? | **No.** §7's regeneration reconciliation is over plan elements (`ProgramExerciseOrigin`, `isPinned`); a preference names exercises a *future* pass should reach for. The draft's `USER_AUTHORED` elements and its pins are plan content the reconciler preserves, and §7's precedence (`PINNED > EXPLICIT USER OVERRIDE > COMPATIBLE USER CHANGE > PURE GENERATED CONTENT`) already fully accounts for them. Folding them in would double-count one user intent and would break `GenerationPreferences`' own "preferred once" invariant. |
| Is it part of the **Program** (non-revision) row? | **No.** `ProgramEntity`'s own KDoc names its allowlist: *"no mode — a Program's mode is its current revision's mode"*, no plan, no selection. A generation input is revision content by the entity's own stated rule. |
| Then where does §6 put it? | §6's list is headed *"Structural/**program-behavior** changes create a new Revision"*, and its **no-revision** list is closed — rename, description, activate/select, Start/Pause/Resume/Finish — and does **not** contain a generation preference. |
| Which existing mechanism is it therefore shaped like? | **`focus`.** Goals & Focus is the same kind of thing: a stated input to generation that §6 makes revision-creating even when no exercise moved. `FocusPlan` already had `ProgramStructure` / `ProgramRevision` / `ProgramEditorDraft` / `ProgramDraftEditor.withFocus` / `ProgramEditorService.save` / `program_revision.focusGoal+focusTargets` / `ProgramTransferMapper` / the editor's Goals & Focus section. |

**Verdict: revision-scoped, following the existing revision contract.** No second identity or lifecycle
model was introduced — `ProgramRevision` already *is* the Program's configuration of record, and a
preference that was not revision content would need somewhere else to live that §6 does not describe.

### Program vs Revision scope, stated precisely

A **revision** owns the preference, and a revision belongs to exactly one `programId`. So:

* the preference is scoped to **one Program**, through the revision that Program's `currentRevisionId`
  names;
* two Programs never share one — they cannot, because a revision row carries its own `programId` and the
  preference lives on that row, not in a global table;
* deleting a Program deletes its revisions by the **existing** `ForeignKey.CASCADE` on
  `program_revision.programId` (§29) — the preference goes with it through the mechanism already accepted
  for every other revision fact. **No new cascade, no new trigger, no new ownership mechanism.**

P28 introduced **no new table**. One nullable column, for the same reason `focusGoal`/`focusTargets` are
two columns rather than a table: a preference is a handful of ids belonging to exactly one immutable
revision.

---

## 3. `missing ≠ zero`, stated as the rule it is

| situation | stored | read back as |
| --- | --- | --- |
| user never stated a preference | `preferredExerciseIds = NULL` | `ExercisePreference.NONE` (empty) |
| row written before `MIGRATION_15_16` | `NULL` (the column's no-default) | `ExercisePreference.NONE` |
| preference cleared by the user | `NULL` | `ExercisePreference.NONE` |
| import of a file that states `"preferredExercises": []` | `NULL` | `ExercisePreference.NONE` |

None of these is a ranking, and none is ever read as *the whole catalogue in some order*. There is no
`DEFAULT` clause on the column, no `""` empty-string encoding, and no fallback list anywhere in the path.
An absent preference is a **stated absence**, not an unranked default.

The transfer format makes the same distinction on the file side: the writer **always** emits
`preferredExercises`, `[]` included, so "the file said nothing is preferred" is stated rather than
absent. That is why the reader can treat the field as **required** — an absent key and an empty array are
different documents, and only one of them is a user saying *"I prefer nothing"*.

---

## 4. The distinction that keeps this from being something else

| it is **not** | where that thing lives, and why it is different |
| --- | --- |
| current plan composition | the draft's `days` / `ProgramExercise` elements. The plan says what this Program *has*; the preference says what a future pass should reach for. |
| `USER_AUTHORED` content | `ProgramExerciseOrigin.USER_AUTHORED` — plan content reconciliation *preserves* (§7). A preference expresses no claim to keep anything. |
| pinned content | `ProgramExercise.isPinned` — exempts one element from automatic change (§7). A preference expresses no exemption at all. |
| Goals & Focus | `FocusPlan` — what the plan is built **for**. A preference is what it should reach **for**. Both are configuration; they are separate facts and neither is derived from the other. |
| adaptive preference | `GenerationPreferences.adaptivePreferredExerciseIds` — still a recorded gap (P27 §8.2). `FamilyProgressionState.currentExerciseId` is *"the exercise the family is currently on"*, not a selection preference, and promoting it would fabricate the field's meaning. |
| recent history | `recentExerciseIds` — the exercises actually performed, most recent first. A different semantic unit entirely. |
| candidates / equipment boundary | `GenerationRequest.isUsable` and `usableCandidatesFor` — applied **above** the ranking. See §6. |

---

## 5. The exact application path

```text
ProgramEditorScreen.PreferredExercisesSection        ui/screens
        ↓ four calls, no local state for the ranking
ProgramsController.preferDraftExercise / unpreferDraftExercise /
                 promoteDraftPreferredExercise / demoteDraftPreferredExercise
        ↓ the value's own operations — ExercisePreference.preferring / without / moved
ProgramDraftEditor.withPreferredExercises            domain/program  (pure)
        ↓
ProgramEditorDraft.preferredExercises
        ↓
GenerationContextSource.preferencesFor(draft)       domain/usecase  (P27's port, unchanged shape)
        ↓
GenerationPreferences(userPreferredExerciseIds = …)  domain/program/generated  (field unchanged)
        ↓
ProgramGenerationService.planned(...) → ProductionGenerationBoundary.generationRequest(preferences = …)
        ↓
GenerationRequest.preferences  →  ExerciseSelector.ranked   (unchanged)
```

**`ProgramGenerationService` was not changed.** It reads no persistence and builds no preference; it
already forwarded whatever `context.preferencesFor(draft)` answered into the existing
`GenerationRequest.preferences`. P28 changed only *what that source answers*.

`GenerationContextSource` remains an application-level port. `ProgramHistoryGenerationContext` holds
**one** collaborator (the `GenerationSessionHistory` read port) and gained **no** second read: the
preference is already on the draft, so forwarding it needs no I/O at all. That is what keeps P27's
invariant structurally true rather than merely tested:

> **one generation operation = one coherent context snapshot** — still one `preferencesFor` call per
> pass, still in the shared private `edit`, still forwarded as an argument rather than re-read.

---

## 6. Planner precedence — §9 as it already was

§9's order is unchanged and this stage does not touch `ExerciseSelector`. What P28 proves is the first
level of it, with a **real** preference reaching the **existing** selector:

```text
user choice              → GenerationPreferences.userPreferredExerciseIds   ← comparison 1
> hard execution constraints  → usableCandidatesFor, applied before ranking
> adaptive preference    → GenerationPreferences.adaptivePreferredExerciseIds
> progression need       → not modelled at this stage (P10's recorded absence)
> recency / diversity    → usesThisCycle, familyUsesThisCycle, recentExerciseIds
> deterministic tie-break→ the exercise's canonical id, ascending
```

The hard-constraint boundary sits **above** the preference and P28 pins that it does: a preference cannot
widen the candidate set. `ExerciseSelector.ranked` is only ever asked for
`request.usableCandidatesFor(focus)`, so an exercise the equipment forbids is not selectable whatever a
preference says — §9's *"hard execution constraints must never be silently violated"*.

The behavioural proof is `ExercisePreferencePlannerPrecedenceTest`: with `pullups` preferred and the
equipment excluding it, `ranked(...)` never returns `pullups`, and with the equipment admitting it, the
same preference does return it first.

---

## 7. Persistence representation

```text
program_revision.preferredExerciseIds  TEXT NULL   ← MIGRATION_15_16, ALTER TABLE, no DEFAULT
```

* **Order-preserving.** The user's order is stored verbatim and read back verbatim. This is the one
  mapper in the codebase where canonicalising would be *wrong*: `FocusPlan` has a vocabulary order, and a
  preference has none — `["pullups","dips"]` and `["dips","pullups"]` are different statements.
* **`null` = NONE**, never `""` and never a ranking.
* **Strict on read.** A stored value with a blank or duplicated id fails loudly and names the row, because
  a preference whose order has two answers at one position is not a preference.
* **Not the mapper's job to check existence.** Whether an id names a real shipped exercise is decided by
  the layer that holds the catalogue — the controller's add path, and §5's `exerciseId validation` step on
  import. The mapper has no catalogue and inventing the check would be a second vocabulary.

Migration: `15 → 16`, one `ALTER TABLE ... ADD COLUMN`, registered in `getDatabase`'s migration list, with
the Room `version` bumped to 16. `MIGRATION_15_16` is `internal` like every step before it, because the
schema suites compare its statements token for token.

---

## 8. Import / Export decision

**The preference is transferable Program configuration, so it is in the format.**

The reasoning: §5 says *"Export: Program definition/configuration only"* and lists what is **never**
exported — `programId`, active/selected state, current day, history, statistics, SetLogs, adaptive
state, adaptive decision history, in-progress session state. A preference is none of those. It is a
stated input to generation, of exactly the kind `focus` already is, and §5's list makes `focus`
transferable. Dropping the preference would mean an imported Program silently generating differently from
the one that was shared — the one thing an importer most needs not to do.

| rule | how P28 keeps it |
| --- | --- |
| `programId` not exported | the field holds exercise ids only; the format has no identity field anywhere |
| history not exported | `recentExerciseIds` and every other P27 signal stay out — the format is unchanged in that respect |
| adaptive decision / history not exported | untouched; no adaptive field exists in the format |
| import mints a new `programId` | `ProgramTransferMapper.revisionOf` re-identifies everything and the import path mints the new Program — the preference rides along as configuration, not as an identity |
| the existing checkbox stays the only selection choice | `ProgramImportReviewUi.makeActive` untouched; P28 added no second import option |
| the file is deterministic | the ids are written as an ordered JSON array in the user's own order; no sorting, no re-derivation |

**Format version: 1 → 2.** `ProgramTransferFormat`'s own KDoc states the rule: *"Adding a field is
therefore a version bump"*, and §5 requires *"Unknown/unsupported version is rejected"*. A version-1 file
is refused as unsupported rather than read as "a program with no preference" — a version-1 file has no
`preferredExercises` key at all, so reading it would be guessing what its author meant.

`document.referencedExerciseIds` now includes the preference's ids, so §5's *"Unknown exerciseId is
rejected"* covers them too: a file naming an exercise the receiving app does not ship is refused, rather
than importing a preference the importer can never honour.

---

## 9. Architecture boundaries

| boundary | how it is held |
| --- | --- |
| `domain/program/generated/**` unchanged | no file added or edited in the package. The gate asserts the exact eight-file list, that the package imports nothing from `usecase`/`data`/`di`, that `GenerationPreferences` has exactly its six fields, and that `GenerationRequest` gained no field. |
| the service holds no persistence | unchanged collaborators (five: catalogue, focusSource, ids, context, policy). Banned by name: `*Repository`, `*Dao`, `AppDatabase`, `Entity`, `Room`. It also does **not** hold `GenerationSessionHistory`. |
| the service builds no preference | it neither constructs a `GenerationPreferences` nor reads `draft.preferredExercises`; it forwards the source's answer unchanged into `planned`. |
| the context source performs no writes | it still holds one collaborator and one read port; no table, entity, DAO or migration behind it. |
| one construction site | `ProgramHistoryGenerationContext` is built only in `AppContainer`. |
| the UI names no repository / context / planner | the gate bans `Repository`, `Dao`, `GenerationContextSource`, `GenerationPreferences`, `GenerationRequest`, `GeneratedPlanner`, `ExerciseSelector`, `ProgramGeneratedEditor`, `AppDatabase` from the screen, the controller and the authoring rules. |
| the screen holds no second ranking | the gate bans `remember`/`mutableStateOf`/`var` holding an `ExercisePreference` or a list of exercise ids in the preference section, and asserts the section's only state is the picker dialog's open flag. |
| no hidden ranking | the gate rejects `sortedBy` / `sortedWith` / `.sort(` in the preference section, and asserts no screen code constructs an `ExercisePreference` with ids. |
| a new Program never inherits one | a draft with no `programId` gets the preference it states and reads **no** history; the context source's early return happens after the preference is taken, not before. |

---

## 10. Tests

Six new suites, 61 tests. Every row below is a real behavioural or architectural oracle, and the RED
script in §11 mutates the layer each one owns.

| # | claim | where |
| --- | --- | --- |
| 1 | authoring preserves the exact order | `ExercisePreferenceTest.theUsersOrderIsTheValueAndNothingReordersIt`; `ProgramPreferenceStorageTest.theExactOrderedPreferenceSurvivesTheRoundTripThroughStorage`; `ProgramTransferPreferenceTest.theReversedOrderProducesDifferentBytes` |
| 2 | reorder changes the exact preference order | `ExercisePreferenceTest.reorderingAnEntryChangesTheExactPreferenceOrder`; `ProgramsExercisePreferencesTest.movingAnEntryChangesTheExactOrder` |
| 3 | remove deletes only the chosen id | `ExercisePreferenceTest.removingAnEntryLeavesEveryOtherPositionWhereItWas`; `ProgramsExercisePreferencesTest.removingAnExerciseRemovesOnlyThatOne` |
| 4 | duplicate preference is rejected | `ExercisePreferenceTest.theSameExerciseCannotBePreferredTwice` (both entry points); `ProgramTransferPreferenceTest.aRepeatedPreferenceIsRefusedBecauseTheOrderWouldHaveTwoAnswersAtOnePosition` |
| 5 | unknown / blank exercise id rejected | `ExercisePreferenceTest.aBlankExerciseNameIsRefusedRatherThanStored`; `ProgramsExercisePreferencesTest.anExerciseTheCatalogueDoesNotOfferIsRefusedBeforeTheDraftChanges`; `ProgramTransferPreferenceTest.aPreferredExerciseIsAReferenceAndIsValidatedWithTheRest` |
| 6 | two Programs have isolated preference lists | `ProgramPreferenceStorageTest.twoProgramsInOneDatabaseNeverSeeEachOthersPreference` and `…aProgramWithNoPreferenceDoesNotReadAsAnotherPrograms` |
| 7 | no preference = empty, never invented | `ExercisePreferenceTest.noPreferenceIsEmptyAndNeverAnImplicitRanking`; `ProgramPreferenceStorageTest.aProgramThatNeverStatedOneReadsAsEmptyRatherThanARanking`, `…aRowThatPredatesTheColumnReadsAsEmptyRatherThanARanking` |
| 8 | the persisted preference reaches `GenerationPreferences` unchanged | `ProgramPreferenceStorageTest.thePersistedPreferenceReachesTheRequestUnchanged` (column → revision → draft → request, nothing injected by the test) |
| 9 | Generate and Preview see the same snapshot | `ProgramsExercisePreferencesTest.generateAndPreviewOverTheSameDraftSeeTheSamePreferenceSnapshot`; P27's `ProgramGenerationContextSnapshotTest` unchanged and green |
| 10 | P27's one-snapshot invariant holds | `ProgramPreferenceStorageTest.theContextSourceStillIssuesExactlyOneReadPerPassWithAPreferenceInPlay`; `ProgramGenerationContextArchitectureTest.theServiceReadsTheContextExactlyOnceInsideTheSharedPass` |
| 11 | the UI names no repository/context/planner | `ExercisePreferenceArchitectureTest.theScreenAndItsRulesReachNoRepositoryNoContextAndNoPlanner`, `…theAuthoringRulesHoldNoConfigurationAndReachNoFramework` |
| 12 | the service holds no persistence collaborator | `ProgramGenerationFlowArchitectureTest` and `ProgramGenerationPreviewServiceTest`, both unchanged and green |
| — | §9's six-level precedence, on the real selector | `ExercisePreferencePlannerPrecedenceTest` (10 tests) |

### 10.1 The three claims worth naming separately

**`missing ≠ zero` is asserted at every layer, not once.** The column is `NULL` with no `DEFAULT`
(`ProgramSchemaTest.thePreferenceMigrationAddsOneNullableColumnAndNoDefault`), the mapper reads `NULL` as
`ExercisePreference.NONE`, storage round-trips it, the context source forwards an empty list, and the
transfer writer emits a stated `[]`. A fabricated ranking is refused at the mapper and at the migration.

**The preference reaches the planner through the field that already existed.**
`ExercisePreferenceArchitectureTest.theGeneratedPackageIsUntouchedAndTheRequestGainedNoField` asserts the
eight-file list of `domain/program/generated` is unchanged and reads the **compiled** `GenerationRequest`
to assert its seven fields are the seven it always had.

**The hard-constraint boundary sits above the preference.**
`ExercisePreferencePlannerPrecedenceTest.aPreferredExerciseTheEquipmentForbidsIsNeverSelected` prefers an
exercise the equipment excludes and asserts it is absent from `usableCandidatesFor` itself — not filtered
after ranking — with `…theSamePreferenceSelectsTheExerciseOnceTheEquipmentAllowsIt` as the control that
proves the preference works at all.

---

## 11. RED mutation evidence

`scripts/program-stage28-red-mutations.sh` — 18 mutations over 10 production sources, each aimed at the
layer that DECIDES the rule it breaks.

| # | mutation | layer | oracle |
| --- | --- | --- | --- |
| 1 | the mapper sorts what the user ranked | `PlanMappers` | storage suite |
| 2 | the duplicate refusal is bypassed | `ExercisePreference` | value suite |
| 3 | the blank-name refusal is bypassed | `ExercisePreference` | value suite |
| 4 | the add path drops its catalogue check | `ProgramsController` | controller suite |
| 5 | the stored read leaks across Programs | `PlanMappers` | storage suite |
| 6 | `NULL` becomes a hard-coded ranking | `PlanMappers` | storage suite |
| 7 | the migration gains a `DEFAULT` | `AppDatabase` | schema suite |
| 8 | the preference is not forwarded to the request | `ProgramGenerationContext` | context architecture gate |
| 9 | the context is read a second time | `ProgramGenerationContext` | P27's snapshot suite |
| 10 | the add puts the new entry **first** instead of last | `ExercisePreference` | controller suite |
| 11 | the save stops writing the column | `PlanMappers` | storage suite |
| 12 | the writer stops emitting the field | `ProgramTransferJson` | transfer suite |
| 13 | a version-1 file is accepted although `VERSION` is 2 | `ProgramTransferReader` | transfer suite |
| 14 | a repeated entry in an imported file is accepted | `ProgramTransferReader` | transfer suite |
| 15 | the screen imports `AppDatabase` | `ProgramEditorScreen` | architecture gate |
| 16 | the screen keeps its own copy of the ranking | `ProgramEditorScreen` | architecture gate |
| 17 | the screen sorts the ranking | `ProgramEditorScreen` | architecture gate |
| 18 | the aspect is declared but not wired into the comparison | `ProgramStructure` | architecture gate |

**A compile failure is not a catch.** `check_red` inspects the run log for a Kotlin `e:` line and charges
such a row `NOT-A-CATCH` rather than `caught`, so every row above is behavioural evidence. A mutation that
does not apply aborts the run rather than scoring a verdict.

Four rows were rewritten before they counted, and each rewrite is a fact about **the oracle**, not about
the production code — the row that cannot discriminate is the defect:

* **Row 1** first sorted on the mapper's *write* side, which the storage suite never exercises (it writes
  raw SQL by design, so the round trip is measured at the read boundary). Re-aimed at the read side.
* **Row 5** first added an early `return` after a `?: return` — unreachable code, which Kotlin rejects, so
  the row scored NOT-A-CATCH for a compile error. Rewritten as a value the mapper really produces on every
  read, which is what a cross-Program leak actually looks like.
* **Row 8** and **9** first ran against the *architecture gate*, which is a source scan and correctly does
  not react to a behavioural change. Re-aimed at the suites that assert the behaviour.
* **Row 16** first inserted a constructor parameter, which does not compile. Rewritten as a `remember`ed
  copy — the mistake the gate exists to catch — and the gate's own pattern was widened from the *type* name
  to the *field* it forbids, because a pattern requiring `ExercisePreference` would have missed exactly the
  line a screen would really write.

Result, as printed by the script:

```text
control GREEN
caught: 18   missed: 0   not-a-catch: 0
every mutated source restored byte-identically
```

---

## 12. Verification

Measured on `feat/program-stage28-user-preferences`, base `feabff3` (the P27 merge, PR #329):

| | |
| --- | --- |
| full JVM | **311 classes / 3071 tests / 0 failures / 0 errors / 0 skipped** (fresh, `--rerun-tasks`, results dir wiped) |
| focused P28 | `ExercisePreferenceTest`, `ExercisePreferencePlannerPrecedenceTest`, `ProgramPreferenceStorageTest`, `ProgramsExercisePreferencesTest`, `ProgramTransferPreferenceTest`, `ExercisePreferenceArchitectureTest` |
| compile gates | `compileDebugKotlin`, `compileDebugUnitTestKotlin`, `compileReleaseKotlin`, `compileReleaseJavaWithJavac`, `assembleDebug` |
| RED | `scripts/program-stage28-red-mutations.sh` — control GREEN / caught 18 / missed 0 / not-a-catch 0 |
| `git diff --check` | clean |

`compileReleaseKotlin` and `assembleRelease` are the release evidence; `:app:lintVitalRelease` fails
pre-existing (`res/values/themes.xml` ResourceCycle) and is not stage evidence.

### The eleven facts this stage rests on

```text
Revision-scoped                          §6's program-behavior clause; the focus precedent
nullable preferredExerciseIds            one column, no DEFAULT — missing ≠ zero ≠ a ranking
existing revision FK cascade             deletion via the ON DELETE CASCADE already accepted (§29)
no new lifecycle model                   no table, no entity, no DAO, no new revision semantics
configuration in the transfer format     §5 transfers Program configuration; preference is one
VERSION 1 → 2                            the format's own documented bump rule
v1 intentionally rejected                UnsupportedDocumentVersion — never read as "no preference"
P27 snapshot invariant preserved         one preferencesFor per pass, still in the shared private edit
hard constraints above the preference     usableCandidatesFor, unchanged
no planner change                        ExerciseSelector untouched; its six comparisons intact
no new request field                     the field that already existed is the one filled
```

---

## 13. Scope boundary — what P28 does NOT do

No `adaptivePreferredExerciseIds` · no `recentExposureByFocus` · no `recentLoadByFocus` · no `recovery` ·
no workout UI · no scheduler change · no session-runtime change · no revision-contract change · no planner
change · no `GenerationRequest` field · no new table · no second lifecycle model · no automatic
recommendation and no favourites.

Those five remaining P27 gaps stay exactly where P27 recorded them, and filling them needs the domain
decisions P27 named — not a derivation.