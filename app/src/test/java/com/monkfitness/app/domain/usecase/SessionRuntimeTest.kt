package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.AdaptiveCompletion
import com.monkfitness.app.domain.workout.AdaptiveOutcome
import com.monkfitness.app.domain.workout.SessionRefusal
import com.monkfitness.app.domain.workout.SessionRuntimeResult
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.WorkoutSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/**
 * The session runtime — §30 step 8 — against a real SQLite engine.
 *
 * The suite is organized by the questions §19 asks of an attempt, and every claim is measured on the
 * rows rather than on a value a call returned: the session is re-read through **freshly built
 * repositories** after each operation, the snapshot is compared to the presentation the start captured,
 * the set rows are read from `program_set_log`, and "nothing was written" is a census of every table.
 *
 * The two claims the brief singled out have their own sections. The **snapshot** section starts a
 * session, changes the live plan underneath it — a §6 Save with new identities, a rewrite of the plan's
 * own rows, and a superseding adjustment — and proves the restored presentation is the captured one.
 * The **occupancy** section proves §19's *"no more than one `IN_PROGRESS` attempt per slot"* at the
 * persistence boundary, including two starts that race for one opportunity across two connections.
 */
class SessionRuntimeTest {

    private val rig = SessionRuntimeRig("r")

    private val slot get() = rig.slotId(1)

    /** Starts the fixture's first opportunity and returns the session as the start produced it. */
    private suspend fun started(): WorkoutSession {
        rig.createProgram()
        return SessionFixture.valueOf(rig.runtime.startSession(slot))
    }

    /** Starts, then confirms one repetition set of the first occurrence. */
    private suspend fun startedWithOneSet(): Pair<WorkoutSession, SessionExerciseId> {
        val session = started()
        val occurrence = session.exercises.first().sessionExerciseId
        rig.clock.instant = SessionFixture.SET_ONE
        SessionFixture.valueOf(rig.runtime.confirmSet(session.sessionId, occurrence, completedReps = 12))
        return session to occurrence
    }

    // ================================================================ the identity triple (§19)

    @Test
    fun aStartedSessionIsBoundToOneProgramRevisionAndSlot() = runBlocking {
        rig.clock.instant = SessionFixture.STARTED

        val session = started()

        assertEquals("sess-001", session.sessionId.value)
        assertEquals(rig.graph.program.programId, session.programId)
        assertEquals(rig.graph.revision.revisionId, session.revisionId)
        assertEquals(slot, session.slotId)
        assertEquals(SessionStatus.IN_PROGRESS, session.status)
        assertEquals(SessionFixture.STARTED, session.startedAt)
        assertNull("an in-progress attempt has not finished", session.finishedAt)
        assertEquals(
            "and the snapshot it was started with is its own, for its own slot, Program and revision",
            session.sessionId,
            session.snapshot.sessionId
        )
        assertEquals(session.slotId, session.snapshot.workout.slotId)
        assertEquals(session.programId, session.snapshot.workout.programId)
        assertEquals(session.revisionId, session.snapshot.workout.revisionId)
        assertEquals(
            "the planned date it was scheduled for is preserved beside the actual start (§19)",
            rig.slot(1).plannedFor,
            session.snapshot.workout.plannedFor
        )
        assertEquals(1, rig.allSessionIds().size)
    }

    @Test
    fun startingCapturesThePresentedPlanInItsOwnOrderWithItsOwnPrescriptions() = runBlocking {
        val session = started()

        assertEquals(
            "the presentation is the plan day's elements, in the plan's order",
            listOf("pushup", "pike_pushup"),
            session.snapshot.workout.exercises.map { it.exerciseId }
        )
        assertEquals(
            "with each element's plan identity, per-set prescription included",
            listOf("plan-ex-r-1" to listOf(12, 10, 8, 6), "plan-ex-r-2" to listOf(8, 8)),
            session.snapshot.workout.exercises.map { it.programExerciseId.value to it.prescription.perSetTargets }
        )
        assertEquals(
            "and the session runs exactly those occurrences, in the same order",
            session.snapshot.workout.exercises.map { it.programExerciseId },
            session.exercises.map { it.programExerciseId }
        )
        assertEquals(
            "nothing was adjusted, so no adjustment is recorded as applied",
            emptyList<AdjustmentId>(),
            session.snapshot.workout.appliedAdjustmentIds
        )
        assertEquals(
            "the snapshot was taken at the moment the workout started, not after it",
            session.startedAt,
            session.snapshot.capturedAt
        )
    }

    // ================================================================ the immutable snapshot (§19)

    /**
     * The critical case of §30 step 8, run in three shapes at once: the plan's own rows are rewritten,
     * a whole new revision replaces it, and the restored session must be exactly what the start
     * captured.
     */
    @Test
    fun theRestoredPresentationIsDomainEquivalentToTheCapturedSnapshotAfterANewRevision() = runBlocking {
        val started = started()

        rig.rewriteRevisionOneElements()
        val revised = rig.saveRevisedPlan()

        val restored = SessionFixture.valueOf(rig.freshRuntime().restoreSession(started.sessionId))

        assertEquals(
            "the session is unchanged in every part — snapshot, occurrences and identity alike",
            started,
            restored
        )
        assertEquals(
            "the presented exercise is the one captured, not the path the plan now names",
            listOf("pushup", "pike_pushup"),
            restored.snapshot.workout.exercises.map { it.exerciseId }
        )
        assertEquals(
            "and the presentation is *byte for byte* the plan day's elements as they were presented",
            listOf("pushup" to listOf(12, 10, 8, 6), "pike_pushup" to listOf(8, 8)),
            restored.snapshot.workout.exercises.map { it.exerciseId to it.prescription.perSetTargets }
        )
        assertEquals(
            "in the order that was presented",
            listOf("plan-ex-r-1", "plan-ex-r-2"),
            restored.snapshot.workout.exercises.map { it.programExerciseId.value }
        )
        assertEquals(
            "the stored snapshot rows are the capture, not a recomputation",
            listOf("pushup" to "12,10,8,6", "pike_pushup" to "8,8"),
            rig.capturedElements(started.sessionId).map {
                it["exerciseId"] to it["perSetTargets"]
            }
        )
        assertEquals(
            "the live plan really did change underneath it",
            listOf("burpee", "squat"),
            rig.database.strings(
                "SELECT `exerciseId` FROM `program_exercise` WHERE `programExerciseId` IN ('plan-ex-r-1', 'plan-ex-r-2') ORDER BY `programExerciseId`"
            )
        )
        assertEquals(
            "and the Program's current revision is now the new one, while the session still names its own",
            revised.revisionId.value,
            rig.database.scalar("SELECT `currentRevisionId` FROM `program` WHERE `programId` = 'program-r'")
        )
        assertEquals(rig.graph.revision.revisionId, restored.revisionId)
        assertTrue(
            "the revised revision exists beside the first one and nothing about the plan was overwritten",
            rig.database.count("program_revision") == 2
        )
    }

