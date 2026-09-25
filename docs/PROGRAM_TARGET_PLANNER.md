# Program target planner — Stage 5

## Purpose

Stage 5 adds `TargetPlanner`, one deterministic pure entry point over the already merged target-domain components. It is additive and domain-only. It does not wire the planner into the shipped scheduler or any persistence, runtime, adaptive, UI, session, import/export, or generator layer.

Stage 2 answers WHEN.
Stage 3 answers WHICH RESOLVED WORK IS TOGETHER.
Stage 4 answers WHAT HAPPENS TO EXISTING OCCURRENCE FACTS.
Stage 5 answers HOW THESE PURE TARGET OPERATIONS ARE COMPOSED INTO ONE PLAN.

## Exact pipeline

```text
TargetScheduleResolver.resolve(...)
        ↓
TargetOccurrenceComposer.compose(...)
        ↓
TargetOccurrenceReconciler.reconcile(...)
        ↓
TargetPlan
```

`TargetPlan.planned` is the exact list returned by Stage 3. `TargetPlan.reconciliation` is the exact Stage 4 result of reconciling the supplied existing occurrences against that planned replacement.

The caller supplies the bounded `TargetScheduleWindow`, composition selection, existing occurrences, and resolved derived sources. Stage 5 does not discover, expand, or reinterpret any of them.

## Ownership

- Stage 2 remains the sole owner of cadence/date arithmetic, weekly frequency rules, derived exclusions, anchors, windows, and canonical resolved ordering.
- Stage 3 remains the sole owner of same-date composition, explicit selection, component grouping, occurrence identity, and planned canonical ordering.
- Stage 4 remains the sole owner of existing-versus-replacement identity reconciliation, planned retention/supersession, addition, and historical execution preservation.
- Stage 5 only connects those outputs in the required order.

`PlannedOccurrence.occurrenceKey` remains the sole target occurrence membership identity. Stage 5 introduces no identity semantics and copies no actual results. `STARTED`, `COMPLETED`, and `CANCELLED` occurrences remain preserved exactly as Stage 4 supplies them.

## Why orchestration only

The planner contains no cadence or date calculation, weekly rules, derived-exclusion logic, composition rules, identity generation, reconciliation rules, or canonical sorting. Keeping policy in its owning stage prevents competing implementations and makes later integration a wiring decision rather than a semantic rewrite.

## Determinism and idempotence

The pipeline is pure. Identical inputs produce equality-identical `TargetPlan` values. Reordering schedules, source map insertion order, existing occurrences, or composition-selection set insertion order does not change the result because each owning stage already canonicalizes its output. No mutable singleton state, cache, hidden global state, side effect, clock, random source, UUID, filesystem, or network access is used.

## Purity boundary

`TargetPlanner` uses only Kotlin/JVM standard-library values, `java.time` through the existing domain types, and pure `domain.program`/target-domain values. The focused architecture test rejects Android, AndroidX, Room, DAO, repository, use-case, ViewModel, UI, session runtime, adaptive runtime, `ProgramScheduler`, `SlotPlanner`, `ScheduleCalendar`, clocks, current-time APIs, randomness, UUID, filesystem access, and mutable global state.

## Why the scheduler remains untouched

This stage creates a clean pure target-planning seam. It does not replace `ProgramScheduler`, `SlotPlanner`, or `ScheduleCalendar`; it does not change legacy scheduler semantics, persistence, slot database IDs, or runtime behavior. Later scheduler work may consume this seam only under a separately approved integration stage.

## What Stage 5 deliberately does not own

Stage 5 does not infer `asOf`, current date, horizon, pause behavior, missed state, catch-up, Program lifecycle, pause shifting, catch-up, slot persistence, stored slot IDs, repository transactions, UI state, session execution, adaptive behavior, import/export, or generator policy. Those concerns remain outside this pure target planning entry point.

## Verification map

- `TargetPlannerTest`: empty and one/multiple schedule paths, selection and no-selection composition, retention/supersession/addition, historical preservation, derived exclusion, reordered inputs, direct Stage 2 → Stage 3 → Stage 4 parity, repeated execution, and caller-input non-mutation.
- `TargetPlannerArchitectureTest`: exact four-file production census, pure dependency shape, exact stage calls and order, no duplicate policy, no mutable singleton state, and no production wiring.
- Existing Stage 2–4 target architecture tests retain their original boundaries with the Stage 5 census extension.
- `scripts/program-stage5-red-mutations.sh`: control plus deliberate resolver, composer, reconciler, ordering, forwarding, determinism, clock, scheduler, and mutable-state mutations; every mutation must be caught and the source restored byte-identically.
