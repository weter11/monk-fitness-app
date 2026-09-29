# Stage 20 — the first production target scheduling consumer

## What this stage is

Stages 12–19 built the target scheduling contour and then made it *authorable*, and at the end of
Stage 19 every production caller of a target stage was a test. The contour was complete and
unreachable: a resolution → composition → reconciliation → planning → policy → presentation →
persistence → execution-read-back chain, all of it separately callable, none of it called by anything
the app runs.

This stage connects the two halves — the persisted explicit source of Stages 18/19, and the target
runtime of Stages 12–17 — for the first time, in a real application-layer consumer:

```text
ProgramId
  ↓  ProgramRepository.programById            → ProgramNotFound
  ↓  ProgramPlanRepository.currentRevision   → CurrentRevisionMissing
  ↓  TargetScheduleSourceBridge              → SourceMissing | SourceMalformed | Source
  ↓  TargetScheduleOccurrenceRepository.occurrencesOfProgram
  ↓  TargetExistingOccurrenceReader.existingOccurrencesOf
  ↓  TargetScheduleInputAdapter.adapt
  ↓  TargetScheduleOrchestrator.apply
target plan → temporal decision → presentation → target slot + target occurrence, on disk
```

The class is `com.monkfitness.app.domain.usecase.TargetScheduleProductionConsumer`, in
`app/src/main/java/com/monkfitness/app/domain/usecase/TargetScheduleProductionConsumer.kt`. It is an
application/use-case boundary: not a repository, not a domain singleton, not a UI component, and not
in the pure `domain/program/target` package — the architecture gate pins that package's file list and
this stage adds nothing to it.

## What is new, and what is not

Three production files change:

| file | change |
| --- | --- |
| `domain/usecase/TargetScheduleProductionConsumer.kt` | **new** — the consumer, its run context, and its result vocabulary |
| `di/AppContainer.kt` | three new graph nodes: the stored-execution read-back, its bridge, and the consumer |

Nothing else in `src/main` changes. In particular `ProgramScheduler`, `SlotPlanner`,
`ScheduleCalendar`, `ProgramsController`, `ProgramHomeController`, `ProgramSaveService`,
`ProgramImportService` and the session runtime are byte-identical.

Two graph nodes are new to the container for a second reason as well as the first: until now
`TargetOccurrenceExecutionReader` and `TargetExistingOccurrenceReader` existed but were not wired, so
the container gains them as the consumer's collaborators. They are the *same* objects the container
would have held if a cutover stage had arrived first.

## The exact read path

1. **`programRepository.programById(programId)`** — the aggregate's own existence read. `null` is
   `TargetScheduleRunResult.ProgramNotFound`. The revision is *not* taken from this call.
2. **`planRepository.currentRevision(programId)`** — the revision the stored `currentRevisionId`
   pointer names (§23), fully assembled. `null` here can only mean the pointer names a revision that
   is not stored, because step 1 already established that the Program exists; that is
   `CurrentRevisionMissing`, a different fact from an absent Program.

   Two repositories rather than one is deliberate. `ProgramRepository.programWithCurrentRevision`
   answers both questions in one read, but it answers "the pointer is dangling" by throwing
   `IllegalStateException`. Converting that throw into a result case would be a second translation
   policy at this boundary, and the brief's failure table wants a *typed* outcome rather than a
   caught exception. Two reads through two existing repository boundaries give all six outcomes
   without a single `catch`.
3. **`sourceBridge.definitionsAndBindingsOf(revisionId)`** — the persisted, revision-owned explicit
   source. This is the bridge's first production consumer.
4. **`occurrenceRepository.occurrencesOfProgram(programId)`** — every stored target occurrence of the
   Program, in the repository's own read order (planned date, then key). No date filter, no horizon
   and no limit is applied before the target stages see them: which of them are past, missed, paused
   or still to come is `TargetSchedulePolicy`'s question, not this boundary's.
5. **`existingOccurrenceReader.existingOccurrencesOf(programId, stored.map { it.occurrence })`** —
   each stored occurrence is handed over as a **lookup key** and the reader returns the stored record
   back. That is what keeps the reconciler's payload comparison honest: comparing the caller against
   itself would make a stored payload conflict unobservable.
