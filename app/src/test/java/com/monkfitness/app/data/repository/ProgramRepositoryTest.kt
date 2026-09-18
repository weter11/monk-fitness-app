package com.monkfitness.app.data.repository

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.AppState
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.SlotStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.SQLException

/**
 * `ProgramRepository` against a real SQLite engine: the whole-Program round trip, the atomic creation,
 * the deletion that is the schema's own cascade, and the failures that must reach the caller.
 *
 * The repository is exercised through the DAOs the way production will, so every claim here is decided
 * by the engine's transaction and foreign keys rather than by the repository's code shape.
 */
class ProgramRepositoryTest {

    private val rig = ProgramDataAccessRig("a")

    private val programId = ProgramId(ProgramGraphFixture.programId("a"))

    // ---- the aggregate ----------------------------------------------------------------------------

    @Test
    fun theWholeProgramGraphRoundTripsThroughTheRepository() = runBlocking {
        rig.createGraph()

        val loaded = rig.freshProgramRepository().programWithCurrentRevision(programId)!!

        assertEquals(rig.graph.program, loaded.program)
        assertEquals(rig.graph.revision, loaded.currentRevision)

        val days = loaded.currentRevision.days
        assertEquals("the plan's order is 1..n", listOf(1, 2, 3), days.map { it.position })
        assertEquals(
            listOf("pushup", "pike_pushup"),
            days[0].exercises.map { it.exerciseId }
        )
        assertEquals(
            "the rest day survived as a rest day with no elements",
            ProgramDayType.REST,
            days[1].type
        )
        assertEquals(emptyList<String>(), days[1].exercises.map { it.exerciseId })
        assertEquals(
            "the repeated exercise survived as two distinct occurrences (§9)",
            listOf("pushup", "pushup", "plank"),
            days[2].exercises.map { it.exerciseId }
        )
        assertEquals(3, days[2].exercises.map { it.programExerciseId }.toSet().size)
        assertEquals(
            "and a started Program keeps its identity fields",
            rig.graph.program.createdAt,
            loaded.program.createdAt
        )
    }

    @Test
    fun everyPrescriptionSurvivesTheDatabaseInBothKinds() = runBlocking {
        rig.createGraph()

        val days = rig.freshPlanRepository().currentRevision(programId)!!.days

        assertEquals(
            "12 / 10 / 8 / 6 is four numbers, not a uniform target",
            listOf(12, 10, 8, 6),
            days[0].exercises[0].prescription.perSetTargets
        )
        assertEquals(
            "30 / 30 / 45 is a per-set duration in its own dimension",
            listOf(30, 30, 45),
            days[2].exercises[2].prescription.perSetTargets
        )
        assertEquals(
            "a repetition prescription and a timed one are not the same dimension",
            listOf("REP_BASED", "REP_BASED", "REP_BASED", "REP_BASED", "TIME_BASED"),
            days.flatMap { it.exercises }.map { it.prescription.dimension.name }
        )
    }

    @Test
    fun theInitialSlotsArePartOfTheCreationAndCarryTheRightPlanDay() = runBlocking {
        rig.createGraph()

        val slots = rig.programScheduleRepository.slotsOfProgram(programId)

        assertEquals(3, slots.size)
        assertEquals(
            "each slot presents its own day, in plan order",
            listOf("day-a-1", "day-a-2", "day-a-3"),
            slots.map { it.programDayId.value }
        )
        assertTrue("all three are still open opportunities", slots.all { it.status == SlotStatus.PLANNED })
        assertTrue("and none of them claims to hold work", slots.all { it.attempts.isEmpty() && it.completedAt == null })
    }

