# Monk Fitness — Program System Implementation Blueprint

## 0. Mission

Replace the current legacy single-program/cycle architecture with a multi-Program system that is:

* free and open-source;
* deterministic;
* explainable;
* user-controlled;
* testable without Android/Room where possible;
* safe against silent data loss;
* extensible without rewriting the storage model.

No legacy program/history migration is required. Treat the new architecture as a clean target schema.

---

## 1. Core identity model

```text
Exercise Library
    ↓ exerciseId

Program
    ↓ programId

ProgramRevision
    ↓ revisionId

ProgramDay
    ↓ programDayId

ProgramExercise
```

Runtime:

```text
ProgramWorkoutSlot
    ↓
WorkoutSession
    ↓
SessionExercise
    ↓
SetLog
```

Adaptive:

```text
ProgramRevision
    ↓
FamilyProgressionState

WorkoutSession
    ↓
AdaptiveDecisionRecord
    ↓
AdaptiveAdjustment
```

Lifecycle:

```text
Program
    ↓
ProgramPause[]
```

Rules:

* `programId` is stable for the whole life of a saved Program.
* Structural edits create a new immutable Revision, not a new Program.
* Rename/description/selection/lifecycle do not create Revision.
* Started Sessions are bound to Program + Revision + complete Session Snapshot.
* Date, cycle number and day number are never Program ownership identifiers.

---

## 2. Program modes

Only two permanent modes:

```text
MANUAL
GENERATED
```

"Semi-automatic" is a creation/editing workflow, not a third mode.

### MANUAL

Fully free-form:

* arbitrary duration;
* arbitrary named days;
* arbitrary schedule;
* arbitrary exercise order;
* arbitrary reps/time;
* per-set prescriptions;
* repeated use of the same exercise;
* no Generator limits;
* no Adaptive Engine.

Manual programs still have full Session history/statistics.

### GENERATED

Generator builds the plan, but the final plan belongs to the user.

Manual edits inside Generated remain Generated and become overrides.

Explicit mode switching creates a new Revision.

---

## 3. Lifecycle

```text
NOT_STARTED
    ↓ Start
RUNNING
    ↕
PAUSED
    ↓ Finish / natural end
COMPLETED
```

Archive is separate:

```text archivedAt != null
```

It is not a lifecycle state.

Rules:

* planned start date does not automatically start a Program;
* `actualStartDate` is factual;
* Pause freezes active program time and missed-opportunity logic;
* Completed cannot directly Resume;
* next Program is user-selected;
* auto-start is explicit and never resumes a paused next Program;
* deleting selected Program selects Standard Program as technical fallback;
* archiving selected Program requires choosing another selected Program.

---

## 4. Program operations

Every saved Program supports:

* Activate/Select
* Edit
* Share
* Rename
* Archive/Unarchive
* Delete

Built-in Standard Program:

* can be selected;
* can be copied;
* can be shared;
* cannot be directly edited;
* cannot be deleted.

Editing Standard means creating a user copy first.

Delete is explicit and removes all Program-owned state.

---

## 5. Import / Export

Export:

* UTF-8 JSON;
* filename such as `MonkFitnessProgram.mfp.json`;
* required `formatVersion`;
* app/schema/export metadata;
* Program definition/configuration only.

Never export:

* `programId`;
* active/selected state;
* current day;
* history;
* statistics;
* SetLogs;
* Adaptive state;
* Adaptive decision history;
* in-progress Session state.

Import:

```text
JSON
 ↓
parse
 ↓
formatVersion validation
 ↓
schema validation
 ↓
exerciseId validation
 ↓
semantic validation
 ↓
Import Draft
 ↓
save new Program
```

Unknown/unsupported version is rejected.

Unknown exerciseId is rejected.

Import never creates or guesses exercises.

Import always creates a new `programId`.

UI checkbox:

```text
☐ Сделать эту программу активной
```

Default OFF.

Importing the same file twice creates two independent Programs.

