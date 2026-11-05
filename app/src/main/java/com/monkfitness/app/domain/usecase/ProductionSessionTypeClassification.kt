package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.SessionType
import com.monkfitness.app.domain.usecase.ExerciseGenerationFacts.SessionTypeSource

/**
 * The app's production exercise → [SessionType] classification, the second table beside
 * [ProductionFocusClassification] and read the same way: membership is stated per exercise id, never
 * computed from a category at the point of use.
 *
 * Three types are the catalogue's own categories — [SessionType.STRENGTH] is `ExerciseCategory.STRENGTH`,
 * [SessionType.MOBILITY] is `MOBILITY` plus `STRETCHING`, [SessionType.POSTURE] is `POSTURE` — while
 * [SessionType.HYPERLORDOSIS] is a **curated cross-category set**: the exercises the training-style map
 * assigns to the hyperlordosis style, which spans strength, posture and stretching. The sub-category alone
 * names two of them, so reading it would under-state the set by four.
 *
 * A type is a filter on the pool, so an exercise may belong to several (a glute bridge is both strength
 * and hyperlordosis work) and the members of a selection combine as a union. Every entry is stated once;
 * the table classifies the whole shipped catalogue, and `ProductionSessionTypeClassificationTest` measures
 * that against the real catalogue rather than trusting this file's own claim.
 */
object ProductionSessionTypeClassification : SessionTypeSource {

    /**
     * By the catalogue's own opaque library id. Held per type rather than per exercise so the table reads
     * as the four chips a user sees; the per-exercise view is derived once below.
     */
    private val MEMBERS: Map<SessionType, Set<String>> = mapOf(
        SessionType.STRENGTH to setOf(
            "pushups", "pushups_wide", "pushups_military", "pushups_knee", "decline_pushups",
            "diamond_pushups", "squats", "squats_sumo", "squats_jump", "cossack_squat", "pullups",
            "pullups_chin", "pullups_neutral", "pullups_wide", "plank", "side_plank", "lunges",
            "lunges_reverse", "lunges_side", "step_ups", "rows", "glute_bridge", "dead_bug",
            "leg_raises", "burpees", "mountain_climbers", "dips", "pike_pushups", "wall_sit",
            "kettlebell_swing"
        ),
        SessionType.MOBILITY to setOf(
            "deep_squat", "cat_cow", "bird_dog", "bird_dog_reps", "world_greatest_stretch",
            "thoracic_rotations", "thoracic_extension", "shoulder_cars", "hip_cars",
            "ninety_ninety_hips", "ankle_mobility", "neck_circles", "jumping_jacks", "arm_circles",
            "hip_circles", "leg_swings", "couch_stretch", "hip_flexor_stretch", "cobra_stretch",
            "lat_stretch", "piriformis_stretch", "calf_stretch", "child_pose", "hamstring_stretch"
        ),
        SessionType.POSTURE to setOf(
            "hang", "pelvic_tilt", "face_pull", "band_pull_aparts", "y_t_raises",
            "scapular_retraction_hold", "reverse_snow_angels", "scapular_pullups", "wall_slides",
            "chin_tucks", "horse_stance", "superman"
        ),
        SessionType.HYPERLORDOSIS to setOf(
            "glute_bridge", "dead_bug", "leg_raises", "pelvic_tilt", "couch_stretch",
            "hip_flexor_stretch"
        )
    )

    private val CLASSIFICATION: Map<String, Set<SessionType>> =
        MEMBERS.values.flatten().distinct().associateWith { exerciseId ->
            SessionType.entries.filterTo(LinkedHashSet()) { type ->
                MEMBERS.getValue(type).contains(exerciseId)
            }
        }

    init {
        MEMBERS.forEach { (type, ids) ->
            require(ids.isNotEmpty()) {
                "a session type nothing trains is not a filter: '$type' names no exercise"
            }
            require(ids.size == ids.distinct().size) {
                "an exercise is named once per session type: '$type'"
            }
        }
    }

    /** `null` states that this table makes no claim about [exerciseId] — never "belongs to all types". */
    override fun sessionTypesOf(exerciseId: String): Set<SessionType>? = CLASSIFICATION[exerciseId]

    val classifiedExerciseIds: Set<String>
        get() = CLASSIFICATION.keys

    /** How many exercises this table states for [type] — the figure the type's chip promises. */
    fun sizeOf(type: SessionType): Int = MEMBERS.getValue(type).size
}
