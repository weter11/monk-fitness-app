package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.adaptive.ExposureLevel
import com.monkfitness.app.domain.adaptive.ExposureObservation
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * A missed opportunity and a cancelled session are **not** workouts that scored zero.
 *
 * This is the invariant the architecture states most bluntly (§12), and it is asserted here four ways,
 * because any one of them alone could be satisfied by accident:
 *
 *  * the slot type has no amount to be zero — its field set is pinned, so nothing can be added for a
 *    later change to fill with zeroes;
 *  * a missed slot may still have been attempted, and its cancelled attempt keeps its partial work in
 *    the session rather than being flattened into a slot result;
 *  * the exposure model has no "observed nothing" instance to construct: no exposure is the *absence*
 *    of an observation, and there is no `NONE` level to spell it with;
 *  * a set result cannot record zero work at all, so no downstream aggregate can be built from
 *    placeholder rows.
 */
class MissedAndCancelledAreNotZeroPerformanceTest {

    private val planned = LocalDate.parse("2026-09-18")
    private val startedAt: Instant = Instant.parse("2026-09-18T09:00:00Z")
    private val finishedAt: Instant = Instant.parse("2026-09-18T09:20:00Z")

    private val amountLikeNames = listOf(
        "reps", "repetitions", "sets", "duration", "durationSeconds", "seconds",
        "load", "volume", "score", "performance", "amount", "total", "completed"
    )

    @Test
    fun aSlotHasNoAmountThatAMissedOpportunityCouldReportAsZero() {
        val fields = declaredFieldNames(WorkoutSlot::class.java)

        assertEquals(
            setOf(
                "slotId", "programId", "revisionId", "programDayId", "plannedFor", "status",
                "attempts", "completedAt", "targetOccurrenceKey"
            ),
            fields
        )
        assertTrue(
            "a slot records which sessions were attempted, never how much work happened; found " +
                "amount-like fields: ${fields.intersect(amountLikeNames.toSet())}",
            fields.intersect(amountLikeNames.toSet()).isEmpty()
        )
    }

    @Test
    fun aCancelledSessionCarriesNoAggregateThatCancellingCouldZero() {
        val fields = declaredFieldNames(WorkoutSession::class.java)

        assertEquals(
            setOf(
                "sessionId", "slotId", "programId", "revisionId", "snapshot", "status",
                "startedAt", "finishedAt", "exercises"
            ),
            fields
        )
        assertTrue(
            "a session's work lives in its per-set results, so there is no session-level amount " +
                "to be zeroed: ${fields.intersect(amountLikeNames.toSet())}",
            fields.intersect(amountLikeNames.toSet()).isEmpty()
        )
    }

    @Test
    fun aMissedSlotMayHaveBeenAttemptedByASessionThatWasCancelled() {
        val session = cancelledSessionWithPartialWork()
        val missed = WorkoutSlot(
            slotId = SlotId("slot-1"),
            programId = ProgramId("program-1"),
            revisionId = RevisionId("rev-1"),
            programDayId = ProgramDayId("day-1"),
            plannedFor = planned,
            status = SlotStatus.MISSED,
            attempts = listOf(session.sessionId)
        )

        // The opportunity is missed; the attempt is recorded; the partial work survives in the
        // session and is not converted into a slot result of any kind.
        assertEquals(SlotStatus.MISSED, missed.status)
        assertEquals(SlotStatus.MISSED, missed.status)
        assertTrue(missed.hasBeenAttempted)
        assertFalse(missed.status == SlotStatus.COMPLETED)
        assertEquals(2, session.exercises.single().completedSetCount)
        assertEquals(listOf(8, 5), session.exercises.single().results.map { it.completedReps })
    }

    @Test
    fun aCancelledSessionIsNotACompletedWorkout() {
        val session = cancelledSessionWithPartialWork()

        assertFalse(session.isCompleted)
        assertFalse(session.status.isCompleted)
        assertEquals(SessionStatus.CANCELLED, session.status)
        assertEquals(finishedAt, session.finishedAt)

        // Cancelling does not clear, rewrite or zero what was confirmed.
        assertEquals(2, session.exercises.single().completedSetCount)
        assertTrue(session.exercises.single().results.all { it.completedReps > 0 })
    }

