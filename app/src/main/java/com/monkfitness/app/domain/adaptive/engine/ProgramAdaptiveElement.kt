package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.workout.EffectiveExercise

/**
 * Whose change an adaptation would be. The principle is `USER CHOICE > PROGRAM STRUCTURE > ADAPTIVE
 * HEURISTICS`, and the stage's share of it is stated here: an element the user authored or pinned is
 * **not adapted at all** — no adaptive rule runs against it, so no path exists by which adaptive logic
 * can change, block or reinterpret a user's own choice. The engine's reason for holding can still tell
 * the two apart for an audit.
 */
enum class ProgramElementOwnership {

    AUTOMATIC,

    USER_AUTHORED,

    PINNED
}

/**
 * One plan element an adaptation is decided for: what the plan presents, and whose it is. Deliberately not
 * a second "effective workout" model — the presentation is the domain's own [EffectiveExercise] — and the
 * only facts added are the two it cannot state: the family (a caller classification, since the engine
 * resolves no exercise metadata) and the ownership.
 */
data class ProgramAdaptiveElement(
    val presentation: EffectiveExercise,
    val familyId: String,
    val ownership: ProgramElementOwnership = ProgramElementOwnership.AUTOMATIC
) {

    init {
        require(familyId.isNotBlank()) { "an adaptive element must name its family" }
    }

    val exerciseId: String
        get() = presentation.exerciseId

    val isAdaptable: Boolean
        get() = ownership == ProgramElementOwnership.AUTOMATIC
}
