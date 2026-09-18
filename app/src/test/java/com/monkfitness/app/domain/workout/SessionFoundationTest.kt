package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The session and snapshot foundation.
 *
 * The centre of gravity here is the distinction the architecture draws between *what should be
 * presented* and *what was presented*: an effective workout is recomputed as adjustments supersede one
 * another, while the snapshot a session captured is frozen and is the only thing that session may run
 * from. Both halves are asserted behaviourally — recompute an effective workout after a new adjustment
 * and show the snapshot did not move — rather than by restating the field names.
 */
class SessionFoundationTest {

    private val planned = LocalDate.parse("2026-09-18")
    private val computedAt: Instant = Instant.parse("2026-09-18T08:00:00Z")
    private val capturedAt: Instant = Instant.parse("2026-09-18T08:05:00Z")

    // ------------------------------------------------------------------ vocabulary

    @Test
    fun sessionStatusHasExactlyThreeValues() {
        assertEquals(
            listOf("IN_PROGRESS", "COMPLETED", "CANCELLED"),
            SessionStatus.entries.map { it.name }
        )
        assertFalse(SessionStatus.IN_PROGRESS.isCompleted)
        assertTrue(SessionStatus.COMPLETED.isCompleted)
        assertFalse(SessionStatus.CANCELLED.isCompleted)
    }

    // ------------------------------------------------------------------ effective vs snapshot

    @Test
    fun recomputingTheEffectiveWorkoutDoesNotMoveTheSnapshotThatCapturedIt() {
        val presentation = effectiveWorkout(reps = 10)
        val snapshot = WorkoutSessionSnapshot(SessionId("session-1"), capturedAt, presentation)
        val session = session(snapshot = snapshot)

        // The adaptive stage supersedes its earlier adjustment: the slot's presentation changes...
        val adjusted = effectiveWorkout(reps = 12).copy(
            computedAt = computedAt.plusSeconds(600),
            appliedAdjustmentIds = listOf(
                com.monkfitness.app.domain.common.AdjustmentId("adjustment-2")
            )
        )
        assertNotEquals(presentation, adjusted)
        assertEquals(12, (adjusted.exercises.single().prescription as RepPrescription).targetForSet(1))

        // ... while what the session actually ran is still the presentation it captured.
        assertEquals(10, (session.snapshot.workout.exercises.single().prescription as
            RepPrescription).targetForSet(1))
        assertEquals(presentation, session.snapshot.workout)
        assertTrue(session.snapshot.workout.appliedAdjustmentIds.isEmpty())
        assertTrue(adjusted.isAdjusted)
    }

    @Test
    fun aSnapshotCannotCaptureAPresentationComputedAfterItWasTaken() {
        assertRejects("a snapshot of a future computation") {
            WorkoutSessionSnapshot(
                sessionId = SessionId("session-1"),
                capturedAt = capturedAt,
                workout = effectiveWorkout().copy(computedAt = capturedAt.plusSeconds(1))
            )
        }
    }

    @Test
    fun anEffectiveWorkoutCarriesIdentityAndADeterministicPresentation() {
        val workout = effectiveWorkout()
        assertEquals(SlotId("slot-1"), workout.slotId)
        assertEquals(ProgramId("program-1"), workout.programId)
        assertEquals(RevisionId("rev-1"), workout.revisionId)
        assertEquals(planned, workout.plannedFor)
        assertFalse(workout.isAdjusted)

        assertRejects("two presentations of the same plan element") {
            workout.copy(exercises = workout.exercises + workout.exercises)
        }
        assertRejects("the same adjustment applied twice") {
            workout.copy(
                appliedAdjustmentIds = listOf(
                    com.monkfitness.app.domain.common.AdjustmentId("adjustment-1"),
                    com.monkfitness.app.domain.common.AdjustmentId("adjustment-1")
                )
            )
        }
        // A rest day presents nothing, which is not an error.
        assertTrue(workout.copy(exercises = emptyList()).exercises.isEmpty())
    }

    // ------------------------------------------------------------------ the session's own invariants

    @Test
    fun aSessionIsBoundToTheSnapshotItStartedUnder() {
        val snapshot = snapshot()
        session(snapshot = snapshot)

        assertRejects("a session holding another session's snapshot") {
            session(snapshot = snapshot.copy(sessionId = SessionId("session-2")))
        }
        assertRejects("a session holding a snapshot of another slot") {
            session(
                snapshot = snapshot.copy(
                    workout = snapshot.workout.copy(slotId = SlotId("slot-2"))
                )
            )
        }
        assertRejects("a session holding a snapshot of another program") {
            session(
                snapshot = snapshot.copy(
                    workout = snapshot.workout.copy(programId = ProgramId("program-2"))
                )
            )
        }
        assertRejects("a session holding a snapshot of another revision") {
            session(
                snapshot = snapshot.copy(
                    workout = snapshot.workout.copy(revisionId = RevisionId("rev-2"))
                )
            )
        }
    }

