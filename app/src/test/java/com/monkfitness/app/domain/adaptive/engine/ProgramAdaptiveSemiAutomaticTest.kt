package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.ExerciseRange
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An element whose exercise and range the user chose and whose amount the engine owns: the range is the
 * boundary, the span is climbed before the ladder is, and both ends of it are answered with a reason
 * rather than with silence.
 */
class ProgramAdaptiveSemiAutomaticTest {

    private val engine = ProgramAdaptiveEngine

    private val range: ExerciseRange = ExerciseRange(repRange = 6..15)

    @Test
    fun aStepInsideTheRangeIsTheSameExerciseAndNeverPastTheTop() {
        val result = engine.decide(
            ProgramAdaptiveRig.request(
                snapshot = ProgramAdaptiveRig.snapshot(exposures = ProgramAdaptiveRig.improvement()),
                element = ProgramAdaptiveRig.element(prescription = RepPrescription.uniform(3, 10)),
                window = ProgramAdaptiveRig.window(level = 2),
                range = range
            )
        )

        val adjustment = result.adjustment!!
        assertEquals(ProgramAdaptiveRig.PRESENTED, adjustment.after.exerciseId)
        assertTrue(
            "the user's range is the boundary a target may not cross: " +
                adjustment.after.prescription.perSetTargets,
            adjustment.after.prescription.perSetTargets.all { amount -> amount in 6..15 }
        )
        assertTrue(
            "and the amounts only ever grow inside it",
            adjustment.after.prescription.perSetTargets.max() >
                adjustment.before.prescription.perSetTargets.max()
        )
        assertEquals(AdaptiveTarget.Exercise(ProgramAdaptiveRig.PRESENTED), result.decision.target)
    }

    @Test
    fun aHarderVariantIsTheAnswerOnlyOnceTheWholeSpanIsUsed() {
        val result = engine.decide(
            ProgramAdaptiveRig.request(
                snapshot = ProgramAdaptiveRig.snapshot(exposures = ProgramAdaptiveRig.improvement()),
                element = ProgramAdaptiveRig.element(prescription = RepPrescription.uniform(3, 15)),
                window = ProgramAdaptiveRig.window(level = 2),
                range = range
            )
        )

        assertEquals(AdaptiveAction.PROGRESS, result.decision.action)
        assertEquals(ProgramAdaptiveRig.HARDER, result.adjustment!!.after.exerciseId)
    }

    @Test
    fun aFamilyWithNoLadderHoldsAtTheTopOfItsRangeAndSaysSo() {
        val result = engine.decide(
            ProgramAdaptiveRig.request(
                snapshot = ProgramAdaptiveRig.snapshot(exposures = ProgramAdaptiveRig.improvement()),
                element = ProgramAdaptiveRig.element(prescription = RepPrescription.uniform(3, 15)),
                window = ProgramAdaptiveRig.window(level = 2),
                range = range,
                progression = FamilyProgression.WITHIN_EXERCISE
            )
        )

        assertEquals(AdaptiveAction.HOLD, result.decision.action)
        assertEquals(DecisionOutcome.NOT_APPLIED, result.decision.outcome)
        assertEquals(ProgramAdaptiveReason.HELD_AT_UPPER_BOUND, result.reason)
        assertEquals(ProgramAdaptiveReason.HELD_AT_UPPER_BOUND, result.decision.reason)
        assertNull(result.adjustment)
        assertEquals(AdaptiveAction.PROGRESS, result.requestedAction)
    }

    @Test
    fun oneMissedLowerBoundIsHeldWithoutEscalating() {
        val result = engine.decide(lowerBoundRequest(misses = 1))

        assertEquals(ProgramAdaptiveReason.HELD_AT_LOWER_BOUND, result.reason)
        assertNull(result.adjustment)
    }

    @Test
    fun consecutiveMissesOfTheLowerBoundSuggestAnEasierVariantWhereTheFamilyHasOne() {
        val result = engine.decide(lowerBoundRequest(misses = 2))

        assertEquals(ProgramAdaptiveReason.EASIER_VARIANT_SUGGESTED, result.reason)
        assertNull("a suggestion is not a change: the range below is the user's too", result.adjustment)
    }

    @Test
    fun consecutiveMissesOfTheLowerBoundWithoutALadderSayTheExerciseIsTooHeavy() {
        val result = engine.decide(
            lowerBoundRequest(misses = 2, progression = FamilyProgression.WITHIN_EXERCISE)
        )

        assertEquals(ProgramAdaptiveReason.TOO_HEAVY_FOR_THE_RANGE, result.reason)
        assertNull(result.adjustment)
    }

    @Test
    fun eachRowOfOneAppearanceIsKeptInsideItsOwnRange() {
        val result = engine.decide(
            ProgramAdaptiveRig.request(
                snapshot = ProgramAdaptiveRig.snapshot(exposures = ProgramAdaptiveRig.improvement()),
                range = range,
                familyPeers = listOf(peer(range = ExerciseRange(repRange = 6..7)))
            )
        )

        val shared = result.adjustment!!.after.prescription.perSetTargets
        assertEquals(
            "the element the decision is about gets the whole target",
            listOf(12, 12, 12),
            shared
        )
        val peer = result.adjustments.single { adjustment ->
            adjustment.before.programExerciseId == ProgramExerciseId(PEER_ROW)
        }
        assertTrue(
            "the other row's own range binds it, and it is told apart from the shared target: " +
                "${peer.after.prescription.perSetTargets}",
            peer.after.prescription.perSetTargets.all { amount -> amount in 6..7 }
        )
        assertEquals(listOf(PEER_ROW), result.clampedRows)
    }

    @Test
    fun aRowAlreadyAtTheTopOfItsOwnRangeIsReportedRatherThanRewritten() {
        val result = engine.decide(
            ProgramAdaptiveRig.request(
                snapshot = ProgramAdaptiveRig.snapshot(exposures = ProgramAdaptiveRig.improvement()),
                range = range,
                familyPeers = listOf(
                    peer(range = ExerciseRange(repRange = 6..7), prescription = RepPrescription.uniform(3, 7))
                )
            )
        )

        assertEquals(
            "a row that cannot move is not an adjustment with equal ends",
            1,
            result.adjustments.size
        )
        assertEquals(listOf(PEER_ROW), result.clampedRows)
    }

    private fun peer(
        range: ExerciseRange?,
        prescription: RepPrescription = RepPrescription.uniform(3, 6)
    ): ProgramFamilyPeer = ProgramFamilyPeer(
        presentation = EffectiveExercise(ProgramExerciseId(PEER_ROW), ProgramAdaptiveRig.PRESENTED, prescription),
        adjustmentId = AdjustmentId("${ProgramAdaptiveRig.ADJUSTMENT_ID.value}~$PEER_ROW"),
        range = range
    )

    /** An element standing at the bottom of its range, with [misses] appearances that fell below it. */
    private fun lowerBoundRequest(
        misses: Int,
        progression: FamilyProgression = FamilyProgression.VARIANT_LADDER
    ): ProgramAdaptiveRequest = ProgramAdaptiveRig.request(
        snapshot = ProgramAdaptiveRig.snapshot(
            exposures = (0 until misses).map { index ->
                ProgramAdaptiveRig.observation(index = index, reps = 4)
            }
        ),
        element = ProgramAdaptiveRig.element(prescription = RepPrescription.uniform(3, 6)),
        window = ProgramAdaptiveRig.window(level = 2),
        range = range,
        progression = progression
    )

    private companion object {

        const val PEER_ROW: String = "plan-element-2"
    }
}
