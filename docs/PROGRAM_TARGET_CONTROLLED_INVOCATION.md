# Stage 21 — the first controlled production invocation of the target scheduling contour

## What this stage is

Stage 20 built `TargetScheduleProductionConsumer` — the first *real* production consumer of the
target scheduling contour — and deliberately left it with **no production caller at all**. The path
existed, was correct, and was unreachable. Stage 21 closes exactly that gap, and nothing else:

```text
UI
  ↓  one application-level operation (§3's Start)
ProgramStartService
  ├── ProgramLifecycleService.startProgram(…)   → refused | failed | the started Program
  └── TargetScheduleProductionConsumer.run(…)    → only after a successful start
```

This is the **first controlled production invocation**, not a cutover. `ProgramScheduler` remains the
production scheduler, `SlotPlanner` and `ScheduleCalendar` are byte-identical to the base, and no
legacy slot is read, written or reconciled by anything this stage adds.

The class is `com.monkfitness.app.domain.usecase.ProgramStartService`, in
`app/src/main/java/com/monkfitness/app/domain/usecase/ProgramStartService.kt`, together with its
result vocabulary `ProgramStartResult` and the target-owned pause adapter
`TargetSchedulePauseAdapter` in the same package. Nothing is added to the pure
`domain/program/target` package.

## Why this boundary, and not the controller

The five values one target run needs that storage does not state are five *policies*:

```text
persisted, revision-owned target source   →  rules + bindings
persisted target occurrences              →  existing occurrences
----------------------------------------------------------------------
caller-owned                              →  window, selection, sources, asOf, pauses
```

A screen, a view model or a `Compose` node that built any of them would be making a scheduling
decision in the UI layer (§16, §33). A consumer that defaulted any of them would be a second policy
wearing a consumer's clothes. So the UI sees exactly one operation, and the policies live in the
application boundary next to the lifecycle transition they are derived from.

`ProgramLifecycleService` is **not** extended with target scheduling responsibility. It owns
`NOT_STARTED → RUNNING ↕ PAUSED → COMPLETED` and nothing else; its policy stays in
`ProgramLifecyclePolicy`, which the new service never names.

## The exact invocation order

The order is the contract, and it is asserted mechanically — on the **first** reach of the consumer,
because a pass composed above the transition would leave every later index in order while planning
for a Program that has not started:

```text
lifecycle.startProgram(programId)
    ↓
Success(Program)             → carry on
Refused(reason)              → ProgramStartResult.StartRefused; the target pass is never reached
Failure(cause)               → ProgramStartResult.StartFailed;  the target pass is never reached
    ↓
asOf ← started.actualStartDate, read in the explicit ZoneId
    ↓
build the explicit run context
    ↓
TargetScheduleProductionConsumer.run(programId, context)   ← exactly once
    ↓
Scheduled     → ProgramStartResult.Started(started, run)
any of the four typed absences → ProgramStartResult.TargetSchedulingRefused(started, run)
```

`AlreadyThere` is a **successful** lifecycle result (§3: a lifecycle already at the target is a
no-op, not an error), so a Start on a `RUNNING` Program runs the same controlled invocation again
over the same factual start date. That is deliberate, and it is what makes a repeated invocation
idempotent: the second pass creates nothing, presents nothing, and leaves both target tables
byte-identical. It is recorded as a gap below rather than quietly converted into a no-op, because
"Start is idempotent" and "Start is a no-op the second time" are different contracts and only the
first is true here.

## The four result cases

```kotlin
sealed interface ProgramStartResult {
    data class Started(val program: Program, val targetScheduling: TargetScheduleRunResult.Scheduled)
    data class StartRefused(val programId: ProgramId, val reason: ProgramOperationRefusal)
    data class StartFailed(val cause: Throwable)
    data class TargetSchedulingRefused(val program: Program, val targetScheduling: TargetScheduleRunResult)
}
```

