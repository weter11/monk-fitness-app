package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.usecase.ExerciseGenerationFacts.GenerationFocusSource

/**
 * The app's production exercise → [Focus] classification: membership is stated per exercise id and read,
 * never computed.
 *
 * The catalogue's own vocabularies are not this one. `ExerciseCategory`, `ExerciseSubCategory` and
 * `exerciseToFamiliesMap` each overlap `Focus` by a few names, which is what makes a `when (category)`
 * classifier read as a reasonable simplification in review while being a fabricated training fact at
 * runtime; `PUSH`, `PULL` and `CONDITIONING` are names the catalogue never uses at all, so no lookup
 * recovers them in either direction.
 *
 * So there is no `when`, no `if` and no lookup by category, sub-category, family or training style. An
 * exercise this table does not name is **unclassified** (reported by
 * [ProductionGenerationBoundary.ProductionGenerationCatalogue.unclassifiedExerciseIds]) and no focus is
 * invented for it. No entry is empty and no entry is the whole vocabulary: "trains everything" and
 * "trains nothing stated" are both fabrications, and `GenerationCandidate` refuses an empty set anyway.
 *
 * `MOBILITY` and `POSTURE` are separate on purpose — the catalogue's `MOBILITY` category covers
 * stretching, mobility *and* posture work — so they are drawn per exercise: `cat_cow` is mobility,
 * `chin_tucks` posture, `cobra_stretch` both.
 *
 * It holds no collaborator and reads no state, so the same id yields the same focuses on any device, in
 * any order. It is not settings and not user-editable; a Goals & Focus editor would be a different owner
 * of the same fact. `ProductionFocusClassificationTest` measures the table against the real catalogue.
 */
object ProductionFocusClassification : GenerationFocusSource {

