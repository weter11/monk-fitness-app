# Program target schedule — Stage 2

## Scope

Stage 2 adds a pure target schedule value model and deterministic bounded resolver. It is an additive semantic layer under `domain/program/target`; it does not wire or replace the shipped §30 step-7 scheduler.

Stage 1 stopped at the pure vocabulary because a target schedule describes when work is intended, but it does not by itself enumerate concrete calendar dates. That boundary prevents a weekly frequency, a literal weekday set, an interval, and a derived exclusion from being confused while keeping persistence and runtime concerns out of the domain contract.

## Model and resolver

- `TargetSchedule` is the immutable rule identity, workout identity, Stage 1 `ScheduleCadence`, and anchor.
- `TargetScheduleWindow` is the inclusive `[from, through]` request.
- `ResolvedScheduleOccurrence` is the immutable planned date result. It carries the original cadence but no completion, attempt, actual-date, or performance state.
- `ResolvedScheduleSource` makes the dependency of `DerivedExcluding(sourceRuleId)` explicit.
- `TargetScheduleResolver.resolve(schedule, window, source)` is a pure function. Multi-rule resolution sorts by planned date and then rule identity.

## Cadence semantics

`Daily` resolves each date in the window that is not before the anchor. It is not implemented through another cadence.

`EveryNDays(n)` is anchor-relative arithmetic: the anchor is eligible and subsequent eligible dates are exactly `n` calendar days apart. It never becomes weekday-based and never becomes `SessionsPerWeek`.

`FixedWeekdays(set)` is literal membership in the named Java `DayOfWeek` values, filtered by the anchor. Date iteration, not set iteration, determines output order.

`SessionsPerWeek(n)` is calendar-week frequency semantics. Its deterministic Monday-first spread is:

```text
1 → MON
2 → MON, THU
3 → MON, WED, FRI
4 → MON, TUE, THU, SAT
5 → MON, TUE, WED, FRI, SAT
6 → MON, TUE, WED, THU, FRI, SUN
7 → MON, TUE, WED, THU, FRI, SAT, SUN
```

Only values 1..7 are valid. The anchor bounds the first eligible date but does not redefine the weekly spread. The frequency is authoritative: existing slots, completed work, and unrelated history cannot affect it. `SessionsPerWeek(3)` therefore remains distinct from `EveryNDays(2)`; equal counts in a finite window are not semantic equivalence.

`DerivedExcluding(sourceRuleId)` resolves the explicitly supplied source, removes those source dates from the requested window, and invents no second cadence. Missing or mismatched source identity fails loudly.

## Bounded window and ordering

The window rejects `through < from`; it never chooses today, extends itself, or plans outside its bounds. Both boundaries are inclusive, and a one-day window is valid. Resolved values are immutable, chronological, and use rule identity as the canonical tie-breaker when several rules produce the same date.

## Purity and architecture boundary

The target package may use `java.time`, Kotlin values, and pure `domain.program` values. It may not reach Android, Room, data, repositories, UI, ViewModels, the session/adaptive runtime, or the existing `ProgramScheduler`, `SlotPlanner`, and `ScheduleCalendar`. It has no clock, random source, mutable global cache, filesystem access, or hidden state.

`TargetScheduleArchitectureTest` enforces the package boundary and non-wiring claim. `ProgramDomainPurityTest` also includes `program/target` in the foundation scan.

## Why the existing scheduler remains untouched

This stage answers only “which target dates does this rule produce in this explicit window?” Stage 3 (`docs/PROGRAM_TARGET_COMPOSITION.md`) then answers the separate question of which already-resolved occurrences appear together. The shipped scheduler still owns its existing §30 persistence-facing behavior. Later wiring, persistence, runtime, adaptive, progress, import/export, UI, and legacy work remain outside Stage 2.

## Verification map

- `TargetScheduleResolverTest`: validation, every cadence, boundaries, all frequencies 1..7, ordering, insertion-order independence, source dependency, repeatability, and input non-mutation.
- `TargetScheduleArchitectureTest`: pure dependency direction, no ambient state, and no existing-scheduler wiring.
- `scripts/program-stage2-red-mutations.sh`: control GREEN plus ten deliberate RED mutations covering slot-derived frequency, randomness, interval conversion, wrong spread, exclusive end, ignored anchor, set-order dependence, derived-as-daily, clock reads, and input mutation; every source is restored by MD5.
