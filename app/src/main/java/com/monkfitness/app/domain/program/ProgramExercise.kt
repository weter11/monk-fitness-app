package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.prescription.Prescription

/**
 * Who authored one plan element (§7).
 *
 * Regeneration is reconciliation, not replacement, and the precedence is what makes that possible:
 * a pinned element outranks everything, an explicit user change outranks compatible ones, and pure
 * generated content is the only thing a regenerate pass may freely replace.
 *
 * Only two facts are stored, because the four levels of that precedence are derivable from them plus
 * [ProgramExercise.isPinned]: a generated element the user then edited is [USER_AUTHORED], and
 * "compatible" versus "conflicting" is a comparison the editor makes when it reconciles, not a
 * property to be frozen here.
 */
enum class ProgramExerciseOrigin {

    /** The generator produced this element; a regenerate pass may replace it. */
    GENERATED,

    /** The user authored or edited this element; regeneration keeps it. */
    USER_AUTHORED
}

/**
 * One occurrence of one exercise in a day's plan.
 *
 * The identity is the occurrence, not the exercise: the same exercise used twice in a day is two
 * elements with two [ProgramExerciseId]s, so pinning, overriding or adapting one of them cannot
 * silently change the other (§9). [exerciseId] is the Exercise Library key and stays opaque here —
 * the domain never resolves it, so no exercise catalogue, no equipment rule and no Android type
 * reaches this model.
 *
 * [prescription] is owned by the plan, never by the library: editing what a program prescribes must
 * not touch exercise metadata (§10), and a manual program's prescription is free of generator
 * ranges.
 *
 * @property programExerciseId identity of this occurrence.
 * @property exerciseId the library key of the exercise performed here.
 * @property prescription what the element asks for, per set.
 * @property origin whether the generator or the user produced it.
 * @property isPinned whether the element is exempt from automatic change. Unpinning does not change
 *   the value — it only makes the element eligible for a future regenerate pass (§7).
 */
data class ProgramExercise(
    val programExerciseId: ProgramExerciseId,
    val exerciseId: String,
    val prescription: Prescription,
    val origin: ProgramExerciseOrigin,
    val isPinned: Boolean = false
) {

    init {
        require(exerciseId.isNotBlank()) { "a plan element must name the exercise it uses" }
    }
}