    @Test
    fun twoProgramsCoexistWithoutTouchingEachOther() = runBlocking {
        val other = ProgramGraphFixture.graph("b")
        rig.programRepository.createProgram(rig.graph.program, rig.graph.revision, rig.graph.slots)
        rig.programRepository.createProgram(other.program, other.revision, other.slots)

        assertEquals(2, rig.programRepository.countPrograms())
        assertEquals(other.revision, rig.freshPlanRepository().currentRevision(other.program.programId))
        assertEquals(rig.graph.revision, rig.freshPlanRepository().currentRevision(programId))
    }

    @Test
    fun aProgramThatDoesNotExistReadsAsAbsentAndNotAsAFailure() = runBlocking {
        val repository = rig.freshProgramRepository()

        assertNull(repository.programById(ProgramId("program-nowhere")))
        assertNull(repository.programWithCurrentRevision(ProgramId("program-nowhere")))
        assertEquals(emptyList<Any>(), repository.programs())
        assertEquals(0, repository.countPrograms())
    }

    // ---- creation is atomic -----------------------------------------------------------------------

    @Test
    fun aFailureWhileWritingThePlanLeavesNoProgramBehind() = runBlocking {
        rig.faults.failExerciseInsert = true

        val failure = failureOf {
            rig.programRepository.createProgram(rig.graph.program, rig.graph.revision, rig.graph.slots)
        }

        assertTrue(
            "the planted failure is the one that came out: ${failure.message}",
            failure.message!!.contains("planted fault")
        )
        assertEquals(
            "no Program, no revision, no day, no element, no slot survived the rollback",
            listOf(0, 0, 0, 0, 0),
            listOf("program", "program_revision", "program_day", "program_exercise", "program_workout_slot")
                .map { rig.database.count(it) }
        )
    }

    @Test
    fun aConstraintFailureIsReportedRatherThanTurnedIntoAnEmptyResult() = runBlocking {
        rig.createGraph()

        val failure = failureOf { rig.createGraph() }

        assertTrue(
            "creating the same Program twice is a constraint failure, not a silent no-op: $failure",
            failure is SQLException
        )
        assertEquals("and the first Program is still the only one", 1, rig.database.count("program"))
        assertEquals(1, rig.database.count("program_revision"))
    }

    @Test
    fun aGraphThatDoesNotBelongTogetherIsRefusedBeforeAnyWrite() = runBlocking {
        val otherRevision = ProgramGraphFixture.revision("b")

        val failure = failureOf {
            rig.programRepository.createProgram(rig.graph.program, otherRevision, rig.graph.slots)
        }

        assertTrue(failure.message!!.contains("must belong to the Program being created"))
        assertEquals(0, rig.database.count("program"))
    }

    @Test
    fun aSlotThatPresentsSomethingTheRevisionDoesNotPlanIsRefused() = runBlocking {
        val stray = rig.graph.slots.first().copy(programDayId = com.monkfitness.app.domain.common.ProgramDayId("day-nowhere"))

        val failure = failureOf {
            rig.programRepository.createProgram(rig.graph.program, rig.graph.revision, rig.graph.slots + stray)
        }

        assertTrue(failure.message!!.contains("must present a day of the revision being created"))
        assertEquals(0, rig.database.count("program"))
    }

    // ---- the non-structural facts -----------------------------------------------------------------

    @Test
    fun updatingAProgramChangesOnlyWhatIsNotStructure() = runBlocking {
        rig.createGraph()
        val before = rig.database.rows("SELECT * FROM `program_revision`") +
            rig.database.rows("SELECT * FROM `program_day`") +
            rig.database.rows("SELECT * FROM `program_exercise`")

        val renamed = rig.graph.program.copy(
            name = "Program A (renamed)",
            lifecycleStatus = LifecycleStatus.PAUSED,
            updatedAt = ProgramGraphFixture.FINISHED,
            archivedAt = ProgramGraphFixture.FINISHED
        )
        rig.programRepository.updateProgram(renamed)

        assertEquals(renamed, rig.freshProgramRepository().programById(programId))
        assertEquals(
            "renaming, pausing and archiving are not structural changes (§6)",
            before,
            rig.database.rows("SELECT * FROM `program_revision`") +
                rig.database.rows("SELECT * FROM `program_day`") +
                rig.database.rows("SELECT * FROM `program_exercise`")
        )
    }

