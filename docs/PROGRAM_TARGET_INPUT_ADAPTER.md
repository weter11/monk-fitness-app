# Program target scheduling input adapter — Stage 13

## Boundary

Stage 12 gave the target pipeline a single entry point. What it did not give it was a *way to get
there*: `TargetScheduleOrchestrationRequest` demands ten values, and until Stage 13 every caller had
to assemble all ten itself — `TargetSchedule[]`, a `TargetScheduleWindow`, a `CompositionSelection`,
an `ExistingOccurrence[]`, a `ResolvedScheduleSource` map, `asOf`, a `ProgramPauseWindow[]`,
`TargetProgramDayBinding[]`, a `ProgramId` and a `RevisionId`.

Stage 13 states that assembly as an explicit, typed contract:

```text
Program data
    ↓
explicit target definitions + explicit ProgramDay bindings
    ↓
TargetScheduleInputAdapter
    ↓
TargetScheduleOrchestrationRequest
    ↓
TargetScheduleOrchestrator
    ↓
TargetPlanner → TargetSchedulePolicy → TargetScheduleApplicationService → TargetScheduleSlotPersister
```

`TargetScheduleInputAdapter` lives in `domain/usecase`, beside the Stage 12 orchestrator it feeds. It
performs **value conversion only** and it does **not run the pass**: it never calls the planner, the
temporal policy, the application boundary, the persister or the orchestrator, and it holds no
repository, no clock, no identity generator and no field at all. An equivalent input therefore always
describes an equality-identical request.

## The three explicit values

| Type | States |
| --- | --- |
| `TargetScheduleDefinition` | one target rule: `ruleId`, `workoutId`, `cadence`, `anchorDate` |
| `TargetScheduleInput` | the ten caller-owned values a pass needs |
| `TargetScheduleInputAdapter` | converts the first into `TargetSchedule`, the second into `TargetScheduleOrchestrationRequest` |

`TargetScheduleDefinition` is deliberately a **separate type** from the engine's `TargetSchedule`, not
an alias: `TargetSchedule` is the engine's input, and keeping a caller-authored record distinct from it
is what makes "the caller supplied this" checkable rather than assumed. The conversion between them is
four assignments and no branch.

Nothing is defaulted or derived. `TargetScheduleDefinition` validates nothing at construction either —
a blank identity is not representable as a *rule*, only as a *claim*, and the adapter is where a
malformed claim is refused with a typed reason:

- `TargetScheduleInputException.BlankTargetRuleIdentity(index)`
- `TargetScheduleInputException.BlankTargetWorkoutIdentity(index)`
- `TargetScheduleInputException.DuplicateTargetRuleIdentity(ruleId, index)`

Those three are the **whole** validation, and they are all about identity and shape. There is no
`(ruleId, anchorDate)` uniqueness rule, because `TargetSchedule` states none and inventing one would
be a second semantic rule about what a pass means.

## No implicit `ProgramSchedule → TargetSchedule` mapping

**There is no mapper, and this stage adds none.** `ProgramSchedule` states *when slots fall*:

```kotlin
sealed interface ProgramSchedule {
    data class FixedWeekdays(val weekdays: Set<DayOfWeek>) : ProgramSchedule
    data class FlexiblePerWeek(val sessionsPerWeek: Int) : ProgramSchedule
}
```

A target rule needs more than that, and the "more" is exactly what a mapper would have to invent:

| A `TargetSchedule` needs | Available from `ProgramSchedule`? |
| --- | --- |
| `ruleId` | no — it is an identity the caller owns |
| `workoutId` | no — nothing in a weekday set names a workout |
| `cadence` | partially — the shape exists, but choosing it is a claim about the target, not a read of the legacy field |
| `anchorDate` | no — a weekday set is a rhythm, not a date |

So `ProgramSchedule.FixedWeekdays` and `ProgramSchedule.FlexiblePerWeek` remain legacy/application
schedule vocabulary. They are **not** complete target rules, and a conversion between them would
require supplying a `workoutId`, a rule identity, an anchor date, a ProgramDay binding, a composition
and a source dependency out of nothing.

The same argument applies to every other identity a Program holds, and each is forbidden by name:

- `ProgramDay.position` is an *ordering*, not an identity (§1);
- `ProgramDay.name` is a user-facing label;
- a `ProgramDayId` / `ProgramId` / `RevisionId` value is **opaque** — the domain never parses it and
  never reuses one entity's vocabulary for another's id;
- `occurrenceKey`, `plannedFor`, `weekday` and list index are *coordinates*, not identities.

