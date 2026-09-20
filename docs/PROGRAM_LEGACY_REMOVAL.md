# §30 Step 15 — Legacy Removal and the Program System Cutover

**Status:** implemented on `feat/program-legacy-removal`, based on `6f56493` (the §30 step 14 merge).

This document records what §30 step 15 actually did. It is written from the source, the schema and the
test runs in this tree, not from the plan: every number below was measured here, and where something is
deferred it is named as deferred rather than described as done.

---

## 1. The finding that decided the stage's shape

The brief's central question was *"is the target runtime actually live?"* The answer, measured before any
deletion:

```text
SessionRuntime is constructed at di/AppContainer.kt and referenced by nothing else in src/main.
The only other occurrence is a sentence in a KDoc comment.
```

So the target workout runtime was **DI-wired and unused**, and production ran the shipped 56-day program
end to end:

```text
HomeScreen → onStartWorkout(currentDay) → "workout/{day}" → WorkoutScreen(day)
           → MainViewModel.startWorkoutSession(day, mode) → UserProgress / ProgramDayState / set_log
```

That made §30 step 15 a **build**, not a rewire: the target architecture needed its production path, and
only then could the legacy one go. Everything below follows from that.

---

## 2. The cutover — one production workout runtime

### 2.1 The new path

```text
Home
 └── ProgramHomeController.load()
      ├── ProgramLifecycleService.myPrograms()      → the one global selection (§3)
      ├── ProgramScheduler.nextOpportunity(id)      → the earliest startable opportunity (§20)   [new]
      └── ProgramProgressService.calendarProgress / trainingProgress   → §21's counts and the streak
 └── "Start workout" → programs/session/{slotId}

programs/session/{slotId}
 └── ProgramSessionController.open(slotId)
      ├── SessionRuntime.startSession(slotId)
      │     └── refused with SlotIsAlreadyBeingWorkedOut(slotId, sessionId)?
      │           → SessionRuntime.restoreSession(sessionId)     (the refusal names the attempt)
      ├── confirmSet(sessionId, sessionExerciseId, …)            (position from the stored rows)
      └── finish()
            ├── ProgramAdaptiveIntegration.adaptAfter(sessionId) → AdaptiveCompletion
            └── SessionRuntime.finishSession(sessionId, adaptive)
                  → session + slot + adaptive state/decision/adjustment, ONE transaction (§27)

Progress
 └── ProgramProgressController.load()
      ├── ProgramProgressService.calendarProgress   → completed / missed / upcoming / superseded
      ├── ProgramProgressService.trainingProgress   → frequency, session duration, streaks, deferred
      └── ProgramProgressService.history            → the attempts themselves
```

### 2.2 What each piece owns

| piece | owns | does **not** own |
| --- | --- | --- |
| `ProgramHomeController` | Home's read of selection + next opportunity + calendar | any write; any scheduling decision |
| `ProgramSessionController` | calling the runtime and rendering what it returns | a set counter, a completion rule, a state machine |
| `ProgramSessionScreen` | presentation, the rest countdown, keep-screen-on, vibration | any persistence; it must not even name `SessionRuntime` |
| `ProgramScheduler.nextOpportunity` | *which* opportunity is next (a read of its own rows) | planning, writing, or inventing an anchor |
| `SessionRuntime` | start / restore / confirm / cancel / finish | scheduling, lifecycle policy, progress, UI |
| `ProgramProgressController` | reading §21's measures | computing any of them |

### 2.3 Route and identity

```text
retired   "workout/{day}"                       a day number was a position in a 56-day grid, not an identity
retired   "posture-workout/{day}"               replaced by "posture-workout" (no argument)
retired   "exercise/{exerciseId}?day=&isPosture="   the day was carried and never used by the lookup
added     "programs/session/{slotId}"           the opportunity the runtime starts, restores and completes
```

`MainViewModel.ROUTE_PROGRAM_SESSION` + `programSessionRoute(slotId)` are the only workout identity.

---

## 3. Legacy inventory and classification

