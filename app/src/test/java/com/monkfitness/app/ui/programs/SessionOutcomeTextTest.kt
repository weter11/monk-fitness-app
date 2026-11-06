package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * The one mapping from a reason token to a sentence, including for a token this build does not know.
 *
 * The codes are the engine's stable identifiers and they are never shown to the user: a code added by a
 * later engine must read as the neutral sentence, not as `SOME_NEW_CODE` on a finished workout's screen.
 */
class SessionOutcomeTextTest {

    private val plank = FamilyProgressionState(
        revisionId = RevisionId("revision-1"),
        familyId = "plank",
        progressionLevel = 1,
        adaptationState = AdaptiveState.HOLD,
        currentExerciseId = "plank",
        updatedAt = Instant.parse("2026-10-01T10:00:00Z")
    )

    private val shoulderMobility = FamilyProgressionState(
        revisionId = RevisionId("revision-1"),
        familyId = "shoulder_mobility",
        progressionLevel = 1,
        adaptationState = AdaptiveState.HOLD,
        currentExerciseId = "lat_stretch",
        updatedAt = Instant.parse("2026-10-01T10:00:00Z")
    )

    private val pushups = FamilyProgressionState(
        revisionId = RevisionId("revision-1"),
        familyId = "pushups",
        progressionLevel = 2,
        adaptationState = AdaptiveState.HOLD,
        currentExerciseId = "pushup",
        updatedAt = Instant.parse("2026-10-01T10:00:00Z")
    )

    @Test
    fun theRequiredReasonCodesReadAsSentences() {
        assertEquals(
            R.string.programs_session_outcome_ceiling,
            SessionOutcomeText.holdText(ProgramAdaptiveReason.CEILING_REACHED.name)
        )
        assertEquals(
            R.string.programs_session_outcome_user_authored,
            SessionOutcomeText.holdText(ProgramAdaptiveReason.USER_AUTHORED_ELEMENT.name)
        )
        assertEquals(
            R.string.programs_session_outcome_return_after_gap,
            SessionOutcomeText.changeText(ProgramAdaptiveReason.RETURN_AFTER_GAP.name)
        )
        assertEquals(
            R.string.programs_session_outcome_below_target,
            SessionOutcomeText.changeText(ProgramAdaptiveReason.BELOW_TARGET_CORRECTION.name)
        )
    }

    @Test
    fun aCodeThisBuildDoesNotKnowReadsNeutralAndIsNeverShown() {
        assertEquals(
            "a later engine's code must not surface as a token on the screen",
            R.string.programs_session_outcome_neutral,
            SessionOutcomeText.changeText("SOME_FUTURE_CODE")
        )
        assertEquals(
            "nor may a hold it does not know",
            R.string.programs_session_outcome_neutral,
            SessionOutcomeText.holdText("SOME_FUTURE_CODE")
        )
    }

    @Test
    fun aWindowOnlyFamilySaysItIsNotInTheNextWorkout() {
        val outcome = AdaptiveIntegrationOutcome.WindowOnly(
            familyId = "pushups",
            familyState = pushups,
            reason = ProgramAdaptiveReason.SUSTAINED_POSITIVE
        )

        assertEquals(
            "the family advanced its bookkeeping and nothing changed, which is a different sentence " +
                "from a hold",
            R.string.programs_session_outcome_window_only,
            SessionOutcomeText.messageResOf(outcome)
        )
    }

    @Test
    fun aFamilyWithNoFurtherAppearanceSaysThePlanEnded() {
        assertEquals(
            "the plan's end is a different sentence from \"not in your next workout\": one comes back",
            R.string.programs_session_outcome_no_further_appearance,
            SessionOutcomeText.windowText(ProgramAdaptiveReason.NO_FURTHER_APPEARANCE.name)
        )
        assertEquals(
            "and a window read for any other reason keeps the earlier sentence",
            R.string.programs_session_outcome_window_only,
            SessionOutcomeText.windowText(ProgramAdaptiveReason.STABLE_PLATEAU.name)
        )
    }

    @Test
    fun aManualProgrammeSaysWhyNothingIsAdapted() {
        val lines = SessionOutcomeText.linesOf(
            outcomes = listOf(
                AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest(
                    AdaptiveInputGap.PROGRAM_MODE_IS_MANUAL
                )
            ),
            familyNames = mapOf("pushups" to R.string.family_pushups)
        )

        assertEquals(
            "one plain line, and it names no family because a manual Program is nobody's window",
            1,
            lines.size
        )
        assertEquals(null, lines.single().familyNameRes)
        assertEquals(
            R.string.programs_session_outcome_manual_programme,
            lines.single().messageRes
        )
    }

