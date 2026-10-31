package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.program.Focus

/**
 * Which exercise realizes one focus of one slot: user choice, then adaptive preference, then the
 * plan's own diversity and family balance, then recency, then the canonical id as a tie-break.
 *
 * Two of those levels are deliberate absences rather than omissions. Hard execution constraints are
 * not a comparison here: [select] is only ever asked for [GenerationRequest.usableCandidatesFor],
 * which has already removed every candidate the equipment cannot support — a constraint that already
 * removed a candidate cannot be violated by a later comparison. Progression need is not modelled at
 * all: progression is the Adaptive Engine's own result, and inventing one here would be implementing
 * the stage this one deliberately comes before.
 *
 * It reads only the candidates the caller supplied: no library definition is mutated, no resource
 * resolved, no catalogue read, and no identity minted — repeating an exercise is allowed, and each
 * occurrence gets its own identity in the editor that materializes the plan.
 */
object ExerciseSelector {

    /**
     * The candidate that realizes [focus] for the next slot, or `null` when none may be chosen.
     *
     * The ranking is total and deterministic, so the first element is *the* choice: the same request
     * and the same cycle produce the same candidate, and no comparison step can be reordered by a
     * caller's collection order.
     */
    fun <E> select(
        request: GenerationRequest<E>,
        focus: Focus,
        usesThisCycle: Map<String, Int>,
        familyUsesThisCycle: Map<String, Int>
    ): GenerationCandidate<E>? =
        ranked(request, focus, usesThisCycle, familyUsesThisCycle).firstOrNull()

    /**
     * Every candidate that may realize [focus], in ranking order — the selection as a comparable
     * list, exposed rather than kept private so a test can assert the *order* and not only its first
     * element.
     */
    fun <E> ranked(
        request: GenerationRequest<E>,
        focus: Focus,
        usesThisCycle: Map<String, Int>,
        familyUsesThisCycle: Map<String, Int>
    ): List<GenerationCandidate<E>> =
        request.usableCandidatesFor(focus).sortedWith(
            compareBy(
                // user choice: absent means "the user did not ask for this one".
                { candidate: GenerationCandidate<E> -> positionIn(request.preferences.userPreferredExerciseIds, candidate.exerciseId) },
                // adaptive preference: a plain input, ranked below the user's own choice.
                { candidate: GenerationCandidate<E> -> positionIn(request.preferences.adaptivePreferredExerciseIds, candidate.exerciseId) },
                // diversity: an exercise this plan has not used yet comes first.
                { candidate: GenerationCandidate<E> -> usesThisCycle[candidate.exerciseId] ?: 0 },
                // family balance: a family this plan has not drawn on yet comes first.
                { candidate: GenerationCandidate<E> -> familyUsesThisCycle[candidate.familyId] ?: 0 },
                // recency: an exercise absent from the recent list has not been used at all and
                // comes first, then the oldest entry, and the entry used most recently comes last —
                // the list is "most recent first", so it is read backwards.
                { candidate: GenerationCandidate<E> -> recencyIn(request.preferences.recentExerciseIds, candidate.exerciseId) },
                // deterministic tie-break: the canonical id, ascending.
                { candidate: GenerationCandidate<E> -> candidate.exerciseId }
            )
        )

    /**
     * Where [exerciseId] sits in a preference list (0 = most preferred), or a value larger than any
     * real position when it is not named there at all: "absent" is a fact about a preference list,
     * not a low rank, so the sentinel is a value no list index can produce rather than `0`.
     */
    private fun positionIn(preferences: List<String>, exerciseId: String): Int =
        preferences.indexOf(exerciseId).let { position -> if (position < 0) ABSENT else position }

    /**
     * How recently [exerciseId] was used, as a key that sorts the **least** recently used first.
     *
     * [recent] is "most recent first", so index 0 is the *worst* candidate; negating the position
     * inverts that order, and the sentinel is smaller than any negated position, so "never used"
     * sorts first — recency read as an avoidance rule, not as a preference for the newest entry.
     */
    private fun recencyIn(recent: List<String>, exerciseId: String): Int =
        recent.indexOf(exerciseId).let { position -> if (position < 0) NEVER_USED else -position }

    /** Larger than any position a preference list can address. */
    private const val ABSENT: Int = Int.MAX_VALUE

    /** Smaller than any negated position, so an exercise that was not recently used sorts first. */
    private const val NEVER_USED: Int = Int.MIN_VALUE
}
