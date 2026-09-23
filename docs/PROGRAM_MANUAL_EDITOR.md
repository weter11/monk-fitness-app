# Program System — the Manual Editor (PR 6)

Scope: the **use-case and domain layer** for editing a Program by hand — creating a MANUAL Program,
editing an existing user Program, copying a Program, editing the built-in Standard Program by copying
it first, validating a draft, reviewing what a save would do, and saving it as **at most one new
immutable revision**.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below
as **§N**). Implementation order: §30 step 6, *Manual Editor*. Companions:
`docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1), `docs/PROGRAM_ROOM_SCHEMA.md` (PR 2),
`docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md` (PR 2.1), `docs/PROGRAM_DATA_ACCESS.md` (PR 3),
`docs/PROGRAM_COMPOSITION_ROOT.md` (PR 4), `docs/PROGRAM_LIFECYCLE.md` (PR 5).

This change is **draft-first editing and the revision rule**. No scheduler, no session runtime, no
adaptive policy, no generator, no import/export, no progress calculation and no UI wiring — §30 steps
7+ own those.

---

## 1. The graph

```text
AppContainer                                     ← PR 4, unchanged apart from one construction
    ↓
    ProgramEditorService                         ← this PR (§30 step 6)
        ├── ProgramRepository                    ┐
        ├── ProgramPlanRepository                │ PR 3, persistence only
        │   (no ProgramScheduleRepository,       │ the schedule is deliberately ABSENT: the editor
        │    no session repository,              │ has no way to touch a slot (§20, §30 step 7)
        │    no exercise-library port)           ┘
        ├── clock: Clock                         ← injected; every stamp a save writes
        ├── idGenerator: IdGenerator             ← the only source of a persisted identity
        └── inTransaction                        ← the database's own transaction (§27)
    ↓
    ProgramDraftEditor        (pure domain)      ← the `edit` step: draft in, draft out, no I/O
    ProgramStructure          (pure domain)      ← "did the structure change?", identity excluded
    ProgramDraftValidation    (pure domain)      ← the `validate` step, findings and no repairs
    ProgramDraftReview        (pure domain)      ← the `Review` step: what saving would do
    ProgramEditorResult       (pure domain)      ← §28's classes, the save outcomes, the rejections
    ↓
ViewModels (not wired by this PR)
```

| file | layer | responsibility |
| --- | --- | --- |
| `domain/program/ProgramStructure.kt` | pure domain | a plan's content without its identities, and the aspects in which two plans differ |
| `domain/program/ProgramDraftEditor.kt` | pure domain | the `edit` step: every operation answers with the next draft; identity comes from an injected `DraftIdSource` |
| `domain/program/ProgramDraftValidation.kt` | pure domain | the `validate` step: typed findings, structurally incapable of carrying a repair |
| `domain/program/ProgramDraftReview.kt` | pure domain | the `Review` step: validation, what the save will produce, what changed, and the plan's totals |
| `domain/program/ProgramEditorResult.kt` | pure domain | `Success / Rejected / Failed` (§28), the typed rejections, and the three save outcomes |
| `domain/usecase/ProgramEditorService.kt` | use case | the operations: open a draft, review it, save it — deciding and persisting, never generating or scheduling |
| `di/AppContainer.kt` | composition root | one construction added (+29 lines, 0 removed); nothing else |

## 2. The flow, and where each step lives

```text
load/create  →  Draft  →  edit  →  validate  →  Review  →  Save
   service      value     pure      pure         pure       service
