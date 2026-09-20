package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramDaoFaults
import com.monkfitness.app.data.repository.ProgramGraph
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.MovableClock
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.workout.EffectiveExercise
import com.monkfitness.app.domain.workout.SessionRefusal
import com.monkfitness.app.domain.workout.SessionRuntimeResult
import com.monkfitness.app.domain.workout.WorkoutSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * The session-runtime suite's rig: the data-access rig's real SQLite engine and production DAOs, plus
 * the [SessionRuntime] built over them exactly as [com.monkfitness.app.di.AppContainer] builds it.
 *
 * Everything a session test needs to state is reachable from here:
 *
 *  * [runtime] — the behaviour under test, constructed with a [MovableClock] (so *when* a workout
 *    started, when a set was confirmed and when it finished are values the test chose) and a
 *    [SequentialIds] generator (so every minted session, occurrence and set id is readable in a failure
 *    message);
 *  * [database] — the engine, for the mutations a test performs *underneath* a session (rewriting the
 *    plan, re-pointing an element) and for the row-level readings that make "the snapshot is stored"
 *    and "nothing was written" measurable rather than argued;
 *  * [freshRuntime] — the same graph through **freshly constructed repositories**, which is what makes
 *    "this value came from storage" a claim about rows rather than about a cache: the runtime holds no
 *    state of its own, so a restart is exactly a second instance over the same database;
 *  * [graph] / [createProgram] — a Program with a real three-day plan and one opportunity per plan day,
 *    created through the production creation path;
 *  * [saveRevisedPlan] — a second, structurally different revision of it: a different element order, a
 *    different exercise and different prescriptions, with **new** day and element identities, which is
 *    the §6 Save a started session must be immune to;
 *  * [plantAdjustment] — one applied decision and the adjustment it produced, stored through the
 *    production adaptive path, so a presentation can be measured with an adjustment standing for it;
 *  * [tableCounts] — the row count of **every** table, which is how "a refused operation wrote nothing"
 *    and "a cancelled session did not touch its opportunity" are measured.
 *
 * The fixture's own times and ids are the data-access fixture's (`ProgramGraphFixture`), extended with
 * the moments a session needs — started, confirmed, finished — so the two suites describe the same
 * graph.
 */
internal class SessionRuntimeRig(key: String = "r", database: SqliteTestDatabase? = null) {

    private val data: ProgramDataAccessRig = ProgramDataAccessRig(key, database)

    /** The engine, for direct SQL, planted mutations and row counts. */
    val database: SqliteTestDatabase get() = data.database

    /** A clock the test moves: every moment the runtime stamps is a value the test stated. */
    val clock: MovableClock = MovableClock(SessionFixture.STARTED)

    /** Deterministic identity, so a minted session, occurrence or set id is readable in a failure. */
    val ids: SequentialIds = SequentialIds("sess")

    /** The faults a test plants, to prove a failed operation leaves no partial state. */
    val faults: ProgramDaoFaults get() = data.faults

    /** The behaviour under test, wired as the composition root wires it. */
    val runtime: SessionRuntime = SessionRuntime(
        planRepository = data.programPlanRepository,
        scheduleRepository = data.programScheduleRepository,
        sessionRepository = data.workoutSessionRepository,
        adaptiveRepository = data.programAdaptiveRepository,
        clock = clock,
        idGenerator = ids,
        zone = ZoneOffset.UTC,
        inTransaction = data.transaction
    )

    /** The Program, its first revision and its initial opportunities. */
    val graph: ProgramGraph get() = data.graph

    /** The same graph through fresh repositories: a restart, as far as any value here can tell. */
    fun freshRuntime(): SessionRuntime = SessionRuntime(
        planRepository = data.freshPlanRepository(),
        scheduleRepository = data.programScheduleRepository,
        sessionRepository = data.freshSessionRepository(),
        adaptiveRepository = data.freshAdaptiveRepository(),
        clock = clock,
        idGenerator = ids,
        zone = ZoneOffset.UTC,
        inTransaction = data.transaction
    )

    /** Persists the fixture's graph through the production creation path. */
    suspend fun createProgram() = data.createGraph()

    /** The plan day at [position] of the Program's first revision. */
    fun day(position: Int): ProgramDay = graph.days.single { it.position == position }