The Stage 1 `LegacyScheduleMapper` already in the tree is exactly the shape this stage refuses to
extend: it produces a `ScheduleRule` with the literal rule id `"legacy"` and workout id
`"legacy-workout"`. It is a legacy compatibility mapping and is documented as such; Stage 13 does not
use it for target semantics, and doing so would require the caller to supply the missing target
identity explicitly.

## Caller-owned facts this stage refuses to reconstruct

Three inputs are stated by the caller and forwarded unchanged. In each case the reason is that the
honest reconstruction does not exist yet.

### Existing target occurrences

The persisted `WorkoutSlot` carries `programDayId`, `plannedFor`, status and `targetOccurrenceKey` —
it does **not** carry the `PlannedOccurrence.components` payload. So a slot cannot be read back into
an `ExistingOccurrence` without inventing a component, parsing the occurrence key, reusing the
`ProgramDayId` as a workout id, inferring from a date, or inventing a `"legacy"` placeholder rule.

Stage 13 does none of those. `existingOccurrences` is caller input and is forwarded as stated.

> **This is a conscious boundary, not missing test coverage.** Until a dedicated semantic read-back
> contract exists — one that stores or reconstructs a real occurrence payload — existing target
> occurrences remain explicit caller input. The behaviour suite pins the forward; the architecture
> suite pins the absence of a reconstruction, so closing this boundary later has to be a deliberate
> change to both.

### Resolved sources

`sources` is a caller-supplied `Map<String, ResolvedScheduleSource>`. The adapter forwards it and
never calls `TargetScheduleResolver` to build it, never computes derived exclusions and never checks
that a derived rule's source is present. Source resolution is the planner's work, inside the pass.

### ProgramDay binding

`workoutId ↔ ProgramDayId` is caller-supplied through the existing `TargetProgramDayBinding`. The
adapter forwards the list unchanged and constructs no `ProgramDayId` of its own.

## Order, determinism and immutability

- **Order is the caller's.** Definitions are converted positionally. There is no sort, no grouping and
  no deduplication, because a canonical order would be a second, invisible statement about which rule
  matters. Duplicate rule identities are *refused*, not canonicalised.
- **Determinism.** The same input always yields an equality-identical request, on this instance and on
  a fresh one; the class holds no field.
- **Immutability.** No caller collection is read destructively or written to. The adapter copies
  nothing and mutates nothing.

## The pipeline after the adapter is unchanged

```text
Input Adapter
    ↓
TargetScheduleOrchestrator
    ↓
Planner
    ↓
Policy
    ↓
Application
    ↓
Persister
```

Stage 13 adds a step *before* the Stage 12 entry point. `TargetScheduleOrchestrator` still runs exactly
`TargetPlanner.plan` → `TargetSchedulePolicy.decide` → `applicationService.apply`, and does not know the
adapter exists.

## Composition root

`AppContainer` wires `targetScheduleInputAdapter` beside the persister, the application service and the
orchestrator, with **no collaborator**:

```kotlin
val targetScheduleInputAdapter: TargetScheduleInputAdapter = TargetScheduleInputAdapter()
```

It is not connected to `ProgramScheduler`, `SessionRuntime`, any UI, `ViewModel`, repository, `Clock` or
`IdGenerator`, and nothing consumes it in production yet — the target contour is a live entry point, not
a replacement for the legacy scheduler. The cutover remains a separate stage.

## Legacy isolation

Unmodified by this stage: `ProgramScheduler.kt`, `SlotPlanner.kt`, `ScheduleCalendar.kt`,
`ProgramDuration.kt` (which declares `ProgramSchedule`) and `SemanticContracts.kt` (which declares the
Stage 1 compatibility mapping). No `ProgramSchedule → TargetSchedule` mapper was added to the legacy
scheduler, and the architecture suite asserts that none exists anywhere in the production tree.

## Verification map

- `TargetScheduleInputAdapterTest` — explicit definition conversion, all five cadence shapes, caller
  order preserved, an empty definition list, exact pass-through of all ten values plus the request
  field census, exact ProgramDay binding preservation, ProgramDay position/name/identity changes not
  moving `workoutId`, the legacy schedule not being an input, duplicate rule identity refused (same
  anchor and different anchors), blank rule and workout identity refused, a typed refusal with no
  partial request, caller collections unmodified, the adapter holding no field and taking no
  constructor argument, determinism across instances, existing occurrences / sources / pauses / window
  forwarded and unclassified, and that the only public method is `adapt(input): request`.
