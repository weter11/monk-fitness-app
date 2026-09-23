package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraph
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.MovableClock
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.SchedulerFixture
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.prescription.RepPrescription
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Save suite's rig: the data-access rig's real SQLite engine and production DAOs, with the editor,
 * the Scheduler and [ProgramSaveService] built over them exactly as `AppContainer` builds the graph.
 *
 * Everything a Save test needs to state is reachable from here:
 *
 *  * [service] — the behaviour under test, wired with the same six collaborators the composition root
 *    passes, over a [MovableClock] and a [SequentialIds] generator so every date and identity in a
 *    test is a value the test chose;
 *  * [editor] and [scheduler] — the two answers the orchestration composes, also reachable directly,
 *    which is how *"the stored slots are the Scheduler's own decision"* is measured by recomputing it
 *    rather than by trusting a second copy of the expectation;
 *  * [createGraph] and [storedProgram] — the fixture Program (a running, anchored, M/W/F plan with
 *    three stored opportunities) an edit reconciles, and fresh reads of whatever a save wrote;
 *  * [tableCounts] and [faults] — the whole-table census and the planted engine failures, which is
 *    how *"a failure at any leg leaves no partially created Program"* is decided by the transaction
 *    rather than argued from the code's shape.
 */
internal class ProgramSaveRig(private val key: String = "s") {

    private val data: ProgramDataAccessRig = ProgramDataAccessRig(key)

    /** The engine, for direct SQL and row counts. */
    val database: SqliteTestDatabase get() = data.database

    /** The calendar every date is read in — the Scheduler fixture's own, so `today` is a stated day. */
    val zone: ZoneId = SchedulerFixture.ZONE

    /**
     * A clock the test moves. It starts at the graph fixture's `COMPUTED` instant, so *today* for a
     * creation is `2026-09-21` in [zone] — a date a test can write out rather than compute.
     */
    val clock: MovableClock = MovableClock(ProgramGraphFixture.COMPUTED)

    /** Deterministic identity, so every minted id is readable in a failure message. */
    val ids: SequentialIds = SequentialIds("save")

    /** §7's editor, wired as the composition root wires it. */
    val editor: ProgramEditorService = ProgramEditorService(
        programRepository = data.programRepository,
        planRepository = data.programPlanRepository,
        clock = clock,
        idGenerator = ids,
        inTransaction = data.transaction
    )

    /** §20's Scheduler, wired as the composition root wires it. */
    val scheduler: ProgramScheduler = ProgramScheduler(
        programRepository = data.programRepository,
        planRepository = data.programPlanRepository,
        scheduleRepository = data.programScheduleRepository,
        clock = clock,
        idGenerator = ids,
        zone = zone,
        inTransaction = data.transaction
    )

    /** The behaviour under test. */
    val service: ProgramSaveService = ProgramSaveService(
        editor = editor,
        programRepository = data.programRepository,
        scheduler = scheduler,
        clock = clock,
        zone = zone,
        inTransaction = data.transaction
    )

    /** The faults a test plants to prove a failed leg leaves nothing behind. */
    val faults get() = data.faults

    /** The graph's persistence — reachable so a test can compose a *second* service over the same rows. */
    val programRepository get() = data.programRepository
    val planRepository get() = data.programPlanRepository
    val scheduleRepository get() = data.programScheduleRepository
    val transaction get() = data.transaction

    /**
     * Leaves the fixture graph's first opportunity as **finished history**: a real session with
     * confirmed sets on it (the way attempts are actually recorded — §23 keeps the link on the
     * session rows, and `WorkoutSlot` refuses a COMPLETED status with no attempt), and the slot's
     * status recorded as COMPLETED with the fixture's own finish stamp.
     *
     * @return the slot, read back — the value reconciliation is asserted against.
     */
    suspend fun completeFirstOpportunity(): WorkoutSlot {
        val slot = data.graph.slotFor(1)
        data.startSessionWithSets(ProgramGraphFixture.session(key, slot))
        data.programScheduleRepository.recordSlotOutcome(
            slot.slotId,
            com.monkfitness.app.domain.program.SlotStatus.COMPLETED,
            ProgramGraphFixture.FINISHED
        )
        return data.programScheduleRepository.slotById(slot.slotId)
            ?: error("the completed opportunity vanished")
    }

