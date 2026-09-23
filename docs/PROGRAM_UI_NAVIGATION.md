# §30 step 14 — Settings / navigation / Program UI integration

This stage makes the Program System the app's **user-facing** Program architecture. It adds no domain
contract, changes no Revision, Scheduler, Session, Adaptive or transfer rule, and removes nothing: §30
step 15 is the Legacy Removal stage, and what stayed here is recorded below with its owner.

Everything below is measured; the numbers are in “Verification”, and every claim has a test named.

---

## 1. The navigation structure

One `NavHost`, one `NavController`, one place where destinations are declared (`MainActivity.kt`), and the
route constants sit with the app's other routes in `MainViewModel`'s companion. No nested graph, no second
controller, no navigation library. Every route carries a **stable identifier or a simple value** — a
`programId`, or one of §2's two mode names:

```text
Settings (existing bottom-bar destination)
  └─ programs                        Programs hub
       ├─ programs/my-programs       §21 My Programs
       │    └─ programs/detail/{programId}      §22 Program Detail
       │         ├─ programs/editor/edit/{programId}   §7 Edit
       │         └─ programs/editor/copy/{programId}   §4 Copy
       ├─ programs/create            §7's two entry paths
       │    ├─ programs/editor/create/MANUAL
       │    └─ programs/editor/create/GENERATED
       └─ programs/import            §5 Import (picker → review → confirm)
```

The Settings screen gained a **Programs** section, which is the entry point. It stopped being the hidden
primary entry into the Program architecture.

---

## 2. Screens, and which service each action calls

| screen | what it is | the actions it offers, and who owns them |
| --- | --- | --- |
| `ProgramsScreen` | the hub: My Programs / Create / Import | none — it navigates |
| `ProgramCreateChoiceScreen` | §7's `Build it myself` / `Build for me` | none — it opens the editor with the draft's mode |
| `MyProgramsScreen` | §21's management list | **Select** → `ProgramLifecycleService.selectProgram`; a row opens Detail |
| `ProgramDetailScreen` | §22's current-state management | **Select, Rename, Archive / Unarchive, Delete, Start / Pause / Resume / Finish** → `ProgramLifecycleService`; **Edit** → `ProgramEditorService.editDraft`; **Copy** → `ProgramEditorService.copyDraft`; **Share** → `ProgramExportService.export` then §11's platform boundary |
| `ProgramEditorScreen` | §7's draft-first editor and its Review step | every edit → `ProgramDraftEditor` through `ProgramEditorService`; **Review** → `ProgramEditorService.review`; **Save** → `ProgramSaveService.save` (the creation remediation: Create/Copy goes through §27's whole creation unit — Program + first Revision + the Scheduler's initial slots, anchored to the request's date — and an edit performs the revision plus the reconciliation pass; the state holder never calls `ProgramEditorService.save` directly, pinned by `ProgramsArchitectureTest.theStateHolderSavesThroughTheSaveOrchestrationAndRunsNoPassItself`); **start date (create/copy)** → held in `ProgramsUiState.draftPlannedStartDate`, beside the draft |
| `ProgramImportScreen` | §5's import flow | the picker and the bounded read → `platform/ProgramDocumentImport`; review → `ProgramImportService.review`; confirm → `ProgramImportService.save`; Share is not here (it is on Detail/My Programs) |

`ui/programs/ProgramsController.kt` is the single state holder: the Compose screens render its
`ProgramsUiState` and call its actions, and its constructor takes **exactly** the seven application
services (the six §30 step 14 wired plus `ProgramSaveService`, the creation remediation's §27 Save), the
two UI ports (the exercise catalogue and the share target) and the two §26 ports (`Clock`, `ZoneId`).
There is no DAO, no Room entity, no repository, no `AppState` write, no revision construction, no
scheduling and no JSON in it — `ProgramsArchitectureTest` pins that against the sources **and** against the
compiled constructor.

The UI holds **no second source of truth**: after every operation that succeeded it re-reads the list (and
the open Detail) from the services, and `selectedProgramId` is the service's answer, never a local flag.

### The actions that carry their outcome back to the screen

Two actions have to decide something *after* they ran, so the controller returns §15's own classification of
what happened and the screen branches on it — no global state, no exception, no rule in the UI:

```text
delete(programId): ProgramNotice
  Done     the Program is gone          → the Detail closes and the screen navigates back
  Refused  §3's built-in Program, or    → the Detail stays open, the refusal's sentence is shown,
           §29's IN_PROGRESS session      nothing navigates
  Failed   a storage failure            → the Detail stays open, the failure is shown, nothing navigates
```

`suspend fun delete(...)` therefore returns the notice it published, and `ProgramDetailScreen` leaves the
screen only when that notice is a `Done`. The rule that refused the delete stays in
`ProgramLifecycleService`; the screen only reads the answer. The screen's half of this contract is asserted
against its source by `ProgramsArchitectureTest`, because this project has no Compose test harness.

The planned start date has its own notice (`Planned start date saved`), because saving a date is not a
rename — and, like every other non-structural change, it creates no Revision.

---

## 3. The imported Program's planned start date

§30 step 13 left this as the stage's one product-visible choice (`docs/PROGRAM_IMPORT_EXPORT.md`,
decision 5). It is now the user's:

