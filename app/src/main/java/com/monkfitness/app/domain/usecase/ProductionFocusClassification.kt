package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.usecase.ExerciseGenerationFacts.GenerationFocusSource

/**
 * The app's **production exercise → [Focus] classification** — §30 step 24's answer to the gap §30 step
 * 23 recorded.
 *
 * ```text
 * production exercise catalogue
 *          ↓  this table, by exercise id
 * explicit focus membership
 *          ↓  ProductionGenerationBoundary (P23)
 * GenerationCandidate<Equipment>
 * ```
 *
 * ### Why this is a table and not a rule
 *
 * P23 established that no such classification exists in production, and that none of the catalogue's
 * own vocabularies is one: `ExerciseCategory` and `ExerciseSubCategory` are two of the three names
 * that *overlap* `Focus` without being it, and `exerciseToFamiliesMap` is a third. Four of the seven
 * focuses are names those vocabularies happen to use, which is exactly what makes a
 * `when (category)` classifier read as a reasonable simplification in review while being a
 * fabricated training fact at runtime; three focuses (`PUSH`, `PULL`, `CONDITIONING`) are names the
 * catalogue never uses at all, so no lookup recovers them in either direction.
 *
 * So membership here is **stated per exercise id** and is read, never computed:
 *
 *  * there is no `when`, no `if`, no lookup by category, sub-category, family or training style;
 *  * an exercise the table does not name is **unclassified** — the boundary reports it in
 *    [ProductionGenerationBoundary.ProductionGenerationCatalogue.unclassifiedExerciseIds] and no
 *    focus is invented for it;
 *  * no entry is the whole vocabulary and no entry is empty, because "an exercise trains everything"
 *    and "an exercise trains nothing stated" are both fabrications. `GenerationCandidate` refuses an
 *    empty set at construction, and this table never offers one.
 *
 * Every one of the app's shipped exercises is classified, and `ProductionFocusClassificationTest`
 * reads the **real** `WorkoutGenerator().getExerciseLibrary()` to prove it — that no shipped exercise
 * is missing, that the table names no id the catalogue does not have, and that no entry is empty or
 * the full vocabulary. The count is measured from the catalogue, never hardcoded here or there.
 *
 * ### What the focuses say
 *
 * One focus is the common answer, and more than one where an exercise genuinely serves two — §8
 * allocates exposure *over* the vocabulary, so an exercise that both trains the hips and asks for
 * hip mobility has to say so, and an exercise that is one of those things only has to say that.
 * The distinctions the table draws are the ones a generated plan acts on:
 *
 * | focus | what an exercise classified here does |
 * | --- | --- |
 * | `PUSH` | pressing: chest, shoulders, triceps |
 * | `PULL` | pulling: back, biceps, grip |
 * | `LEGS` | squatting, hinging, lunging, loaded hip extension |
 * | `CORE` | trunk and pelvic control |
 * | `MOBILITY` | range work: a controlled joint position being reached or held |
 * | `POSTURE` | a position being *held or corrected*, as opposed to moved through |
 * | `CONDITIONING` | continuous rhythmic work that raises heart rate |
 *
 * `MOBILITY` and `POSTURE` are separate on purpose: the catalogue's own `MOBILITY` category covers
 * stretching, mobility *and* posture work, which is why the category cannot be mapped onto a single
 * focus (P23's record). Here they are drawn per exercise — `cat_cow` is mobility, `chin_tucks` is
 * posture, and `cobra_stretch` is both, because it is a held extension that is also range work.
 *
 * ### What this is not
 *
 * It holds no collaborator, reads no state, and is a function of its argument: the same id yields the
 * same focuses on any device, in any process, in any order. It is not a repository, not a settings
 * value, and not user-editable — a **user-facing Goals & Focus editor** that lets a person correct an
 * entry, or a persisted exercise→focus map, would be a different owner of the same fact and is
 * recorded as a later decision, not something this table anticipates.
 */
object ProductionFocusClassification : GenerationFocusSource {

