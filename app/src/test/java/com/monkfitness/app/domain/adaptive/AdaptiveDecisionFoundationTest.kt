package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The decision vocabulary: what the adaptive stage may ask for, what it decided about, and how the
 * decision and the adjustment it produced stay consistent with each other.
 *
 * The invariants pinned here are the ones that keep a decision *auditable*: an applied decision has
 * exactly one adjustment, a decision that changed nothing is recorded as `NOT_APPLIED` rather than
 * deleted, `HOLD` is never applied, and an adjustment always changes exactly one presented element —
 * before and after are the same occurrence, and they are not the same value.
 */
class AdaptiveDecisionFoundationTest {

    private val decidedAt: Instant = Instant.parse("2026-09-18T09:00:00Z")

    // ------------------------------------------------------------------ vocabularies

    @Test
    fun theActionSpaceIsTheFiveActionsTheArchitectureLocks() {
        assertEquals(
            listOf("HOLD", "PROGRESS", "REGRESS", "CHANGE_VARIANT", "CHANGE_REST"),
            AdaptiveAction.entries.map { it.name }
        )
    }

    @Test
    fun aDecisionEndsEitherAppliedOrNotApplied() {
        assertEquals(listOf("APPLIED", "NOT_APPLIED"), DecisionOutcome.entries.map { it.name })
    }

    @Test
    fun aTargetCarriesItsOwnScopeSoTheTwoCannotDisagree() {
        assertEquals(AdaptiveScope.EXERCISE, AdaptiveTarget.Exercise("pushups").scope)
        assertEquals(AdaptiveScope.FAMILY, AdaptiveTarget.Family("push-family").scope)
        assertEquals(AdaptiveScope.FOCUS, AdaptiveTarget.Focus("push").scope)
        assertEquals(AdaptiveScope.SESSION, AdaptiveTarget.Session.scope)

        assertRejects("an exercise target with no exercise") { AdaptiveTarget.Exercise(" ") }
        assertRejects("a family target with no family") { AdaptiveTarget.Family("") }
        assertRejects("a focus target with no focus") { AdaptiveTarget.Focus(" ") }
    }

    // ------------------------------------------------------------------ the decision

    @Test
    fun anAppliedDecisionProducedExactlyOneAdjustmentAndARejectedOneProducedNone() {
        val applied = decision(action = AdaptiveAction.PROGRESS, adjustmentId = AdjustmentId("adjustment-1"))
        assertTrue(applied.isApplied)
        assertEquals(AdjustmentId("adjustment-1"), applied.adjustmentId)

        // The load guard filtered this one out: it stays recorded, without an adjustment.
        val filtered = decision(
            action = AdaptiveAction.PROGRESS,
            outcome = DecisionOutcome.NOT_APPLIED,
            adjustmentId = null
        )
        assertFalse(filtered.isApplied)

        assertRejects("an APPLIED decision with no adjustment") {
            decision(action = AdaptiveAction.PROGRESS, adjustmentId = null)
        }
        assertRejects("a NOT_APPLIED decision that produced an adjustment") {
            decision(
                action = AdaptiveAction.PROGRESS,
                outcome = DecisionOutcome.NOT_APPLIED,
                adjustmentId = AdjustmentId("adjustment-1")
            )
        }
    }

    @Test
    fun holdIsARealResultAndNeverAnAppliedChange() {
        val held = decision(
            action = AdaptiveAction.HOLD,
            outcome = DecisionOutcome.NOT_APPLIED,
            adjustmentId = null
        )
        assertFalse(held.isApplied)

        assertRejects("an applied HOLD") {
            decision(action = AdaptiveAction.HOLD, adjustmentId = AdjustmentId("adjustment-1"))
        }
    }

    @Test
    fun aDecisionRecordsTheGroundsItWasMadeOn() {
        val decision = decision(action = AdaptiveAction.PROGRESS, adjustmentId = AdjustmentId("adjustment-1"))

        assertEquals(EvidenceLevel.STABLE, decision.evidence)
        assertEquals(ConfidenceLevel.MODERATE, decision.confidence)
        assertEquals(RecoveryContext.CAUTIOUS, decision.recovery)
        assertEquals(SlotId("slot-5"), decision.slotId)
        assertEquals(AdaptiveTarget.Exercise("pushups"), decision.target)
    }

