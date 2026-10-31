package com.monkfitness.app.domain.prescription

/**
 * The prescription vocabulary of a plan element.
 *
 * A prescription belongs to the Program — to one `ProgramExercise` of one revision — and never to
 * the Exercise Library: editing what a program prescribes must never mutate exercise metadata.
 *
 * Only REP_BASED and TIME_BASED have subtypes. The other three dimensions are named with
 * deliberately no subtype, so adding one later is additive to this sealed hierarchy; their
 * algorithms, coefficients and units are not decided here and must not be guessed here.
 *
 * Every prescription is per set: a set's target is a value of its own, which is why the target is a
 * list and the set count is derived from it rather than stored beside it.
 */
sealed interface Prescription {

    /** The dimension this element progresses in. */
    val dimension: PrescriptionDimension

    /** The prescribed target of every set, in this dimension's unit, in set order. */
    val perSetTargets: List<Int>

    /** How many sets this prescription composes. The list is the source of truth, never a copy. */
    val setCount: Int

    /** The 1-based target of the set numbered [setNumber]; throws when outside `1..setCount`. */
    fun targetForSet(setNumber: Int): Int {
        require(setNumber in 1..setCount) {
            "set $setNumber is outside this prescription's 1..$setCount"
        }
        return perSetTargets[setNumber - 1]
    }

    /**
     * Volume within one dimension and one prescription — not a load score. Never comparable across
     * dimensions or exercises, and totals are never summed across dimensions.
     */
    val totalTarget: Int
        get() = perSetTargets.sum()
}

/**
 * How a plan element is prescribed, and its primary progression dimension. A dimension is named
 * whether or not a `Prescription` subtype implements it yet.
 */
enum class PrescriptionDimension {

    REP_BASED,

    TIME_BASED,

    /** Sets are the prescribed quantity. */
    SET_BASED,

    /** Difficulty/variant is the prescribed quantity. */
    DIFFICULTY_BASED,

    /** Rest is the prescribed quantity. */
    REST_BASED
}