* **default: today**, in the composition root's calendar (`Clock` + `ZoneId`, the container's own values);
* the user may pick **any other date** through the Material date picker, and the choice is held in the UI
  state until the confirmation;
* the chosen date is part of the **import request** — `ProgramImportService.save(draft, makeActive,
  plannedStartDate)` — not a second transaction. §27's creation unit stays one unit, and the
  **initial Slots the Scheduler decides are anchored to that date**, which is why the date travels with
  the creation rather than being written afterwards (importing first and moving the date would leave the
  first opportunities anchored to a date the user never chose);
* the date is a **plan**, not a fact: the lifecycle stays `NOT_STARTED` and `actualStartDate` stays `null`;
* **nothing about it is serialized**: the transfer format and its `formatVersion` are unchanged, the JSON
  Transfer DTO has no date field, and the reviewed draft is not modified by the choice;
* `ProgramImportService.save`'s parameter defaults to `null`, which preserves §30 step 13's behaviour
  (the day it arrived) for any caller that does not choose.

### The same rule on Create and Copy — the editor's creation request

The editor got the identical configuration item (the creation remediation, §1 of the brief):

* a **separate section** on the create and copy entries — *Planned start date* — with the hint that
  says what it is: a **plan, not a start** (`programs_editor_start_date_hint`). The edit entry does
  not show it: an existing Program's date keeps its own owner,
  `ProgramLifecycleService.setPlannedStartDate` (a change that creates no Revision), and no parallel
  date model was added;
* **no explicit choice** is a first-class state: `ProgramsUiState.draftPlannedStartDate` stays `null`
  and `ProgramSaveService.save` resolves it to **today from the injected `Clock` + `ZoneId` at Save
  time** — not when the screen opened, and never chosen by the Scheduler;
* the choice lives **beside the draft, not inside it**: it is not part of `ProgramEditorDraft` /
  `ProgramStructure`, so picking or changing it creates no Revision (§6), and it never starts the
  Program — the lifecycle stays `NOT_STARTED`, `actualStartDate` `null`;