6. **`inputAdapter.adapt(input)`**, then **`orchestrator.apply(request)`** — the two existing owners.
   The consumer constructs no request and runs no stage.

## The caller-owned runtime context

```kotlin
data class TargetScheduleRunContext(
    val window: TargetScheduleWindow,
    val selection: CompositionSelection,
    val sources: Map<String, ResolvedScheduleSource>,
    val asOf: LocalDate,
    val pauses: List<ProgramPauseWindow>
)
```

No default, no validation, no derivation. The architecture gate asserts the field list, the
declaration order, the absence of any defaulted constructor argument, and — field by field — that
each of the five reaches `TargetScheduleInput` as `context.<field>` and nothing else.

The integration suite does not stop at equality. Each of the five is given a value that would change
the outcome if the consumer replaced it:

| value | how the suite proves it reached the stage that owns it |
| --- | --- |
| `window` | a Thursday/Saturday cadence only resolves at all inside a window that contains one |
| `selection` | combining a rule identity changes the composed occurrence key to `combined:<date>:…` |
| `sources` | a `DerivedExcluding` rule produces nothing on the date the stated source excluded — and with an empty map the resolver's own `require` refuses the pass |
| `asOf` | nothing before it is created |
| `pauses` | the covered date creates nothing |

### Why they stay explicit

- **window.** The target core deliberately treats `TargetScheduleWindow` as an explicit bounded
  input and states that it "never supplies or infers the current date". This stage does not
  reintroduce `ProgramSchedule`, `ScheduleWindow`, `PLANNING_HORIZON_DAYS`, `plannedStartDate`,
  `actualStartDate` or `revision.duration` as a way of manufacturing one. A horizon algorithm is a
  policy, and policies are named, tested and owned; an implicit one inside a consumer is neither.
- **selection.** Composition is a decision about which rules may share an occurrence. Inferring it
  would be a second composition policy.
- **sources.** `TargetScheduleSource` states *authored* definitions; `TargetScheduleInput.sources` is
  a deliberately separate `Map<String, ResolvedScheduleSource>` of *resolved* occurrences. Nothing
  here manufactures the second from the first. A `DerivedExcluding` rule whose source the caller did
  not state is refused by the resolver, in the resolver's own words — the integration suite asserts
  that refusal propagates rather than being answered with an empty source.
- **asOf.** The temporal stage's classification boundary. A consumer that read the device's date
  would make every pass depend on when it ran, and the architecture gate bans `LocalDate.now`,
  `Instant.now` and `System.currentTimeMillis` from the file outright.
- **pauses.** `ProgramPauseWindow` is already a date window, and the target policy's contract states
  that the caller owns the conversion of persisted pause instants into it. This stage does not
  reinterpret `ProgramPause`, does not invent a final date for an open pause, and does not duplicate
  the legacy `ScheduleCalendar` pause logic. A later stage may add a target-owned pause adapter; this
  one does not.

## The result boundary

```kotlin
sealed interface TargetScheduleRunResult {
    data class Scheduled(revisionId, input, result) : TargetScheduleRunResult
    data class ProgramNotFound(programId)           : TargetScheduleRunResult
    data class CurrentRevisionMissing(programId)     : TargetScheduleRunResult
    data class SourceMissing(revisionId)             : TargetScheduleRunResult
    data class SourceMalformed(revisionId, reason)   : TargetScheduleRunResult
}
```

- **A missing source is not an empty source.** A revision that states nothing about target semantics
  and a revision that schedules nothing are different claims, and only the second is representable —
  a rule-less revision cannot produce an occurrence. The `Missing` arm is a `return`, and the gate
  asserts that the branch is exactly that return and that no branch of the run constructs a
  `TargetScheduleSource` out of an empty rule list.
- **A malformed source is reported with its reason**, never defaulted. The suite corrupts a stored
  cadence token to a value outside the vocabulary — the same mechanism Stage 18 established, which is
  reachable precisely because the entity's own guard defers unknown tokens to the mapper.
