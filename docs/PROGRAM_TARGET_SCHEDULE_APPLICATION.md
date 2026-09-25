# Program target schedule application boundary — Stage 11

## Boundary

Stage 11 adds the first application-level coordinator for applying a ready target schedule decision:

```text
TargetScheduleDecision
        ↓
TargetOccurrencePresenter
        ↓
TargetScheduleSlotPersister
        ↓
ProgramScheduleRepository
```

`TargetScheduleApplicationService` lives in `domain/usecase`. It accepts the owning `ProgramId`, source `RevisionId`, an already-computed `TargetScheduleDecision`, and explicit `TargetProgramDayBinding` values. It does not read a clock or derive any input.

The pass performs only this pipeline:

```text
decision.created
        ↓
TargetOccurrencePresenter.present(...)
        ↓
TargetScheduleSlotPersistenceInput
        ↓
TargetScheduleSlotPersister.persist(...)
```

The service does not present `decision.targetPlan.planned`, `retained`, `preserved`, `superseded`, or `missed`. Only `decision.created` describes new target occurrences that this application pass may materialize.

## Result and failure semantics

`TargetScheduleApplicationResult` preserves three values without rewriting them:

- the original `TargetScheduleDecision`;
- the presentations produced by Stage 7;
- the result returned by the Stage 10 persister.

The boundary has no generic exception translation. `TargetOccurrencePresentationException` and `TargetSlotPersistenceException` propagate unchanged. A missing or ambiguous ProgramDay binding is therefore a typed failure, never an empty success. The same applies to a persistence conflict.

## Ownership

| Stage | Owns |
| --- | --- |
| Stages 1–6 | semantic target computation and the resulting `TargetScheduleDecision` |
| Stage 7 | explicit `TargetProgramDayBinding` lookup and occurrence presentation |
| Stage 8 | persisted target identity schema |
| Stage 9 | pure `TargetOccurrencePresentation -> WorkoutSlot` materialization |
| Stage 10 | target lookup, idempotency, `IdGenerator` use, and repository persistence |
| Stage 11 | application orchestration only |

The application service does not compute schedule semantics, generate identity, map a target occurrence to a `WorkoutSlot`, or access storage. In particular:

- it creates no `SlotId`, `targetOccurrenceKey`, or `ProgramDayId`;
- it does not alter `plannedFor` or `occurrenceKey`;
- it owns no `IdGenerator`, repository, DAO, Room database, clock, random source, UUID, hash, or list-position identity;
- it does not duplicate `TargetSlotMaterializer` mapping;
- it does not duplicate target membership lookup.

`TargetScheduleSlotPersister` remains the sole orchestration-level owner of `IdGenerator` use for new target slots, and `TargetSlotMaterializer` remains the sole new-slot mapping point.

## Idempotency and immutability

Repeated application of the same decision and bindings is delegated to the persister. It uses `(programId, targetOccurrenceKey)` membership, returns a matching existing slot as retained, preserves its `slotId` and execution state, and creates no duplicate row.

The application service does not sort or remove values from its input collections in place. It stores the original decision in its result and lets the existing presenter and persister own any canonical ordering inside their value outputs. Same-date occurrences with distinct occurrence keys remain independent; no date-only occupancy or one-date-one-target rule exists at this boundary.

## Composition and legacy isolation

`AppContainer` wires `targetScheduleApplicationService` beside `targetScheduleSlotPersister`. The application service receives only the persister. `ProgramScheduler` receives no dependency on the new service.

`ProgramScheduler`, `SlotPlanner`, and `ScheduleCalendar` are untouched. The target path does not fall back to the legacy scheduler, and the legacy contour does not import or invoke the target application boundary.

## Verification map

- `TargetScheduleApplicationServiceTest` covers created-only presentation, explicit ProgramDay binding, same-date independence, empty created input, missing and multi-day typed failures, persistence-result preservation, reapplication, started and completed state retention, input immutability, and delegated identity/materialization ownership.
- `TargetScheduleApplicationServiceArchitectureTest` pins placement, collaborators, presenter and persister ownership, no direct repository/storage access, no slot construction, no semantic recomputation, unchanged typed failures, composition-root wiring, and legacy isolation.
- `scripts/program-stage11-red-mutations.sh` runs a green control, applies every deliberate application-boundary mutation, requires all to be caught, requires zero misses, and verifies byte-identical restoration.

## Revised guards

Stage 7's `TargetOccurrencePresenterArchitectureTest.stageSevenIsNotWiredIntoAnyOtherProductionPackage` asserted an **absence**: no production source outside the pure target package named the presenter. The application boundary is now that one caller, so the claim was inverted rather than relaxed: the test is renamed
`stageSevenHasExactlyOneProductionCallerAndItIsTheStageElevenApplicationBoundary` and asserts the closed list is exactly `domain/usecase/TargetScheduleApplicationService.kt`, plus that `ProgramScheduler`, `SlotPlanner` and `ScheduleCalendar` do not reach the presenter. A second consumer still fails the pin.

No other previous-stage guard was weakened. `TargetSlotMaterializerArchitectureTest` still pins the Stage 10 persister as the only materializer consumer, and the application service does not reference the materializer, so the claim is unchanged.

## Verification (measured on this branch)

Base: `origin/main` `ae31d7d` (merge of PR #312), measured in a detached worktree.

| Check | Result |
| --- | --- |
| base JVM suite | 263 classes / 2314 tests / 0F / 0E / 0S |
| branch JVM suite (`cleanTest`, `--rerun-tasks`) | 265 classes / 2336 tests / 0F / 0E / 0S |
| suite delta | +2 classes / +22 tests (12 behaviour + 10 architecture) |
| focused Stage 11 suites | 22 tests / 0F / 0E / 0S |
| `scripts/program-stage11-red-mutations.sh` | control GREEN, caught 15, missed 0, source restored byte-identically, exit 0 |
| `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:assembleDebug` | BUILD SUCCESSFUL |
| `git diff --check` | clean |