    @Test
    fun aSessionOutlivesALaterRevisionDescribingSomethingElseEntirely() = runBlocking {
        val started = started()
        rig.saveRevisedPlan()

        val restored = SessionFixture.valueOf(rig.freshRuntime().restoreSession(started.sessionId))

        assertEquals(
            "the two elements of the revised plan are pike_pushup and diamond_pushup, in that order",
            listOf("pike_pushup", "diamond_pushup"),
            rig.database.strings(
                "SELECT `exerciseId` FROM `program_exercise` WHERE `programDayId` = 'day-r-v2-1' ORDER BY `position`"
            )
        )
        assertTrue(
            "while the session's own presentation is the one it was started with",
            restored.snapshot.workout.exercises.map { it.exerciseId } == listOf("pushup", "pike_pushup")
        )
        assertEquals(
            "and it still presents its own revision's elements, which is the only mapping that exists " +
                "between an opportunity and a presentation",
            listOf("plan-ex-r-1", "plan-ex-r-2"),
            restored.snapshot.workout.exercises.map { it.programExerciseId.value }
        )
    }

    // ================================================================ §16's composition

    @Test
    fun anAdjustmentStandingForTheOpportunityIsAppliedAndItsIdCaptured() = runBlocking {
        rig.createProgram()
        val adjustment = rig.plantAdjustment(
            id = "a1",
            programExerciseId = ProgramExerciseId("plan-ex-r-1"),
            before = FixtureElements.PUSHUP,
            after = FixtureElements.PUSHUP.copy(
                exerciseId = "knee_pushup",
                prescription = RepPrescription(listOf(8, 8, 8, 8))
            )
        )

        val session = SessionFixture.valueOf(rig.runtime.startSession(slot))

        assertEquals(
            "the adjusted element is presented as the adjustment says, per set",
            listOf("knee_pushup", "pike_pushup"),
            session.snapshot.workout.exercises.map { it.exerciseId }
        )
        assertEquals(
            listOf(listOf(8, 8, 8, 8), listOf(8, 8)),
            session.snapshot.workout.exercises.map { it.prescription.perSetTargets }
        )
        assertEquals(
            "the element the adjustment did not change is presented as the revision wrote it",
            listOf("plan-ex-r-1", "plan-ex-r-2"),
            session.snapshot.workout.exercises.map { it.programExerciseId.value }
        )
        assertEquals(
            "and the adjustment that was applied is captured by id, in presentation order",
            listOf(adjustment.adjustmentId),
            session.snapshot.workout.appliedAdjustmentIds
        )
        assertEquals(
            "the stored snapshot is what the presentation says, not a pointer at the adjustment",
            listOf("knee_pushup"),
            rig.capturedElements(session.sessionId).filter { it["position"] == "1" }.map { it["exerciseId"] }
        )
    }

    @Test
    fun aSupersedingAdjustmentDoesNotChangeWhatAStartedSessionPresented() = runBlocking {
        rig.createProgram()
        rig.plantAdjustment(
            id = "a1",
            programExerciseId = ProgramExerciseId("plan-ex-r-1"),
            before = FixtureElements.PUSHUP,
            after = FixtureElements.PUSHUP.copy(exerciseId = "knee_pushup", prescription = RepPrescription(listOf(8)))
        )
        val session = SessionFixture.valueOf(rig.runtime.startSession(slot))

        val superseding = rig.plantAdjustment(
            id = "a2",
            programExerciseId = ProgramExerciseId("plan-ex-r-1"),
            before = FixtureElements.PUSHUP.copy(exerciseId = "knee_pushup", prescription = RepPrescription(listOf(8))),
            after = FixtureElements.PUSHUP.copy(exerciseId = "diamond_pushup", prescription = RepPrescription(listOf(3))),
            supersedes = "a1",
            at = SessionFixture.REVISED
        )

        val restored = SessionFixture.valueOf(rig.freshRuntime().restoreSession(session.sessionId))
        assertEquals(
            "the started session still presents the adjustment that was in effect when it started",
            listOf("knee_pushup", "pike_pushup"),
            restored.snapshot.workout.exercises.map { it.exerciseId }
        )
        assertEquals(
            "and still names it, not the one that superseded it",
            listOf(AdjustmentId("adjustment-a1")),
            restored.snapshot.workout.appliedAdjustmentIds
        )
        assertEquals(listOf(listOf(8), listOf(8, 8)), restored.snapshot.workout.exercises.map { it.prescription.perSetTargets })

        // A *new* attempt at the same opportunity is a new capture, and there the superseding adjustment
        // is the one in effect — which is what makes the older session's freeze a fact rather than an
        // accident of the fixture. The first attempt is **cancelled** rather than completed, because a
        // completed opportunity is not attempted again (§19): a cancellation ends the attempt and leaves
        // the opportunity open.
        rig.clock.instant = SessionFixture.FINISHED
        SessionFixture.valueOf(rig.runtime.cancelSession(session.sessionId))
        rig.clock.instant = SessionFixture.FINISHED.plusSeconds(60)
        val second = SessionFixture.valueOf(rig.runtime.startSession(slot))

        assertEquals(
            "the next attempt presents the adjustment that supersedes the first one",
            listOf("diamond_pushup", "pike_pushup"),
            second.snapshot.workout.exercises.map { it.exerciseId }
        )
        assertEquals(
            listOf(superseding.adjustmentId),
            second.snapshot.workout.appliedAdjustmentIds
        )
        assertEquals(
            "and the first attempt is untouched by it",
            listOf("knee_pushup", "pike_pushup"),
            rig.requireStored(session.sessionId).snapshot.workout.exercises.map { it.exerciseId }
        )
    }

    @Test
    fun anAdjustmentThatNamesAnElementThisRevisionDoesNotPresentRefusesTheStart() = runBlocking {
        rig.createProgram()
        rig.plantAdjustment(
            id = "stale",
            programExerciseId = ProgramExerciseId("plan-ex-r-1"),
            before = FixtureElements.PUSHUP,
            after = FixtureElements.PUSHUP.copy(exerciseId = "knee_pushup")
        )
        // The element the adjustment changes belongs to a *later* revision, so this opportunity's own
        // revision cannot present it.
        rig.database.exec(
            "UPDATE `adaptive_adjustment` SET `programExerciseId` = 'plan-ex-nowhere', " +
                "`afterExerciseId` = 'knee_pushup' WHERE `adjustmentId` = 'adjustment-stale'"
        )
        val before = rig.tableCounts()

        val refusal = SessionFixture.refusalOf(rig.runtime.startSession(slot))

        assertTrue(
            "the start is refused rather than presenting the workout without the stored change: $refusal",
            refusal is SessionRefusal.StoredAdjustmentIsNotOfThisRevision
        )
        assertEquals("plan-ex-nowhere", (refusal as SessionRefusal.StoredAdjustmentIsNotOfThisRevision).programExerciseId.value)
        assertEquals("and nothing was written", before, rig.tableCounts())
    }

    // ================================================================ starting: the rules (§19, §20)

    @Test
    fun startingAnOpportunityThatIsNotStoredIsRefused() = runBlocking {
        rig.createProgram()

        val refusal = SessionFixture.refusalOf(rig.runtime.startSession(SlotId("slot-nowhere")))

        assertTrue(refusal is SessionRefusal.SlotNotFound)
        assertEquals(0, rig.allSessionIds().size)
    }