    // ------------------------------------------------------------------ the adjustment

    @Test
    fun anAdjustmentChangesOneElementByOneStepAndKeepsBothSidesOfTheChange() {
        val adjustment = adjustment(reps = 8, newReps = 10)

        assertEquals(8, (adjustment.before.prescription as RepPrescription).targetForSet(1))
        assertEquals(10, (adjustment.after.prescription as RepPrescription).targetForSet(1))
        assertEquals(adjustment.before.programExerciseId, adjustment.after.programExerciseId)
        assertFalse(adjustment.supersedesAnEarlierAdjustment)

        assertRejects("an adjustment that changes nothing") {
            adjustment(reps = 10, newReps = 10)
        }
        assertRejects("an adjustment that swaps in a different presented element") {
            adjustment(
                reps = 8,
                newReps = 10,
                afterElementId = "element-2"
            )
        }
    }

    @Test
    fun anAdjustmentMaySupersedeAnEarlierOneForTheSameSlotButNotItself() {
        val first = adjustment(reps = 10, newReps = 12, id = "adjustment-1", supersedes = null)
        val second = adjustment(
            reps = 12,
            newReps = 15,
            id = "adjustment-2",
            supersedes = "adjustment-1"
        )

        assertTrue(second.supersedesAnEarlierAdjustment)
        assertEquals(AdjustmentId("adjustment-1"), second.supersedesAdjustmentId)
        assertEquals("the superseded adjustment is not rewritten", 12,
            (first.after.prescription as RepPrescription).targetForSet(1))

        assertRejects("an adjustment that supersedes itself") {
            adjustment(reps = 10, newReps = 12, id = "adjustment-1", supersedes = "adjustment-1")
        }
    }

    @Test
    fun anAdjustmentChangesThePresentationAndNeverTheRevision() {
        val adjustment = adjustment(reps = 8, newReps = 10)

        // An adjustment carries no revision, no mode and no program structure: it is a delta that the
        // snapshot consumes, not an edit to the plan (§16).
        val fields = AdaptiveAdjustment::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                "adjustmentId", "decisionId", "slotId", "before", "after", "createdAt",
                "supersedesAdjustmentId"
            ),
            fields
        )
        assertTrue(fields.none { it.contains("revision", ignoreCase = true) })
    }

    // ------------------------------------------------------------------ helpers

    private fun decision(
        action: AdaptiveAction,
        outcome: DecisionOutcome = DecisionOutcome.APPLIED,
        adjustmentId: AdjustmentId? = AdjustmentId("adjustment-1")
    ) = AdaptiveDecision(
        decisionId = DecisionId("decision-1"),
        programId = ProgramId("program-1"),
        revisionId = RevisionId("rev-1"),
        slotId = SlotId("slot-5"),
        target = AdaptiveTarget.Exercise("pushups"),
        action = action,
        outcome = outcome,
        evidence = EvidenceLevel.STABLE,
        confidence = ConfidenceLevel.MODERATE,
        recovery = RecoveryContext.CAUTIOUS,
        decidedAt = decidedAt,
        adjustmentId = adjustmentId
    )

    private fun adjustment(
        reps: Int,
        newReps: Int,
        id: String = "adjustment-1",
        supersedes: String? = null,
        afterElementId: String = "element-1"
    ) = AdaptiveAdjustment(
        adjustmentId = AdjustmentId(id),
        decisionId = DecisionId("decision-1"),
        slotId = SlotId("slot-5"),
        before = element("element-1", reps),
        after = element(afterElementId, newReps),
        createdAt = decidedAt,
        supersedesAdjustmentId = supersedes?.let { AdjustmentId(it) }
    )

    private fun element(id: String, reps: Int) = EffectiveExercise(
        programExerciseId = ProgramExerciseId(id),
        exerciseId = "pushups",
        prescription = RepPrescription(listOf(reps))
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
