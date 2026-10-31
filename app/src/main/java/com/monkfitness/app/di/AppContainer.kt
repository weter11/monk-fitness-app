package com.monkfitness.app.di

import android.content.Context
import androidx.room.withTransaction
import com.monkfitness.app.data.local.AdaptiveAdjustmentDao
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.AppStateDao
import com.monkfitness.app.data.local.ProgramAdaptiveDecisionDao
import com.monkfitness.app.data.local.ProgramDao
import com.monkfitness.app.data.local.ProgramDayDao
import com.monkfitness.app.data.local.ProgramExerciseDao
import com.monkfitness.app.data.local.ProgramFamilyProgressionStateDao
import com.monkfitness.app.data.local.ProgramPauseDao
import com.monkfitness.app.data.local.ProgramRevisionDao
import com.monkfitness.app.data.local.ProgramSetLogDao
import com.monkfitness.app.data.local.ProgramTargetOccurrenceDao
import com.monkfitness.app.data.local.ProgramTargetScheduleSourceDao
import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.local.SessionExerciseDao
import com.monkfitness.app.data.local.SessionSnapshotDao
import com.monkfitness.app.data.local.SessionSnapshotExerciseDao
import com.monkfitness.app.data.local.WorkoutSessionDao
import com.monkfitness.app.data.repository.AppStateRepository
import com.monkfitness.app.data.repository.MaintenanceRepository
import com.monkfitness.app.data.repository.ProgramAdaptiveRepository
import com.monkfitness.app.data.repository.ProgramPlanRepository
import com.monkfitness.app.data.repository.ProgramProgressRepository
import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.TargetScheduleOccurrenceRepository
import com.monkfitness.app.data.repository.TargetScheduleSourceRepository
import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.bootstrap.StandardProgramBootstrap
import com.monkfitness.app.domain.adaptive.integration.NoDeclaredProgression
import com.monkfitness.app.data.local.ProgressionRelationVariantDao
import com.monkfitness.app.data.repository.ProgressionRelationRepository
import com.monkfitness.app.domain.usecase.StoredProgressionRelationProvider
import com.monkfitness.app.bootstrap.BuiltInProgressionCatalogueBootstrap
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.usecase.SHIPPED_EXERCISE_CATALOGUE
import com.monkfitness.app.domain.usecase.CatalogExerciseFamilyClassification
import com.monkfitness.app.domain.usecase.CatalogExerciseRangeSource
import com.monkfitness.app.domain.usecase.ProgramAdaptiveIntegration
import com.monkfitness.app.domain.usecase.ProgramEditorService
import com.monkfitness.app.domain.usecase.ProgramExerciseLibrary
import com.monkfitness.app.domain.usecase.ProgramExportService
import com.monkfitness.app.domain.usecase.GenerationSessionHistory
import com.monkfitness.app.domain.usecase.ProgramGenerationService
import com.monkfitness.app.domain.usecase.ProgramHistoryGenerationContext
import com.monkfitness.app.domain.usecase.ProductionFocusClassification
import com.monkfitness.app.domain.usecase.ProgramImportService
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.usecase.ProgramScheduler
import com.monkfitness.app.domain.usecase.ProgramSaveService
import com.monkfitness.app.domain.usecase.TargetExistingOccurrenceReader
import com.monkfitness.app.domain.usecase.TargetOccurrenceExecutionReader
import com.monkfitness.app.domain.usecase.TargetScheduleApplicationService
import com.monkfitness.app.domain.usecase.TargetScheduleInputAdapter
import com.monkfitness.app.domain.usecase.TargetScheduleOrchestrator
import com.monkfitness.app.domain.usecase.TargetScheduleProductionConsumer
import com.monkfitness.app.domain.usecase.ProgramStartService
import com.monkfitness.app.domain.usecase.TargetScheduleSlotPersister
import com.monkfitness.app.domain.usecase.TargetScheduleSourceBridge
import com.monkfitness.app.domain.usecase.SessionRuntime
import com.monkfitness.app.domain.program.StandardProgram
import java.time.ZoneId