    /**
     * The classification itself, by the catalogue's own opaque library id (§10: opaque in both
     * directions — the ids are never mapped, joined or re-derived, only read).
     *
     * Values are held in the vocabulary's canonical order, so the same entry always has the same
     * iteration order and two runs cannot differ.
     */
    private val CLASSIFICATION: Map<String, List<Focus>> = mapOf(
        // ── pressing ────────────────────────────────────────────────────────────────────────────
        "pushups" to listOf(Focus.PUSH),
        "pushups_wide" to listOf(Focus.PUSH),
        "pushups_military" to listOf(Focus.PUSH),
        "pushups_knee" to listOf(Focus.PUSH),
        "decline_pushups" to listOf(Focus.PUSH),
        "diamond_pushups" to listOf(Focus.PUSH),
        "dips" to listOf(Focus.PUSH),
        "pike_pushups" to listOf(Focus.PUSH),

        // ── pulling ────────────────────────────────────────────────────────────────────────────
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

        // ── legs ───────────────────────────────────────────────────────────────────────────────
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

        // ── core ───────────────────────────────────────────────────────────────────────────────
        "plank" to listOf(Focus.CORE),
        "side_plank" to listOf(Focus.CORE),
        "dead_bug" to listOf(Focus.CORE),
        "leg_raises" to listOf(Focus.CORE),
        "pelvic_tilt" to listOf(Focus.CORE, Focus.POSTURE),
        "couch_stretch" to listOf(Focus.CORE, Focus.MOBILITY),
        "bird_dog" to listOf(Focus.CORE, Focus.MOBILITY),
        "bird_dog_reps" to listOf(Focus.CORE, Focus.MOBILITY),
        "superman" to listOf(Focus.CORE, Focus.POSTURE),

        // ── mobility ───────────────────────────────────────────────────────────────────────────
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

        // ── posture ────────────────────────────────────────────────────────────────────────────
        "y_t_raises" to listOf(Focus.MOBILITY, Focus.POSTURE),
        "scapular_retraction_hold" to listOf(Focus.POSTURE),
        "reverse_snow_angels" to listOf(Focus.MOBILITY, Focus.POSTURE),
        "wall_slides" to listOf(Focus.MOBILITY, Focus.POSTURE),
        "chin_tucks" to listOf(Focus.POSTURE),
        "horse_stance" to listOf(Focus.LEGS, Focus.POSTURE),

        // ── conditioning ───────────────────────────────────────────────────────────────────────
        "burpees" to listOf(Focus.PUSH, Focus.LEGS, Focus.CONDITIONING),
        "mountain_climbers" to listOf(Focus.CORE, Focus.CONDITIONING),
        "jumping_jacks" to listOf(Focus.MOBILITY, Focus.CONDITIONING),
        "kettlebell_swing" to listOf(Focus.LEGS, Focus.CONDITIONING)
    )

    init {
        // Two guards on the *data*, so a careless edit fails when the table is built rather than
        // somewhere downstream where the message would not name the offending id. The third claim —
        // that the table names no id the catalogue does not have, and misses none it does — is not
        // checkable here, because this object must not read the catalogue; `ProductionFocusClassificationTest`
        // measures it against the real one instead.
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
     * The focuses [exerciseId] trains, or `null` when this table states none.
     *
     * `null` is a real answer, not an absence of one: it is what the boundary reports as
     * unclassified, and it is what keeps "this exercise trains everything" and "this exercise trains
     * an empty set" both unreachable. A test may substitute its own source, so this signature is the
     * contract rather than a convenience.
     */
    override fun focusesOf(exerciseId: String): Set<Focus>? =
        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }

    /** Every id this table classifies — the table read as a value, for review and for tests. */
    val classifiedExerciseIds: Set<String>
        get() = CLASSIFICATION.keys

    /** Whether this table states [exerciseId]'s focuses, as opposed to leaving it unclassified. */
    fun classifies(exerciseId: String): Boolean = CLASSIFICATION.containsKey(exerciseId)
}
