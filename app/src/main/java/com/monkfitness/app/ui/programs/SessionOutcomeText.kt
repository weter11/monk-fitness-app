package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome

/**
 * One family's line after a completion: the display name the catalogue gave it (or `null` when the
 * catalogue does not know the family), the exercise names the attempt actually performed for it, the
 * sentence the screen renders, and a second sentence when the outcome has a fact the first one does not
 * carry.
 */
data class SessionOutcomeUi(
    val familyNameRes: Int?,
    val messageRes: Int,
    val noteRes: Int? = null,
    /** The exercises this attempt confirmed work for in the family, in the order it presents them. */
    val performedNameRes: List<Int> = emptyList()
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
        familyNames: Map<String, Int>,
        performedNames: Map<String, List<Int>> = emptyMap()
    ): List<SessionOutcomeUi> = outcomes.mapNotNull { outcome ->
        // A manual Program is not adapted at all, and its gap names no family: the line it earns is the
        // one that says so, rather than silence on a finished workout.
        if (outcome is AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest &&
            outcome.gap == AdaptiveInputGap.PROGRAM_MODE_IS_MANUAL
        ) {
            return@mapNotNull SessionOutcomeUi(
                familyNameRes = null,
                messageRes = R.string.programs_session_outcome_manual_programme
            )
        }
        val familyId = outcome.familyId ?: return@mapNotNull null
        SessionOutcomeUi(
            familyNameRes = familyNames[familyId],
            messageRes = messageResOf(outcome),
            // A change that had to keep one of the family's other rows inside its own range says so beside
            // the change: the two rows carry different targets, and that is the user's decision, not a
            // contradiction the screen should hide.
            noteRes = (outcome as? AdaptiveIntegrationOutcome.AdaptiveApplied)
                ?.takeIf { applied -> applied.clampedRows.isNotEmpty() }
                ?.let { R.string.programs_session_outcome_row_limited },
            // The family label names the scope of a change, not the exercise the user trained: a line for
            // "Shoulder CARs" under a Lat Stretch says the wrong mother exercise twice over.
            performedNameRes = performedNames[familyId].orEmpty()
        )
    }

    /**
     * How much of a line is a name: the exercises the attempt performed, then the family the change
     * belongs to.
     *
     * The family label is left out only when it is already one of the performed names, so the same word
     * is never printed twice. Comparing the *resolved* names is what keeps that rule localizable.
     */
    fun shapeOf(performedNames: List<String>, familyName: String?): SessionOutcomeShape = when {
        familyName == null -> SessionOutcomeShape.SENTENCE
        performedNames.isEmpty() -> SessionOutcomeShape.FAMILY
        performedNames.contains(familyName) -> SessionOutcomeShape.PERFORMED
        else -> SessionOutcomeShape.PERFORMED_AND_FAMILY
    }

    fun messageResOf(outcome: AdaptiveIntegrationOutcome): Int = when (outcome) {
        is AdaptiveIntegrationOutcome.AdaptiveApplied -> changeText(outcome.reason.name)
        is AdaptiveIntegrationOutcome.AdaptiveFiltered -> R.string.programs_session_outcome_held_back
        is AdaptiveIntegrationOutcome.NothingToAdapt -> holdText(outcome.reason.name)
        is AdaptiveIntegrationOutcome.WindowOnly -> windowText(outcome.reason.name)
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
        ProgramAdaptiveReason.HELD_AT_LOWER_BOUND.name -> R.string.programs_session_outcome_lower_bound
        ProgramAdaptiveReason.HELD_AT_UPPER_BOUND.name -> R.string.programs_session_outcome_upper_bound
        ProgramAdaptiveReason.EASIER_VARIANT_SUGGESTED.name ->
            R.string.programs_session_outcome_easier_variant

        ProgramAdaptiveReason.TOO_HEAVY_FOR_THE_RANGE.name ->
            R.string.programs_session_outcome_too_heavy

        ProgramAdaptiveReason.USER_AUTHORED_ELEMENT.name -> R.string.programs_session_outcome_user_authored
        else -> NEUTRAL
    }

    /**
     * A family whose window was read with nothing to attach a change to. The plan's end is worth saying
     * differently from "not in your next workout": the second one comes back next week, the first never
     * does.
     */
    fun windowText(reasonCode: String): Int = when (reasonCode) {
        ProgramAdaptiveReason.NO_FURTHER_APPEARANCE.name ->
            R.string.programs_session_outcome_no_further_appearance

        else -> R.string.programs_session_outcome_window_only
    }

    private val NEUTRAL = R.string.programs_session_outcome_neutral
}

/**
 * The parts of a line that carry a name, as the screen assembles it: the sentence alone, the family's
 * label alone, the performed exercises alone, or both.
 */
enum class SessionOutcomeShape {
    SENTENCE,
    FAMILY,
    PERFORMED,
    PERFORMED_AND_FAMILY
}
