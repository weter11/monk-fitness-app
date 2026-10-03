package com.monkfitness.app.ui.programs

import androidx.annotation.StringRes
import com.monkfitness.app.R
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan

/**
 * §7's *Goals & Focus* authoring, as **pure rules** the JVM tests can decide without a Compose harness.
 *
 * The three things a screen would otherwise have to compute are here instead, and each one exists only
 * because the domain refuses to do it for the screen:
 *
 * ```text
 * toggledFocus         FOCUSED's multi-select, refusing to empty itself
 * FocusPercentEntry    CUSTOM's working percentages, with the remaining share as a number
 * focusLabelRes        the seven Focus values and their seven labels
 * ```
 *
 * ### None of this is a second source of truth
 *
 * The draft's configuration is [com.monkfitness.app.domain.program.ProgramEditorDraft.focus] and
 * nothing else. [FocusPercentEntry] is the *dialog's own* working state while a CUSTOM allocation is
 * still being typed — it is never written to the draft, never persisted and never read back as the
 * draft's configuration, and [toFocusPlan] is the only way out of it. That is why it can hold an
 * unfinished, invalid allocation at all: an invalid `FocusPlan` cannot be constructed, so the
 * temporary state deliberately is not one.
 *
 * ### The validation is the domain's, not this file's
 *
 * [toFocusPlan] hands the user's own numbers to [FocusPlan.custom] and reports what it answered. There
 * is no second rule here that could disagree with §8's: the sum, the positivity, the "one share per
 * focus" and the canonical order are all decided by the constructor that refuses an invalid
 * configuration. [remainingPercent] is **display arithmetic over the numbers the user typed** — how
 * much of 100% is still unaccounted for — and it decides nothing about whether a configuration is
 * legal.
 */

/**
 * The focuses to train, with [focus] added or removed.
 *
 * Returns `null` for the one toggle the domain forbids: removing the **last** remaining focus. §8
 * says a plan that names no focus is the BALANCED configuration, so a FOCUSED plan with nothing in it
 * cannot be built — and the honest answer to "uncheck the only one" is that the state does not
 * change, not an empty plan.
 *
 * The result is built through [FocusPlan.focused], which holds the focuses in the vocabulary's own
 * order. That is what makes the order the user happened to tap in **not** become a hidden priority:
 * two selections that name the same focuses are the same configuration whatever order they were
 * assembled in, and nothing downstream can read a rank out of the list.
 *
 * @param current the configuration the toggle starts from; its [FocusPlan.Focused] focuses are the
 *   ones already chosen, and any other form contributes none.
 * @param focus the focus the user tapped.
 * @return the next FOCUSED configuration, or `null` when the toggle would leave none named.
 */
fun toggledFocus(current: FocusPlan, focus: Focus): FocusPlan? {
    val chosen = (current as? FocusPlan.Focused)?.focuses.orEmpty().toMutableSet()
    if (!chosen.remove(focus)) chosen.add(focus)
    if (chosen.isEmpty()) return null
    return FocusPlan.focused(chosen)
}

/**
 * The working percentages of §7's CUSTOM dialog: one whole percent per [Focus], `0` meaning *this
 * focus is not part of the plan*.
 *
 * A zero is not an allocation (§8: a focus the user did not ask for is left out of the configuration),
 * so it is the absent value here too and never reaches [FocusAllocation].
 */
data class FocusPercentEntry(
    val percents: Map<Focus, Int> = emptyMap()
) {

    /** The share [focus] is being given, or `0` while it is not part of the plan. */
    fun percentOf(focus: Focus): Int = percents[focus] ?: 0

    /** The next entry with [focus] set to [percent]; a value that is not a usable share is stored as `0`. */
    fun withPercent(focus: Focus, percent: Int): FocusPercentEntry =
        copy(percents = percents + (focus to (percent.takeIf { it > 0 } ?: 0)))

    /** The focuses this entry gives a positive share to, in the vocabulary's own order. */
    fun statedFocuses(): List<Focus> =
        FocusPlan.canonical(percents.filterValues { percent -> percent > 0 }.keys)

    /** How much of the 100% the user has not accounted for yet — a number to show, never a rule. */
    fun remainingPercent(): Int =
        FocusPlan.FULL_ALLOCATION - statedFocuses().sumOf { focus -> percentOf(focus) }

    /**
     * The finished CUSTOM configuration, or `null` while the entry does not describe one.
     *
     * It goes through [FocusPlan.custom] and reports that constructor's own answer: an allocation that
     * does not sum to [FocusPlan.FULL_ALLOCATION], that states a focus twice, or that is built in the
     * wrong order is refused **there**, and this returns `null` rather than inventing a correction.
     * No percentage is computed, rounded, defaulted or redistributed here — the user's numbers are
     * the configuration, or there is no configuration.
     */
    fun toFocusPlan(): FocusPlan.Custom? = try {
        FocusPlan.custom(statedFocuses().map { focus -> FocusAllocation(focus, percentOf(focus)) })
    } catch (refused: IllegalArgumentException) {
        null
    }
}

/**
 * The whole focus vocabulary, as the seven labels §8 names — the only labels this feature introduces
 * for a [Focus].
 *
 * Exhaustive over [Focus.entries] on purpose: a focus added to the domain without a label here is a
 * focus the editor cannot show, and the [com.monkfitness.app.ui.programs.ProgramsLocalizationTest]
 * census is what turns that into a failing test rather than an English word on a foreign screen.
 */
@StringRes
fun focusLabelRes(focus: Focus): Int = when (focus) {
    Focus.PUSH -> R.string.programs_focus_push
    Focus.PULL -> R.string.programs_focus_pull
    Focus.LEGS -> R.string.programs_focus_legs
    Focus.CORE -> R.string.programs_focus_core
    Focus.MOBILITY -> R.string.programs_focus_mobility
    Focus.POSTURE -> R.string.programs_focus_posture
    Focus.CONDITIONING -> R.string.programs_focus_conditioning
}