```

* **load/create** — `newDraft` (create), `editDraft` (edit), `copyDraft` (copy and the Standard path).
  Each returns a `ProgramEditorDraft`, the value the rest of the flow works on.
* **Draft** — `ProgramEditorDraft` (PR 1): no revision identity, may be incomplete, holds the plan in
  the same shapes a revision does.
* **edit** — `ProgramDraftEditor`: `renamed`, `described`, `withMode`, `withDuration`, `withSchedule`,
  `addingDay`, `removingDay`, `movingDay`, `renamingDay`, `retypingDay`, `addingExercise`,
  `duplicatingExercise`, `removingExercise`, `movingExercise`, `settingPrescription`, `settingPinned`.
  Every one returns the **next** draft; none mutates the one it was given, and none can reach storage.
* **validate** — `ProgramEditorDraft.validation()`: the rules a save must satisfy, decided from the
  draft alone.
* **Review** — `ProgramDraftReview.of(draft, base, nextRevisionNumber)`, surfaced by
  `ProgramEditorService.review`: the validation, the save kind, the target identity, the aspects that
  differ, and the plan's totals (days, rest days, exercises, sets).
* **Save** — `ProgramEditorService.save`: validate, dispatch on the entry point, compare, write.

The three entry points are distinguishable from the draft itself, without a flag that could disagree
with it:

| entry point | draft | what `Save` produces |
| --- | --- | --- |
| create | no Program, no base revision | a new Program, its first revision and its plan, in one transaction |
| copy | no Program, a base revision | a new Program carrying the copied structure, owned by the user whatever the source was |
| edit | a Program and the revision it was opened from | a new revision of that Program, **only** if the structure changed |
| Standard Program | — | editing it in place is refused; `copyDraft` is the §4 copy-before-edit path |

## 3. The rules, and where each is enforced

| §30/§6/§7 rule | Enforcement |
| --- | --- |
| The editor is always draft-first (§6, §7) | Every edit is an operation on a `ProgramEditorDraft` returning the next draft. `ProgramDraftEditor` holds only the draft and an identity source, declares no `var`, and imports no repository — the pure half of the editor cannot write anything. `editingNeverTouchesAnythingThatIsStored` drives a whole editing session and asserts the Program, its current revision, its slots and the row count of every table are unchanged. |
| A saved revision is immutable (§6) | `Save` never updates a revision: a structural change is a **new** `ProgramRevision` with a new identity, written by `ProgramPlanRepository.saveNewRevision`. `ProgramPlanRepository` exposes no update and no delete for a revision, a day or an element (PR 3). `theSupersededRevisionStillDescribesExactlyWhatItDescribed` reloads the superseded revision by identity and compares it whole. |
| One Save creates at most one new Revision (§6) | `save` has exactly three exits that write: `planRepository.saveNewRevision` once (an edit) and `programRepository.createProgram` once (a create or a copy, which is one revision by construction). `ProgramSaveOutcome.revisionsCreated` states it as `1` or `0`, and `editingAUserProgramsPlanSavesExactlyOneNewRevision` measures the revision count before and after. |
| No-op Save creates none (§6) | A save whose `ProgramStructure` matches the plan it is measured against, and whose name and description are unchanged, returns `ProgramSaveOutcome.NothingToChange` and writes **nothing at all** — no revision, no row, not even the `updatedAt` stamp. `aSaveThatChangesNothingCreatesNoRevisionAndWritesNothing` compares the row count of every table and the stored Program. Saving the same draft twice is therefore idempotent. |
| Structural changes create a new Revision (§6) | The comparison is `ProgramStructure` — mode, duration, schedule, days (type/name/order), exercises (selection/order/authorship), prescriptions, pinning. Any difference is a revision; §6's own list is the aspect vocabulary, and `ProgramStructureTest` pins each dimension to its own aspect. |
| Rename, description, planned start date, lifecycle, selection and archive create no Revision (§6) | `ProgramStructure` contains none of them: the name and description are columns of `program`, and the rest are facts of `Program` this layer never writes. A rename-only save is `FactsSaved` (one `updateProgram`, no revision). `savingLeavesTheProgramsOwnFactsAlone` measures `plannedStartDate`, `actualStartDate`, `lifecycleStatus`, `archivedAt`, `createdAt`, `source` and `programId` across a structural save. |
| Standard Program cannot be edited in place; editing means copying first (§4) | `editDraft(standardProgramId)` and `save` of any draft naming the built-in Program both fail with the lifecycle layer's own `StandardProgramProtected`, which the result boundary maps to `ProgramOperationRefusal.StandardProgramCannotBeEdited` — one rule, one sentence. `copyDraft` on the same id is allowed and produces a `ProgramSource.USER` Program. |
| Copy creates new Program/revision/day/exercise identities (§6, §23) | `createProgramFrom` mints a new `programId` and `mintRevision` mints a new `revisionId`, a new `ProgramDayId` for every day and a new `ProgramExerciseId` for every element. `copyingCreatesANewProgramWhosePlanIsNewRowsWithTheSameStructure` asserts the disjointness of all four sets against the source, and that the source's own rows survive unchanged. |
| A draft's identities never reach storage | The draft's day and element ids are working handles — for an edit they are the revision's own ids, which makes the trap real. `mintRevision` re-identifies every row, and `aNewRevisionNeverReusesThePlanRowsItReplaces` asserts the saved day/element identities are disjoint from both the superseded revision's and the draft's. |
| Per-set prescriptions remain lossless (§10) | The prescription is carried whole into the revision and back out: `aCreatedRevisionHoldsEverySetTheDraftPrescribed` and `aReloadedPlanDescribesTheWorkTheUserPrescribed` read `12/10/8/6` and `30/30/45` back per set, in their own dimensions, after a save and a fresh load. |
| Repeated exercise occurrences remain separate identities (§9) | Each occurrence is a `ProgramExercise` with its own id: `repeatedOccurrencesKeepSeparateIdentitiesThroughTheSave` (via the created revision's distinct ids), `duplicatingAnExerciseRepeatsItAsItsOwnOccurrence` and `changingOneOccurrencesPrescriptionLeavesTheOtherAlone` in the pure engine. |
| Manual prescriptions are not constrained by Generator ranges (§2, §10) | Nothing in the editor resolves an exercise, consults a library, an equipment list or a range, and no validation rule mentions volume. `ProgramDraftEditor.addingExercise` takes whatever the user typed, and `ProgramDraftValidationTest` proves the only plan rules are the ones a revision must be able to hold. |
| Exercise Library data is never mutated (§10) | The editor holds no library port and never resolves an exercise id; `ProgramEditorArchitectureTest` pins that. Behaviourally, `theEditorNeverMutatesExerciseLibraryData` runs a full create/edit/copy cycle and compares the app's own library, read through `WorkoutGenerator.getExerciseLibrary()`, before and after. |
| Invalid drafts are rejected before persistence (§7, §28) | `save` validates first and returns `Rejected(InvalidDraft(validation))`; no DAO is reached, and `anUnfinishedDraftIsRejectedBeforeAnythingIsWritten` / `aDayThatPlansNothingIsRejectedBeforeItIsSaved` measure the row counts. The rules are the plan a revision must be able to hold (§6, §23 — days, identities, numbering), §20's rest-day rule read in the direction that matters, and §7's three entry points. |
| Failed Save is atomic (§27) | An edit's facts write and its revision write are one transaction, with the facts first (a Program row written *after* `saveNewRevision` would point back at the plan it replaced). A create/copy is `ProgramRepository.createProgram`, which PR 3 already makes one transaction. `aFailedSaveLeavesNoRevisionNoMovedPointerAndNoRenamedProgram` and `aFailedCreateLeavesNoProgramBehind` plant a DAO fault and measure the revision count, the pointer, the whole Program row and the row count of every table. |
| Saving a new revision does not reconcile or modify scheduler slots **in the editor itself** (§20, §30 step 7) | The editor has no schedule repository at all, and `createProgramFrom` passes no initial slots. `savingDoesNotReconcileOrModifySchedulerSlots` compares the slots before and after (they still present the revision they were planned from) and `creatingAProgramDoesNotScheduleIt` proves the editor's own creation writes no slot row. **Revised with the creation remediation:** §27's two lines are now composed *above* the editor by `ProgramSaveService` — a production Create/Copy writes the Scheduler's initial slots in the creation transaction (`ProgramSaveServiceTest`), and a production structural Save runs the reconciliation pass inside the save's transaction (`aStructuralSaveReconcilesFutureOpportunities`, `aCompletedOpportunitySurvivesReconciliationAsHistory`, `repeatedReconciliationIsIdempotent`). The editor remains free of the collaborator; the production path is no longer missing the composition. |
| ViewModels free of Room/DAO access (§26, §33) | No ViewModel is wired by this PR, and `ProgramEditorArchitectureTest` asserts that neither `ui/` nor `viewmodel/` references `ProgramEditorService` — so the wiring that will be added later is added deliberately, not by drift. |
| No second persistence model, no second revision mechanism | The editor adds no entity, no DAO, no mapper and no repository method: persistence goes through the PR 3 repositories, and the revision mechanism is `ProgramPlanRepository.saveNewRevision`. `ProgramEditorArchitectureTest` scans the editor's sources for DAO, entity and Room tokens and requires the composition root to be the only place the service is constructed. |

## 4. Two identities, deliberately different

A draft addresses its days and its plan elements by id — that is how an edit says *which* day to
remove — and for an edit the draft is opened from a revision, so it starts out holding **that
revision's** identities as its working handles. A saved revision, meanwhile, may never reuse a row of
the revision it replaces: a day and an element are rows of their own (§6, §23), so a second revision
that kept them would be rewriting the first one.

The seam is `mintRevision`, and it is one sentence:

```text
a save mints a new revision identity, a new identity for every day and for every element,
and changes nothing else
```

The `require` inside it is the second half of that sentence as a guard — the minted revision's
`ProgramStructure` must equal the draft's — so if re-identification ever reordered a day, dropped an
element or rewrote a prescription, the save would fail loudly instead of persisting a plan the user
never reviewed. `ProgramStructure` is what makes both halves expressible: it is the plan *without* its
identities, which is exactly the thing that must not change.

## 5. What this change does not do

* **No slot reconciliation — in this class.** §27's `Save Editor → new Revision + future-slot
  reconciliation` is implemented in its first half *here*: the revision is written and the pointer
  moves; which slots a new plan supersedes is §30 step 7's decision, and this class deliberately does
  not have the collaborator to make it. **The production half is not missing:** `ProgramSaveService`
  (the creation remediation) runs the Scheduler's pass after every structural Save, so the §27 line
  holds end-to-end while the editor's constructor stays exactly as pinned. What this class still does
  not do — and what no layer above it lets it do — is decide a date or a slot of its own.
* **No generation and no Focus/goals.** §7's editor has a *Goals & Focus* section; goals, focus
  percentages and their validation belong to the Focus Planner (§8, §30 step 10), which is also where
  `generate`/`regenerate` live. A draft's mode is stored, never exercised: a `GENERATED` draft saves
  the plan the user is holding.
* **No scheduler, no session runtime, no adaptive engine, no progress, no import/export.** Steps 7–13.
* **No ViewModel and no screen.** The service is constructed in `AppContainer` and fully tested through
  the production DAOs; the UI integration is a later step and is fenced by
  `ProgramEditorArchitectureTest`.
* **No seeding of the Standard Program.** The built-in Program's *content* is a later stage's decision
  (as PR 5 recorded too); until it exists, `editDraft`/`copyDraft` on it report §28's `ProgramNotFound`
  rather than inventing one.
* **No planned start date in the draft.** It is a Program fact that creates no revision (§6). Two
  owners now carry it: PR 5's `ProgramLifecycleService.setPlannedStartDate` for an existing Program,
  and the editor's creation request — held beside the draft in `ProgramsUiState.draftPlannedStartDate`,
  handed to `ProgramSaveService.save` at Save, defaulted to *today* there — for a Program being
  created. Neither path puts it into `ProgramStructure`, so choosing or changing it creates no
  Revision either way.

## 6. Verification

`ProgramEditorServiceTest` (31 tests) drives the real `AppContainer` wiring over real Room/SQLite
doubles — the production DAOs, the production transaction runner, the production clock and id
generator — so every claim about storage is decided by the engine. `ProgramDraftEditorTest` (14),
`ProgramDraftValidationTest` (9), `ProgramStructureTest` (8) and `ProgramEditorArchitectureTest` (8)
cover the pure half, the rules and the boundaries, all on the JVM.

Baseline before this change (measured on `a92017c`, the merge commit of PR 5, in this same tree
before any edit, with a fresh `--rerun-tasks` run whose JUnit XML `timestamp`s were checked against
`date -u`): **1854 tests, 226 classes, 0 failures**. After: **1924 tests, 231 classes, 0 failures**
— exactly `+70 tests / +5 classes`, the five new classes and nothing else. The counts are parsed from
the XML and cross-checked against the sources (`grep -rho '@Test' app/src/test/java | wc -l` = 1924,
`grep -rl '@Test' app/src/test/java | wc -l` = 231).

```text
:app:testDebugUnitTest --rerun-tasks          1924 tests, 231 classes, 0 failures, 0 errors, 0 skipped
:app:compileDebugKotlin                       BUILD SUCCESSFUL
:app:compileReleaseKotlin                     BUILD SUCCESSFUL
:app:assembleDebug                            BUILD SUCCESSFUL
```

(`lintVitalRelease` is excluded for the known pre-existing `themes.xml` resource cycle; the release
evidence is `compileReleaseKotlin` plus the assembled debug APK.)

### RED evidence

`scripts/program-editor-red-mutations.sh` applies one mutation at a time to the *production* sources,
reruns the focused editor suite, restores the file and proves the restoration by `md5sum -c`. A rule is
only proven if breaking it fails a test. Twelve rules plus a control:

| mutation | expectation |
| --- | --- |
| opening an edit draft writes to the stored Program | caught |
| a save reuses the revision identity it replaces | caught |
| one save writes the same revision twice | caught |
| a new revision reuses the plan rows it replaces | caught |
| a no-op save creates a revision | caught |
| a no-op save still writes the Program row | caught |
| renaming counts as a structural change | caught |
| the facts write reverts the current-revision pointer | caught |
| the facts write happens outside the transaction | caught |
| validation is skipped before the write | caught |
| the built-in Program is editable in place | caught |
| a prescription change stops being structural | caught |
| control: the unmutated tree stays GREEN | not caught (as required) |

The **first** run of the script caught eleven of the twelve mutations and missed one — the
pointer-reverting mutation, because the success path of *"rename **and** a structural change in one
save"* was never asserted. That gap was a real one in this PR's own tests, not a mutation that could
not be violated: `aRenameAndAPlanChangeAreSavedTogetherAndPointAtTheNewRevision` closes it, and the
script was then re-run end to end (13/13) on the new bytes.

Two claims are deliberately *not* expressed as mutations, because they cannot be violated by an edit to
this code: the editor holds no schedule repository and no exercise-library port, so "a save does not
reconcile slots" and "the editor never mutates library data" are properties of its constructor rather
than of its body. They are asserted structurally (`ProgramEditorArchitectureTest` reads the
constructor's collaborators and the sources' tokens) and behaviourally (the slot A/B and the library
A/B are measured against the real engine and the app's own library).

## 7. Decisions to review

1. **The save envelope is not PR 5's.** `ProgramEditorResult<T>` mirrors `ProgramOperationResult<T>`
   but has no `programId` in its refusals, because a create and a copy have no Program yet. The
   *rules* are shared (`ProgramOperationRefusal`), so §4's sentence is written once.
2. **A stale draft saves; no `RevisionConflict` is raised.** A save is measured against the Program's
   **current** revision, so a draft opened from a superseded revision saves relative to what is
   stored. Nothing can be lost (revisions are immutable and append-only, and the superseded revision
   survives untouched), and the alternative — refusing — is a policy about concurrent editing that
   belongs with the UI/scheduler stage. `aDraftOpenedFromASupersededRevisionSavesRelativeToWhatIsStored`
   records the behaviour.
3. **A day that is not a rest day must prescribe something.** §20 states the rule one way (*"a rest day
   is the one type that prescribes nothing"*); this reads the converse as a validation rule, so an
   empty training/mobility/posture day is an unfinished draft rather than a saveable plan. The
   alternative reading is a one-line change in `ProgramDraftValidation`.
4. **`retypingDay` refuses instead of repairing.** Turning a day with elements into a rest day fails
   loudly rather than dropping them (§20, §33); the user removes the elements first, one deliberate
   action each. §28's "expected states are results" governs operations on stored state; this is a pure
   value algebra and mirrors the domain's own `require` style — flipping it to a result type is cheap
   if a screen wants to present it.
5. **Planning a start date is outside the editor.** A save creates a Program with `plannedStartDate =
   null` and never writes the column; PR 5's `setPlannedStartDate` owns it, and §6 says it creates no
   revision. Whether the editor's Basics section should carry it later is a UI decision.
6. **Editing an archived Program is allowed and does not unarchive it.** The blueprint neither forbids
   it nor mentions it; no rule was invented, and the archive stamp is measured as unchanged.
7. **The test rig now nests transactions (`SAVEPOINT`).** The production runner is Room's
   `withTransaction`, which nests; the editor composes two writes in one unit and the revision's own
   write opens a unit of its own. `SqliteTestDatabase.transaction` gained the same semantics so the
   composition is decided by SQLite on the JVM exactly as it is on a device. Non-nested callers are
   unaffected, and the whole pre-existing suite was re-run on the change.
8. **Goals/focus are not modelled at all.** §7's *Goals & Focus* section needs a vocabulary the Focus
   Planner (§8) owns; a draft carries no goal.

## 8. Base

This branch is based on `origin/main` at `a92017c`, the merge commit of PR 5
(`feat/program-lifecycle`), so the lifecycle decisions and the composition root it builds on are
already in `main`. No rebase was needed.
