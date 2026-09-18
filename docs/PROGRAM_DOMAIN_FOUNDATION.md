# Program System — Domain Foundation (PR 1)

Scope: the **domain foundation** of the multi-Program architecture — the types every later stage
(entities, mappers, repositories, scheduler, editor, adaptive engine) will depend on. This document
records which files were created and what each one is responsible for.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below
as **§N**). Implementation order: §30 step 1, *Domain foundation*.

Nothing in this change is wired to anything: no entity, no DAO, no repository, no ViewModel, no UI, no
scheduler, no generator change, no adaptive algorithm, no import/export, and no legacy code was removed
or modified. The new packages are new files only.

---

## 1. Packages

```text
com.monkfitness.app.domain.common        typed identity
com.monkfitness.app.domain.program       Program / revision / plan / slot / pause / editor draft
com.monkfitness.app.domain.prescription  what a plan element prescribes
com.monkfitness.app.domain.workout       sessions, what was presented, what was performed
com.monkfitness.app.domain.adaptive      exposure, evidence, load, recovery, the input window
com.monkfitness.app.domain.adaptive.decision  the decision vocabulary
```

The five packages are siblings under `domain/`, as the brief specifies. The sixth is a deliberate,
documented deviation — see §4.1.

## 2. Files and responsibilities

### `domain/common` — identity

| File | Responsibility |
| --- | --- |
| `TypedIds.kt` | The eleven typed ids (`ProgramId`, `RevisionId`, `ProgramDayId`, `ProgramExerciseId`, `SlotId`, `SessionId`, `SessionExerciseId`, `SetLogId`, `PauseId`, `AdjustmentId`, `DecisionId`) as inline value classes. Each is its own type, so none can be passed where another is expected; the wrapped value is opaque and may not be blank. Generation and uniqueness belong to the layer that mints an id (§26). |

### `domain/program` — the program's identity, its plan, and its opportunities

| File | Responsibility |
| --- | --- |
| `ProgramSource.kt` | Provenance: the built-in Standard Program, a user program, an imported program (§4, §5). `isBuiltIn` is the single fact the copy-before-edit rule derives from. |
| `ProgramMode.kt` | `MANUAL` / `GENERATED` and nothing else; semi-automatic is a workflow, not a mode (§2). |
| `LifecycleStatus.kt` | `NOT_STARTED` / `RUNNING` / `PAUSED` / `COMPLETED`. Archiving is a stamp, never a state (§3). |
| `ProgramDayType.kt` | What a plan day is for: training, mobility, posture-mobility, rest (§20). |
| `SlotStatus.kt` | `PLANNED` / `COMPLETED` / `MISSED` / `SUPERSEDED`, with missed and superseded kept distinct (§20). |
| `ProgramDuration.kt` | How long a revision runs: fixed days or indefinite, and the two schedule forms the architecture supports (fixed weekdays, deterministic flexible frequency) (§20, §21). |
| `Program.kt` | A saved Program: identity, name/description, source, lifecycle, current revision, stamps, planned vs actual start, archive stamp. It owns **no** mode and **no** plan (those are revision content) and **no** selection flag (§6, §21). |
| `ProgramRevision.kt` | The immutable structural snapshot: revision id + human-readable number, mode, duration, schedule, the plan days, creation stamp. A saved revision always carries a plan, and its days are numbered `1..n` in order (§6, §23). |
| `ProgramDay.kt` | One day of a revision's plan: position (an ordering, never an identity), optional name, type, ordered plan elements. Every occurrence keeps its own identity; a rest day prescribes nothing (§9, §20). |
| `ProgramExercise.kt` | One occurrence of one exercise in a day: the library key, the prescription, the origin (`GENERATED` / `USER_AUTHORED`) and the pin — the two facts regeneration reconciles on (§7). |
| `WorkoutSlot.kt` | One planned opportunity: which plan day it presents, the date it was planned for, its status, and the sessions attempted for it. It records **no amount of work**, which is what makes "missed" unable to read as zero (§12, §20). |
| `ProgramPause.kt` | One pause interval of a program, open or closed (§3). |
| `ProgramEditorDraft.kt` | The editor's work in progress: what it was opened from, the working name/description/mode/duration/schedule/plan. It has no revision identity and may be incomplete — the only place an unfinished plan is representable (§6, §7). |