    @Test
    fun anOpportunityWhoseRevisionBelongsToAnotherProgramIsRefused() = runBlocking {
        rig.createProgram()
        // A second Program in the same database, with a revision of its own, and a slot of the first
        // Program that names that revision.
        rig.database.exec(
            "INSERT INTO `program` (`programId`, `name`, `description`, `source`, `lifecycleStatus`, " +
                "`currentRevisionId`, `createdAt`, `updatedAt`, `plannedStartDate`, `actualStartDate`, " +
                "`archivedAt`) VALUES ('program-x', 'Other', '', 'USER', 'RUNNING', 'revision-x', " +
                "1700000000000, 1700000000000, '2026-09-14', 1700000000000, NULL)"
        )
        rig.database.exec(
            "INSERT INTO `program_revision` (`revisionId`, `programId`, `revisionNumber`, `mode`, " +
                "`durationType`, `durationDays`, `scheduleType`, `scheduleWeekdays`, `createdAt`, " +
                "`scheduleSessionsPerWeek`) VALUES ('revision-x', 'program-x', 1, 'MANUAL', " +
                "'INDEFINITE', NULL, 'FIXED_WEEKDAYS', 'MONDAY', 1700000000000, NULL)"
        )
        rig.database.exec(
            "INSERT INTO `program_day` (`programDayId`, `revisionId`, `position`, `type`, `name`) " +
                "VALUES ('day-x-1', 'revision-x', 1, 'TRAINING', 'Other day')"
        )
        rig.database.exec(
            "INSERT INTO `program_workout_slot` (`slotId`, `programId`, `revisionId`, `programDayId`, " +
                "`plannedFor`, `status`, `completedAt`) VALUES ('slot-mismatch', 'program-r', " +
                "'revision-x', 'day-x-1', '2026-09-24', 'PLANNED', NULL)"
        )
        val before = rig.tableCounts()

        val refusal = SessionFixture.refusalOf(rig.runtime.startSession(SlotId("slot-mismatch")))

        assertTrue(
            "the triple is one fact: a Program, the revision it scheduled from and a day of that " +
                "revision — $refusal",
            refusal is SessionRefusal.RevisionIsOfAnotherProgram
        )
        assertEquals("program-x", (refusal as SessionRefusal.RevisionIsOfAnotherProgram).ownerProgramId.value)
        assertEquals("and nothing was written", before, rig.tableCounts())
    }

    @Test
    fun anOpportunityThatNamesAPlanDayTheRevisionLacksIsRefused() = runBlocking {
        rig.createProgram()
        // A day of a *later* revision: it exists, so the foreign key is satisfied, but the revision this
        // opportunity names does not present it — the state a re-pointed slot would be in, and one no
        // session may be started from.
        val revised = rig.saveRevisedPlan()
        rig.database.exec(
            "UPDATE `program_workout_slot` SET `programDayId` = ? WHERE `slotId` = 'slot-r-1'",
            revised.days.first().programDayId.value
        )
        val before = rig.tableCounts()

        val refusal = SessionFixture.refusalOf(rig.runtime.startSession(slot))

        assertTrue(
            "a slot presents a day of the revision it names, and nothing else: $refusal",
            refusal is SessionRefusal.SlotNamesAPlanDayTheRevisionDoesNotPresent
        )
        assertEquals(
            "day-r-v2-1",
            (refusal as SessionRefusal.SlotNamesAPlanDayTheRevisionDoesNotPresent).programDayId.value
        )
        assertEquals("and nothing was written", before, rig.tableCounts())
    }

    @Test
    fun anOpportunityThatWasAlreadyTakenIsRefused() = runBlocking {
        val session = started()
        rig.clock.instant = SessionFixture.FINISHED
        SessionFixture.valueOf(rig.runtime.finishSession(session.sessionId))

        val refusal = SessionFixture.refusalOf(rig.runtime.startSession(slot))

        assertTrue(
            "a completed opportunity is history; a second attempt would have to overwrite its stamp",
            refusal is SessionRefusal.SlotIsAlreadyCompleted
        )
        assertEquals("and the completed attempt is the only one stored", 1, rig.allSessionIds().size)
    }

    @Test
    fun aSupersededOpportunityIsRefused() = runBlocking {
        rig.createProgram()
        rig.database.exec("UPDATE `program_workout_slot` SET `status` = 'SUPERSEDED' WHERE `slotId` = 'slot-r-1'")

        val refusal = SessionFixture.refusalOf(rig.runtime.startSession(slot))

        assertTrue(
            "the user was not expected to train a withdrawn opportunity (§20)",
            refusal is SessionRefusal.SlotIsSuperseded
        )
    }

    @Test
    fun aRestDayHasNoWorkoutToStart() = runBlocking {
        rig.createProgram()
        val restSlot = rig.slotId(2)
        val before = rig.tableCounts()

        val refusal = SessionFixture.refusalOf(rig.runtime.startSession(restSlot))

        assertTrue(
            "a plan day that presents nothing is not a workout, and the runtime invents none to store it",
            refusal is SessionRefusal.PlanDayPresentsNothing
        )
        assertEquals("day-r-2", (refusal as SessionRefusal.PlanDayPresentsNothing).programDayId.value)
        assertEquals("a rest day keeps its slot and gains nothing", before, rig.tableCounts())
    }

    @Test
    fun aMissedOpportunityIsStillAttemptable() = runBlocking {
        rig.createProgram()
        rig.database.exec("UPDATE `program_workout_slot` SET `status` = 'MISSED' WHERE `slotId` = 'slot-r-1'")

        val session = SessionFixture.valueOf(rig.runtime.startSession(slot))

        assertEquals(SessionStatus.IN_PROGRESS, session.status)
        rig.clock.instant = SessionFixture.FINISHED
        val completion = SessionFixture.valueOf(rig.runtime.finishSession(session.sessionId))

        assertEquals(
            "training an opportunity late takes it: the completion records the outcome the runtime " +
                "decided on, and it is not derived from the date (§20, §19)",
            SlotStatus.COMPLETED,
            completion.slot.status
        )
        assertEquals(SessionFixture.FINISHED, completion.slot.completedAt)
    }

    // ================================================================ one attempt in progress (§19)

    @Test
    fun aSecondInProgressAttemptIsRefusedAndNamesTheOneThatHoldsTheSlot() = runBlocking {
        val first = started()
        val before = rig.tableCounts()

        val refusal = SessionFixture.refusalOf(rig.runtime.startSession(slot))

        assertTrue(
            "no more than one attempt per slot is in progress: $refusal",
            refusal is SessionRefusal.SlotIsAlreadyBeingWorkedOut
        )
        refusal as SessionRefusal.SlotIsAlreadyBeingWorkedOut
        assertEquals(slot, refusal.slotId)
        assertEquals("and the refusal names the attempt to resume", first.sessionId, refusal.sessionId)
        assertEquals("nothing of the refused attempt was stored", before, rig.tableCounts())
        assertEquals(listOf("sess-001"), rig.allSessionIds())
    }

