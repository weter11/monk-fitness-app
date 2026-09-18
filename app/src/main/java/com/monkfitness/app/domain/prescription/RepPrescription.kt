package com.monkfitness.app.domain.prescription

/**
 * A repetition prescription: how many repetitions each set of one plan element prescribes.
 *
 * The target is **per set** — `12 / 10 / 8 / 6` is one prescription with four sets, not a uniform
 * `10` with an override — because that is what a real plan says and what the database must be able
 * to store without flattening it (§10).
 *
 * A manual program's prescription is free: the only rule held here is that a prescribed set asks
 * for work. Generator ranges and progression limits belong to the layers that generate and adapt a
 * plan, never to the value that records what the plan says.
 */
data class RepPrescription(
    /** Repetitions prescribed for each set, in set order. Never empty, always positive. */
    val perSetReps: List<Int>
) : Prescription {

    init {
        require(perSetReps.isNotEmpty()) {
            "a prescription composes at least one set, got none"
        }
        require(perSetReps.all { it > 0 }) {
            "a prescribed set asks for at least one repetition, got $perSetReps"
        }
    }

    override val dimension: PrescriptionDimension
        get() = PrescriptionDimension.REP_BASED

    override val perSetTargets: List<Int>
        get() = perSetReps

    override val setCount: Int
        get() = perSetReps.size

    companion object {

        /**
         * A repetition prescription of [sets] sets that all ask for [reps] repetitions — the
         * uniform case, expanded into the per-set list the model stores.
         */
        fun uniform(sets: Int, reps: Int): RepPrescription {
            require(sets >= 1) { "sets must be >= 1, was $sets" }
            require(reps >= 1) { "reps must be >= 1, was $reps" }
            return RepPrescription(List(sets) { reps })
        }
    }
}