- **Neither refusal writes a row.** The source is read before the occurrences, so a refused run never
  reaches the adapter, the pass or the database. Both refusal tests assert zero target occurrences and
  zero target slots.
- **A failed pass is not a case here.** The target stages' typed refusals — a duplicate rule identity
  at the input boundary, a missing plan-day binding at presentation, a conflicting stored payload at
  persistence — propagate unchanged. A `Failed(cause)` wrapper would be a second answer to "which
  rule refused", and the stages that own those rules already give one. The architecture gate bans
  `catch`, `try`, `runCatching` and `throw` from the consumer, so "propagates" is a mechanical claim
  rather than a promise.

## The boundary that did *not* move

`ProgramScheduler` remains the production scheduler. After this stage:

```text
ProgramScheduler                     remains the production scheduler
TargetScheduleRuntime/Consumer       becomes a separately callable production target path
UI / ProgramsController /
  ProgramHomeController              remain on ProgramScheduler
```

Nothing above the composition root calls the consumer. The gate asserts it as a **closed list** of
callers (`emptyList()`), and separately that no file under `ui/` or `viewmodel/` names the consumer,
the input adapter, the orchestrator, the source bridge or the execution bridge. The integration suite
adds the behavioural half: after a target run, the legacy slots the production UI trains from are
byte-identical, and the consumer's constructor takes no legacy scheduling boundary at all.

This is **not** a cutover. A cutover decides who invokes the target path; this stage only makes the
path callable from production code, so that a later stage can be tested and exercised on its own
before anything is rewired.

## Architecture pins: revised, not relaxed

Four existing gates were false by construction once the first production consumer existed. Each was
rewritten as the claim that is now true, and each keeps a closed list so a *second* consumer still
fails.

| gate | before | after |
| --- | --- | --- |
| `TargetScheduleArchitectureTest.oldSchedulerSourcesRemainOutsideAndStageTwoHasExactlyTwoProductionCallers` | a two-element closed list of files naming `TargetScheduleWindow` / `ResolvedScheduleSource` outside the pure package | **renamed** to `…ExactlyThreeProductionCallers`; the list gains `domain/usecase/TargetScheduleProductionConsumer.kt` with the reason in the assertion. The resolver stays uncalled outside the pure package, and the legacy-negative half is unchanged. |
| `TargetScheduleInputAdapterArchitectureTest.theAdapterIsNotWiredIntoTheSchedulerRuntimeUiOrAnyRepository` | a construction-site absence: nothing outside the composition root built the adapter | **renamed** to `theAdapterHasExactlyOneProductionConsumerAndItIsTheStageTwentyNode`; the pin is now over files that *name* the type, and the legacy half becomes an explicit `ui/` + `viewmodel/` sweep. A construction-site scan would have kept reading empty and claiming the adapter was unconsumed while a production consumer held it — which is the failure mode §8b of the playbook describes. |
| `TargetScheduleSourceArchitectureTest.theTargetContourStaysASeparatelyCallableContourAndNothingWasCutOver` | "the bridge is wired but not yet consumed" | the same claim as a one-element consumer list; the legacy-contour half (no legacy file names any Stage 18 type) is untouched. |
| `TargetScheduleAuthoringArchitectureTest.productionConsumersStillDoNotRunATargetPass` | "the bridge is wired but still consumed by nobody" | **renamed** to `theSaveBoundaryStillRunsNoTargetPassAndTheOnlyTargetConsumerIsTheStageTwentyNode`. Every negative assertion — the save boundary runs no pass, names no bridge, the editor names no target type, the legacy contour is untouched — is kept whole. |

No cardinality check was weakened or deleted. The one pin that grew
(`ExactlyTwoProductionCallers → ExactlyThreeProductionCallers`) grew by exactly one named file, with
the reason recorded in the assertion, and a fourth consumer still fails it.

### The new gate

`TargetScheduleProductionConsumerArchitectureTest` states seventeen mechanical claims, comments
stripped first:

1. the consumer is an application boundary, and the pure target package's file list is unchanged;
2. its collaborator set is exactly the seven the read/assembly/run boundary needs;
3. it names no identity generator, clock, zone, DAO, entity, Room type or UI state — plus a
   **path-level** storage ban, because a whole-token ban on `Dao` cannot see a qualified
   `…SourceDao`; that ban is proved against a synthetic careless line so it is a rule and not a
   statement about today's tree;
4. it names no legacy scheduling vocabulary (whole-token, with `_` a word character, so
   `ProgramScheduler` inside a ban on `ProgramSchedule` is not a false positive);
5. no production source anywhere maps a legacy schedule onto a target rule;
6. it reimplements no target stage, and the adapter and the orchestrator are each invoked exactly once;
7. it decides no execution state — no occurrence value is even named, no slot status, no aggregation;
8. it reads no ambient time or randomness and translates no failure;
9. the `Missing` arm is a `return`, and no branch manufactures an empty source;
10. the source is read exactly once and it is read against the revision the current-revision read
    named;
11. the run context declares the five values, in order, with no defaults;
12. the input is assembled field for field, five values from `context.*` and three from storage;
13. the result vocabulary is exactly the five refusal/success cases, each carrying its fact;
14. the container wires the consumer once, hands it no identity and no legacy boundary, and **nothing
    above the composition root calls it**;
15. the legacy planner is still constructed in exactly one place, is still the container's only
    scheduler property, and the consumer's file does not contain the strings `ProgramScheduler`,
    `SlotPlanner` or `ScheduleCalendar` at all — in code *or* in prose.

## Tests

`TargetScheduleProductionConsumerIntegrationTest` — eleven cases on a real SQLite engine, with every
collaborator composed exactly as `AppContainer` composes it. Only the identity generator is a
sequence, because a test cannot ask the device for ids. Nothing is mocked: the Program, its revision,
its plan, the authored source, the target occurrence, the target slot, the session and its confirmed
sets are all written and read through production paths.

| # | case | what it proves |
| --- | --- | --- |
| 1 | `aPersistedAuthoringBecomesAStoredTargetOccurrenceAndTargetSlot` | the whole chain, and that the fixture's legacy slots are still there beside the new target ones |
| 2 | `everyFieldOfTheRuntimeComesFromTheStoredSourceAndNotFromLegacyProgramData` | `ruleId`, `workoutId`, cadence form + payload, `anchorDate` and the plan-day binding all cross; then the legacy schedule is rewritten underneath and the rerun is unchanged |
| 3 | `aStoredOccurrenceIsSuppliedByTheExecutionBridgeAndItsStoredHistoryStaysAuthoritative` | a completed attempt's occurrence arrives as `COMPLETED`, is preserved rather than created, is not duplicated, and its payload equals what the repository holds |
| 4 | `runningTheSameRequestTwiceCreatesNoDuplicateOccurrenceOrSlot` | the second pass creates nothing, presents nothing, and both tables are byte-for-byte unchanged |
| 5 | `aLegacyCreatedRevisionWithNoTargetSourceIsATypedAbsenceAndWritesNoTargetRow` | `SourceMissing`, zero target rows, and the legacy revision's schedule demonstrably present |
| 6 | `aMalformedStoredSourceIsReportedRatherThanDefaulted` | `SourceMalformed`, zero target rows |
| 7 | `theCallerOwnedContextIsForwardedUnchangedAndEachValueReachesTheStageThatOwnsIt` | equality for all five, plus the behavioural consequence of each |
| 8 | `aDerivedRuleWithNoStatedSourceIsTheResolversOwnRefusalAndIsNotManufacturedHere` | the resolver's `require` propagates; nothing is written |
| 9 | `theRunBelongsToTheCurrentRevisionAndAnEarlierRevisionsSourceIsUntouched` | two revisions with two sources; the run reads B, A is unchanged, and no occurrence was written against A |
| 10 | `twoProgramsMayHoldIdenticalRuleIdentitiesWithoutReadingOrWritingEachOther` | identical rule ids and occurrence keys under two Programs; running one leaves the other's target table empty and its legacy slots intact |
| 11 | `theConsumerRunsNoLegacySchedulingPassAndLeavesTheLegacySlotsAlone` | the legacy slots are byte-identical after a target run, and the constructor holds no legacy boundary |

