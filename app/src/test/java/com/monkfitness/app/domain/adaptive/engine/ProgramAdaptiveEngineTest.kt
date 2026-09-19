package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.ADJUSTMENT_ID
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.DECISION_ID
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.EASIER
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.EVERY_EXERCISE
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.FAMILY
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.HARDER
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.PRESENTED
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.SAME_LEVEL_ALTERNATIVE
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.decline
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.element
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.improvement
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.plateau
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.profile
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.relation
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.request
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.snapshot
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.window
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 11: the Adaptive Engine end to end — one request in, one auditable decision out.
 *
 * The suite is the stage's own contract, so it states the things the architecture asks for by name:
 * the ceiling and the floor (§15), the user's own selection as the authority on what a step may be
 * (§9), §15's bounded variant change, §18's aggregate load guard refusing an automatic increase
 * without inventing a regression, the user's own content never being adapted at all, and §16's
 * adjustment — one occurrence, before and after, superseded by reference and never rewritten.
 */
class ProgramAdaptiveEngineTest {

    private val engine = ProgramAdaptiveEngine

    // ------------------------------------------------------------------ progression and regression

    @Test
    fun aConfirmedProgressionMovesTheElementToTheNextDeclaredVariant() {
        val result = engine.decide(
            request(snapshot = snapshot(exposures = improvement()), window = window(level = 2))
        )

        assertEquals(AdaptiveAction.PROGRESS, result.decision.action)
        assertEquals(DecisionOutcome.APPLIED, result.decision.outcome)
        assertEquals(ProgramAdaptiveReason.SUSTAINED_POSITIVE, result.reason)
        assertEquals(AdaptiveState.PROGRESS, result.state)
        assertEquals(AdaptiveAction.PROGRESS, result.requestedAction)
        assertEquals(
            "a family-level move is decided at the family's own granularity (§18)",
            AdaptiveTarget.Family(FAMILY), result.decision.target
        )
        assertEquals(AdaptiveScope.FAMILY, result.decision.target.scope)
        assertTrue(result.guard is ProgramGuardVerdict.Approved)

        val adjustment = result.adjustment!!
        assertEquals(PRESENTED, adjustment.before.exerciseId)
        assertEquals(HARDER, adjustment.after.exerciseId)
        assertEquals(
            "an adjustment changes one presented element and not a different one (§16)",
            adjustment.before.programExerciseId, adjustment.after.programExerciseId
        )
        assertEquals(ADJUSTMENT_ID, adjustment.adjustmentId)
        assertEquals(DECISION_ID, adjustment.decisionId)
    }

    @Test
    fun theCeilingStopsTheProgressionAndSaysSo() {
        val result = engine.decide(
            request(snapshot = snapshot(exposures = improvement()), window = window(level = 3))
        )

        assertEquals(AdaptiveAction.PROGRESS, result.requestedAction)
        assertEquals(AdaptiveAction.HOLD, result.decision.action)
        assertEquals(DecisionOutcome.NOT_APPLIED, result.decision.outcome)
        assertEquals(ProgramAdaptiveReason.CEILING_REACHED, result.reason)
        assertEquals(AdaptiveState.HOLD, result.state)
        assertNull(result.adjustment)
    }

    @Test
    fun theFloorStopsTheRegressionAndSaysSo() {
        val result = engine.decide(
            request(snapshot = snapshot(exposures = decline()), window = window(level = 1))
        )

        assertEquals(AdaptiveAction.REGRESS, result.requestedAction)
        assertEquals(AdaptiveAction.HOLD, result.decision.action)
        assertEquals(ProgramAdaptiveReason.FLOOR_REACHED, result.reason)
        assertNull(result.adjustment)
    }

