# Program target schedule orchestration — Stage 12

## Boundary

Stage 12 adds the single application-level entry point for the whole target scheduling pipeline:

```text
TargetSchedule[]
        ↓
TargetPlanner
        ↓
TargetSchedulePolicy
        ↓
TargetScheduleApplicationService
        ↓
TargetScheduleSlotPersister
        ↓
ProgramScheduleRepository
```

`TargetScheduleOrchestrator` lives in `domain/usecase`, beside the Stage 11 application service it
composes. Stage 12 adds **no scheduling semantics**. Every semantic decision was already owned by a
component from Stage 2 to Stage 11; this stage connects them in order and stops.

The pass performs exactly three statements:

```kotlin
val targetPlan = TargetPlanner.plan(
    schedules = request.schedules,
    window = request.window,
    selection = request.selection,
    existing = request.existing,
    sources = request.sources
)
val decision = TargetSchedulePolicy.decide(
    targetPlan = targetPlan,
    existing = request.existing,
    asOf = request.asOf,
    pauses = request.pauses
)
val applicationResult = applicationService.apply(
    programId = request.programId,
    revisionId = request.revisionId,
    targetScheduleDecision = decision,
    programDayBindings = request.programDayBindings
)
```

## The ownership chain

| Stage | Owns |
| --- | --- |
| Stage 2 | resolve target dates (`TargetScheduleResolver`) |
| Stage 3 | compose occurrences (`TargetOccurrenceComposer`) |
| Stage 4 | reconcile membership (`TargetOccurrenceReconciler`) |
| Stage 5 | `TargetPlanner` — semantic target computation |
| Stage 6 | `TargetSchedulePolicy` — temporal classification |
| Stage 7 | `TargetOccurrencePresenter` — ProgramDay presentation |
| Stage 8 | persisted target identity schema |
| Stage 9 | `TargetSlotMaterializer` — pure slot materialization |
| Stage 10 | `TargetScheduleSlotPersister` — target persistence |
| Stage 11 | `TargetScheduleApplicationService` — presentation → persistence application boundary |
| Stage 12 | `TargetScheduleOrchestrator` — composition of those three layers only |

Stated directly:

- **TargetPlanner owns semantic target computation.**
- **TargetSchedulePolicy owns temporal classification.**
- **TargetScheduleApplicationService owns presentation → persistence application.**
- **TargetScheduleOrchestrator owns only the composition of those three layers.**

## Request and result

`TargetScheduleOrchestrationRequest` is a pure value carrying every input one pass needs:
`programId`, `revisionId`, `schedules`, `window`, `selection`, `existing`, `sources`, `asOf`,
`pauses`, `programDayBindings`. Every one of them is an existing domain value — `ProgramId`,
`RevisionId`, `TargetSchedule`, `TargetScheduleWindow`, `CompositionSelection`, `ExistingOccurrence`,
`ResolvedScheduleSource`, `LocalDate`, `ProgramPauseWindow`, `TargetProgramDayBinding`.

No input is read from ambient state. The orchestrator holds no `Clock`, no repository, no
`IdGenerator`, and no date, random or UUID source; the same request always describes the same
semantic pass, and two equivalent requests produce equality-identical results.

`TargetScheduleOrchestrationResult` keeps all three semantic boundaries whole:

```text
request            — what was asked for
targetPlan         — what semantic target computation produced
decision           — what temporal classification produced
applicationResult  — what presentation and persistence produced
```

They are deliberately **not** flattened into one generic collection: a caller can inspect what the
planner produced, what the policy changed or classified, and what was actually presented and
persisted, without reconstructing any layer.

## The critical semantic rule

`TargetPlan.planned` is **not** a substitute for `TargetScheduleDecision.created`. After
`TargetSchedulePolicy.decide(...)` the application service receives the `decision` itself, so its
own existing contract processes exactly `decision.created`. There is no bypass and no
re-derivation. A planned occurrence the policy classified as `missed`, `superseded` or `retained`
never reaches presentation, and a past or paused occurrence never becomes a slot.