    /** The opportunity that presents the plan day at [position]. */
    fun slot(position: Int): WorkoutSlot = graph.slotFor(position)

    /** One slot's id, by its plan day's position. */
    fun slotId(position: Int): SlotId = slot(position).slotId

    // ---------------------------------------------------------------- the live plan a session outlives

    /**
     * Saves a **second** revision of the Program that changes exactly what a started session must be
     * immune to: the elements' order, the exercise of one of them, and every prescription — with new
     * day and element identities, because a revision's rows are new rows (§6).
     *
     * This is the §6 Save a live plan changes by, and it is what the snapshot suite runs underneath a
     * session.
     */
    suspend fun saveRevisedPlan(key: String = "r"): ProgramRevision {
        val revision = revisedRevision(key)
        data.programPlanRepository.saveNewRevision(revision, SessionFixture.REVISED)
        return revision
    }

    /** The revision [saveRevisedPlan] saves, without saving it — for a test that wants to inspect it. */
    fun revisedRevision(key: String = "r"): ProgramRevision {
        val tag = "$key-v2"
        val revisedDay = day(1).let { original ->
            ProgramDay(
                programDayId = ProgramDayId("day-$tag-1"),
                position = 1,
                type = ProgramDayType.TRAINING,
                name = "Push day, revised",
                exercises = listOf(
                    // The order is inverted and the first element's exercise is replaced: an exercise,
                    // an order and a prescription all change.
                    ProgramExercise(
                        programExerciseId = ProgramExerciseId("plan-ex-$tag-1-1"),
                        exerciseId = "pike_pushup",
                        prescription = RepPrescription(listOf(9, 9, 9)),
                        origin = ProgramExerciseOrigin.USER_AUTHORED,
                        isPinned = true
                    ),
                    ProgramExercise(
                        programExerciseId = ProgramExerciseId("plan-ex-$tag-1-2"),
                        exerciseId = "diamond_pushup",
                        prescription = RepPrescription(listOf(4, 4)),
                        origin = ProgramExerciseOrigin.GENERATED
                    )
                )
            )
        }
        return ProgramRevision(
            revisionId = RevisionId("revision-$tag"),
            programId = graph.program.programId,
            revisionNumber = graph.revision.revisionNumber + 1,
            mode = ProgramMode.MANUAL,
            duration = ProgramDuration.FixedDays(30),
            schedule = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.TUESDAY)),
            days = listOf(
                revisedDay,
                ProgramDay(programDayId = ProgramDayId("day-$tag-2"), position = 2, type = ProgramDayType.REST),
                ProgramDay(
                    programDayId = ProgramDayId("day-$tag-3"),
                    position = 3,
                    type = ProgramDayType.TRAINING,
                    name = "Repeat day, revised",
                    exercises = listOf(
                        ProgramExercise(
                            programExerciseId = ProgramExerciseId("plan-ex-$tag-3-1"),
                            exerciseId = "plank",
                            prescription = TimePrescription(listOf(20, 20)),
                            origin = ProgramExerciseOrigin.USER_AUTHORED
                        )
                    )
                )
            ),
            createdAt = SessionFixture.REVISED
        )
    }

    /** Rewrites the live plan's own rows underneath a session — the artificial, maximal mutation. */
    fun rewriteRevisionOneElements() {
        database.exec(
            "UPDATE `program_exercise` SET `exerciseId` = 'burpee', `perSetTargets` = '99,99', " +
                "`position` = 28 WHERE `programExerciseId` = 'plan-ex-r-1'"
        )
        database.exec(
            "UPDATE `program_exercise` SET `exerciseId` = 'squat', `perSetTargets` = '7' " +
                "WHERE `programExerciseId` = 'plan-ex-r-2'"
        )
    }

    /**
     * Stores one applied decision and the adjustment it produced, through the production adaptive path.
     *
     * [before] and [after] are the element as the revision presents it and as it should be presented,
     * so a test can state exactly what a captured presentation should say. [supersedes] is the
     * adjustment this one replaces, which is how `standingAdjustments` is exercised end to end.
     */
    suspend fun plantAdjustment(
        id: String,
        programExerciseId: ProgramExerciseId,
        before: EffectiveExercise,
        after: EffectiveExercise,
        at: Instant = SessionFixture.ADJUSTED,
        supersedes: String? = null,
        slotId: SlotId = slotId(1),
        outcome: DecisionOutcome = DecisionOutcome.APPLIED,
        action: AdaptiveAction = AdaptiveAction.PROGRESS
    ): AdaptiveAdjustment {
        val decision = AdaptiveDecision(
            decisionId = DecisionId("decision-$id"),
            programId = graph.program.programId,
            revisionId = graph.revision.revisionId,
            slotId = slotId,
            target = AdaptiveTarget.Exercise(programExerciseId.value),
            action = action,
            outcome = outcome,
            evidence = EvidenceLevel.STRONG,
            confidence = ConfidenceLevel.HIGH,
            recovery = RecoveryContext.FAVORABLE,
            decidedAt = at,
            adjustmentId = if (outcome == DecisionOutcome.APPLIED) AdjustmentId("adjustment-$id") else null
        )
        val adjustment = AdaptiveAdjustment(
            adjustmentId = AdjustmentId("adjustment-$id"),
            decisionId = decision.decisionId,
            slotId = slotId,
            before = before,
            after = after,
            createdAt = at,
            supersedesAdjustmentId = supersedes?.let { AdjustmentId("adjustment-$it") }
        )
        data.programAdaptiveRepository.persistDecision(decision, adjustment.takeIf { decision.isApplied })
        return adjustment
    }

    // ---------------------------------------------------------------- what the tests read back

    /** The stored session, read through a fresh repository — never a value a call returned. */
    suspend fun stored(sessionId: SessionId): WorkoutSession? =
        data.freshSessionRepository().sessionById(sessionId)

    /** The stored session, or a failure naming the id — for the suite's post-conditions. */
    suspend fun requireStored(sessionId: SessionId): WorkoutSession =
        stored(sessionId) ?: error("no session '${sessionId.value}' is stored")

    /** One opportunity as stored, read fresh. */
    suspend fun storedSlot(slotId: SlotId): WorkoutSlot =
        data.programScheduleRepository.slotById(slotId) ?: error("no slot '${slotId.value}' is stored")

    /** Every session id of one opportunity, in start order. */
    suspend fun sessionIdsOf(slotId: SlotId): List<String> =
        database.strings("SELECT `sessionId` FROM `workout_session` WHERE `slotId` = ? ORDER BY `startedAt` ASC, `sessionId` ASC", slotId.value)
            .map { it!! }

    /** Every session id in the database, in start order. */
    suspend fun allSessionIds(): List<String> =
        database.strings("SELECT `sessionId` FROM `workout_session` ORDER BY `startedAt` ASC, `sessionId` ASC")
            .map { it!! }

    /** The status stored for one session. */
    fun storedStatus(sessionId: SessionId): String? =
        database.scalar("SELECT `status` FROM `workout_session` WHERE `sessionId` = ?", sessionId.value)

    /** The captured presentation's elements, in presented order, as the rows that hold them. */
    fun capturedElements(sessionId: SessionId): List<Map<String, String?>> =
        database.rows(
            "SELECT `position`, `programExerciseId`, `exerciseId`, `prescriptionDimension`, `perSetTargets` " +
                "FROM `session_snapshot_exercise` WHERE `sessionId` = ? ORDER BY `position` ASC",
            sessionId.value
        )

    /** The adjustments a session captured, in application order, as stored. */
    fun capturedAdjustmentIds(sessionId: SessionId): List<String> =
        (database.scalar("SELECT `appliedAdjustmentIds` FROM `session_snapshot` WHERE `sessionId` = ?", sessionId.value) ?: "")
            .split(",")
            .filter { it.isNotBlank() }

    /** The confirmed sets of one occurrence, in set order, as rows. */
    fun storedSets(sessionExerciseId: String): List<Map<String, String?>> =
        database.rows(
            "SELECT `setIndex`, `completedReps`, `durationSeconds`, `performedAt` FROM `program_set_log` " +
                "WHERE `sessionExerciseId` = ? ORDER BY `setIndex` ASC",
            sessionExerciseId
        )

    /** The occurrences a session stored, in presentation order. */
    fun storedOccurrences(sessionId: SessionId): List<Map<String, String?>> =
        database.rows(
            "SELECT `sessionExerciseId`, `position`, `programExerciseId`, `exerciseId` " +
                "FROM `session_exercise` WHERE `sessionId` = ? ORDER BY `position` ASC",
            sessionId.value
        )

    /** The rows written for a session: the count of every table a session operation can write. */
    fun tableCounts(): Map<String, Int> = SESSION_TABLES.associateWith { database.count(it) }

    fun close() = data.close()

    companion object {

        /**
         * Every table a session operation can write, plus the ones it must not: the twelve the Program
         * System owns for a session's own graph and for the plan it reads, and the two Stage-1 adaptive
         * tables, so "nothing was written" is a claim about the whole database rather than about the
         * tables a test happened to think of.
         */
        val SESSION_TABLES: List<String> = listOf(
            "workout_session",
            "session_snapshot",
            "session_snapshot_exercise",
            "session_exercise",
            "program_set_log",
            "program_workout_slot",
            "program_adaptive_decision_record",
            "adaptive_adjustment",
            "program_family_progression_state",
            "program_revision",
            "program_day",
            "program_exercise",
            "program",
            // The retained global tables. §30 step 15 dropped the shipped program's four that this
            // census used to reach, so these are what "nothing was written" can still cover — and they
            // are where a careless write would still land.
            "posture_session_progress",
            "body_weight_log",
            "meal_cycles",
            "meals",
            "shopping_items"
        )
    }
}

