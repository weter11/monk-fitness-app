package com.monkfitness.app.domain.program

/**
 * The exercises **the user** would rather see in a generated plan, **most preferred first** (§9's first
 * priority level).
 *
 * ### What this is, stated as an exclusion
 *
 * This is a *ranking of exercises the generator may choose from*, and nothing else in the codebase
 * means that. It is deliberately **not**:
 *
 *  * the plan's own content — a draft's days and [ProgramExercise] elements are what a Program
 *    currently plans, which is a different question from what a future pass should reach for;
 *  * a [ProgramExerciseOrigin.USER_AUTHORED] element — that is *plan content the user put there*, and
 *    reconciliation preserves it (§7) rather than ranking it against anything;
 *  * pinning — a pin exempts one element from automatic change (§7); a preference expresses no
 *    exemption at all;
 *  * Goals & Focus — [FocusPlan] says what a plan is built **for**; this says what it should **reach
 *    for**;
 *  * an adaptive preference, a recency list or any other of §8's signals — those are
 *    `GenerationPreferences`' other five fields, each with its own semantic unit;
 *  * a *default*. There is no built-in list, no alphabetical fallback and no "favourites": an
 *    absent preference is [NONE], which states that the user named nothing (§8's plain-input
 *    discipline, applied to the user rather than to the planner).
 *
 * ### Why it is a value and not a bare list
 *
 * The invariants below are the reason. They are all states a `List<String>` cannot refuse:
 *
 *  * **order is the meaning.** The list *is* the ranking — first is most preferred — so a list that
 *    could be reordered after the fact without changing what it means would not be stating a
 *    preference at all. This type refuses a list that names an exercise twice, which is what stops
 *    *"most preferred"* from having two answers;
 *  * **an unknown id is refused.** A preference naming an exercise that does not exist would be a
 *    claim about a catalogue this value cannot see, so the id must be a non-blank name and the
 *    *existence* check belongs to the layer that holds the catalogue (§5's own split, applied here);
 *  * **absent is empty, not ranked.** [NONE] states nothing, and there is no value that states an
 *    *implicit* order — "no preference" and "these three in this order" are different facts and this
 *    type never lets one be read as the other.
 *
 * ### What it is not allowed to do
 *
 * It does not decide anything about generation. `GenerationRequest.isUsable` and
 * `usableCandidatesFor` already remove every exercise the available equipment cannot support, and
 * that boundary is applied **above** §9's ranking: an exercise outside the user's candidates is not
 * selectable whatever this value says (§9's *hard execution constraints must never be silently
 * violated*). A preference can therefore only ever reorder exercises that were already eligible —
 * it can never widen the set.
 *
 * @property exerciseIds the preferred exercises, most preferred first; never empty per entry, never
 *   a duplicate.
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

    /** How many exercises the user named. */
    val size: Int
        get() = exerciseIds.size

    /**
     * This preference with [exerciseId] added as the **least** preferred entry, or this preference
     * unchanged when it is already named.
     *
     * Added at the end rather than the front on purpose: a user adds an exercise they would *like*,
     * not one they would rank above what they already chose, and nothing here decides a position
     * the user did not state. The result goes through [ExercisePreference] again, so a duplicate
     * cannot be introduced even by a caller that ignores this method's return value being the same.
     */
    fun preferring(exerciseId: String): ExercisePreference =
        if (exerciseId in exerciseIds) this else ExercisePreference(exerciseIds + exerciseId)

    /**
     * This preference without [exerciseId], or unchanged when it names nothing.
     *
     * Removes that one entry and leaves every other position exactly where it was: removing a
     * preference is not a re-ranking of the ones that remain.
     */
    fun without(exerciseId: String): ExercisePreference =
        ExercisePreference(exerciseIds.filterNot { it == exerciseId })

    /**
     * This preference with [exerciseId] moved to [toPosition] (1-based, `1..size`).
     *
     * The one operation that genuinely *is* a re-ranking, and the only one a user performs
     * deliberately by choosing "move up" or "move down". The position is checked against this
     * preference's own size rather than clamped, because a caller that computed a stale index is a
     * bug and the honest answer names the value that was out of range.
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
     * Whether [exerciseId] is named at all — the question the authoring surface asks before it offers
     * to add one, and never a question about *where*: an exercise that is preferred is preferred, and
     * its position is stated by the list itself.
     */
    operator fun contains(exerciseId: String): Boolean = exerciseId in exerciseIds

    companion object {

        /**
         * No preference at all: the user has named nothing.
         *
         * This is the absence, and it is stated rather than implied — a Program with no preference is
         * not a Program whose preference happens to be every exercise in some order, and nothing in
         * this type can be read as such a ranking.
         */
        val NONE: ExercisePreference = ExercisePreference()

        /**
         * The preference of [exerciseIds], in the order given, refusing a duplicate or a blank name.
         *
         * The only way a preference is built, so the invariants above cannot be bypassed by a
         * constructor call somewhere else.
         */
        fun of(vararg exerciseIds: String): ExercisePreference = ExercisePreference(exerciseIds.toList())

        /** The preference of [exerciseIds], in the order given, refusing a duplicate or a blank name. */
        fun of(exerciseIds: List<String>): ExercisePreference = ExercisePreference(exerciseIds)
    }
}