    /** *Today*, exactly as this rig's clock and calendar read it — the creation default. */
    fun today(): LocalDate = clock.now().atZone(zone).toLocalDate()

    // ---------------------------------------------------------------- the drafts a test saves

    /**
     * A savable **create** draft: a name, one training day, one exercise — and whatever schedule the
     * caller states, defaulting to the editor's own `FlexiblePerWeek(3)`.
     */
    fun draftOf(
        name: String,
        schedule: ProgramSchedule = ProgramSchedule.FlexiblePerWeek(3)
    ): ProgramEditorDraft = ProgramEditorDraft(
        name = name,
        schedule = schedule,
        days = listOf(
            ProgramDay(
                programDayId = ProgramDayId("draft-day-1"),
                position = 1,
                type = ProgramDayType.TRAINING,
                name = "Push day",
                exercises = listOf(
                    ProgramExercise(
                        programExerciseId = com.monkfitness.app.domain.common
                            .ProgramExerciseId("draft-ex-1"),
                        exerciseId = "pushup",
                        prescription = RepPrescription(listOf(5)),
                        origin = ProgramExerciseOrigin.USER_AUTHORED
                    )
                )
            )
        )
    )

    // ---------------------------------------------------------------- what the tests start from

    /** Creates the fixture's whole graph — a RUNNING Program, its plan and its three opportunities. */
    suspend fun createGraph() = data.createGraph()

    /** The fixture graph: its Program, its first revision, its stored slots. */
    val graph: ProgramGraph get() = data.graph

    // ---------------------------------------------------------------- what the tests read back

    /** The Program as stored, read through a fresh repository so nothing is a cache. */
    suspend fun storedProgram(programId: ProgramId): Program =
        data.freshProgramRepository().programById(programId)
            ?: error("no Program '${programId.value}' is stored")

    /** The revision that currently describes [programId]'s plan, read fresh. */
    suspend fun currentRevision(programId: ProgramId): ProgramRevision? =
        data.freshPlanRepository().currentRevision(programId)

    /** How many revisions one Program has saved. */
    suspend fun revisionCount(programId: ProgramId): Int =
        data.freshPlanRepository().countRevisionsOf(programId)

    /** One Program's opportunities, in storage order. */
    suspend fun slots(programId: ProgramId): List<WorkoutSlot> =
        data.programScheduleRepository.slotsOfProgram(programId)

    /** The opportunities one **revision** presents — how "the new revision is scheduled" is read. */
    suspend fun slotsOfRevision(revisionId: RevisionId): List<WorkoutSlot> =
        data.programScheduleRepository.slotsOfRevision(revisionId)

    /** One opportunity by identity — an existing row is never deleted, so this must not go null. */
    suspend fun slot(slotId: com.monkfitness.app.domain.common.SlotId): WorkoutSlot? =
        data.programScheduleRepository.slotById(slotId)

    /** The selection row itself — a creation must leave it untouched. */
    suspend fun selection() = data.appStateRepository.state()

    /** Makes [programId] the selection through the state row, so *"a creation does not clear it"* is measurable. */
    suspend fun select(programId: ProgramId) {
        data.appStateRepository.save(
            com.monkfitness.app.domain.program.AppState(selectedProgramId = programId)
        )
    }

    /** The row count of every table — the strongest form of *"this save wrote nothing"*. */
    fun tableCounts(): Map<String, Int> = SchedulerFixture.TABLES.associateWith { table ->
        database.count(table)
    }

    /** Closes the engine. */
    fun close() = data.close()
}
