package com.monkfitness.app.domain.program

import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraph
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.data.repository.ProgramDaoFaults
import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.data.repository.ProgramPlanRepository
import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.usecase.ProgramEditorService
import com.monkfitness.app.domain.usecase.WorkoutGenerator

/**
 * The editor suite's rig: the data-access rig's real SQLite engine and production DAOs, plus the
 * [ProgramEditorService] built over them exactly as [com.monkfitness.app.di.AppContainer] builds it.
 * The clock and the id generator are shared with the service on purpose: the rig moves the clock to
 * state *when* a save happened, and its ids are readable in a failure message (`editor-001`).
 */
internal class ProgramEditorRig(key: String = "a") {

    private val data: ProgramDataAccessRig = ProgramDataAccessRig(key)

    /** The engine, for direct SQL and row counts. */
    val database: SqliteTestDatabase get() = data.database

    /**
     * A clock the test moves, because a revision's `createdAt` and a Program's `updatedAt` are facts.
     *
     * It starts *after* every stamp the fixture graph carries, so a save's own stamps are recognisable
     * in an assertion instead of coinciding with a value that was already there.
     */
    val clock: MovableClock = MovableClock(ProgramGraphFixture.COMPUTED)

    /** Deterministic identity, so a saved revision's day ids are readable in a failure message. */
    val ids: SequentialIds = SequentialIds("editor")

    /** The behaviour under test, wired as the composition root wires it. */
    val service: ProgramEditorService = ProgramEditorService(
        programRepository = data.programRepository,
        planRepository = data.programPlanRepository,
        clock = clock,
        idGenerator = ids,
        inTransaction = data.transaction
    )

    /** The user Program's graph: a running Program, its first revision and its initial slots. */
    val graph: ProgramGraph get() = data.graph

    /** The Standard Program's id, as the composition root declares it. */
    val standardProgramId: ProgramId get() = StandardProgram.programId

    val programRepository: ProgramRepository get() = data.programRepository
    val planRepository: ProgramPlanRepository get() = data.programPlanRepository
    val scheduleRepository: ProgramScheduleRepository get() = data.programScheduleRepository

    /** The faults a test plants to prove a failed save leaves nothing behind. */
    val faults: ProgramDaoFaults get() = data.faults

    /** Creates the user Program's whole graph — including its initial slots — as a creation would. */
    suspend fun createGraph() = data.createGraph()

    /**
     * Creates the built-in Standard Program: the fixture plan, the built-in source, its stable identity,
     * and its own revision/day/element identities (two Programs may not share plan rows).
     *
     * Production seeds this later with the app's own content; the rig seeds the smallest Program that
     * makes the "a built-in Program is not edited in place" rules measurable.
     */
    suspend fun createStandardProgram() {
        val standard = graph.program.copy(
            programId = standardProgramId,
            name = StandardProgram.NAME,
            description = "the app's built-in program",
            source = ProgramSource.STANDARD,
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            actualStartDate = null
        )
        val revision = graph.revision.copy(
            revisionId = RevisionId("revision-standard"),
            programId = standardProgramId,
            days = graph.revision.days.map { day ->
                day.copy(
                    programDayId = ProgramDayId("${day.programDayId.value}-standard"),
                    exercises = day.exercises.map { element ->
                        element.copy(
                            programExerciseId = ProgramExerciseId(
                                "${element.programExerciseId.value}-standard"
                            )
                        )
                    }
                )
            }
        )
        data.programRepository.createProgram(
            standard.copy(currentRevisionId = revision.revisionId),
            revision,
            emptyList()
        )
    }

    /** The Program as stored, read through a fresh repository so nothing is a cache. */
    suspend fun stored(programId: ProgramId): Program =
        data.freshProgramRepository().programById(programId)
            ?: error("no Program '${programId.value}' is stored")

    /** The revision that currently describes [programId]'s plan, read fresh. */
    suspend fun currentRevision(programId: ProgramId): ProgramRevision? =
        data.freshPlanRepository().currentRevision(programId)

    /** One revision by identity, read fresh — the way a superseded revision is inspected. */
    suspend fun revision(revisionId: RevisionId): ProgramRevision? =
        data.freshPlanRepository().revisionById(revisionId)

    /** How many revisions one Program has saved. */
    suspend fun revisionCount(programId: ProgramId): Int =
        data.freshPlanRepository().countRevisionsOf(programId)

    /** The slots of one Program, with their attempts — the scheduler's rows, as the editor must leave them. */
    suspend fun slots(programId: ProgramId): List<WorkoutSlot> =
        data.programScheduleRepository.slotsOfProgram(programId)

    /**
     * The row count of every table of the migrated database.
     *
     * The strongest form of "this save wrote nothing" available: a no-op save must leave all of them
     * where they were, so a write into any table — including one no editor test would think to look at
     * — fails the assertion.
     */
    fun tableCounts(): Map<String, Int> = TABLES.associateWith { table -> database.count(table) }

    /** The app's own exercise library, as the generator defines it — the metadata the editor must not touch. */
    fun library(): List<Exercise> = WorkoutGenerator().getExerciseLibrary()

    fun close() = data.close()

    private companion object {

        /**
         * Every table the migrated database holds: the Program System's fifteen target tables and the
         * five retained global ones.
         *
         * The census has to cover the whole schema that exists, because a careless editor integration
         * could quietly leave a mark in any of it — and the retained tables are where a mark could
         * still land.
         */
        val TABLES: List<String> = listOf(
            // the Program System target schema
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
            // the retained global tables the app still holds — where this census's "wrote nothing"
            // claim still has something to cover
            "posture_session_progress",
            "body_weight_log",
            "meal_cycles",
            "meals",
            "shopping_items"
        )
    }
}
