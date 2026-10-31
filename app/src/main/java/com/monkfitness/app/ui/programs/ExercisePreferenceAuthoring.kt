package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.program.ExercisePreference

/**
 * Exercise preference authoring as pure rules the JVM tests can decide without a Compose harness.
 *
 * The screen may show a ranking but must not decide one: the order *is* [ExercisePreference]'s value, and
 * add / remove / move are its own methods, so a screen holding a copy would be the second source of truth.
 * What is left here is presentation only — which entries a picker may still offer, and which move buttons
 * apply to a row — and both are derived from the draft's value rather than deciding it.
 */

/**
 * The catalogue entries that may still be preferred: everything offered minus what the draft already
 * prefers.
 *
 * Filtering rather than disabling is deliberate: an entry already preferred has nothing left to add —
 * [ExercisePreference.preferring] would answer the same preference — so offering it would offer an action
 * that cannot happen. The catalogue's order is preserved untouched, and is *not* a ranking: the order an
 * exercise appears in a picker says nothing about how much the user would rather train it.
 */
fun addablePreferenceOptions(
    preferred: ExercisePreference,
    options: List<ExerciseOptionUi>
): List<ExerciseOptionUi> = options.filterNot { option -> option.exerciseId in preferred }

/**
 * Whether the two move buttons apply to the entry at [position] (1-based) of a preference of [size].
 *
 * The first entry has no place to move up to and the last none to move down to, so each is disabled at its
 * own end rather than having its tap clamped: a disabled button says "there is nowhere to go", while a
 * clamped no-op says "you moved it and nothing happened" — a silent failure the user cannot see.
 *
 * Total on purpose — it never throws. A Composable must not raise over a position the renderer produced,
 * so a position outside `1..size` answers "this row can move nowhere in either direction"; the domain's
 * own [ExercisePreference.moved] is where an out-of-range position is refused, loudly, because there the
 * caller's index really is a bug.
 */
fun movementEnabled(position: Int, size: Int): MovementAvailability =
    MovementAvailability(
        canMoveUp = position in 2..size,
        canMoveDown = position in 1 until size
    )

/**
 * Whether one entry may move in each direction, stated as a value so a screen cannot enable a button whose
 * operation would be a no-op.
 */
data class MovementAvailability(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)