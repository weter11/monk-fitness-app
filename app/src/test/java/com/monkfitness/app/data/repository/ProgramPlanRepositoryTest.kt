package com.monkfitness.app.data.repository

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramSchedule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * `ProgramPlanRepository`: a revision is immutable history, a new plan is new rows, and the Program's
 * pointer moves with the save — or neither does.
 */
class ProgramPlanRepositoryTest {

    private val rig = ProgramDataAccessRig("a")

    private val programId = ProgramId(ProgramGraphFixture.programId("a"))

    private val firstRevisionId = RevisionId(ProgramGraphFixture.revisionId("a"))

    @Test
    fun savingASecondRevisionLeavesTheFirstOneExactlyAsItWas() = runBlocking {
        rig.createGraph()
        val before = rig.database.rows("SELECT * FROM `program_exercise` ORDER BY `programExerciseId`")

        val second = ProgramGraphFixture.nextRevision("a")
        rig.programPlanRepository.saveNewRevision(second, ProgramGraphFixture.FINISHED)

        assertEquals(
            "the revision that is no longer current still describes exactly what it described (§6)",
            rig.graph.revision,
            rig.freshPlanRepository().revisionById(firstRevisionId)
        )
        assertEquals(
            "and its rows were not rewritten in place",
            before,
            rig.database.rows(
                "SELECT * FROM `program_exercise` WHERE `programDayId` IN ('day-a-1', 'day-a-2', 'day-a-3') " +
                    "ORDER BY `programExerciseId`"
            )
        )
        assertEquals("the new revision is stored as its own rows", second, rig.freshPlanRepository().revisionById(second.revisionId))
    }

    @Test
    fun savingARevisionMovesTheCurrentPointerInTheSameTransaction() = runBlocking {
        rig.createGraph()
        val second = ProgramGraphFixture.nextRevision("a")

        rig.programPlanRepository.saveNewRevision(second, ProgramGraphFixture.FINISHED)

        assertEquals(
            "the revision that describes the plan now is the one just saved (§23)",
            second,
            rig.freshPlanRepository().currentRevision(programId)
        )
        assertEquals(2, rig.programPlanRepository.countRevisionsOf(programId))
        assertEquals(
            "and the Program itself was stamped, not replaced",
            ProgramGraphFixture.FINISHED,
            rig.freshProgramRepository().programById(programId)!!.updatedAt
        )
    }

    @Test
    fun theCurrentRevisionIsThePointerAndNotTheNewestRow() = runBlocking {
        rig.createGraph()
        val second = ProgramGraphFixture.nextRevision("a")
        rig.programPlanRepository.saveNewRevision(second, ProgramGraphFixture.FINISHED)
        rig.programRepository.updateProgram(
            rig.graph.program.copy(currentRevisionId = firstRevisionId)
        )

        assertEquals(
            "a Program pointed back at revision 1 reads revision 1, not revision 2",
            rig.graph.revision,
            rig.freshPlanRepository().currentRevision(programId)
        )
        assertEquals(2, rig.programPlanRepository.revisionsOf(programId).size)
        assertEquals(
            "both revisions are readable in revision-number order",
            listOf(1, 2),
            rig.programPlanRepository.revisionsOf(programId).map { it.revisionNumber }
        )
    }

    @Test
    fun aFailedRevisionSaveLeavesNeitherTheRowsNorAMovedPointer() = runBlocking {
        rig.createGraph()
        val second = ProgramGraphFixture.nextRevision("a")
        rig.faults.failExerciseInsert = true

        val failure = failureOf {
            rig.programPlanRepository.saveNewRevision(second, ProgramGraphFixture.FINISHED)
        }

        assertTrue(failure.message!!.contains("planted fault"))
        assertEquals("no second revision", 1, rig.database.count("program_revision"))
        assertEquals("no days of a phantom revision", 3, rig.database.count("program_day"))
        assertNull(rig.freshPlanRepository().revisionById(second.revisionId))
        assertEquals(
            "and the Program still points at the revision it had",
            firstRevisionId,
            rig.freshProgramRepository().programById(programId)!!.currentRevisionId
        )
    }

    @Test
    fun anIndefiniteRevisionRoundTripsWithoutFakeLength() = runBlocking {
        rig.createGraph()

        rig.programPlanRepository.saveNewRevision(
            ProgramGraphFixture.nextRevision("a"),
            ProgramGraphFixture.FINISHED
        )

        val loaded = rig.freshPlanRepository().currentRevision(programId)!!
        assertEquals(ProgramDuration.Indefinite, loaded.duration)
        assertNull(
            "an indefinite revision stores no day count at all (§21)",
            rig.database.scalar("SELECT `durationDays` FROM `program_revision` WHERE `revisionId` = 'revision-a-2'")
        )
    }

    @Test
    fun aFlexibleWeeklyFrequencySurvivesTheRepositoryAndTheDatabase() = runBlocking {
        rig.createGraph()
        val flexible = ProgramGraphFixture.nextRevision("a")
            .copy(schedule = ProgramSchedule.FlexiblePerWeek(3))

        rig.programPlanRepository.saveNewRevision(flexible, ProgramGraphFixture.FINISHED)

        val loaded = rig.freshPlanRepository().currentRevision(programId)!!

        assertEquals("the schedule is the one that was saved", ProgramSchedule.FlexiblePerWeek(3), loaded.schedule)
        assertEquals(
            "the frequency is stored, not derived: the row holds the number itself",
            "3",
            rig.database.scalar(
                "SELECT `scheduleSessionsPerWeek` FROM `program_revision` " +
                    "WHERE `revisionId` = 'revision-a-2'"
            )
        )
        assertNull(
            "and the weekday column is empty for a frequency schedule",
            rig.database.scalar(
                "SELECT `scheduleWeekdays` FROM `program_revision` WHERE `revisionId` = 'revision-a-2'"
            )
        )
        assertEquals(
            "while the first revision's weekday schedule stores no frequency at all — the correction " +
                "made the column required by one form, not by both",
            listOf<String?>(null),
            rig.database.strings(
                "SELECT `scheduleSessionsPerWeek` FROM `program_revision` WHERE `revisionId` = 'revision-a'"
            )
        )
        assertEquals(
            "and it still loads as the weekday set it was saved with",
            ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            rig.freshPlanRepository().revisionById(firstRevisionId)!!.schedule
        )
    }

    @Test
    fun anUnknownRevisionIsAbsentAndNotAFailure() = runBlocking {
        val repository = rig.freshPlanRepository()

        assertNull(repository.revisionById(RevisionId("revision-nowhere")))
        assertNull(repository.currentRevision(ProgramId("program-nowhere")))
        assertEquals(emptyList<Any>(), repository.revisionsOf(ProgramId("program-nowhere")))
        assertEquals(0, repository.countRevisionsOf(ProgramId("program-nowhere")))
    }

    @Test
    fun aProgramWhosePointerNamesAMissingRevisionIsInvalidPersistedData() = runBlocking {
        rig.createGraph()
        rig.database.exec("UPDATE `program` SET `currentRevisionId` = 'revision-nowhere' WHERE `programId` = 'program-a'")

        val failure = failureOf {
            rig.freshProgramRepository().programWithCurrentRevision(programId)
        }

        assertTrue(
            "a Program always has a current revision (§23): ${failure.message}",
            failure.message!!.contains("revision-nowhere") && failure.message!!.contains("always has")
        )
    }
}