| component | class | why |
| --- | --- | --- |
| `data/model/UserProgress.kt` (table `user_progress`) | **RETIRE** | the shipped 56-day program's day-level completion |
| `data/model/ProgramDayState.kt` (table `program_day_state`) | **RETIRE** | its 1..56 grid, cycle number and missed flags |
| `data/model/SetLog.kt`, `SetLogRow.kt` (table `set_log`) | **RETIRE** | the shipped set log; the target writes `program_set_log` |
| `data/local/ProgressDao.kt` | **REPLACE — split** | mixed: program state + posture + nutrition + body weight + set log |
| `data/repository/WorkoutRepository.kt` | **REPLACE — split** | mixed the same way |
| `data/model/FamilyProgressionState.kt`, `data/local/FamilyProgressionStateDao.kt`, `data/repository/AdaptiveRepository.kt` | **RETIRE** | Stage-1 adaptive persistence |
| `data/model/AdaptiveDecisionRecord.kt`, `data/local/AdaptiveDecisionHistoryDao.kt` | **RETIRE** | Stage-1 adaptive audit trail |
| `data/repository/AdaptiveSessionDecisionRecorder.kt`, `SessionAdaptivePlanReader.kt`, `SessionHistoryAdapter.kt` | **RETIRE** | the Stage-1 adapters over those tables |
| `data/repository/ProgramMaintenance.kt` | **RETIRE** | the per-cycle wipe, plus the old Full-Reset table lists |
| `domain/adaptive/AdaptivePolicy.kt`, `AdaptiveProgramEngine.kt`, `AdaptiveSignalCalculator.kt`, `AdaptiveSignals.kt`, `AdaptiveProgramInput.kt`, `AdaptiveProgressionPlan.kt`, `ProgressionResolver.kt`, `ProgressionProfile.kt`, `PilotProgressionProfiles.kt`, `AdaptiveDecision.kt`, `AdaptiveReasonCode.kt`, `AdaptiveEvidence.kt`, `ExerciseResult.kt`, `SessionObservation.kt`, `SessionObservationMapper.kt`, `SessionSetLog.kt`, `SessionOutcome.kt`, `PlannedExercise.kt` | **RETIRE** | the Stage-1 adaptive generation |
| `domain/usecase/AdaptiveWorkoutIntegration.kt` | **RETIRE** | its integration with the retired routine builder |
| `domain/usecase/ProgramCalendar.kt` | **REPLACE — moved** | the 56-day arithmetic stays; it moves to `domain/track/TrackCalendar` |
| `ui/screens/WorkoutScreen.kt` | **RETIRE** | the day-based session screen |
| `viewmodel/ActiveWorkoutConfiguration.kt`, `WorkoutSessionContext.kt`, `SetLogObservation.kt` | **RETIRE** | the legacy session's configuration holder, context and set observation |
| `UiState.HomeUiState`, `UiState.WorkoutSessionUiState` | **RETIRE** | they presented `ProgramDayState` / the legacy session |
| `MainViewModel`'s program half | **REMOVE** | every legacy Program read/write (§5 of the brief) |
| Settings → *Restart current cycle*, *Start revised program* | **RETIRE** | §16: no target concept of a cycle; the intent is Edit/Copy → new Revision → Start |
| `posture_session_progress` | **RETAIN — renamed** | a retained global feature; its row identity becomes `trackCycle`/`trackDay` |
| `body_weight_log`, `meal_cycles`, `meals`, `shopping_items` | **RETAIN** | global features, untouched |
| `domain/adaptive/{AdaptiveScope, LoadProfile, AdaptiveInputSnapshot, AdaptiveState, FamilyProgressionState, ExposureObservation, decision/*, engine/*, integration/*}` | **RETAIN — target dependency** | the target engine and integration consume them |
| `WorkoutGenerator` (catalogue + posture/mobility routine) | **RETAIN — target dependency** | the Exercise Library, and the retained track's routine; its day-based *routine* methods lost their caller |
| `CustomProgramScreen` / `CustomProgramEditor` / `ProgramConfiguration` / `WorkoutConfigurationSnapshot` / `ProgramConfigurationRepository` / `ProgramConfigurationValidator` / the validation vocabulary | **RETIRE — proved dead, see §9** | a setting whose only reader was the editor that wrote it |
| `domain/adaptive/ProgramConfigurationValidation.kt`'s `TrainingDomain` / `BodyRegion` / `ExerciseMetadata` | **RETAIN — split out** | a **target dependency** (the Generated planner and `data/model/Exercise.kt` consume them); they moved to `domain/adaptive/ExerciseMetadata.kt` |

The evidence for the last two rows' "zero callers" claims is the compiler plus the greps in §11.