/** The moments and identities a session suite states, so no expectation is a computed value. */
internal object SessionFixture {

    /** When the workout is started. */
    val STARTED: Instant = Instant.parse("2026-09-21T07:30:00Z")

    /** When the first set is confirmed. */
    val SET_ONE: Instant = Instant.parse("2026-09-21T07:40:00Z")

    /** When the second set is confirmed. */
    val SET_TWO: Instant = Instant.parse("2026-09-21T07:45:00Z")

    /** When the workout is finished or cancelled. */
    val FINISHED: Instant = Instant.parse("2026-09-21T08:20:00Z")

    /** When a §6 Save replaces the plan underneath a session. */
    val REVISED: Instant = Instant.parse("2026-09-21T09:00:00Z")

    /** When an adjustment is produced for an opportunity. */
    val ADJUSTED: Instant = Instant.parse("2026-09-21T07:00:00Z")

    /** Everything a result carries that a test asserts on, as one readable line. */
    fun describe(result: SessionRuntimeResult<*>): String = when (result) {
        is SessionRuntimeResult.Success -> "Success(${result.value})"
        is SessionRuntimeResult.Refused -> "Refused(${result.reason.message})"
        is SessionRuntimeResult.Failure -> "Failure(${result.cause})"
    }

    /** The refusal a result carries, failing the test when it is not a refusal. */
    fun refusalOf(result: SessionRuntimeResult<*>): SessionRefusal {
        if (result !is SessionRuntimeResult.Refused) {
            throw AssertionError("expected a refusal, got ${describe(result)}")
        }
        return result.reason
    }

    /** The value a result carries, failing the test when it is not a success. */
    fun <T> valueOf(result: SessionRuntimeResult<T>): T {
        if (result !is SessionRuntimeResult.Success) {
            throw AssertionError("expected a value, got ${describe(result)}")
        }
        return result.value
    }

    /** The cause a result carries, failing the test when it is not a failure. */
    fun failureValueOf(result: SessionRuntimeResult<*>): Throwable {
        if (result !is SessionRuntimeResult.Failure) {
            throw AssertionError("expected a failure, got ${describe(result)}")
        }
        return result.cause
    }
}

/** The two plan elements the fixture's first revision presents on its first day, as the plan writes them. */
internal object FixtureElements {

    val PUSHUP = EffectiveExercise(
        ProgramExerciseId("plan-ex-r-1"),
        "pushup",
        RepPrescription(listOf(12, 10, 8, 6))
    )

    val PIKE_PUSHUP = EffectiveExercise(
        ProgramExerciseId("plan-ex-r-2"),
        "pike_pushup",
        RepPrescription(listOf(8, 8))
    )
}