- `TargetScheduleInputAdapterArchitectureTest` — placement in `domain/usecase` and not in the pure
  target package, the pure target package's file census unchanged, the exact import list, compiled
  values depending only on JVM and pure domain types, the absence of every planning/policy/presentation/
  persistence component, the absence of any repository/runtime/storage/identity collaborator, the
  absence of ambient time and randomness, the absence of every legacy scheduling identifier, the
  absence of any `ProgramSchedule → TargetSchedule` mapping in the whole production tree, legacy
  isolation, the one-expression-per-field request construction, the field-for-field definition
  conversion, identity-and-shape-only validation, the three typed refusals, composition-root wiring with
  nothing attached, no other construction site, and the unchanged Stage 12 pipeline.
- `scripts/program-stage13-red-mutations.sh` — a green control, then every deliberate break of this
  boundary, each of which must be caught, with zero misses and byte-identical restoration.

## Revised guard

One previous-stage guard was revised rather than relaxed.

| Guard | Was | Is now |
| --- | --- | --- |
| `TargetScheduleArchitectureTest` | `oldSchedulerSourcesRemainOutsideAndStageTwoHasExactlyOneProductionCaller` | `oldSchedulerSourcesRemainOutsideAndStageTwoHasExactlyTwoProductionCallers` |

The pin was already a **closed list**, not an absence, after Stage 12. The Stage 13 adapter genuinely
has to name Stage 2 types — its input carries a `TargetScheduleWindow` and a `ResolvedScheduleSource`
map, and forwarding them unchanged *is* its contract — so the list becomes the closed two-element one
naming `TargetScheduleInputAdapter.kt` and `TargetScheduleOrchestrator.kt`. A third consumer still fails
the guard, and the legacy-contour negative half of the assertion is kept verbatim.

`TargetPlannerArchitectureTest`, `TargetSchedulePolicyArchitectureTest`,
`TargetOccurrencePresenterArchitectureTest`, `TargetSlotMaterializerArchitectureTest` and
`TargetScheduleSlotPersisterArchitectureTest` are **unchanged**: the adapter names none of the planner,
the policy, the presenter, the materializer or the persister, which is itself part of what this stage
asserts.

## Verification (measured on this branch)

Base: `origin/main` `2724245` (merge of PR #314, the Stage 12 orchestrator).

| Check | Result |
| --- | --- |
| base JVM suite (Stage 12 doc, same task) | 267 classes / 2362 tests / 0F / 0E / 0S |
| branch JVM suite (`--rerun-tasks`, fresh XML) | 269 classes / 2407 tests / 0F / 0E / 0S |
| suite delta | +2 classes / +45 tests (27 behaviour + 18 architecture) |
| focused Stage 13 suites | 45 tests / 0F / 0E / 0S |
| `scripts/program-stage13-red-mutations.sh` | control GREEN, caught 20, missed 0, source restored byte-identically (md5 `4de481ce…`), exit 0 |
| `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:assembleDebug` | BUILD SUCCESSFUL |
| `git diff --check` | clean |

Every RED row is decided by a suite that reads the mutated *code* rather than its prose; the script
strips comments from each mutated file before the suites run, so a row cannot be scored on a token
that only reached a KDoc.

Two rows are worth naming explicitly, because they are the ones a plausible implementation would have
got wrong:

| Row | What it would take to pass |
| --- | --- |
| `derive workoutId from a ProgramDayId` | replacing the caller's `workoutId` with the first binding's `ProgramDayId.value` — caught behaviourally (`theProgramDayBindingIsPreservedExactlyAsStated` sees a binding that no longer states what the caller stated) and by the `ProgramDayId` identifier gate |
| `construct a fake ExistingOccurrence from a slot` | appending a synthetic occurrence keyed `"legacy:<programDayId>"` with the `ProgramDayId` reused as a workout id — caught by the forward test and by the `PlannedOccurrence` / `OccurrenceComponent` / `OccurrenceExecution` gate |

## Architecture gaps recorded

- The adapter is wired in `AppContainer` but has no production consumer, exactly like the Stage 12
  orchestrator above it.
- Existing target occurrences still cannot be read back from storage; a semantic read-back contract is
  the missing piece before a pass can be run from persisted state alone.
- `TargetScheduleWindow` and `asOf` remain caller-stated, so a caller can still state a window that
  excludes the current date. The adapter forwards both unchanged on purpose — deciding today's date is
  the policy's job, and doing it at an input boundary would make the request untestable and the boundary
  impure.
