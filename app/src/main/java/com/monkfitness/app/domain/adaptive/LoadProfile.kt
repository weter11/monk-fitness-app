package com.monkfitness.app.domain.adaptive

/**
 * The load of one scope, in the four dimensions the architecture keeps apart (§17).
 *
 * There is **no total**. This type deliberately exposes no scalar, no weight and no conversion
 * between its parts: "1 push-up = X load units" is exactly the invention the architecture forbids,
 * because it would silently equate unlike work and then let a single number drive progression. What
 * the adaptive stage may reason about is what is actually recorded here:
 *
 *  * [volume] — how much work, in the units it was prescribed in;
 *  * [intensity] — at what progression level, per family;
 *  * [density] — the work-to-time structure;
 *  * [exposure] — how much opportunity the scope had, which is context rather than physical workload:
 *    a focus that got more of the plan's attention did not thereby become heavier.
 *
 * The four are meaningful together and only *within* a comparable context — volume primarily within
 * the same exercise or family, intensity through the progression relations of that family — so a
 * profile is a description, not a ranking key, and comparing two profiles is a decision the load
 * guard makes at a named [scope] with its own rules.
 *
 * @property scope the granularity this profile is stated at.
 * @property volume the work performed or prescribed, per unit.
 * @property intensity the progression level of the families involved.
 * @property density the work/rest structure.
 * @property exposure the opportunity this scope had.
 */
data class LoadProfile(
    val scope: AdaptiveScope,
    val volume: VolumeLoad,
    val intensity: IntensityLoad,
    val density: DensityLoad,
    val exposure: ExposureLoad
)

/**
 * Work in the units a plan is written in.
 *
 * Repetitions and seconds are different measurements, so they are reported side by side and never
 * added together; two volumes are comparable only when they are in the same unit. This mirrors the
 * convention the app's own statistics already use (sets / reps / timer seconds).
 *
 * @property sets the number of sets, `>= 0`.
 * @property repetitions the repetitions performed or prescribed, `>= 0`.
 * @property durationSeconds the seconds performed or prescribed, `>= 0`.
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

    /** Whether nothing at all was prescribed or performed in any unit. */
    val isZero: Boolean
        get() = sets == 0 && repetitions == 0 && durationSeconds == 0
}

/**
 * The progression level of one family, as the family's progression hierarchy defines it.
 *
 * [level] is an ordinal position in that hierarchy and nothing more. This model fixes no bounds, no
 * step size and no coefficient: the level range, the step between levels and the relations that make
 * one variant harder than another belong to the progression hierarchy and arrive with it (§15 — a
 * variant change requires an explicit progression relation). A level is therefore comparable to
 * another level of the *same* family and to nothing else.
 *
 * @property familyId the family this level belongs to.
 * @property level the family's position in its progression hierarchy.
 */
data class IntensityEntry(
    val familyId: String,
    val level: Int
) {

    init {
        require(familyId.isNotBlank()) { "an intensity entry must name its family" }
    }
}

/**
 * The progression levels of the families a load profile covers.
 *
 * The entries are held in family order so that two profiles covering the same families in the same
 * positions are equal values, whatever order the caller discovered them in.
 *
 * @property levels the levels, one per family, in family-id order.
 */
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

    /** The level recorded for [familyId], or `null` when this profile does not cover that family. */
    fun levelOf(familyId: String): Int? =
        levels.firstOrNull { it.familyId == familyId }?.level
}

/**
 * The work-to-time structure of a scope.
 *
 * Density is not a ratio and not a rate: the two amounts are kept as they were measured, because a
 * ratio would be a derived number the architecture has not agreed on. What it says is how the work was
 * arranged in time — the same volume performed with half the rest is a different stimulus even though
 * the volume is identical.
 *
 * @property workingSeconds seconds spent working.
 * @property restSeconds seconds spent resting.
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
 * How much opportunity a scope had, and how much of it produced work.
 *
 * This is the exposure/context channel, and it is explicitly **not** physical workload: a focus
 * receiving more of the plan's attention, or a session having more slots available, says something
 * about the plan and the calendar, not about how heavy the training was (§17). Exposure is where
 * "the opportunity existed but nothing happened" is recorded honestly rather than as zero work.
 *
 * @property opportunities how many opportunities the scope had, `>= 0`.
 * @property completedOpportunities how many of them produced work, within `0..opportunities`.
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
