package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension

/**
 * One exercise occurrence as it ran inside one session.
 *
 * [programExerciseId] links back to the same element the snapshot presented, which is what makes "what was
 * planned" and "what was performed" comparable without guessing. [prescription] is the one that was
 * presented, copied into the snapshot at session start, so an edit made while the session runs cannot
 * change what it is measured against.
 *
 * [results] accumulates confirmed sets in the order they happened, which is why their indices are `1..n`
 * with no gaps: a set never confirmed is absent rather than a placeholder. [skipped] is the user's
 * explicit "not this one", exactly equivalent to having no results — a skipped exercise observed nothing.
 */
data class SessionExercise(
    val sessionExerciseId: SessionExerciseId,
    val programExerciseId: ProgramExerciseId,
    val exerciseId: String,
    val prescription: Prescription,
    val results: List<SetResult> = emptyList(),
    val skipped: Boolean = false
) {

    init {
        require(exerciseId.isNotBlank()) { "a session exercise must name the exercise it ran" }
        require(results.map { it.setLogId }.toSet().size == results.size) {
            "one confirmed set is one set result"
        }
        require(results.map { it.setIndex } == (1..results.size).toList()) {
            "confirmed sets accumulate as 1..n with no gaps, got ${results.map { it.setIndex }}"
        }
        require(!skipped || results.isEmpty()) {
            "a skipped exercise observed no sets; results and skipped cannot both be set (§12)"
        }
        require(isLoggedInThePrescribedUnit()) {
            "a set is logged in the unit its prescription was written in: " +
                "dimension=${prescription.dimension} results=$results"
        }
    }

    val completedSetCount: Int
        get() = results.size

    private fun isLoggedInThePrescribedUnit(): Boolean = when (prescription.dimension) {
        PrescriptionDimension.REP_BASED -> results.all { it.completedReps > 0 }
        PrescriptionDimension.TIME_BASED -> results.all { it.durationSeconds > 0 }
        // The reserved dimensions carry no unit contract yet, so nothing is claimed about them.
        PrescriptionDimension.SET_BASED,
        PrescriptionDimension.DIFFICULTY_BASED,
        PrescriptionDimension.REST_BASED -> true
    }
}