    @Test
    fun severalAttemptsAreLegalAndTheOpportunityReportsThemAll() = runBlocking {
        val first = started()
        rig.clock.instant = SessionFixture.FINISHED
        SessionFixture.valueOf(rig.runtime.cancelSession(first.sessionId))
        rig.clock.instant = SessionFixture.FINISHED.plusSeconds(60)
        val second = SessionFixture.valueOf(rig.runtime.startSession(slot))

        assertEquals(
            "the second attempt is a row of its own",
            listOf("sess-001", second.sessionId.value),
            rig.sessionIdsOf(slot)
        )
        assertEquals(
            "and the opportunity reports both attempts (§19)",
            listOf("sess-001", second.sessionId.value),
            rig.storedSlot(slot).attempts.map { it.value }
        )
        assertEquals(
            "with each attempt keeping the status it ended in",
            listOf("CANCELLED", "IN_PROGRESS"),
            rig.database.strings(
                "SELECT `status` FROM `workout_session` WHERE `slotId` = 'slot-r-1' ORDER BY `startedAt` ASC, `sessionId` ASC"
            )
        )
        assertEquals(
            "and the opportunity is still open, because neither attempt took it",
            SlotStatus.PLANNED,
            rig.storedSlot(slot).status
        )
    }

    @Test
    fun aCancelledAttemptLeavesTheOpportunityOpenForTheNextOne() = runBlocking {
        val first = started()
        rig.clock.instant = SessionFixture.FINISHED
        SessionFixture.valueOf(rig.runtime.cancelSession(first.sessionId))

        val second = SessionFixture.valueOf(rig.runtime.startSession(slot))

        assertEquals(SessionStatus.IN_PROGRESS, second.status)
        assertEquals(
            "both attempts are kept, one cancelled and one running",
            listOf("sess-001", second.sessionId.value),
            rig.sessionIdsOf(slot)
        )
    }

    /**
     * The brief's race, on the instrument that can actually run it: two connections over one file
     * database, each with its own runtime, both starting the same opportunity at the same moment.
     *
     * Exactly one may succeed. The other is either refused by the statement's own predicate (it saw the
     * committed row) or rejected by the engine's writer lock (it did not) — both are the same answer to
     * the same question, and the test asserts the invariant rather than which of the two happened, since
     * which one it is depends on real timing.
     */
    @Test
    fun twoStartsRacingForOneOpportunityDoNotBothSucceed() = runBlocking {
        val file = Files.createTempFile("session-runtime-race", ".sqlite").toFile()
        file.delete()
        val writer = SqliteTestDatabase.at(file.absolutePath)
        try {
            ProgramDataAccessRig.migrate(writer)
            val first = SessionRuntimeRig("r", writer)
            first.createProgram()

            val reader = SqliteTestDatabase.at(file.absolutePath)
            val second = SessionRuntimeRig("r", reader)
            try {
                val outcomes = racingStarts(first, second)

                assertEquals(
                    "two starts racing for one opportunity do not both succeed: " +
                        outcomes.map { SessionFixture.describe(it) },
                    1,
                    outcomes.count { it is SessionRuntimeResult.Success }
                )
                assertEquals(
                    "and the database holds exactly one attempt, in progress",
                    1,
                    writer.count("workout_session")
                )
                assertEquals(
                    "IN_PROGRESS",
                    writer.scalar("SELECT `status` FROM `workout_session` WHERE `slotId` = 'slot-r-1'")
                )
                assertEquals(
                    "with the whole graph of exactly one attempt stored",
                    listOf(1, 1, 2, 2),
                    listOf("workout_session", "session_snapshot", "session_snapshot_exercise", "session_exercise")
                        .map { writer.count(it) }
                )
            } finally {
                reader.close()
            }
        } finally {
            writer.close()
            file.delete()
        }
    }

    /** Both runtimes start the same opportunity as soon as they are told to, on their own threads. */
    private suspend fun racingStarts(
        first: SessionRuntimeRig,
        second: SessionRuntimeRig
    ): List<SessionRuntimeResult<WorkoutSession>> = withContext(Dispatchers.IO) {
        val start = java.util.concurrent.CountDownLatch(1)
        listOf(first, second).map { rig ->
            async(Dispatchers.IO) {
                start.await()
                rig.runtime.startSession(SlotId("slot-r-1"))
            }
        }.also { start.countDown() }.awaitAll()
    }

    // ================================================================ confirming sets (§27)

    @Test
    fun aConfirmedSetIsAutosavedImmediatelyAsTheNextRow() = runBlocking {
        val (session, occurrence) = startedWithOneSet()
        rig.clock.instant = SessionFixture.SET_TWO

        val after = SessionFixture.valueOf(
            rig.runtime.confirmSet(session.sessionId, occurrence, completedReps = 10)
        )

        assertEquals(
            "the set was stored, not buffered: the rows are there",
            listOf(1 to "12", 2 to "10"),
            rig.storedSets(occurrence.value).map { it["setIndex"]!!.toInt() to it["completedReps"]!! }
        )
        assertEquals(
            "the position follows from the stored rows, so a gap cannot be produced by confirming",
            listOf(1, 2),
            after.exercises.first().results.map { it.setIndex }
        )
        assertEquals(SessionFixture.SET_ONE, after.exercises.first().results.first().performedAt)
        assertEquals(SessionFixture.SET_TWO, after.exercises.first().results.last().performedAt)
    }

    @Test
    fun aTimedOccurrenceLogsSecondsAndARepOccurrenceLogsRepetitions() = runBlocking {
        val session = started()
        val occurrence = session.exercises.first().sessionExerciseId
        val secondOccurrence = session.exercises.last().sessionExerciseId

        // The second element of the fixture's first day is prescribed in repetitions (8/8), so seconds
        // are refused for it; the timed case is exercised on the third plan day, whose plank is 30/30/45.
        val refusal = SessionFixture.refusalOf(
            rig.runtime.confirmSet(session.sessionId, secondOccurrence, durationSeconds = 30)
        )
        assertTrue(refusal is SessionRefusal.SetIsNotInThePrescribedUnit)
        assertEquals("and a refused set leaves no row", 0, rig.storedSets(secondOccurrence.value).size)
        assertTrue(
            "the occurrence it *is* prescribed for accepts a set",
            SessionFixture.valueOf(
                rig.runtime.confirmSet(session.sessionId, occurrence, completedReps = 12)
            ).exercises.first().results.size == 1
        )
        assertEquals(
            "logged in repetitions, with no seconds",
            listOf(12 to "0"),
            rig.storedSets(occurrence.value).map { it["completedReps"]!!.toInt() to it["durationSeconds"]!! }
        )
    }

    @Test
    fun aTimedElementIsLoggedInSeconds() = runBlocking {
        rig.createProgram()
        // The third plan day presents a timed element: pushup 5, pushup 5, plank 30/30/45.
        val session = SessionFixture.valueOf(rig.runtime.startSession(rig.slotId(3)))
        val plank = session.exercises.single { it.exerciseId == "plank" }
        assertEquals("plank is prescribed in time", 3, plank.prescription.perSetTargets.size)

        val after = SessionFixture.valueOf(
            rig.runtime.confirmSet(session.sessionId, plank.sessionExerciseId, durationSeconds = 30)
        )

        val set = after.exercises.single { it.exerciseId == "plank" }.results.single()
        assertEquals(30, set.durationSeconds)
        assertEquals("a timed set carries no repetitions", 0, set.completedReps)
    }