---

## 4. What was **not** migrated

The blueprint is explicit that **no legacy program/history migration is required**, and this stage took it
literally:

```text
UserProgress row       → NOT a Session
ProgramDayState row    → NOT a ProgramWorkoutSlot
set_log row            → NOT a program_set_log row
Stage-1 family level   → NOT a program_family_progression_state row
Stage-1 decision row   → NOT a program_adaptive_decision_record row
```

No compatibility path converts one into the other, and no target row is fabricated. The measurement is
`ProgramMigrationPreservationTest.noLegacyRowBecomesATargetRow`: after the whole chain, **every** target
table is empty on a database that held legacy rows. The target Program history begins with the target
runtime.

The same decision applies to the DataStore: the *retained track's* anchor is carried forward (§6) because
it belongs to a retained feature; the shipped program's stored cycle number and revision integer are not.

---

## 5. Global features preserved

| feature | how it survived |
| --- | --- |
| Body weight | `NutritionRepository` (body-weight log) + `NutritionDao` |
| Nutrition, meals, meal cycle | `NutritionRepository`, its own calendar (`startDate` + `durationDays`) |
| Shopping list | `NutritionRepository`, same calendar |
| Exercise Library | `WorkoutGenerator.getExerciseLibrary()` — a target dependency already |
| Posture / mobility track | `PostureRepository` + `PostureProgressDao`, on `TrackCalendar` |
| The optional mobility **session** | `PostureSessionScreen` + `MainViewModel.postureMobilityWorkout()` / `completePostureWorkout()` |
| Global settings, language, timer, vibration | untouched; the session screen keeps screen-on and buzzes on a rest boundary through `platform/VibrationFeedback` |
| Onboarding, notifications | untouched |
| Progress screen's body-weight card and posture chart | kept verbatim; the rest of the screen is repointed (§8) |

`posture_session_progress` is the one retained table whose **shape** changed, and its rows were copied
rather than recreated — asserted by `ProgramMigrationPreservationTest.theTrackRowIsRenamedAndNotRewritten`.

---

## 6. The retained track's calendar, and the anchor

`domain/track/TrackCalendar` carries the 56-day arithmetic verbatim:

```text
day 1  = the anchor
day 56 = anchor + 55
day 1 of the next cycle = anchor + 56
a date before the anchor clamps to cycle 1 day 1 (not cycle 0)
cappedDay() stops at 56 instead of wrapping   — the nutrition phase's reading
```

Ownership moved, arithmetic did not: `TrackCalendarTest` derives its expectations from the formula
independently and compares them across three cycles and the boundaries either side.

**The anchor.** `SettingsManager` reads a new key (`track_start_date`) and falls back to the key older
builds wrote (`program_start_date`); `ensureTrackStartDate()` copies an existing legacy value forward and
**never writes the legacy key**. So an install that predates this stage keeps the track position it had
instead of being moved to "today", and there is no second source of truth. This is asserted on the source
by `TrackCalendarTest` (the project has no Robolectric harness, so the DataStore object itself cannot be
constructed in a test — the source assertion is the strongest available form of the claim).

`MainViewModel.currentTrackDay` is the retained track's day; the nutrition screen and the meal planner read
it. It is **not** a Program's identity, and the target Program authority is unchanged:

```text
Program → ProgramRevision → ProgramDay → ProgramExercise
ProgramWorkoutSlot → WorkoutSession → SessionSnapshot → SessionExercise → program_set_log
```

---

## 7. Room migration 11 → 12

A real migration, not a recreation. `AppDatabase.MIGRATION_11_12`:

```text
1. posture_session_progress: rebuilt and COPIED row-for-row
      cycleNumber → trackCycle, day → trackDay, same primary-key order
      (rebuilt rather than ALTER … RENAME COLUMN, because that needs SQLite 3.25 and minSdk predates it)
2. DROP  user_progress · program_day_state · set_log · family_progression_state · adaptive_decision_record
```

Nothing else is touched: the target tables, `app_state`, the body-weight log and the nutrition calendar
receive no statement. There is **no destructive fallback** in the builder, so a device that cannot migrate
fails loudly instead of losing the retained rows — asserted by
`ProgramMigrationPreservationTest.theUpgradeDoesNotUseADestructiveRecreation`.

### 7.1 Test-tree copies of the chain

