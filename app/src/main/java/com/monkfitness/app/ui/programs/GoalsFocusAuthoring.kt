package com.monkfitness.app.ui.programs

import androidx.annotation.StringRes
import com.monkfitness.app.R
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan

/**
 * Goals & Focus authoring as pure rules the JVM tests can decide without a Compose harness.
 *
 * The draft's configuration is [com.monkfitness.app.domain.program.ProgramEditorDraft.focus] and nothing
 * else. [FocusPercentEntry] is the *dialog's own* working state while a CUSTOM allocation is still being
 * typed — never written to the draft, never persisted, never read back — and [toFocusPlan] is the only way
 * out of it; that is why it can hold an unfinished, invalid allocation at all, since an invalid `FocusPlan`
 * cannot be constructed.
 *
 * Validation is the domain's, not this file's: [toFocusPlan] hands the user's own numbers to
 * [FocusPlan.custom] and reports what it answered, so no rule here can disagree with the sum, the
 * positivity, the one-share-per-focus or the canonical order. [remainingPercent] is display arithmetic
 * over the numbers the user typed and decides nothing about whether a configuration is legal.
 */

/**
 * The focuses to train, with [focus] added or removed.
 *
 * Returns `null` for the one toggle the domain forbids: removing the **last** remaining focus, because a
 * plan that names no focus is the BALANCED configuration, so a FOCUSED plan with nothing in it cannot be
 * built — and the honest answer to "uncheck the only one" is that the state does not change.
 *
 * Built through [FocusPlan.focused], which holds the focuses in the vocabulary's own order: the order the
 * user happened to tap in must **not** become a hidden priority, so two selections naming the same focuses
 * are the same configuration and nothing downstream can read a rank out of the list.
 */
fun toggledFocus(current: FocusPlan, focus: Focus): FocusPlan? {
    val chosen = (current as? FocusPlan.Focused)?.focuses.orEmpty().toMutableSet()
    if (!chosen.remove(focus)) chosen.add(focus)
    if (chosen.isEmpty()) return null
    return FocusPlan.focused(chosen)
}

/**
 * The working percentages of the CUSTOM dialog: one whole percent per [Focus], `0` meaning *this focus is
 * not part of the plan*.
 *
 * A zero is not an allocation, so it is the absent value here too and never reaches [FocusAllocation].
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
     * It reports [FocusPlan.custom]'s own answer: an allocation that does not sum to
     * [FocusPlan.FULL_ALLOCATION], that states a focus twice or that is built in the wrong order is refused
     * **there**, and this returns `null` rather than inventing a correction — no percentage is computed,
     * rounded, defaulted or redistributed here.
     */
    fun toFocusPlan(): FocusPlan.Custom? = try {
        FocusPlan.custom(statedFocuses().map { focus -> FocusAllocation(focus, percentOf(focus)) })
    } catch (refused: IllegalArgumentException) {
        null
    }
}

/**
 * The whole focus vocabulary as its seven labels — the only labels this feature introduces for a [Focus].
 *
 * Exhaustive over [Focus.entries] on purpose: a focus added to the domain without a label here is a focus
 * the editor cannot show, and the [com.monkfitness.app.ui.programs.ProgramsLocalizationTest] census turns
 * that into a failing test rather than an English word on a foreign screen.
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