## Policy ownership

`TargetSchedulePolicy` owns temporal interpretation, and its result is authoritative. Phase 12
therefore does **not** independently filter past dates, paused dates, future dates, retained
occurrences, superseded occurrences or missed occurrences. There is no extra date condition around
`decision.created`, no second `isPaused`, and no second temporal policy. The architecture gate proves
this by forbidding every date-comparison and collection-rewriting token in the orchestrator source.

## Existing occurrences, sources and selection

`request.existing` is passed unchanged into `TargetPlanner.plan(...)` and then unchanged into
`TargetSchedulePolicy.decide(...)`. The orchestrator does not sort it in place, filter it, rebuild
`ExistingOccurrence`, infer execution from dates, discard `STARTED`/`COMPLETED`/`CANCELLED` values,
or derive actual results. Existing occurrence semantics belong to the caller and to the target
policy and reconciler.

`request.sources` is passed directly into the planner. The orchestrator does not resolve
`ResolvedScheduleSource`, inspect `DerivedExcluding`, or synthesize source occurrences.
`request.selection` is forwarded unchanged; same-date selective composition remains the composer's
work.

## Presentation, persistence and identity

The orchestrator never constructs `ProgramDayId(...)` or `TargetOccurrencePresentation(...)`; it
only forwards `request.programDayBindings` to the application service, and Stage 7 remains the sole
owner of target-occurrence → ProgramDay presentation.

It never touches `ProgramScheduleRepository`, `ProgramWorkoutSlotDao`, `ProgramWorkoutSlotEntity`,
Room or SQLite. Its only persistence call is
`TargetScheduleApplicationService.apply(...)`; persistence remains entirely below Phase 11.

Phase 12 owns no identities. It constructs no `SlotId`, `ProgramId`, `RevisionId`, `ProgramDayId`,
`targetOccurrenceKey`, UUID or random value. `ProgramId` and `RevisionId` arrive already owned in
the request, and slot identity remains the Stage 10 persister's responsibility.

## Failure propagation

There is no generic exception handling. `TargetOccurrencePresentationException`,
`TargetSlotPersistenceException` and the planner's and the policy's own validation failures propagate
unchanged. The orchestrator does not catch `Exception`, does not return an empty result, does not
convert a conflict into a success, and does not convert invalid input into a no-op. A missing or
ambiguous ProgramDay binding and a persistence conflict remain typed failures end to end.

## Legacy isolation

The target orchestrator does **not** replace the legacy scheduler. It is not injected into
`ProgramScheduler`, the legacy scheduler does not call the target path, and the target path does not
call the legacy scheduler. There is no fallback between the generations. Both contours coexist:

```text
LEGACY CONTOUR                TARGET CONTOUR

ProgramScheduler              TargetScheduleOrchestrator
    ↓                              ↓
SlotPlanner                   TargetPlanner
    ↓                              ↓
legacy slots                  TargetSchedulePolicy
                                  ↓
                              TargetScheduleApplicationService
                                  ↓
                              TargetScheduleSlotPersister
```

`ProgramScheduler.kt`, `SlotPlanner.kt` and `ScheduleCalendar.kt` are not modified by this stage.
The cutover — replacing the legacy contour with the target one — remains future work.

## Composition root

`AppContainer` wires `targetScheduleOrchestrator` beside `targetScheduleSlotPersister` and
`targetScheduleApplicationService`. The orchestrator receives only the application service, and no
repository, clock or ID generator. The legacy `programScheduler` node is untouched.

## Verification map

- `TargetScheduleOrchestratorTest` covers the exact pipeline, created-only application when
  `planned != created`, same-date independent target occurrences, an existing retained target, an
  existing `STARTED` occurrence and slot, an existing `COMPLETED` occurrence and slot, pause
  behaviour, a past unpaused occurrence staying `missed`, a removed future target staying
  superseded, a missing ProgramDay binding propagating, a persistence conflict propagating, an
  empty created set, request input immutability, determinism, and legacy-contour isolation.