### `domain/prescription` — what a plan element asks for

| File | Responsibility |
| --- | --- |
| `Prescription.kt` | The sealed prescription model and the five dimension names (§10). Only `REP_BASED` and `TIME_BASED` have a subtype; `SET_BASED`, `DIFFICULTY_BASED` and `REST_BASED` are named and deliberately unimplemented. |
| `RepPrescription.kt` | Repetitions **per set** — `12 / 10 / 8 / 6` is one prescription, not a uniform value with overrides. |
| `TimePrescription.kt` | Duration **per set**, in seconds. |

### `domain/workout` — sessions and what was presented

| File | Responsibility |
| --- | --- |
| `SessionStatus.kt` | `IN_PROGRESS` / `COMPLETED` / `CANCELLED`; cancellation is not completion (§19). |
| `SetResult.kt` | One confirmed set in one unit. A set that was not performed is absent, never a zero row (§12, §19). |
| `SessionExercise.kt` | One occurrence as it ran: the plan element it presents, the prescription that was presented, and the confirmed sets accumulated as `1..n` in the unit the prescription was written in. `skipped` means no results (§12). |
| `EffectiveWorkout.kt` | What should be presented for a slot — `revision + adjustment`, recomputable, carrying the adjustments that are in effect (§16). |
| `WorkoutSessionSnapshot.kt` | The immutable record of what **was** presented when a session started, frozen at capture; it is the only plan that session may run from (§19). |
| `WorkoutSession.kt` | The started session: bound to its own snapshot, its slot, program and revision; status and finish stamp kept consistent; its occurrences must be ones the snapshot presented. Planned date and actual timestamps are both preserved (§19). |

### `domain/adaptive` — what happened, and what may be concluded from it

| File | Responsibility |
| --- | --- |
| `AdaptiveScope.kt` | The four granularities an adaptive fact is stated at — exercise, family, focus, session (§18). A focus is named by id; the focus vocabulary belongs to the Focus Planner (§8). |
| `LoadProfile.kt` | Load in four separated dimensions — volume (per unit, never summed), intensity (level per family), density (work/rest structure), exposure/context — with **no total, no weight, no conversion** (§17). |
| `AdaptiveEvidence.kt` | `EvidenceLevel` (`INSUFFICIENT` / `STABLE` / `STRONG`), `ConfidenceLevel` (kept separate from evidence), `RecoveryContext` (`FAVORABLE` / `CAUTIOUS` / `UNKNOWN`) (§12, §14). |
| `ExposureObservation.kt` | One exercise occurrence as observed: at least one completed set, a level that agrees with the counts, and no `NONE` — no exposure is the absence of an observation (§12). |
| `AdaptiveInputSnapshot.kt` | The frozen facts of one decision window: identity, window start, observations in chronological order (one per occurrence), evidence/confidence/recovery, baseline and recent load. It derives nothing (§11, §13, §18). |

### `domain/adaptive/decision` — the decision vocabulary

| File | Responsibility |
| --- | --- |
| `AdaptiveAction.kt` | `HOLD` / `PROGRESS` / `REGRESS` / `CHANGE_VARIANT` / `CHANGE_REST`, and `DecisionOutcome` (`APPLIED` / `NOT_APPLIED`) — the guard's filtered-out decisions stay recorded (§15, §18). |
| `AdaptiveTarget.kt` | What a decision is about, with the scope derived from the target so the two can never disagree (§18). |
| `AdaptiveDecision.kt` | One auditable decision: grounds, target, action, outcome, and the adjustment it produced — applied exactly when an adjustment exists, and `HOLD` never applied (§15, §16, §22). |
| `AdaptiveAdjustment.kt` | One slot-scoped before/after of a single presented element, immutable, superseding by reference, never creating a revision (§16). |

## 3. Invariants

Unit tests live beside the packages they pin:

| Suite | Pins |
| --- | --- |
| `domain/common/TypedIdTest` | The eleven ids are distinct types (no pair is assignable to another), carry their own value, reject blankness, are inline value classes, and the set is exactly the set the architecture names. |
| `domain/program/ProgramFoundationTest` | The vocabularies are exactly the documented values; the field sets of `Program` and `ProgramRevision` (no mode or plan on the program, no date or cycle ownership on the revision); lifecycle vs archive; planned vs actual start; plan numbering; occurrence identity; slot and pause consistency. |
| `domain/program/ProgramRevisionImmutabilityTest` | `ProgramRevision` is a data class, every field is final, no setter exists, every constructor property is a `val`, the plan is a read-only list, and `copy` produces a new value. |
| `domain/program/ProgramEditorDraftTest` | The draft has no revision identity, may hold an incomplete plan a revision refuses, distinguishes create/copy/edit/import by its own fields, and represents generated and manual editing alike. |
| `domain/prescription/PrescriptionFoundationTest` | Per-set repetitions and per-set durations; the five dimensions with exactly two implemented; no reserved-dimension type exists. |
| `domain/workout/SessionFoundationTest` | Recomputation of an effective workout does not move a snapshot; a session is bound to its snapshot, slot, program and revision; status and finish stamp agree; sets accumulate without gaps and in the prescribed unit. |
| `domain/workout/MissedAndCancelledAreNotZeroPerformanceTest` | A slot and a session carry no amount to zero; a missed slot may carry a cancelled attempt whose partial work survives; no observation exists for work that did not happen; a zero-work set result is not representable. |
| `domain/adaptive/AdaptiveFoundationTest` | Evidence vs confidence vs recovery; the four load channels with no scalar and no floating point; no derivation in the foundation; the frozen window's ordering rules. |
| `domain/adaptive/AdaptiveDecisionFoundationTest` | The action space; target scope derivation; applied ⇔ adjustment; `HOLD` never applied; adjustment before/after identity; the supersede chain. |
| `domain/ProgramDomainPurityTest` | No Android, Room, `androidx`, data-layer, UI or ViewModel import anywhere in the foundation; no Room annotation; no `var` or mutable collection; no floating point, load score, coefficient or `Random`. |

## 4. Decisions worth reviewing

### 4.1 The adaptive decision vocabulary nests one level (`domain/adaptive/decision`)

The Stage-1 adaptive engine already declares `AdaptiveAction` and `AdaptiveDecision` **in**
`domain/adaptive` (`AdaptiveDecision.kt`, used by the pilot progression and policy code). Kotlin does
not allow a second top-level declaration of either name in that package, and this change must not
remove or rename legacy code.

Three ways out were available: nest only the colliding vocabulary (chosen), put the whole adaptive
foundation in a new sibling package, or rename the legacy pair. The first keeps every new file under
the `domain/adaptive` path the brief names, adds no second namespace, and touches nothing that exists;
it costs one sub-package whose members are exactly the four types about *deciding*. When the legacy
stage is retired (§30 step 15), they can be promoted to the parent package in a change that only moves
files.

This is a **decision for the owner**, recorded here rather than settled silently.

### 4.2 Deferred on purpose

* **Goals and focus allocation** (§8) — the goal vocabulary (`BALANCED` / `FOCUSED` / `CUSTOM`), the
  custom-percentage sum rule and the focus vocabulary itself belong to the Focus Planner stage. A
  revision therefore carries mode, duration, schedule and plan today, and the editor draft carries the
  same four. Adding the goal/focus content is an additive change to both types, not a re-shaping.
* **Rest** — `REST_BASED` is named and unimplemented (§10), so no rest field appears anywhere, and
  `AdaptiveAction.CHANGE_REST` exists with no adjustment able to express it yet.
* **The load guard, the signal layer, the policy, the scheduler, the generator reconciliation** — all
  later stages; this change provides their inputs and outputs as values only.
* **Persistence** — no entity, DAO, mapper or migration. Storage vocabulary decisions (column names,
  converters, scope keys) are made where the schema is defined (§23, §30 step 2).

### 4.3 `java.time` and `minSdk`

The foundation uses `LocalDate` / `Instant` / `DayOfWeek` as the brief asks. The module's `minSdk` is
24 and core-library desugaring is not enabled, so `java.time` needs API 26 at runtime — a pre-existing
condition: `domain/usecase/ProgramCalendar.kt` and several repositories already use `java.time` in
production code. This change adds no new exposure, but it is worth an explicit owner decision before
the new schema lands.

### 4.4 What the types deliberately do not contain

No `cycleNumber`, no session date, no revision integer as identity, no selection or "active" flag and
no progress scalar in any model — those are the legacy ownership patterns the architecture removes
(§23, §33). The field-set assertions in `ProgramFoundationTest` fail if one is reintroduced.