Android Share Sheet is the primary sharing mechanism where supported on Android 9+.

---

## 6. Revision rules

Revision is immutable.

Structural/program-behavior changes create a new Revision:

* mode;
* duration;
* frequency;
* preferred weekdays;
* schedule/distribution;
* goals/focus;
* exercise selection;
* generated plan;
* manual workout changes;
* ordering;
* prescriptions;
* rest;
* pinning;
* future overrides.

No Revision for:

* rename;
* description;
* activate/select;
* Start/Pause/Resume/Finish;
* archive;
* planned start date;
* next Program configuration.

One Save creates at most one new Revision.

No-op Save creates none.

Editor is always Draft-first.

---

## 7. Program Editor

One Editor supports Create/Edit/Copy/import review.

Structure:

```text
Basics
Schedule
Goals & Focus
Plan
Review
```

Create has two entry paths:

```text
Build for me → GENERATED
Build it myself → MANUAL
```

Generated supports:

* Generate;
* Preview;
* Edit;
* Regenerate;
* Pin;
* User Override;
* local regeneration.

`Generate`/`Regenerate` only alter Draft.

`Save` creates Revision.

Regeneration is reconciliation, not replacement:

```text
PINNED
    >
EXPLICIT USER OVERRIDE
    >
COMPATIBLE USER CHANGE
    >
PURE GENERATED CONTENT
```

Conflicts with user choices are shown explicitly.

Unpin keeps the current value; it merely makes the item eligible for future automatic change.

---

## 8. Focus Planner

Pipeline:

```text
Program
 ↓
Revision
 ↓
Scheduler
 ↓
Focus Planner
 ↓
Adaptive
 ↓
Workout Generator
 ↓
ProgramWorkoutSlot
```

Focus Planner:

* operates over the whole planning horizon;
* uses weighted exposure, not simple workout counts;
* assigns 1 primary focus and 0–2 secondary focuses per slot;
* considers target deficit, remaining horizon, recent exposure, recent load, explicit priority, adaptive signals;
* uses soft recovery penalties;
* has basic recovery guard;
* does not use universal hard 48-hour rules;
* establishes pinned/manual/fixed workouts first;
* fills remaining slots around them;
* is deterministic;
* uses deterministic tie-breaks;
* exact coefficients are not yet fixed.

Candidate focus vocabulary:

```text
PUSH
PULL
LEGS
CORE
MOBILITY
POSTURE
CONDITIONING
```

Goals:

```text
BALANCED
FOCUSED
CUSTOM
```

Custom focus percentages must sum to 100%.

---

## 9. Exercise selection

Selection considers:

* allowed exerciseIds;
* equipment;
* focus/family membership;
* adaptive preferences;
* recency;
* diversity;
* progression needs;
* user overrides/pins.

Priority:

```text
user choice
>
hard execution constraints
>
adaptive preference
>
progression need
>
recency/diversity
>
deterministic tie-break
```

No random `Random()` in key generation decisions.

Same inputs must produce the same generated result.

Repeated exercise use is allowed.

Each occurrence is its own `ProgramExercise`.

---

## 10. Prescription model

Prescription belongs to Program/Revision, not Exercise Library defaults.

Supported initial dimensions:

```text
REP_BASED
TIME_BASED
SET_BASED
DIFFICULTY_BASED
REST_BASED
```

Primary progression dimension is selected per exercise/plan element.

Examples:

```text
12 / 10 / 8 / 6
```

or:

```text
30s / 30s / 45s
```

Manual prescriptions are unrestricted by Generator ranges.

Exercise Library metadata is never mutated by Program editing.

---

## 11. Adaptive architecture

Adaptive flow:

```text
SetLog
 ↓
Observed Performance
 ↓
Exposure
 ↓
Comparable History
 ↓
Evidence + Confidence
 ↓
Recovery Context
 ↓
Progression Policy
 ↓
Individual Decision
 ↓
Aggregate Load Guard
 ↓
Adaptive Adjustment
 ↓
Future Slot
 ↓
Session Snapshot
```