The chain is duplicated in the test tree by design (each rig runs the deployed migrations), and every copy
that needed it was updated:

| copy | change |
| --- | --- |
| `data/repository/ProgramDataAccessRig` | runs `MIGRATION_11_12` |
| `di/CompositionRootDoubles` (`CompositionRootRig`) | runs `MIGRATION_11_12` |
| `data/local/ProgramMigrationPreservationTest` | chain extended, claims inverted (§7.2) |
| `data/local/ProgramSchemaTest` | entity census and chain-end assertions revised |
| `data/local/AdaptivePersistenceSchemaTest` | revised, not relaxed: the census now asserts the **retained + target** set and that the retired accessors are absent |

### 7.2 The four migration claims, measured on a real SQLite engine

| claim | test |
| --- | --- |
| retained rows survive value-for-value, and the track's identity is renamed | `everyRetainedRowSurvivesTheUpgradeUnchanged`, `theTrackRowIsRenamedAndNotRewritten` |
| the retired tables and their rows are gone, under no other name | `theRetiredTablesAndTheirRowsAreGone` |
| no legacy row became a target row | `noLegacyRowBecomesATargetRow` |
| the migrated schema is the schema Room declares at version 12 | `theMigratedSchemaIsTheSchemaRoomExpects`, `everyTargetColumnHasTheTypeAndNullabilityTheContractDeclares` |

---

## 8. Full Reset after P15

§16 says not to invent a contract, so the existing one — *"everything the program records, except nutrition
plans"* — was mapped onto the tables that exist now, order included:

```text
cleared   every Program the user created (cascading its revisions/days/exercises),
          every opportunity · attempt · snapshot · confirmed set · pause,
          the target adaptive state (family state · decisions · adjustments),
          the selection state, and the two retained daily tracks (posture, body weight)
kept      the built-in Standard Program's own DEFINITION (§12 — and the lifecycle's delete-fallback
          selects it), and the nutrition plans
order     app_state first (`selectedProgramId` is NO_ACTION, so a Program it names cannot be deleted),
          then the attempt graph leaf-first, then the adaptive rows, then slots/pauses/programs
```

`MaintenanceDao` holds every statement and names **no retired table** — its own exception-free status is
part of the P15 gate. `MaintenanceRepository` owns the order and the transaction; `AppContainer` builds it,
so the reset's all-or-nothing unit comes from the composition root like every other one.

The two controls that were *not* global — *Restart current cycle* and *Start revised program* — are gone
from Settings, with the strings that labelled them; the target architecture already provides their intent
through Edit / Copy → new Revision → lifecycle Start.

---

## 9. The `CustomProgram` surface: audited and retired

§11 asked whether `CustomProgramScreen` / `CustomProgramEditor` / `ProgramConfiguration` are the target
Program editor or a library/configuration feature that merely says "Program". The audit answered a third
thing: it is a **setting whose only reader was the editor that wrote it**, so it was retired rather than
kept.

The trail, from actual production references:

```text
before P15
  ActiveWorkoutConfiguration.beginSession  → read ProgramConfigurationRepository, captured a
                                             WorkoutConfigurationSnapshot                        [deleted in P15]
  MainViewModel.getWorkoutForDay           → passed that capture into AdaptiveWorkoutIntegration  [deleted in P15]
  AdaptiveWorkoutIntegration.generateWorkout → passed allowedExerciseIds to WorkoutGenerator    [deleted in P15]

after P15
  ProgramConfigurationRepository.load() / .apply() / .resetToDefault()  ← called only by CustomProgramEditor
  WorkoutConfigurationSnapshot                                          ← constructed by nothing in production
  WorkoutGenerator's `allowedExerciseIds` parameter                     ← supplied by no call site
```

So the stored selection could no longer constrain anything: the target Program plans from a revision, and
the retained posture/mobility routine is constrained by the user's *global* settings (equipment, focus
areas, disabled families), never by this set. §16 and the stage brief both say a user setting that cannot
affect anything is not preserved for compatibility's sake, so the whole surface went with its route:
the screen, the editor and its state, the repository, the configuration and snapshot models, the
validators, the Settings section, its 28 strings (all three locales) and its four test classes.

