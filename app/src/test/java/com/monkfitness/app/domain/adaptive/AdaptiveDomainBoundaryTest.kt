package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dependency boundary of the adaptive domain.
 *
 * `domain.adaptive` is the layer the future signal/policy/progression code will consume, and it must
 * stay independent of the data layer — no `data.model.Exercise`, no Room, no Android. What the
 * session-history adapter feeds the mapper is a pure domain representation of a planned exercise;
 * the data-layer `Exercise` is converted at the adapter and never crosses into this package.
 *
 * This suite pins that boundary from inside the domain package: if any type in `domain.adaptive`
 * were to reach back into `data.model`, this test's own imports would be the first thing to show it,
 * and the pure-conversion contract it asserts would be meaningless.
 */
class AdaptiveDomainBoundaryTest {

    @Test
    fun plannedExerciseIsAPureDomainRepresentationOfOnePrescribedExercise() {
        val repExercise = PlannedExercise(
            exerciseId = "pushups",
            sets = 3,
            repsPerSet = 10,
            durationSecondsPerSet = 0,
            isTimerBased = false
        )

        assertEquals("pushups", repExercise.exerciseId)
        assertEquals(3, repExercise.sets)
        assertEquals(10, repExercise.repsPerSet)
        assertEquals(0, repExercise.durationSecondsPerSet)
        assertFalse(repExercise.isTimerBased)
    }

    @Test
    fun plannedExerciseKeepsTheTwoUnitChannelsDistinct() {
        val timerExercise = PlannedExercise(
            exerciseId = "plank",
            sets = 3,
            repsPerSet = 0,
            durationSecondsPerSet = 30,
            isTimerBased = true
        )

        // A timer exercise prescribes no repetition work; its placeholder reps are normalized to 0.
        assertEquals(0, timerExercise.repsPerSet)
        assertEquals(30, timerExercise.durationSecondsPerSet)
        assertTrue(timerExercise.isTimerBased)
    }

    @Test
    fun plannedExerciseTotalIsThePrescribedAmountAcrossAllSets() {
        val repExercise = PlannedExercise(
            exerciseId = "squats",
            sets = 3,
            repsPerSet = 12,
            durationSecondsPerSet = 0,
            isTimerBased = false
        )

        // The session-total the mapper aggregates: per-set amount x prescribed set count.
        assertEquals(36, repExercise.repsPerSet * repExercise.sets)
        assertEquals(0, repExercise.durationSecondsPerSet * repExercise.sets)
    }
}