`AdaptiveEngine` is a pure domain component.

It does not know Room, Android, DAO or ViewModel.

---

## 12. Exposure / Evidence

Distinguish:

```text
Workout Slot
Session
Exercise Occurrence
Exposure Observation
```

Rules:

* full execution = full Exposure;
* partial execution = partial Exposure;
* skipped exercise = no Exposure;
* cancelled Session may still contain partial Exposure;
* cancelled Session is not a completed workout;
* missed Slot is not zero performance;
* inactivity is not failure;
* different exercise variants are not automatically directly comparable;
* comparable observations require compatible exercise/progression context;
* Exercise trend, Family load and Focus exposure are different dimensions.

Evidence:

```text
INSUFFICIENT
STABLE
STRONG
```

Confidence is separate.

Do not collapse either into fake physiological precision.

---

## 13. Adaptive input signals

Use:

### Hard facts

* planned sets;
* completed sets;
* actual reps;
* actual duration;
* timestamps;
* completion/cancellation state;
* explicit user feedback.

### Derived signals

* completion ratio;
* performance relative to target;
* performance trend;
* consistency;
* shortfall;
* recent load;
* recent family/focus exposure;
* time since exposure;
* inactivity.

### User feedback

Initial simple:

```text
EASY
OK
HARD
```

Optional.

No feedback must be interpreted as "OK".

---

## 14. Recovery model

Recovery is context, not diagnosis.

Do not implement:

```text recoveryScore = 83
```

Use:

```text FAVORABLE
CAUTIOUS
UNKNOWN
```

based on:

* recent load;
* exposure;
* time since exposure;
* performance trend;
* difficulty feedback;
* inactivity;
* optional explicit user caution.

Recovery can make progression more conservative.

It cannot silently:

* move a workout;
* replace the focus;
* cancel a Program;
* rewrite history.

No universal hard 48-hour rule.

Pause/inactivity is not failure.

---

## 15. Adaptive actions

Initial action space:

```text HOLD
PROGRESS
REGRESS
CHANGE_VARIANT
CHANGE_REST
```

Rules:

* one bounded primary change per decision;
* `HOLD` is a real first-class result;
* one bad result does not automatically regress;
* repeated negative evidence can permit regression;
* progression requires sufficient evidence;
* ceiling stops progression;
* floor stops regression;
* variant changes require explicit progression relations;
* Adaptive never changes pinned choices;
* Adaptive never silently changes Focus, duration, frequency or schedule;
* Adaptive never edits historical Sessions.

---

## 16. AdaptiveAdjustment

Revision remains immutable.

Automatic adaptation uses:

```text ProgramRevision
+
AdaptiveAdjustment
=
EffectiveWorkout
```

Adjustment:

* applies to a concrete future Slot;
* is immutable;
* is one-slot scoped;
* may supersede an earlier Adjustment;
* does not create a Revision;
* is consumed by Session Snapshot creation.

New adaptive result:

```text old adjustment
→ superseding new adjustment
```

User Save converts the effective state into a new Revision.

---

## 17. Load model

No universal scalar load score.

Use:

```text LoadProfile
    volume
    intensity
    density
    exposure/context
```

Principles:

* volume is meaningful primarily within comparable exercise/family contexts;
* intensity uses progression level/relations;
* density represents work/time structure;
* Focus exposure is not physical workload;
* no invented conversion such as "1 push-up = X load units".

---

## 18. Aggregate Load Guard

Compare:

```text Baseline Session
vs
Candidate Session
```

Guard checks:

```text Exercise
Family
Focus
Session
```

and sees:

```text baseline
+
adaptive delta
+
recent context
```

Only automatic changes are guarded.

User-authored plan is not silently blocked.

Guard can:

```text PROGRESS → HOLD
```

but does not invent `REGRESS`.

Filtered-out decisions remain recorded with an explicit `NOT_APPLIED` outcome.

All tie-breaking is deterministic.

