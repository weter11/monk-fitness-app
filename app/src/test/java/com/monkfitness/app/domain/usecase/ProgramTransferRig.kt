package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity
import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraph
import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.AppState
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.MovableClock
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.SchedulerFixture
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.program.StandardProgram
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.transfer.ExerciseLibrary
import com.monkfitness.app.domain.program.transfer.ProgramTransferFixture
import com.monkfitness.app.domain.workout.EffectiveExercise
import java.time.DayOfWeek

/**
 * The §30-step-13 rig: the data-access rig's real SQLite engine and production DAOs, with the transfer
 * boundary wired over them exactly as `AppContainer` wires it.
 *
 * It exists so that every claim this stage makes is measured on storage rather than argued from the code's
 * shape:
 *
 *  * [storeSourceProgram] stores a **non-trivial** Program — three days, a rest day, an exercise used twice
 *    in one day, per-set repetition and duration prescriptions, a pinned user-authored element and a
 *    generated one — and the Program is `RUNNING` with three planned opportunities, so an export has
 *    something real to carry;
 *  * [startSessionOnSource] and [seedAdaptiveStateOnSource] put a **session with confirmed sets**, a
 *    family's progression state, an adaptive decision and an adjustment on that Program, so "import creates
 *    none of these" and "export carries none of these" are claims about rows that exist;
 *  * [tableCounts] and [rowsOfProgram] count **every** table and count a table per Program, which is how
 *    "a failed import left nothing" and "the imported Program owns nothing historical" are measured;
 *  * [clock] and [ids] are the two §26 ports, so a test can state the moment of an import and read back
 *    every identity it minted;
 *  * [library] is the exerciseId boundary, and it holds the ids the fixtures actually use.
 */
internal class ProgramTransferRig(private val key: String = "t") {

    val data: ProgramDataAccessRig = ProgramDataAccessRig(key)

    val database get() = data.database

    /** The calendar every date in this suite is read in — the scheduler fixture's own zone. */
    val zone = SchedulerFixture.ZONE

    /** The moment the import happens at, movable so a test can state it. */
    val clock: MovableClock = MovableClock(ProgramTransferFixture.importedAt())

    /** Deterministic identity: every minted id is readable in a failure message. */
    val ids: SequentialIds = SequentialIds("transfer")

    /** §5's exerciseId boundary, holding the ids the fixtures plan with. */
    val library: ExerciseLibrary = RecordingExerciseLibrary(ProgramTransferFixture.KNOWN_EXERCISE_IDS)

    /** The failures a test can plant, so §27's unit is decided by SQLite and not by this suite. */
    val faults: TransferFaults = TransferFaults()

    /**
     * The Program aggregate's persistence, over a slot DAO that can be made to fail — the same shape
     * `ProgramSchedulerRig` uses, and for the same reason: "a failure at the last leg leaves nothing" is a
     * claim about the engine's transaction, so the failure has to be planted in the engine's path rather
     * than argued from the code's shape. It is transparent until a fault is planted, and the element-write
     * fault of the data-access rig (`data.faults.failExerciseInsert`) is planted in the *same* repository,
     * because that rig's exercise DAO is the one this repository is built over.
     */
    val programRepository: ProgramRepository = ProgramRepository(
        data.programDao,
        data.revisionDao,
        data.dayDao,
        data.exerciseDao,
        FaultableSlotDao(data.slotDao, faults),
        data.transaction
    )

    val planRepository get() = data.programPlanRepository

    val scheduleRepository get() = data.programScheduleRepository

    val appStateRepository get() = data.appStateRepository

    val adaptiveRepository get() = data.programAdaptiveRepository

    val sessionRepository get() = data.workoutSessionRepository

    /** §3's/§21's selection owner, wired as the composition root wires it. */
    val lifecycleService: ProgramLifecycleService = ProgramLifecycleService(
        programRepository = programRepository,
        scheduleRepository = data.programScheduleRepository,
        sessionRepository = data.workoutSessionRepository,
        appStateRepository = data.appStateRepository,
        planRepository = data.programPlanRepository,
        clock = clock,
        idGenerator = ids,
        standardProgramId = StandardProgram.programId,
        inTransaction = data.transaction
    )

    /** §20's timing owner, wired as the composition root wires it. */
    val scheduler: ProgramScheduler = ProgramScheduler(
        programRepository = programRepository,
        planRepository = data.programPlanRepository,
        scheduleRepository = data.programScheduleRepository,
        clock = clock,
        idGenerator = ids,
        zone = zone,
        inTransaction = data.transaction
    )

