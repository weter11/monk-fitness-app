# Program System — Progress / History (PR 9)

Scope: the **computation layer** over the target Program System's own facts — Calendar Progress, Training
Progress and History (§21), as pure domain values plus the read-only use case that assembles the facts they
are computed from.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below as
**§N**). Implementation order: §30 step 9, *Progress / History*. Companions:
`docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1), `docs/PROGRAM_ROOM_SCHEMA.md` (PR 2),
`docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md` (PR 2.1), `docs/PROGRAM_DATA_ACCESS.md` (PR 3),
`docs/PROGRAM_COMPOSITION_ROOT.md` (PR 4), `docs/PROGRAM_LIFECYCLE.md` (PR 5),
`docs/PROGRAM_MANUAL_EDITOR.md` (PR 6), `docs/PROGRAM_SCHEDULER.md` (PR 7),
`docs/PROGRAM_SESSION_RUNTIME.md` (PR 8).

This change is **the computation and nothing else**. It adds no entity, no table, no column, no migration
and no DAO statement: the fifteen target tables PR 2 created already hold every fact §21 measures, and PR 8
is what writes them. There is no scheduler, no generator, no Focus Planner, no adaptive policy or engine,
no import/export, no ViewModel and no screen — and §21's measures that *would* need those vocabularies are
reported as deferred (a named boundary, not a zero) rather than guessed.

---

## 1. The graph

```text
AppContainer                                        ← PR 4; +1 construction, 0 lines removed
    ↓
    ProgramProgressService                          ← this PR (§30 step 9, use case)
        ├── ProgramRepository                       ⎫ read for one thing: the Programs the
        │                                           ⎭ "All Programs" aggregate is over (§21)
        ├── ProgramScheduleRepository                ← the opportunities, with statuses and attempts
        ├── WorkoutSessionRepository                 ← the attempts, each assembled from its own
        │                                              captured snapshot and its confirmed sets (§19)
        ├── clock: Clock                             ← "today", for the default window (§26)
        └── ProgressCalculator(zone: ZoneId)         ← the pure computation, over facts and nothing else
    ↓
    domain/progress                                  (pure domain, §21, §25)
    ↓
