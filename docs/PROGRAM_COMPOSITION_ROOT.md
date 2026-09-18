# Program System — composition root (PR 4)

Scope: the **composition root** of the multi-Program architecture — the `AppContainer` that owns the
app's one database and constructs every repository of §30 step 3 over it, with the clock, the id
generator and the transaction runner injected rather than read.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below
as **§N**). Implementation order: §30 step 4, *AppContainer / DI*. Companions:
`docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1, the domain), `docs/PROGRAM_ROOM_SCHEMA.md` (PR 2, the
schema), `docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md` (PR 2.1, the additive column) and
`docs/PROGRAM_DATA_ACCESS.md` (PR 3, the DAOs, mappers and repositories this stage wires).

This change is **wiring only**. There is no new domain behaviour, no lifecycle, no My Programs, no
editor, no scheduler, no session runtime, no progress calculation, no adaptive policy or engine, no
import/export and no legacy removal — §30 steps 5+ own all of that. The repositories constructed here
are exactly the objects PR 3 landed, with the collaborators that stage documented; nothing in this
change alters what any of them does.

---

## 1. The graph

```text
MonkFitnessApplication
    ↓  (container, once per process)
AppContainer                       ← this PR
    ├── database: AppDatabase      ← the app's one database (§26)
    ├── clock: Clock               ← injected, never read inside a repository
    ├── idGenerator: IdGenerator   ← injected, never read inside a repository
    ├── inTransaction              ← the database's own `withTransaction`
    ↓
    ├── ProgramRepository          ┐
    ├── ProgramPlanRepository      │
    ├── ProgramScheduleRepository  │ §30 step 3, unchanged
    ├── WorkoutSessionRepository   │
    ├── ProgramProgressRepository  │
    ├── AppStateRepository         │
    ├── ProgramAdaptiveRepository  ┘ (target adaptive tables)
    └── AdaptiveRepository           (shipped Stage-1 adaptive tables)
    ↓
