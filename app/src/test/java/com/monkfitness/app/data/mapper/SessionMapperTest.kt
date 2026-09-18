package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.SetLogEntity
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session rows ⇄ `WorkoutSession`, pinned on the two things §19 turns on: the presentation a session
 * is loaded from (its own snapshot, never the live plan) and the sets a session has confirmed (rows in
 * set order, with no invented placeholders).
 */
class SessionMapperTest {

    private val slot = ProgramGraphFixture.slots("m").first()

    private val session: WorkoutSession = ProgramGraphFixture.session(
        key = "m",
        slot = slot,
        status = com.monkfitness.app.domain.workout.SessionStatus.COMPLETED,
        finishedAt = ProgramGraphFixture.FINISHED
    )

    private fun snapshotRows() = Triple(
        session.toEntity(),
        session.toSnapshotEntity(),
        session.toSnapshotExerciseEntities()
    )

    private fun occurrenceRows() = session.toSessionExerciseEntities()

    private fun setRows(): List<SetLogEntity> = session.exercises.flatMap { occurrence ->
        occurrence.results.map { it.toEntity(occurrence.sessionExerciseId) }
    }

    @Test
    fun aSessionRoundTripsInBothDirectionsFromItsOwnRows() {
        val (row, snapshot, elements) = snapshotRows()
        val occurrences = occurrenceRows()

        val loaded = sessionDomain(row, snapshot, elements, occurrences, setRows())

        assertEquals("domain → rows → domain", session, loaded)
        assertEquals(
            "rows → domain → rows",
            listOf(row, snapshot),
            listOf(loaded.toEntity(), loaded.toSnapshotEntity())
        )
    }

    @Test
    fun theCapturedPresentationKeepsTheAdjustmentsItWasPresentedWith() {
        val (row, snapshot, elements) = snapshotRows()

        val loaded = sessionDomain(row, snapshot, elements, occurrenceRows(), setRows())

        assertEquals(
            "the ids captured in the snapshot are returned, not re-queried from current state (§16, §19)",
            listOf(AdjustmentId("adjustment-m-1")),
            loaded.snapshot.workout.appliedAdjustmentIds
        )
        assertEquals(
            "and they are stored in application order",
            "adjustment-m-1",
            snapshot.appliedAdjustmentIds.single()
        )
    }

    @Test
    fun thePresentationIsTheSnapshotAndNotTheRevision() {
        val (row, snapshot, elements) = snapshotRows()

        val loaded = sessionDomain(row, snapshot, elements, occurrenceRows(), setRows())

        assertEquals(
            "the captured element is what the user was shown, not the plan's prescription",
            listOf("knee_pushup", "pike_pushup"),
            loaded.snapshot.workout.exercises.map { it.exerciseId }
        )
        assertEquals(
            listOf(listOf(10, 8), listOf(8, 8)),
            loaded.snapshot.workout.exercises.map { it.prescription.perSetTargets }
        )
    }

    @Test
    fun theIdentityTripleIsReadFromTheSessionRowAndNotDuplicatedInTheSnapshot() {
        val (row, snapshot, elements) = snapshotRows()
        val moved = row.copy(slotId = "slot-moved")

        val loaded = sessionDomain(moved, snapshot, elements, occurrenceRows(), setRows())

        assertEquals("slot-moved", loaded.slotId.value)
        assertEquals(
            "the captured workout follows the session's own identity",
            "slot-moved",
            loaded.snapshot.workout.slotId.value
        )
    }

    @Test
    fun aMissingSetIsNotFilledInWithAZeroRow() {
        val (row, snapshot, elements) = snapshotRows()
        val withoutTheSecondSet = setRows().filterNot { it.setIndex == 2 }
        val thirdSet = SetLogEntity(
            setLogId = "set-m-3",
            sessionExerciseId = "session-ex-m-1",
            setIndex = 3,
            completedReps = 6,
            durationSeconds = 0,
            performedAt = ProgramGraphFixture.SET_THREE.toEpochMilli()
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            sessionDomain(row, snapshot, elements, occurrenceRows(), withoutTheSecondSet + thirdSet)
        }

        assertTrue(
            "a gap is invalid persisted data and is refused, never reconstructed: ${failure.message}",
            failure.message!!.contains("no gaps") && failure.message!!.contains("[1, 3]")
        )
        assertTrue(
            "and no zero set is invented in its place",
            !failure.message!!.contains("0,") && !failure.message!!.contains(", 0")
        )
    }

    @Test
    fun setsAreReturnedInSetIndexOrder() {
        val (row, snapshot, elements) = snapshotRows()

        val loaded = sessionDomain(
            row,
            snapshot,
            elements,
            occurrenceRows(),
            setRows().reversed()
        )

        assertEquals(
            listOf(1, 2),
            loaded.exercises.first().results.map { it.setIndex }
        )
        assertEquals(
            listOf(12, 10),
            loaded.exercises.first().results.map { it.completedReps }
        )
        assertEquals(
            "the performed amounts are preserved, not reduced to a completed flag",
            listOf(0, 0),
            loaded.exercises.first().results.map { it.durationSeconds }
        )
    }

