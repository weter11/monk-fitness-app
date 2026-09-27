# Program — target existing-occurrence boundary (§30 step 17)

## Why target scheduling was consuming `ExistingOccurrence`

Target reconciliation and the target temporal policy were written against the domain's
general-purpose `ExistingOccurrence`:

```kotlin
data class ExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val actuals: List<ActualResult>
)
```

Two of those three fields are facts target scheduling actually reads. The third is not read by
**any** rule in the target contour — not by the reconciler's membership decision, not by the
temporal policy's `created / retained / missed / superseded` partition, not by the planner. It was
a passenger on a type that predates the target architecture by a whole generation, and the target
contour inherited two costs from carrying it:

* **a payload it does not use.** Every target occurrence that entered planning, reconciliation and
  temporal classification carried an `ActualResult[]` alongside the two facts it was judged on, and
  three layers of the target pipeline were typed on a performance vocabulary they never read.
* **a dependency it did not choose.** The target contour is a separate generation from the legacy
  scheduling generation (blueprint §1: the two generations coexist, and target code must not read
  or write legacy tables while the legacy generation must not import target types). Consuming
  `ExistingOccurrence` put the legacy execution payload shape on the target contour's dependency
  list, so a change to the general-purpose type's shape became a change to target scheduling's
  input for no scheduling reason at all.

The blueprint's own standing principle applies directly: *prior-generation decisions are not design
constraints; do not preserve old constructions solely because they predate the current
architecture.* The construction was preserved solely because it predated the target architecture.

## Why that dependency is now removed

Two phases had already built the target contour's **own** execution vocabulary, and the third field
was the last reason the legacy type was still reachable from target code:

```text
Phase 15  TargetOccurrenceExecutionRecord     the stored facts of one target occurrence
Phase 16  TargetOccurrenceExecutionDecision   the one verdict, from the one precedence
Phase 17  TargetExistingOccurrence            the scheduling input those two make possible
```

Phase 15 and Phase 16 were written explicitly as *target* types whose KDoc says why they are not
`ExistingOccurrence`. What they could not do was stop the target contour from using it, because
`TargetPlanner` and `TargetSchedulePolicy` were still typed on it. This phase removes that
dependency, and the removal is the point: the target contour no longer depends on the legacy
execution payload shape merely because that type predates the target architecture.

It is a **boundary** change, not a policy change. Every scheduling rule is byte-for-byte the rule it
was; only the declared type of its input changed.

## The minimal target-specific value

```kotlin
data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution
)
```

Two fields, and the shape is the claim: target scheduling needs an occurrence's identity/payload
and an execution classification, and those are the two fields. It also exposes `occurrenceKey`,
`plannedFor` and `isPlanned` as read-only projections — no second identity, no stored duplicate.

The orchestration chain is now stated in the target contour's own vocabulary:

```text
TargetExistingOccurrence[]
        ↓
TargetPlanner
        ↓
TargetSchedulePolicy
        ↓
TargetScheduleApplicationService
```

**No target scheduler component imports or depends on `ExistingOccurrence`.** That is checked by a
whole-tree sweep, not a file-local scan, so a *new* target caller that reintroduces it fails the
build — and there is a positive regression case asserting the gate's predicate still refuses exactly
that shape and does not fire on the target contour's own vocabulary.

### What it deliberately does not carry

`ActualResult`, performed work, `ExecutionEvidence`, `WorkoutSession`, `WorkoutSlot`, repository
state, persistence state, adaptive state — none of it. Each belongs to the layer that owns it:

| what | where it lives |
| --- | --- |
| the stored attempt graph, verbatim | `TargetOccurrenceExecutionRecord` (Phase 15) |
| the opportunity outcome (`SlotStatus`) | `TargetOccurrenceExecutionDecision.slotStatus` (Phase 16) |
| performed work | the session's own `SessionExercise` → `SetResult` graph |
| storage | the repositories, behind the reader |
| adaptive state | the adaptive generation, untouched |

`SemanticContracts.kt` is **not** modified. The legacy `ExistingOccurrence` still exists with its
`actuals` field, still serving the general-purpose `ScheduleReconciliation` /
`ScheduleEditReconciler` contracts it was written for. The brief's prohibition is honoured in both
directions: target code stops using it, and it is not retrofitted with target behaviour.

## The execution-policy boundary from Phase 16

There is exactly one precedence over stored attempt statuses, and this phase introduces no second
one. The route from stored facts to a scheduling input is a sequence of four already-owned steps:

