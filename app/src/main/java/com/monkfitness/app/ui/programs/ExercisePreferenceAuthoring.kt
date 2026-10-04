package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.program.ExercisePreference

/**
 * §30 step 28's **exercise preference** authoring, as **pure rules** the JVM tests can decide without a
 * Compose harness.
 *
 * The screen can show a ranking, but it must not *decide* one. Everything a screen would otherwise have
 * to compute about the order lives here or on [ExercisePreference] itself:
 *
 * ```text
 * addablePreferenceOptions   which catalogue entries may still be preferred
 * movementEnabled            whether "move up"/"move down" apply to one entry
 * ```
 *
 * ### Why so little
 *
 * Because the hard rules are already elsewhere, and duplicating them here would create the second source
 * of truth this stage exists to avoid:
 *
 *  * **the order** is [ExercisePreference]'s — it *is* the value, so the screen cannot hold a copy of it
 *    and drift from it;
 *  * **no duplicates** is refused by [ExercisePreference]'s own constructor, so a screen that tries to
 *    add a preferred exercise twice cannot produce a state the domain would accept;
 *  * **add / remove / move** are [ExercisePreference.preferring], [ExercisePreference.without] and
 *    [ExercisePreference.moved] — the same three methods the controller calls, so there is exactly one
 *    implementation of what "move up" means;
 *  * **whether an id names a real exercise** belongs to the controller, which is the layer holding the
 *    catalogue (§5's split).
 *
 * What is left here is the one thing that genuinely is presentation: which entries a picker should still
 * offer, and which of the two move buttons apply to a row. Both are *derived from* the draft's own value
 * and decide nothing about it.
 */

/**
 * The catalogue entries that may still be preferred: everything the catalogue offers, minus what the
 * draft already prefers.
 *
 * Filtering rather than disabling is deliberate. An exercise the user already prefers has nothing left to
 * be added — [ExercisePreference.preferring] would answer the same preference — so offering it would be
 * offering an action that cannot happen. The filter reads the draft's own list, so the offered set is a
 * function of the value the user is looking at, not of anything remembered separately.
 *
 * The catalogue's order is preserved untouched. This list is *not* a ranking and must never become one:
 * the order an exercise appears in a picker says nothing about how much the user would rather train it.
 *
 * @param preferred the draft's own preference.
 * @param options the catalogue entries the controller published.
 * @return the entries not already preferred, in catalogue order.
 */
fun addablePreferenceOptions(
    preferred: ExercisePreference,
    options: List<ExerciseOptionUi>
): List<ExerciseOptionUi> = options.filterNot { option -> option.exerciseId in preferred }

/**
 * Whether the two move buttons apply to the entry at [position] (1-based) of a preference of [size].
 *
 * The first entry has no place to move up to and the last none to move down to, so each is disabled at its
 * own end rather than having its tap clamped. That distinction is the whole point: a disabled button says
 * *"there is nowhere to go"* while a clamped no-op says *"you moved it and nothing happened"* — the second
 * is a silent failure the user cannot see (§33).
 *
 * Asked as a question rather than computed in the Composable so that the boundary is one rule, stated
 * once, instead of an `if` per row that each re-decide it.
 *
 * **Total on purpose — it never throws.** A Composable must not raise: an exception here would take the
 * screen down over a position the renderer produced, and there is nothing a user could do about it. A
 * position outside `1..size` is not a fact about the preference, so it is answered as *"this row can move
 * nowhere in either direction"* — the buttons are then disabled, which is both harmless and true of a row
 * that does not correspond to an entry. The domain's own [ExercisePreference.moved] is where an
 * out-of-range position is refused, and it refuses it loudly, because there the caller's index really is a
 * bug.
 *
 * @param position the entry's 1-based position in the preference.
 * @param size how many entries the preference holds.
 * @return whether each direction is available.
 */
fun movementEnabled(position: Int, size: Int): MovementAvailability =
    MovementAvailability(
        canMoveUp = position in 2..size,
        canMoveDown = position in 1 until size
    )

/**
 * Whether one entry may move in each direction — the answer to "is this at an end of the list", stated as
 * a value so a screen cannot enable a button whose operation would be a no-op.
 */
data class MovementAvailability(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)