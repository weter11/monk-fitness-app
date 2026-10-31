package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.prescription.PrescriptionDimension

/**
 * A training domain of the program. The two values partition the app's existing exercise categories
 * rather than introducing a new classification, and a caller maps its own metadata onto this vocabulary.
 */
enum class TrainingDomain {
    STRENGTH,

    FLEXIBILITY
}

/**
 * The finer axis that describes balance: a selection can cover every required training domain and still
 * leave a region of the body unaddressed. The values mirror the app's own subcategories.
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
 * One exercise as generation needs to see it — a view of metadata the app already owns, not a second
 * catalogue.
 *
 * [repRange], [timeRange] and [sets] come from the exercise's **own catalogue definition**, never from a
 * planner, so generation starts an exercise where the catalogue says it starts. They are not a
 * prescription and they do not decide how an adaptation moves the range: a caller stating no range
 * (`null`) is still a complete value, and the planner then falls back to its own targets.
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
 * One exercise's progression span, as the adaptive stage may see it: the range alone, without the
 * catalogue's unrelated vocabulary.
 *
 * A range is stated in **one** dimension, exactly as a prescription is written in one; an exercise with
 * no range is `null` at the call site rather than an empty range here.
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

    /** `null` when this range is the other dimension's, or one the domain cannot express yet. */
    fun amountsFor(dimension: PrescriptionDimension): IntRange? = when (dimension) {
        PrescriptionDimension.REP_BASED -> repRange
        PrescriptionDimension.TIME_BASED -> timeRange
        PrescriptionDimension.SET_BASED,
        PrescriptionDimension.DIFFICULTY_BASED,
        PrescriptionDimension.REST_BASED -> null
    }
}

/**
 * The bridge from the catalogue's vocabulary to the adaptive input: the range travels, and nothing else
 * does — the adaptive stage has no use for a body region or an equipment requirement.
 */
fun ExerciseMetadata<*>.toExerciseRange(): ExerciseRange? = when {
    repRange != null -> ExerciseRange(repRange = repRange)
    timeRange != null -> ExerciseRange(timeRange = timeRange)
    else -> null
}

/**
 * A caller fact, like the family classification beside it: the domain owns no catalogue. [NONE] is the
 * honest default for a caller that states no ranges, leaving nothing to clamp a prescription step to.
 */
fun interface ExerciseRangeSource {

    fun rangeOf(exerciseId: String): ExerciseRange?

    companion object {

        val NONE: ExerciseRangeSource = ExerciseRangeSource { null }
    }
}