```text
TargetOccurrenceExecutionReader     reads the facts                     (Phase 15)
        ↓
TargetOccurrenceExecutionRecord     the facts themselves                (Phase 15)
        ↓
TargetOccurrenceExecutionPolicy.decide(...)   the one precedence       (Phase 16)
        ↓
TargetExistingOccurrence            the scheduling input               (this phase)
```

`TargetExistingOccurrenceReader` (in `domain/usecase`) is that sequence, and it is the only new
production class besides the value itself. It holds **one** collaborator — the Phase 15 reader — and
no repository, no clock and no identity generator. It reads the record, calls
`TargetOccurrenceExecutionPolicy.decide(record)`, and takes only `decision.execution`. The
`slotStatus` and `attemptCount` the decision also carries are opportunity and count facts, and
neither crosses into scheduling.

The value's own second constructor takes a `TargetOccurrenceExecutionDecision` **by value**, so the
conversion is a projection of an already-decided fact and never a recomputation. There is
deliberately no constructor that takes a `TargetOccurrenceExecutionRecord` and decides for itself:
that would be a second route to the verdict, which is the failure Phase 16 exists to prevent.

The **payload** side is caller-stated rather than read. A persisted `WorkoutSlot` does not carry a
full occurrence payload, so no honest read-back of the components exists yet; the caller states the
`PlannedOccurrence` it planned, and the bridge answers the only question storage can answer — what
happened to that occurrence. That is also what keeps the reconciler's payload-equality rule exact:
it compares planned payloads, and nothing on the way in rewrites one.

## Why performance / `ActualResult` remains outside this contract

`ActualResult` is not merely unused by target scheduling — **mapping performed work onto it is not a
read**, and no documented contract authorises it:

* nothing relates a session's `exerciseId` to a target occurrence's `workId`;
* summing a session's confirmed sets into one result is a *rule*, and a rule needs an owner and a
  precedence, not a mapper;
* Phase 15 read the performed work out and deliberately refused to aggregate it, and Phase 16
  decided execution without touching it.

So this phase **explicitly does not**: create an `ActualResult`, create a workId mapping, map
exerciseId to workId, sum sets, or aggregate performance. Performance aggregation remains an
out-of-scope, explicitly deferred phase with its own owner. A test proves the operational
consequence: every verdict the target contour can produce is reachable from a two-field input.

## Preserved target scheduling semantics

Nothing about *what the scheduler decides* changed. The preserved rules, each with the suite that
holds it:

| rule | held by |
| --- | --- |
| occurrence membership identity is `occurrenceKey` | `TargetExistingOccurrenceArchitectureTest`, `TargetOccurrenceReconcilerTest` |
| occurrence payload equality on a same-key replacement | `TargetOccurrenceReconcilerTest` |
| duplicate existing key refused | `TargetOccurrenceReconcilerTest` |
| `PLANNED` / `STARTED` / `COMPLETED` / `CANCELLED` classification and its preserved/superseded buckets | `TargetExistingOccurrenceBoundaryTest`, `TargetSchedulePolicyTest` |
| reconciliation preservation (`non-PLANNED` preserved, `PLANNED` withdrawable) | `TargetExistingOccurrenceBoundaryTest`, `TargetOccurrenceReconcilerTest` |
| temporal policy rules (`created / retained / missed / superseded`, pause windows, as-of) | `TargetSchedulePolicyTest`, `TargetExistingOccurrenceBoundaryTest` |
| same-date composition | `TargetPlannerTest`, `TargetOccurrenceComposerTest` |
| canonical ordering — `(plannedFor, occurrenceKey)` | all four target suites |
| pause behaviour (`PASSED_WHILE_PAUSED` supersession) | `TargetSchedulePolicyTest` |
| planner output | `TargetPlannerTest` |
| orchestration output | `TargetScheduleOrchestratorTest` |
| legacy scheduler isolation | `TargetExistingOccurrenceArchitectureTest`, `TargetScheduleArchitectureTest` |

`PLANNED` is the one state whose bucket looks surprising and is correct: a planned occurrence is
what a replacement plan is allowed to withdraw, so it is *superseded* rather than *preserved*. That
was the pre-refactor behaviour and it is unchanged; the boundary suite asserts both halves so the
difference stays documented rather than rediscovered.

## Legacy isolation

Both directions are pinned, and the legacy generation is untouched:

* no target source reaches `ProgramScheduler`, `SlotPlanner`, `ScheduleCalendar` or
  `ScheduleEditReconciler`;
* no legacy source reaches `TargetSchedule`, `TargetPlanner`, `TargetPlan` or any `Target*` type;
* the legacy `ExistingOccurrence` remains for the legacy contracts that legitimately hold it, with
  no target-specific member added to it.

## Verification