    @Test
    fun aSkippedOccurrenceLoadsWithNoResultsRatherThanZeroes() {
        val skipped = ProgramGraphFixture.session(key = "m", slot = slot, skipped = true)
        val (row, snapshot, elements) = Triple(
            skipped.toEntity(),
            skipped.toSnapshotEntity(),
            skipped.toSnapshotExerciseEntities()
        )

        val loaded = sessionDomain(row, snapshot, elements, skipped.toSessionExerciseEntities(), emptyList())

        assertTrue(loaded.exercises.last().skipped)
        assertEquals("a skipped occurrence observed no sets (§12)", emptyList<SetResult>(), loaded.exercises.last().results)
    }

    @Test
    fun aSessionWithoutItsSnapshotIsRefusedLoudly() {
        val (row, _, elements) = snapshotRows()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            sessionDomain(row, null, elements, occurrenceRows(), setRows())
        }

        assertTrue(
            "a session is not loadable without the presentation it started under (§19): ${failure.message}",
            failure.message!!.contains("has no session_snapshot row")
        )
    }

    @Test
    fun aSetRowThatBelongsToAnotherOccurrenceIsRefused() {
        val (row, snapshot, elements) = snapshotRows()
        val stray = SetLogEntity(
            setLogId = "set-elsewhere",
            sessionExerciseId = "session-ex-elsewhere",
            setIndex = 1,
            completedReps = 5,
            durationSeconds = 0,
            performedAt = ProgramGraphFixture.SET_ONE.toEpochMilli()
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            sessionDomain(row, snapshot, elements, occurrenceRows(), setRows() + stray)
        }

        assertTrue(failure.message!!.contains("set-elsewhere"))
    }

    @Test
    fun aSnapshotWhoseSessionIdentityDisagreesIsRefused() {
        val (row, snapshot, elements) = snapshotRows()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            sessionDomain(row, snapshot.copy(sessionId = "session-other"), elements, occurrenceRows(), setRows())
        }

        assertTrue(failure.message!!.contains("bound to its own snapshot"))
    }

    @Test
    fun anOccurrenceTheSnapshotDidNotPresentIsRefused() {
        val (row, snapshot, elements) = snapshotRows()
        val invented = occurrenceRows().map { it.copy(exerciseId = "muscle_up") }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            sessionDomain(row, snapshot, elements, invented, setRows())
        }

        assertTrue(
            "a session may only run what its snapshot presented: ${failure.message}",
            failure.message!!.contains("only run what its snapshot presented")
        )
    }

    @Test
    fun aSetMeasuredInNeitherUnitOrInBothIsUnrepresentable() {
        val occurrence = com.monkfitness.app.domain.common.SessionExerciseId("session-ex-m-1")

        listOf(
            "neither unit" to { SetResult(SetLogId("set-x"), 1, 0, 0, ProgramGraphFixture.SET_ONE) },
            "both units" to { SetResult(SetLogId("set-y"), 1, 5, 30, ProgramGraphFixture.SET_ONE) }
        ).forEach { (description, build) ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                build().toEntity(occurrence)
            }

            assertTrue(
                "a set is measured in repetitions or in time, never in $description: ${failure.message}",
                failure.message!!.contains("repetitions or in time")
            )
        }
    }

    @Test
    fun aTimeBasedOccurrenceKeepsItsSecondsPerSet() {
        val timed = TimePrescription(listOf(30, 30, 45))
        val timeSession = session.copy(
            exercises = listOf(
                session.exercises.first().copy(
                    prescription = timed,
                    results = listOf(
                        SetResult(SetLogId("set-t-1"), 1, completedReps = 0, durationSeconds = 30, performedAt = ProgramGraphFixture.SET_ONE),
                        SetResult(SetLogId("set-t-2"), 2, completedReps = 0, durationSeconds = 45, performedAt = ProgramGraphFixture.SET_TWO)
                    )
                ),
                session.exercises.last()
            ),
            snapshot = session.snapshot.copy(
                workout = session.snapshot.workout.copy(
                    exercises = listOf(
                        session.snapshot.workout.exercises.first().copy(prescription = timed),
                        session.snapshot.workout.exercises.last()
                    )
                )
            )
        )

        val loaded = sessionDomain(
            timeSession.toEntity(),
            timeSession.toSnapshotEntity(),
            timeSession.toSnapshotExerciseEntities(),
            timeSession.toSessionExerciseEntities(),
            timeSession.exercises.first().results.map {
                it.toEntity(timeSession.exercises.first().sessionExerciseId)
            }
        )

        assertEquals(
            "the timed prescription is returned per set, not collapsed",
            listOf(30, 30, 45),
            loaded.exercises.first().prescription.perSetTargets
        )
        assertEquals(
            listOf(30, 45),
            loaded.exercises.first().results.map { it.durationSeconds }
        )
        assertEquals(
            listOf(0, 0),
            loaded.exercises.first().results.map { it.completedReps }
        )
        assertEquals(
            "and the same prescription is what the presentation captured",
            listOf(30, 30, 45),
            loaded.snapshot.workout.exercises.first().prescription.perSetTargets
        )
    }
}