## RED mutation evidence

`scripts/program-stage20-red-mutations.sh` — eighteen rows, one per rule the brief names, with the
harness rules the stage has used since Stage 12: a compile error is never a catch, a comment-only
mutation proves nothing, a no-op mutation is never a catch, and every mutated source is restored
byte-identically (verified with `md5sum -c` from the repository root).

The oracle is the Stage 20 behavioural and architecture suites plus the two architecture gates whose
closed consumer lists name this stage's file. Four rows are *second declarations in a forbidden
vocabulary* rather than retyped signatures — a legacy `ProgramSchedule` parameter, a `SlotStatus`
parameter, a `ProgramScheduler` reference, a DAO reference — because those are the shapes a careless
caller would actually have, and a retyped public signature would instead stop the test source set from
compiling and charge the row as a failure for the wrong reason.

**Measured result** (full run, `bash scripts/program-stage20-red-mutations.sh`):

```text
control GREEN
caught: 18
missed: 0
not-a-catch: 0
every mutated source restored byte-identically
```

One row needed a fix before it could be scored, and the harness was right to refuse to score it.
"The orchestrator is bypassed" originally named `TargetPlanner` and `TargetSchedulePolicy`
unqualified; the consumer does not import them, so the row died at `e:` and was charged
`NOT A CATCH` rather than a catch — correctly, because no oracle had seen it. The payload now writes
both types **fully qualified**, which keeps the row type-correct in the whole tree. The corrected row
is caught by a real oracle: the architecture gate's *names no target stage* assertion fires, and so do
five behavioural cases, because the recomposed pass never persists a target occurrence. No oracle was
weakened to achieve that, and no mutation was deleted or downgraded.

## What a later cutover stage still has to establish

This stage deliberately leaves all of the following undone, and each is a decision, not an
implementation:

1. **Who invokes the consumer.** The UI, a controller, the save boundary, the import boundary and
   the session runtime all stay on `ProgramScheduler`. A cutover has to choose the call site and say
   what happens to the legacy slots that pass then owns.
2. **The policy for the five caller-owned values.** A window, a horizon, a composition, an as-of date
   and a source map each need a stated policy — probably an authoring surface, not an inference.
3. **A target-owned pause adapter**, if persisted `ProgramPause` instants are ever to become
   `ProgramPauseWindow`s without a caller doing it by hand.
4. **Whether a derived rule's resolved source is authored, derived or refused** in production. This
   stage's answer is "refused by the resolver unless the caller states it", which is correct and
   probably not what a product wants long-term.
5. **Reconciliation of the legacy slots a cutover would strand**, and what happens to them when a
   target pass supersedes an occurrence the legacy planner created.
6. **The UI authoring surface.** Stage 19 added the seam; the editor still supplies no authoring, and
   a Program can only acquire a target source through the save boundary's `targetSchedule` parameter.

## Architecture gaps recorded

* `ProgramRepository.programWithCurrentRevision` cannot be used for a typed "current revision
  missing" outcome without catching `IllegalStateException`. The stage works around it with two
  existing reads rather than adding a new repository method or a `catch`. If a third caller needs the
  same distinction, a nullable `currentRevisionOf(programId): ProgramRevision?` on `ProgramRepository`
  is the honest fix, and this stage's two reads are the evidence that it is wanted.
* `TargetExistingOccurrenceReader` can only be reached by handing it a list of `PlannedOccurrence`
  keys. "Every stored occurrence of this Program" is therefore a two-step read
  (`occurrencesOfProgram` then `existingOccurrencesOf`) rather than one call. It is honest — the reader
  is still the only thing that turns stored facts into a scheduling input — but a
  `existingOccurrencesOfProgram(programId)` on the reader would express the use case directly. It is
  not added here because it would widen a component this stage's only job is to *use*.
* `TargetScheduleRunContext` has no `programId`; the Program is a run parameter. That is deliberate —
  it keeps the context to the five values storage does not state — but it does mean the context cannot
  be validated against a Program at construction.