* `TargetExistingOccurrenceArchitectureTest` — the twelve boundaries, swept over the whole
  production tree, including the positive regression that a new target caller importing
  `ExistingOccurrence` would fail the gate.
* `TargetExistingOccurrenceBoundaryTest` — the value, every execution state, and every scheduling
  verdict reached without performance data.
* `TargetExistingOccurrenceReaderTest` — the bridge on a real SQLite engine, staged through the
  production write paths: no attempt → `PLANNED`, started → `STARTED`, completed → `COMPLETED`,
  cancelled → `CANCELLED`, the verdict equal to the Phase 16 policy's over the same stored history,
  the caller's payload forwarded unchanged, and a read refusal propagating rather than defaulting.
* The pre-existing target suites (`TargetOccurrenceReconcilerTest`, `TargetPlannerTest`,
  `TargetSchedulePolicyTest`, `TargetScheduleOrchestratorTest`, `TargetScheduleInputAdapterTest`)
  are preserved, with their fixtures re-pointed at the new value.
* Full JVM suite with `--rerun-tasks`, `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`,
  `:app:assembleDebug` and `git diff --check`: green. The exact numbers are below.
* `scripts/program-stage17-red-mutations.sh` — 18 mutations, one per rule above. Every row is a
  deliberate break of the boundary, and each was required to fail a real oracle. Two harness
  properties are worth recording because they changed the mutations rather than the harness:

  * **a mutation must be type-correct across the *whole* tree.** Four rows originally edited a
    public signature — the exact shape the brief describes. All four failed to compile, because the
    test callers stopped resolving, and the harness charged them as `NOT A CATCH` rather than as
    passes. A Kotlin *overload* is not a workaround either: it erases to the same JVM signature and
    fails with `Platform declaration clash`. The rows were rewritten to add a **second declaration in
    the legacy vocabulary** — an unused private helper, a distinctly named private function, an
    extra property — which compiles, is what a careless new caller would actually have, and is what
    the whole-tree sweep must refuse.
  * **row 13 reads a current `ProgramRevision` from *inside* the reconciler** rather than taking one
    as a parameter, for the same reason: a new parameter breaks every caller before an oracle runs.

  Result: `caught: 18`, `missed: 0`, `source restored byte-identically`, `exit 0`.

### The full fresh census

`scripts/test-census.sh` runs `--rerun-tasks` and prints the census only on a zero gradle exit, so a
compile error can never be reported as a clean baseline:

```text
gradle exit code: 0
classes: 279
tests:   2586
failures:0
errors:  0
skipped: 0
xml timestamp range: 2026-09-27T20:15:41 .. 2026-09-27T20:16:16
non-green suites: none
```

Cross-checked against the sources, and it matches exactly: 279 files containing `@Test`, 2586
`@Test` annotations. The three new suites contribute 13 + 15 + 9 = 37 cases.

Focused target suites (planner, reconciler, policy, orchestration, input adapter and the three new
ones) were also run on their own and are green. `:app:compileDebugKotlin`,
`:app:compileReleaseKotlin` and `:app:assembleDebug` are green, and `git diff --check` is clean.

### Pinned claims revised, not relaxed

Four prior claims changed and each was rewritten as the claim that is now true, never deleted:

| suite | what changed and why |
| --- | --- |
| the seven target-package file lists | `TargetExistingOccurrence.kt` is an eleventh file in the pure target package; the closed list grows by exactly one entry in each copy, so a twelfth still fails |
| `TargetScheduleInputAdapterArchitectureTest` | the adapter's exact import list: `program.ExistingOccurrence` → `program.target.TargetExistingOccurrence` |
| `TargetSchedulePolicyTest` / `TargetPlannerTest` / `TargetOccurrenceReconcilerTest` / `TargetScheduleInputAdapterTest` | the actual-results cases became cases over the two facts the boundary actually carries; the legacy-parity case in the reconciler suite now states its input in the legacy vocabulary, because it exercises the *legacy* reconciler |
| `TargetOccurrenceReconciliationArchitectureTest` | the compiled-type list names the target value instead of the legacy one |

### Architecture gaps recorded

* The target scheduling input's **payload** is still caller-stated. A `PlannedOccurrence`'s
  components are not read back from storage, and no phase owns that read yet. It is not a gap this
  boundary created — Phase 13 recorded the same three caller-owned facts and named this one — and it
  is recorded here so the next stage inherits it explicitly.
* The `SlotStatus` of an occurrence's slot is carried into the Phase 16 decision and is deliberately
  **not** a scheduling input. Whether any target rule should read it is an open policy question, not
  a boundary one; nothing in the current pipeline does, and this phase does not add it.
