package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.workout.SetResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * An observation carries what each completed set actually amounted to, not only how many sets there were.
 *
 * The scenario is the one the performed amounts exist for: **eight repetitions prescribed, fourteen
 * logged**. The count-based facts stay exactly as they were — one set prescribed, one completed, a full
 * exposure — and the performed amount rides alongside them without being compared, clamped to the
 * prescription or turned into a ratio.
 */
class ExposureObservationPerformedAmountsTest {

    private val startedAt: Instant = Instant.parse("2026-09-18T09:00:00Z")

    @Test
    fun loggedRepetitionsReachTheObservationAlongsideTheSetCounts() {
        val prescribed = RepPrescription(listOf(8))
        val logged = listOf(
            SetResult(
                setLogId = SetLogId("set-1"),
                setIndex = 1,
                completedReps = 14,
                durationSeconds = 0,
                performedAt = startedAt.plusSeconds(120)
            )
        )

        val observation = ExposureObservation(
            exerciseId = "pushups",
            sessionId = SessionId("session-1"),
            sessionExerciseId = SessionExerciseId("occurrence-1"),
            level = ExposureLevel.FULL,
            completedSets = logged.size,
            prescribedSets = prescribed.setCount,
            performedAmounts = logged.map { result ->
                PerformedSetAmount(
                    completedReps = result.completedReps,
                    durationSeconds = result.durationSeconds
                )
            },
            startedAt = startedAt,
            finishedAt = startedAt.plusSeconds(600)
        )

        // The counts are unchanged: the performed amount is additive to them.
        assertEquals(1, observation.completedSets)
        assertEquals(1, observation.prescribedSets)
        assertEquals(ExposureLevel.FULL, observation.level)

        // The fourteen logged repetitions are the amount the observation exposes, next to the eight the
        // prescription asked for — carried as performed, never clamped to the prescription.
        assertEquals(listOf(14), observation.performedAmounts?.map { it.completedReps })
        assertEquals(listOf(0), observation.performedAmounts?.map { it.durationSeconds })
        assertEquals(8, prescribed.perSetReps.single())
    }
}
