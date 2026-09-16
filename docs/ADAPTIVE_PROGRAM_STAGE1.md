# Adaptive Program — Stage 1

Concise architecture reference for the adaptive program shipped in Stage 1. The approved
specification is [`docs/2026-09-15-adaptive-program-stage1-design.md`](2026-09-15-adaptive-program-stage1-design.md);
this file describes what the code in the tree actually does and where its boundaries are.

## Purpose

The app adapts the existing 56-day program to observed training behaviour without giving up any of
the constraints the program already has. The engine evaluates the history the app recorded, decides
one of four adaptation states per exercise family, resolves a concrete progression step inside the
user's own allowed exercise set, and lets the existing generator build the workout. It is
deterministic and local: the same history and the same policy produce the same decision, on the
device, with no network, no machine learning and no LLM.

## Runtime pipeline

```text
Stored workout history (UserProgress, SetLog)
    ↓
SessionObservation          domain/adaptive/SessionObservation.kt
    ↓
AdaptiveSignalCalculator    exposure, adherence, consistency, per-family trend, recent load, recovery risk
    ↓
AdaptivePolicy v1           every threshold and window size, in one versioned object
    ↓
AdaptiveProgramEngine       gated state machine with hysteresis and cooldown
    ↓
AdaptiveDecision            state + actions + reason code + policy version
    ↓
ProgressionResolver         the family's own profile maps the level to a step
    ↓
ProgramConfiguration + equipment constraints   the permitted exercise set
    ↓
WorkoutGenerator            builds the concrete workout (unchanged, still authoritative)
    ↓
Biomechanical validation    unchanged, still authoritative
```

Read-path classes: `SessionHistoryAdapter` normalizes stored rows into observations,
`AdaptiveWorkoutIntegration` assembles one session's request, `SessionAdaptivePlanReader` reads the
plan a session will be generated under, and `AdaptiveSessionDecisionRecorder` writes the decisions
of a finalized session. None of them owns a training rule: the thresholds live in `AdaptivePolicy`,
the transitions in `AdaptiveProgramEngine`, the step selection in `ProgressionResolver`.

## Configuration boundary

`ProgramConfiguration` (`source = DEFAULT | CUSTOM`, `enabledExerciseIds`) describes which existing
exercises the program may use. It never creates an exercise and never edits history. It is stored in
its own DataStore (`program_configuration`), deliberately not in the `settings` store, and its
version is advanced inside a single `edit`, so a stale writer cannot rewind it.

The editor validates before it applies: hard errors (a missing required training domain, an exercise
that the user's equipment cannot support) block the change, soft warnings (reduced balance) are
shown without blocking, and the validator never re-enables, inserts or replaces an exercise on the
user's behalf.

Changes are **future-only**. A session captures its configuration when it starts, and generation,
the adaptive plan and finalization all read that captured snapshot — not the live store — so an edit
can never alter a workout that has already started.

## Persistence boundary

Two tables, added by `MIGRATION_6_7`, and nothing else:

- `family_progression_state` — the current adaptation state per `(programRevision, familyId)`:
  progression level, current exercise, state, hysteresis counters, recovery count, cooldown
  position, policy version. This row is the source of current progression truth.
- `adaptive_decision_record` — the immutable audit trail: what was ordered, for which family, in
  which cycle/program day, under which policy version. Append-only; the DAO exposes no update and no
  row-scoped delete, and the audit trail never carries user-facing text, only domain enum names.

The two are independent by design: history is not the source of current state, and current state is
not derived from history. Enum values are stored under their stable names, never their ordinals, so
an audit row written by an earlier build stays readable.

The migration is purely additive. A device upgrading from version 6 receives exactly two
`CREATE TABLE` statements: no existing table is read, rebuilt, dropped or backfilled, so every
progress, posture, set-log, body-weight, calendar and nutrition row survives the upgrade unchanged.

## Lifecycle semantics

| Action | Effect on adaptive state |
| --- | --- |
| 56-day cycle rollover | Preserved. A rollover neither resets progression nor triggers a decision. |
| Restart Current Cycle | Preserved. Only the active cycle's calendar/progress rows are cleared. |
| Start Revised Program | New program revision starts from baseline; the previous revision's state and history stay readable. |
| Full Reset | Cleared, in the same transaction as the rest of the program record — seven tables: `user_progress`, `posture_session_progress`, `program_day_state`, `set_log`, `body_weight_log`, `family_progression_state`, `adaptive_decision_record`. Nutrition (`meal_cycles`, `meals`, `shopping_items`) is preserved. |

Each of these is all-or-nothing: the deletes run inside one Room transaction, so a failure partway
through leaves nothing half-cleared.

## Policy and progression are separate

`AdaptivePolicy` decides *whether* a stimulus change is warranted (PROGRESS / HOLD / REGRESS /
RECOVERY, with confirmation windows, recovery entry/exit gates and a progression cooldown).
`ProgressionResolver` decides *which concrete step* that warrants inside the family's own profile and
the user's permitted set. The profile table (`PilotProgressionProfiles`) maps a level to the family's
own axis — variation, volume, duration — so there is no universal meaning for `+1`, and it reuses
existing exercise ids rather than duplicating exercise metadata. When no permitted step exists, the
family holds: the resolver never bypasses the user's selection and never widens the permitted set.

## Current limitations

- Progression profiles ship for the six pilot families (`pushups`, `squats`, `lunges`, `plank`,
  `pullups`, `glute_bridge`). A family without a profile holds; it is not adapted.
- `recentAbandonment` is contract-true — a `PARTIAL` session with a finish stamp — but today's
  persistence records a finish stamp only for a completed day, so the flag reads `false` in
  practice. The instrumentation decision is open, not worked around.
- Recovery risk is program workload plus observed deterioration. It is not a physiological or
  clinical measurement.
- Policy thresholds are v1 choices, versioned in `AdaptivePolicy`, not physiological truths.
- Adaptive state, actions and reason codes are not surfaced as user-facing text yet; they are stored
  as the domain's own enum names.

## Stage 2 and later (not implemented)

Explicitly outside Stage 1, and not present in this tree:

- camera-based movement assessment and pose estimation;
- automated movement-quality scoring;
- machine-learning models;
- LLM/API calls of any kind (including explanations for a decision);
- generated exercises outside the existing exercise library;
- any change to the 56-day calendar model.

`reasonCode` is a stable enum-like value rather than prose, which leaves room for a future
explanation layer without letting such a layer make the training decision. `MovementAssessment` is
the reserved future input to the engine; it is not an input today.