§28's vocabulary is unchanged: a lifecycle refusal is still a `ProgramOperationRefusal` and a
lifecycle failure is still a `Throwable`. What is new is that the operation is a **composition**, and a
composition has an outcome its parts do not: the Program started *and* the target pass did not run.
Collapsing that into the start's own success would report a scheduled Program when nothing about it
was planned.

The distinction is structural, not a matter of discipline: `Started` is typed on the consumer's
**success** arm, so a `SourceMissing` does not compile there. The gate asserts both field types.

A typed refusal raised by the target stages themselves — the resolver refusing a derived rule whose
resolved source was not stated, the input boundary refusing a duplicate rule identity, the presenter
refusing a missing plan-day binding — **propagates unchanged and is never caught here**. The gate
bans `catch`, `try {` and `runCatching` from the file, so "propagates" is mechanical rather than a
promise.

At the UI, the two started variants get two different notices, so the second half is not hidden:
`ProgramNotice.STARTED` when both halves ran, and
`ProgramNotice.TARGET_SCHEDULING_UNAVAILABLE` when the Program started and the target pass was
refused. The latter is deliberately *not* a lifecycle refusal and *not* a success.

## The five explicit context values

| value | this stage's production policy | why this one |
| --- | --- | --- |
| `asOf` | the started `Program.actualStartDate`, read in the explicit `ZoneId` | the same factual moment the transition just recorded. A pass whose as-of date is not the start date is classifying a Program against a moment that never existed. There is no second clock read. |
| `window` | `TargetScheduleWindow(asOf, asOf + (WINDOW_DAYS - 1))`, `WINDOW_DAYS = 30` | a bounded, named **target** horizon — exactly thirty inclusive dates. |
| `selection` | `CompositionSelection()` | no implicit cross-rule composition. |
| `sources` | `emptyMap()` | authored definitions and *resolved* sources are different facts. |
| `pauses` | `TargetSchedulePauseAdapter.windowsOf(persisted, zone)` | the conversion exists once, here, rather than in a caller. |

### The 30-day window is target policy, not a legacy alias

```kotlin
const val WINDOW_DAYS: Long = 30
window = TargetScheduleWindow(asOf, asOf.plusDays(WINDOW_DAYS - 1))
```

This is **not** `ScheduleWindow`, not `PLANNING_HORIZON_DAYS`, not `plannedStartDate`, not
`actualStartDate` used as an implicit anchor, and not `revision.duration`. It is also deliberately not
a second copy of the legacy `ScheduleHorizon`.

The legacy constant happens to hold 30 as well, which is why the RED row that substitutes it is
scored by the architecture gate's whole-token ban and by nothing else: the two windows are
numerically identical, so no behavioural oracle can distinguish them. That is the reason the ban
exists.

### The empty composition selection

`CompositionSelection()` means "no rule composes with another". It is not derived from the number of
program days, from day names, from a legacy schedule or from workout identity — each of those would
be a second composition policy living in a boundary that has no business having one.

### The empty resolved-source map

Nothing manufactures a resolved source from the authored rules. A `DerivedExcluding` rule whose
resolved source this pass does not state is refused **by the resolver, in the resolver's own
vocabulary**, and that refusal propagating is the correct behaviour to assert. Derived-source
resolution policy remains a separate, unwritten decision.

### The pause adapter and its `ZoneId`

`TargetSchedulePauseAdapter` is target-owned and takes the calendar explicitly:

```text
closed interval   startedAt → firstDate    (in the given zone)
                  endedAt   → lastDate     (in the same zone)
open interval     refused — it has no honest conversion
```

An instant is not a date: which day a pause covers depends on the calendar it is read in, so the zone
is an argument and `ZoneId.systemDefault()` never appears in the adapter. The integration suite
reads the *same stored instants* in UTC and in Asia/Tokyo and gets a different suppression window out
of each, which is what makes "the calendar is the stated one" a measurement rather than a claim.

