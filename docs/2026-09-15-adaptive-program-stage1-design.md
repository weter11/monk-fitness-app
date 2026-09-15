# Adaptive Program Stage 1 — Design Specification

## Status

Approved design. Stage 1 is a deterministic, local adaptive-program engine. No machine learning and no LLM are required or included. Future movement assessment and optional LLM integration are explicitly out of scope for Stage 1 but have reserved architectural boundaries.

## Goal

Turn the existing 56-day workout program into an adaptive program that adjusts progression according to observed training behavior and performance while preserving the existing calendar, biomechanics, exercise library, and user control.

## Product model

The system does not ask an LLM to invent workouts. It evaluates the user's observed training history and produces a deterministic adaptation decision. The existing `WorkoutGenerator` remains responsible for selecting concrete exercises and constructing a workout. Existing biomechanical constraints remain authoritative.

Pipeline:

```text
Stored workout history
        ↓
SessionObservation
        ↓
SignalCalculator
        ↓
AdaptiveSignals
        ↓
AdaptivePolicy v1
        ↓
State Machine
        ↓
AdaptationDecision
        ↓
FamilyProgressionState
        ↓
ProgressionResolver
        ↓
ProgramConfiguration constraints
        ↓
WorkoutGenerator
        ↓
Biomechanical validation
        ↓
Final Workout
```

The adaptive engine is domain logic. It must not depend directly on Android UI, Room, DataStore, or rendering.

## Existing architecture to preserve

The application is Kotlin/Android, Jetpack Compose, MVVM, Room, DataStore, and a biomechanically driven animation/validation stack. The repository already has a `WorkoutGenerator`, exercise metadata, difficulty adjustment support, progress persistence, and per-set logging. The current exercise model contains base/phase ranges, timer durations, categories, subcategories, and equipment requirements. The existing `Exercise.applyDifficultyAdjustment()` clamps adjustments to `-2..+2`; Stage 1 should reuse this mechanism rather than introduce a second low-level reps/timer adjustment implementation.

The 56-day calendar and cycle semantics remain independent from adaptive progression. Calendar rollover must not reset or automatically increase progression.

## Stage 1 scope

Included:

- local deterministic adaptive engine;
- signal calculation from observed program history;
- four adaptation states: `PROGRESS`, `HOLD`, `REGRESS`, `RECOVERY`;
- gated State Machine with hysteresis;
- configurable `AdaptivePolicy` v1;
- exercise/family-level performance trends;
- family-level progression state from `-2..+2`;
- progression profiles and resolver;
- custom exercise configuration UI and validation;
- application of changes from the next not-yet-started workout;
- default/custom configuration source state;
- reset custom exercise selection to default without deleting workout history;
- decision history with policy version and reason code;
- correction of workout instrumentation so recorded repetition/timer values represent actual completed work where the UI can observe it;
- unit tests for signal math, policy transitions, progression resolution, custom configuration validation, and lifecycle interactions.

Explicitly excluded:

- ML models;
- LLM/API calls;
- camera-based user movement assessment;
- automated `movementQuality` scoring;
- physiological diagnosis or medical recovery assessment;
- physics or real-time movement simulation;
- new workout exercises generated outside the existing exercise library;
- changing the 56-day calendar model.

## Future roadmap boundaries

### Stage 2 — Reference Humanoid

Reference Humanoid becomes both an expert visual teaching representation and the future foundation for diagnostic visualization. It is independent of the adaptive engine.

### Stage 3 — User Movement Assessment

Potential future pipeline:

```text
Camera
  ↓
Pose estimation
  ↓
Canonical 33-node skeleton
  ↓
Movement analysis
  ↓
MovementAssessment
```

`MovementAssessment` can later become an additional input to the adaptive engine. It is not an input in Stage 1.

### Stage 4 — Closed-loop adaptation

Potential future pipeline:

```text
MovementAssessment
+
Performance history
+
Adherence / completion
+
Biomechanical profile
        ↓
AdaptiveProgramEngine
        ↓
Personalized adaptation
```

The Stage 1 engine must therefore avoid an interface that makes future movement assessment impossible to add, while not inventing a value for it today.

