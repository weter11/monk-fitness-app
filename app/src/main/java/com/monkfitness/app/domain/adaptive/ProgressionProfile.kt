package com.monkfitness.app.domain.adaptive

/**
 * What a family's progression level orders: the channel that actually changes when the family moves
 * one level on its ladder.
 *
 * The axis is the family's own, and there is no universal meaning for a level — a level on a
 * [REP_VARIATION] family selects another variation of the movement, a level on a [TIMER] family
 * selects a longer or shorter hold of the same one, and a level on a [CORRECTIVE] family selects more
 * or less corrective volume on a movement with no harder variation to reach. [MOBILITY] is the same
 * shape for a mobility family, and [MIXED] is for a ladder that crosses channels inside itself.
 */
enum class ProgressionAxis {
    REP_VARIATION,
    TIMER,
    MOBILITY,
    CORRECTIVE,
    MIXED
}

/**
 * One rung of a family ladder: the exercise a level selects, plus the low-level adjustment that goes
 * with it.
 *
 * [exerciseId] is an exercise the library already defines; a profile references ids and never
 * introduces an exercise. [adjustment] is a rep/duration step in the unit the data layer's existing
 * low-level difficulty-adjustment mechanism consumes, bounded by that mechanism's accepted range
 * ([MIN_ADJUSTMENT]..[MAX_ADJUSTMENT]); it is `0` for a rung that needs none, and a rung may carry
 * one instead of a new variation (a bridge from an easier variation to a harder one at reduced
 * volume, for instance).
 */
data class ProgressionStep(
    val exerciseId: String,
    val adjustment: Int = 0
) {
    init {
        require(exerciseId.isNotBlank()) { "a progression step must name an exercise, was \"$exerciseId\"" }
        require(adjustment in MIN_ADJUSTMENT..MAX_ADJUSTMENT) {
            "a progression step adjustment must be within $MIN_ADJUSTMENT..$MAX_ADJUSTMENT, was $adjustment"
        }
    }

    companion object {
        /** The lowest adjustment step the low-level difficulty-adjustment mechanism accepts. */
        const val MIN_ADJUSTMENT: Int = -2

        /** The highest adjustment step the low-level difficulty-adjustment mechanism accepts. */
        const val MAX_ADJUSTMENT: Int = 2
    }
}

/**
 * One family's progression mapping: which of the family's existing exercises every level `-2..+2`
 * sits on, and what a step from a level means.
 *
 * The profile describes the mapping and nothing else — it references library exercise ids, restates
 * no exercise metadata, and is not a copy of the exercise model. [ladder] carries exactly one step per
 * level, ascending from `LEVELS.first` to `LEVELS.last`, so every level maps to a declared step and
 * there is no level beyond the ends to reach.
 *
 * [fallbackStep] is the profile's explicitly permitted alternative axis, and it is opt-in for a
 * reason: when the variation a level orders is disabled by the user's configuration, a family that
 * declares one here may keep the variation it is already on and take one adjustment step of that
 * size instead, while a family that declares none holds. The size is a low-level adjustment step, not
 * a level: the fallback never moves the family's level.
 */
data class ProgressionProfile(
    val familyId: String,
    val axis: ProgressionAxis,

    /** The step for every level of [LEVELS], ascending: index `0` is the lowest level. */
    val ladder: List<ProgressionStep>,

    /** The size of the explicitly permitted volume/duration fallback, or `null` when there is none. */
    val fallbackStep: Int? = null
) {
    init {
        require(familyId.isNotBlank()) { "a progression profile must name its family" }
        require(ladder.size == LEVELS.count()) {
            "a ladder must map every level of $LEVELS, had ${ladder.size} steps"
        }
        require(ladder.zipWithNext().all { (below, above) -> below != above }) {
            "adjacent ladder steps must differ, had $ladder"
        }
        require(fallbackStep == null || fallbackStep in 1..ProgressionStep.MAX_ADJUSTMENT) {
            "a fallback step must be within 1..${ProgressionStep.MAX_ADJUSTMENT} or omitted, was $fallbackStep"
        }
    }

    /** The step this family sits on at [level]; throws for a level outside [LEVELS]. */
    fun stepAt(level: Int): ProgressionStep {
        require(level in LEVELS) { "progression level must be within $LEVELS, was $level" }
        return ladder[level - LEVELS.first]
    }

    companion object {
        /** The documented progression-level range, exactly as the adaptation state defines it. */
        val LEVELS: IntRange = FamilyAdaptationState.MIN_LEVEL..FamilyAdaptationState.MAX_LEVEL
    }
}