/**
 * The Program System's composition root: one object that owns the app's **one** database and constructs
 * every repository and service over it. It is the only place a database is acquired, and the only place
 * the clock, the id generator and the transaction runner enter the graph, so "which database does this
 * repository use" and "what time does this row get stamped with" are answered by reading one file.
 *
 * It is a **composition root, not a service locator**: nothing reaches into it statically, its
 * collaborators are ordinary constructor arguments handed to their consumers, and a ViewModel receives
 * what it needs rather than coming here to fetch it.
 *
 * It decides **nothing** about the program — no lifecycle transition, no revision rule, no scheduling, no
 * generation, no adaptive policy — because wiring a dependency is not a decision about behaviour.
 *
 * [database] is the process-wide `AppDatabase`, asked for exactly once per container, and every DAO is
 * taken from *that* instance once per table ([ProgramDaos]), so the repositories are windows onto one open
 * database rather than connections that happen to point at the same file.
 *
 * The two adaptive generations stay separate: [adaptiveRepository] (the shipped Stage-1 adapter, keyed by
 * the legacy revision integer) never reads a target table and [programAdaptiveRepository] never reads a
 * Stage-1 one, because a target repository that silently fell back to a legacy table would make "the
 * legacy rows are byte-identical" unprovable.
 *
 * @param database the app's one database; a test may pass its own so the root can run without a device.
 * @param zone the calendar a *day* is read in, handed to **both** the integration (which chooses the target
 *   opportunity against the decision's own day) and the session runtime (which checks that choice against
 *   the decision's own moment) — one value keeps the two sides from disagreeing about which day a decision
 *   belongs to.
 * @param inTransaction defaults to the database's own `withTransaction`, which is the wired production
 *   behaviour; the parameter exists because a `RoomDatabase` transaction needs a database Room has actually
 *   opened, and the unit tests run without a device.
 */
