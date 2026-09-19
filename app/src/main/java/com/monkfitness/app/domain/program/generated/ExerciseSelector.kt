package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.program.Focus

/**
 * The **exercise selection** of the generator (§9) — which exercise realizes one focus of one slot.
 *
 * §9 states the priority, and this file implements exactly that order and nothing in front of it:
 *
 * ```text
 * user choice              >  hard execution constraints  >  adaptive preference
 *   >  progression need    >  recency/diversity            >  deterministic tie-break
 * ```
 *
 * Each level is one comparison in [ranked], in that order, and the mapping is worth reading level by
 * level because two of the six are *deliberate absences* rather than omissions:
 *
 * | §9 level | how it is honoured |
 * | --- | --- |
 * | user choice | [GenerationPreferences.userPreferredExerciseIds]. The user's own preference is the first thing compared, and it outranks an adaptive preference because the request states the two as separate lists — so this is a fact about the input, not an assumption about who is right. |
 * | hard execution constraints | already applied: [select] is only ever asked for [GenerationRequest.usableCandidatesFor], which excludes every exercise the available equipment cannot support and every exercise whose dimension cannot be prescribed. A constraint that has already removed a candidate cannot be violated by a later comparison. |
 * | adaptive preference | [GenerationPreferences.adaptivePreferredExerciseIds], the second comparison. |
 * | progression need | **not modelled in this stage.** Progression is the Adaptive Engine's own result (§11's `Progression Policy`, §30 step 11), and inventing one here would be implementing the stage this one is deliberately before. Nothing is placed above it and nothing pretends to be it; §9's order is preserved by simply leaving the level empty. |
 * | recency / diversity | three comparisons: how often the exercise is already used **in this cycle** (diversity), how often its **family** is used in this cycle (family balance — §12 keeps family load a dimension of its own), and how recently the exercise was used at all ([GenerationPreferences.recentExerciseIds]). |
 * | deterministic tie-break | the exercise's canonical id, ascending — never a list order, never a `Set`'s iteration order, never a hash. |
 *
 * ### What selection may not do
 *
 * It never mutates a library definition, never resolves an Android resource and never reads a
 * catalogue: it reads the candidates the caller supplied and returns one of them (§10, §25). It never
 * repeats an exercise *instead of* choosing: repeating is allowed (§9) and every occurrence is its own
 * plan element with its own identity — which the editor that materializes the plan is responsible for,
 * and which is why this file mints nothing.
 *
 * @param E the caller's equipment vocabulary.
 */
object ExerciseSelector {

    /**
     * The candidate that realizes [focus] for the next slot, or `null` when none may be chosen.
     *
     * The ranking is total and deterministic, so the first element of the result is *the* choice: two
     * calls with the same request and the same cycle produce the same candidate, and no step of the
     * comparison can be reordered by a caller's collection order.
     *
     * @param request the whole request — the candidates, the equipment, the preferences and the policy.
     * @param focus the focus this element serves.
     * @param usesThisCycle how many times each exercise has already been chosen in this plan.
     * @param familyUsesThisCycle how many times each family has already been chosen in this plan.
     */
    fun <E> select(
        request: GenerationRequest<E>,
        focus: Focus,
        usesThisCycle: Map<String, Int>,
        familyUsesThisCycle: Map<String, Int>
    ): GenerationCandidate<E>? =
        ranked(request, focus, usesThisCycle, familyUsesThisCycle).firstOrNull()

    /**
     * Every candidate that may realize [focus], in §9's order — the selection as a comparable list.
     *
     * Exposed as a list rather than kept private because "which exercise was chosen, and by which
     * rule" is exactly the question a plan has to be able to answer, and because a test can then
     * assert the *order* rather than only its first element.
     */
    fun <E> ranked(
        request: GenerationRequest<E>,
        focus: Focus,
        usesThisCycle: Map<String, Int>,
        familyUsesThisCycle: Map<String, Int>
    ): List<GenerationCandidate<E>> =
        request.usableCandidatesFor(focus).sortedWith(
            compareBy(
                // 1. user choice (§9): absent means "the user did not ask for this one".
                { candidate: GenerationCandidate<E> -> positionIn(request.preferences.userPreferredExerciseIds, candidate.exerciseId) },
                // 3. adaptive preference (§9): a plain input, ranked below the user's own choice.
                { candidate: GenerationCandidate<E> -> positionIn(request.preferences.adaptivePreferredExerciseIds, candidate.exerciseId) },
                // 5a. diversity: an exercise this plan has not used yet comes first.
                { candidate: GenerationCandidate<E> -> usesThisCycle[candidate.exerciseId] ?: 0 },
                // 5b. family balance: a family this plan has not drawn on yet comes first.
                { candidate: GenerationCandidate<E> -> familyUsesThisCycle[candidate.familyId] ?: 0 },
                // 5c. recency: an exercise absent from the recent list has not been used at all and
                // comes first, then the oldest entry, and the entry used most recently comes last —
                // the list is "most recent first", so it is read backwards.
                { candidate: GenerationCandidate<E> -> recencyIn(request.preferences.recentExerciseIds, candidate.exerciseId) },
                // 6. deterministic tie-break: the canonical id, ascending.
                { candidate: GenerationCandidate<E> -> candidate.exerciseId }
            )
        )

    /**
     * Where [exerciseId] sits in a **preference** list (0 = most preferred), or a value larger than
     * any real position when it is not named there at all.
     *
     * "Absent" is a fact about a preference list, not a low rank: an exercise the user did not name
     * must sort *after* one they did, so the sentinel is a value no list index can produce rather
     * than `0`.
     */
    private fun positionIn(preferences: List<String>, exerciseId: String): Int =
        preferences.indexOf(exerciseId).let { position -> if (position < 0) ABSENT else position }

    /**
     * How recently [exerciseId] was used, as a key that sorts the **least** recently used first.
     *
     * [recent] is "most recent first", so the entry at index 0 is the *worst* candidate and the
     * entry at the last index is the best of the exercises that appear at all; an exercise that does
     * not appear has not been used recently and is better than every one that does. Negating the
     * position inverts the list's order, and the sentinel is smaller than any negated position, so
     * "never used" sorts first — which is §9's *"recency"* read as an avoidance rule rather than as a
     * preference for the newest entry.
     */
    private fun recencyIn(recent: List<String>, exerciseId: String): Int =
        recent.indexOf(exerciseId).let { position -> if (position < 0) NEVER_USED else -position }

    /** Larger than any position a preference list can address. */
    private const val ABSENT: Int = Int.MAX_VALUE

    /** Smaller than any negated position, so an exercise that was not recently used sorts first. */
    private const val NEVER_USED: Int = Int.MIN_VALUE
}
