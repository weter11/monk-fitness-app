package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.prescription.PrescriptionDimension

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

/**
 * One exercise's own progression span, as the adaptive stage is allowed to see it.
 *
 * This is the pure-domain carrier of [ExerciseMetadata]'s `repRange` / `timeRange`: the metadata type
 * also states a training domain, a body region, equipment and a set count, none of which a
 * prescription step has any use for, and it is generic over the caller's equipment type. Carrying the
 * range alone keeps the adaptive layer's input free of the catalogue's unrelated vocabulary — and,
 * because this file is inside `domain/adaptive`, it is the shape `ProgramDomainPurityTest` fences:
 * no data layer, no platform, no mutable state.
 *
 * A range is stated in **one** dimension, exactly as a prescription is written in one. A repetition
 * range and a time range cannot both be stated, because no exercise progresses in both units at once;
 * an exercise that states no range at all is `null` at the call site rather than an empty range here.
 *
 * @property repRange the repetitions an exercise progresses through, when it is repetition-based.
 * @property timeRange the seconds an exercise progresses through, when it is time-based.
 */
data class ExerciseRange(
    val repRange: IntRange? = null,
    val timeRange: IntRange? = null
) {

    init {
        require((repRange == null) != (timeRange == null)) {
            "an exercise range is stated in exactly one dimension: repRange=$repRange " +
                "timeRange=$timeRange"
        }
        val stated = repRange ?: timeRange!!
        require(!stated.isEmpty()) {
            "an exercise range spans at least one value, was $stated"
        }
        require(stated.first >= 1) {
            "an exercise range starts at a performed amount, was $stated"
        }
    }

    /**
     * The span [dimension] is measured in, or `null` when this range is the other dimension's — or a
     * dimension the prescription vocabulary reserves but the domain cannot express yet.
     */
    fun amountsFor(dimension: PrescriptionDimension): IntRange? = when (dimension) {
        PrescriptionDimension.REP_BASED -> repRange
        PrescriptionDimension.TIME_BASED -> timeRange
        PrescriptionDimension.SET_BASED,
        PrescriptionDimension.DIFFICULTY_BASED,
        PrescriptionDimension.REST_BASED -> null
    }
}

/**
 * The exercise range of this metadata, or `null` when the catalogue states none.
 *
 * The bridge between the catalogue's metadata vocabulary and the adaptive input: a caller that already
 * holds [ExerciseMetadata] passes its range on without restating the catalogue. Nothing else about the
 * metadata travels — the adaptive stage has no use for a body region or an equipment requirement.
 */
fun ExerciseMetadata<*>.toExerciseRange(): ExerciseRange? = when {
    repRange != null -> ExerciseRange(repRange = repRange)
    timeRange != null -> ExerciseRange(timeRange = timeRange)
    else -> null
}

/**
 * Where the adaptive layer reads one exercise's range from — a caller fact, like the family
 * classification beside it.
 *
 * The domain owns no catalogue, so a range arrives as an input. [NONE] is the honest default for a
 * caller that states no ranges at all: the layer then has no range to clamp a prescription step to,
 * which is exactly today's behaviour.
 */
fun interface ExerciseRangeSource {

    /** The range [exerciseId] states, or `null` when this caller declares none. */
    fun rangeOf(exerciseId: String): ExerciseRange?

    companion object {

        /** No ranges are declared at all. */
        val NONE: ExerciseRangeSource = ExerciseRangeSource { null }
    }
}