    @Test
    fun aSetIsRefusedForAnOccurrenceOfAnotherSessionAndForAFinishedOne() = runBlocking {
        val first = started()
        val beforeFinish = rig.tableCounts()

        val unknown = SessionFixture.refusalOf(
            rig.runtime.confirmSet(first.sessionId, SessionExerciseId("sess-999"), completedReps = 1)
        )
        assertTrue(unknown is SessionRefusal.OccurrenceIsNotOfThisSession)
        assertEquals("nothing was written", beforeFinish, rig.tableCounts())

        rig.clock.instant = SessionFixture.FINISHED
        SessionFixture.valueOf(rig.runtime.finishSession(first.sessionId))
        val afterFinish = rig.tableCounts()
        val late = SessionFixture.refusalOf(
            rig.runtime.confirmSet(first.sessionId, first.exercises.first().sessionExerciseId, completedReps = 1)
        )
        assertTrue(
            "a finished attempt takes no more sets, and a cancelled one takes none either: $late",
            late is SessionRefusal.SessionIsNotInProgress
        )
        assertEquals(SessionStatus.COMPLETED, (late as SessionRefusal.SessionIsNotInProgress).status)
        assertEquals("nothing was written into a finished attempt", afterFinish, rig.tableCounts())
        assertEquals(0, rig.storedSets(first.exercises.first().sessionExerciseId.value).size)
    }

    @Test
    fun aSetThatIsNotAnAmountOfWorkIsRefused() = runBlocking {
        val session = started()
        val occurrence = session.exercises.first().sessionExerciseId

        val nothing = SessionFixture.refusalOf(rig.runtime.confirmSet(session.sessionId, occurrence))
        assertTrue(nothing is SessionRefusal.SetIsNotInThePrescribedUnit)
        assertEquals("a set that was not performed has no row at all (§12)", 0, rig.storedSets(occurrence.value).size)

        val both = SessionFixture.refusalOf(
            rig.runtime.confirmSet(session.sessionId, occurrence, completedReps = 5, durationSeconds = 5)
        )
        assertTrue(
            "a set is measured in one unit, and zero is not an amount",
            both is SessionRefusal.SetIsNotInThePrescribedUnit
        )
        assertEquals(0, rig.storedSets(occurrence.value).size)
    }

    @Test
    fun aSkippedOccurrenceTakesNoSet() = runBlocking {
        rig.createProgram()
        // The runtime starts no skipped occurrence — skipping is not one of the five operations — so the
        // case is planted through the persistence path to prove the rule is enforced where it can occur.
        val started = SessionFixture.valueOf(rig.runtime.startSession(slot))
        rig.database.exec(
            "UPDATE `session_exercise` SET `skipped` = 1 WHERE `sessionExerciseId` = ?",
            started.exercises.first().sessionExerciseId.value
        )

        val refusal = SessionFixture.refusalOf(
            rig.runtime.confirmSet(started.sessionId, started.exercises.first().sessionExerciseId, completedReps = 5)
        )

        assertTrue(refusal is SessionRefusal.OccurrenceWasSkipped)
        assertEquals(0, rig.tableCounts()["program_set_log"])
    }

    @Test
    fun setPositionsAreNeverRenumberedAndAGapIsRefusedWhenLoading() = runBlocking {
        val (session, occurrence) = startedWithOneSet()
        rig.clock.instant = SessionFixture.SET_TWO
        SessionFixture.valueOf(rig.runtime.confirmSet(session.sessionId, occurrence, completedReps = 10))
        rig.database.exec("DELETE FROM `program_set_log` WHERE `sessionExerciseId` = ? AND `setIndex` = 1", occurrence.value)

        val failure = SessionFixture.failureValueOf(rig.runtime.restoreSession(session.sessionId))

        assertTrue(
            "a gap in confirmed sets is invalid persisted data rather than a workout with a missing " +
                "set: ${failure.message}",
            failure.message!!.contains("[2]")
        )
        assertTrue(
            "and the loader invents nothing in its place: ${failure.message}",
            !failure.message!!.contains("[0,")
        )
    }

    // ================================================================ Back (§19)

    @Test
    fun leavingTheWorkoutScreenWritesNothingAndTheAttemptSurvives() = runBlocking {
        val (session, occurrence) = startedWithOneSet()
        val before = rig.tableCounts()

        // Coming back to a workout is a restore: a fresh runtime over fresh repositories, exactly as a
        // recreated screen or a restarted process would do it.
        val restored = SessionFixture.valueOf(rig.freshRuntime().restoreSession(session.sessionId))

        assertEquals("the attempt is still in progress", SessionStatus.IN_PROGRESS, restored.status)
        assertNull(restored.finishedAt)
        assertEquals(
            "with the set it had confirmed",
            listOf(12),
            restored.exercises.first().results.map { it.completedReps }
        )
        assertEquals(
            "the opportunity was not completed, missed, superseded or otherwise touched by leaving",
            before,
            rig.tableCounts()
        )
        assertEquals(SlotStatus.PLANNED, rig.storedSlot(slot).status)

        rig.clock.instant = SessionFixture.SET_TWO
        val after = SessionFixture.valueOf(rig.runtime.confirmSet(session.sessionId, occurrence, completedReps = 10))
        assertEquals("and the attempt can simply be continued", 2, after.exercises.first().results.size)
    }

    // ================================================================ Cancel (§19)

    @Test
    fun aCancelledAttemptKeepsItsHistoryAndDoesNotTakeTheOpportunity() = runBlocking {
        val (session, occurrence) = startedWithOneSet()
        val revisionRows = rig.database.rows("SELECT * FROM `program_exercise` ORDER BY `programExerciseId`")
        rig.clock.instant = SessionFixture.FINISHED

        val cancelled = SessionFixture.valueOf(rig.runtime.cancelSession(session.sessionId))

        assertEquals(SessionStatus.CANCELLED, cancelled.status)
        assertEquals(SessionFixture.FINISHED, cancelled.finishedAt)
        assertEquals(
            "the work it observed stays recorded (§12)",
            listOf(12),
            cancelled.exercises.first().results.map { it.completedReps }
        )
        assertEquals(1, rig.storedSets(occurrence.value).size)
        assertEquals(
            "the opportunity is left exactly as it was: not completed, and not stamped",
            SlotStatus.PLANNED,
            rig.storedSlot(slot).status
        )
        assertNull(rig.storedSlot(slot).completedAt)
        assertEquals("the plan is untouched by a cancellation", revisionRows, rig.database.rows("SELECT * FROM `program_exercise` ORDER BY `programExerciseId`"))
        assertEquals("CANCELLED", rig.storedStatus(session.sessionId))
    }

    @Test
    fun aCancelledAttemptCanNeverBecomeACompletedOne() = runBlocking {
        val session = started()
        rig.clock.instant = SessionFixture.FINISHED
        SessionFixture.valueOf(rig.runtime.cancelSession(session.sessionId))
        val afterCancel = rig.tableCounts()

        val refusal = SessionFixture.refusalOf(rig.runtime.finishSession(session.sessionId))

        assertTrue(
            "cancellation is not completion, and completing it afterwards is refused: $refusal",
            refusal is SessionRefusal.SessionIsNotInProgress
        )
        assertEquals(SessionStatus.CANCELLED, (refusal as SessionRefusal.SessionIsNotInProgress).status)
        assertEquals("nothing was written by the refused completion", afterCancel, rig.tableCounts())
        assertEquals("CANCELLED", rig.storedStatus(session.sessionId))
        assertEquals("and the opportunity is still open", SlotStatus.PLANNED, rig.storedSlot(slot).status)
    }

