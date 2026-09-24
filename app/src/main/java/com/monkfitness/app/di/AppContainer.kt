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
import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.bootstrap.StandardProgramBootstrap
import com.monkfitness.app.domain.adaptive.integration.NoDeclaredProgression
import com.monkfitness.app.domain.adaptive.integration.NoExerciseFamilyClassification
import com.monkfitness.app.domain.usecase.ProgramAdaptiveIntegration
import com.monkfitness.app.domain.usecase.ProgramEditorService
import com.monkfitness.app.domain.usecase.ProgramExerciseLibrary
import com.monkfitness.app.domain.usecase.ProgramExportService
import com.monkfitness.app.domain.usecase.ProgramImportService
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.usecase.ProgramScheduler
import com.monkfitness.app.domain.usecase.ProgramSaveService
import com.monkfitness.app.domain.usecase.SessionRuntime
import com.monkfitness.app.domain.program.StandardProgram
import java.time.ZoneId

/**
 * The Program System's composition root — §26: "*Use explicit `AppContainer`*", with the app's own
 * shape:
 *
 * ```text
 * MonkFitnessApplication
 *     ↓
 * AppContainer            ← this class
 *     ↓
 * Repositories / Services / UseCases
 *     ↓
 * ViewModels
 * ```
 *
 * ### What it is
 *
 * One object that owns the app's **one** database and constructs every repository over it. It is the
 * only place a database is acquired, the only place a repository of the Program System is
 * constructed, and the only place the clock, the id generator and the transaction runner enter the
 * graph, so "which database does this repository use" and "what time does this row get stamped with"
 * are answered by reading one file.
 *
 * ### What it is not
 *
 * It is a **composition root, not a service locator**. Nothing reaches into it statically: it is a
 * value the application holds, its repositories are ordinary constructor arguments handed to their
 * consumers, and no consumer asks a global for a dependency it needs. §26 also rules the other
 * direction: "*ViewModels never create DB/repositories*", so a ViewModel receives what it needs —
 * it does not come here to fetch it.
 *
 * It decides **nothing** about the program. There is no lifecycle transition, no revision rule, no
 * scheduling, no generation, no adaptive policy, no load guard and no import/export anywhere in this
 * class: wiring a dependency is not a decision about behaviour, and no production path gains one merely
 * because these objects now exist (§33). §30 steps 7–9 added three nodes — the timing pass, the session
 * runtime and the Progress layer — and this container only says which objects each of them receives; each
 * of their own documents says what they do with them. Each repository below is still exactly the object
 * §30 step 3 landed, with the collaborators that stage documented.
 *
 * ### The single database
 *
 * [database] is the app's shared `AppDatabase` (§26's "*Database instance is created once and
 * shared*"): [create] asks `AppDatabase.getDatabase` — the process-wide singleton — for it exactly
 * once per container, and nothing else in production acquires a database at all. Every DAO the graph
 * needs is taken from *that* instance, once per table ([ProgramDaos]), so the seven repositories and
 * the two adaptive generations below are all windows onto one open database rather than a set of
 * connections that happen to point at the same file.
 *
 * ### The two persistence generations stay separate
 *
 * §24 lists `AdaptiveRepository`, and that name is already taken by the shipped Stage-1 adapter, which
 * reads and writes `family_progression_state` and `adaptive_decision_record` keyed by the legacy
 * revision integer. The Program System's own adaptive tables
 * (`program_family_progression_state`, `program_adaptive_decision_record`, `adaptive_adjustment`) are
 * served by `ProgramAdaptiveRepository`. Both are wired here, over the same database, and each is
 * wired to **its own** tables: [adaptiveRepository] never reads a target table, and
 * [programAdaptiveRepository] never reads a Stage-1 one. The two names collapse when §30 step 15
 * retires the legacy generation — not before, because a target repository that silently fell back to
 * a legacy table would make "the legacy rows are byte-identical" unprovable.
 *
 * @param database the app's one database. In production it is [create]'s `AppDatabase.getDatabase`
 *   result; a test may pass its own so the composition root can be exercised without a device.
 * @param clock the clock the graph stamps persistence facts with. Injected, never read inside a
 *   repository (§26).
 * @param idGenerator the generator new identity is minted through. Injected, never read inside a
 *   repository or a mapper (§26). Nothing in this stage mints an id yet: §30 step 5's creation path
 *   is the first consumer.
 * @param zone the calendar the adaptive path reads a *day* in — handed to **both** the integration
 *   (which chooses the target opportunity against the decision's own day) and the session runtime (which
 *   checks that choice against the decision's own moment). One value for the two sides of §4's contract
 *   is what keeps them from disagreeing about which day a decision belongs to; a caller that owns its own
 *   calendar supplies it, and the production default is the device's.
 * @param inTransaction the runner every atomic operation of the graph executes in. It defaults to
 *   the database's **own** `withTransaction`, which is the wired production behaviour; the parameter
 *   exists because a `RoomDatabase` transaction needs a database Room has actually opened, and this
 *   repository's unit tests run without a device (§30 step 3's rig supplies the engine's own
 *   `BEGIN`/`COMMIT`/`ROLLBACK` in exactly the same place).
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

    // --- the Program aggregate (§24) -------------------------------------------------------------

    /**
     * The Program itself: its creation (Program + first revision + plan + initial slots in one
     * transaction) and its cascade delete. It exposes no lifecycle transition, no selection and no
     * "may this be deleted" — those are §3's and §29's decisions, and §30 step 5 owns them.
     */
    val programRepository: ProgramRepository = ProgramRepository(
        daos.program, daos.revision, daos.day, daos.exercise, daos.slot, inTransaction
    )

    /**
     * A revision's plan: reading it, and saving a new one as new rows with the `currentRevisionId`
     * pointer moved in the same transaction. Future-slot reconciliation is the Scheduler's (§30
     * step 7) and is not wired here.
     */
    val programPlanRepository: ProgramPlanRepository = ProgramPlanRepository(
        daos.program, daos.revision, daos.day, daos.exercise, inTransaction
    )

    /**
     * Slots and pause intervals: what the plan was scheduled as, read and recorded. No date is
     * chosen here — `addSlots` stores what the Scheduler decided (§30 step 7).
     */
    val programScheduleRepository: ProgramScheduleRepository = ProgramScheduleRepository(
        daos.slot, daos.session, daos.pause
    )

    /**
     * Sessions: starting one with the presentation it captured, reading it back from that capture
     * alone (§19), appending a confirmed set, and finishing. §27's "Complete Workout + Adaptive"
     * composition is the session runtime's (§30 step 8) and is not built here.
     */
    val workoutSessionRepository: WorkoutSessionRepository = WorkoutSessionRepository(
        daos.session, daos.snapshot, daos.snapshotExercise, daos.sessionExercise, daos.setLog,
        daos.slot, inTransaction
    )

    /**
     * The row-level facts Progress reads: counts and ordered identities (§21), with no derived
     * measure. Computing frequency, volume, focus, PRs or streaks is §30 step 9's and is
     * deliberately absent.
     */
    val programProgressRepository: ProgramProgressRepository = ProgramProgressRepository(
        daos.slot, daos.session, daos.setLog
    )

    /**
     * The single global application-state row (`app_state`): which Program is selected, which is
     * next, and whether the next one starts automatically. Reading it invents no fallback and
     * writing it performs no transition — §3's lifecycle rules are §30 step 5's.
     */
    val appStateRepository: AppStateRepository = AppStateRepository(daos.appState)

    /**
     * The **target** adaptive persistence: family progression state, the decision trail and the
     * adjustments, on the target tables only. It evaluates no policy and calculates no signal (§30
     * step 11 owns the engine, step 12 the integration).
     *
     * The clock reaches it here and nowhere else: `now = { clock.now() }` reads the container's
     * injected clock at each stamp, so a runtime with a moved clock — a test, or a future caller with
     * its own — stamps the rows it writes with the time it supplied.
     */
    val programAdaptiveRepository: ProgramAdaptiveRepository = ProgramAdaptiveRepository(
        daos.familyState, daos.decision, daos.adjustment, now = { clock.now() }, inTransaction = inTransaction
    )

    /**
     * The Program System's lifecycle and selection decisions — §30 step 5, over the repositories this
     * container constructs.
     *
     * It is the layer that owns the rules the persistence layer must not (§3, §4, §29): which lifecycle
     * transitions are legal, that selection is one global fact, that deleting the selected Program falls
     * back to the Standard Program *after* moving the selection, that the Standard Program is protected
     * from a direct edit and from deletion, and that an `IN_PROGRESS` session blocks a delete. It makes
     * those decisions from domain values and asks the repositories below only for persistence.
     *
     * The collaborators are the ones above; the clock and the id generator are the container's two
     * injected ports (§26), because `actualStartDate` is a fact and a new Program's identity is minted,
     * not derived. The Standard Program's id is [StandardProgram.programId] — one constant, read by the
     * fallback, by the guard and by the stage that will seed the plan.
     *
     * This is **wiring, not behaviour**: the container decides which objects the service receives and
     * nothing about what it does with them. The transaction runner is the same one every repository
     * uses, so §27's `Delete Program → selection move + cascade` is one unit of the database's own.
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
     * The Program System's **Manual Editor** — §30 step 6, over the repositories and the lifecycle
     * service above.
     *
     * It is the layer that owns draft-first editing and the revision rule (§6, §7, §27): it opens a
     * draft from a Program (or from a Program being copied), validates it, reviews what saving would
     * do, and saves — which is to say it decides whether a save warrants a new immutable revision,
     * whether it creates a Program, or whether it writes nothing at all. The built-in Program is
     * refused for an in-place edit here, through the same guard the lifecycle carries, so §4's
     * copy-before-edit rule has one implementation.
     *
     * The collaborators are chosen for what they deliberately exclude: the two repositories that own
     * persistence, the clock and the id generator, and the transaction runner. There is **no**
     * schedule repository and no exercise-library port, and that absence is the guarantee — the
     * editor cannot reconcile scheduler slots (§20 is §30 step 7's) or touch exercise metadata (§10)
     * because it has nothing to do either with.
     *
     * This is **wiring, not behaviour**: the container decides which objects the service receives and
     * nothing about what it does with them. The transaction runner is the same one every repository
     * uses, so §27's `Save Editor → new Revision` is one unit of the database's own.
     */
    val programEditorService: ProgramEditorService = ProgramEditorService(
        programRepository = programRepository,
        planRepository = programPlanRepository,
        clock = clock,
        idGenerator = idGenerator,
        inTransaction = inTransaction
    )

    /**
     * The Scheduler — §30 step 7, over the repositories above and the plan the editor writes.
     *
     * It owns §20's timing decisions: which dates a saved revision trains on over a bounded window, which
     * of them do not have an opportunity yet, which existing opportunities the revision no longer
     * presents, and which passed. It turns a revision into **slots** and nothing else — no Session, no
     * policy, no plan content, no lifecycle movement — and it is the layer §27's
     * *"Save Editor → new Revision + future-slot reconciliation"* names for the second half of that
     * sentence.
     *
     * The collaborators are chosen for what they exclude as much as for what they do. There is **no
     * session repository**, so a Session cannot be created here (§33); no plan *write* is used, so an
     * immutable revision cannot be rewritten (§6); no adaptive, generator, library or progress port is
     * present, so the pass cannot consult performance, produce a plan or derive a statistic (§30 steps
     * 9–12). The `zone` is the calendar the two dates in a pass are read in: the clock supplies an
     * instant and a pause is stored as instants, while a slot is planned for a date, and the conversion
     * belongs to the layer that owns the clock rather than to the decision (§26).
     *
     * This is **wiring, not behaviour**: the container decides which objects the service receives and
     * nothing about what it does with them. The transaction runner is the same one every repository
     * uses, so one pass is one unit of the database's own — every opportunity it creates and every status
     * it records land together, or none of them does.
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

    /** The idempotent production bootstrap for the product-owned Standard Program. */
    val standardProgramBootstrap: StandardProgramBootstrap = StandardProgramBootstrap(
        programRepository = programRepository,
        scheduler = programScheduler,
        clock = clock,
        idGenerator = idGenerator,
        zone = zone
    )

    /**
     * The **Save** orchestration — §27's two composition lines, over the three owners above.
     *
     * It is a graph node for the same reason every atomic operation in this container is: §26 puts
     * the transaction runner in the composition root, and §27's lines are pairings of writes that
     * must land together — `Create / Copy → Program + Revision + plan + initial Slots`, and
     * `Save Editor → new Revision + future-slot reconciliation`. The editor alone cannot hold
     * either (it may not touch a slot or a date), the Scheduler alone plans only what is stored, and
     * a screen assembling the two would be the UI deciding when a scheduling pass runs (§16, §33).
     *
     * ```text
     * editor              the draft's structure: validation, the minted Program + first Revision,
     *                     and an edit's own revision rule (§6, §7)
     * programRepository   the creation primitive: the whole graph and its slots, or nothing (§27)
     * scheduler           initial opportunities for a creation; the one reconciliation pass after
     *                     an edit (§20)
     * clock, zone         *today*, only when a creation request names no exact start date (§26)
     * inTransaction       the unit: a failure at any leg leaves no partial Program and no
     *                     half-applied save
     * ```
     *
     * There is **no** lifecycle service here, so a creation cannot move a selection (§3, §9), no
     * plan repository (the editor holds the revision rule), no DAO and no session or progress port.
     *
     * This is **wiring, not behaviour**: the container decides which objects the Save receives and
     * nothing about what it does with them.
     */
    val programSaveService: ProgramSaveService = ProgramSaveService(
        editor = programEditorService,
        programRepository = programRepository,
        scheduler = programScheduler,
        clock = clock,
        zone = zone,
        inTransaction = inTransaction
    )

    // --- §30 step 13: the transfer boundary (export / import / share) --------------------------------

    /**
     * The **export** half of §30 step 13 — §5's *"Program definition/configuration only"*.
     *
     * It owns one thing: turning a stored Program into the file a share carries. Its single collaborator
     * is the repository that returns a Program together with the revision its pointer names, and the
     * absences are the guarantees §15 and §16 ask for: there is **no** session repository, no progress
     * repository, no adaptive repository, no clock and no id generator in this node, so an exported file
     * cannot contain a session, a set, a statistic, a streak, a family state, a decision or an adjustment
     * — not because this stage remembers not to write them, but because it holds nothing that could
     * produce one. The document type it writes has no field for any of them either, so both halves of the
     * prohibition are structural.
     *
     * This is **wiring, not behaviour**: the container decides which object the service receives and
     * nothing about what it does with it.
     */
    val programExportService: ProgramExportService = ProgramExportService(
        programRepository = programRepository
    )

    /**
     * The **import** half of §30 step 13 — §5's pipeline and §27's creation unit.
     *
     * It owns the whole path from a shared file to a new Program: decoding, parsing, the format version,
     * the schema, the exerciseId boundary, the semantic validation, the Import Draft, and then the save
     * that writes `Program + Revision + ProgramDays + ProgramExercises + initial slots` as **one
     * transaction** (§27). The collaborators are chosen for what each of them owns and for what this node
     * therefore cannot do:
     *
     * ```text
     * programRepository     the creation primitive: the Program-owned graph or nothing
     * scheduler             §20's timing: the imported revision's initial opportunities are its decision
     * lifecycleService      §3/§21's selection: the only path by which the import can become selected
     * ProgramExerciseLibrary §5's exerciseId boundary: the app's own catalogue, asked per referenced id
     * clock, idGenerator    the two §26 ports: every timestamp and every identity of the new graph
     * zone                  the calendar the import's date is read in — the day it is planned to start on
     * inTransaction         the unit: a failure at any leg leaves no Program, no revision and no slot
     * ```
     *
     * There is **no** `ProgramPlanRepository` (an import creates its first revision rather than saving one
     * onto an existing Program), no session repository, no adaptive repository and no progress reader, and
     * no DAO: the import reaches storage through the two owners of it above and through nothing else.
     *
     * This is **wiring, not behaviour**: the container decides which objects the service receives and
     * nothing about what it does with them.
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
     * The Session runtime — §30 step 8, over the repositories above.
     *
     * It owns the five operations of one attempt at one opportunity: starting a workout with the
     * complete immutable presentation it was started under (§19), reading it back from that capture
     * alone, appending a confirmed set, cancelling it, and completing it together with the adaptive
     * decision the adaptive stage hands over (§27's `Complete Workout → Session + Slot + Adaptive
     * state + Decisions + Adjustments`, which is one unit of work and is composed here).
     *
     * The collaborators are chosen for what they exclude as much as for what they do. There is **no
     * `ProgramRepository`**: the Program is bound by the revision the opportunity names, and whether a
     * Program is paused, archived or completed is §3's and §29's decision, not this operation's — so
     * *"no lifecycle policy beyond the session operation itself"* is the absence of the dependency
     * rather than a rule the code remembers. There is no generator, no policy, no signal calculator, no
     * progress reader and no calendar: §30 steps 9–12 own those. And there is no second way to write a
     * session row, so the occupancy rule of §19 cannot be bypassed from outside.
     *
     * This is **wiring, not behaviour**: the container decides which objects the runtime receives and
     * nothing about what it does with them. The transaction runner is the same one every repository
     * uses, which is what makes the completion one unit of the database's own rather than three writes
     * that happen to be adjacent.
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
     * The Progress/History layer — §30 step 9, over the facts the repositories above already expose.
     *
     * It owns §21's two aggregations and the history: which facts a scope *is*, and how they are read. The
     * computation itself is pure and lives in the domain (`domain/progress`), so this node is thin on
     * purpose — it reads opportunities from the schedule repository, attempts from the session repository
     * (each assembled from its own captured presentation and its confirmed sets) and the Program list from
     * the Program repository, then hands them to the calculator.
     *
     * The collaborators are chosen for what they exclude as much as for what they do. There is **no
     * transaction runner and no id generator**, because this layer reads and never writes: Progress is a
     * view of facts other layers own, and the aggregate §21 calls "All Programs" is a view rather than an
     * entity, so there is nothing here to store. There is no DAO either, no policy, no generator and no
     * planner: §30 steps 10–12 own those, and the measures that would need them are reported as deferred
     * rather than guessed.
     *
     * The clock is passed for one purpose: the default span of the rate-like measures. "Today" is read
     * through the injected port (§26) rather than inside the service, so a caller that owns its own time
     * gets its own window, and the zone the calendar is read in stays explicit.
     *
     * This is **wiring, not behaviour**: the container decides which objects the layer receives and nothing
     * about what it computes. Nothing above the container reaches it yet — §30 step 9 lands the contract,
     * and the screens are a later step.
     */
    val programProgressService: ProgramProgressService = ProgramProgressService(
        programRepository = programRepository,
        scheduleRepository = programScheduleRepository,
        sessionRepository = workoutSessionRepository,
        clock = clock
    )

    // --- §30 step 12: the adaptive integration ----------------------------------------------------

    /**
     * The adaptive integration — §30 step 12, over the repositories and the engine above.
     *
     * It owns one thing: turning a **completed Session** into the adaptive half of §27's completion unit.
     * It reads the session's revision, the next not-yet-started opportunity of that revision, that
     * opportunity's presentation and the family's own history, assembles the engine's request from those
     * facts, and returns one of §20's four outcomes. It writes nothing: the outcome carries an
     * `AdaptiveCompletion`, and `sessionRuntime.finishSession` is what persists it inside §27's
     * transaction — which is why the same [inTransaction] runner and the same
     * [programAdaptiveRepository] are on both sides of it.
     *
     * ### The two collaborators that are deliberately empty
     *
     * [relations] and [classification] are wired to their explicit *"nothing is declared"* values, and
     * that is the honest state of the target tree rather than a placeholder to be filled in later:
     *
     * ```text
     * no persisted family ladder      → NoDeclaredProgression          (§15's progression relations)
     * no persisted exercise→family map → NoExerciseFamilyClassification (§9's family membership)
     * ```
     *
     * Neither fact exists anywhere in §23's schema; the only ladders the repository holds are the Stage-1
     * pilot's, which §30 step 11 forbids this generation to reach for and which are scoped to the legacy
     * program's own axis. With no ladder declared, every adaptive pass in production ends in the engine's
     * bounded `PROGRESSION_UNAVAILABLE` hold ([com.monkfitness.app.domain.adaptive.integration
     * .AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION] when the day presents no exposed family either)
     * — **no family is adapted, and nothing is fabricated to make one look adaptable**. §30 step 12's
     * document names both artefacts and what supplying them means; a caller that has them (a test, or a
     * later stage that persists them) constructs this class with its own provider.
     *
     * ### What the container decides, and what it does not
     *
     * This is **wiring, not behaviour**: the container decides which objects the integration receives and
     * nothing about what it does with them. The `zone` handed over is the container's own [zone] — the
     * **same** value the session runtime receives, because the integration chooses the target opportunity
     * against the decision's own day while the runtime checks that choice against the decision's own
     * moment: two calendars there would be a producer and a consumer disagreeing about which day a
     * decision belongs to. The window rule and the policy are the integration's own documented v1 values,
     * for the same reason: a threshold with a second home is a threshold that can disagree with itself.
     */
    val programAdaptiveIntegration: ProgramAdaptiveIntegration = ProgramAdaptiveIntegration(
        planRepository = programPlanRepository,
        scheduleRepository = programScheduleRepository,
        sessionRepository = workoutSessionRepository,
        adaptiveRepository = programAdaptiveRepository,
        relations = NoDeclaredProgression,
        classification = NoExerciseFamilyClassification,
        clock = clock,
        idGenerator = idGenerator,
        zone = zone
    )

    /**
     * Settings → **Full reset**: the app's own Program data and its retained daily-track data, wiped as
     * one unit.
     *
     * It is a graph node rather than a call the view model assembles, for the same reason every other
     * atomic operation is: §26 puts the transaction runner in the composition root, and a reset that
     * opened its own would be a second place where "all or nothing" is decided. The contract it
     * implements — what it clears, what it keeps — is [MaintenanceRepository]'s and
     * [com.monkfitness.app.data.local.MaintenanceDao]'s.
     */
    val maintenanceRepository: MaintenanceRepository = MaintenanceRepository(
        dao = database.maintenanceDao(),
        inTransaction = inTransaction
    )

    /**
     * The database's data-access objects, each asked for **once**.
     *
     * A Room DAO accessor is cheap and memoized, so calling `database.programDao()` per repository
     * would work — and would quietly make "how many things in this graph read `program`" a number
     * nobody can state. Taking each DAO once here keeps the answer mechanical: one database, one DAO
     * per table, shared by every repository that needs it. The set is exactly the target graph's plus
     * the two Stage-1 adaptive tables; `progressDao` and the rest of the shipped accessors are
     * deliberately not taken, because nothing in the Program System reads the shipped progress
     * tables (§23: no new architecture dependency on `UserProgress`) and §30 step 15 is where they
     * leave.
     */
    private class ProgramDaos(database: AppDatabase) {

        val program: ProgramDao = database.programDao()
        val appState: AppStateDao = database.appStateDao()
        val revision: ProgramRevisionDao = database.programRevisionDao()
        val day: ProgramDayDao = database.programDayDao()
        val exercise: ProgramExerciseDao = database.programExerciseDao()
        val slot: ProgramWorkoutSlotDao = database.programWorkoutSlotDao()
        val session: WorkoutSessionDao = database.workoutSessionDao()
        val snapshot: SessionSnapshotDao = database.sessionSnapshotDao()
        val snapshotExercise: SessionSnapshotExerciseDao = database.sessionSnapshotExerciseDao()
        val sessionExercise: SessionExerciseDao = database.sessionExerciseDao()
        val setLog: ProgramSetLogDao = database.programSetLogDao()
        val pause: ProgramPauseDao = database.programPauseDao()
        val familyState: ProgramFamilyProgressionStateDao = database.programFamilyProgressionStateDao()
        val decision: ProgramAdaptiveDecisionDao = database.programAdaptiveDecisionDao()
        val adjustment: AdaptiveAdjustmentDao = database.adaptiveAdjustmentDao()
    }

    companion object {

        /**
         * Composes the app's graph over the app's one database.
         *
         * `AppDatabase.getDatabase` is the process-wide singleton, so calling this more than once
         * builds more containers but never a second database — and the application holds exactly one
         * container ([com.monkfitness.app.MonkFitnessApplication.container]), created on first use so
         * the moment the database is acquired is unchanged from the build that read it from the view
         * model.
         *
         * @param clock the runtime's clock. Production takes the default; a caller that owns its own
         *   time (a test, or a future import that must replay a program's dates) supplies it.
         * @param idGenerator the runtime's id generator, with the same default-and-override shape.
         */
        fun create(
            context: Context,
            clock: Clock = Clock.system(),
            idGenerator: IdGenerator = IdGenerator.random()
        ): AppContainer = AppContainer(AppDatabase.getDatabase(context), clock, idGenerator)
    }
}
