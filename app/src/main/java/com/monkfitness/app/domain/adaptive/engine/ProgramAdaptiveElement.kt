package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.workout.EffectiveExercise

/**
 * Whose change an adaptation would be (§18: *"only automatic changes are guarded"*, §15: *"Adaptive
 * never changes pinned choices"*).
 *
 * The architecture's guiding principle is `USER CHOICE > PROGRAM STRUCTURE > ADAPTIVE HEURISTICS`, and
 * the adaptive stage's share of it is stated exactly here: an element the user authored or pinned is
 * **not adapted at all**. It is not filtered by the load guard, and it is not held for a policy
 * reason — no adaptive rule ever runs against it, so there is no path by which adaptive logic can
 * change, block or reinterpret a user's own choice.
 *
 * The distinction the three values keep is the one §7 reconciles on: `AUTOMATIC` is the generator's
 * content, `USER_AUTHORED` is an element the user modified, and `PINNED` is an element the user fixed
 * in place. The adaptive stage treats the last two alike — as not its business — while the engine's
 * *reason* for holding can still tell them apart for an audit.
 */
enum class ProgramElementOwnership {

    /** The plan's own automatic content: the only thing an adaptation may change. */
    AUTOMATIC,

    /** An element the user authored or overrode. */
    USER_AUTHORED,

    /** An element the user pinned. */
    PINNED
}

/**
 * One plan element an adaptation is being decided for: what the plan presents, and whose it is.
 *
 * This is deliberately not a second "effective workout" model. The presentation is the domain's own
 * [EffectiveExercise] — the same value `presentedWorkout` composes from a revision and its standing
 * adjustments (§16), and the same value an [AdaptiveAdjustment] carries as its `before` — and the only
 * fact added beside it is the two the presentation itself cannot state: the family the element's
 * exercise belongs to (§9's selection axis and §17's family load dimension) and the ownership above.
 *
 * Both are caller facts, supplied explicitly. The engine resolves no exercise metadata and reads no
 * library: an element's family is a classification the caller owns, exactly as the pre-existing signal
 * layer takes its exercise-to-family map as a parameter.
 *
 * @property presentation what the plan presents for this element right now.
 * @property familyId the family the presented exercise belongs to, by the caller's own classification.
 * @property ownership whether this element is the plan's automatic content or the user's.
 */
data class ProgramAdaptiveElement(
    val presentation: EffectiveExercise,
    val familyId: String,
    val ownership: ProgramElementOwnership = ProgramElementOwnership.AUTOMATIC
) {

    init {
        require(familyId.isNotBlank()) { "an adaptive element must name its family" }
    }

    /** The exercise the element currently presents. */
    val exerciseId: String
        get() = presentation.exerciseId

    /** Whether an adaptation is allowed to change this element at all (§18, §15). */
    val isAdaptable: Boolean
        get() = ownership == ProgramElementOwnership.AUTOMATIC
}