    val exportService = ProgramExportService(programRepository)

    val importService = ProgramImportService(
        programRepository = programRepository,
        scheduler = scheduler,
        lifecycleService = lifecycleService,
        exerciseLibrary = library,
        clock = clock,
        idGenerator = ids,
        zone = zone,
        inTransaction = data.transaction
    )

    // ---------------------------------------------------------------- what the tests start from

    /**
     * Stores a Program with the fixture's own non-trivial shape — three days with a rest day, an exercise
     * used twice, per-set repetition and duration prescriptions, a pinned element and generated ones, and
     * three planned opportunities — written through the production repository.
     *
     * The plan's exercise ids are [withCatalogueExerciseIds]'s: the shape is [ProgramGraphFixture]'s, the
     * ids are ones the app's library really holds.
     */
    suspend fun storeSourceProgram(): ProgramGraph {
        val graph = ProgramGraphFixture.graph(key)
        val revision = graph.revision.copy(days = graph.revision.days.map { day -> day.withCatalogueExerciseIds() })
        programRepository.createProgram(graph.program, revision, graph.slots)
        return graph.copy(revision = revision)
    }

    /** The source Program's identity, which every fixture row carries. */
    val sourceProgramId: ProgramId get() = ProgramId(ProgramGraphFixture.programId(key))

    /** Starts an attempt at the source Program's opportunity [position] and confirms the sets it carries. */
    suspend fun startSessionOnSource(position: Int = 1) {
        data.startSessionWithSets(ProgramGraphFixture.session(key, data.graph.slotFor(position)))
    }

    /**
     * Puts the target adaptive generation's three kinds of row on the **source** Program: a family's
     * progression state, an applied decision and the adjustment it produced.
     *
     * They exist so that "an import creates none of them" is a claim about a non-empty table rather than
     * about a table nobody wrote to.
     */
    suspend fun seedAdaptiveStateOnSource() {
        val programId = data.graph.program.programId
        val revisionId = data.graph.revision.revisionId
        val slotId = data.graph.slotFor(1).slotId
        val occurrence = ProgramExerciseId(ProgramGraphFixture.planExerciseId(key, 1))

        adaptiveRepository.saveFamilyState(
            FamilyProgressionState(
                revisionId = revisionId,
                familyId = "pushups",
                progressionLevel = 1,
                adaptationState = AdaptiveState.PROGRESS,
                currentExerciseId = "pushups",
                updatedAt = ProgramGraphFixture.CREATED
            )
        )
        adaptiveRepository.persistDecision(
            decision = AdaptiveDecision(
                decisionId = DecisionId("decision-source"),
                programId = programId,
                revisionId = revisionId,
                slotId = slotId,
                target = AdaptiveTarget.Exercise("pushups"),
                action = AdaptiveAction.PROGRESS,
                outcome = DecisionOutcome.APPLIED,
                evidence = EvidenceLevel.STRONG,
                confidence = ConfidenceLevel.HIGH,
                recovery = RecoveryContext.FAVORABLE,
                decidedAt = ProgramGraphFixture.CREATED,
                adjustmentId = AdjustmentId("adjustment-source")
            ),
            adjustment = AdaptiveAdjustment(
                adjustmentId = AdjustmentId("adjustment-source"),
                decisionId = DecisionId("decision-source"),
                slotId = slotId,
                before = EffectiveExercise(occurrence, "pushups", RepPrescription(listOf(12, 10, 8, 6))),
                after = EffectiveExercise(occurrence, "pike_pushups", RepPrescription(listOf(8, 8))),
                createdAt = ProgramGraphFixture.CREATED
            )
        )
    }

    /**
     * Stores the built-in Standard Program, so the selection rules §3 owns have a fallback to move to and a
     * test can prove an imported copy of it is *not* standard.
     */
    suspend fun seedStandardProgram() {
        val standardId = StandardProgram.programId
        val days = ProgramGraphFixture.days("standard").map { day -> day.withCatalogueExerciseIds() }
        val revision = ProgramRevision(
            revisionId = RevisionId("revision-standard"),
            programId = standardId,
            revisionNumber = ProgramRevision.FIRST_REVISION_NUMBER,
            mode = ProgramMode.MANUAL,
            duration = ProgramDuration.FixedDays(30),
            schedule = ProgramSchedule.FixedWeekdays(
                setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
            ),
            days = days,
            createdAt = ProgramGraphFixture.CREATED
        )
        val program = Program(
            programId = standardId,
            name = StandardProgram.NAME,
            description = "the built-in program",
            source = StandardProgram.source,
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            currentRevisionId = revision.revisionId,
            createdAt = ProgramGraphFixture.CREATED,
            updatedAt = ProgramGraphFixture.CREATED
        )
        programRepository.createProgram(program, revision, emptyList())
    }