    /**
     * By the catalogue's own opaque library id — the ids are read, never mapped or re-derived.
     *
     * Entries are held in the vocabulary's canonical order so iteration order is stable and two runs
     * cannot differ.
     */
    private val CLASSIFICATION: Map<String, List<Focus>> = mapOf(
        "pushups" to listOf(Focus.PUSH),
        "pushups_wide" to listOf(Focus.PUSH),
        "pushups_military" to listOf(Focus.PUSH),
        "pushups_knee" to listOf(Focus.PUSH),
        "decline_pushups" to listOf(Focus.PUSH),
        "diamond_pushups" to listOf(Focus.PUSH),
        "dips" to listOf(Focus.PUSH),
        "pike_pushups" to listOf(Focus.PUSH),

        "pullups" to listOf(Focus.PULL),
        "pullups_chin" to listOf(Focus.PULL),
        "pullups_neutral" to listOf(Focus.PULL),
        "pullups_wide" to listOf(Focus.PULL),
        "hang" to listOf(Focus.PULL, Focus.POSTURE),
        "rows" to listOf(Focus.PULL),
        "face_pull" to listOf(Focus.PULL, Focus.POSTURE),
        "band_pull_aparts" to listOf(Focus.PULL, Focus.POSTURE),
        "scapular_pullups" to listOf(Focus.PULL, Focus.POSTURE),
        "lat_stretch" to listOf(Focus.PULL, Focus.MOBILITY),

        "squats" to listOf(Focus.LEGS),
        "squats_sumo" to listOf(Focus.LEGS),
        "squats_jump" to listOf(Focus.LEGS, Focus.CONDITIONING),
        "cossack_squat" to listOf(Focus.LEGS, Focus.MOBILITY),
        "deep_squat" to listOf(Focus.LEGS, Focus.MOBILITY),
        "lunges" to listOf(Focus.LEGS),
        "lunges_reverse" to listOf(Focus.LEGS),
        "lunges_side" to listOf(Focus.LEGS),
        "step_ups" to listOf(Focus.LEGS, Focus.CONDITIONING),
        "glute_bridge" to listOf(Focus.LEGS, Focus.CORE),
        "wall_sit" to listOf(Focus.LEGS),
        "leg_swings" to listOf(Focus.LEGS, Focus.MOBILITY),

        "plank" to listOf(Focus.CORE),
        "side_plank" to listOf(Focus.CORE),
        "dead_bug" to listOf(Focus.CORE),
        "leg_raises" to listOf(Focus.CORE),
        "pelvic_tilt" to listOf(Focus.CORE, Focus.POSTURE),
        "couch_stretch" to listOf(Focus.CORE, Focus.MOBILITY),
        "bird_dog" to listOf(Focus.CORE, Focus.MOBILITY),
        "bird_dog_reps" to listOf(Focus.CORE, Focus.MOBILITY),
        "superman" to listOf(Focus.CORE, Focus.POSTURE),

        "cat_cow" to listOf(Focus.MOBILITY, Focus.POSTURE),
        "cobra_stretch" to listOf(Focus.MOBILITY, Focus.POSTURE),
        "world_greatest_stretch" to listOf(Focus.MOBILITY),
        "thoracic_rotations" to listOf(Focus.MOBILITY),
        "thoracic_extension" to listOf(Focus.MOBILITY, Focus.POSTURE),
        "shoulder_cars" to listOf(Focus.MOBILITY),
        "hip_cars" to listOf(Focus.MOBILITY),
        "ninety_ninety_hips" to listOf(Focus.MOBILITY),
        "piriformis_stretch" to listOf(Focus.MOBILITY),
        "ankle_mobility" to listOf(Focus.MOBILITY),
        "calf_stretch" to listOf(Focus.MOBILITY),
        "neck_circles" to listOf(Focus.MOBILITY),
        "hip_flexor_stretch" to listOf(Focus.LEGS, Focus.MOBILITY),
        "hamstring_stretch" to listOf(Focus.MOBILITY),
        "arm_circles" to listOf(Focus.MOBILITY),
        "hip_circles" to listOf(Focus.MOBILITY),
        "child_pose" to listOf(Focus.MOBILITY),

        "y_t_raises" to listOf(Focus.MOBILITY, Focus.POSTURE),
        "scapular_retraction_hold" to listOf(Focus.POSTURE),
        "reverse_snow_angels" to listOf(Focus.MOBILITY, Focus.POSTURE),
        "wall_slides" to listOf(Focus.MOBILITY, Focus.POSTURE),
        "chin_tucks" to listOf(Focus.POSTURE),
        "horse_stance" to listOf(Focus.LEGS, Focus.POSTURE),

        "burpees" to listOf(Focus.PUSH, Focus.LEGS, Focus.CONDITIONING),
        "mountain_climbers" to listOf(Focus.CORE, Focus.CONDITIONING),
        "jumping_jacks" to listOf(Focus.MOBILITY, Focus.CONDITIONING),
        "kettlebell_swing" to listOf(Focus.LEGS, Focus.CONDITIONING)
    )

    init {
        // Guards on the data, so a careless edit fails where the offending id is named. The claim that
        // every catalogue id is covered is not checkable here — this object must not read the catalogue
        // — so `ProductionFocusClassificationTest` measures it against the real one.
        CLASSIFICATION.forEach { (exerciseId, focuses) ->
            require(focuses.isNotEmpty()) {
                "an exercise whose focuses are not stated is unclassified, not an exercise that " +
                    "trains nothing: '$exerciseId'"
            }
            require(focuses != Focus.entries.toList()) {
                "an exercise trains the focuses it trains, not the whole vocabulary: '$exerciseId'"
            }
            require(focuses.distinct().size == focuses.size) {
                "a focus is stated once per exercise: '$exerciseId' states $focuses"
            }
            require(focuses == FocusPlan.canonical(focuses)) {
                "an entry is held in the vocabulary's own order so two runs cannot differ: " +
                    "'$exerciseId' states $focuses instead of ${FocusPlan.canonical(focuses)}"
            }
        }
    }

    /**
     * `null` is a real answer, not an absence of one: it is what the boundary reports as unclassified,
     * and it keeps "trains everything" and "trains an empty set" both unreachable.
     */
    override fun focusesOf(exerciseId: String): Set<Focus>? =
        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }

    val classifiedExerciseIds: Set<String>
        get() = CLASSIFICATION.keys

    /** Whether this table states [exerciseId]'s focuses, as opposed to leaving it unclassified. */
    fun classifies(exerciseId: String): Boolean = CLASSIFICATION.containsKey(exerciseId)
}