The one thing the deletion could **not** take with it was the metadata vocabulary. `TrainingDomain`,
`BodyRegion` and `ExerciseMetadata` were declared in the same file as the configuration's error and warning
types, and they are a **target dependency**: `domain/program/generated/GenerationRequest.kt` carries an
`ExerciseMetadata<E>`, and `data/model/Exercise.kt` maps the app's catalogue into it. The first deletion
pass took them, the compiler caught it immediately, and they were split into
`domain/adaptive/ExerciseMetadata.kt` — which is what let the dead surface go without a target dependency
following it.

### Left alone on purpose

`rewards_granted_days` / `SettingsManager.setRewardGranted` are now written and read by nothing (their
callers were the retired completion paths). The DataStore key was **not** touched: removing a persisted
preference is a data change, not a cleanup, and this stage had no reason to make one.

`WorkoutGenerator`'s `allowedExerciseIds` / `preferredExerciseIds` parameters are likewise left in place.
They are internal parameters of a retained class with defaults that every call site relies on
(`null` = unconstrained), not a user-facing setting, and removing them would be an edit to a target
dependency for tidiness alone.

## 10. Final adaptive architecture

```text
ProgramRevision
    └── program_family_progression_state   (per revision + family)
    └── program_adaptive_decision_record   (append-only; cleared only by Full Reset)
            └── adaptive_adjustment        (the change a decision applied, if any)
```

`ProgramAdaptiveRepository` is the only adaptive repository; the generic name `AdaptiveRepository` is
retired and asserted free. `ProgramAdaptiveEngine` / `ProgramAdaptivePolicy` /
`ProgramAggregateLoadGuard` / `ProgramAdaptiveSignals` / `ProgramProgressionRelation` are the only engine,
and nothing above the integration may name them.

### 10.1 The honest boundary, kept

With no persisted progression relation and no exercise→family classification, every production adaptive
pass ends in the engine's bounded input gap — no family is adapted, and **nothing is fabricated to make one
look adaptable**. The acceptance test asserts that outcome explicitly rather than engineering around it.

---

## 11. Remaining capability gaps (unchanged by this stage)

```text
Generated Planner       → GENERATION_UNAVAILABLE    the catalogue has no PUSH/PULL/LEGS-style focus metadata
Adaptive                → no applicable relation    no persisted progression ladder
Adaptive                → no family classification  no authoritative exercise→family map
Progress: PROGRAM_PR    → DeferredMeasure           no comparable progression context
Progress: FOCUS_DISTRIBUTION / FAMILY_DISTRIBUTION → DeferredMeasure
```

None of these was unblocked by inventing data: §10 of the brief forbids fabricating the ladder or the
classification to make the target system look complete, and P11/P12 recorded the same two artefacts.

---

## 12. Test classes retired with their subject

`docs/_p15-deleted-tests.txt` holds the full record — one entry per class, with *why* it became invalid and
which suite now owns the behaviour. Thirty-two classes were deleted; none was deleted because it was
inconvenient, and twelve of the surviving suites were **revised rather than relaxed**, each with the reason
at the assertion. Four of the inversions are worth naming here:

| suite | claim inverted |
| --- | --- |
| `SessionRuntimeArchitectureTest.noUiOrViewModelSourceReachesTheRuntimeYet` | *nothing* reaches the runtime → **exactly one state holder** calls it, and exactly one source constructs it |
| `ProgramAdaptiveArchitectureTest.theStageOneAdaptiveGenerationIsStillThereUntouchedAndStillItsOwn` | the Stage-1 sources must still exist → they must be **gone** |
| `CompositionRootArchitectureTest.theShippedStageOneConstructionSitesAreUnchangedAndClosed` | a closed list of 3 sites → **no** construction site |
| `ProgramAdaptiveRepositoryTest.theStageOneAdaptiveRepositoryIsStillTheOnlyClassWithThatName` | the generic name must be taken → it must be **free** |

---

## 13. Claim → test

