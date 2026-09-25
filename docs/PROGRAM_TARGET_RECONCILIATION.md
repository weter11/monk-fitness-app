# Program target occurrence reconciliation — Stage 4

## Scope

Stage 4 adds `TargetOccurrenceReconciler`, a pure target-domain operation that compares existing occurrence facts with a replacement target plan. It is additive and intentionally not wired into the existing scheduler, persistence, runtime, UI, adaptive, progress, import/export, or generator layers.

Stage 2 answers WHEN.
Stage 3 answers WHICH RESOLVED WORK IS TOGETHER.
Stage 4 answers WHAT HAPPENS TO EXISTING OCCURRENCE FACTS WHEN THE TARGET PLAN CHANGES.

The flow remains:

```text
TargetScheduleResolver
        ↓
ResolvedScheduleOccurrence
        ↓
TargetOccurrenceComposer
        ↓
PlannedOccurrence
        +
ExistingOccurrence
        ↓
TargetOccurrenceReconciler
        ↓
TargetOccurrenceReconciliation
```

## Identity and membership

`PlannedOccurrence.occurrenceKey` is the sole membership key. Stage 4 does not infer identity from workout ids, dates, component counts, component order, cadence, frequency, program day, exercise family, or list position. A changed Stage 3 composition membership is a different identity: the old planned occurrence is superseded and the new identity is added.

Duplicate keys are rejected with `IllegalArgumentException`. Neither input is deduplicated, merged, or repaired silently.

## Existing facts and replacement material

An existing occurrence is a historical fact whenever `execution != OccurrenceExecution.PLANNED`. This includes `STARTED`, `COMPLETED`, and `CANCELLED`. Such a value is preserved exactly as supplied, including occurrence identity, planned date, components, execution state, and actual results. A replacement cannot overwrite it, even when the identity is equal.

Only an existing `PLANNED` occurrence is replaceable:

- same key in replacement: retain the existing occurrence; it is neither superseded nor added again;
- key absent from replacement: place the existing planned occurrence in `superseded`;
- replacement key absent from existing: place the replacement in `added`.

The result is explicit:

```text
preserved  = existing non-PLANNED facts
superseded = existing PLANNED occurrences removed by identity
added      = replacement occurrences with new identities
```

No historical actual is reconstructed, rewritten, or discarded. `CANCELLED` preservation is an explicit Stage 4 clarification of the broader existing rule `execution != PLANNED → preserved`; it extends the Stage 1 `ScheduleEditReconciler` parity cases without changing that legacy type.

## Payload consistency

If an existing key is also present in replacement, the replacement payload must equal the existing `PlannedOccurrence` exactly. A same key with a different planned date or component list is rejected as an inconsistent identity. The reconciler does not merge payloads or attempt semantic similarity matching.

## Determinism and idempotence

Each result bucket is ordered canonically by `plannedFor` ascending, then `occurrenceKey` ascending. The result does not depend on input list order, hash-map order, or hash-set order.

Applying the same replacement again to the resulting current material is a no-op for future material: no identity is added again and no already-retained planned occurrence is superseded. Input collections are only read; they are not mutated.

## Boundaries

Stage 4 has no date resolution, date arithmetic, window discovery, horizon calculation, weekly scheduling, pause interpretation, pause shifting, pause-aware renumbering, missed detection, or `asOf` handling. It does not produce `MISSED`; that decision belongs to scheduling. It does not call Stage 2 or Stage 3 and does not call the shipped `ProgramScheduler`, `SlotPlanner`, or `ScheduleCalendar`.

The reconciler uses only Kotlin/JVM values, stored `LocalDate` values through the existing domain value types, and pure `domain.program` values. It has no mutable singleton state, cache, ambient accumulator, clock, random source, UUID, filesystem, network, or side effect.

`ScheduleEditReconciler` remains untouched. There is no reverse target-to-legacy dependency.

## Existing scheduler and Stage 1 compatibility

The existing `ProgramScheduler`, `SlotPlanner`, and `ScheduleCalendar` remain production-unchanged and behaviorally untouched. The Stage 1 compatibility test confirms that `STARTED` and `COMPLETED` occurrences remain preserved and replacement remains future material. Stage 4 formalizes that historical preservation rule more broadly to include `CANCELLED` and to distinguish superseded from added future occurrences.

## Verification map

- `TargetOccurrenceReconcilerTest`: the semantic matrix, duplicate and conflicting-key validation, ordering, input immutability, historical actual preservation, repeatability, idempotence, and Stage 1 parity.
- `TargetOccurrenceReconciliationArchitectureTest`: pure dependency shape, forbidden scheduler/legacy references, no ambient inputs/state, exact target package census, and no production wiring.
- `TargetScheduleArchitectureTest` and `TargetOccurrenceCompositionArchitectureTest`: existing Stage 2/3 purity checks retained and their exact target package census extended to the reconciler.
- `scripts/program-stage4-red-mutations.sh`: control GREEN plus deliberate mutations covering planned/factual bucketing, addition, duplicates, conflicts, ordering, actual preservation, resolver reach, clock, and random identity. Every mutation restores the source byte-identically.
