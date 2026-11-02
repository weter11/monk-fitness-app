package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome

/**
 * One family's line after a completion: the display name the catalogue gave it (or `null` when the
 * catalogue does not know the family) and the sentence the screen renders.
 */
data class SessionOutcomeUi(
    val familyNameRes: Int?,
    val messageRes: Int
)

/**
 * The one place a reason token becomes user text.
 *
 * The tokens are the engine's own stable identifiers, never copy, so the mapping is a `when` over names
 * rather than over an enum: a code this build does not know — one a later engine adds, or one read back
 * from a row a newer version wrote — answers the neutral sentence instead of crashing or showing the
 * token itself. The family's name is not resolved here; the screen renders it from the resource the
 * catalogue named.
 */
internal object SessionOutcomeText {

    /** One line per family the pass named, in the pass's own order. A gap names no family, so it has none. */
    fun linesOf(
        outcomes: List<AdaptiveIntegrationOutcome>,
        familyNames: Map<String, Int>
    ): List<SessionOutcomeUi> = outcomes.mapNotNull { outcome ->
        val familyId = outcome.familyId ?: return@mapNotNull null
        SessionOutcomeUi(
            familyNameRes = familyNames[familyId],
            messageRes = messageResOf(outcome)
        )
    }

    fun messageResOf(outcome: AdaptiveIntegrationOutcome): Int = when (outcome) {
        is AdaptiveIntegrationOutcome.AdaptiveApplied -> changeText(outcome.reason.name)
        is AdaptiveIntegrationOutcome.AdaptiveFiltered -> R.string.programs_session_outcome_held_back
        is AdaptiveIntegrationOutcome.NothingToAdapt -> holdText(outcome.reason.name)
        is AdaptiveIntegrationOutcome.WindowOnly -> R.string.programs_session_outcome_window_only
        is AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest -> NEUTRAL
    }

    fun changeText(reasonCode: String): Int = when (reasonCode) {
        ProgramAdaptiveReason.SUSTAINED_POSITIVE.name -> R.string.programs_session_outcome_progressed
        ProgramAdaptiveReason.SUSTAINED_NEGATIVE.name -> R.string.programs_session_outcome_eased
        ProgramAdaptiveReason.VARIANT_REALIGNED.name -> R.string.programs_session_outcome_variant
        ProgramAdaptiveReason.RETURN_AFTER_GAP.name -> R.string.programs_session_outcome_return_after_gap
        ProgramAdaptiveReason.BELOW_TARGET_CORRECTION.name -> R.string.programs_session_outcome_below_target
        else -> NEUTRAL
    }

    fun holdText(reasonCode: String): Int = when (reasonCode) {
        ProgramAdaptiveReason.CEILING_REACHED.name -> R.string.programs_session_outcome_ceiling
        ProgramAdaptiveReason.FLOOR_REACHED.name -> R.string.programs_session_outcome_floor
        ProgramAdaptiveReason.USER_AUTHORED_ELEMENT.name -> R.string.programs_session_outcome_user_authored
        else -> NEUTRAL
    }

    private val NEUTRAL = R.string.programs_session_outcome_neutral
}
