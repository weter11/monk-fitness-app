package com.monkfitness.app.domain.program

import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraph
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import java.time.Instant

/**
 * The lifecycle suite's rig: the data-access rig's real SQLite engine and production DAOs, plus the
 * [ProgramLifecycleService] built over them exactly as [com.monkfitness.app.di.AppContainer] builds it.
 *
 * Everything a lifecycle test needs to state is reachable from here:
 *
 *  * [service] — the behaviour under test, constructed with a [MovableClock] and a [SequentialIds]
 *    generator so every timestamp and every new identity in a test is a value the test chose.
 *  * [database] and the rig's repositories — for creating the Programs and sessions a test starts
 *    from, and for reading the rows back through a second object so a claim is about storage and not
 *    about a cache.
 *  * [graph] and [standardGraph] — the two Programs every suite needs: a user Program with a real
 *    three-day plan, and the built-in Standard Program the §3 fallback selects.
 *
 * ### Why the Standard Program is seeded here and not in production
 *
 * §4's built-in Program is the fallback the selection moves to when the selected Program is deleted
 * (§3), and the delete path refuses to run without it ([StandardProgramNotSeeded]). A Program without
 * a plan is not a representable state, so this rig seeds one with the fixture's plan — which is what
 * a later stage will do with the app's own content. Production's seeding is §30 steps 6–10; the rig's
 * is the smallest graph that makes the rules testable.
 */
internal class ProgramLifecycleRig(key: String = "a") {

    /** The migrated engine, the production DAO implementations, and the seven repositories. */
    private val data: ProgramDataAccessRig = ProgramDataAccessRig(key)

    /** The engine, for direct SQL and row counts. */
    val database: SqliteTestDatabase = data.database

    /** A clock the test moves, because `actualStartDate` is a fact the test states (§3). */
    val clock: MovableClock = MovableClock(ProgramGraphFixture.STARTED)

    /** Deterministic identity, so a copied Program's id is readable in a failure message. */
    val ids: SequentialIds = SequentialIds("lifecycle")

    /** The user Program's graph: a running Program, its first revision and its initial slots. */
    val graph: ProgramGraph = data.graph

    /** The Standard Program's id, as the composition root declares it. */
    val standardProgramId: ProgramId = StandardProgram.programId

    /** The Standard Program's graph — same plan shape, built-in source, not yet started. */
    val standardGraph: ProgramGraph = standardGraph()

    /** The behaviour under test, wired as the composition root wires it. */
    val service: ProgramLifecycleService = ProgramLifecycleService(
        programRepository = data.programRepository,
        scheduleRepository = data.programScheduleRepository,
        sessionRepository = data.workoutSessionRepository,
        appStateRepository = data.appStateRepository,
        planRepository = data.programPlanRepository,
        clock = clock,
        idGenerator = ids,
        standardProgramId = standardProgramId,
        inTransaction = data.transaction
    )

    /** The data-access rig's repositories, for the reads a test makes outside the service. */
    val programRepository get() = data.programRepository
    val planRepository get() = data.programPlanRepository
    val scheduleRepository get() = data.programScheduleRepository
    val sessionRepository get() = data.workoutSessionRepository
    val appStateRepository get() = data.appStateRepository

    /** Creates the user Program's whole graph the way a creation path will. */
    suspend fun createGraph() = data.createGraph()

    /** Creates the Standard Program, so §3's fallback has something to select. */
    suspend fun createStandardProgram() =
        data.programRepository.createProgram(standardGraph.program, standardGraph.revision, emptyList())

    /**
     * The rows that prove a lifecycle, selection, rename, archive or planned-start change created no
     * revision: the count of the Program's revisions, read through the plan repository.
     */
    suspend fun revisionCount(programId: ProgramId): Int = data.programPlanRepository.countRevisionsOf(programId)

    /** The Program as stored, read through a fresh repository so nothing is a cache. */
    suspend fun stored(programId: ProgramId): Program =
        data.freshProgramRepository().programById(programId)
            ?: error("no Program '${programId.value}' is stored")

    /** The global state row, or `null` when none was written. */
    suspend fun state(): AppState? = data.appStateRepository.state()

    /** Whether the single state row names a selected Program — the §3 fallback's postcondition. */
    fun selectionIsAValidPointer(): Boolean =
        database.scalar("SELECT COUNT(*) FROM `app_state` WHERE `selectedProgramId` IS NOT NULL")
            ?.toInt() == 1

    /** Closes the engine. */
    fun close() = data.close()

    /** The built-in Program's graph: the fixture plan with a built-in source and the standard id. */
    private fun standardGraph(): ProgramGraph {
        val standard = graph.program.copy(
            programId = standardProgramId,
            name = StandardProgram.NAME,
            description = "the app's built-in program",
            source = ProgramSource.STANDARD,
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            actualStartDate = null
        )
        val revision = graph.revision.copy(
            // A new identity, because two Programs may not share one plan row (§6, §23): the
            // built-in Program's plan is the same structure, stored as its own revision.
            revisionId = com.monkfitness.app.domain.common.RevisionId("revision-standard"),
            programId = standardProgramId,
            days = graph.revision.days.map { day ->
                day.copy(
                    programDayId = com.monkfitness.app.domain.common.ProgramDayId(
                        "${day.programDayId.value}-standard"
                    ),
                    exercises = day.exercises.map { element ->
                        element.copy(
                            programExerciseId = com.monkfitness.app.domain.common.ProgramExerciseId(
                                "${element.programExerciseId.value}-standard"
                            )
                        )
                    }
                )
            }
        )
        val withRevision = standard.copy(currentRevisionId = revision.revisionId)
        return ProgramGraph(withRevision, revision, emptyList())
    }
}

/**
 * A clock a test moves: the instant it returns is a value the test sets, so "the Program started when
 * the user started it" is measured against an instant the test chose rather than against the wall
 * clock (§26 — the clock is injected; §3 — the start is factual).
 */
internal class MovableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/**
 * Deterministic identity: a counter with a tag, so a copied Program's id and its first revision's id
 * are both readable in a failure message and reproducible across runs (§26).
 */
internal class SequentialIds(private val tag: String = "id") : IdGenerator {

    var count: Int = 0
        private set

    override fun newId(): String {
        count += 1
        return "$tag-${count.toString().padStart(3, '0')}"
    }
}