    @Test
    fun cancellingAnAttemptThatAlreadyEndedIsRefused() = runBlocking {
        val session = started()
        rig.clock.instant = SessionFixture.FINISHED
        SessionFixture.valueOf(rig.runtime.finishSession(session.sessionId))
        val afterFinish = rig.tableCounts()

        val refusal = SessionFixture.refusalOf(rig.runtime.cancelSession(session.sessionId))

        assertTrue(refusal is SessionRefusal.SessionIsNotInProgress)
        assertEquals(SessionStatus.COMPLETED, (refusal as SessionRefusal.SessionIsNotInProgress).status)
        assertEquals("a completed attempt is not rewritten as a cancelled one", afterFinish, rig.tableCounts())
    }

    // ================================================================ Finish (§19, §27)

    @Test
    fun finishingCompletesTheAttemptAndTheOpportunityTogether() = runBlocking {
        val (session, _) = startedWithOneSet()
        rig.clock.instant = SessionFixture.FINISHED

        val completion = SessionFixture.valueOf(rig.runtime.finishSession(session.sessionId))

        assertEquals(SessionStatus.COMPLETED, completion.session.status)
        assertEquals(SessionFixture.FINISHED, completion.session.finishedAt)
        assertEquals(
            "the planned date and the actual timestamps are both preserved (§19)",
            rig.slot(1).plannedFor,
            completion.session.snapshot.workout.plannedFor
        )
        assertEquals(SessionFixture.STARTED, completion.session.startedAt)
        assertEquals(SlotStatus.COMPLETED, completion.slot.status)
        assertEquals(SessionFixture.FINISHED, completion.slot.completedAt)
        assertEquals(
            "the opportunity reports the attempt that took it",
            listOf(session.sessionId),
            completion.slot.attempts
        )
        assertEquals(
            "and the values it reports are the stored ones, read back",
            rig.requireStored(session.sessionId),
            completion.session
        )
        assertEquals(rig.storedSlot(slot), completion.slot)
    }

    @Test
    fun aCompletedAttemptIsNotCompletedTwice() = runBlocking {
        val session = started()
        rig.clock.instant = SessionFixture.FINISHED
        SessionFixture.valueOf(rig.runtime.finishSession(session.sessionId))
        val afterFinish = rig.tableCounts()

        val refusal = SessionFixture.refusalOf(rig.runtime.finishSession(session.sessionId))

        assertTrue(refusal is SessionRefusal.SessionIsNotInProgress)
        assertEquals(SessionStatus.COMPLETED, (refusal as SessionRefusal.SessionIsNotInProgress).status)
        assertEquals(afterFinish, rig.tableCounts())
        assertEquals(
            "and the stamp of the first completion is not overwritten",
            SessionFixture.FINISHED.toEpochMilli().toString(),
            rig.database.scalar("SELECT `finishedAt` FROM `workout_session` WHERE `sessionId` = ?", session.sessionId.value)
        )
    }

    // ================================================================ the adaptive half of a completion

    @Test
    fun aCompletionWithoutADecisionStoresNoAdaptiveRowAndSaysSo() = runBlocking {
        val session = started()

        val completion = SessionFixture.valueOf(rig.runtime.finishSession(session.sessionId))

        assertEquals(
            "the runtime invents no decision: there is none to record, and it says exactly that",
            AdaptiveOutcome.NothingDecided,
            completion.adaptive
        )
        assertEquals(0, rig.database.count("program_adaptive_decision_record"))
        assertEquals(0, rig.database.count("adaptive_adjustment"))
        assertEquals(0, rig.database.count("program_family_progression_state"))
        assertEquals(0, rig.database.count("family_progression_state"))
    }

    @Test
    fun aFilteredOutDecisionIsStoredWithItsOutcomeAndNoAdjustment() = runBlocking {
        val session = started()
        val decision = AdaptiveDecision(
            decisionId = DecisionId("decision-hold"),
            programId = session.programId,
            revisionId = session.revisionId,
            slotId = session.slotId,
            target = AdaptiveTarget.Family("push-family"),
            action = AdaptiveAction.HOLD,
            outcome = DecisionOutcome.NOT_APPLIED,
            evidence = EvidenceLevel.INSUFFICIENT,
            confidence = ConfidenceLevel.LOW,
            recovery = RecoveryContext.UNKNOWN,
            decidedAt = SessionFixture.FINISHED
        )

        val completion = SessionFixture.valueOf(
            rig.runtime.finishSession(session.sessionId, AdaptiveCompletion.Decided(decision))
        )

        assertEquals(
            "§18 keeps a filtered-out decision as an explicit non-applied record rather than dropping it",
            AdaptiveOutcome.Stored(decision.decisionId, null),
            completion.adaptive
        )
        assertEquals(
            "NOT_APPLIED",
            rig.database.scalar(
                "SELECT `outcome` FROM `program_adaptive_decision_record` WHERE `decisionId` = 'decision-hold'"
            )
        )
        assertEquals(
            "and it produced no adjustment",
            0,
            rig.database.count("adaptive_adjustment")
        )
        assertEquals(SessionStatus.COMPLETED, completion.session.status)
    }

    @Test
    fun anAppliedDecisionIsStoredWithItsAdjustmentInTheSameUnit() = runBlocking {
        val session = started()
        val decision = AdaptiveDecision(
            decisionId = DecisionId("decision-progress"),
            programId = session.programId,
            revisionId = session.revisionId,
            slotId = session.slotId,
            target = AdaptiveTarget.Exercise("plan-ex-r-1"),
            action = AdaptiveAction.PROGRESS,
            outcome = DecisionOutcome.APPLIED,
            evidence = EvidenceLevel.STRONG,
            confidence = ConfidenceLevel.HIGH,
            recovery = RecoveryContext.FAVORABLE,
            decidedAt = SessionFixture.FINISHED,
            adjustmentId = AdjustmentId("adjustment-progress")
        )
        val adjustment = AdaptiveAdjustment(
            adjustmentId = AdjustmentId("adjustment-progress"),
            decisionId = decision.decisionId,
            slotId = session.slotId,
            before = FixtureElements.PUSHUP,
            after = FixtureElements.PUSHUP.copy(prescription = RepPrescription(listOf(14, 12, 10, 8))),
            createdAt = SessionFixture.FINISHED
        )

        val completion = SessionFixture.valueOf(
            rig.runtime.finishSession(session.sessionId, AdaptiveCompletion.Decided(decision, adjustment))
        )

        assertEquals(
            "the completion reports the decision and the adjustment it stored",
            AdaptiveOutcome.Stored(decision.decisionId, adjustment.adjustmentId),
            completion.adaptive
        )
        assertEquals(SessionStatus.COMPLETED, completion.session.status)
        assertEquals(SlotStatus.COMPLETED, completion.slot.status)
        assertEquals(
            "both rows are stored, and the decision is APPLIED",
            listOf("APPLIED", "1"),
            listOf(
                rig.database.scalar(
                    "SELECT `outcome` FROM `program_adaptive_decision_record` WHERE `decisionId` = 'decision-progress'"
                ),
                rig.database.scalar(
                    "SELECT COUNT(*) FROM `adaptive_adjustment` WHERE `adjustmentId` = 'adjustment-progress'"
                )
            )
        )
        assertEquals(
            "the adjustment names the decision that produced it and the element it changes",
            listOf("decision-progress", "plan-ex-r-1", "14,12,10,8"),
            listOf(
                rig.database.scalar("SELECT `decisionId` FROM `adaptive_adjustment` WHERE `adjustmentId` = 'adjustment-progress'"),
                rig.database.scalar("SELECT `programExerciseId` FROM `adaptive_adjustment` WHERE `adjustmentId` = 'adjustment-progress'"),
                rig.database.scalar("SELECT `afterPerSetTargets` FROM `adaptive_adjustment` WHERE `adjustmentId` = 'adjustment-progress'")
            )
        )
    }