ViewModels / screens (not wired by this PR — §30 step 9 lands the contracts)
```

| file | layer | responsibility |
| --- | --- | --- |
| `domain/progress/ProgressScope.kt` | pure domain | §21's two contexts: one Program, or the All Programs aggregate (which is not an entity) |
| `domain/progress/ProgressFacts.kt` | pure domain | the raw facts a calculation is over, and the scope invariant that keeps Program A from holding B's rows |
| `domain/progress/CalendarProgress.kt` | pure domain | §21's calendar: the opportunities chronologically, and the four status counts derived from them |
| `domain/progress/HistoryItem.kt` | pure domain | one attempt as history reads it: status, actual timestamps, duration, exposure |
| `domain/progress/SessionDuration.kt` | pure domain | actual elapsed time, and the mean of a set of measured attempts (no average of nothing) |
| `domain/progress/Frequency.kt` | pure domain | completed workouts and distinct training dates inside one calendar window |
| `domain/progress/ExercisePerformanceSeries.kt` | pure domain | the comparable context (exercise + unit), one observation per confirmed set, and the series |
| `domain/progress/ContextVolume.kt` | pure domain | volume in one context, in that context's own unit and no other |
| `domain/progress/ProgramStreak.kt` | pure domain | §21's Program streak: the run of taken opportunities |
| `domain/progress/DeferredMeasure.kt` | pure domain | the §21 measures the target facts cannot answer, with the reason each is deferred |
| `domain/progress/TrainingProgress.kt` | pure domain | §21's training progress, composed of the values above and deriving nothing from the calendar |
| `domain/progress/ProgressCalculator.kt` | pure domain | the three aggregations: `calendar`, `history`, `training(facts, window)` |
| `domain/usecase/ProgramProgressService.kt` | use case | which facts a scope is, how they are read, and what "today" means |
| `di/AppContainer.kt` | composition root | one construction added (+32 lines, 0 removed); nothing else |
| `scripts/program-progress-red-mutations.sh` | evidence | the deliberate mutations of the rules below (§8) |

## 2. Three aggregations, three values — never one state

§21 names Calendar Progress and Training Progress as separate things, and the contracts list separates the
parts further (frequency, average duration, comparable performance, per-context volume, streak, history).
They are separate types here, and the calculator offers three entry points rather than one:

```text
calendar(facts)          → CalendarProgress     what the plan's calendar came to
history(facts)           → List<HistoryItem>    every attempt, chronologically
training(facts, window)  → TrainingProgress     the measures over those facts
```

There is deliberately **no `ProgressState`** that holds all three. The reason is not stylistic: an
opportunity that passed and a workout that happened are different facts (§12 *"missed Slot is not zero
performance"*), an attempt is not an opportunity, and a type that carried both would invite the collapsing
the blueprint forbids. `TrainingProgress` composes the *training* measures and reads nothing from the
calendar.

Each measure has an explicit scope, and the scopes differ honestly:

| measure | scope | why |
| --- | --- | --- |
| `CalendarProgress.completed/missed/upcoming/superseded` | the scope's opportunities | §20's statuses, as stored |
| `TrainingFrequency` | **completed** workouts inside the window | a rate is only readable against a span |
| `AverageSessionDuration` | the same completed workouts | a cancelled attempt's elapsed time is not a workout's duration |
| `ExercisePerformanceSeries`, `ContextVolume` | the scope's whole history, per comparable context | a series and a volume are cumulative descriptions, not rates |
| `ProgramStreak` | the scope's whole history, **per Program** | a run of opportunities is a Program-scoped fact (§21) |

## 3. The facts, and the scope invariant

`ProgressFacts(scope, slots, sessions)` is what the calculator is handed. Its two members are the domain
values the repositories already produce — a `WorkoutSlot` and an assembled `WorkoutSession` — not a parallel
projection of the same rows: a second shape would be a second definition of what a slot and a session are
(§25, §24). The session is the **assembled** one, whose occurrences carry the prescription captured in
`session_snapshot_exercise` and whose results are the `program_log` rows, which is exactly what §21 requires
performance progression to be derived from: *"`SessionSnapshot` + `SetLog`"*, and never the live plan (§19).

**The one invariant is §21's default context made mechanical.** A **Program-scoped** fact set must be
homogeneous: every slot and every session in it belongs to the Program the scope names. Mixing two Programs'
facts is refused at construction, *before* any measure is computed:

```text
ProgressFacts(OfProgram("program-a"), programA.slots + programB.slots, …)
  → IllegalArgumentException: a Program-scoped aggregation reads that Program's facts alone (§21):
    'program-a' was given 3 row(s) belonging to [program-a, program-b]