## Program Configuration

The user receives a dedicated `Custom Program` exercise editor.

### Configuration model

```text
ProgramConfiguration
├── source = DEFAULT | CUSTOM
└── enabledExerciseIds: Set<String>
```

A custom configuration controls which existing exercises are eligible for generation. It does not create new exercises and does not alter workout history.

### UI behavior

The editor:

- groups exercises by family;
- supports tri-state family presentation: all enabled, none enabled, partially enabled;
- provides search/filtering appropriate to the existing exercise library;
- edits a local draft rather than persisting every checkbox immediately;
- provides `Cancel` and `Apply`;
- validates the proposed configuration before applying it;
- displays hard errors for invalid program configurations;
- displays soft warnings for configurations that remain technically valid but reduce training balance;
- marks the program `CUSTOM` after a successful non-default change;
- provides `Reset to default` as a distinct confirmed action.

Changes apply to the next workout that has not yet started. An already-started or completed workout must not change retroactively.

`Reset to default` restores the standard exercise selection only. It does not reset calendar position, training history, adaptive progression state, or program revision.

### Configuration validation

Hard constraints prevent clearly invalid programs, including a missing required training domain or an exercise that cannot be used under the user's available equipment. Soft constraints may warn about poor balance without blocking the user.

The configuration validator must never silently re-enable an exercise.

If a custom configuration removes the next valid progression option, the resolver must not bypass the user's selection. It may use another permitted progression axis when the policy allows it; otherwise the family remains `HOLD`.

## SessionObservation

`SessionObservation` is a domain representation built from the workout that was presented plus the user's actual session events. It is not required to be a permanent Room entity in Stage 1.

```text
SessionObservation
├── cycleNumber
├── programDay
├── startedAt
├── finishedAt
├── outcome
│   ├── NOT_STARTED
│   ├── PARTIAL
│   └── COMPLETED
├── plannedExercises
├── completedExercises
├── plannedWork
├── actualWork
└── exerciseResults[]
```

### ExerciseResult

```text
ExerciseResult
├── exerciseId
├── plannedSets
├── completedSets
├── plannedReps
├── completedReps
├── plannedDurationSeconds
├── completedDurationSeconds
└── exposure
```

For repetition exercises:

```text
exposure = completedReps / plannedReps
```

For timer exercises:

```text
exposure = completedDurationSeconds / plannedDurationSeconds
```

Exposure is clamped to `0..1` for Stage 1 adaptation calculations. `COMPLETED` does not imply exposure `1.0`; a workout can be completed with partial planned work. `PARTIAL` requires actual work greater than zero and failure to satisfy the workout completion condition. `NOT_STARTED` has zero actual work.

An abandoned workout is represented as a partial session with an abandonment signal. Abandonment is evidence, not an automatic regression trigger.

## Source data and instrumentation

Existing `UserProgress` remains the day-level completion source. Existing `SetLog` remains the raw per-exercise/per-set source and currently stores exercise ID, completed repetitions, duration, timestamp, and session date.

Stage 1 must correct any session logging path where repetition exercises currently record a configured maximum rather than the actually completed repetitions. The adaptive engine must not infer actual work from a configured target when the UI can directly observe the user's completed amount.

No new generic session table is required unless implementation discovers a concrete persistence need that cannot be satisfied by existing history plus decision records.

## Signal calculation

### ExposureScore

Use the last six eligible sessions. Newer sessions receive weights `1,2,3,4,5,6`, with `6` assigned to the newest observation.

```text
ExposureScore = Σ(exposure × weight) / Σ(weight)
```

This prevents old sessions from dominating current adaptation while retaining a short history.

### Adherence

Use a 14-calendar-day window.

A session counts as meaningfully started when at least one exercise is completed or at least 10% of planned work is completed.

```text
adherence = meaningful planned sessions attended / planned sessions
```

The denominator is the set of planned workout opportunities in the window.

### Consistency

Use the latest eight planned workout opportunities. Consistency is intentionally bucketed instead of pretending to have medical or statistical precision:

