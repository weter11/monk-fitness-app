package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension

/**
 * One exercise occurrence as it ran inside one session.
 *
 * The link back to the plan is [programExerciseId]: this is the *same element* the session's snapshot
 * presented, which is what makes "what was planned" and "what was performed" comparable without
 * guessing. [prescription] is the one that was presented — copied into the snapshot at session start
 * — so an edit made to the program while the session runs cannot change what the session is measured
 * against.
 *
 * [results] accumulates confirmed sets in the order they happened, which is why their indices must be
 * `1..n` with no gaps: confirming a set appends it, and a set that was never confirmed is absent
 * rather than inserted as a placeholder. [skipped] is the user's explicit "not this one", and it is
 * exactly equivalent to having no results — a skipped exercise observed nothing (§12).
 *
 * @property sessionExerciseId identity of this occurrence within the session.
 * @property programExerciseId the plan element it presents.
 * @property exerciseId the library key of the exercise, as presented.
 * @property prescription what was presented for it, per set.
 * @property results the confirmed sets, in confirmation order.
 * @property skipped whether the user explicitly skipped it.
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

    /** How many sets were confirmed for this occurrence. */
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