    @Test
    fun aDecisionAboutAnotherOpportunityIsRefusedAndNothingIsCompleted() = runBlocking {
        val session = started()
        val elsewhere = AdaptiveDecision(
            decisionId = DecisionId("decision-elsewhere"),
            programId = session.programId,
            revisionId = session.revisionId,
            slotId = rig.slotId(3),
            target = AdaptiveTarget.Session,
            action = AdaptiveAction.HOLD,
            outcome = DecisionOutcome.NOT_APPLIED,
            evidence = EvidenceLevel.INSUFFICIENT,
            confidence = ConfidenceLevel.LOW,
            recovery = RecoveryContext.UNKNOWN,
            decidedAt = SessionFixture.FINISHED
        )
        val before = rig.tableCounts()

        val refusal = SessionFixture.refusalOf(
            rig.runtime.finishSession(session.sessionId, AdaptiveCompletion.Decided(elsewhere))
        )

        assertTrue(
            "a decision is about one slot of one revision, and it is stored as part of that " +
                "opportunity's history: $refusal",
            refusal is SessionRefusal.AdaptiveDecisionIsOfAnotherOpportunity
        )
        assertEquals(
            "slot-r-3",
            (refusal as SessionRefusal.AdaptiveDecisionIsOfAnotherOpportunity).decisionSlotId.value
        )
        assertEquals("the completion wrote nothing at all", before, rig.tableCounts())
        assertEquals(SessionStatus.IN_PROGRESS, rig.requireStored(session.sessionId).status)
    }

    // ================================================================ transactions (§27)

    @Test
    fun aStartThatFailsBeforeTheSnapshotLeavesNoSessionBehind() = runBlocking {
        rig.createProgram()
        val before = rig.tableCounts()
        rig.faults.failSnapshotInsert = true
        try {
            val failure = SessionFixture.failureValueOf(rig.runtime.startSession(slot))
            assertTrue(failure.message!!.contains("planted fault"))
        } finally {
            rig.faults.failSnapshotInsert = false
        }

        assertEquals(
            "§19 makes the session row and its complete snapshot one write: neither survives without " +
                "the other",
            before,
            rig.tableCounts()
        )
        assertEquals(0, rig.database.count("workout_session"))
    }

    @Test
    fun aStartThatFailsBeforeTheOccurrencesLeavesNoSnapshotBehind() = runBlocking {
        rig.createProgram()
        val before = rig.tableCounts()
        rig.faults.failSessionExerciseInsert = true
        try {
            val failure = SessionFixture.failureValueOf(rig.runtime.startSession(slot))
            assertTrue(failure.message!!.contains("planted fault"))
        } finally {
            rig.faults.failSessionExerciseInsert = false
        }

        assertEquals(
            "a snapshot stored without the occurrences that present it is as forbidden as the reverse",
            before,
            rig.tableCounts()
        )
        assertEquals(
            listOf(0, 0, 0, 0),
            listOf("workout_session", "session_snapshot", "session_snapshot_exercise", "session_exercise")
                .map { rig.database.count(it) }
        )
    }

    @Test
    fun aConfirmThatFailsLeavesTheStoredSetsExactlyAsTheyWere() = runBlocking {
        val (session, occurrence) = startedWithOneSet()
        rig.faults.failSetLogInsert = true
        try {
            val failure = SessionFixture.failureValueOf(
                rig.runtime.confirmSet(session.sessionId, occurrence, completedReps = 10)
            )
            assertTrue(failure.message!!.contains("planted fault"))
        } finally {
            rig.faults.failSetLogInsert = false
        }

        assertEquals(listOf(1), rig.storedSets(occurrence.value).map { it["setIndex"]!!.toInt() })
        assertEquals(
            "and the autosaved set that was already there is untouched",
            "12",
            rig.storedSets(occurrence.value).single()["completedReps"]
        )
    }

    @Test
    fun aCompletionThatFailsOnTheSlotLeavesThePreCompletionStateIntact() = runBlocking {
        assertCompletionIsAtomic(failSlot = true)
    }

    @Test
    fun aCompletionThatFailsOnTheAdaptiveWriteLeavesThePreCompletionStateIntact() = runBlocking {
        assertCompletionIsAtomic(failAdaptive = true)
    }

    /**
     * §27's completion, at each point it can die: the session and the opportunity roll back together
     * with the adaptive write, and what is left is exactly the state before the call.
     *
     * With [failAdaptive] the completion is handed an `APPLIED` decision, so the failing statement is
     * the adjustment insert that follows the decision insert — which is what makes the rollback of the
     * *adaptive* half provable and not merely the rollback of the session's own rows.
     */
    private suspend fun assertCompletionIsAtomic(failSlot: Boolean = false, failAdaptive: Boolean = false) {
        val (session, occurrence) = startedWithOneSet()
        val adjustmentId = AdjustmentId("adjustment-atomic")
        val decision = AdaptiveDecision(
            decisionId = DecisionId("decision-atomic"),
            programId = session.programId,
            revisionId = session.revisionId,
            slotId = session.slotId,
            target = AdaptiveTarget.Exercise("plan-ex-r-1"),
            action = if (failAdaptive) AdaptiveAction.PROGRESS else AdaptiveAction.HOLD,
            outcome = if (failAdaptive) DecisionOutcome.APPLIED else DecisionOutcome.NOT_APPLIED,
            evidence = EvidenceLevel.INSUFFICIENT,
            confidence = ConfidenceLevel.LOW,
            recovery = RecoveryContext.UNKNOWN,
            decidedAt = SessionFixture.FINISHED,
            adjustmentId = adjustmentId.takeIf { failAdaptive }
        )
        val adjustment = if (!failAdaptive) null else AdaptiveAdjustment(
            adjustmentId = adjustmentId,
            decisionId = decision.decisionId,
            slotId = session.slotId,
            before = FixtureElements.PUSHUP,
            after = FixtureElements.PUSHUP.copy(prescription = RepPrescription(listOf(14, 12, 10, 8))),
            createdAt = SessionFixture.FINISHED
        )
        val before = rig.tableCounts()
        rig.faults.failSlotOutcomeUpdate = failSlot
        rig.faults.failAdjustmentInsert = failAdaptive
        try {
            val failure = SessionFixture.failureValueOf(
                rig.runtime.finishSession(
                    session.sessionId,
                    AdaptiveCompletion.Decided(decision, adjustment)
                )
            )
            assertTrue(failure.message!!.contains("planted fault"))
        } finally {
            rig.faults.failSlotOutcomeUpdate = false
            rig.faults.failAdjustmentInsert = false
        }

        assertEquals(
            "the whole completion or none of it: every table is exactly where it was",
            before,
            rig.tableCounts()
        )
        assertEquals("IN_PROGRESS", rig.storedStatus(session.sessionId))
        assertEquals(SlotStatus.PLANNED, rig.storedSlot(slot).status)
        assertNull(rig.storedSlot(slot).completedAt)
        assertEquals(
            "the set the attempt had confirmed is still there",
            listOf(1),
            rig.storedSets(occurrence.value).map { it["setIndex"]!!.toInt() }
        )
        assertEquals(
            "no decision, no adjustment and no family state survived the rolled-back unit",
            listOf(0, 0, 0),
            listOf(
                rig.database.count("program_adaptive_decision_record"),
                rig.database.count("adaptive_adjustment"),
                rig.database.count("program_family_progression_state")
            )
        )
    }