* it travels **in the same call** that creates the Program and its slots, so the initial opportunities
  are anchored to the date the user actually chose (the reason the import's date travels the same way).

## 4. The activation checkbox

`Make this program active` is §5's checkbox, and it **defaults OFF**:

* **OFF** — the selection is not touched at all: not cleared, not compared, not rewritten;
* **ON** — the imported Program becomes the selected one through `ProgramLifecycleService.selectProgram`
  (the layer that owns selection), inside the **same transaction** as the creation. The UI never writes
  `AppState`.

Importing the same file twice still creates two independent Programs, each with the date it was imported
with, and either of them can be the selected one.

---

### The notice lifecycle: shown, awaited, then cleared (P2 UI-01)

`ProgramNoticeHost` clears the controller's notice **after** `showSnackbar` completes, not before.
The effect is keyed on the notice, so clearing it mid-effect used to cancel that very coroutine at its
first suspension point — `Generation unavailable`, a refusal, a storage failure and the save/import
successes could all vanish unread. The order is now
`notice published → dismiss any old snackbar → showSnackbar(...) awaited → onShown()`, which for a
`Done` means after the short duration and for the other three classes after the user pressed OK. The
domain error taxonomy is untouched; only *when* the controller may clear it changed.
`ProgramsArchitectureTest.theNoticeHostAwaitsTheSnackbarBeforeItClearsTheNotice` pins the order.

### The working draft survives recreation (P2 UI-03)

The editor's seed runs through `ProgramsController.seedEditor(seedKey) { … }`: the route-derived key
names the flow, and a seed whose key matches the already-open working draft is skipped — a rotation,
a configuration change or the screen simply reappearing can no longer replace a half-edited draft with
a fresh empty one. A *different* flow still replaces it (entering `edit <id>` after an abandoned
`create` shows the Program, not the stale draft), and Discard/Save clear the key with the draft. The
**residual gap is process death**, recorded in §6 below rather than papered over.

### Home loads its Program on every open (P1)

`Programs → Home` no longer renders the state holder's initial value: `HomeScreen` triggers
`viewModel.refreshHomeProgram()` in a `LaunchedEffect` keyed on the holder — the same mechanism
`ProgressScreen` already used for its own controller — and `MainViewModel.refreshHomeProgram` runs
`ProgramHomeController.load()`. The read itself stays in the controller (selected Program → the
Scheduler's next opportunity → the calendar's counts); the screen touches no service. With a created,
selected Program and its initial slots, Home now shows the Program and the next workout, and **Start**
becomes available. `ProgramsArchitectureTest.homeIsLoadedByALifecycleAwareEffectWhenTheScreenOpens`
pins the call site; `ProgramHomeControllerTest` measures the controller's behaviour.

## 5. What was removed, replaced, or kept — the Settings audit

Each legacy Settings control was audited against §7's three questions. The target Program System is now the
user-facing authority; nothing was deleted, because §30 step 15 is where legacy code leaves.

| legacy control | represented by the target system? | what this stage did |
| --- | --- | --- |
| **Settings → Custom Program** (`CustomProgramScreen`) | **No.** It edits `ProgramConfiguration` — *which exercises the shipped workout generator may use for its own family/equipment filter* — which is not a Program, a Revision or a selection. The target system has no equivalent because it is a different question. | **Kept**, with its own description unchanged; it is still reached from Settings, and it is no longer the Program architecture's entry point. Documented here for §30 step 15. |
| **Program controls → Restart current cycle** | **No.** It wipes the shipped 56-day program's current-cycle rows (`ProgramDayState`) and re-seeds the template grid; the target system has no cycle concept. | **Kept**, labelled by a new sentence that says which program these controls act on. §30 step 15 removes them with the shipped generation they belong to. |
| **Program controls → Start revised program** | **Partly.** The *intent* (start again from a revised plan) is the target system's `Copy`/`Edit` → new Revision → `Start`. Replacing the control here would silently re-point the user at a different program than the one it has always acted on. | **Kept**, same label and same note; the target path is offered beside it through Programs. |
| **Program controls → Full reset** | Not a program operation at all: it clears the app's stores. §12 keeps global maintenance in Settings. | **Kept unchanged.** |

No permanent dual UI: the two mechanisms act on **different** programs — the shipped generator's
56-day program and the user's Program System programs — and the Settings screen says so in the user's
language (`programs_legacy_controls_note`). The order on screen is the target entry first.

The legacy `custom-program` destination is retained for §30 step 15 and is reached from exactly one place
(the legacy callback), which `ProgramsNavigationTest` asserts.

---

## 6. Architecture gaps recorded, not filled

### The working draft does not survive process death

`workingDraft` (and its seed key and start-date choice) live in `ProgramsController`, which lives in
the view model. Configuration recreation — rotation, density/language changes, the screen
reappearing — is covered by the `seedEditor` guard above. **Process death is not**: the platform may
destroy the process without a configuration change, and nothing persists an unsaved draft, so after a
restore the editor re-seeds and the user's unsaved arrangement is gone. The honest fix is a
persistence/restore seam (a `SavedStateHandle`-style store for the draft, or a room-less serialized
form), which is its own work; until it exists this gap is recorded here and in
`ProgramsController.draftSeedKey`'s KDoc rather than being lost silently.

### The Generated entry path, and why no plan is fabricated

`GENERATED` is a **real, permanent Program mode** (§2), it is offered by the UI (`Build for me`), the draft
is created in that mode, the mode is stored as the revision's content, and a Generated draft is saved,
edited and shared like any other.

What this stage does **not** do is produce the plan automatically. The Generated Planner
(`ProgramGeneratedEditor`) plans from a *library view*: `GenerationRequest` takes candidates that each
state the **focuses they train**, their family and their prescription dimension. This app's catalogue
carries `familyId` and an `ExerciseCategory` (STRENGTH / MOBILITY / STRETCHING / POSTURE) plus a
sub-category — it does **not** carry the `PUSH / PULL / LEGS / CORE / MOBILITY / POSTURE / CONDITIONING`
classification the planner consumes. Deriving one from category or family names would be **inventing a
training fact the data does not hold**, which §9 (*"user choice > hard execution constraints > adaptive
preference"*, with no guessed metadata) and §33 forbid.

So `generateDraft()` reports `GENERATION_UNAVAILABLE` — a **capability boundary said out loud**, in the
user's language, with the sentence that explains what to do meanwhile (arrange the days and exercises by
hand) — and returns `false`. It never returns a fabricated plan, never silently does nothing, and never
downgrades the failure into a success. §30 step 12 recorded the same missing artefact from the adaptive
side (`NoExerciseFamilyClassification`, `NoDeclaredProgression`); when a persisted exercise→focus
classification exists, this is where generation plugs in.

### §22 fields left out on purpose

Program Detail shows what an application service can already answer. Two fields §22 lists have no read use
case in the target tree and are **left out rather than reconstructed from storage** (§5 of the stage
brief):

* **adaptive summary** — the target adaptive tables are written by the session runtime, and the
  composition root wires the integration with *"no declared progression"* and *"no exercise→family
  classification"*. There is nothing a Program-level summary could honestly say, so no card is filled
  with a placeholder or a fabricated verdict.
* **next Program (identity)** — `MyPrograms` exposes only the boolean `hasNextProgram` from `app_state`.
  The detail screen shows that fact and does not invent an id; a read of `nextProgramId` through the
  lifecycle layer is a later step's small addition, not a UI reconstruction.

Everything else §22 names is shown from the owner: `ProgramLifecycleService` (name, source, lifecycle,
archive, dates, current revision), the Scheduler's own `preview` (next opportunity), the Progress layer's
own counts and history (completed / missed / upcoming, recent attempts).

### The next workout has three states, not two

`nextOpportunity` alone cannot say what happened, so the detail's state carries the Scheduler's own three
outcomes apart from each other (§15, §28, §33):

```text
Success  → the Scheduler's answer, shown as the date (or as "the plan has no dates left")
Refused  → the honest absence of a next workout (no anchor yet, archived, completed): the ordinary
           "no date is planned yet" line, and no notice at all
Failure  → a SYSTEM_FAILURE: `ProgramNotice.STORAGE_FAILED` is published and the detail says the schedule
           could not be read. It is never rendered as an absence — `showsNoPlannedDate` is false whenever
           the answer was unreadable, which is what makes the two states distinguishable in the UI state
           rather than in prose.
```

---

## 7. Verification

Measured in this tree with `:app:cleanTest :app:testDebugUnitTest --rerun-tasks`, parsing the JUnit XML and
checking the newest `timestamp` against `date -u`:

```text
pristine origin/main (f3edc69)   265 classes / 2511 tests / 0F / 0E / 0S
this branch, §30 step 14         270 classes / 2573 tests / 0F / 0E / 0S   (+5 / +62)
```

The focused §30-step-14 suites:

| suite | tests | what it pins |
| --- | --- | --- |
| `ProgramImportDateTest` | 15 | the default date, the user's choice, the Program's `plannedStartDate`, the anchored initial slots, both checkbox paths, two independent imports, a failed import leaving nothing, the format carrying no date |
| `ProgramsControllerTest` | 23 | the list projection, select / rename / archive / unarchive / delete and the lifecycle, §4's Standard-Program protections, the editor's save / no-op / review / copy paths, the Generated boundary, the Detail's reads, the export-and-share path |
| `ProgramsArchitectureTest` | 10 | §16's layers, §13's ViewModel boundary, the constructor census, the platform call sites, Compose only in the screens |
| `ProgramsNavigationTest` | 7 | one graph, one controller, every destination and route, identifier-only arguments, the Settings entry, the legacy route's demotion |
| `ProgramsLocalizationTest` | 7 | no hardcoded copy, every referenced key in all three locales, translations that are translations, no declared-but-unused key, the required vocabulary |

Three earlier stages' guards were **revised, not relaxed**, and each revision is recorded where it lives:

1. `ProgramTransferArchitectureTest` — `noUiOrViewModelSourceReachesTheTransferStageYet` became
   `theUiReachesTheTransferStageOnlyThroughTheServicesItIsHanded`: the UI now *uses* the import and export
   services and still **constructs neither them nor the codec**;
2. `ProgramEditorArchitectureTest`, `ProgramSchedulerArchitectureTest` — the "nothing in the UI names it
   yet" half became "nothing above the composition root **constructs** one", plus a non-vacuity assertion
   that the UI does reach the node it is handed;
3. `ProgressArchitectureTest` — `nothingAboveTheCompositionRootReachesTheProgressLayerYet` became
   `nothingAboveTheCompositionRootConstructsTheProgressLayerAndTheUiOnlyReadsIt`.

RED evidence: `scripts/program-14-red-mutations.sh` — one mutation per rule this document states, a control
row that must stay GREEN, and a `md5sum -c` restore of every mutated source. Its result is recorded in the
PR description.

---

## 8. What §30 step 15 inherits

* the legacy `CustomProgramScreen` destination and its `MainViewModel` bridges (`ProgramConfiguration`);
* the three legacy program controls in Settings and their `MaintenanceResult` plumbing;
* the shipped 56-day program's state (`ProgramDayState`, the stored cycle number) and the legacy
  adaptive generation (`AdaptiveRepository` over `family_progression_state` /
  `adaptive_decision_record`), which the composition root still wires side by side with the target one;
* the target-tree gaps §30 step 12 recorded (a persisted family ladder and an exercise→family
  classification), which are also what would let the Generated path produce a plan.
