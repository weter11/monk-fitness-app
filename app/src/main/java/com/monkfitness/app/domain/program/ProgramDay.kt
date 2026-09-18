package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId

/**
 * One day of a revision's plan.
 *
 * [position] is the day's place in the sequence — an ordering, not an identity. The architecture is
 * explicit that a date, a cycle number and a day number are never ownership identifiers, so which
 * calendar day this plan day eventually falls on is the Scheduler's decision and is not stored here
 * (§1).
 *
 * [exercises] is the day's ordered plan. The order is the user's (a manual program may arrange it
 * freely, §2), and every element is its own occurrence, so repeating an exercise is legal as long as
 * each occurrence has its own identity (§9). A rest day is the one type that prescribes nothing: it
 * may exist as a slot without a session (§20).
 *
 * @property programDayId identity of this plan day.
 * @property position 1-based place of this day in the revision.
 * @property type what the day is for.
 * @property name an optional user-facing label; `null` means the day is presented by its position
 *   and type.
 * @property exercises the ordered plan elements of the day.
 */
data class ProgramDay(
    val programDayId: ProgramDayId,
    val position: Int,
    val type: ProgramDayType,
    val name: String? = null,
    val exercises: List<ProgramExercise> = emptyList()
) {

    init {
        require(position >= 1) { "a plan day's position is 1-based, was $position" }
        require(name == null || name.isNotBlank()) {
            "a named plan day has a non-blank name; use null for an unnamed day"
        }
        require(exercises.map { it.programExerciseId }.toSet().size == exercises.size) {
            "one day may use the same exercise repeatedly, but each occurrence keeps its own " +
                "programExerciseId (§9)"
        }
        require(type != ProgramDayType.REST || exercises.isEmpty()) {
            "a REST day prescribes no exercises; it may be a slot without a session (§20)"
        }
    }

    /** The ids of the exercises this day plans, in plan order. */
    val plannedExerciseIds: List<String>
        get() = exercises.map { it.exerciseId }
}