ViewModels
```

| file | responsibility |
| --- | --- |
| `di/AppContainer.kt` | the composition root: the one database, the DAOs taken from it once, the eight repositories, the two factories (`create(context)`, the constructor) |
| `di/Clock.kt` | the clock port and its production implementation (`Clock.system()`) |
| `di/IdGenerator.kt` | the id port and its production implementation (`IdGenerator.random()`) |
| `MonkFitnessApplication.kt` | holds the container: a stored `val`, created on first use |
| `viewmodel/MainViewModel.kt` | one line changed: the shared database comes from the container instead of being acquired |

## 2. What the composition root is, and what it is not

`AppContainer` is §26's composition root: the single place that decides **which** database a
repository runs on, **which** clock stamps a row, **what** mints a new identity and **which**
transaction an atomic operation runs in. It is the only production file that acquires the database,
the only one that constructs a Program System repository and the only one that takes a target DAO
from the database — each of those is asserted against the sources.

It is **not** a service locator. It is a value the application holds; its repositories are ordinary
constructor arguments handed to their consumers, and no consumer asks a global for a dependency. It
is also **not** a decision maker: the class declares no operation at all (asserted by reflection), and
its code contains no policy, scheduler, generator, calendar, clock read or lifecycle vocabulary
(asserted by a token scan). Wiring a dependency is not deciding one (§33).

## 3. The single database

§26: *"Database instance is created once and shared"*.

* `AppContainer.create(context)` asks `AppDatabase.getDatabase` — the process-wide singleton, still
  `INSTANCE ?: synchronized(this)` around one `Room.databaseBuilder` — for the database exactly once.
* `MonkFitnessApplication.container` is a **stored** `by lazy` value, so the process has one container,
  one database and one place where the clock, the generator and the transaction runner are decided.
  It is created on first use rather than in `onCreate`, because acquiring the database is the one thing
  this stage must not move: the build that read it from the view model acquired it when a view model
  was first constructed, and the container preserves that moment.
* Every DAO the graph needs is taken from that instance **once** and shared by every repository that
  needs it (`AppContainer.ProgramDaos`), so "how many things in this graph read `program`" is one.

The counter-example the tests use is a second database of the same schema that the container is never
handed: every table in it stays empty while the graph is exercised end to end.

## 4. The ports: `Clock` and ID generation

§26: *"`Clock` and ID generation are injectable"*, *"deterministic generation does not use
uncontrolled `Random`"*.

Both are ports with production implementations:

```kotlin
fun interface Clock { fun now(): Instant }          // Clock.system() → Instant.now()
fun interface IdGenerator { fun newId(): String }   // IdGenerator.random() → UUID.randomUUID()
```

* **The clock reaches exactly one consumer**: `ProgramAdaptiveRepository`, whose `now` parameter is
  `{ clock.now() }` — read at each stamp, so a clock that moves is a clock that moves. It is the only
  production file in the Program System that reads the device's time (asserted by a token scan over
  `di/`, `data/mapper/` and the `Program*` sources). Nothing else in this stage has a timestamp to take.
* **The id generator has no consumer in this stage.** §30 step 5's creation path is the first one, and
  it is the one that will wrap the body in the typed id it is creating (`ProgramId(idGenerator.newId())`)
  — which is why the port returns a `String` body rather than one method per id type (a second identity
  vocabulary beside the typed ids of `domain/common`). It is wired now because the container is where a
  runtime's identity source belongs, and because "a test can drive the graph deterministically" is a
  property of the composition root rather than of the first caller.

Both are also arguments of `create(context, clock, idGenerator)`, so a caller that owns its own time or
its own identity sequence — a test, or a future import that must preserve the ids it was handed —
composes the same graph with its own values.

## 5. The two persistence generations

`AdaptiveRepository` (shipped, Stage-1: `family_progression_state`, `adaptive_decision_record`, keyed by
the legacy revision integer) and `ProgramAdaptiveRepository` (target:
`program_family_progression_state`, `program_adaptive_decision_record`, `adaptive_adjustment`) are both
wired **by this container, over the same database, each on its own tables**.

That is deliberate, and it is the one addition beyond "construct the target repositories": the boundary
between the two generations is a composition-root responsibility, and a boundary that is asserted only
in prose is a boundary nobody checks. With both adapters in one graph, "a write through one generation
is invisible to the other" is measured on the engine's own tables — including that the shipped adapter
keeps the caller's stamp while the target one takes the container's clock.

Nothing legacy changes: `SessionAdaptivePlanReader.of` and `AdaptiveSessionDecisionRecorder.of` keep
their own `AdaptiveRepository` instances exactly as they are (the same stateless adapter over the same
DAOs — not a second source of state), `ProgramMaintenance`'s Full-Reset table set is untouched, and the
four construction sites of the shipped adapter are pinned by a test so a fifth cannot appear unnoticed.

## 6. The transaction runner

Every repository takes `inTransaction` as a constructor argument (§30 step 3) and the composition root
is its supplier. The container's runner defaults to the **database's own** `withTransaction`, which is
the production behaviour, and no production call overrides it (asserted).

The parameter exists because a `RoomDatabase` transaction needs a database Room has actually opened, and
this repository's unit tests run without a device: they build the same container with the same DAO
implementations over a real SQLite engine and supply the engine's own `BEGIN`/`COMMIT`/`ROLLBACK` — the
same substitution PR 3's rig already makes. No production path is aware of it.

## 7. What the tests prove

`AppContainerTest` (behavioural, on a real SQLite engine) and `CompositionRootArchitectureTest` (the
sources themselves, comments stripped).

| test | proves |
| --- | --- |
| `everyRepositoryOfTheProgramSystemIsConstructedByTheContainer` | the container's surface is exactly the seven §30-step-3 repositories plus both adaptive generations, each a live distinct object (reflection census) |
| `theContainerExposesRepositoriesAndTheSharedDatabaseAndNoDao` | the public surface hands out repositories and the shared database — never a DAO (§33), and declares no operation at all |
| `everyRepositoryOfTheGraphReadsAndWritesTheOneDatabaseTheContainerWasGiven` | a Program created through `ProgramRepository` is readable through `ProgramPlanRepository`; a foreign key (`app_state.selectedProgramId`) resolves across repositories; the session path (start → confirm set → finish) is readable through `ProgramProgressRepository`; and a second database of the same schema, never handed to the container, stays empty |
| `eachDaoIsTakenFromTheDatabaseOnceAndShared` | exactly one accessor call per table (17), nothing else taken from the database, and the shipped progress DAO never asked |
| `anAtomicOperationThroughTheContainerRollsBackAsAWhole` | a planted mid-graph failure leaves no Program, revision, day, element or slot — the rollback is SQLite's, and the same container then writes the whole graph |
| `theInjectedClockIsWhatTheAdaptiveRepositoryStampsItsRowsWith` | the stamp is the injected instant (returned *and* stored), and a moved clock changes the next stamp |
| `theInjectedIdGeneratorIsTheOnlyIdentityTheGraphMintsThrough` | the container exposes the injected generator, a caller-minted id is the id in storage, and nothing in the graph minted one of its own |
| `theTwoAdaptiveGenerationsAreWiredToTheirOwnTables` | each generation writes only its own tables; the shipped rows are byte-identical after a target write; the stamps come from different sources |
| `onlyTheCompositionRootAcquiresTheAppsDatabase` | one `AppDatabase.getDatabase` call site, one `Room.databaseBuilder`, one `INSTANCE` |
| `theDatabaseIsStillCreatedOnceAndSharedByItsOwnSingleton` | the singleton shape is intact and the companion offers exactly one factory, taking a `Context` |
| `theApplicationHoldsExactlyOneCompositionRoot` | the container is created in one place, stored (not a `get()` accessor), and the manifest names that application |
| `onlyTheCompositionRootConstructsAProgramSystemRepository` / `onlyTheCompositionRootTakesDaosFromTheDatabase` | every target repository and every target DAO is constructed/acquired in `di/AppContainer.kt` and nowhere else |
| `theShippedStageOneConstructionSitesAreUnchangedAndClosed` | the shipped adapter's three construction sites are unchanged, and the view model's legacy constructions are pinned |
| `noViewModelOrUiSourceReachesTheDatabaseOrTheProgramSystemGraph` | no `ui/` or `viewmodel/` source names the database, a DAO, a target repository or the container; the one remaining reach (`container.database`) is the single line §30 step 15 deletes |
| `theCompositionRootDecidesNothing` / `theTransactionRunnerIsTheDatabasesOwnAndOnlyThePortsReadTheDevice` | no policy/scheduler/generator/calendar/clock vocabulary and no clock or random read outside the two port files; the runner is the database's own |
| `theCompositionPackageHoldsTheCompositionRootAndItsTwoPorts` | the package is the root and the two ports, and nothing else |

The counts, the RED evidence (one deliberate break per rule, with the measured failures) and the exact
gate output are in the PR body.

## 8. What changed in existing files

| file | change |
| --- | --- |
| `MonkFitnessApplication.kt` | added `val container: AppContainer by lazy { AppContainer.create(this) }`. `onCreate` is unchanged. |
| `viewmodel/MainViewModel.kt` | `val db = AppDatabase.getDatabase(application)` → `val db = (application as MonkFitnessApplication).container.database`. The rest of the `init` block — `WorkoutRepository`, `SessionAdaptivePlanReader.of`, `AdaptiveSessionDecisionRecorder.of`, `SettingsManager` — is untouched. |

The view-model change is the one §30 step 4 permits, and it is a **construction-point replacement with no
behaviour change**: the container holds `AppDatabase.getDatabase(application)`'s own result, so the
instance handed to `WorkoutRepository` is the same instance as before. What it buys is the invariant
itself — a view model no longer creates a `RoomDatabase`, so *"ViewModels never create DB/repositories"*
(§26) is now true and testable rather than aspirational. The legacy repository constructions on that path
remain until §30 step 15 retires the Stage-1 generation.

No file of PR 1, PR 2, PR 2.1 or PR 3 is modified.

## 9. Decisions for the owner

### 9.1 Where the two ports live

`Clock` and `IdGenerator` are in `di/`, beside the composition root, not in `domain/common`. Reasons: they
are named in §26's DI section rather than in the domain foundation; the production implementations read the
device's time and a random source, which is exactly what the domain's own purity rules keep out; and the
consumer of a port is the object that injects it. The alternative reading — that a port belongs where the
*future* consumer lives, i.e. next to the typed ids in `domain/common`, so that a later `domain/usecase`
never imports the composition layer — is defensible, and moving them is a two-file move plus the imports in
this stage's own files. The owner decides if §30 step 5's creation path wants the ports on the domain side.

### 9.2 The transaction runner is a defaulted constructor argument

`AppContainer`'s fourth parameter exists so the composition root can be exercised without a device (see §6).
The production default is the database's own `withTransaction` and a test asserts that no production call
overrides it. The alternative — a `RoomDatabase` subclass in the test source set that overrides
`beginTransaction`/`setTransactionSuccessful`/`endTransaction`/`isOpen`/`getTransactionExecutor` so the real
`withTransaction` runs on the engine — was considered and rejected as coupling the tests to six Room
internals for one line of production shape.

### 9.3 The container holds the shipped `AdaptiveRepository` too

See §5. The alternative is to wire the target generation only and leave the shipped adapter to its two
`of(...)` factories; that keeps the container strictly target-only, at the price of having no artefact in
which "the two generations do not see each other" can be measured. Recorded rather than silently resolved.

### 9.4 `IdGenerator` has no consumer in this stage

See §4. It is wired and tested because §26 requires the dependency to be injectable and because the
verification of this stage asks for deterministic identity to be injectable; the first real consumer is §30
step 5. If the owner prefers no unconsumed port in the graph, the drop is one file plus two constructor
parameters, and step 5 reintroduces it in the same place.

### 9.5 `java.time` on `minSdk = 24`

`Clock.system()` calls `Instant.now()`. Production sources already call `LocalDate.now()` (and
`ProgramCalendar` uses `java.time` throughout) with no core-library desugaring, so this adds no exposure
the app did not already carry — recorded because PR 1 raised the same point for the domain types.

## 10. Deferred to later stages

* Program lifecycle behaviour, selection fallback, the deletability question and My Programs (§30 step 5) —
  the container exposes `appStateRepository` and `programRepository` and decides nothing about either.
* The manual editor and its draft persistence (§30 step 6).
* The scheduler's slot generation, missed detection, supersession and horizon extension (§30 step 7).
* The session runtime, including the composition of `Complete Workout + Adaptive` in one transaction
  (§30 step 8) — the two repositories it will compose are wired; the composition is not.
* Progress/History computations and any aggregation view (§30 step 9).
* The generator's reconciliation, pin/override preservation and the Focus Planner (§30 step 10).
* The adaptive engine, its integration, the policy and the load guard (§30 steps 11–12).
* Import/export/Share and their DTOs (§30 step 13).
* Settings/navigation cleanup (§30 step 14) and the retirement of the Stage-1 tables, the collapse of the
  two adaptive repository names and the deletion of the view model's database reach (§30 step 15).