    @Test
    fun aRefusedOperationWritesNothingAtAll() = runBlocking {
        val session = started()
        val before = rig.tableCounts()

        val refusals = listOf(
            rig.runtime.startSession(SlotId("slot-nowhere")),
            rig.runtime.startSession(rig.slotId(2)),
            rig.runtime.startSession(rig.slotId(1)),
            rig.runtime.confirmSet(session.sessionId, session.exercises.first().sessionExerciseId),
            rig.runtime.cancelSession(SessionId("sess-999")),
            rig.runtime.restoreSession(SessionId("sess-999"))
        )

        assertTrue(
            "every operation that is not a success is one that decided before writing: " +
                refusals.map { SessionFixture.describe(it) },
            refusals.none { it is SessionRuntimeResult.Success }
        )
        assertEquals(before, rig.tableCounts())
    }

    // ================================================================ the process comes back (§19)

    /**
     * A workout started in one process and read in another: a second connection over the same file,
     * its own repositories and its own runtime, restoring the attempt from the rows the first one left.
     */
    @Test
    fun aSessionIsRestoredFromItsOwnRowsAfterTheProcessIsRecreated() = runBlocking {
        val file = Files.createTempFile("session-runtime-restart", ".sqlite").toFile()
        file.delete()
        val firstProcess = SqliteTestDatabase.at(file.absolutePath)
        try {
            ProgramDataAccessRig.migrate(firstProcess)
            val first = SessionRuntimeRig("r", firstProcess)
            first.createProgram()
            val session = SessionFixture.valueOf(first.runtime.startSession(SlotId("slot-r-1")))
            first.clock.instant = SessionFixture.SET_ONE
            SessionFixture.valueOf(
                first.runtime.confirmSet(session.sessionId, session.exercises.first().sessionExerciseId, completedReps = 9)
            )
            first.close()

            val secondProcess = SqliteTestDatabase.at(file.absolutePath)
            val second = SessionRuntimeRig("r", secondProcess)
            try {
                val restored = SessionFixture.valueOf(second.runtime.restoreSession(session.sessionId))

                assertEquals(
                    "the attempt comes back with the identity, revision and slot it was started under",
                    listOf(session.sessionId, session.programId, session.revisionId, session.slotId),
                    listOf(restored.sessionId, restored.programId, restored.revisionId, restored.slotId)
                )
                assertEquals(
                    "and with the presentation it captured, element for element",
                    session.snapshot,
                    restored.snapshot
                )
                assertEquals(
                    "and with the occurrence identities it minted",
                    session.exercises.map { it.sessionExerciseId },
                    restored.exercises.map { it.sessionExerciseId }
                )
                assertEquals(SessionStatus.IN_PROGRESS, restored.status)
                assertEquals(
                    "with the set that was autosaved before the process went away",
                    listOf(9),
                    restored.exercises.first().results.map { it.completedReps }
                )
                assertEquals(
                    "and the same presentation it captured",
                    listOf("pushup", "pike_pushup"),
                    restored.snapshot.workout.exercises.map { it.exerciseId }
                )
                assertEquals(
                    "and the same moment it started at",
                    SessionFixture.STARTED,
                    restored.startedAt
                )
            } finally {
                second.close()
            }
        } finally {
            firstProcess.close()
            file.delete()
        }
    }

    // ================================================================ restore's own rules

    @Test
    fun restoringASessionThatIsNotStoredIsRefused() = runBlocking {
        rig.createProgram()

        val refusal = SessionFixture.refusalOf(rig.runtime.restoreSession(SessionId("sess-404")))

        assertTrue(refusal is SessionRefusal.SessionNotFound)
    }

    @Test
    fun everyOperationNamesTheSessionItCouldNotFind() = runBlocking {
        rig.createProgram()
        val missing = SessionId("sess-404")
        val occurrence = SessionExerciseId("sess-404")

        val refusals = listOf(
            rig.runtime.restoreSession(missing),
            rig.runtime.confirmSet(missing, occurrence, completedReps = 1),
            rig.runtime.cancelSession(missing),
            rig.runtime.finishSession(missing)
        ).map(SessionFixture::refusalOf)

        assertTrue(
            "a session that is not stored is a refusal about that identity, never a silent success: " +
                refusals.map { it.message },
            refusals.all { it is SessionRefusal.SessionNotFound && it.sessionId == missing }
        )
    }

    @Test
    fun theRuntimeStoresTheMomentsItWasGivenAndNothingElse() = runBlocking {
        val (session, occurrence) = startedWithOneSet()
        rig.clock.instant = SessionFixture.FINISHED
        val completion = SessionFixture.valueOf(rig.runtime.finishSession(session.sessionId))

        assertEquals(
            "every stamp comes from the injected clock (§26), which is why a suite can state them",
            listOf(
                SessionFixture.STARTED.toEpochMilli(),
                SessionFixture.SET_ONE.toEpochMilli(),
                SessionFixture.FINISHED.toEpochMilli()
            ),
            listOf(
                completion.session.startedAt.toEpochMilli(),
                rig.storedSets(occurrence.value).single()["performedAt"]!!.toLong(),
                completion.session.finishedAt!!.toEpochMilli()
            )
        )
    }

    @Test
    fun aPlannedForDateThatWasWrittenByTheSchedulerIsNotReadFromTheClock() = runBlocking {
        rig.clock.instant = Instant.parse("2026-12-24T06:00:00Z")

        val session = started()

        assertEquals(
            "the planned date is the opportunity's, and the actual start is the clock's — two facts " +
                "kept apart (§19)",
            rig.slot(1).plannedFor,
            session.snapshot.workout.plannedFor
        )
        assertEquals(SessionFixtureTimes.DECEMBER_START, session.startedAt)
    }
}

/** The one moment a test states for itself, to prove a date is not derived from a start. */
internal object SessionFixtureTimes {
    val DECEMBER_START: Instant = Instant.parse("2026-12-24T06:00:00Z")
}
