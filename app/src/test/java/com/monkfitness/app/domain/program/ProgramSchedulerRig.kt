package com.monkfitness.app.domain.program

import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity
import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramPlanRepository
import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.SqliteProgramWorkoutSlotDao
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.usecase.ProgramScheduler
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The scheduler suite's rig: the data-access rig's real SQLite engine and production DAOs, plus the
 * [ProgramScheduler] built over them exactly as [com.monkfitness.app.di.AppContainer] builds it.
 *
 * Everything a scheduler test needs to state is reachable from here:
 *
 *  * [scheduler] — the behaviour under test, constructed with a [MovableClock] (so *when* a pass is
 *    made is a value the test chose), a [SequentialIds] generator (so every minted `SlotId` is readable
 *    in a failure message) and a **fixed zone**, so the two dates a pass reads — the anchor and the pass
 *    date — are the dates the test wrote;
 *  * [scheduleRepository] — the production repository over a **fault-injectable** slot DAO, which is what
 *    makes "one pass is one unit" measurable rather than argued;
 *  * [create] / [saveRevision] — the two things that change what is stored: a Program's first graph, and
 *    a second immutable revision of it (the §6 change a supersession is read against);
 *  * [tableCounts] — the row count of **every** table, which is how "this pass wrote nothing" and "this
 *    pass created no Session" are measured rather than promised;
 *  * [slots] — the stored opportunities, read fresh, so no claim is about a value the pass returned.
 *
 * The fixture Programs carry no initial slots (a creation that schedules nothing is a real state: §27's
 * creation unit mentions initial slots, and §30 step 7 is the layer that decides them), so a suite starts
 * from a Program whose schedule is exactly what the Scheduler makes of it.
 *
 * [MovableClock] and [SequentialIds] are the lifecycle suite's own doubles: one clock and one id
 * generator, shared by every rig in this package, so "the clock a test moved" means the same thing in
 * all of them.
 */
internal class ProgramSchedulerRig(key: String = "s") {

    private val data: ProgramDataAccessRig = ProgramDataAccessRig(key)

    /** The engine, for direct SQL, row counts and the planted "missing revision" case. */
    val database: SqliteTestDatabase get() = data.database

    /** The zone a pass reads its dates in. Fixed, so the tests reason in dates rather than in offsets. */
    val zone: ZoneId = SchedulerFixture.ZONE

    /** A clock the test moves: the day a pass is made on is a value the test states. */
    val clock: MovableClock = MovableClock(SchedulerFixture.at(SchedulerFixture.ANCHOR))

    /** Deterministic identity, so a created slot's id is readable in a failure message. */
    val ids: SequentialIds = SequentialIds("scheduler")

    /** The faults a test plants, to prove a failed pass leaves the schedule exactly as it was. */
    val faults: SchedulerFaults = SchedulerFaults()

    /**
     * The schedule's persistence, over a slot DAO that can be made to fail.
     *
     * The wrapper is transparent until a fault is planted, so every read and write below goes through
     * SQLite exactly as production does — and the one claim that needs a failure (a pass that dies
     * part-way rolls back completely) is decided by the engine's own transaction.
     */
    val scheduleRepository: ProgramScheduleRepository = ProgramScheduleRepository(
        FaultableSlotDao(SqliteProgramWorkoutSlotDao(data.database), faults),
        data.sessionDao,
        data.pauseDao
    )

    val programRepository: ProgramRepository get() = data.programRepository

    val planRepository: ProgramPlanRepository get() = data.programPlanRepository

    /**
     * The session repository, for the one claim a scheduling pass makes about sessions: that it does
     * not touch them. A suite starts a session on an opportunity whose date has passed, so the miss the
     * pass records is provably independent of the attempt (§19, §20).
     */
    val sessionRepository: com.monkfitness.app.data.repository.WorkoutSessionRepository
        get() = data.workoutSessionRepository