    @Test
    fun aConfirmedRegressionMovesDownTheHierarchyAndIsNeverFiltered() {
        val result = engine.decide(
            request(snapshot = snapshot(exposures = decline()), window = window(level = 2))
        )

        assertEquals(AdaptiveAction.REGRESS, result.decision.action)
        assertEquals(ProgramAdaptiveReason.SUSTAINED_NEGATIVE, result.reason)
        assertEquals(EASIER, result.adjustment!!.after.exerciseId)
        assertEquals(
            "a regression takes load away, so the aggregate load guard has nothing to refuse and " +
                "never rules on it (§18)",
            ProgramGuardVerdict.NotGuarded(AdaptiveScope.FAMILY, AdaptiveAction.REGRESS), result.guard
        )
    }

    @Test
    fun aStepTheUsersSelectionExcludesIsUnavailableAndNothingIsSubstituted() {
        val result = engine.decide(
            request(
                snapshot = snapshot(exposures = improvement()),
                window = window(level = 2),
                availableExerciseIds = setOf(EASIER, PRESENTED)
            )
        )

        assertEquals(AdaptiveAction.PROGRESS, result.requestedAction)
        assertEquals(AdaptiveAction.HOLD, result.decision.action)
        assertEquals(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE, result.reason)
        assertNull(
            "the user's own configuration is authoritative: an unavailable step produces a bounded " +
                "non-progressing result and never a different exercise (§9, §15)",
            result.adjustment
        )
    }

    @Test
    fun anElementItsFamilyDoesNotDeclareIsRealignedToTheDeclaredVariant() {
        val result = engine.decide(
            request(
                snapshot = snapshot(exposures = plateau()),
                element = element(exerciseId = "mystery_press"),
                window = window(level = 2)
            )
        )

        assertEquals(AdaptiveAction.CHANGE_VARIANT, result.decision.action)
        assertEquals(ProgramAdaptiveReason.VARIANT_REALIGNED, result.reason)
        assertEquals(AdaptiveTarget.Exercise("mystery_press"), result.decision.target)
        assertEquals(AdaptiveScope.EXERCISE, result.decision.target.scope)
        assertEquals(PRESENTED, result.adjustment!!.after.exerciseId)
        assertEquals(
            "a variant realignment is a same-level move, so the family stays where it is",
            AdaptiveState.HOLD, result.state
        )
    }

    @Test
    fun aVariantTheUserNoLongerHasIsRealignedToAnotherTheFamilyDeclaresAtTheSameLevel() {
        val result = engine.decide(
            request(
                snapshot = snapshot(exposures = plateau()),
                element = element(exerciseId = SAME_LEVEL_ALTERNATIVE),
                relation = relation(sameLevelAlternatives = true),
                window = window(level = 2),
                availableExerciseIds = setOf(EASIER, PRESENTED, HARDER)
            )
        )

        assertEquals(AdaptiveAction.CHANGE_VARIANT, result.decision.action)
        assertEquals(ProgramAdaptiveReason.VARIANT_REALIGNED, result.reason)
        assertEquals(SAME_LEVEL_ALTERNATIVE, result.adjustment!!.before.exerciseId)
        assertEquals(
            "the two ends of a variant change are both declared at the family's own level (§15)",
            PRESENTED, result.adjustment.after.exerciseId
        )
    }

    // ------------------------------------------------------------------ the user's own content

    @Test
    fun anElementTheUserOwnsIsNeverAdaptedAtAll() {
        val pinned = engine.decide(
            request(
                snapshot = snapshot(exposures = improvement()),
                element = element(ownership = ProgramElementOwnership.PINNED),
                window = window(level = 2)
            )
        )

        assertEquals(AdaptiveAction.HOLD, pinned.decision.action)
        assertEquals(ProgramAdaptiveReason.USER_AUTHORED_ELEMENT, pinned.reason)
        assertEquals(AdaptiveState.HOLD, pinned.state)
        assertNull(pinned.adjustment)
        assertEquals(
            "the guard is never even asked about an element that is not the plan's automatic " +
                "content (§18), so it cannot be said to have blocked it",
            ProgramGuardVerdict.NotGuarded(AdaptiveScope.FAMILY, AdaptiveAction.HOLD), pinned.guard
        )
    }

