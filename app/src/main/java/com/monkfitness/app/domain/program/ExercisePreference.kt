package com.monkfitness.app.domain.program

/**
 * The exercises **the user** would rather see in a generated plan, **most preferred first**.
 *
 * It is a *ranking of exercises the generator may choose from*, and nothing else. It is not the plan's
 * own content, not a user-authored [ProgramExercise] (which reconciliation preserves rather than
 * ranks), not pinning (a pin exempts an element from change; a preference exempts nothing), not
 * [FocusPlan] (which says what a plan is built **for**), and not a default: there is no built-in list
 * and no alphabetical fallback, so an absent preference is [NONE], which states that the user named
 * nothing.
 *
 * It is a value rather than a bare list because these are states a `List<String>` cannot refuse:
 *
 *  * **order is the meaning.** The list *is* the ranking — first is most preferred — so naming an
 *    exercise twice is refused, which is what stops *"most preferred"* from having two answers;
 *  * **an unknown id is refused.** The id must be a non-blank name; whether the exercise exists
 *    belongs to the layer that holds the catalogue, which this value cannot see;
 *  * **absent is empty, not ranked.** "No preference" and "these three in this order" are different
 *    facts, and this type never lets one be read as the other.
 *
 * It decides nothing about generation: hard execution constraints (equipment, usability) are applied
 * **above** this ranking, so a preference can only ever reorder exercises that were already eligible —
 * it can never widen the set.
 */
data class ExercisePreference(
    val exerciseIds: List<String> = emptyList()
) {

    init {
        require(exerciseIds.none { it.isBlank() }) {
            "a preference names exercises; a blank name is not an exercise: $exerciseIds"
        }
        require(exerciseIds.distinct().size == exerciseIds.size) {
            "a preference is an order, so an exercise is preferred once: $exerciseIds — naming one " +
                "twice would give 'most preferred' two answers"
        }
    }

    /** Whether the user has named anything at all. An absent preference is empty, never a default. */
    val isEmpty: Boolean
        get() = exerciseIds.isEmpty()

    val size: Int
        get() = exerciseIds.size

    /**
     * This preference with [exerciseId] added as the **least** preferred entry, or unchanged when it is
     * already named.
     *
     * Added at the end rather than the front on purpose: a user adds an exercise they would *like*, not
     * one they would rank above what they already chose. The result goes through [ExercisePreference]
     * again, so a duplicate cannot be introduced.
     */
    fun preferring(exerciseId: String): ExercisePreference =
        if (exerciseId in exerciseIds) this else ExercisePreference(exerciseIds + exerciseId)

    /**
     * This preference without [exerciseId], or unchanged when it names nothing.
     *
     * Every other position stays exactly where it was: removing a preference is not a re-ranking of the
     * ones that remain.
     */
    fun without(exerciseId: String): ExercisePreference =
        ExercisePreference(exerciseIds.filterNot { it == exerciseId })

    /**
     * This preference with [exerciseId] moved to [toPosition] (1-based, `1..size`).
     *
     * The only operation that genuinely *is* a re-ranking. The position is checked against this
     * preference's own size rather than clamped, because a caller that computed a stale index is a bug
     * and the honest answer names the value that was out of range.
     */
    fun moved(exerciseId: String, toPosition: Int): ExercisePreference {
        require(toPosition in 1..exerciseIds.size) {
            "a preference holds ${exerciseIds.size} exercise(s), so a position is 1..${exerciseIds.size}, " +
                "got $toPosition"
        }
        val index = exerciseIds.indexOf(exerciseId)
        require(index >= 0) {
            "this preference does not name '$exerciseId'; it names $exerciseIds"
        }
        val reordered = exerciseIds.toMutableList().apply {
            removeAt(index)
            add(toPosition - 1, exerciseId)
        }
        return ExercisePreference(reordered)
    }

    /**
     * Whether [exerciseId] is named at all — the question the authoring surface asks before it offers to
     * add one, and never a question about *where*.
     */
    operator fun contains(exerciseId: String): Boolean = exerciseId in exerciseIds

    companion object {

        /**
         * No preference at all: the user has named nothing.
         *
         * The absence is stated rather than implied — a Program with no preference is not a Program
         * whose preference happens to be every exercise in some order.
         */
        val NONE: ExercisePreference = ExercisePreference()

        /**
         * The preference of [exerciseIds], in the order given, refusing a duplicate or a blank name.
         *
         * The only way a preference is built, so the invariants above cannot be bypassed by a
         * constructor call somewhere else.
         */
        fun of(vararg exerciseIds: String): ExercisePreference = ExercisePreference(exerciseIds.toList())

        fun of(exerciseIds: List<String>): ExercisePreference = ExercisePreference(exerciseIds)
    }
}