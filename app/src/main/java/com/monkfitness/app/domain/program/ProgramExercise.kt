package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.prescription.Prescription

/**
 * Who authored one plan element.
 *
 * Regeneration is reconciliation, not replacement, and the precedence is what makes that possible: a
 * pinned element outranks everything, an explicit user change outranks compatible ones, and pure generated
 * content is the only thing a regenerate pass may freely replace.
 *
 * Only two facts are stored, because the four levels of that precedence are derivable from them plus
 * [ProgramExercise.isPinned]: a generated element the user then edited is [USER_AUTHORED], and
 * "compatible" versus "conflicting" is a comparison the editor makes when it reconciles, not a property
 * frozen here.
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
 * The identity is the occurrence, not the exercise: the same exercise used twice in a day is two elements
 * with two [ProgramExerciseId]s, so pinning, overriding or adapting one cannot silently change the other.
 * [exerciseId] is the Exercise Library key and stays opaque here — the domain never resolves it, so no
 * exercise catalogue, no equipment rule and no Android type reaches this model.
 *
 * [prescription] is owned by the plan, never by the library: editing what a program prescribes must not
 * touch exercise metadata, and a manual program's prescription is free of generator ranges.
 *
 * [focus] is the focus assignment this occurrence **represents** in the generated plan it came from: the
 * planner plans one element per assigned focus, in the assignment's own order, so the focus is a fact the
 * plan already holds rather than a classification this model infers.
 *
 * It is **nullable**, and that is the honest reading rather than a convenience default: a generated
 * element states one, while a user-authored or pinned element, a manual program and a row written before
 * the field existed all state `null` because the generator never said. No `DEFAULT` focus exists anywhere
 * in this model — a default would invent a claim that the element trains something, which no assignment
 * ever made. `null` means *no focus was recorded*, a different statement from *this element trains PUSH*.
 */
data class ProgramExercise(
    val programExerciseId: ProgramExerciseId,
    val exerciseId: String,
    val prescription: Prescription,
    val origin: ProgramExerciseOrigin,
    val isPinned: Boolean = false,
    val focus: Focus? = null
) {

    init {
        require(exerciseId.isNotBlank()) { "a plan element must name the exercise it uses" }
    }
}