    @Test
    fun anElementTheUserAuthoredIsNotRealignedEither() {
        val authored = engine.decide(
            request(
                snapshot = snapshot(exposures = plateau()),
                element = element(
                    exerciseId = "mystery_press",
                    ownership = ProgramElementOwnership.USER_AUTHORED
                ),
                window = window(level = 2)
            )
        )

        assertEquals(AdaptiveAction.HOLD, authored.decision.action)
        assertEquals(ProgramAdaptiveReason.USER_AUTHORED_ELEMENT, authored.reason)
        assertNull(authored.adjustment)
    }

    // ------------------------------------------------------------------ the aggregate load guard

    @Test
    fun anIncreasePastTheToleranceIsFilteredAndBecomesAHold() {
        val result = engine.decide(
            request(
                // The next variant's own prescription is twice the element's own repetitions: the
                // shape of an automatic change that compounds rather than progresses.
                snapshot = snapshot(exposures = improvement()),
                relation = relation(topReps = 20),
                window = window(level = 2)
            )
        )

        assertEquals(AdaptiveAction.PROGRESS, result.requestedAction)
        assertEquals(AdaptiveAction.HOLD, result.decision.action)
        assertEquals(
            "a filtered change is recorded as a hold with the refusal as its reason, and the " +
                "requested action stays readable (§18)",
            ProgramAdaptiveReason.AGGREGATE_LOAD_GUARD, result.reason
        )
        assertTrue(result.wasFilteredByTheGuard)
        assertNull(result.adjustment)
        assertNotEquals(
            "the guard does not invent a regression (§18)",
            AdaptiveAction.REGRESS, result.decision.action
        )

        val verdict = result.guard as ProgramGuardVerdict.Filtered
        assertEquals(ProgramGuardReason.EXCESSIVE_AUTOMATIC_INCREASE, verdict.reason)
    }

    @Test
    fun aRecentContextStillOwingWorkFiltersARealignmentThroughTheGuard() {
        val result = engine.decide(
            request(
                snapshot = snapshot(
                    exposures = plateau(),
                    baselineLoad = profile(opportunities = 4, completedOpportunities = 1),
                    recentLoad = profile(opportunities = 4, completedOpportunities = 1)
                ),
                element = element(exerciseId = "mystery_press"),
                window = window(level = 2)
            )
        )

        assertEquals(AdaptiveAction.HOLD, result.decision.action)
        assertEquals(ProgramAdaptiveReason.AGGREGATE_LOAD_GUARD, result.reason)
        assertNull(result.adjustment)
        val verdict = result.guard as ProgramGuardVerdict.Filtered
        assertEquals(ProgramGuardReason.RECENT_LOAD_UNMET_OPPORTUNITY, verdict.reason)
    }

    // ------------------------------------------------------------------ HOLD and the vocabulary

    @Test
    fun aStableWindowHoldsAndProducesNoAdjustment() {
        val result = engine.decide(request(snapshot = snapshot(exposures = plateau())))

        assertEquals(AdaptiveAction.HOLD, result.decision.action)
        assertEquals(DecisionOutcome.NOT_APPLIED, result.decision.outcome)
        assertEquals(AdaptiveState.HOLD, result.state)
        assertNull(result.decision.adjustmentId)
        assertNull(result.adjustment)
        assertFalse(result.isApplied)
        assertFalse(
            "an applied decision is one that produced an adjustment — and this one produced none",
            result.wasFilteredByTheGuard
        )
        assertEquals(
            ProgramGuardVerdict.NotGuarded(AdaptiveScope.FAMILY, AdaptiveAction.HOLD), result.guard
        )
    }

    @Test
    fun aRestAdaptationIsUnsupportedAndStaysUnapplied() {
        val result = engine.decide(
            request(window = window(restChangeRequested = true))
        )

        assertEquals(AdaptiveAction.HOLD, result.decision.action)
        assertEquals(ProgramAdaptiveReason.REST_CHANGE_UNSUPPORTED, result.reason)
        assertNull(result.adjustment)
    }