    // ---------------------------------------------------------------- what the tests read back

    /**
     * Stores a **second** revision of the source Program: a new revision identity, new day identities and
     * new element identities, carrying whatever configuration the test states (§6 — a structural change is a
     * new immutable revision, never an edit).
     *
     * It is how a suite states the two facts an export depends on: which revision is current (§17), and that
     * the configuration the current one states is what the file carries.
     */
    suspend fun storeRevision(
        source: ProgramGraph,
        revisionNumber: Int = source.revision.revisionNumber + 1,
        mode: ProgramMode = source.revision.mode,
        duration: ProgramDuration = source.revision.duration,
        schedule: ProgramSchedule = source.revision.schedule,
        focus: com.monkfitness.app.domain.program.FocusPlan = source.revision.focus
    ): ProgramRevision {
        val tag = "${source.program.programId.value.removePrefix("program-")}-$revisionNumber"
        val revision = ProgramRevision(
            revisionId = RevisionId("revision-$tag"),
            programId = source.program.programId,
            revisionNumber = revisionNumber,
            mode = mode,
            duration = duration,
            schedule = schedule,
            days = source.revision.days.map { day ->
                day.copy(
                    programDayId = com.monkfitness.app.domain.common.ProgramDayId("day-$tag-${day.position}"),
                    exercises = day.exercises.mapIndexed { index, element ->
                        element.copy(
                            programExerciseId = com.monkfitness.app.domain.common.ProgramExerciseId(
                                "plan-ex-$tag-${day.position}-${index + 1}"
                            )
                        )
                    }
                )
            },
            createdAt = ProgramGraphFixture.UPDATED,
            focus = focus
        )
        planRepository.saveNewRevision(revision, ProgramGraphFixture.UPDATED)
        return revision
    }

    suspend fun storedProgram(programId: ProgramId): Program? =
        data.freshProgramRepository().programById(programId)

    suspend fun storedPrograms(): List<Program> = data.freshProgramRepository().programs()

    suspend fun currentRevision(programId: ProgramId): ProgramRevision? =
        data.freshPlanRepository().currentRevision(programId)

    suspend fun revisionCount(programId: ProgramId): Int =
        data.freshPlanRepository().countRevisionsOf(programId)

    suspend fun slotsOf(programId: ProgramId): List<WorkoutSlot> =
        data.programScheduleRepository.slotsOfProgram(programId)

    suspend fun selection(): AppState? = data.appStateRepository.state()

    suspend fun select(programId: ProgramId) {
        data.appStateRepository.save(AppState(selectedProgramId = programId))
    }

    /** The row count of **every** table, shipped ones included — the whole-database census. */
    fun tableCounts(): Map<String, Int> = SchedulerFixture.TABLES.associateWith { table ->
        database.count(table)
    }

    /** How many rows one Program owns in [table], counted in SQL rather than in memory. */
    fun rowsOfProgram(table: String, programId: ProgramId): Int =
        database.scalar("SELECT COUNT(*) FROM `$table` WHERE `programId` = ?", programId.value)
            ?.toInt() ?: 0

    /** How many rows one **revision** owns in [table] — the plan tables are keyed by revision. */
    fun rowsOfRevision(table: String, revisionId: RevisionId): Int =
        database.scalar("SELECT COUNT(*) FROM `$table` WHERE `revisionId` = ?", revisionId.value)
            ?.toInt() ?: 0

    /**
     * How many confirmed sets one Program's sessions hold.
     *
     * `program_set_log` is keyed by a **session occurrence**, not by a Program — a set belongs to the
     * occurrence the user performed it in (§27) — so a Program's set count has to go through its sessions
     * rather than through a column that does not exist.
     */
    fun rowsOfSetLogs(programId: ProgramId): Int =
        database.scalar(
            "SELECT COUNT(*) FROM `program_set_log` WHERE `sessionExerciseId` IN " +
                "(SELECT `sessionExerciseId` FROM `session_exercise` WHERE `sessionId` IN " +
                "(SELECT `sessionId` FROM `workout_session` WHERE `programId` = ?))",
            programId.value
        )?.toInt() ?: 0