    // ---- deletion is the database's cascade -------------------------------------------------------

    @Test
    fun deletingAProgramRemovesItsWholeGraphAndLeavesTheOtherAndTheGlobalRowsAlone() = runBlocking {
        val other = ProgramGraphFixture.graph("b")
        rig.programRepository.createProgram(rig.graph.program, rig.graph.revision, rig.graph.slots)
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        rig.workoutSessionRepository.startSession(
            ProgramGraphFixture.session("b", other.slots.first())
        )
        rig.database.exec("INSERT INTO `program_family_progression_state` (`revisionId`, `familyId`, `progressionLevel`, `adaptationState`, `currentExerciseId`, `updatedAt`) VALUES ('revision-b', 'push-family', 1, 'HOLD', NULL, 1700000000000)")
        rig.database.exec("INSERT INTO `set_log` (`exerciseId`, `repsCompleted`, `durationSeconds`, `timestamp`, `sessionDate`) VALUES ('pushup', 12, 0, 1700000000000, '2026-09-21')")
        rig.database.exec("INSERT INTO `body_weight_log` (`weightKg`, `date`) VALUES (80.5, '2026-09-21')")
        rig.database.exec(
            "INSERT INTO `family_progression_state` (`familyId`, `progressionLevel`, `currentExerciseId`, " +
                "`adaptationState`, `precedingProgressQualifyingWindows`, " +
                "`precedingRegressQualifyingWindows`, `precedingHighRiskWindows`, " +
                "`recoveryQualifyingSessions`, `eligibleSessionsSinceLastProgressionChange`, " +
                "`programRevision`, `updatedAt`, `policyVersion`) VALUES ('push-family', 0, NULL, " +
                "'HOLD', 0, 0, 0, 0, NULL, 0, 1700000000000, 1)"
        )

        rig.programRepository.deleteProgram(programId)

        assertEquals(
            "every table A owned lost A's rows and kept every one of B's",
            mapOf(
                "program" to 1, "program_revision" to 1, "program_day" to 3, "program_exercise" to 5,
                "program_workout_slot" to 3, "workout_session" to 1, "session_snapshot" to 1,
                "session_snapshot_exercise" to 2, "session_exercise" to 2, "program_set_log" to 0,
                "program_pause" to 0, "program_family_progression_state" to 1,
                "program_adaptive_decision_record" to 0, "adaptive_adjustment" to 0
            ),
            listOf(
                "program", "program_revision", "program_day", "program_exercise", "program_workout_slot",
                "workout_session", "session_snapshot", "session_snapshot_exercise", "session_exercise",
                "program_set_log", "program_pause", "program_family_progression_state",
                "program_adaptive_decision_record", "adaptive_adjustment"
            ).associateWith { rig.database.count(it) }
        )
        assertEquals(
            "and the surviving rows are B's, by identity",
            listOf("program-b"),
            rig.database.strings("SELECT `programId` FROM `program`")
        )
        assertEquals(
            "down to the last owned table",
            listOf("revision-b"),
            rig.database.strings("SELECT DISTINCT `revisionId` FROM `program_workout_slot`")
        )
        assertEquals(
            "B's graph is untouched, down to its captured presentation",
            other.revision,
            rig.freshPlanRepository().currentRevision(other.program.programId)
        )
        assertEquals(
            "a session of B is still readable with its own snapshot",
            other.slots.first().slotId,
            rig.freshSessionRepository().sessionById(com.monkfitness.app.domain.common.SessionId("session-b"))!!.slotId
        )
        assertEquals(
            "and the global rows a Program does not own survive (§29)",
            listOf("1", "1", "1"),
            listOf(
                rig.database.scalar("SELECT COUNT(*) FROM `set_log`"),
                rig.database.scalar("SELECT COUNT(*) FROM `body_weight_log`"),
                rig.database.scalar("SELECT COUNT(*) FROM `family_progression_state`")
            )
        )
    }

