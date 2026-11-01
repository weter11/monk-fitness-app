package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.common.RevisionId
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
}
