package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId

/**
 * One day of a revision's plan.
 *
 * [position] is the day's place in the sequence — an ordering, not an identity. A date, a cycle number and
 * a day number are never ownership identifiers, so which calendar day this plan day falls on is the
 * Scheduler's decision and is not stored here.
 *
 * [exercises] is the day's ordered plan. The order is the user's (a manual program may arrange it freely),
 * and every element is its own occurrence, so repeating an exercise is legal as long as each occurrence has
 * its own identity. A rest day is the one type that prescribes nothing: it may exist as a slot without a
 * session.
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

    val plannedExerciseIds: List<String>
        get() = exercises.map { it.exerciseId }
}