    @Test
    fun deletingTheSelectedProgramIsRefusedByTheDatabaseAndTheRefusalIsNotSwallowed() = runBlocking {
        rig.createGraph()
        val other = ProgramGraphFixture.graph("b")
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        rig.appStateRepository.save(AppState(selectedProgramId = programId))

        val failure = failureOf { rig.programRepository.deleteProgram(programId) }

        assertTrue(
            "the schema refuses the delete (NO ACTION) and the caller hears about it: $failure",
            failure is SQLException && failure.message!!.contains("FOREIGN KEY")
        )
        assertEquals("nothing was deleted", 2, rig.database.count("program"))
        assertEquals(2, rig.database.count("program_revision"))

        rig.appStateRepository.save(AppState(selectedProgramId = other.program.programId))
        rig.programRepository.deleteProgram(programId)

        assertEquals("once the selection moved, the delete is the database's to allow", 1, rig.database.count("program"))
        assertEquals(
            "and the selection still names the Program that is left",
            other.program.programId,
            rig.appStateRepository.state()!!.selectedProgramId
        )
    }

    @Test
    fun deletingTheProgramChosenToStartNextNullsThatReferenceAndKeepsItsRow() = runBlocking {
        rig.createGraph()
        val other = ProgramGraphFixture.graph("b")
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        rig.appStateRepository.save(
            AppState(
                selectedProgramId = other.program.programId,
                nextProgramId = programId,
                nextProgramAutoStart = true
            )
        )

        rig.programRepository.deleteProgram(programId)

        val state = rig.appStateRepository.state()!!
        assertNull("the plan to start A next is void, so the reference is cleared (§29)", state.nextProgramId)
        assertEquals("while the selection is untouched", other.program.programId, state.selectedProgramId)
        assertEquals("and the auto-start flag is a fact of its own", true, state.nextProgramAutoStart)
    }

    @Test
    fun deletingAProgramDoesNotTouchTheLegacyTables() = runBlocking {
        rig.createGraph()
        rig.database.exec("INSERT INTO `adaptive_decision_record` (`familyId`, `programRevision`, `cycleNumber`, `programDay`, `timestamp`, `previousState`, `newState`, `actions`, `reasonCode`, `policyVersion`) VALUES ('push-family', 0, 1, 1, 1700000000000, 'HOLD', 'PROGRESS', 'PROGRESS', 'EVIDENCE_STABLE', 1)")

        rig.programRepository.deleteProgram(programId)

        assertEquals(
            "the Stage-1 audit trail is not Program-owned and is not deleted with a Program (§30 step 15)",
            "1",
            rig.database.scalar("SELECT COUNT(*) FROM `adaptive_decision_record`")
        )
        assertEquals(
            "and the target tables are empty of the deleted Program",
            listOf("0", "0"),
            listOf(
                rig.database.scalar("SELECT COUNT(*) FROM `program_adaptive_decision_record`"),
                rig.database.scalar("SELECT COUNT(*) FROM `adaptive_adjustment`")
            )
        )
    }

    @Test
    fun aSavedRevisionBecomesTheCurrentOneAndTheOldOneStaysReadable() = runBlocking {
        rig.createGraph()
        rig.programPlanRepository.saveNewRevision(ProgramGraphFixture.nextRevision("a"), ProgramGraphFixture.FINISHED)

        val program = rig.freshProgramRepository().programById(programId)!!

        assertEquals(RevisionId("revision-a-2"), program.currentRevisionId)
        assertEquals(
            "and the revision that moved out of current is still readable in full",
            rig.graph.revision,
            rig.freshPlanRepository().revisionById(RevisionId(ProgramGraphFixture.revisionId("a")))
        )
    }
}