    @Test
    fun aSessionCannotRunWhatWasNeverPresented() {
        val presented = sessionExercise(id = "occurrence-1", elementId = "element-1")
        session(exercises = listOf(presented))

        assertRejects("a session running an element the snapshot never presented") {
            session(
                exercises = listOf(
                    presented.copy(programExerciseId = ProgramExerciseId("element-9"))
                )
            )
        }
        assertRejects("a session running an exercise the snapshot presented under another key") {
            session(exercises = listOf(presented.copy(exerciseId = "squats")))
        }
        assertRejects("the same occurrence run twice") {
            session(exercises = listOf(presented, presented.copy(exerciseId = "pushups")))
        }
    }

    @Test
    fun theStatusAndTheFinishStampAgree() {
        assertRejects("an IN_PROGRESS session that has finished") {
            session(status = SessionStatus.IN_PROGRESS, finishedAt = capturedAt.plusSeconds(60))
        }
        assertRejects("a COMPLETED session with no finish stamp") {
            session(status = SessionStatus.COMPLETED, finishedAt = null)
        }
        assertRejects("a CANCELLED session with no finish stamp") {
            session(status = SessionStatus.CANCELLED, finishedAt = null)
        }
        assertRejects("a session that finished before it started") {
            session(status = SessionStatus.COMPLETED, finishedAt = capturedAt.minusSeconds(1))
        }
    }

    @Test
    fun thePlannedDateAndTheActualTimestampsAreBothPreserved() {
        val startedOnAnotherDay = capturedAt.plusSeconds(86_400)
        val finished = startedOnAnotherDay.plusSeconds(3600)
        val session = session(
            startedAt = startedOnAnotherDay,
            status = SessionStatus.COMPLETED,
            finishedAt = finished
        )

        assertEquals(planned, session.snapshot.workout.plannedFor)
        assertEquals(startedOnAnotherDay, session.startedAt)
        assertEquals(finished, session.finishedAt)
        assertNotEquals(planned, session.startedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate())
        assertTrue(session.isCompleted)
    }

    // ------------------------------------------------------------------ sets

    @Test
    fun confirmedSetsAccumulateWithoutGapsAndInOneUnit() {
        val oneSet = sessionExercise(id = "occurrence-1")
        assertEquals(1, oneSet.completedSetCount)

        assertRejects("a set list with a gap") {
            oneSet.copy(
                results = listOf(
                    repSet(index = 1, id = "set-1"),
                    repSet(index = 3, id = "set-3")
                )
            )
        }
        assertRejects("two results for one set") {
            oneSet.copy(results = listOf(repSet(index = 1, id = "set-1"), repSet(index = 1, id = "set-1")))
        }
        assertRejects("a repetition prescription logged in seconds") {
            oneSet.copy(results = listOf(setResult(index = 1, reps = 0, seconds = 30)))
        }
        assertRejects("a timed prescription logged in repetitions") {
            sessionExercise(id = "occurrence-1", prescription = TimePrescription(listOf(30)))
                .copy(results = listOf(repSet(index = 1, id = "set-1")))
        }
        assertRejects("a skipped exercise with results") {
            oneSet.copy(skipped = true)
        }
        // Skipping with no results is the honest shape, and needs no work record to say so.
        assertTrue(oneSet.copy(results = emptyList(), skipped = true).results.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun effectiveWorkout(reps: Int = 10) = EffectiveWorkout(
        slotId = SlotId("slot-1"),
        programId = ProgramId("program-1"),
        revisionId = RevisionId("rev-1"),
        plannedFor = planned,
        computedAt = computedAt,
        exercises = listOf(
            EffectiveExercise(
                programExerciseId = ProgramExerciseId("element-1"),
                exerciseId = "pushups",
                prescription = RepPrescription(listOf(reps))
            )
        )
    )

    private fun snapshot() = WorkoutSessionSnapshot(
        sessionId = SessionId("session-1"),
        capturedAt = capturedAt,
        workout = effectiveWorkout()
    )

    private fun sessionExercise(
        id: String,
        elementId: String = "element-1",
        prescription: com.monkfitness.app.domain.prescription.Prescription =
            RepPrescription(listOf(10))
    ) = SessionExercise(
        sessionExerciseId = SessionExerciseId(id),
        programExerciseId = ProgramExerciseId(elementId),
        exerciseId = "pushups",
        prescription = prescription,
        results = listOf(repSet(index = 1, id = "set-1")),
        skipped = false
    )

    private fun repSet(index: Int, id: String) = setResult(index = index, reps = 10, seconds = 0, id = id)

    private fun setResult(
        index: Int,
        reps: Int,
        seconds: Int,
        id: String = "set-$index"
    ) = SetResult(
        setLogId = SetLogId(id),
        setIndex = index,
        completedReps = reps,
        durationSeconds = seconds,
        performedAt = capturedAt.plusSeconds(120L * index)
    )

    private fun session(
        snapshot: WorkoutSessionSnapshot = snapshot(),
        status: SessionStatus = SessionStatus.IN_PROGRESS,
        startedAt: Instant = capturedAt,
        finishedAt: Instant? = null,
        exercises: List<SessionExercise> = emptyList()
    ) = WorkoutSession(
        sessionId = SessionId("session-1"),
        slotId = SlotId("slot-1"),
        programId = ProgramId("program-1"),
        revisionId = RevisionId("rev-1"),
        snapshot = snapshot,
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt,
        exercises = exercises
    )

    private fun assertRejects(what: String, block: () -> Any) {
        try {
            block()
            throw AssertionError("$what must not be constructible")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.isNotBlank() == true)
        }
    }
}
