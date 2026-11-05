package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.SessionType

/**
 * The production facts about an exercise that the catalogue does not hold and a generation request
 * cannot be built without — which focuses it trains, and which session types it belongs to.
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
     * Where an exercise's session-type membership is stated.
     *
     * `null` is the same kind of answer [GenerationFocusSource] gives: *"this source makes no claim"*, which
     * leaves the exercise outside every selection (a type is a filter, so an unstated exercise is never
     * silently admitted by belonging to everything).
     */
    fun interface SessionTypeSource {

        /** The session types [exerciseId] belongs to, or `null` when this source states none. */
        fun sessionTypesOf(exerciseId: String): Set<SessionType>?
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