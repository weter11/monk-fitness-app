# Program target slot materialization — Stage 9

## Purpose

Stage 9 closes the pure semantic bridge from the target pipeline to the persisted opportunity model:

```text
TargetOccurrencePresentation
        ↓
TargetSlotMaterialization
        ↓
WorkoutSlot
```

The three shapes have distinct responsibilities:

```text
TargetOccurrencePresentation
    = semantic target occurrence + explicit ProgramDay identity

TargetSlotMaterialization
    = deterministic value transformation

WorkoutSlot
    = persisted opportunity
```

## Two identities

Storage identity and semantic membership identity remain separate:

```text
slotId
    = persisted storage identity

targetOccurrenceKey
    = target semantic membership identity

slotId
    !=
targetOccurrenceKey
```

`slotId` arrives as a caller-issued `SlotId`. The materializer never derives it from `occurrenceKey`, `ProgramDayId`, a date, a hash, a UUID, list position, or execution state.

`targetOccurrenceKey` is the existing `PlannedOccurrence.occurrenceKey`, copied byte-for-byte. It is never decoded, trimmed, normalized, reconstructed from the date, or replaced with `ProgramDayId`.

The persistence identity `(programId, targetOccurrenceKey)` therefore describes target membership while `slotId` remains the row identity. Two target occurrences planned for the same date can and must materialize independently when their occurrence keys or ProgramDay identities differ.

## Exact mapping

`TargetSlotMaterializer.materialize` performs only this transformation:

```text
slotId
    = input.slotId

programId
    = input.programId

revisionId
    = input.revisionId

programDayId
    = input.presentation.programDayId

plannedFor
    = input.presentation.occurrence.plannedFor

status
    = PLANNED

attempts
    = emptyList()

completedAt
    = null

targetOccurrenceKey
    = input.presentation.occurrence.occurrenceKey
```

No component ordering, occurrence key, date, or ProgramDay identity is changed. Repeated materialization of the same input returns an equality-identical `WorkoutSlot` and does not mutate the original presentation or occurrence.

## Validation

Blank `SlotId`, `ProgramId`, `RevisionId`, and `ProgramDayId` values are unrepresentable because their typed identity constructors reject blank text. The materializer rejects a blank occurrence key and blank occurrence component identities before constructing a slot. It creates no fallback identity and does not repair malformed input.

## Fresh execution state

A newly materialized target slot is always:

```text
status = PLANNED
attempts = empty
completedAt = null
```

Materialization does not create a session or attempt, mark a slot missed, superseded, or completed, or copy execution state from another slot. Those transitions remain runtime/persistence responsibilities.

## Ownership boundary

Stage 9 does not persist.

Stage 9 does not schedule.

Stage 9 does not reconcile.

Stage 9 does not generate identities.

The operation has no Room, DAO, repository, scheduler, planner, calendar, clock, filesystem, network, random, UUID, Android, or runtime dependency. It does not call `TargetScheduleResolver`, `TargetOccurrenceComposer`, `TargetOccurrenceReconciler`, `TargetPlanner`, `TargetSchedulePolicy`, or `TargetOccurrencePresenter`. The presentation is already complete when it enters the materializer.

ID generation remains the responsibility of an orchestration/integration layer. Stage 9 only transfers caller-supplied storage identities and the existing semantic target identity into a `WorkoutSlot` value.

## Verification map

- `TargetSlotMaterializerTest`: one occurrence to one slot; multiple independent slots; same-date independent target slots; exact key/date/ProgramDay preservation; provided storage identity preservation; fresh `PLANNED` state; empty attempts; null completion; blank identity rejection; repeatability; input immutability; and no identity derivation.
- `TargetSlotMaterializerArchitectureTest`: exact target package census, materializer package location, pure JVM/domain types, no platform/storage/runtime dependencies, no prior target-stage calls, no mutable singleton state, no identity construction/string inference, exact field mapping, and no production wiring outside `domain.program.target`.
- `scripts/program-stage9-red-mutations.sh`: a GREEN control plus deliberate wrong slot, Program, revision, ProgramDay, date, rewritten key, date-derived key, ProgramDay-derived key, execution-state, UUID/random, scheduler-reference, and mutable-global-state mutations. Every mutation must be caught, and the production source must be restored byte-identically.