    // ------------------------------------------------------------------ §16, the adjustment

    @Test
    fun anAdjustmentSupersedesByReferenceAndTheEarlierOneIsNeverRewritten() {
        val earlier = engine.decide(
            request(snapshot = snapshot(exposures = improvement()), window = window(level = 2))
        ).adjustment!!
        val later = engine.decide(
            request(
                snapshot = snapshot(exposures = improvement()),
                window = window(level = 2),
                supersedesAdjustmentId = earlier.adjustmentId,
                adjustmentId = AdjustmentId("adjustment-2")
            )
        ).adjustment!!

        assertEquals(earlier.adjustmentId, later.supersedesAdjustmentId)
        assertTrue(later.supersedesAnEarlierAdjustment)
        assertFalse(earlier.supersedesAnEarlierAdjustment)
        assertEquals(
            "the superseded adjustment keeps describing exactly what it described",
            PRESENTED, earlier.before.exerciseId
        )
        assertEquals(HARDER, earlier.after.exerciseId)
    }

    @Test
    fun anAdjustmentChangesSomethingAndKeepsTheOccurrenceIdentity() {
        val adjustment = engine.decide(
            request(snapshot = snapshot(exposures = improvement()), window = window(level = 2))
        ).adjustment!!

        assertNotEquals(adjustment.before, adjustment.after)
        assertEquals(adjustment.before.programExerciseId, adjustment.after.programExerciseId)
    }

    @Test
    fun theDecisionCarriesTheSlotTheRevisionAndTheMomentItWasGiven() {
        val result = engine.decide(
            request(snapshot = snapshot(exposures = improvement()), window = window(level = 2))
        )

        assertEquals(ProgramAdaptiveRig.PROGRAM_ID, result.decision.programId)
        assertEquals(
            "a decision never creates a revision: it is recorded against the revision in effect (§16)",
            ProgramAdaptiveRig.REVISION_ID, result.decision.revisionId
        )
        assertEquals(ProgramAdaptiveRig.SLOT_ID, result.decision.slotId)
        assertEquals(ProgramAdaptiveRig.DECIDED_AT, result.decision.decidedAt)
        assertEquals(ProgramAdaptiveRig.DECIDED_AT, result.adjustment!!.createdAt)
    }

    @Test
    fun aStepThatIsDeclaredButNotSingleIsUnavailableRatherThanChosenByTheEngine() {
        val levelAboveDeclaresTwo = ProgramProgressionRelation(
            familyId = FAMILY,
            variants = listOf(
                ProgramProgressionVariant(1, EASIER, ProgramAdaptiveRig.presentation(EASIER, 3, 8).prescription),
                ProgramProgressionVariant(2, PRESENTED, ProgramAdaptiveRig.presentation().prescription),
                ProgramProgressionVariant(
                    3, "pushups_decline", ProgramAdaptiveRig.presentation("pushups_decline").prescription
                ),
                ProgramProgressionVariant(3, HARDER, ProgramAdaptiveRig.presentation(HARDER).prescription)
            )
        )

        val result = engine.decide(
            request(
                snapshot = snapshot(exposures = improvement()),
                relation = levelAboveDeclaresTwo,
                window = window(level = 2)
            )
        )

        assertEquals(AdaptiveAction.PROGRESS, result.requestedAction)
        assertEquals(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE, result.reason)
        assertNull(
            "the relation does not single out one step, and the engine does not pick between two " +
                "declared alternatives",
            result.adjustment
        )
    }

    @Test
    fun everyAvailableExerciseStaysAvailableWhateverTheHomeExerciseIs() {
        // A guard against a fixture that quietly narrows the user's selection: the ordinary request
        // is made over the whole rig vocabulary.
        assertEquals(
            setOf(EASIER, PRESENTED, HARDER, SAME_LEVEL_ALTERNATIVE), EVERY_EXERCISE
        )
    }
}
