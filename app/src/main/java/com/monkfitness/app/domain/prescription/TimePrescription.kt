package com.monkfitness.app.domain.prescription

/**
 * A time prescription: how long each set of one plan element lasts.
 *
 * The target is per set and the unit is seconds — the unit the rest of the app stores durations in.
 * Nothing here decides whether a duration is a hold, an interval or a rest-to-work structure; that
 * lives in the exercise and the dimension it progresses in.
 */
data class TimePrescription(
    /** Seconds prescribed for each set, in set order. Never empty, always positive. */
    val perSetSeconds: List<Int>
) : Prescription {

    init {
        require(perSetSeconds.isNotEmpty()) {
            "a prescription composes at least one set, got none"
        }
        require(perSetSeconds.all { it > 0 }) {
            "a prescribed set lasts at least one second, got $perSetSeconds"
        }
    }

    override val dimension: PrescriptionDimension
        get() = PrescriptionDimension.TIME_BASED

    override val perSetTargets: List<Int>
        get() = perSetSeconds

    override val setCount: Int
        get() = perSetSeconds.size

    companion object {

        /** [sets] sets that all last [seconds], expanded into the per-set list the model stores. */
        fun uniform(sets: Int, seconds: Int): TimePrescription {
            require(sets >= 1) { "sets must be >= 1, was $sets" }
            require(seconds >= 1) { "seconds must be >= 1, was $seconds" }
            return TimePrescription(List(sets) { seconds })
        }
    }
}
