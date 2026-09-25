# Program target slot persistence integration — Stage 10

## Boundary

Stage 10 adds the first orchestration and persistence bridge around the pure target pipeline:

```text
TargetScheduleDecision
        ↓
TargetOccurrencePresentation
        ↓
TargetSlotMaterializer
        ↓
WorkoutSlot
        ↓
ProgramScheduleRepository.addSlots(...)
```

The bridge is `TargetScheduleSlotPersister` in `domain/usecase`. It receives a `TargetScheduleDecision` and already-presented `TargetOccurrencePresentation` values. It does not resolve dates, compose occurrences, reconcile plans, decide temporal policy, bind ProgramDays, or invoke Stages 2–9 again.

## Two identities

Target membership and storage identity remain separate:

```text
(programId, targetOccurrenceKey)
    = target membership lookup

slotId
    = persisted storage identity
```

`targetOccurrenceKey` is copied from `TargetOccurrencePresentation.occurrence.occurrenceKey`. A new `SlotId` is minted only at the integration boundary through the existing `IdGenerator`, then passed to the Stage 9 `TargetSlotMaterializer`.

The persister never derives a slot identity from an occurrence key, date, ProgramDay, revision, hash, list position, or UUID. Same-date occurrences remain independent because their target keys are distinct.

## Lookup and idempotency

Before materializing each presentation, the bridge performs exactly:

```text
ProgramScheduleRepository.slotByTargetOccurrenceKey(
    programId,
    targetOccurrenceKey
)
```

There is no fallback lookup by `plannedFor`, `programDayId`, `revisionId`, `slotId`, or occupied date.

An existing matching target slot is returned in `retained` exactly as stored. Its `slotId`, status, attempts, and completion state are not rewritten. A repeated pass therefore creates no second row and does not mint a replacement id.

If the same `(programId, targetOccurrenceKey)` exists with a different Program, revision, ProgramDay, date, or target key, the bridge throws `TargetSlotPersistenceException`. It never silently overwrites, repairs, deletes, or reinserts the row.

## New slots

For a missing target key, the bridge creates a fresh `SlotId` through `IdGenerator` and calls the existing pure Stage 9 materializer. It does not duplicate the mapping logic.

The new value is therefore exactly:

```text
status          = PLANNED
attempts        = empty
completedAt     = null
targetOccurrenceKey = presented occurrenceKey
```

The target domain remains free of persistence, Room, DAO, repository, clock, random, UUID, Android, and runtime dependencies.

## Repository boundary

All reads and writes go through `ProgramScheduleRepository`:

- target lookup: `slotByTargetOccurrenceKey`
- insertion: `addSlots`

The integration does not import or call Room, `ProgramWorkoutSlotDao`, `ProgramWorkoutSlotEntity`, SQLite, or any data-local class directly.

## Legacy coexistence

The new bridge is wired beside the existing `ProgramScheduler` in `AppContainer`; it is not called by that scheduler. `ProgramScheduler`, `SlotPlanner`, `ScheduleCalendar`, and their date-only occupancy behavior are unchanged. The target path and legacy scheduler remain separate generations while Phase 10 establishes the target persistence seam.

## Scope

Stage 10 adds no scheduler algorithm, resolver, composer, reconciler, temporal policy, adaptive logic, session creation, workout execution, statistics, UI, lifecycle, pause policy, migration, or database schema change.

The schema seam is the one already supplied by Phase 8: `WorkoutSlot.targetOccurrenceKey` and the unique `(programId, targetOccurrenceKey)` index.

## Verification map

- `TargetScheduleSlotPersisterTest`: missing-key creation, repeated idempotency, same-date independence, Program scoping, started/completed state preservation, storage identity preservation, no date fallback, typed payload conflict, Stage 9 mapping, input immutability, and no semantic drift.
- `TargetScheduleSlotPersisterArchitectureTest`: integration placement, repository/ID boundary, no direct Room/DAO/entity access, exact target lookup, no date-only membership, materializer-only mapping, no prior target-stage calls, and legacy scheduler isolation.
- `scripts/program-stage10-red-mutations.sh`: control plus deliberate lookup, id-generation, execution-state, target-key, duplicate-insertion, same-date, direct-storage, materializer, conflict, and in-place-mutation failures; all mutations must be caught and the source restored byte-identically.
