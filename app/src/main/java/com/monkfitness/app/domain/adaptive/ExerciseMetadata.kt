package com.monkfitness.app.domain.adaptive

/**
 * The **metadata vocabulary** a plan element is described with: which training domain an exercise
 * serves, which body region it trains, and the facts about it a planner may consume.
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
 * ### The progression range is catalogue data, not a prescription
 *
 * [repRange], [timeRange] and [sets] are the span and set count the exercise's **own catalogue
 * definition** states — read from it by `Exercise.toConfigurationMetadata`, never chosen by a
 * planner. They exist so generation can start an exercise where the catalogue says it starts (at the
 * lower bound of the span it will travel, for as many sets as the catalogue defines) instead of at a
 * fixed shape of the generator's own.
 *
 * They are not a prescription: there is no per-set sequence, no difficulty, no order and no rest,
 * and nothing here decides how an *adaptation* moves the range. A caller that states no range
 * (`null`) is still a complete value — the planner then falls back to its own policy targets.
 *
 * @property repRange the full span this exercise's repetitions progress through, when the catalogue
 *   states one. `null` for a timed exercise, and for a caller that states no range at all.
 * @property timeRange the same span for a timed exercise, in seconds; `null` for a repetition one.
 * @property sets the set count the catalogue defines this exercise with — the count a generated
 *   prescription is written for. `0` only when the caller states no range, which is the one case
 *   where no set count is needed.
 */
data class ExerciseMetadata<E>(
    val id: String,
    val familyId: String,
    val trainingDomain: TrainingDomain,
    val bodyRegion: BodyRegion,
    val requiredEquipment: Set<E> = emptySet(),
    val repRange: IntRange? = null,
    val timeRange: IntRange? = null,
    val sets: Int = 0
) {
    init {
        require(id.isNotBlank()) { "an exercise metadata must carry an exercise id" }
        require(familyId.isNotBlank()) { "an exercise metadata must carry its family, was \"$familyId\"" }
        require(repRange == null || timeRange == null) {
            "an exercise progresses in one dimension: '${id}' stated repRange=$repRange and " +
                "timeRange=$timeRange, and a prescription is written in one of them"
        }
        repRange?.let { range ->
            require(!range.isEmpty()) {
                "a progression range spans at least one value, '${id}' stated $range"
            }
        }
        timeRange?.let { range ->
            require(!range.isEmpty()) {
                "a progression range spans at least one value, '${id}' stated $range"
            }
        }
        require(sets >= 0) { "a catalogue set count is a count, '${id}' stated $sets" }
        require((repRange == null && timeRange == null) || sets >= 1) {
            "an exercise that states a progression range is also stated with the set count its " +
                "catalogue definition carries, '${id}' stated $sets"
        }
    }
}