**The open interval.** A `ProgramPauseWindow` is a closed interval by construction, so an open pause
has no honest conversion. It is refused rather than given `today`, the window's last date,
`LocalDate.MAX` or any other artificial end — all of which would suppress occurrences the model says
were not paused. The adapter's `require` is **not** a second copy of the lifecycle rule: the
invariance it leans on is that a pause can only be opened by `PAUSE`, which is legal only from
`RUNNING`, while a Program enters `RUNNING` only through `START` or `RESUME` — both of which close
the open interval. A Program that is *starting* therefore has no open pause. The integration suite
reaches the branch by planting the interval directly on a `NOT_STARTED` Program, which is exactly the
stored data that contradicts the lifecycle the same Program reports.

## Transaction semantics — Variant B, and the gap it leaves

```text
Program lifecycle start write
        ↓
successful result
        ↓
target scheduling pass
        ↓
target persistence transaction
```

**There is currently no single atomic transaction spanning both.**

`ProgramLifecycleService.startProgram` writes through `ProgramRepository.updateProgram`; the target
rows are written inside the slot persister's own transaction, reached through
`TargetScheduleOrchestrator`. The composition root's transaction runner is held by the repositories,
and no abstraction spans the two writes. A "delete the Program while its target pass runs" failure is
therefore possible, and this stage does not claim otherwise.

Rather than invent a transaction layer for one stage, the ordering is stated and the failure is
surfaced: the start lands first, the target pass runs second, and a target refusal is reported in its
own result case. Making the two atomic across those boundaries is a separate decision — recorded
below as a gap, not designed around.

## Legacy isolation

After this stage:

```text
ProgramScheduler     unchanged, still the production scheduler
SlotPlanner          unchanged
ScheduleCalendar     unchanged
legacy slots         never read, written or reconciled by the invocation
```

The invocation creates and updates **target-owned persistence only**. There is no
target → legacy slot reconciliation, no legacy slot → target occurrence conversion, and no
backfill. The integration suite asserts the legacy slots are byte-identical across the operation, and
a RED row that rewrites them through `recordSlotOutcome` is caught.

The three legacy files are proven byte-identical to the Stage 21 base by blob hash, not by grep — see
the verification section of the pull request.

## Existing target occurrences

The Stage 20 read path is untouched. The invocation still reaches
`TargetScheduleProductionConsumer → TargetScheduleOccurrenceRepository →
TargetExistingOccurrenceReader → TargetOccurrenceExecutionPolicy`. It does not read `WorkoutSlot.status`
as a substitute for execution, does not build a `TargetExistingOccurrence`, does not filter occurrences
by the current window before the consumer sees them, and does not treat legacy slots as target
occurrences. A completed occurrence stays `COMPLETED` across another invocation, and that is
asserted by reading the stored history back **through the reader**, not from a slot status.

## What the UI is given

```text
ProgramsController.start(programId)
    → ProgramStartService.start(programId)
```

The controller holds the application service and nothing else target-related. It builds no run
context, no window, no selection, no source map, no pause window and no as-of date, and it does not
decide what to do with a `DerivedExcluding` rule. The architecture gate sweeps `ui/` and
`viewmodel/` for all seven of those types and requires the empty result, **and** asserts that the
ban fires on the line a careless screen would write, so the rule is a rule rather than a statement
about today's tree.

## Architecture pins: revised, not relaxed