---

## 19. Session model

`WorkoutSession` is persisted runtime state.

Starting a session creates it once.

Session survives:

* screen close;
* process recreation;
* app restart;
* reboot.

Confirmed sets autosave.

`Back` does not cancel.

Explicit Cancel:

```text Session → CANCELLED
```

and history remains.

Finish:

```text Session → COMPLETED
```

One Slot may have multiple attempts, but no more than one `IN_PROGRESS` Session.

Session Snapshot is immutable.

A Session started on one date and completed later preserves both planned and actual timestamps.

---

## 20. Scheduler

Scheduler owns timing/opportunities.

```text MANUAL
    → user-defined plan
    → Scheduler

GENERATED
    → Scheduler
    → Focus Planner
    → Adaptive
    → Generator
```

Slot states:

```text PLANNED
COMPLETED
MISSED
SUPERSEDED
```

Rules:

* no automatic whole-schedule sliding after a missed workout;
* past/started Sessions never change;
* future incompatible slots become SUPERSEDED;
* missed is not the same as superseded;
* REST may exist as a Slot without Session.

Fixed weekdays and deterministic flexible frequency are supported.

Indefinite Program keeps exactly 30 days of future planning and extends it when needed.

---

## 21. Progress

Default context:

```text selectedProgramId
```

Selector also offers:

```text All Programs
```

All Programs is an aggregation view, not an entity.

Overview must stay compact.

Separate:

```text Calendar Progress
Training Progress
```

First implementation includes:

* completed/missed/upcoming;
* frequency;
* volume;
* focus distribution;
* family distribution;
* average Session duration;
* performance progression;
* Program PR view;
* Program streak;
* recent history.

Global:

* body weight;
* all-time PR;
* Overall streak.

Global values must not become Program-owned accidentally.

For Indefinite Programs:

```text Active for N days
Completed N sessions
```

No fake `N / 30` total-program progress.

---

## 22. Program Detail

Program Detail is current-state management, not full analytics.

Shows:

* name/mode/source;
* lifecycle;
* two progress measures;
* next workout;
* schedule;
* current Revision;
* recent workouts;
* adaptive summary;
* next Program;
* lifecycle actions.

Progress/history remain separate screens.

Debug mode may show:

* Program ID;
* Revision ID;
* Slot ID;
* Session ID;
* Adaptive policy;
* Evidence;
* Recovery;
* Decision/Adjustment chain;
* scheduler horizon.

---

## 23. Database target

Core entities:

```text
Program
AppState

ProgramRevision
ProgramDay
ProgramExercise

ProgramWorkoutSlot

WorkoutSession
SessionExercise
SetLog

ProgramPause

FamilyProgressionState
AdaptiveDecisionRecord
AdaptiveAdjustment
```

Global:

```text
Exercise Library
BodyWeightEntry
MealCycle
Meal
ShoppingItem
Global Settings
```

No new architecture dependency on:

```text
UserProgress
ProgramDayState
cycleNumber
sessionDate as ownership
legacy revision integer as identity
```

New domain IDs are stable typed IDs.

Revision has real `revisionId`; human-readable `revisionNumber` may also exist.

---

## 24. Room/Data boundaries

DAO:

* persistence only;
* no business logic.

Repositories:

```text
ProgramRepository
ProgramPlanRepository
ProgramScheduleRepository
WorkoutSessionRepository
AdaptiveRepository
ProgramProgressRepository
```

Global:

```text
BodyWeightRepository
NutritionRepository
ExerciseLibraryRepository
GlobalSettingsRepository
```

Do not keep one giant `WorkoutRepository`.

---

## 25. Domain/Data/UI boundaries

```text
Room Entity
    ↕
Data Mapper
    ↕
Domain Model
    ↕
UseCase
    ↕
UI Model / UiState
```

Separate import/export DTOs:

```text
JSON Transfer DTO
    ↕
Import/Export Mapper
    ↕
Domain
```

Room entities never leave Data layer.

