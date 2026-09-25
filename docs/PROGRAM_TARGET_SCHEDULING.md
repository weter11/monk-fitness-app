# Program target scheduling — Stage 6

## Purpose and ownership

Stage 6 adds one pure temporal scheduling decision over the `TargetPlan` produced by Stage 5. It is additive and does not wire into the existing scheduler or any persistence, UI, runtime, adaptive, import/export, or generator layer.

The pipeline ownership is explicit:

- Stage 2: WHEN
- Stage 3: WHICH WORK IS TOGETHER
- Stage 4: IDENTITY RECONCILIATION
- Stage 5: PIPELINE ORCHESTRATION
- Stage 6: TEMPORAL SCHEDULING STATUS

Stage 6 consumes the Stage 5 plan and does not invoke `TargetScheduleResolver`, `TargetOccurrenceComposer`, or `TargetOccurrenceReconciler` again. It does not reimplement their date, composition, or identity rules.

## State matrix

`asOf` is an explicit `LocalDate`. An occurrence is passed only when `plannedFor < asOf`; an occurrence planned for `asOf` remains open.

| Occurrence state | Not paused | Paused |
| --- | --- | --- |
| Past planned occurrence | `MISSED` | `SUPERSEDED(PASSED_WHILE_PAUSED)` |
| Today/future planned occurrence, replacement still contains identity | `RETAINED` | `RETAINED` |
| Current/future planned occurrence, replacement removed identity | `SUPERSEDED(TARGET_NO_LONGER_PRESENTS_OCCURRENCE)` | same; a pause is not a revision deletion |
| Newly added target occurrence | `CREATED` when not paused and not past | not created; remains in `targetPlan.planned` |

Historical `STARTED`, `COMPLETED`, and `CANCELLED` occurrences are historical facts. They are preserved exactly, including their actual results, and are not reinterpreted as missed or superseded.

## Revision-history rule

A revision change cannot rewrite the past. If an old planned occurrence has `plannedFor < asOf`, Stage 6 reports it as:

- `MISSED` when no pause covers the occurrence date;
- `SUPERSEDED(PASSED_WHILE_PAUSED)` when a pause covers that date.

Stage 4 may classify that old occurrence as superseded because it is identity-blind to scheduling time. Stage 6 deliberately reinterprets that classification and must not expose Stage 4's past superseded bucket unchanged. For current/future occurrences, Stage 4's target-identity supersession remains authoritative.

## Pause semantics

Stage 6 uses the existing `ProgramPauseWindow.covers(plannedFor)` predicate. Overlapping windows are valid. An open pause is already represented by the caller's pause model; no current-time lookup is needed.

Pause handling belongs here because it is a temporal interpretation of an already planned opportunity, not target identity reconciliation. A pause does not remove a target identity and must not permanently destroy a future opportunity. It only suppresses materialization of a new occurrence while the date is paused, and classifies a past paused opportunity as superseded rather than missed.

## Result buckets and identity

The decision keeps these buckets explicit:

- `preserved`: historical execution facts, unchanged;
- `retained`: still-present current/future planned identities;
- `created`: eligible additions from `TargetPlan.reconciliation.added`;
- `superseded`: paused past opportunities and current/future identities removed by replacement;
- `missed`: past planned opportunities not covered by a pause.

Membership uses `PlannedOccurrence.occurrenceKey` only. Stage 6 does not regenerate keys or match by workout, date, components, position, rule, plan day, exercise, or frequency. Every output bucket is ordered by `plannedFor` and then `occurrenceKey`; superseded values use reason name only as the final tie-breaker.

## Pure boundary

`TargetSchedulePolicy.decide` accepts a target plan, existing occurrences, an explicit `asOf`, and pause windows. It has no clock, `Instant`, `ZoneId`, repository, scheduler, persistence, database, UI, runtime, randomness, or mutable singleton state. The caller owns conversion of persisted pause instants into `ProgramPauseWindow` and owns any persistence or scheduling integration.

Stage 6 does not calculate the planning horizon, calculate `TargetScheduleWindow`, read `ProgramRevision`, read Program lifecycle, convert `Instant` to `LocalDate`, persist slots, mint slot IDs, create `WorkoutSession` values, mark Room rows, call `ProgramScheduler`, call `SlotPlanner`, or call `ScheduleCalendar`.

## Idempotence and immutability

Identical `targetPlan`, `existing`, `asOf`, and `pauses` inputs return equality-identical decisions. Applying the same decision to conceptual current material does not create duplicate material. Caller collections and the target plan are not mutated.

## Verification map

- `TargetSchedulePolicyTest`: empty input, all historical states, retained today/future, revision supersession, past missed and pause supersession, paused creation suppression, past creation suppression, overlapping pauses, ordering, input non-mutation, repeatability, direct matrix parity, exact actual preservation, and Stage 4 reinterpretation.
- `TargetSchedulePolicyArchitectureTest`: exact target-package census, pure dependencies, forbidden references, no prior-stage invocation, no clock, and no mutable state.
- `scripts/program-stage6-red-mutations.sh`: control plus semantic, ordering, identity, clock, scheduler, and mutable-state mutations, with byte-identical restoration.