| § | claim | test |
| --- | --- | --- |
| 1 | the target runtime is the production path | `ProgramTargetAcceptanceTest`, `ProgramLegacyRemovalGateTest.theTargetWorkoutRuntimeIsWiredAndReachable` |
| 1 | no production source launches the day-based runtime | `ProgramLegacyRemovalGateTest` (retired types + routes) |
| 2 | one workout identity: the opportunity | `ProgramsNavigationTest`, `ProgramLegacyRemovalGateTest` |
| 5 | no ViewModel → DAO for Program state | `CompositionRootArchitectureTest.noViewModelOrUiSourceReachesTheDatabaseOrTheProgramSystemGraph` |
| 7 | Back does not cancel; only Cancel cancels | `SessionRuntimeTest` (P8's contracts, untouched) |
| 8 | Progress comes from §21's layer | `ProgramProgressController`, `ProgressArchitectureTest` |
| 9 | the Stage-1 generation is retired | `ProgramLegacyRemovalGateTest`, `ProgramAdaptiveArchitectureTest` |
| 11 | the localization floor reflects the real UI | `ProgramsLocalizationTest` |
| 12 | no retired concept in production | `ProgramLegacyRemovalGateTest` |
| 12 | domain/runtime depend on no Room/Android | `ProgramLegacyRemovalGateTest.theDomainAndTheProgramUiDependOnNoRoomAndNoAndroid` |
| 13 | the whole lifecycle composes | `ProgramTargetAcceptanceTest.theWholeTargetLifecycleComposes` |
| 13 | the mutations are caught | `scripts/program-15-red-mutations.sh` |
| 5,6 | migration preserves the track's rows; the anchor is not lost | `ProgramMigrationPreservationTest`, `TrackCalendarTest` |

---

## 14. Verification

Every number below comes from a run whose JUnit XML timestamps were checked against the wall clock, so none
of them is a carried-over figure.

```text
pristine origin/main (6f56493)     270 classes / 2578 tests / 0F / 0E / 0S
this branch, §30 step 15          235 classes / 2024 tests / 0F / 0E / 0S
```

The census is smaller by 35 classes and 554 tests, and every one of them is itemised in
`docs/_p15-deleted-tests.txt`: the suites that tested the shipped program's runtime, the Stage-1 adaptive
generation, the legacy session's configuration freeze and the dead `CustomProgram` configuration surface.

### Build gates

`:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, `:app:compileReleaseKotlin`,
`:app:compileReleaseJavaWithJavac`, `:app:assembleDebug` — all green.

### `lintVitalRelease`, against the pristine P14 baseline

Run in a `git worktree` at `6f56493` and again on this branch, both `--offline`:

| finding | pristine `6f56493` | this branch |
| --- | --- | --- |
| `app/src/main/res/values/themes.xml:2` — `ResourceCycle` | present | present |
| `app/build.gradle.kts:25` — `ExpiredTargetSdkVersion` | present | present |
| **new in P15** | — | **none** |

Both runs report exactly those two errors and nothing else, so P15 introduced **no** lint finding and
removed none. The task still fails, as it already did, on the pre-existing pair.

### The migration, the gate and the acceptance scenario

| suite | result |
| --- | --- |
| `ProgramMigrationPreservationTest` (11 → 12, real SQLite) | 21 tests, 0F |
| `ProgramSchemaTest` | 47 tests, 0F |
| `AdaptivePersistenceSchemaTest` | 11 tests, 0F |
| `ProgramLegacyRemovalGateTest` | 6 tests, 0F |
| `ProgramTargetAcceptanceTest` | 2 tests, 0F |
| `TrackCalendarTest` | 9 tests, 0F |

### The RED mutation suite

`scripts/program-15-red-mutations.sh`, one run:

```text
control                                GREEN
10 mutations                           caught
0 mutations                            missed
every mutated source                   restored byte-identically (md5sum -c)
residual mutations in the tree         none
```

The suite is built to fail loudly rather than quietly pass: a mutation that does not change its source
aborts the run (a no-op mutation would read as a hole in the oracle), and the sources are re-checksummed
after the last mutation. It clears the project's KSP incremental cache before **each** invocation, because
that cache fails intermittently under many rapid recompiles of the same files and the failure looks exactly
like a source error.

### The final production grep

The gate's own scan — every `.kt` under `app/src/main`, comments stripped, whole-token matches,
`AppDatabase.kt`'s migration chain exempt *by location* — reports:

```text
0 survivors of 33 retired tokens
```

A raw `grep` without those three refinements still finds hits, and they are all one of three things:
comments that *document* the removal, the target types whose names contain a retired one
(`ProgramAdaptiveRepository`, `SetLogEntity`, `ProgramHomeUiState`), or the migration chain that drops and
renames the tables. One genuine stale reference was found this way and fixed: `di/Clock.kt`'s KDoc still
attributed "a program day" to the deleted `domain.usecase.ProgramCalendar`.