    /** The behaviour under test, wired as the composition root wires it. */
    val scheduler: ProgramScheduler = ProgramScheduler(
        programRepository = data.programRepository,
        planRepository = data.programPlanRepository,
        scheduleRepository = scheduleRepository,
        clock = clock,
        idGenerator = ids,
        zone = zone,
        inTransaction = data.transaction
    )

    // ---------------------------------------------------------------- what the tests start from

    /**
     * Stores a Program with its first revision and **no** slots, at [anchor].
     *
     * The Program is `RUNNING` by default, which is the state a scheduled Program is in: its
     * `actualStartDate` is the anchor, so the anchor a pass reads is a fact the test stated rather than
     * a date the Scheduler invented. A `NOT_STARTED` Program with a `plannedStartDate` is the other
     * anchor shape, and the tests use it to prove that planning from a planned date starts nothing.
     */
    suspend fun create(
        key: String = "s",
        schedule: ProgramSchedule = SchedulerFixture.weekly(),
        duration: ProgramDuration = ProgramDuration.FixedDays(30),
        anchor: LocalDate = SchedulerFixture.ANCHOR,
        lifecycleStatus: LifecycleStatus = LifecycleStatus.RUNNING,
        plannedStartDate: LocalDate? = anchor,
        archivedAt: Instant? = null,
        days: List<ProgramDay> = SchedulerFixture.days("$key-d1")
    ): SchedulerGraph {
        val programId = ProgramId(SchedulerFixture.programId(key))
        val revision = revision(
            programId = programId,
            tag = "$key-d1",
            revisionNumber = 1,
            schedule = schedule,
            duration = duration,
            days = days
        )
        val program = Program(
            programId = programId,
            name = "Program $key",
            description = "the scheduling fixture $key",
            source = ProgramSource.USER,
            lifecycleStatus = lifecycleStatus,
            currentRevisionId = revision.revisionId,
            createdAt = SchedulerFixture.CREATED_AT,
            updatedAt = SchedulerFixture.CREATED_AT,
            plannedStartDate = plannedStartDate,
            actualStartDate = if (lifecycleStatus == LifecycleStatus.NOT_STARTED) null else {
                SchedulerFixture.at(anchor)
            },
            archivedAt = archivedAt
        )
        data.programRepository.createProgram(program, revision, emptyList())
        return SchedulerGraph(program, revision)
    }

    /**
     * Saves a **second** revision of [graph]'s Program: a new revision identity, new day identities and
     * new element identities, carrying whatever shape the test asked for (§6: a structural change is a
     * new immutable revision, never an edit).
     *
     * Nothing is deleted or rewritten: the revision it replaces keeps describing exactly what it
     * described, which is the fact the supersession suite re-reads afterwards.
     */
    suspend fun saveRevision(
        graph: SchedulerGraph,
        schedule: ProgramSchedule = graph.revision.schedule,
        duration: ProgramDuration = graph.revision.duration,
        days: List<ProgramDay> = SchedulerFixture.days("${graph.program.programId.value}-d2"),
        revisionNumber: Int = graph.revision.revisionNumber + 1
    ): ProgramRevision {
        val revision = revision(
            programId = graph.program.programId,
            tag = "${graph.program.programId.value}-d$revisionNumber",
            revisionNumber = revisionNumber,
            schedule = schedule,
            duration = duration,
            days = days
        )
        data.programPlanRepository.saveNewRevision(revision, SchedulerFixture.CREATED_AT)
        return revision
    }

    /** Replaces the Program's own row, so a suite can state a lifecycle, an archive stamp or a date. */
    suspend fun store(program: Program) = data.programRepository.updateProgram(program)

    // ---------------------------------------------------------------- what the tests read back

    /** The Program as stored, read through a fresh repository so nothing is a cache. */
    suspend fun stored(programId: ProgramId): Program =
        data.freshProgramRepository().programById(programId)
            ?: error("no Program '${programId.value}' is stored")

    /** The revision that currently describes [programId], read fresh. */
    suspend fun currentRevision(programId: ProgramId): ProgramRevision? =
        data.freshPlanRepository().currentRevision(programId)