    /**
     * How many plan elements one revision's days carry.
     *
     * `program_exercise` is keyed by **day**, not by revision — an occurrence belongs to the day that holds
     * it (§9) — so a revision's occurrence count has to go through its days rather than through a column
     * that does not exist.
     */
    fun rowsOfPlanElements(revisionId: RevisionId): Int =
        database.scalar(
            "SELECT COUNT(*) FROM `program_exercise` WHERE `programDayId` IN " +
                "(SELECT `programDayId` FROM `program_day` WHERE `revisionId` = ?)",
            revisionId.value
        )?.toInt() ?: 0

    fun close() = data.close()
}

/**
 * §5's exerciseId boundary as a test double: a set of ids and the honest answer about membership.
 *
 * It records nothing and decides nothing — it is the library's *shape* without the library, which is what
 * lets a suite state a document that names an exercise the app does not have. The production adapter is
 * exercised against the app's real catalogue in `ProgramTransferArchitectureTest`.
 */
internal class RecordingExerciseLibrary(private val known: Set<String>) : ExerciseLibrary {

    override suspend fun knows(exerciseId: String): Boolean = exerciseId in known
}

/** The failures a transfer suite can plant in the creation unit's path (§27, §13). */
internal class TransferFaults {

    /** When set, storing the revision's initial opportunities fails — the last leg of the unit. */
    var failSlotInsert: Boolean = false
}

/**
 * The production slot DAO with one switchable failure: the insert of the opportunities a save decided on.
 *
 * The element-write leg is planted through the data-access rig's own failing exercise DAO, which is the
 * instance this rig's repository is built over — so both legs are measured on the real engine, inside the
 * real transaction.
 */
private class FaultableSlotDao(
    private val delegate: ProgramWorkoutSlotDao,
    private val faults: TransferFaults
) : ProgramWorkoutSlotDao {

    override suspend fun insertSlots(slots: List<ProgramWorkoutSlotEntity>) {
        if (faults.failSlotInsert) throw IllegalStateException("planted fault: slot insert")
        delegate.insertSlots(slots)
    }

    override suspend fun slotById(slotId: String): ProgramWorkoutSlotEntity? = delegate.slotById(slotId)

    override suspend fun slotByTargetOccurrenceKey(
        programId: String,
        targetOccurrenceKey: String
    ): ProgramWorkoutSlotEntity? = delegate.slotByTargetOccurrenceKey(programId, targetOccurrenceKey)

    override suspend fun slotsOfProgram(programId: String): List<ProgramWorkoutSlotEntity> =
        delegate.slotsOfProgram(programId)

    override suspend fun slotsOfRevision(revisionId: String): List<ProgramWorkoutSlotEntity> =
        delegate.slotsOfRevision(revisionId)

    override suspend fun slotsFrom(
        programId: String,
        fromDate: String,
        status: String
    ): List<ProgramWorkoutSlotEntity> = delegate.slotsFrom(programId, fromDate, status)

    override suspend fun updateOutcome(slotId: String, status: String, completedAt: Long?) =
        delegate.updateOutcome(slotId, status, completedAt)

    override suspend fun countByStatus(programId: String, status: String): Int =
        delegate.countByStatus(programId, status)
}

/**
 * [ProgramDay] with the exercise ids the **shipped catalogue** really holds.
 *
 * `ProgramGraphFixture` was written long before anything asked whether a plan element's id exists in the
 * library, and it plans `pushup` and `pike_pushup` — while `WorkoutGenerator`'s catalogue holds `pushups`
 * and `pike_pushups`. Nothing before §30 step 13 ever asked, so the difference was invisible; an import is
 * the first boundary that *must* ask (§5: *"unknown exerciseId is rejected"*), and it is right to refuse a
 * file that names an exercise the app does not have. So the transfer fixtures plan with the catalogue's own
 * ids, and the older fixture is left exactly as it is: the suites built on it are not this stage's to move.
 *
 * It is recorded in `docs/PROGRAM_IMPORT_EXPORT.md` as a finding of this stage rather than hidden here,
 * because it is the first evidence that the pre-P13 tree never checked an id against a library.
 */
private fun ProgramDay.withCatalogueExerciseIds(): ProgramDay = copy(
    exercises = exercises.map { element ->
        element.copy(
            exerciseId = when (element.exerciseId) {
                "pushup" -> "pushups"
                "pike_pushup" -> "pike_pushups"
                else -> element.exerciseId
            }
        )
    }
)