| gate | before | after |
| --- | --- | --- |
| `TargetScheduleProductionConsumerArchitectureTest.theCompositionRootWiresTheConsumerOnceAndNothingAboveItCallsTheConsumer` | a closed list of **zero** callers | **renamed** to `…AndExactlyOneApplicationBoundaryCallsIt`; the list holds `domain/usecase/ProgramStartService.kt`. Its scan was also re-keyed from the container's *property name* to the *type*, because a caller receives the consumer as a constructor parameter and calls it — a scan for `targetSchedule…` would have read empty on a tree where the composed Start demonstrably holds it. |
| `TargetScheduleArchitectureTest.oldSchedulerSourcesRemainOutsideAndStageTwoHasExactlyThreeProductionCallers` | a three-element closed list of files naming a Stage 2 value outside the pure package | **renamed** to `…ExactlyFourProductionCallers`; the list gains `domain/usecase/ProgramStartService.kt`, which names `TargetScheduleWindow` because it *decides* the window rather than forwarding one. A fifth consumer still fails. |
| `ProgramsArchitectureTest.theStateHoldersCollaboratorsAreExactlyTheOwnersOfWhatItDoes` | the eleven collaborators the controller held | the list gains `ProgramStartService`. The state holder still names no target type and still decides nothing about windows, compositions or sources. |
| `ProgramsArchitectureTest.theViewModelTakesTheServicesFromTheCompositionRootAndHandsThemToTheStateHolder` | the handed-over nodes | gains `programGraph.programStartService`. |
| `ProgramAdaptiveIntegrationArchitectureTest.theCompositionRootHandsTheProducerAndTheConsumerOneCalendar` | six `zone = zone` hand-overs | seven, because the composed Start turns an instant into a date twice. Every hand-over *strengthens* the rule; a fourth boundary acquiring its own calendar still breaks the count. |
| `ProgramsControllerTest.theLifecycleActionsReachTheServiceAndAreReported` | Start published `STARTED` | the fixture's editor states no target authoring, so Start now publishes `TARGET_SCHEDULING_UNAVAILABLE`; the **success** notice is asserted by a new case that *does* state an authoring. The claim changed, so the test says why. |

No cardinality check was weakened or deleted. Two lists grew by exactly one named file, each with the
reason recorded in the assertion, and one count went from 6 to 7 for a named reason. Every negative
assertion is kept whole.

The new gate `TargetScheduleControlledInvocationArchitectureTest` states fifteen mechanical claims,
comments stripped first: one production caller; the caller is this stage's node and holds exactly
four collaborators; no `ui/`/`viewmodel/` run-context construction; the five fields stated and the
window this stage's own policy; no ambient time anywhere in the two new files; no resolved-source
type named at all; the pause adapter pure, zone-explicit and end-date-inventing-free; no legacy
vocabulary, no slot write, no identity generator, no storage type, no second scheduler; lifecycle
policy in its own file and the new service asking the service rather than the policy; every typed
absence carried into the refusal case with no `catch`; and the ordering, stated on the first and only
reach of the consumer.

## Tests

`TargetScheduleControlledInvocationIntegrationTest` — eleven cases on a real SQLite engine, every
collaborator composed as `AppContainer` composes it. Only the clock, the identity generator and the
calendar are values, because a test cannot ask the device for them.

| # | case | what it proves |
| --- | --- | --- |
| 1 | `aStartCreatesTheTargetOccurrenceAndTheTargetSlot` | the whole path from a `NOT_STARTED` Program, thirty occurrences and thirty target slots, and the as-of date is the start date |
| 2 | `theLegacySlotsTheApplicationTrainsFromAreByteIdenticalAfterTheStart` | legacy isolation, by identity and status |
| 3 | `aSecondControlledInvocationCreatesNoDuplicateOccurrenceOrSlot` | the `AlreadyThere` semantics and idempotence |
| 4 | `theProductionContextIsTheFiveStatedValuesAndEachReachesTheStageThatOwnsIt` | the real production values, with the pause observably suppressing its own dates |
| 5 | `thePauseCalendarIsTheStatedOneAndTheSameStoredInstantReadsAsAnotherDate` | the same stored instants, two calendars, two different windows |
| 6 | `anOpenPauseIntervalIsRefusedRatherThanGivenAnInventedEnd` | the open-interval refusal, reached from real stored data |
| 7 | `aDerivedRuleWithNoStatedSourceIsTheResolversOwnRefusalAndWritesNothing` | the resolver's own refusal propagates, and the Program still started |
| 8 | `aProgramWithNoTargetAuthoringIsATypedAbsenceAndWritesNoTargetRow` | `SourceMissing`, zero target rows, the legacy schedule demonstrably present |
| 9 | `aProgramTheLifecycleRefusesToStartNeverReachesTheTargetContour` | a refused start, and target persistence **unchanged** |
| 10 | `twoProgramsWithIdenticalRuleIdentitiesDoNotConsumeEachOthersTargetState` | cross-Program isolation with byte-identical authoring |
| 11 | `aCompletedTargetOccurrenceStaysCompletedAcrossAnotherControlledInvocation` | stored history stays authoritative across a second invocation |

