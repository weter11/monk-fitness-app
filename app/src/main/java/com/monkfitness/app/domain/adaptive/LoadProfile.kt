package com.monkfitness.app.domain.adaptive

/**
 * The load of one scope, in the four dimensions kept apart. There is deliberately **no total**, no weight
 * and no conversion between the parts: equating unlike work would let one number drive progression.
 * [exposure] is context, not workload — a focus that got more of the plan's attention did not thereby
 * become heavier.
 */
data class LoadProfile(
    val scope: AdaptiveScope,
    val volume: VolumeLoad,
    val intensity: IntensityLoad,
    val density: DensityLoad,
    val exposure: ExposureLoad
)

/**
 * Work in the units a plan is written in. Repetitions and seconds are different measurements, reported
 * side by side and never added together; two volumes are comparable only in the same unit.
 */
data class VolumeLoad(
    val sets: Int = 0,
    val repetitions: Int = 0,
    val durationSeconds: Int = 0
) {

    init {
        require(sets >= 0 && repetitions >= 0 && durationSeconds >= 0) {
            "load amounts must be >= 0, were sets=$sets repetitions=$repetitions " +
                "durationSeconds=$durationSeconds"
        }
    }

    val isZero: Boolean
        get() = sets == 0 && repetitions == 0 && durationSeconds == 0
}

/**
 * [level] is an ordinal position in the family's own hierarchy and nothing more: the bounds and steps
 * belong to the hierarchy, so a level is comparable to another level of the *same* family only.
 */
data class IntensityEntry(
    val familyId: String,
    val level: Int
) {

    init {
        require(familyId.isNotBlank()) { "an intensity entry must name its family" }
    }
}

/** Held in family order, so two profiles covering the same families are equal values whatever order the caller discovered them in. */
data class IntensityLoad(
    val levels: List<IntensityEntry> = emptyList()
) {

    init {
        require(levels.map { it.familyId }.toSet().size == levels.size) {
            "one level per family: ${levels.map { it.familyId }}"
        }
        require(levels == levels.sortedBy { it.familyId }) {
            "intensity entries are held in family order, got ${levels.map { it.familyId }}"
        }
    }

    fun levelOf(familyId: String): Int? =
        levels.firstOrNull { it.familyId == familyId }?.level
}

/**
 * Not a ratio and not a rate: the amounts are kept as measured, because a ratio would be a derived number
 * nobody agreed on. The same volume with half the rest is a different stimulus.
 */
data class DensityLoad(
    val workingSeconds: Int = 0,
    val restSeconds: Int = 0
) {

    init {
        require(workingSeconds >= 0 && restSeconds >= 0) {
            "density amounts must be >= 0, were workingSeconds=$workingSeconds " +
                "restSeconds=$restSeconds"
        }
    }
}

/**
 * The exposure/context channel, explicitly **not** physical workload: a focus receiving more of the plan's
 * attention says something about the plan and the calendar. It is where "the opportunity existed but
 * nothing happened" is recorded honestly rather than as zero work.
 */
data class ExposureLoad(
    val opportunities: Int = 0,
    val completedOpportunities: Int = 0
) {

    init {
        require(opportunities >= 0) { "opportunities must be >= 0, was $opportunities" }
        require(completedOpportunities in 0..opportunities) {
            "completed opportunities must be within 0..$opportunities, was " +
                "$completedOpportunities"
        }
    }
}
