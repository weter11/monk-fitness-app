package com.monkfitness.app.domain.adaptive

/**
 * The **metadata vocabulary** a plan element is described with: which training domain an exercise
 * serves, which body region it trains, and the four facts about it a planner may consume.
 *
 * ### Why this file is separate
 *
 * These three types were declared beside the `CustomProgram` configuration's error and warning
 * vocabulary, in one file that §30 step 15 deleted with the editor that used it. They are not part of
 * that surface: they are what the **target** Generated Planner consumes
 * (`domain/program/generated/GenerationRequest.kt` carries an `ExerciseMetadata<E>`) and what
 * `data/model/Exercise.kt` maps the app's own catalogue *into*. Splitting them out is what let the dead
 * configuration surface go without taking a target dependency with it — and it is the reason this file
 * has no editor, no error code and no validation outcome in it.
 *
 * The generic parameter is the exercise-library id type: the planner is fed metadata about the library
 * it plans from, and nothing here names a concrete exercise, a table or a store.
 */

/**
 * A training domain of the program: the coarse channel the app's own workout days are generated
 * from, and the vocabulary this validation reports coverage in.
 *
 * The two values are a partition of the app's existing exercise categories, not a new
 * classification: [STRENGTH] is loaded strength work (the app's `STRENGTH` category) and
 * [FLEXIBILITY] is the flexibility channel the mobility days and the posture/mobility sessions draw
 * from — the app's `MOBILITY`, `STRETCHING` and `POSTURE` categories. A caller maps its own exercise
 * metadata onto this vocabulary; the mapping is mechanical, and it is pinned by the library's own
 * definitions rather than restated exercise by exercise.
 */
enum class TrainingDomain {
    /** Loaded strength work: what the program's strength and functional days are built from. */
    STRENGTH,

    /** Flexibility work: mobility, stretching and posture. */
    FLEXIBILITY
}

/**
 * The body region an exercise trains, in the vocabulary the app itself reports as its body regions
 * (its exercise subcategories). It is the finer axis this validation uses to describe balance: a
 * selection can cover every required training domain and still leave a region of the body unaddressed.
 *
 * As with [TrainingDomain], the values mirror the app's existing classification and are supplied by
 * the caller; nothing here restates an exercise.
 */
enum class BodyRegion {
    SHOULDERS,
    SPINE,
    HIPS,
    LEGS,
    CORE,
    FULL_BODY,
    HYPERLORDOSIS
}

/**
 * One exercise, as validation needs to see it: its canonical id and family, the training domain and
 * body region it belongs to, and the equipment it cannot be performed without.
 *
 * This is a view of metadata the app already owns, not a second exercise catalogue: the caller
 * supplies one entry per library exercise, derived from that exercise's own definitions, and
 * [requiredEquipment] carries the app's own equipment values — the type is the caller's, so the
 * domain never restates or re-declares the equipment vocabulary. An exercise that needs nothing
 * carries an empty set, and equipment tokens the app uses to mean "nothing" are tolerated without
 * being interpreted (the availability rule below reads requirements, never tokens).
 *
 * The type carries no prescription: no sets, reps, durations, difficulty or order. Validation is
 * about whether a selection is structurally and training-domain valid, and nothing about it applies,
 * persists, generates or adjusts a workout.
 */
data class ExerciseMetadata<E>(
    val id: String,
    val familyId: String,
    val trainingDomain: TrainingDomain,
    val bodyRegion: BodyRegion,
    val requiredEquipment: Set<E> = emptySet()
) {
    init {
        require(id.isNotBlank()) { "an exercise metadata must carry an exercise id" }
        require(familyId.isNotBlank()) { "an exercise metadata must carry its family, was \"$familyId\"" }
    }
}