    /** One revision by identity, read fresh — the way a superseded revision is inspected. */
    suspend fun revision(revisionId: RevisionId): ProgramRevision? =
        data.freshPlanRepository().revisionById(revisionId)

    /** How many revisions one Program has saved. */
    suspend fun revisionCount(programId: ProgramId): Int =
        data.freshPlanRepository().countRevisionsOf(programId)

    /** The stored opportunities of one Program, in plan order, with their attempts. */
    suspend fun slots(programId: ProgramId): List<WorkoutSlot> =
        scheduleRepository.slotsOfProgram(programId)

    /** Every pause interval of one Program, earliest first. */
    suspend fun pauses(programId: ProgramId): List<ProgramPause> =
        scheduleRepository.pausesOfProgram(programId)

    /** Opens a pause interval on [programId] and, when [until] is given, closes it again (§3). */
    suspend fun pause(
        programId: ProgramId,
        from: LocalDate,
        until: LocalDate? = null,
        pauseId: String = "pause-$programId"
    ) {
        scheduleRepository.addPause(
            ProgramPause(
                pauseId = com.monkfitness.app.domain.common.PauseId(pauseId),
                programId = programId,
                startedAt = SchedulerFixture.at(from)
            )
        )
        if (until != null) {
            scheduleRepository.closePause(
                com.monkfitness.app.domain.common.PauseId(pauseId),
                SchedulerFixture.at(until)
            )
        }
    }

    /**
     * The row count of every table of the migrated database.
     *
     * The strongest form of "this pass wrote nothing" available: a refusal, a `preview` and an inert
     * second pass must leave **all** of them where they were, so a write into any table — including one
     * a scheduler test would not think to look at — fails the assertion. It is also how "the Scheduler
     * created no Session" is measured: the session tables are in this list.
     */
    fun tableCounts(): Map<String, Int> = SchedulerFixture.TABLES.associateWith { table ->
        database.count(table)
    }

    /** Moves the clock to [on] and runs one persisting pass. */
    suspend fun plan(programId: ProgramId, on: LocalDate) = moveTo(on).let {
        scheduler.schedule(programId)
    }

    /** Moves the clock to [on] and runs one deciding, non-persisting pass. */
    suspend fun preview(programId: ProgramId, on: LocalDate) = moveTo(on).let {
        scheduler.preview(programId)
    }

    /** Moves the clock to the start of [on]. */
    fun moveTo(on: LocalDate): LocalDate {
        clock.instant = SchedulerFixture.at(on)
        return on
    }

    fun close() = data.close()

    // ---------------------------------------------------------------- the fixture's own builders

    /** One revision of the fixture's shape, with fresh identities for its days and elements. */
    private fun revision(
        programId: ProgramId,
        tag: String,
        revisionNumber: Int,
        schedule: ProgramSchedule,
        duration: ProgramDuration,
        days: List<ProgramDay>
    ): ProgramRevision = ProgramRevision(
        revisionId = RevisionId("revision-$tag"),
        programId = programId,
        revisionNumber = revisionNumber,
        mode = ProgramMode.MANUAL,
        duration = duration,
        schedule = schedule,
        days = days.mapIndexed { index, day ->
            day.copy(
                programDayId = ProgramDayId("day-$tag-${index + 1}"),
                exercises = day.exercises.mapIndexed { position, element ->
                    element.copy(programExerciseId = ProgramExerciseId("plan-ex-$tag-${index + 1}-$position"))
                }
            )
        },
        createdAt = SchedulerFixture.CREATED_AT
    )
}

/** One Program and the revision it points at, as a suite sets them up. */
internal data class SchedulerGraph(val program: Program, val revision: ProgramRevision) {

    val programId: ProgramId get() = program.programId
}

/** The faults a scheduler suite can plant in the DAO layer (§28's `SYSTEM_FAILURE`, §33's contract). */
internal class SchedulerFaults {

    /** When set, storing the opportunities a pass decided on fails — the "the write dies" case. */
    var failSlotInsert: Boolean = false
}

