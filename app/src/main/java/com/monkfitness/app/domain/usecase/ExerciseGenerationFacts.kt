package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus

/**
 * The production facts about an exercise that the catalogue does not hold and a generation request
 * cannot be built without — currently only which focuses it trains.
 */
object ExerciseGenerationFacts {

    /**
     * Where an exercise's focus membership is stated. **One method, and it may say "not stated".**
     *
     * `null` and an empty set are different answers and both are refusals, not defaults: `null` is
     * *"this source makes no claim"* (the boundary gap), an empty set is a claim `GenerationCandidate`
     * itself refuses (it requires at least one focus). Neither is ever read as "trains everything".
     */
    fun interface GenerationFocusSource {

        /** The focuses [exerciseId] trains, or `null` when this source states none. */
        fun focusesOf(exerciseId: String): Set<Focus>?
    }

    /**
     * The dimension [isTimerBased] says this exercise is written in.
     *
     * Exhaustive with no default arm — a value outside the fact is not expressible — and it is the same
     * rule the plan editor applies when a user adds an exercise by hand, so a generated element and a
     * hand-authored one can never disagree about an exercise's dimension.
     */
    fun DimensionOf(isTimerBased: Boolean): PrescriptionDimension = when (isTimerBased) {
        true -> PrescriptionDimension.TIME_BASED
        false -> PrescriptionDimension.REP_BASED
    }
}