```

That is deliberately a check on the **facts** and not only a hope about the read that produced them. The
service also reads scoped (`slotsOfProgram` / `sessionsOfProgram` of one Program), and the two together mean
an isolation defect fails at one of two independent places rather than as a wrong number downstream. The
**aggregate** is the opposite case and needs no such rule: it is *defined* as every Program's facts, so
several program ids are legitimate in it — and nothing is merged on the way in, which is what makes it a sum
of Program-scoped facts rather than an entity with facts of its own.

**Why `ProgramProgressRepository` was not extended.** §24's repositories own their aggregates, and each fact
this stage needs already has an owner that returns it as a typed domain value. Adding a projection to
`ProgramProgressRepository` would be a second path to the same rows, and the two could disagree about what a
session is — the assembly from the snapshot is §19's guarantee, and it lives in `WorkoutSessionRepository`.
The stage therefore reads the owners and leaves the placeholder repository and its guard untouched
(`ProgramProgressRepositoryTest` passes unmodified, which is the evidence that this PR added no persistence
surface). §21's *"All Programs is an aggregation view, not an entity"* also means there is nothing to store
for it, so the read-only shape costs nothing.

## 4. §21's measures, one at a time

**completed / missed / upcoming (+ superseded).** Every opportunity of the scope, chronologically (`plannedFor`,
then identity as the deterministic tiebreak), with the status §20 gave it, and the four counts *derived* from
those entries rather than passed in beside them — so the value cannot hold a calendar of three completed days
and a count that says four. A `MISSED` entry carries no amount of work, because a slot carries none: the
absence is the representation (§12). `upcoming` is the `PLANNED` status, never a date comparison made here
(decision 3). `superseded` is reported rather than folded into `missed`, because the user was never expected
to train it (§20) and a calendar whose parts do not add up to the whole is a calendar nobody can trust.

**frequency.** `COMPLETED` sessions whose actual `startedAt` falls, in the calculator's explicit zone, on a
date inside the window; plus the number of **distinct** such dates (`trainingDays`), which is what separates
*"trained three times"* from *"trained on three days"*. Two ratios are exposed — workouts per seven calendar
dates, and training days over the window's length — and neither is a load, a score or an amount of work. A
cancelled attempt is not a workout (§12, §19); an attempt still running is not one either; a missed
opportunity is not one.

**average Session duration.** The mean of the **actual** `finishedAt - startedAt` of the completed workouts
inside the window. `measuredSessions` and `totalSeconds` are the value and the mean is derived, because the
interesting case is the empty one: an average of nothing is `null` rather than `0.0`, so no screen can print
"0m 00s average" for a Program nobody has trained. A cancelled attempt's elapsed time is a fact about the
attempt (it appears in history with its own duration) but it is not a workout's duration, and an attempt
still running has no duration at all.

**comparable performance, per exercise.** The context is `ComparableContext(exerciseId, dimension)` — the
exercise and the unit it progresses in — which is the whole of what a performed set records (§12:
*"comparable observations require compatible exercise/progression context"*; §17: volume is meaningful
*"within comparable exercise/family contexts"*). Two exercises are never one context, and repetitions are
never compared with seconds: a `ComparableContext` cannot even be built in a dimension with no unit contract.
Every confirmed set becomes one `PerformanceObservation` carrying its Program, its attempt, the attempt's
status, the set index, when it happened and what was performed — the observations of one context are the
series, in the order they happened, with the largest single set reported as `bestRepetitions` /
`bestSeconds` (decision 4). The prescription behind each observation is the one captured in the session's
snapshot, so a plan edited afterwards cannot re-explain what was measured (§19).

**volume, per context.** §17's *volume* is one exercise in one unit: a repetition context accumulates
repetitions **and no seconds**, a timed context accumulates seconds **and no repetitions**, the other side is
`null` and not `0`. There is no `total`, no `amount` and no cross-context sum anywhere — the forbidden
operation is unrepresentable rather than discouraged, and the shape is pinned field by field by
`ProgressArchitectureTest`.

**Program streak.** §21 names the measure and does not define it, so the definition is stated in full and
recorded as decision 2:

```text
COMPLETED   extends the run        MISSED      ends the run, counting as a decided opportunity
SUPERSEDED  transparent            PLANNED     transparent
```

A superseded or still-open opportunity between two taken ones does not break the streak, because the user was
not asked to train it; a missed one does, because they were. `current` is the run ending at the most recent
decided opportunity, `longest` the longest the Program has had. It is an ordinal over the opportunities' own
order, so it consults no clock and no calendar. Streaks are reported **per Program** —
`TrainingProgress.streaks` — so the aggregate reports one run per Program instead of merging two slot
sequences into a run neither Program ever had.

**recent history.** One entry per **attempt** (`HistoryItem`), newest first through the service's `history`
query, with an optional limit. An opportunity nobody attempted has no entry, and no empty one is invented for
it. Each entry carries the status **as stored**, the actual timestamps, the duration, and the exposure the
attempt recorded — `performedSets`, `exposedExercises`, `skippedExercises` — for every status (§12: partial
execution is partial exposure, and a cancelled session may still contain some).

**deferred.** `FOCUS_DISTRIBUTION`, `FAMILY_DISTRIBUTION` and `PROGRAM_PR` are reported with the reason each
cannot be computed (§12, §17, §21, decision 5). They are named **unconditionally**, on every result, because a
measure is deferred by the model rather than by the data: a caller that had to interpret an empty list would
be back to guessing whether "no focus distribution" meant *nothing to show* or *nothing that can be shown*.

## 5. The rules, and where each is enforced

| rule | enforcement |
| --- | --- |
| A Program scope cannot see another Program's history | the service reads `slotsOfProgram`/`sessionsOfProgram` of the named Program, **and** `ProgressFacts` refuses a heterogeneous Program-scoped fact set; both are measured against a database that really holds two Programs |
| "All Programs" is an aggregation, not an entity | `ProgressScope.AllPrograms` is a `data object` with no `ProgramId`, nothing is persisted for it, and every entry keeps its own Program (§21); pinned reflectively and behaviourally |
| MISSED is not zero performance | a slot carries no amount of work at all; the calendar counts the missed opportunity, and the missed opportunity contributes no observation and no volume (its *attempt's* recorded work stays exposure, §12) |
| CANCELLED != COMPLETED | frequency, average duration and the summary counts filter on `status.isCompleted`; a cancelled attempt is not a completed workout (§19) |
| A partially cancelled attempt keeps its exposure | every set log becomes an observation, whatever became of its attempt, and the observation carries the attempt's status |
| An attempt still running is not a workout and has no duration | it fails the `isCompleted` filter, and `durationOf` returns `null` while `finishedAt` is `null` — `HistoryItem` refuses to hold a duration for an attempt that has not finished |
| Session duration is the actual `startedAt → finishedAt` | one function (`durationOf`), reading the two stored stamps; a session that began at 23:30 and ended at 00:15 lasted 45 minutes |
| "Today" is the injected clock, and the calendar is the injected zone | the clock is read once, by `currentWindow()`; the zone is a constructor argument, and the window that produced a rate travels with it |
| No universal scalar volume/load/score | a volume is one context in one unit, with the other unit `null`; no `total`/`amount` field exists and no coefficient appears anywhere in the layer |
| Comparable only within an explicit context | `ComparableContext` cannot be built without a unit contract, series and volumes are keyed by it, and two exercises are never one context |
| No invented "Focus distribution" | `ProgramDayType`/focus vocabulary appears nowhere in the layer's code (the shipped reason strings name it only to explain the deferral), and the measure is reported as deferred |
| No "Family distribution" from the legacy generation | the legacy/Stage-1 family vocabulary appears nowhere in the layer; the measure is deferred with its dependency named |
| No invented "Program PR" | the strongest thing the facts define is the best single set **within one comparable context**, named for what it measures; a Program PR is deferred (decision 4) |
| Progress never writes | the service has no transaction runner, no id generator and no DAO, and no source in the layer contains a write statement |
| The repository is not turned into an analytics engine | `ProgramProgressRepository` is untouched and unread by this layer; the four reads it already had are the only ones it has |
| No UI migration | no source under `ui/` or `viewmodel/` names the layer; the test asserts it |
| No current revision is consulted for a concluded Session | the facts are the session's own snapshot and sets; the calculator never reads a revision, a day or a plan element |
| No `RevisionConflict` mechanism | nothing here compares revisions; the layer computes over what was stored |

## 6. The invariants the brief names, and where each is measured

| required invariant | measured by |
| --- | --- |
| Program A cannot see Program B's history | `ProgramProgressServiceTest.aProgramScopeReadsThatProgramsHistoryAlone` (over a database that holds both) + `ProgressFoundationTest.aProgramScopedFactSetRefusesAnotherProgramsRows` + mutations 14, 15, 21 |
| "All Programs" aggregates Programs without becoming an entity | `ProgramProgressServiceTest.allProgramsIsTheSumOfBothProgramsAndAnEntityOfItsOwn`, `ProgressCalculatorTest.theAggregateIsTheSumOfProgramScopedFactsAndHoldsNoIdentityOfItsOwn`, `ProgressArchitectureTest.theAggregateIsStructurallyNotAnEntity` + mutation 13 |
| an empty history yields zeroes and empty values of the correct type | `ProgressCalculatorTest.anEmptyHistoryYieldsZeroesAndEmptyCollectionsOfTheRightType`, `ProgramProgressServiceTest.aProgramWithNoHistoryAtAllAnswersWithZeroesAndEmptyValues` |
| MISSED produces no performance/volume | `ProgressCalculatorTest.aMissedOpportunityProducesNoPerformanceAndNoVolumeOfItsOwn`, `aMissedOpportunityCarriesNoWorkAndNoAttemptIsInventedForIt` + mutations 1, 2 |
| CANCELLED is not counted as completed | `ProgressCalculatorTest.aCancelledAttemptIsNotAWorkoutAndIsNotCountedAsOne`, `ProgramProgressServiceTest.theNumbersComeFromTheStoredRows` + mutation 3 |
| a partially cancelled Session retains its accessible exposure | `ProgressCalculatorTest.aPartiallyCancelledAttemptKeepsTheExposureItRecorded`, `ProgramProgressServiceTest.theNumbersComeFromTheStoredRows` + mutation 4 |
| Session duration comes from the actual `startedAt → finishedAt` | `ProgressCalculatorTest.averageDurationComesFromTheActualStartAndFinishStamps` (cross-midnight case), `ProgressFoundationTest.aDurationIsActualElapsedTimeAndARunningAttemptHasNone` |
| an in-progress Session is not a completed workout, has no duration, and keeps the exposure it has recorded | `ProgramProgressServiceTest.anAttemptStillRunningIsNotAWorkoutAndKeepsItsExposureSoFar`, `ProgressCalculatorTest.historyIsOneEntryPerAttemptInStartOrderWithItsOwnExposure` + mutations 4, 5 |
| frequency counts workouts on the dates they happened, in an explicit zone | `ProgressCalculatorTest.frequencyCountsCompletedWorkoutsOnTheDatesTheyActuallyStarted`, `aWorkoutIsCountedOnTheDateItsStartFallsOnInTheCalculatorsOwnZone`, `ProgramProgressServiceTest.theDefaultWindowIsTodaysAndTheClockAndTheZoneDecideWhatTodayMeans` + mutation 18 |
| the deterministic order of every sequence | `ProgressCalculatorTest.aSeriesIsInTheOrderItHappenedEvenWhenTheFactsArriveShuffled`, the `ORDER` invariants of `CalendarProgress`/`ExercisePerformanceSeries`/`TrainingProgress` + mutation 23 |

## 7. Verification

Measured on the final bytes, with forced-fresh runs whose JUnit XML `timestamp`s were checked against
`date -u`:

| gate | result |
| --- | --- |
| pristine `origin/main` baseline (`e6c95e8`, detached worktree, `:app:cleanTest :app:testDebugUnitTest --rerun-tasks`) | **238 classes / 2081 tests / 0 failures / 0 errors / 0 skipped** (newest XML `2026-09-19T11:57:17Z`) |
| branch, same command on the **restored** bytes (after the 24 mutation runs) | **242 classes / 2147 tests / 0 failures / 0 errors / 0 skipped** — **+4 classes / +66 tests**; newest XML `2026-09-19T12:10:06Z`, `BUILD SUCCESSFUL in 41s`, `date -u` `12:10:15Z` for the whole job — **9 s of freshness** |
| structural cross-check | `grep -rl '@Test' app/src/test/java \| wc -l` = 242; `grep -rho '@Test' … \| wc -l` = 2147 |
| focused PR-9 suites | `ProgressCalculatorTest` 25, `ProgressFoundationTest` 17, `ProgressArchitectureTest` 16, `ProgramProgressServiceTest` 8 — **66 tests, 0 failures** |
| `:app:compileDebugKotlin` / `:app:compileReleaseKotlin` / `:app:compileReleaseJavaWithJavac` / `:app:assembleDebug` | BUILD SUCCESSFUL in 8s |

The **+66 tests are exactly the four new classes** and nothing else: no pre-existing test was edited,
deleted, disabled or loosened, no golden, RFC or plan document was touched, and the pre-existing guards this
stage sits next to (`ProgramProgressRepositoryTest`'s pinned method list, `AppContainerTest`'s graph census,
`CompositionRootArchitectureTest`'s fences, `ProgramDataAccessArchitectureTest`'s statement census) all pass
**unmodified** — which is the evidence that no persistence surface and no wiring beyond one node was added.

`lintVitalRelease` remains excluded for the known pre-existing `themes.xml` resource cycle (unrelated to this
change); the release evidence is `compileReleaseKotlin` + `compileReleaseJavaWithJavac` + the assembled debug
APK.

### The guards that *had* to change, and why none of them was relaxed

**None.** The one pre-existing expectation that could have blocked a correct implementation is
`CompositionRootArchitectureTest.theCompositionRootDecidesNothing`, which forbids a container that decides
anything about a Program. §30 step 9's node is a **read-only query layer**, so the container wires it with the
same "wiring is not behaviour" rule that step 7 established, and the new guard that pins it is *stricter*
than the existing one: `ProgressArchitectureTest.theContainerWiresTheLayerAsOneGraphNodeWithTheDocumentedCollaborators`
counts the container's mentions of the layer (exactly three: import, type, construction), asserts the single
construction, and matches the construction against the exact literal listing its collaborators — so a zone, a
calculator override or a policy cannot ride along with the wiring unnoticed. The container's own KDoc
sentence about what it does not decide was corrected to name steps 7–9 rather than to deny that step 9
exists.

## 8. RED evidence

`scripts/program-progress-red-mutations.sh` applies one mutation at a time to the *production* sources,
reruns the four focused PR-9 suites, restores the file, and proves the restoration by `md5sum -c`.
**Twenty-three rules plus a control: 24 caught / 0 missed** on the final bytes, with all five mutated sources
verified byte-identical afterwards (`md5sum -c` printed OK for each).

The control run is the un-mutated tree and it must be **GREEN** — it is, which is what makes the other
twenty-three rows evidence rather than noise.

| mutation | rule it breaks | caught by |
| --- | --- | --- |
| a missed opportunity is read as a taken one | §12: missed is not performance | 3 tests, `aMissedOpportunityEndsTheRunAndASupersededOneDoesNot` among them |
| a missed opportunity is not reported | §12, §21: the missed fact is reported | 9 tests, `aMissedOpportunityProducesNoPerformanceAndNoVolumeOfItsOwn` among them |
| a cancelled attempt is counted as a completed workout | §19: `CANCELLED != COMPLETED` | 6 tests, `aCancelledAttemptIsNotAWorkoutAndIsNotCountedAsOne` among them |
| partial exposure is dropped | §12: a cancelled session may still contain exposure | 8 tests, `aPartiallyCancelledAttemptKeepsTheExposureItRecorded` among them |
| a running attempt is given a duration | §19: an unfinished attempt has not happened | 8 tests: `aHistoryEntryCannotClaimADurationItDoesNotHave` (the value refuses it), `aDurationIsActualElapsedTimeAndARunningAttemptHasNone`, `historyIsOneEntryPerAttemptInStartOrderWithItsOwnExposure` |
| the average ignores the status and the window | §12, §19, §21: the average is over completed workouts in the window | 4 tests, `averageDurationComesFromTheActualStartAndFinishStamps` among them |
| an average of nothing is zero | §21: an average of nothing is no average | 5 tests, `anAverageOfNothingIsNoAverageRatherThanZeroSeconds` among them |
| every observation is put in a repetition context | §12, §17: an observation is measured in its own unit | 11 tests, `performanceIsPerExerciseAndUnitAndTheTwoAreNeverOneSeries` among them |
| the exercise is dropped from the context | §12, §17: two exercises are never one context | 8 tests, `theAggregateComparesAcrossProgramsOnlyWithinAComparableContext` among them |
| a volume carries both units | §17: only comparable contexts, never one scalar | 21 tests — `ContextVolume` refuses the value at construction |
| a superseded opportunity breaks the streak | §20, §21: it was never expected | `aMissedOpportunityEndsTheRunAndASupersededOneDoesNot` |
| an open opportunity extends the streak | §21: a streak is about what happened | 4 tests, `theStreakRunsOverTakenOpportunitiesAndTreatsTheTwoUndecidedStatesAsTransparent` among them |
| the aggregate invents its own Program | §21: All Programs is not an entity | 4 tests, `allProgramsIsTheSumOfBothProgramsAndAnEntityOfItsOwn` among them |
| a Program scope accepts another Program's facts | §21: the selected Program is the context | 4 tests — `ProgressFacts` refuses to be built |
| the service reads every Program for one Program's scope | §21: the read is scoped too | 6 tests, `aProgramScopeReadsThatProgramsHistoryAlone` among them |
| the calendar guesses `missed` from a date | §20, §33: the timing layer owns the status | 7 tests, `anOpenOpportunityStaysOpenEvenWhenItsDateHasPassed` among them |
| the window stops bounding the rate | §21: a rate is over the span it was asked for | 3 tests, `theWindowIsPartOfTheAnswerSoARateCannotBeReadWithoutItsSpan` among them |
| the instant is read in the wrong calendar | §21's calendar semantics and the zone contract | `aWorkoutIsCountedOnTheDateItsStartFallsOnInTheCalculatorsOwnZone` |
| history is not newest first | §21: recent history | 3 tests, `historyIsNewestFirstAndLimitedToWhatWasAskedFor` among them |
| the default window reads the device clock | §26: the clock is injectable | 2 tests, `theDefaultWindowIsTodaysAndTheClockAndTheZoneDecideWhatTodayMeans` among them |
| a Program scope reads a substitute for its Program | §21: the scope's own facts | 8 tests |
| the deferred measures disappear | §21: a measure the facts cannot answer is not a zero | 2 tests, `theMeasuresTheFactsCannotAnswerAreReportedAsDeferredRatherThanAsZero` among them |
| the series is not ordered | §21: a deterministic, chronological history | 11 tests — `ExercisePerformanceSeries` refuses the value when the order is not the one it happened in |

**Two kinds of catch appear in that table, and they are not the same evidence.** A *behavioural* catch is a
measure that changed and a test that measured it. An *invariant* catch is a mutant that cannot even be
**built** as a value — a volume carrying both units, a series out of order, an attempt that is still running
yet has a duration — which is the design: the forbidden state is unrepresentable, so the failure is the
constructor's refusal rather than a wrong number downstream.

**One rule has a fence that is currently unreachable, and it is worth saying out loud.** A plan element
prescribed in `SET_BASED`, `DIFFICULTY_BASED` or `REST_BASED` produces no series and no volume
(`hasComparableUnit`), and no test can exercise that path behaviourally today because the sealed
`Prescription` hierarchy has only `RepPrescription` and `TimePrescription` — there is no way to *build* an
occurrence in a reserved dimension (§10: *"their units are not decided here and must not be guessed here"*).
The fence is therefore pinned by the contract it depends on: `ComparableContext` refuses a reserved
dimension, `PrescriptionDimension.entries.filterNot { it.hasComparableUnit }` is asserted to be exactly the
three reserved ones (`ProgressFoundationTest.theReservedDimensionsHaveNoRepresentablePrescription`), and the
dimension→unit mapping is asserted for the two that exist. Calling it a behavioural test would be the
"unreachable swap" mistake.

## 9. Remaining decisions to review

1. **`ProgramProgressRepository` is not extended; the layer reads the repositories that own the facts.**
   The brief says the repository *"may be minimally extended to include only the raw facts actually required
   for P9 calculations"* — and the honest answer after reading the tree is that no such raw fact is missing:
   slots come from `ProgramScheduleRepository`, and attempts-with-their-snapshot-and-sets come from
   `WorkoutSessionRepository`, which is the only place §19's assembly exists. A second projection would be a
   second reader of the same rows and a second definition of a session's presentation. **Cost of the
   alternative:** adding e.g. `performanceFacts(programId)` (one join over `program_set_log` ×
   `session_exercise` × `workout_session`) would make the per-exercise measures one query cheaper and would
   need its own DAO correspondence row in `ProgramDataAccessArchitectureTest`; the calculator would then take
   a projection type instead of `WorkoutSession`, and the snapshot guarantee would have to be re-argued for
   it. The stage did the cheaper, safer thing; if the owner wants the projection, it is an additive change to
   `ProgramProgressRepository` plus a second fact type, and nothing in the domain values changes.
2. **The streak's definition.** §21 names the measure and not its semantics. This stage defines it as a run
   over **taken opportunities**, with superseded and still-open opportunities transparent and a missed one
   ending the run, and reports it per Program. The alternative readings that were rejected: *consecutive
   calendar days with a completed workout* (that is a different, also defensible measure — it would use the
   same zone machinery the frequency uses and is a small addition), and *consecutive planned opportunities
   without a miss* (which would make a superseded slot break a streak the user had no part in). If the owner
   wants the calendar-day reading, it is one new function in the calculator plus one field.
3. **`upcoming` is the stored `PLANNED` status, not a date comparison.** §20 gives the Scheduler ownership of
   *missed*, and §33 forbids guessing from a date, so Progress does not re-classify an opportunity whose date
   has passed but whose status the timing layer has not yet moved: doing so would be a second missed-ness
   rule, and the two could disagree. Every calendar entry carries its planned date, so a screen can show
   "overdue" without Progress inventing a status. **If the owner prefers** Progress to derive it, the change
   is confined to `ProgressCalculator.calendar` plus a reference date parameter — and it would then need a
   decision about what a date-derived "missed" means for an opportunity that also has an attempt.
4. **No Program PR view: the measure is reported as deferred, with the reason.** The target facts record the
   exercise, the unit and what was performed — no progression level, no difficulty and no variant relation —
   so a *record against a comparable context* cannot be defined without inventing the missing context, which
   §17 forbids (*"no invented conversion"*). What the facts **do** define unambiguously is reported instead:
   the best single set within one context (`bestRepetitions` / `bestSeconds`), named for what it measures
   rather than as a PR. **Extension point:** when the adaptive layer (step 11) owns a progression level or a
   difficulty per plan element, `ProgressMeasure.PROGRAM_PR` becomes computable against that level and the
   `DeferredMeasure` entry is replaced by a value — the vocabulary and the reason already exist, and no
   existing field changes. The engine's all-time PR (§21's *Global* list) is out of scope here for a stronger
   reason: it is explicitly **global** data, and §21 warns that *"Global values must not become Program-owned
   accidentally"*.
5. **Focus distribution and family distribution are deferred, with their owners named.** Focus belongs to the
   Focus Planner (§8, §30 step 10): the runtime records no focus for a slot or a session, and a
   `ProgramDayType` is the *kind of day* (`TRAINING`/`REST`), not the weighted exposure plan §8 describes —
   converting one into the other would be inventing the vocabulary, and the distribution would then be over
   nothing. Family is a **deferred dependency**: family membership exists today only in the legacy
   generator's catalogue and in the Stage-1 adaptive state (`family_progression_state`), and reading Progress
   out of that generation would mix two architectures (§1, §23, §30 step 15). Both are declared as
   `DeferredMeasure`s — `TrainingProgress.deferred` — so a screen shows nothing rather than `0`, and the
   dependency each needs is in the shipped message.
6. **The rate-like measures are windowed; the cumulative ones are not.** Frequency and average duration are
   computed over an explicit `ProgressWindow` (the service's default is the last 28 calendar dates ending
   today in its zone, `DEFAULT_WINDOW_DAYS`), while series, volumes and streaks describe the whole history.
   The window travels inside the result, so "recently" is never implicit. The alternative — windowing
   everything — would make "volume" mean "volume in the last four weeks" without saying so, and the
   alternative of *no* default window would force every caller to invent one.
7. **A cancelled attempt's duration is a fact about the attempt, not a workout's duration.** It appears in
   history with its own `SessionDuration`, and it is excluded from the average, which is a measure of
   training. If the owner wants "average time spent in the workout screen" as a separate measure, that is a
   second value over a different set of attempts rather than a change to this one.
8. **`java.time` in the domain is unchanged exposure, flagged for the record.** `domain/usecase/ProgramCalendar.kt`
   and the scheduler already use `LocalDate`/`Instant`/`ZoneId` while `minSdk = 24` has no core-library
   desugaring; this stage adds nine more values that use `java.time` (dates are what §21's calendar is made
   of) and no new dependency. Recorded as a pre-existing project-level decision, not resolved here.
9. **One line of the brief's test list was truncated in the request** — *"an in-progress Session…"*. The
   behaviour implemented, and covered by four assertions, is: an in-progress attempt is **not** a completed
   workout (it fails the `isCompleted` filter, so it is in neither the frequency nor the average), it has
   **no** duration, the sets it has already confirmed are **already exposure**, and the opportunity it is
   being worked on is still **open** rather than taken. If the intended sentence was something else (for
   example that an in-progress attempt should be excluded from exposure too, or should be shown in a separate
   section), say so and it is a small change — but note that excluding its sets would contradict §12's
   *"partial execution = partial Exposure"*.
10. **`DEFAULT_WINDOW_DAYS = 28` is a constant of the use case.** Four weeks is the shortest span in which a
    three-times-a-week program shows a whole number of weeks. It is a presentation default and a caller can
    pass any window; nothing stored depends on it.

### What is deliberately *not* open

* **No UI migration.** §30 step 9 completes the domain/use-case/data contracts; `ProgressScreen`,
  `MainViewModel`, navigation and the legacy workout UI are untouched, and a test asserts that nothing under
  `ui/` or `viewmodel/` names the layer.
* **No new persistence.** No entity, column, migration or DAO statement; `ProgramProgressRepository` is
  unchanged.
* **No rewrite of PR 8.** The session runtime's semantics — slot revision authoritative, snapshot immutable,
  cancelled stays cancelled, missed is not zero, no `RevisionConflict`, atomic completion — are consumed
  here, not modified.

## 10. Base

Branch `feat/program-progress-history`, cut from `origin/main` at **`e6c95e8`** (the merge of PR #290,
§30 step 8). The branch adds the thirteen pure-domain sources, the use case, one construction in the
composition root, the four suites and the mutation script; it changes no existing file except
`di/AppContainer.kt` (additive: +1 import, +1 property, +1 KDoc block, and one corrected KDoc sentence) and
`docs/` (this document).
