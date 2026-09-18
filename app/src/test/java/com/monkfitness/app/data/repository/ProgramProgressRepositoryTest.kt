package com.monkfitness.app.data.repository

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ProgramProgressRepository`: the row-level facts the Progress stage will compute over, and the
 * prohibitions that keep it from becoming the analytics engine — no amount of work, no average, no
 * score, no focus or family distribution.
 */
class ProgramProgressRepositoryTest {

    private val rig = ProgramDataAccessRig("a")

    private val programId = ProgramId(ProgramGraphFixture.programId("a"))

    @Test
    fun theCountsAreACensusOfRowsWithZeroesForWhatDidNotHappen() = runBlocking {
        rig.createGraph()
        val slot = rig.graph.slotFor(1)
        val session = ProgramGraphFixture.session("a", slot)
        rig.startSessionWithSets(session)
        rig.workoutSessionRepository.finishSession(
            ProgramGraphFixture.session("a", slot, status = SessionStatus.COMPLETED, finishedAt = ProgramGraphFixture.FINISHED),
            ProgramGraphFixture.completedSlot("a", slot)
        )

        val progress = rig.programProgressRepository

        assertEquals(
            "one of three opportunities was taken, and the other two are still open",
            mapOf(SlotStatus.PLANNED to 2, SlotStatus.COMPLETED to 1, SlotStatus.MISSED to 0, SlotStatus.SUPERSEDED to 0),
            progress.slotStatusCounts(programId)
        )
        assertEquals(
            mapOf(SessionStatus.IN_PROGRESS to 0, SessionStatus.COMPLETED to 1, SessionStatus.CANCELLED to 0),
            progress.sessionStatusCounts(programId)
        )
        assertEquals(
            "two sets were confirmed, and that count is a count of rows",
            2,
            progress.confirmedSetCount(programId)
        )
        assertEquals(listOf("session-a"), progress.sessionIdsOf(programId).map { it.value })
    }

    @Test
    fun anotherProgramsFactsAreNotCountedInThisOnes() = runBlocking {
        val other = ProgramGraphFixture.graph("b")
        rig.programRepository.createProgram(rig.graph.program, rig.graph.revision, rig.graph.slots)
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        rig.workoutSessionRepository.startSession(ProgramGraphFixture.session("b", other.slots.first()))

        assertEquals(
            "the counts are scoped by Program, not global (§21: the selected Program is the default context)",
            mapOf(SlotStatus.PLANNED to 3, SlotStatus.COMPLETED to 0, SlotStatus.MISSED to 0, SlotStatus.SUPERSEDED to 0),
            rig.programProgressRepository.slotStatusCounts(programId)
        )
        assertEquals(
            mapOf(SessionStatus.IN_PROGRESS to 1, SessionStatus.COMPLETED to 0, SessionStatus.CANCELLED to 0),
            rig.programProgressRepository.sessionStatusCounts(ProgramId(other.program.programId.value))
        )
        assertEquals(0, rig.programProgressRepository.confirmedSetCount(programId))
        assertEquals(
            emptyList<SessionId>(),
            rig.programProgressRepository.sessionIdsOf(programId)
        )
    }

    @Test
    fun aProgramWithNoHistoryCountsZeroRatherThanFailing() = runBlocking {
        rig.createGraph()

        assertEquals(
            mapOf(SlotStatus.PLANNED to 3, SlotStatus.COMPLETED to 0, SlotStatus.MISSED to 0, SlotStatus.SUPERSEDED to 0),
            rig.programProgressRepository.slotStatusCounts(programId)
        )
        assertEquals(0, rig.programProgressRepository.confirmedSetCount(programId))
    }

    @Test
    fun theRepositoryExposesNoAmountOfWorkAndNoDerivedMeasure() {
        val methods = ProgramProgressRepository::class.java.declaredMethods
            .map { it.name }
            .map { name -> name.substringBefore('-') }
            .sorted()

        assertEquals(
            "§24: persistence primitives only — the §21 measures (frequency, volume, focus and family " +
                "distribution, average duration, progression, PRs, streak) are §30 step 9's computations",
            listOf("confirmedSetCount", "sessionIdsOf", "sessionStatusCounts", "slotStatusCounts"),
            methods
        )
        assertTrue(
            "no scalar amount of work is exposed, because repetitions are not comparable across " +
                "exercises (§17)",
            methods.none { name ->
                listOf("volume", "total", "reps", "load", "average", "score", "streak")
                    .any { name.lowercase().contains(it) }
            }
        )
    }
}