    @Test
    fun aPlanWithBothKindsOfElementReadsEachOneByItsOwnOutcome() {
        val lines = SessionOutcomeText.linesOf(
            outcomes = listOf(
                appliedChange(),
                AdaptiveIntegrationOutcome.NothingToAdapt(
                    reason = ProgramAdaptiveReason.USER_AUTHORED_ELEMENT,
                    familyState = plank
                )
            ),
            familyNames = mapOf("pushups" to R.string.family_pushups, "plank" to R.string.family_plank)
        )

        assertEquals(2, lines.size)
        assertEquals(
            "the adaptable family reports the change the engine made",
            R.string.programs_session_outcome_progressed,
            lines[0].messageRes
        )
        assertEquals(
            "and the one the user kept reports that it was kept, in the same list",
            R.string.programs_session_outcome_user_authored,
            lines[1].messageRes
        )
        assertEquals(null, lines[0].noteRes)
    }

    @Test
    fun aRowHeldInsideItsOwnRangeSaysSoBesideTheChange() {
        val line = SessionOutcomeText.linesOf(
            outcomes = listOf(appliedChange(clampedRows = listOf("element-2"))),
            familyNames = mapOf("pushups" to R.string.family_pushups)
        ).single()

        assertEquals(R.string.programs_session_outcome_progressed, line.messageRes)
        assertEquals(R.string.programs_session_outcome_row_limited, line.noteRes)
    }

    @Test
    fun theRangeCeilingAndTheRepeatedLowerBoundEachReadAsTheirOwnSentence() {
        assertEquals(
            R.string.programs_session_outcome_upper_bound,
            SessionOutcomeText.holdText(ProgramAdaptiveReason.HELD_AT_UPPER_BOUND.name)
        )
        assertEquals(
            R.string.programs_session_outcome_easier_variant,
            SessionOutcomeText.holdText(ProgramAdaptiveReason.EASIER_VARIANT_SUGGESTED.name)
        )
        assertEquals(
            R.string.programs_session_outcome_too_heavy,
            SessionOutcomeText.holdText(ProgramAdaptiveReason.TOO_HEAVY_FOR_THE_RANGE.name)
        )
    }

    @Test
    fun oneLinePerFamilyInThePassesOrderAndNoneForAGap() {
        val lines = SessionOutcomeText.linesOf(
            outcomes = listOf(
                AdaptiveIntegrationOutcome.NothingToAdapt(
                    reason = ProgramAdaptiveReason.CEILING_REACHED,
                    familyState = pushups
                ),
                AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest(AdaptiveInputGap.NO_FUTURE_SLOT)
            ),
            familyNames = mapOf("pushups" to R.string.family_pushups)
        )

        assertEquals(
            "a family the pass named has a line; the gap names no family, so it has none",
            1,
            lines.size
        )
        assertEquals(R.string.family_pushups, lines.single().familyNameRes)
        assertEquals(R.string.programs_session_outcome_ceiling, lines.single().messageRes)
    }

    @Test
    fun aFamilyTheCatalogueCannotNameStillGetsItsLine() {
        val lines = SessionOutcomeText.linesOf(
            outcomes = listOf(
                AdaptiveIntegrationOutcome.NothingToAdapt(
                    reason = ProgramAdaptiveReason.CEILING_REACHED,
                    familyState = pushups
                )
            ),
            familyNames = emptyMap()
        )

        assertEquals(
            "an unnamed family is rendered without a name rather than dropped or shown under its id",
            null,
            lines.single().familyNameRes
        )
        assertEquals(R.string.programs_session_outcome_ceiling, lines.single().messageRes)
    }

    @Test
    fun aLineLeadsWithTheExerciseTheUserPerformedAndKeepsTheFamily() {
        val line = shoulderLine(performed = listOf(R.string.ex_lat_stretch))

        assertEquals(
            "the exercise the user trained, not the family's own name",
            listOf(R.string.ex_lat_stretch),
            line.performedNameRes
        )
        assertEquals(
            "and the family stays named, because the change applies to the whole family",
            R.string.family_shoulder_mobility,
            line.familyNameRes
        )
        assertEquals(
            SessionOutcomeShape.PERFORMED_AND_FAMILY,
            SessionOutcomeText.shapeOf(listOf("Lat Stretch"), "Shoulder CARs")
        )
    }

    @Test
    fun bothExercisesOfOneFamilyAreListedInTheOrderPerformed() {
        val line = shoulderLine(
            performed = listOf(R.string.ex_lat_stretch, R.string.ex_arm_circles)
        )

        assertEquals(
            "both, in the session's own order, with nothing dropped",
            listOf(R.string.ex_lat_stretch, R.string.ex_arm_circles),
            line.performedNameRes
        )
        assertEquals(
            "two names, neither of them the family, read as both",
            SessionOutcomeShape.PERFORMED_AND_FAMILY,
            SessionOutcomeText.shapeOf(listOf("Lat Stretch", "Arm Circles"), "Shoulder CARs")
        )
    }

