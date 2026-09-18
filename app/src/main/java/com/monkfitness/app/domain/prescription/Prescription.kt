package com.monkfitness.app.domain.prescription

/**
 * The prescription vocabulary of a plan element (§10).
 *
 * A prescription belongs to the Program — to one `ProgramExercise` of one revision — and never to
 * the Exercise Library: editing what a program prescribes must never mutate exercise metadata, and
 * a manual program's prescription is unrestricted by any generator range (§10).
 *
 * Only two dimensions are modelled today, because only two are specified in enough detail to be
 * representable without inventing semantics:
 *
 *  * [PrescriptionDimension.REP_BASED] — [RepPrescription], a repetition target per set;
 *  * [PrescriptionDimension.TIME_BASED] — [TimePrescription], a duration target per set.
 *
 * The remaining three dimensions — [PrescriptionDimension.SET_BASED],
 * [PrescriptionDimension.DIFFICULTY_BASED] and [PrescriptionDimension.REST_BASED] — are named here
 * and deliberately have **no subtype**. Naming them is what keeps the model open: a plan element can
 * already be described as progressing in one of them, and adding the subtype later is an additive
 * change to this sealed hierarchy, not a re-shaping of it. Their algorithms, their coefficients and
 * their units are not decided here and must not be guessed here.
 *
 * Every prescription is per set: a set's target is not a repeated default but a value of its own
 * (§10 shows `12 / 10 / 8 / 6` and `30s / 30s / 45s`), which is why the target is a list and the set
 * count is derived from it rather than stored beside it.
 */
sealed interface Prescription {

    /** The dimension this element progresses in (§10). */
    val dimension: PrescriptionDimension

    /** The prescribed target of every set, in this dimension's unit, in set order. */
    val perSetTargets: List<Int>

    /** How many sets this prescription composes. The list is the source of truth, never a copy. */
    val setCount: Int

    /**
     * The target of the set numbered [setNumber] (1-based, as sets are presented to the user).
     *
     * @throws IllegalArgumentException when [setNumber] is outside `1..setCount`.
     */
    fun targetForSet(setNumber: Int): Int {
        require(setNumber in 1..setCount) {
            "set $setNumber is outside this prescription's 1..$setCount"
        }
        return perSetTargets[setNumber - 1]
    }

    /**
     * The sum of the per-set targets.
     *
     * This is a volume within **one** dimension and one prescription — not a load score. It is never
     * comparable across dimensions or exercises, and nothing in the domain adds the repetition total
     * of one prescription to the second total of another (§17).
     */
    val totalTarget: Int
        get() = perSetTargets.sum()
}

/**
 * How a plan element is prescribed, and what its primary progression dimension is (§10).
 *
 * A dimension is named whether or not a `Prescription` subtype implements it yet: the vocabulary is
 * the contract the later stages fill in.
 */
enum class PrescriptionDimension {

    /** Repetitions per set — implemented by [RepPrescription]. */
    REP_BASED,

    /** Duration per set — implemented by [TimePrescription]. */
    TIME_BASED,

    /** Sets are the prescribed quantity. Named, no subtype yet. */
    SET_BASED,

    /** Difficulty/variant is the prescribed quantity. Named, no subtype yet. */
    DIFFICULTY_BASED,

    /** Rest is the prescribed quantity. Named, no subtype yet. */
    REST_BASED
}