    @Test
    fun noExposureObservationExistsForWorkThatDidNotHappen() {
        assertRejects("an observation of zero completed sets") {
            observation(completedSets = 0, prescribedSets = 3, level = ExposureLevel.PARTIAL)
        }
        assertRejects("an observation of nothing prescribed") {
            observation(completedSets = 1, prescribedSets = 0, level = ExposureLevel.FULL)
        }
        assertTrue(
            "no exposure is the absence of an observation, not a level: ExposureLevel must not " +
                "have a NONE member",
            ExposureLevel.entries.none { it.name == "NONE" }
        )
    }

    @Test
    fun theExposureLevelMustAgreeWithWhatWasCompleted() {
        val partial = observation(completedSets = 1, prescribedSets = 3, level = ExposureLevel.PARTIAL)
        assertEquals(ExposureLevel.PARTIAL, partial.level)
        assertFalse(partial.isFull)

        val full = observation(completedSets = 3, prescribedSets = 3, level = ExposureLevel.FULL)
        assertTrue(full.isFull)

        // Extra sets are allowed, and they are still full exposure — never a claim of more than full.
        assertTrue(observation(completedSets = 4, prescribedSets = 3, level = ExposureLevel.FULL).isFull)

        assertRejects("a partial execution claiming to be full") {
            observation(completedSets = 1, prescribedSets = 3, level = ExposureLevel.FULL)
        }
        assertRejects("a full execution under-reporting itself") {
            observation(completedSets = 3, prescribedSets = 3, level = ExposureLevel.PARTIAL)
        }
    }

    @Test
    fun aZeroWorkSetResultIsNotRepresentable() {
        assertRejects("a set of zero repetitions and zero seconds") {
            result(reps = 0, seconds = 0)
        }
        assertRejects("a set measured in both units at once") {
            result(reps = 10, seconds = 30)
        }
        assertRejects("a set at index 0") { result(reps = 10, seconds = 0, index = 0) }
    }

    // ------------------------------------------------------------------ helpers

    private fun cancelledSessionWithPartialWork(): WorkoutSession {
        val workout = EffectiveWorkout(
            slotId = SlotId("slot-1"),
            programId = ProgramId("program-1"),
            revisionId = RevisionId("rev-1"),
            plannedFor = planned,
            computedAt = startedAt.minusSeconds(60),
            exercises = listOf(
                EffectiveExercise(
                    programExerciseId = ProgramExerciseId("element-1"),
                    exerciseId = "pushups",
                    prescription = RepPrescription(listOf(10, 10, 10))
                )
            )
        )
        val snapshot = WorkoutSessionSnapshot(SessionId("session-1"), startedAt, workout)
        val occurrence = SessionExercise(
            sessionExerciseId = SessionExerciseId("occurrence-1"),
            programExerciseId = ProgramExerciseId("element-1"),
            exerciseId = "pushups",
            prescription = RepPrescription(listOf(10, 10, 10)),
            results = listOf(
                result(reps = 8, seconds = 0, index = 1),
                result(reps = 5, seconds = 0, index = 2)
            ),
            skipped = false
        )
        return WorkoutSession(
            sessionId = SessionId("session-1"),
            slotId = SlotId("slot-1"),
            programId = ProgramId("program-1"),
            revisionId = RevisionId("rev-1"),
            snapshot = snapshot,
            status = SessionStatus.CANCELLED,
            startedAt = startedAt,
            finishedAt = finishedAt,
            exercises = listOf(occurrence)
        )
    }

    private fun result(reps: Int, seconds: Int, index: Int = 1) = SetResult(
        setLogId = SetLogId("set-$index"),
        setIndex = index,
        completedReps = reps,
        durationSeconds = seconds,
        performedAt = startedAt.plusSeconds(120L * index)
    )

    private fun observation(
        completedSets: Int,
        prescribedSets: Int,
        level: ExposureLevel
    ) = ExposureObservation(
        exerciseId = "pushups",
        sessionId = SessionId("session-1"),
        sessionExerciseId = SessionExerciseId("occurrence-1"),
        level = level,
        completedSets = completedSets,
        prescribedSets = prescribedSets,
        startedAt = startedAt,
        finishedAt = finishedAt
    )

    private fun declaredFieldNames(cls: Class<*>): Set<String> =
        cls.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    private fun assertRejects(what: String, block: () -> Any) {
        try {
            block()
            throw AssertionError("$what must not be constructible")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.isNotBlank() == true)
        }
    }
}