    @Test
    fun anExerciseNamedLikeItsFamilyIsShownOnce() {
        val line = shoulderLine(performed = listOf(R.string.ex_shoulder_cars))

        assertEquals(listOf(R.string.ex_shoulder_cars), line.performedNameRes)
        assertEquals(
            "the same word must not be printed twice: the family label adds nothing here",
            SessionOutcomeShape.PERFORMED,
            SessionOutcomeText.shapeOf(listOf("Shoulder CARs"), "Shoulder CARs")
        )
    }

    @Test
    fun aFamilyTheAttemptDidNotTrainKeepsTheLineItHad() {
        assertEquals(
            "the plan's end still reads as the plan's end",
            R.string.programs_session_outcome_no_further_appearance,
            SessionOutcomeText.windowText(ProgramAdaptiveReason.NO_FURTHER_APPEARANCE.name)
        )
        assertEquals(
            "and a row the user wrote still says it was kept",
            R.string.programs_session_outcome_user_authored,
            SessionOutcomeText.holdText(ProgramAdaptiveReason.USER_AUTHORED_ELEMENT.name)
        )

        val line = SessionOutcomeText.linesOf(
            outcomes = listOf(
                AdaptiveIntegrationOutcome.WindowOnly(
                    familyId = "shoulder_mobility",
                    familyState = shoulderMobility,
                    reason = ProgramAdaptiveReason.SUSTAINED_POSITIVE
                )
            ),
            familyNames = mapOf("shoulder_mobility" to R.string.family_shoulder_mobility),
            performedNames = emptyMap()
        ).single()

        assertEquals("no name was added to a line that had none", emptyList<Int>(), line.performedNameRes)
        assertEquals(
            SessionOutcomeShape.FAMILY,
            SessionOutcomeText.shapeOf(emptyList(), "Shoulder CARs")
        )
    }

    /** One line for the shoulder-mobility family as the session controller feeds it. */
    private fun shoulderLine(performed: List<Int>): SessionOutcomeUi = SessionOutcomeText.linesOf(
        outcomes = listOf(
            AdaptiveIntegrationOutcome.WindowOnly(
                familyId = "shoulder_mobility",
                familyState = shoulderMobility,
                reason = ProgramAdaptiveReason.SUSTAINED_POSITIVE
            )
        ),
        familyNames = mapOf("shoulder_mobility" to R.string.family_shoulder_mobility),
        performedNames = mapOf("shoulder_mobility" to performed)
    ).single()

    /** An applied change of the pushups family, with the rows it had to keep inside their own ranges. */
    private fun appliedChange(
        clampedRows: List<String> = emptyList()
    ): AdaptiveIntegrationOutcome.AdaptiveApplied = AdaptiveIntegrationOutcome.AdaptiveApplied(
        decision = AdaptiveDecision(
            decisionId = DECISION_ID,
            programId = ProgramId("program-1"),
            revisionId = RevisionId("revision-1"),
            slotId = SlotId("slot-1"),
            target = AdaptiveTarget.Exercise("pushup"),
            action = AdaptiveAction.PROGRESS,
            outcome = DecisionOutcome.APPLIED,
            evidence = EvidenceLevel.STRONG,
            confidence = ConfidenceLevel.HIGH,
            recovery = RecoveryContext.FAVORABLE,
            decidedAt = Instant.parse("2026-10-01T10:00:00Z"),
            adjustmentId = ADJUSTMENT_ID,
            reason = ProgramAdaptiveReason.SUSTAINED_POSITIVE
        ),
        adjustment = AdaptiveAdjustment(
            adjustmentId = ADJUSTMENT_ID,
            decisionId = DECISION_ID,
            slotId = SlotId("slot-1"),
            before = EffectiveExercise(ProgramExerciseId("element-1"), "pushup", RepPrescription.uniform(3, 8)),
            after = EffectiveExercise(ProgramExerciseId("element-1"), "pushup", RepPrescription.uniform(3, 10)),
            createdAt = Instant.parse("2026-10-01T10:00:00Z")
        ),
        familyState = pushups,
        reason = ProgramAdaptiveReason.SUSTAINED_POSITIVE,
        clampedRows = clampedRows
    )

    private companion object {

        val DECISION_ID: DecisionId = DecisionId("decision-1")

        val ADJUSTMENT_ID: AdjustmentId = AdjustmentId("adjustment-1")
    }
}