```text
HIGH   = >= 75% attended consistently
MEDIUM = 50–74%
LOW    = < 50%
```

The implementation must use deterministic, documented gap handling so the same history always produces the same bucket.

### Performance trend

Performance is evaluated per exercise/family, not only across the entire workout. The trend uses the latest 3–5 meaningful exposures for the same exercise or family, normalized to the planned target for that observation.

Policy thresholds:

```text
trend >= +0.05 → POSITIVE
trend <= -0.05 → NEGATIVE
otherwise      → STABLE
```

The exact slope/normalization algorithm must be documented in code and unit-tested; it must not compare unrelated exercise units directly.

### Recent load

Use rolling seven-day workload and compare it with the preceding seven-day workload window.

```text
loadRatio = recent7 / previous7
```

Policy buckets:

```text
<= +10% increase → NORMAL
+10% to +20%     → ELEVATED
> +20%           → HIGH
```

This is program workload, not a physiological diagnosis.

### Recovery risk

Recovery risk is derived from high recent load combined with observed deterioration. It must not claim to measure clinical recovery.

Primary Stage 1 flags:

```text
poorCompletionStreak
performanceDecline
highRecentLoad
recentAbandonment
```

A high recovery-risk result requires high recent load plus deterioration, or a documented prolonged combination of low exposure and declining performance.

## Adaptive State Machine

### PROGRESS

Indicates sustained evidence that the current stimulus can be increased.

Eligibility requires:

- at least 4 eligible sessions;
- `ExposureScore >= 0.80`;
- adherence `>= 0.70`;
- performance trend is not negative;
- recent load is not HIGH;
- progress conditions are satisfied in two consecutive decision windows.

Completion does not need to reach 100%.

### HOLD

The default and normal state for insufficient evidence, mixed signals, or a stable plateau. HOLD does not mean failure.

### REGRESS

Requires sustained evidence of inability to handle the current stimulus:

- at least 3 eligible sessions;
- `ExposureScore < 0.70`;
- negative performance trend;
- confirmation across two consecutive decision windows, except a documented strong negative safety case.

A single bad workout must not trigger regression.

### RECOVERY

Entered only under stronger conditions than HOLD. Primary condition:

- recent load is HIGH;
- plus performance deterioration or strongly reduced exposure;

or an equivalent documented prolonged high-risk pattern.

Recovery can be entered from one qualifying high-risk decision window. Leaving RECOVERY requires two qualifying sessions and transitions to HOLD, not directly to PROGRESS.

### Hysteresis and cooldown

The engine must prevent oscillation:

- PROGRESS requires two consecutive qualifying decision windows.
- REGRESS requires two consecutive qualifying decision windows unless implementation documents an exceptional strong-negative safety rule.
- RECOVERY can enter after one qualifying high-risk window.
- RECOVERY exits only after two qualifying sessions.
- After a progression level change, at least two eligible sessions must occur before another progression change for that same family.

## AdaptiveProgram contract

The engine is a pure domain service:

```text
AdaptiveProgramEngine.evaluate(input): AdaptiveDecision
```

Input:

```text
AdaptiveProgramInput
├── programDay
├── programCycle
├── programType
├── enabledExerciseIds
├── recentSessions
├── currentProgressionStates
└── policy
```

`AdaptiveProgramInput` contains already-normalized domain data. The engine does not query Room/DataStore itself.

Intermediate model:

```text
AdaptiveSignals
├── exposureScore
├── adherence
├── consistency
├── performanceTrend(s)
├── recentLoad
└── recoveryRisk
```

Decision model:

```text
AdaptiveDecision
├── state
├── actions[]
├── reasonCode
└── policyVersion
```

`reasonCode` is a stable enum-like value, not user-facing prose. UI/localization maps it to text. This also creates a structured future input for an optional LLM explanation layer without allowing the LLM to make the training decision.

## AdaptationAction and progression

Adaptation is performed at exercise/family scope, not only globally.

A decision may contain different results for different families, for example push-ups `PROGRESS`, squats `HOLD`, and lunges `REGRESS`.