class AppContainer(
    val database: AppDatabase,
    val clock: Clock = Clock.system(),
    val idGenerator: IdGenerator = IdGenerator.random(),
    val zone: ZoneId = ZoneId.systemDefault(),
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { block ->
        database.withTransaction { block() }
    }
) {

    /** The database's DAOs, taken once. See [ProgramDaos]. */
    private val daos = ProgramDaos(database)

    /**
     * The Program itself: creation (Program + first revision + plan + initial slots in one transaction)
     * and cascade delete. No lifecycle transition, no selection and no "may this be deleted" — those are
     * the lifecycle service's decisions.
     */
    val programRepository: ProgramRepository = ProgramRepository(
        daos.program, daos.revision, daos.day, daos.exercise, daos.slot, inTransaction
    )

    /**
     * A revision's plan: reading it, and saving a new one as new rows with the `currentRevisionId`
     * pointer moved in the same transaction. Future-slot reconciliation is the Scheduler's and is not
     * wired here.
     */
    val programPlanRepository: ProgramPlanRepository = ProgramPlanRepository(
        daos.program, daos.revision, daos.day, daos.exercise, inTransaction
    )

    /**
     * Slots and pause intervals: what the plan was scheduled as, read and recorded. No date is chosen
     * here — `addSlots` stores what the Scheduler decided.
     */
    val programScheduleRepository: ProgramScheduleRepository = ProgramScheduleRepository(
        daos.slot, daos.session, daos.pause
    )

    /**
     * Sessions: starting one with the presentation it captured, reading it back from that capture alone,
     * appending a confirmed set, and finishing. The Complete Workout + Adaptive composition is the session
     * runtime's and is not built here.
     */
    val workoutSessionRepository: WorkoutSessionRepository = WorkoutSessionRepository(
        daos.session, daos.snapshot, daos.snapshotExercise, daos.sessionExercise, daos.setLog,
        daos.slot, inTransaction
    )

    /**
     * The row-level facts Progress reads: counts and ordered identities, with no derived measure.
     * Computing frequency, volume, focus, PRs or streaks is the Progress service's and is deliberately
     * absent.
     */
    val programProgressRepository: ProgramProgressRepository = ProgramProgressRepository(
        daos.slot, daos.session, daos.setLog
    )

    /**
     * The single global application-state row (`app_state`): which Program is selected, which is next, and
     * whether the next one starts automatically. Reading it invents no fallback and writing it performs no
     * transition — the lifecycle rules are the lifecycle service's.
     */
    val appStateRepository: AppStateRepository = AppStateRepository(daos.appState)

    /**
     * The **target** adaptive persistence: family progression state, the decision trail and the
     * adjustments, on the target tables only. It evaluates no policy and calculates no signal.
     *
     * The clock reaches it here and nowhere else: `now = { clock.now() }` reads the container's injected
     * clock at each stamp, so a runtime with a moved clock — a test, or a future caller with its own —
     * stamps the rows it writes with the time it supplied.
     */
    val programAdaptiveRepository: ProgramAdaptiveRepository = ProgramAdaptiveRepository(
        daos.familyState, daos.decision, daos.adjustment, now = { clock.now() }, inTransaction = inTransaction
    )

    /**
     * The lifecycle and selection decisions the persistence layer must not make: which transitions are
     * legal, that selection is one global fact, that deleting the selected Program falls back to the
     * Standard Program *after* moving the selection, that the Standard Program is protected from edit and
     * deletion, and that an `IN_PROGRESS` session blocks a delete.
     *
     * `clock` and `idGenerator` are the container's ports because `actualStartDate` is a fact and identity
     * is minted, not derived; the Standard Program's id is [StandardProgram.programId], read by the
     * fallback, the guard and the seeding stage. The shared runner makes Delete Program → selection move +
     * cascade one unit of the database's own.
     */
    val programLifecycleService: ProgramLifecycleService = ProgramLifecycleService(
        programRepository = programRepository,
        scheduleRepository = programScheduleRepository,
        sessionRepository = workoutSessionRepository,
        appStateRepository = appStateRepository,
        planRepository = programPlanRepository,
        clock = clock,
        idGenerator = idGenerator,
        standardProgramId = StandardProgram.programId,
        inTransaction = inTransaction
    )

    /**
     * The **Manual Editor**: draft-first editing and the revision rule — it opens a draft, validates it,
     * reviews what saving would do, and decides whether a save warrants a new immutable revision, creates a
     * Program, or writes nothing. The built-in Program is refused for an in-place edit through the same
     * guard the lifecycle carries, so copy-before-edit has one implementation.
     *
     * There is **no** schedule repository and no exercise-library port, and that absence is the guarantee:
     * the editor cannot reconcile scheduler slots or touch exercise metadata.
     */
    val programEditorService: ProgramEditorService = ProgramEditorService(
        programRepository = programRepository,
        planRepository = programPlanRepository,
        clock = clock,
        idGenerator = idGenerator,
        inTransaction = inTransaction
    )

    /**
     * The Scheduler: which dates a saved revision trains on over a bounded window, which of them have no
     * opportunity yet, which existing opportunities the revision no longer presents, and which passed. It
     * turns a revision into **slots** and nothing else.
     *
     * There is **no** session repository, so a Session cannot be created here; no plan *write*, so an
     * immutable revision cannot be rewritten; no adaptive, generator, library or progress port, so the pass
     * cannot consult performance, produce a plan or derive a statistic. The `zone` is the calendar the two
     * dates in a pass are read in — the clock supplies an instant while a slot is planned for a date. The
     * shared runner makes one pass one unit.
     */
    val programScheduler: ProgramScheduler = ProgramScheduler(
        programRepository = programRepository,
        planRepository = programPlanRepository,
        scheduleRepository = programScheduleRepository,
        clock = clock,
        idGenerator = idGenerator,
        zone = zone,
        inTransaction = inTransaction
    )

    /**
     * The target occurrence's own semantic persistence: the planned date and the ordered rule/workout
     * components, stored beside the slot rather than reconstructed from it.
     *
     * It is a **separate** repository from [programScheduleRepository] on purpose. A slot row has no
     * component columns, so a repository that owned both would be one whose read-back sometimes answers and
     * sometimes has to invent — and the inventing would be invisible at the call site.
     */
    val targetScheduleOccurrenceRepository: TargetScheduleOccurrenceRepository =
        TargetScheduleOccurrenceRepository(daos.targetOccurrence)

    /**
     * A revision's **explicit** target schedule source: the target rules it states and the explicit
     * `workoutId -> ProgramDayId` bindings beside them.
     *
     * It is a repository of its own, beside the legacy contour and never inside it, because it is the one
     * node that can answer "what target semantics does this revision actually state?" — and the answer is
     * frequently *nothing*. A repository that also owned the legacy schedule would have to either conflate
     * the two vocabularies or invent a rule per legacy schedule, and both are the guessing this source
     * exists to remove.
     *
     * Its transaction runner is the container's shared one, because a revision's rules and its bindings are
     * one immutable unit: a source with rules but no bindings cannot present an occurrence, so the two
     * halves land together or not at all.
     */
    val targetScheduleSourceRepository: TargetScheduleSourceRepository = TargetScheduleSourceRepository(
        sourceDao = daos.targetScheduleSource,
        programDayDao = daos.day,
        inTransaction = inTransaction
    )

    /**
     * The explicit bridge: a revision's persisted target source, and nothing else.
     *
     * It is wired beside the target contour and consumed by nobody yet. The orchestrator below stays a
     * separately callable contour, the legacy Scheduler above stays production scheduling's owner, and this
     * bridge's only job is to be *available* to the future caller that will use it once target semantics
     * have an authoring path of their own.
     */
    val targetScheduleSourceBridge: TargetScheduleSourceBridge = TargetScheduleSourceBridge(
        sourceRepository = targetScheduleSourceRepository
    )

    /**
     * Target-stage persistence bridge; it is wired beside, never into, the legacy Scheduler.
     *
     * The transaction runner is the container's shared one, so the target slot and the target occurrence it
     * presents are committed as one unit: a pass that failed between them would leave a slot pointing at a
     * semantic record that does not exist.
     */
    val targetScheduleSlotPersister: TargetScheduleSlotPersister = TargetScheduleSlotPersister(
        scheduleRepository = programScheduleRepository,
        occurrenceRepository = targetScheduleOccurrenceRepository,
        idGenerator = idGenerator,
        inTransaction = inTransaction
    )

    /** Application boundary that presents and persists a ready target schedule decision. */
    val targetScheduleApplicationService: TargetScheduleApplicationService =
        TargetScheduleApplicationService(
            slotPersister = targetScheduleSlotPersister
        )

    /** Orchestration over planner, policy and the application boundary. */
    val targetScheduleOrchestrator: TargetScheduleOrchestrator = TargetScheduleOrchestrator(
        applicationService = targetScheduleApplicationService
    )

    /**
     * Target input boundary: explicit caller definitions become an orchestration request.
     *
     * It is wired with no collaborator at all, which is the point: the adapter is a value conversion, so a
     * repository, a clock or an identity generator handed to it would be a collaborator it has no use for
     * and must not be able to consult.
     */
    val targetScheduleInputAdapter: TargetScheduleInputAdapter = TargetScheduleInputAdapter()

    /**
     * The stored-execution read-back, and the bridge from it to the target scheduling input.
     *
     * Both are wired here because the consumer below is their first production caller. Neither is reachable
     * from the legacy contour: the read-back reads the Program's own stored target occurrences and the
     * attempts that reference them, and the bridge turns that one record into the single value the target
     * planning rules consume.
     */
    val targetOccurrenceExecutionReader: TargetOccurrenceExecutionReader = TargetOccurrenceExecutionReader(
        occurrenceRepository = targetScheduleOccurrenceRepository,
        scheduleRepository = programScheduleRepository,
        sessionRepository = workoutSessionRepository
    )

    val targetExistingOccurrenceReader: TargetExistingOccurrenceReader = TargetExistingOccurrenceReader(
        executionReader = targetOccurrenceExecutionReader
    )

    /**
     * The first production consumer of the target contour, and a **separately callable** one: a Program plus
     * an explicit run context become one real target pass — current revision, explicit source through the
     * bridge, stored occurrences through the execution bridge, the input adapter, then the orchestrator
     * plans, classifies, presents and persists.
     *
     * Nothing above this node calls it, which is the point: the legacy planner still owns every slot the
     * application trains from, and the legacy contour is not rewired to reach a target pass. It holds no
     * identity generator, no clock, no second planning boundary and no UI state — the five values storage
     * does not state arrive per call.
     */
    val targetScheduleProductionConsumer: TargetScheduleProductionConsumer =
        TargetScheduleProductionConsumer(
            programRepository = programRepository,
            planRepository = programPlanRepository,
            sourceBridge = targetScheduleSourceBridge,
            occurrenceRepository = targetScheduleOccurrenceRepository,
            existingOccurrenceReader = targetExistingOccurrenceReader,
            inputAdapter = targetScheduleInputAdapter,
            orchestrator = targetScheduleOrchestrator
        )

    /**
     * The app-owned **progression relation catalogue**, over the one table that stores each family's
     * declared ladder.
     *
     * It is a **graph node rather than an inline construction** for the same reason every other repository
     * here is: the container decides which objects the adaptive path receives without deciding anything
     * about them. It is the sole persistence collaborator of the stored provider below, and the same
     * repository the bootstrap writes through.
     */
    val progressionRelationRepository: ProgressionRelationRepository = ProgressionRelationRepository(
        daos.progressionRelationVariant, inTransaction
    )

    /**
     * The production `ProgressionRelationProvider`: the stored catalogue, read.
     *
     * It holds exactly one collaborator and reads nothing else — not the authored definitions, not the
     * shipped exercise catalogue, not adaptive history — because the persisted rows, not a static constant,
     * are the authoritative statement of what this app's families declare.
     */
    val storedProgressionRelationProvider: StoredProgressionRelationProvider =
        StoredProgressionRelationProvider(progressionRelationRepository)

    /**
     * The built-in catalogue bootstrap — the one boundary that puts the authored ladders into storage, from
     * `Application.onCreate`.
     *
     * It is a separate node rather than something the provider does on first read, so no consumer can
     * observe the catalogue before it is complete, and a deleted ladder is never silently restored by a
     * read or an adaptive evaluation.
     */
    val builtInProgressionCatalogueBootstrap: BuiltInProgressionCatalogueBootstrap =
        BuiltInProgressionCatalogueBootstrap(progressionRelationRepository)

    /** The idempotent production bootstrap for the product-owned Standard Program. */
    val standardProgramBootstrap: StandardProgramBootstrap = StandardProgramBootstrap(
        programRepository = programRepository,
        scheduler = programScheduler,
        clock = clock,
        idGenerator = idGenerator,
        zone = zone
    )

    /**
     * The one controlled production invocation of the target contour, and the application operation the UI is
     * given for *Start*: it performs `startProgram` and, only if that succeeded, builds the run context the
     * target stages need and runs the consumer above — one lifecycle point, five stated values, no second
     * scheduler. The legacy planner still owns every slot the application trains from, so this is a first
     * controlled invocation, not a cutover.
     *
     * The zone is the composition root's own calendar, so "the day a Program started" and "the day a pause
     * covered" cannot be read in two different calendars.
     */
    val programStartService: ProgramStartService = ProgramStartService(
        lifecycle = programLifecycleService,
        consumer = targetScheduleProductionConsumer,
        scheduleRepository = programScheduleRepository,
        zone = zone
    )

    /**
     * The **Save** orchestration: the composition lines whose writes must land together —
     * `Create / Copy → Program + Revision + plan + initial Slots` and
     * `Save Editor → new Revision + future-slot reconciliation`. The editor alone cannot hold either (it may
     * not touch a slot or a date), the Scheduler alone plans only what is stored, and a screen assembling
     * the two would be the UI deciding when a scheduling pass runs.
     *
     * `targetSourceRepository` writes a stated authoring against the revision this save creates, inside the
     * same transaction; none stated means no row and a typed `Missing`. `clock` and `zone` supply *today*,
     * only when a creation request names no exact start date. There is **no** lifecycle service here, so a
     * creation cannot move a selection, no plan repository (the editor holds the revision rule), no DAO and
     * no session or progress port. The shared runner means a failure at any leg leaves no partial Program,
     * no half-applied save and no orphaned target source.
     */
    val programSaveService: ProgramSaveService = ProgramSaveService(
        editor = programEditorService,
        programRepository = programRepository,
        scheduler = programScheduler,
        targetSourceRepository = targetScheduleSourceRepository,
        clock = clock,
        zone = zone,
        inTransaction = inTransaction
    )

    /**
     * The **export** half: turning a stored Program into the file a share carries. Its single collaborator
     * returns a Program together with the revision its pointer names, and the absences are the guarantees —
     * **no** session, progress or adaptive repository, no clock and no id generator, so an exported file
     * cannot contain a session, a set, a statistic, a streak, a family state, a decision or an adjustment,
     * not because this stage remembers not to write them but because it holds nothing that could produce
     * one. The document type has no field for any of them either, so both halves of the prohibition are
     * structural.
     */
    val programExportService: ProgramExportService = ProgramExportService(
        programRepository = programRepository
    )

    /**
     * The **import** half: the whole path from a shared file to a new Program — decoding, parsing, the format
     * version, the schema, the exerciseId boundary, semantic validation, the Import Draft, and the save that
     * writes `Program + Revision + ProgramDays + ProgramExercises + initial slots` as **one transaction**.
     *
     * `programRepository` is the creation primitive (the Program-owned graph or nothing), `scheduler` makes
     * the imported revision's initial opportunities its decision, `lifecycleService` is the only path by which
     * the import can become selected, `ProgramExerciseLibrary` is the exerciseId boundary asked per referenced
     * id, `clock` and `idGenerator` supply every timestamp and identity of the new graph, and `zone` is the
     * calendar its start date is read in. There is **no** `ProgramPlanRepository` (an import creates its first
     * revision rather than saving one onto an existing Program), no session, adaptive or progress reader, and
     * no DAO: it reaches storage through the owners above and nothing else.
     */
    val programImportService: ProgramImportService = ProgramImportService(
        programRepository = programRepository,
        scheduler = programScheduler,
        lifecycleService = programLifecycleService,
        exerciseLibrary = ProgramExerciseLibrary(),
        clock = clock,
        idGenerator = idGenerator,
        zone = zone,
        inTransaction = inTransaction
    )

    /**
     * The Session runtime: the five operations of one attempt at one opportunity — starting a workout with
     * the complete immutable presentation it was started under, reading it back from that capture alone,
     * appending a confirmed set, cancelling it, and completing it together with the adaptive decision the
     * adaptive stage hands over, which is one unit of work composed here.
     *
     * There is **no `ProgramRepository`**: the Program is bound by the revision the opportunity names, and
     * whether a Program is paused, archived or completed is the lifecycle's decision — so "no lifecycle
     * policy beyond the session operation" is the absence of the dependency, not a rule the code remembers.
     * No generator, policy, signal calculator, progress reader or calendar either, and no second way to
     * write a session row, so the occupancy rule cannot be bypassed from outside. The shared runner is what
     * makes the completion one unit of the database's own rather than three adjacent writes.
     */
    val sessionRuntime: SessionRuntime = SessionRuntime(
        planRepository = programPlanRepository,
        scheduleRepository = programScheduleRepository,
        sessionRepository = workoutSessionRepository,
        adaptiveRepository = programAdaptiveRepository,
        clock = clock,
        idGenerator = idGenerator,
        zone = zone,
        inTransaction = inTransaction
    )

    /**
     * The Progress/History layer: which facts a scope *is*, and how they are read. The computation itself is
     * pure and lives in `domain/progress`, so this node is thin on purpose — it reads opportunities, attempts
     * (each assembled from its own captured presentation and confirmed sets) and the Program list, then hands
     * them to the calculator.
     *
     * There is **no** transaction runner and no id generator because it reads and never writes — "All
     * Programs" is a view, not an entity — and no DAO, policy, generator or planner, so measures needing them
     * are reported as deferred rather than guessed. The clock is passed for one purpose: the default span of
     * the rate-like measures, read through the injected port so a caller that owns its own time gets its own
     * window.
     */
    val programProgressService: ProgramProgressService = ProgramProgressService(
        programRepository = programRepository,
        scheduleRepository = programScheduleRepository,
        sessionRepository = workoutSessionRepository,
        clock = clock
    )

    /**
     * The one application-level Generate/Regenerate. It is wired with **no repository, no DAO, no clock and
     * no zone**, and that absence is the guarantee: Generate and Regenerate only ever alter a draft, so the
     * service has nothing to persist and no date to choose — the only route from a generated draft to storage
     * stays `ProgramSaveService`, and the only place a revision identity is minted stays the editor. No
     * scheduler either, so no generation pass can plan a slot or touch a date.
     *
     * The focus classification is a **wired node, not a constant read from the catalogue**: nothing here maps
     * a category, a body region or a training style onto a focus — that inference is what this stage replaces
     * with data.
     */
    val programGenerationService: ProgramGenerationService = ProgramGenerationService(
        catalogue = SHIPPED_EXERCISE_CATALOGUE,
        focusSource = ProductionFocusClassification,
        ids = DraftIdSource { idGenerator.newId() },
        // This Program's own performed sessions, and nothing else. The read is the repository's own —
        // `sessionsOfProgram` assembles a complete `WorkoutSession` from its stored snapshot and
        // occurrence rows — so there is exactly one path to those facts.
        context = ProgramHistoryGenerationContext(
            sessions = GenerationSessionHistory { programId ->
                workoutSessionRepository.sessionsOfProgram(programId)
            }
        )
    )

    /**
     * The exercise→family classification — the shipped catalogue read for the family each exercise already
     * states, so nothing here infers a family. It is a **graph node rather than a constant**, and its
     * construction sits **outside** every other node's container slice: several architecture gates read this
     * file with `substringAfter("val <node>").substringBefore("val <node>")`, so a node dropped between two
     * of those boundaries would silently widen a neighbour's scan and the failure would name a forbidden
     * token this wiring never meant to police.
     */
    val catalogExerciseFamilyClassification: CatalogExerciseFamilyClassification =
        CatalogExerciseFamilyClassification()

    /**
     * The exercise's own progression range, read from the shipped catalogue. The adaptive layer owns no
     * catalogue, so a range arrives as an input, and before this node existed production supplied none:
     * `ExerciseRangeSource.NONE` answered `null` for every id, so no range reached the engine at all. It is a
     * **read of a fact the catalogue already states**, not a new prescription or threshold — and a `val`
     * built once rather than a repository, since nothing is persisted, cached or invalidated.
     */
    val catalogExerciseRangeSource: CatalogExerciseRangeSource =
        CatalogExerciseRangeSource()

    /**
     * The adaptive integration: turning a **completed Session** into the adaptive half of the completion
     * unit. It reads the session's revision, that revision's next not-yet-started opportunity, its
     * presentation and the family's history, assembles the engine's request and returns one of the four
     * outcomes. It writes nothing — `sessionRuntime.finishSession` persists the `AdaptiveCompletion` inside
     * the completion transaction, which is why the same runner and [programAdaptiveRepository] are on both
     * sides.
     *
     * The ladder comes from [storedProgressionRelationProvider] and the family from
     * [catalogExerciseFamilyClassification], both read off the app's own catalogue. Families with no declared
     * ladder are **undeclared by decision** — `plank` and `glute_bridge` cannot be authored under the
     * one-exercise-one-position invariant — so a pass over them reports
     * `AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION`; absence is answered `null` and no family is made
     * adaptable to make coverage look complete.
     *
     * The `zone` is the **same** value the session runtime receives: the integration chooses the target
     * opportunity against the decision's own day while the runtime checks that choice against the decision's
     * own moment, and two calendars would be a producer and a consumer disagreeing about which day a decision
     * belongs to. The window rule and the policy are the integration's own documented v1 values, because a
     * threshold with a second home can disagree with itself.
     */
    val programAdaptiveIntegration: ProgramAdaptiveIntegration = ProgramAdaptiveIntegration(
        planRepository = programPlanRepository,
        scheduleRepository = programScheduleRepository,
        sessionRepository = workoutSessionRepository,
        adaptiveRepository = programAdaptiveRepository,
        relations = storedProgressionRelationProvider,
        classification = catalogExerciseFamilyClassification,
        clock = clock,
        idGenerator = idGenerator,
        zone = zone,
        exerciseRanges = catalogExerciseRangeSource
    )

    /**
     * Settings → **Full reset**: the app's own Program data and its retained daily-track data, wiped as one
     * unit.
     *
     * It is a graph node rather than a call the view model assembles, for the same reason every other atomic
     * operation is: the transaction runner belongs in the composition root, and a reset that opened its own
     * would be a second place where "all or nothing" is decided. What it clears and what it keeps is
     * [MaintenanceRepository]'s and [com.monkfitness.app.data.local.MaintenanceDao]'s.
     */
    val maintenanceRepository: MaintenanceRepository = MaintenanceRepository(
        dao = database.maintenanceDao(),
        inTransaction = inTransaction
    )

    /**
     * The database's data-access objects, each asked for **once**.
     *
     * A Room DAO accessor is cheap and memoized, so calling `database.programDao()` per repository would
     * work — and would quietly make "how many things in this graph read `program`" a number nobody can
     * state. Taking each DAO once here keeps the answer mechanical: one database, one DAO per table, shared
     * by every repository that needs it. The set is exactly the target graph's plus the two Stage-1 adaptive
     * tables; `progressDao` and the rest of the shipped accessors are deliberately not taken, because
     * nothing in the Program System reads the shipped progress tables.
     */
    private class ProgramDaos(database: AppDatabase) {

        val program: ProgramDao = database.programDao()
        val appState: AppStateDao = database.appStateDao()
        val revision: ProgramRevisionDao = database.programRevisionDao()
        val day: ProgramDayDao = database.programDayDao()
        val exercise: ProgramExerciseDao = database.programExerciseDao()
        val slot: ProgramWorkoutSlotDao = database.programWorkoutSlotDao()
        val targetOccurrence: ProgramTargetOccurrenceDao = database.programTargetOccurrenceDao()
        val targetScheduleSource: ProgramTargetScheduleSourceDao =
            database.programTargetScheduleSourceDao()
        val session: WorkoutSessionDao = database.workoutSessionDao()
        val snapshot: SessionSnapshotDao = database.sessionSnapshotDao()
        val snapshotExercise: SessionSnapshotExerciseDao = database.sessionSnapshotExerciseDao()
        val sessionExercise: SessionExerciseDao = database.sessionExerciseDao()
        val setLog: ProgramSetLogDao = database.programSetLogDao()
        val pause: ProgramPauseDao = database.programPauseDao()
        val familyState: ProgramFamilyProgressionStateDao = database.programFamilyProgressionStateDao()
        val decision: ProgramAdaptiveDecisionDao = database.programAdaptiveDecisionDao()
        val adjustment: AdaptiveAdjustmentDao = database.adaptiveAdjustmentDao()
        val progressionRelationVariant: ProgressionRelationVariantDao =
            database.progressionRelationVariantDao()
    }

    companion object {

        /**
         * Composes the app's graph over the app's one database.
         *
         * `AppDatabase.getDatabase` is the process-wide singleton, so calling this more than once builds
         * more containers but never a second database — and the application holds exactly one container
         * ([com.monkfitness.app.MonkFitnessApplication.container]), created on first use so the moment the
         * database is acquired is unchanged.
         *
         * @param clock production takes the default; a caller that owns its own time (a test, or a future
         *   import that must replay a program's dates) supplies it.
         * @param idGenerator the runtime's id generator, with the same default-and-override shape.
         */
        fun create(
            context: Context,
            clock: Clock = Clock.system(),
            idGenerator: IdGenerator = IdGenerator.random()
        ): AppContainer = AppContainer(AppDatabase.getDatabase(context), clock, idGenerator)
    }
}