The suite needs a `createNotStartedGraph()` helper: the shared `ProgramGraphFixture` creates a Program
that is already `RUNNING`, which would make every case an `AlreadyThere` no-op rather than the start
this stage is about.

`ProgramsControllerTest` adds the UI's two halves — `STARTED` with an authoring, and
`TARGET_SCHEDULING_UNAVAILABLE` without one — measured through the real state holder on the real
engine.

## RED mutation evidence

`scripts/program-stage21-red-mutations.sh` — eighteen rows, with the harness rules this repository has
used since Stage 12: a compile error is never a catch, a comment-only mutation proves nothing, a
no-op mutation is never a catch, and every mutated source is restored byte-identically
(`md5sum -c` from the repository root).

The oracle is the Stage 21 behavioural and architecture suites, the two gates whose closed caller
lists name this stage's file, and the UI suite. Rows 2, 3 and 15 all concern "the pass ran when it
must not" and are written so that **a different oracle sees each**: row 2 by the first-reach
ordering assertion, row 3 by target persistence changing on a refused start, row 15 by the result
type the caller is handed. Rows 13 and 14 preserve the target result's own distinction — one is
scored on the refusal propagating, the other on the result case itself — rather than on notice text.

## Known architecture gaps

These are recorded rather than designed around.

1. **Cross-boundary atomicity is not available.** The lifecycle start write and the target
   persistence write are in two different transactions, and no abstraction spans them. Documented
   above; a design decision, not an oversight.
2. **The open-pause adapter refusal is `require`-based and unreachable through a valid Start.** A
   `NOT_STARTED` Program cannot have an open pause, so the branch is only reachable from stored data
   that contradicts the lifecycle. The gate pins it in the source rather than relying on the
   behavioural suite to reach it.
3. **`AlreadyThere` causes another controlled invocation.** A Start on a `RUNNING` Program re-runs
   the pass over the same factual start date. It is idempotent, but it is not free, and a product
   that wants "Start" to be a no-op once running will need that stated.
4. **The UI notice coarsens the typed target refusals.** The service carries all four typed absences;
   the screen reports one notice for all of them. Surfacing *which* absence it was is a product
   decision, and it needs its own strings.
5. **`ProgramStartService` holds a repository for one call.** The persisted pauses have to come from
   somewhere, and the only existing boundary that owns them is `ProgramScheduleRepository`. A
   narrower "pauses of a Program" port would express the need directly, and is not added here
   because it would widen a boundary this stage's job is to *use*.

## Explicitly deferred

None of the following is implemented by this stage, and none should be inferred from it:

```text
full ProgramScheduler cutover
Home / session runtime migration
legacy slot retirement
legacy ↔ target reconciliation
legacy → target backfill
UI target authoring
persisted composition selection
persisted resolved derived sources
indefinite-horizon maintenance policy
full target pause lifecycle policy
```

Stage 21 is only the **first controlled production invocation**. A Program that states no target
source still starts, still trains from its legacy slots, and reports that its target schedule was
not planned.