`AdaptivePolicy` decides what adaptation state/action is warranted. `ProgressionResolver` decides which concrete progression step is valid. `WorkoutGenerator` then constructs the workout.

### FamilyProgressionState

```text
FamilyProgressionState
├── familyId
├── progressionLevel       // -2 ... +2
├── currentExerciseId?
├── adaptationState
├── updatedAt
└── policyVersion
```

The progression level is an abstract family state, not a universal reps delta.

### Progression profiles

Each family has a profile describing how levels map to its progression axis:

```text
REP_VARIATION
TIMER
MOBILITY
CORRECTIVE
MIXED
```

A profile may map level to exercise variation, volume, duration, or a combination. The mapping is family-specific; there is no universal meaning for `+1` across all exercise families.

For Stage 1, initial pilot profiles should cover representative families such as:

```text
pushups
squats
lunges
plank
pullups
glute_bridge
```

The profile table must reuse existing exercise definitions rather than invent duplicate Exercise metadata.

### Existing difficulty adjustment

The existing `Exercise.applyDifficultyAdjustment()` mechanism remains the low-level parameter adjustment mechanism. The adaptive engine may select a small progression step, but the resolver/profile decides whether that step means a harder variation, additional volume, duration, or another valid change. Do not create a second generic reps/timer adjustment engine.

## Recovery semantics

RECOVERY does not destroy accumulated progression level. For example:

```text
family level = +1
state = RECOVERY
```

means the normal progression level remains +1, but the next session uses a recovery profile. Once recovery resolves, the state goes to HOLD and the progression level remains unless a later adaptive decision explicitly changes it.

## Lifecycle semantics

### Normal 56-day rollover

Cycle 56 → next cycle Day 1 does not reset FamilyProgressionState and does not itself trigger progression.

### Restart Current Cycle

Resets the current cycle's calendar/progress according to existing C3 semantics while preserving adaptive progression state. Repeating a cycle does not imply loss of physical capability.

### Start Revised Program

Creates a new program revision and resets adaptive progression states to baseline for the new revision while retaining historical results from earlier revisions. The old history remains available for analysis.

### Full Reset

Existing C3 Full Reset semantics apply: adaptive progression, workout history, custom configuration, and revision state are reset to the true first-launch condition. Nutrition data that C3 explicitly preserves remains preserved.

## Custom Program interaction with adaptation

Custom configuration defines the allowed exercise graph. The adaptive engine cannot use disabled exercises.

If a user's enabled selection is:

```text
knee → standard → wide
```

then progression cannot jump to a disabled military or decline variation. If no valid next progression step exists, the resolver may use an allowed alternative progression axis such as volume when the profile explicitly allows it; otherwise the family remains HOLD.

When a family is disabled, its FamilyProgressionState is retained. Re-enabling a previously disabled family does not silently erase its state, but the first eligible post-reenable session is treated conservatively as HOLD/assessment before normal adaptation resumes.

## Persistence

Adaptive state is persistent across ordinary sessions and cycle rollover.

Decision history is stored separately from current state:

```text
AdaptiveDecisionRecord
├── id
├── cycleNumber
├── programDay
├── timestamp
├── previousState
├── newState
├── action(s)
├── reasonCode
└── policyVersion
```

Decision records are immutable audit/history entries. They are not the source of current progression truth.

A configuration version should be attached to a persisted custom program configuration so the exact set of enabled exercises used by a future workout can be identified.

A workout that is already started must retain its effective configuration snapshot. No configuration edit may mutate an in-progress workout.

## Policy versioning

All numeric thresholds and window sizes belong to a single `AdaptivePolicy` object. Stage 1 policy version is `1`.

Do not scatter thresholds through `if` statements across multiple classes.

Initial values:

```text
eligible session window       6
adherence window              14 days
consistency opportunities     8
progress minimum evidence     4 eligible sessions
progress exposure             >= 0.80
progress adherence            >= 0.70
regress exposure              < 0.70
strong low exposure           < 0.60
performance positive          >= +0.05
performance negative          <= -0.05
recent load normal            <= +10%
recent load elevated          +10% to +20%
recent load high              > +20%
progress confirmation         2 windows
regress confirmation          2 windows
recovery entry                1 qualifying window
recovery exit                 2 qualifying sessions
progression cooldown          2 eligible sessions
```