- `TargetScheduleOrchestratorArchitectureTest` pins placement in `domain/usecase`, the single
  allowed service collaborator, the forbidden direct collaborators, the target stage ownership
  boundary, the absence of manual presentation, the absence of slot materialization, the request
  field list, the result field list, the absence of ambient state, unchanged typed failures, the
  composition-root wiring, and legacy isolation.
- `scripts/program-stage12-red-mutations.sh` runs a green control, applies every deliberate
  orchestration mutation, requires all to be caught, requires zero misses, and verifies
  byte-identical restoration.

## Revised guards

Three previous-stage guards asserted an **absence** — that no production source outside the pure
target package named the Stage 2 values, the planner, or the policy. Stage 12 is now that single
legitimate caller, so each claim was inverted rather than relaxed, and each was renamed to the claim
that is now true:

| Guard | Was | Is now |
| --- | --- | --- |
| `TargetScheduleArchitectureTest` | `oldSchedulerSourcesRemainOutsideAndTheNewLayerIsNotWiredIntoProduction` | `oldSchedulerSourcesRemainOutsideAndStageTwoHasExactlyOneProductionCaller` |
| `TargetPlannerArchitectureTest` | `stageFiveIsNotWiredIntoTheExistingSchedulerOrAnyOtherProductionPackage` | `stageFiveHasExactlyOneProductionCallerAndItIsTheStageTwelveOrchestrator` |
| `TargetSchedulePolicyArchitectureTest` | `stageSixIsNotWiredIntoAnyOtherProductionPackage` | `stageSixHasExactlyOneProductionCallerAndItIsTheStageTwelveOrchestrator` |

Each inverted pin asserts a **closed one-element list** naming
`domain/usecase/TargetScheduleOrchestrator.kt`, so a second consumer still fails it. The
legacy-contour half of each guard was kept as an explicit negative assertion: `ProgramScheduler`,
`SlotPlanner` and `ScheduleCalendar` still may not reach Stage 2, Stage 5 or Stage 6. The Stage 2
guard's token set was also extended with `ResolvedScheduleSource`, the source type the request now
carries, so the pin covers the whole Stage 2 surface the orchestrator touches.

No other previous-stage guard was weakened. Stage 7's
`stageSevenHasExactlyOneProductionCallerAndItIsTheStageElevenApplicationBoundary` is unchanged: the
orchestrator reaches the presenter only through the application service, never directly.
`TargetSlotMaterializerArchitectureTest` and the Stage 10 persister pins are likewise unchanged.

## Verification (measured on this branch)

Base: `origin/main` `ddd83f1` (merge of PR #313), measured in a detached worktree.

| Check | Result |
| --- | --- |
| base JVM suite | 265 classes / 2336 tests / 0F / 0E / 0S |
| branch JVM suite (`cleanTest`, `--rerun-tasks`) | 267 classes / 2362 tests / 0F / 0E / 0S |
| suite delta | +2 classes / +26 tests (15 behaviour + 11 architecture) |
| focused Stage 12 suites | 26 tests / 0F / 0E / 0S |
| `scripts/program-stage12-red-mutations.sh` | control GREEN, caught 17, missed 0, source restored byte-identically, exit 0 |
| `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:assembleDebug` | BUILD SUCCESSFUL |
| `git diff --check` | clean |

## Architecture gaps recorded

- The orchestrator is constructed in `AppContainer` but has no production consumer yet. The target
  contour is live as an entry point, not as a replacement: the legacy scheduler still owns
  production scheduling, and the cutover is a separate stage.
- `TargetScheduleApplicationResult.persistenceResult` is the first place a caller can observe stored
  `WorkoutSlot` rows. Reading them back for a schedule view is the cutover's concern, not this
  stage's.