Domain never imports Room/Android.

---

## 26. DI

Use explicit `AppContainer`.

```text
Application
    ↓
AppContainer
    ↓
Repositories / Services / UseCases
    ↓
ViewModels
```

Rules:

* AppContainer is composition root, not Service Locator;
* ViewModels never create DB/repositories;
* pure engines get plain inputs and return plain results;
* `Clock` and ID generation are injectable;
* deterministic generation does not use uncontrolled Random;
* tests can use TestAppContainer or pure domain tests.

---

## 27. Transactions

Required atomic operations:

```text
Create / Copy / Import
    → Program + Revision + initial Slots

Save Editor
    → new Revision + future-slot reconciliation

Start Workout
    → Session + complete Snapshot

Confirm Set
    → SetLog + runtime position

Complete Workout
    → Session + Slot + Adaptive state + Decisions + Adjustments

Delete Program
    → complete ownership cascade
```

`Complete Workout + Adaptive` is one transaction.

Historical data is never modified by future adaptation.

---

## 28. Error contract

Classes:

```text
EXPECTED
CONFLICT
INVALID_DATA
SYSTEM_FAILURE
```

Expected states are results, not exceptions.

Examples:

```text
NoFutureSlot
NothingToChange
RevisionConflict
ActiveSession
UnknownExercise
UnsupportedFormatVersion
DatabaseFailure
```

Errors have recoverability/action semantics:

```text
RETRY
USER_ACTION
TERMINAL
```

Do not swallow DAO exceptions into `null` or empty collections.

---

## 29. Deletion / archive

Delete cascades all Program-owned entities.

Archive:

* retains all history;
* stops future planning;
* does not create Revision.

Global data survives Program deletion.

`nextProgramId` uses `SET NULL`.

Exercise Library survives Program deletion.

Delete is atomic.

No Program may be deleted with an `IN_PROGRESS` Session.

---

## 30. Implementation order

```text
1. Domain foundation
2. New Room schema
3. DAO + mappers + repositories
4. AppContainer / DI
5. Program lifecycle + My Programs
6. Manual Editor
7. Scheduler
8. Session runtime
9. Progress / History
10. Generated Planner / Editor
11. Adaptive engine
12. Adaptive integration
13. Import / Export / Share
14. Settings / navigation cleanup
15. Legacy removal
```

Each stage must keep the project buildable.

Do not create a new/old dual architecture spread across the whole codebase.

---

## 31. Required architecture gates

Storage:

```text FK
cascade
set-null
schema verification
```

Session:

```text restore
autosave
cancel
complete
transaction rollback
```

Generated:

```text deterministic generation
regeneration reconciliation
pin preservation
override preservation
```

Adaptive:

```text golden scenarios
property tests
determinism
aggregate guard
```

Before legacy removal:

```text full integration suite
full end-to-end lifecycle
ownership destruction test
```

---

## 32. Global end-to-end acceptance scenario

```text
Create Program
→ Save Revision 1
→ Select
→ Start Program
→ Schedule Slot
→ Start Session
→ Confirm Sets
→ Complete Session
→ Adaptive Decision
→ Adjustment
→ Next Session
→ Progress update
→ Edit Program
→ Revision 2
→ old Session unchanged
→ Export
→ Import as new Program
→ delete imported Program
→ original Program remains intact
```

This is a release-gate scenario.

---

## 33. Mandatory prohibitions

Never:

```text
guess Program from date
use cycle/day as ownership
mutate Revision
silently overwrite user choices
silently replace pinned items
turn missed into zero performance
turn cancelled into completed
let Adaptive rewrite Focus
let Adaptive create Revision
let Scheduler create Session
let ViewModel write DAO directly
let Repository expose Room Entity
catch Exception and return empty result
use random nondeterministic generation
```

The architecture's guiding principle is:

```text
USER CHOICE
    >
PROGRAM STRUCTURE
    >
ADAPTIVE HEURISTICS
```

The system assists; it does not silently take control.