These values are v1 policy choices, not physiological truths. They must be easy to replace with a future policy version.

## Recommended package boundaries

Exact paths should follow the repository's existing domain/data/ui conventions, but responsibilities should be separated approximately as follows:

```text
domain/adaptive/
    AdaptiveProgramEngine
    AdaptivePolicy
    AdaptiveSignals
    AdaptiveSignalCalculator
    AdaptiveDecision
    AdaptiveState
    ProgressionResolver
    ProgressionProfile
    model types

data/model/
    FamilyProgressionState entity
    AdaptiveDecisionRecord entity
    ProgramConfiguration model if persistence model lives here

data/local/
    DAOs for adaptive state, decision history, and configuration

data/repository/
    repository methods for persistence and transactional configuration updates
ui/
    Custom Program editor and validation presentation
```

Do not split or refactor unrelated existing files unless required to establish these boundaries cleanly.

## Tests

Stage 1 must be implemented test-first and must include deterministic unit coverage for:

### Session observation and exposure

- reps-based exposure;
- timer-based exposure;
- clamping above 1.0;
- zero planned work handling;
- completed workout with less than 1.0 exposure;
- partial workout;
- not-started workout;
- abandoned workout;
- actual-vs-planned logging correctness.

### Signal calculation

- weighted six-session ExposureScore;
- 14-day adherence;
- meaningful-start threshold;
- consistency buckets;
- positive/stable/negative performance trends;
- recent seven-day load comparison;
- recovery-risk flags.

### State machine

- insufficient evidence → HOLD;
- sustained positive signal → PROGRESS;
- one bad workout does not regress;
- sustained negative signal → REGRESS;
- high-load deterioration → RECOVERY;
- recovery returns to HOLD;
- hysteresis prevents oscillation;
- progression cooldown is enforced.

### Progression

- family-specific level mapping;
- `-2..+2` boundaries;
- reuse of existing difficulty adjustment;
- no progression into disabled exercise;
- fallback to allowed volume/duration only where explicitly permitted;
- impossible custom progression → HOLD;
- disabled/re-enabled family state retention.

### Configuration

- default configuration loads correctly;
- draft Cancel does not persist;
- Apply persists only validated configuration;
- invalid hard-constraint configuration is rejected;
- soft warnings do not block valid configurations;
- custom source state is set after a real change;
- Reset to default restores selection only;
- edits apply only to future not-yet-started workouts;
- in-progress workout remains unchanged.

### Lifecycle

- cycle rollover preserves progression;
- Restart Current Cycle preserves progression;
- Start Revised Program resets progression baseline but preserves old history;
- Full Reset clears adaptive state/configuration according to C3 semantics.

### Invariants

The test suite should assert at least:

```text
AI cannot invent exercises.
AI cannot select disabled exercises.
AI cannot bypass equipment constraints.
AI cannot modify calendar day/cycle.
AI cannot retroactively change a started workout.
AI cannot turn a single poor session into regression.
AI cannot turn high-load deterioration into normal progression.
LLM/ML is not required for any Stage 1 decision.
```

## Acceptance criteria

Stage 1 is complete only when:

1. A user can safely customize the set of exercises in the dedicated editor.
2. The user can restore the default set without losing progress/history.
3. Custom configuration changes affect only future workouts.
4. The adaptive engine deterministically evaluates observed history and returns one of the four defined states with structured reason/action data.
5. Progression can differ by exercise/family rather than changing the entire program uniformly.
6. Existing exercise, equipment, biomechanical, calendar, and C3 reset constraints remain authoritative.
7. Partial completion can contribute positively to progression when the multi-signal policy supports it.
8. No movement-quality inference is performed in Stage 1.
9. All policy parameters are centralized and versioned.
10. Tests cover the mathematical model, state transitions, custom configuration, progression resolution, and lifecycle semantics.
11. The feature works fully offline and introduces no LLM/ML dependency.
