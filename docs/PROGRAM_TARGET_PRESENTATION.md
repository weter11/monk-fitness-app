# Program target occurrence presentation — Stage 7

## Purpose

Stage 7 closes the semantic gap between the pure target pipeline and the persisted `WorkoutSlot` model. It adds one additive, pure value and mapping operation:

```text
PlannedOccurrence
        ↓ explicit workoutId → ProgramDayId binding
TargetOccurrencePresentation
        ↓ future integration only
WorkoutSlot
```

Stage 7 does not invoke Stages 2–6, create a `WorkoutSlot`, mint a `SlotId`, write Room, or wire `ProgramScheduler`, `SlotPlanner`, or `ScheduleCalendar`.

## Two identities

The identities are deliberately distinct:

```text
Target occurrence identity
    =
PlannedOccurrence.occurrenceKey

Program presentation identity
    =
ProgramDayId
```

`occurrenceKey` remains the identity of the target occurrence. `ProgramDayId` names the concrete plan day that a future scheduler integration may present. Stage 7 never substitutes one for the other, decodes one from the other, or infers either from a date, cycle, list position, workout text, hash, UUID, or storage order.

## Explicit binding

`TargetProgramDayBinding` is the caller-supplied input that maps a target `workoutId` to a typed `ProgramDayId`:

```kotlin
TargetProgramDayBinding(
    workoutId = "strength",
    programDayId = ProgramDayId("program-day-7")
)
```

The mapping is not discovered by the presenter. It is not read from Room, a repository, a revision, a clock, or the UI. `ProgramDayId` is the final ProgramDay identity; the binding's `workoutId` is only the target-side lookup vocabulary.

Blank binding identities are rejected. The same `workoutId` is rejected when supplied more than once, whether the duplicate points to the same day or conflicting days. No last-write-wins map or silent deduplication is permitted.

## Presentation rule

For each `PlannedOccurrence`, the presenter resolves every `OccurrenceComponent.workoutId` through the explicit binding.

The conservative repository rule is:

```text
one PlannedOccurrence must resolve to exactly one ProgramDayId
```

A multi-component occurrence is valid only when all components resolve to the same `ProgramDayId`. Its complete original component payload is retained; components are canonically ordered by rule identity and workout identity so component input order cannot change equality-identical output.

If components resolve to multiple distinct `ProgramDayId` values, presentation fails with the explicit typed refusal `MultiDayProgramOccurrence`. This is deliberate because the current `WorkoutSlot` has exactly one `programDayId` field. There is no hidden primary day, no first/last-component selection, no smallest/largest identity choice, and no secondary-component loss.

A missing binding fails with `MissingProgramDayBinding`; conflicting or duplicate bindings fail with their own typed refusals. Invalid occurrence identities and duplicate target occurrence keys are also rejected. Invalid input is never converted into an empty result.

The operation returns presentations ordered by:

```text
plannedFor ascending
then occurrenceKey ascending
```

Bindings and occurrence input may be supplied in any order. Repeated calls with the same values return equality-identical output. Input collections and original `PlannedOccurrence` values are not mutated.

## Ownership boundary

Stage 7 owns presentation identity compatibility only. It does not own date resolution, occurrence composition, reconciliation, temporal scheduling policy, persistence, slot identity, program lifecycle, session execution, adaptive behavior, or UI state.

The presenter consumes the already-produced `PlannedOccurrence` values. It does not call:

- `TargetScheduleResolver`;
- `TargetOccurrenceComposer`;
- `TargetOccurrenceReconciler`;
- `TargetPlanner`; or
- `TargetSchedulePolicy`.

The architecture test pins the exact target package census, pure dependency shape, forbidden platform/runtime/scheduler references, no string-based `ProgramDayId` inference, and no mutable singleton state.

## Verification map

- `TargetOccurrencePresenterTest`: single and multiple occurrences; same-day multi-component presentation; multi-day rejection; first/last-component protection; missing, conflicting, duplicate, and blank bindings; invalid and duplicate occurrence identities; reordered bindings, occurrences, and components; canonical ordering; input immutability; unchanged key/date; empty input; repeatability; and explicit typed ProgramDay output.
- `TargetOccurrencePresenterArchitectureTest`: exact Stage 2–7 target file census, pure JVM/domain boundary, no platform/storage/runtime/UI/scheduler/clock/random/filesystem/network references, no prior-stage invocation or ProgramDay string inference, no mutable singleton state, and no production wiring outside the target package.
- `scripts/program-stage7-red-mutations.sh`: control plus mutations for first/last selection, dropped components, missing/conflicting bindings, multi-day collapse, component/binding/occurrence order dependence, string inference, key rewriting, scheduler reach, and mutable state; every mutation must be caught and the source restored byte-identically.
