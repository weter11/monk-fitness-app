# Program target occurrence composition — Stage 3

## Scope

Stage 3 adds `TargetOccurrenceComposer`, a pure target-domain operation that groups occurrences already resolved by Stage 2. It is additive: no scheduler, persistence, runtime, adaptive, generator, progress, UI, import/export, or legacy code is wired or changed.

Stage 2 answers WHEN. Stage 3 answers WHICH RESOLVED WORK IS TOGETHER.

## Why resolution and composition are separate

`TargetScheduleResolver` owns cadence, anchors, date arithmetic, weekly frequencies, fixed weekdays, intervals, derived exclusions, and inclusive windows. `TargetOccurrenceComposer` accepts only `ResolvedScheduleOccurrence` values. It cannot receive a `TargetSchedule` to discover dates and never calls the resolver.

Keeping the operations separate prevents grouping from becoming a second scheduler. The fixed flow is:

```text
TargetScheduleResolver
        ↓
ResolvedScheduleOccurrence
        +
CompositionSelection
        ↓
TargetOccurrenceComposer
        ↓
PlannedOccurrence
```

## Meaning of CompositionSelection

Stage 1's `CompositionSelection.combinedRuleIds` remains the explicit grouping policy:

```text
selected matching occurrences on one date
    → one combined PlannedOccurrence

unselected matching occurrences on that date
    → separate PlannedOccurrence values
```

The only combined rules are identities named in `combinedRuleIds`. The composer does not infer composition from workout identity, cadence, frequency, program day, exercise family, or input order. An empty selection leaves every occurrence separate.

Selection is applied locally to each supplied date. A selected rule that has no resolved occurrence in this resolution window contributes nothing. It is never fabricated.

## Same-date grouping and component auditability

The one explicit combined group is formed independently for each date. Different dates can never contribute components to one occurrence. A selected occurrence combines only with other selected occurrences on that exact date.

Each `OccurrenceComponent(ruleId, workoutId)` retains the concrete source identity of the resolved rule. No selected or unselected source component disappears during composition.

A selected group with one present component is represented as a one-component combined occurrence. This keeps the operation total for selected policy input without inventing work.

## Deterministic ordering

Output is ordered by `plannedFor` ascending. Within a date:

- separate occurrences are ordered by their component `ruleId` ascending;
- the one combined occurrence follows the separate occurrences;
- combined components are ordered by `ruleId` ascending.

No output ordering relies on list insertion order, `Set` iteration, `Map` iteration, or hash order. Reversed occurrence input and differently inserted linked sets produce equality-identical results.

## Deterministic identity

Separate occurrence keys use:

```text
<ruleId>:<plannedDate>
```

Combined occurrence keys use a deterministic, length-prefixed encoding of the sorted rule identities:

```text
combined:<plannedDate>:<ruleIdLength>:<ruleId>|<ruleIdLength>:<ruleId>...
```

Identity therefore depends only on date and participating rule identities. It does not include workout text, collection order, timestamps, random values, or `hashCode()`. Membership or date changes produce a different key.

## Validation and empty values

Composition fails loudly with `IllegalArgumentException` when a resolved occurrence has a blank rule identity, a blank workout identity, duplicate `(ruleId, plannedDate)`, or inconsistent workout identity for the same rule.

`CompositionSelection` retains its Stage 1 validation and rejects blank selected rule ids. Selection may name a rule absent from the current window; absence is not an error.

An empty occurrence list produces an empty result. Every emitted `PlannedOccurrence` contains at least one component, as required by the existing Stage 1 value invariant.

## Stage 1 compatibility

The provisional Stage 1 `OccurrenceComposer` remains untouched so its original schedule-rule API and its existing contract test retain their meaning. The new production target composer implements the Stage 3 resolved-occurrence API. `TargetOccurrenceComposerTest.stageOneComposerAndTargetComposerShareTheDefinedSemantics` proves parity for the Stage 1-defined separate and selective combined cases. No reverse dependency from the target layer to the old composer exists.

## Purity and architecture boundary

`TargetOccurrenceCompositionArchitectureTest` pins the target composer to pure domain values. It forbids Android, Room, data and repository access, use cases, UI, ViewModels, session/adaptive runtime, the legacy scheduler, filesystem access, clock/current-time sources, randomness, and UUID identity.

It also pins that:

- `TargetOccurrenceComposer` does not reference `TargetScheduleResolver`, `TargetSchedule`, cadence values, date arithmetic, or `resolve`;
- `ProgramScheduler`, `SlotPlanner`, and `ScheduleCalendar` remain outside the target composition package;
- no production package outside `domain/program/target` references the new composer;
- the Kotlin object owns no cache, mutable singleton field, or global accumulator.

## Existing scheduler

The existing `ProgramScheduler`, `SlotPlanner`, and `ScheduleCalendar` are untouched. Stage 3 is not wired into them. Wiring, persistence, runtime, adaptive, progress, generator, UI, and legacy removal remain outside this stage.

## Verification map

- `TargetOccurrenceComposerTest`: composition semantics, selective grouping, missing selected rules, date boundaries, input/set-order independence, canonical ordering, deterministic identity, duplicate and blank validation, empty output, pure result shape, strong semantic example permutations, and Stage 1 parity.
- `TargetOccurrenceCompositionArchitectureTest`: pure dependency direction, no resolver/date resolution, no platform/storage/runtime/UI, no ambient state, no scheduler wiring, and exact target package contents.
- `TargetOccurrenceReconciliationArchitectureTest` and `TargetScheduleArchitectureTest`: Stage 4 extends the exact target package census to resolver, composer, and reconciler while retaining Stage 2/3 purity and non-wiring rules.
- `docs/PROGRAM_TARGET_RECONCILIATION.md`: Stage 4 occurrence-identity reconciliation contract.
- `scripts/program-stage3-red-mutations.sh`: control GREEN plus eleven mutations covering selected/unselected inversion, cross-date grouping, input ordering, component ordering, key ordering, fabrication, duplicate repair, resolver reach, current time, and random identity. Every mutated source is restored byte-identically by MD5.
