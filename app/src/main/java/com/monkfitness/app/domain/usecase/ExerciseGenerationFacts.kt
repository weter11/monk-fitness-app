package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus

/**
 * The production facts about an exercise that the **catalogue does not hold** and a generation request
 * cannot be built without — currently, exactly one of them: which focuses an exercise trains.
 *
 * Kept as its own small surface rather than as arguments on the boundary, because it is the seam a
 * later stage fills: the vocabulary of a focus classification belongs to whoever owns the training
 * taxonomy (a curated production table, a user-facing Goals & Focus editor, a persisted
 * exercise→focus map), and this stage deliberately does not invent one. Everything the catalogue
 * *does* hold — identity, family, training domain, body region, required equipment, prescription
 * dimension — is read from the exercise itself and needs no port at all.
 */
object ExerciseGenerationFacts {

    /**
     * Where an exercise's focus membership is stated. **One method, and it may say "not stated".**
     *
     * `null` and an empty set are deliberately different answers, and both are refusals rather than
     * defaults: `null` is *"this source makes no claim about this exercise"* — the boundary gap, and
     * what an app with no classification table returns — while an empty set is a claim the generated
     * domain itself refuses (`GenerationCandidate` requires at least one focus). Neither is ever read
     * as "trains everything", and a caller cannot get a candidate out of either without stating a
     * focus.
     *
     * The parameter is the catalogue's own opaque library id (§10: opaque in both directions), so an
     * implementation may join against whatever table it owns and never has to map the id.
     */
    fun interface GenerationFocusSource {

        /** The focuses [exerciseId] trains, or `null` when this source states none. */
        fun focusesOf(exerciseId: String): Set<Focus>?
    }

    /**
     * The prescription dimension [isTimerBased] says this exercise is written in (§10's per-element
     * progression dimension).
     *
     * Exhaustive over the catalogue's own fact, with no default arm: a value outside it is not
     * expressible, and the two dimensions it maps to — `REP_BASED` and `TIME_BASED` — are the two §10
     * gives a prescription subtype. It is the same rule the plan editor applies when a user adds an
     * exercise by hand, so a generated element and a hand-authored one can never disagree about what
     * an exercise is prescribed in.
     */
    fun DimensionOf(isTimerBased: Boolean): PrescriptionDimension = when (isTimerBased) {
        true -> PrescriptionDimension.TIME_BASED
        false -> PrescriptionDimension.REP_BASED
    }
}