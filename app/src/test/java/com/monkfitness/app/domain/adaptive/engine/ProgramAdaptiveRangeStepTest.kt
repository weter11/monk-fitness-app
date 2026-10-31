package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.BodyRegion
import com.monkfitness.app.domain.adaptive.ExerciseMetadata
import com.monkfitness.app.domain.adaptive.TrainingDomain
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.adaptive.toExerciseRange
import com.monkfitness.app.domain.prescription.RepPrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The within-range prescription step: the same exercise, a different Prescription, clamped to the range
 * the catalogue states.
 *
 * Three properties are asserted, and each is the reason the path exists:
 *
 *  * the exercise is **unchanged** — this is a re-prescription, not a ladder step, so `before` and
 *    `after` name the same exercise and the same plan element;
 *  * the amounts are **clamped** — a target below the range's bottom becomes the bottom, never less;
 *  * at the floor there is **no adjustment** — the same bounded answer a regression gives at the
 *    family's own floor — and the reason the caller supplied travels with the decision unchanged.
 */
class ProgramAdaptiveRangeStepTest {

    /** The catalogue's own span for the exercise, as the adaptive layer receives it. */
    private val range = ExerciseMetadata<Unit>(
        id = ProgramAdaptiveRig.PRESENTED,
        familyId = ProgramAdaptiveRig.FAMILY,
        trainingDomain = TrainingDomain.STRENGTH,
        bodyRegion = BodyRegion.CORE,
        repRange = 6..15,
        sets = 3
    ).toExerciseRange()!!

    @Test
    fun aCallerDecidedStepRePrescribesTheSameExerciseInsideItsRange() {
        val result = ProgramAdaptiveEngine.decide(
            request(target = RepPrescription.uniform(3, 8))
        )

        val adjustment = result.adjustment!!
        assertEquals(AdaptiveAction.REGRESS, result.decision.action)
        assertEquals(DecisionOutcome.APPLIED, result.decision.outcome)
        assertEquals(
            "the caller's reason is carried, not replaced by the engine's hard-coded one",
            ProgramAdaptiveReason.RETURN_AFTER_GAP, result.reason
        )
        assertEquals(ProgramAdaptiveReason.RETURN_AFTER_GAP, result.decision.reason)

        assertEquals(ProgramAdaptiveRig.PRESENTED, adjustment.before.exerciseId)
        assertEquals(
            "the same exercise is presented, not a lower variant of the family",
            ProgramAdaptiveRig.PRESENTED, adjustment.after.exerciseId
        )
        assertEquals(adjustment.before.programExerciseId, adjustment.after.programExerciseId)
        assertNotEquals(adjustment.before.prescription, adjustment.after.prescription)
        assertEquals(listOf(8, 8, 8), adjustment.after.prescription.perSetTargets)
        assertEquals(listOf(10, 10, 10), adjustment.before.prescription.perSetTargets)
        assertEquals(AdaptiveTarget.Exercise(ProgramAdaptiveRig.PRESENTED), result.decision.target)
    }

    @Test
    fun aTargetBelowTheRangesBottomIsClampedToTheBottom() {
        val result = ProgramAdaptiveEngine.decide(
            request(target = RepPrescription.uniform(3, 4))
        )

        assertEquals(
            "six is the bottom of the catalogue range, so the clamp answers with six",
            listOf(6, 6, 6), result.adjustment!!.after.prescription.perSetTargets
        )
        assertEquals(ProgramAdaptiveReason.RETURN_AFTER_GAP, result.reason)
    }

    @Test
    fun atTheRangesFloorThereIsNoAdjustment() {
        // The element already prescribes the bottom of its range, so clamping a lower target to the
        // range produces exactly what is presented: the step is at the floor, not an empty adjustment.
        val result = ProgramAdaptiveEngine.decide(
            request(
                target = RepPrescription.uniform(3, 4),
                element = ProgramAdaptiveRig.element(prescription = RepPrescription.uniform(3, 6))
            )
        )

        assertNull(result.adjustment)
        assertEquals(ProgramAdaptiveReason.FLOOR_REACHED, result.reason)
        assertEquals(AdaptiveAction.HOLD, result.decision.action)
        assertEquals(DecisionOutcome.NOT_APPLIED, result.decision.outcome)
    }

    private fun request(
        target: RepPrescription,
        element: ProgramAdaptiveElement = ProgramAdaptiveRig.element()
    ): ProgramAdaptiveRequest = ProgramAdaptiveRig.request(element = element).copy(
        range = range,
        prescriptionStep = ProgramPrescriptionStep(
            target = target,
            reason = ProgramAdaptiveReason.RETURN_AFTER_GAP
        )
    )
}