/**
 * The production slot DAO with one switchable failure: the insert.
 *
 * It exists so that "one pass is one unit" is decided by SQLite rather than by this suite's
 * bookkeeping: with the fault planted the pass must leave *no* trace at all — not the opportunities it
 * created, and not the statuses it had already recorded for the opportunities it reconciled.
 */
private class FaultableSlotDao(
    private val delegate: ProgramWorkoutSlotDao,
    private val faults: SchedulerFaults
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
 * The scheduler fixture's own shapes: the dates, the plan and the table census.
 *
 * The dates are stated, not computed from the production code: the Monday anchor, the Monday/Wednesday/
 * Friday rhythm and every window the suites assert are written out, so a test that agrees with the
 * Scheduler agrees about the *calendar* rather than about its own copy of the algorithm.
 */
internal object SchedulerFixture {

    /** The zone every fixture date is read in. */
    val ZONE: ZoneId = ZoneId.of("UTC")

    /** The fixture's Monday, and the anchor every week-based expectation is stated from. */
    val ANCHOR: LocalDate = LocalDate.parse("2026-09-14")

    val CREATED_AT: Instant = Instant.parse("2026-09-01T08:00:00Z")

    /** The start of [date] in [ZONE] — an instant whose date in the rig's zone is exactly [date]. */
    fun at(date: LocalDate): Instant = date.atStartOfDay(ZONE).toInstant()

    fun programId(key: String): String = "program-$key"

    /** The default rhythm: the three weekdays a user asking for three sessions a week picks. */
    fun weekly(): ProgramSchedule = ProgramSchedule.FixedWeekdays(
        setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
    )

    /**
     * The fixture's plan: a training day, a rest day and a training day, in that order (§20 — a rest day
     * may exist as a slot without a session, so it is part of the sequence the plan cycles through).
     */
    fun days(tag: String): List<ProgramDay> = listOf(
        ProgramDay(
            programDayId = ProgramDayId("day-$tag-1"),
            position = 1,
            type = ProgramDayType.TRAINING,
            name = "Day one",
            exercises = listOf(
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("plan-ex-$tag-1-1"),
                    exerciseId = "pushup",
                    prescription = RepPrescription(listOf(12, 10, 8)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED
                )
            )
        ),
        ProgramDay(
            programDayId = ProgramDayId("day-$tag-2"),
            position = 2,
            type = ProgramDayType.REST
        ),
        ProgramDay(
            programDayId = ProgramDayId("day-$tag-3"),
            position = 3,
            type = ProgramDayType.MOBILITY,
            name = "Day three",
            exercises = listOf(
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("plan-ex-$tag-3-1"),
                    exerciseId = "plank",
                    prescription = RepPrescription(listOf(30, 30)),
                    origin = ProgramExerciseOrigin.USER_AUTHORED
                )
            )
        )
    )

    /** A two-day plan: the same shape with the third day dropped, for a plan of a different length. */
    fun shortDays(tag: String): List<ProgramDay> = days(tag).take(2)

    /**
     * Every table the migrated database holds — the Program System's fifteen and the five retained
     * global ones.
     *
     * §30 step 15 inverted what "the pass wrote nothing" has to cover. It used to include the shipped
     * program's tables (`set_log`, `user_progress`, `program_day_state`), because a careless integration
     * could quietly leave a mark in one of them; those tables no longer exist, so the claim is now about
     * the whole schema that does — and the retained global tables are where a mark could still land.
     */
    val TABLES: List<String> = listOf(
        "program",
        "app_state",
        "program_revision",
        "program_day",
        "program_exercise",
        "program_workout_slot",
        "workout_session",
        "session_snapshot",
        "session_snapshot_exercise",
        "session_exercise",
        "program_set_log",
        "program_pause",
        "program_family_progression_state",
        "program_adaptive_decision_record",
        "adaptive_adjustment",
        // The retained global tables. §30 step 15 dropped the shipped program's five, so these are
        // what "the pass wrote nothing" still has to cover — and they are the ones a careless
        // integration could mark.
        "posture_session_progress",
        "body_weight_log",
        "meal_cycles",
        "meals",
        "shopping_items"
    )

}
