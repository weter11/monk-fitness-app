package com.monkfitness.app.domain.adaptive

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

/**
 * Why a configuration cannot be applied.
 *
 * The codes are the contract; user-facing wording belongs to the UI that presents them.
 */
enum class ProgramConfigurationErrorCode {
    /** The selection covers no exercise of a required training domain. */
    MISSING_REQUIRED_TRAINING_DOMAIN,

    /** The selection enables an exercise the user's available equipment cannot support. */
    EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT
}

/**
 * One hard validation error: the configuration cannot be applied as it stands.
 *
 * [trainingDomain] is present for [ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN]
 * and [exerciseId] for [ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT], and
 * exactly one of them for either. The exercise id is always an exercise the user's own selection
 * enabled — a validation reports what is wrong with the selection, it never proposes another one.
 * The equipment an offending exercise needs is deliberately not carried here: the caller already
 * holds the library it supplied, and keeping the equipment vocabulary out of this type is what lets
 * a validation result be read without it.
 */
data class ProgramConfigurationError(
    val code: ProgramConfigurationErrorCode,
    val trainingDomain: TrainingDomain? = null,
    val exerciseId: String? = null
) {
    init {
        when (code) {
            ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN -> require(
                trainingDomain != null && exerciseId == null
            ) {
                "a missing required training domain must name the domain and no exercise, " +
                    "was $trainingDomain / $exerciseId"
            }
            ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT -> require(
                exerciseId != null && trainingDomain == null
            ) {
                "an unusable exercise must name the exercise and no domain, " +
                    "was $exerciseId / $trainingDomain"
            }
        }
    }
}

/**
 * Why a configuration is technically valid but poorly balanced.
 *
 * The codes are the contract, and they are never a rejection: a selection that produces warnings can
 * still be applied.
 */
enum class ProgramConfigurationWarningCode {
    /** The selection enables no exercise in a body region the program's own days are built from. */
    BODY_REGION_NOT_COVERED,

    /** The selection concentrates in one body region: it holds more exercises than all the others. */
    BODY_REGION_CONCENTRATION
}

/**
 * One soft validation warning.
 *
 * [bodyRegion] is the region the warning is about and [usableExerciseCount] is what was measured for
 * it: `0` for [ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED], and the region's own count
 * for [ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION]. The count is a measurement of the
 * selection the user proposed, never a target to fill.
 */
data class ProgramConfigurationWarning(
    val code: ProgramConfigurationWarningCode,
    val bodyRegion: BodyRegion,
    val usableExerciseCount: Int
) {
    init {
        require(usableExerciseCount >= 0) {
            "a usable exercise count cannot be negative, was $usableExerciseCount"
        }
        require(
            code != ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED || usableExerciseCount == 0
        ) {
            "a covered region is not a coverage warning, $bodyRegion had $usableExerciseCount"
        }
        require(
            code != ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION || usableExerciseCount > 0
        ) {
            "a concentration warning must report the concentrated count, $bodyRegion had $usableExerciseCount"
        }
    }
}

/**
 * The structured result of validating one proposed configuration.
 *
 * It answers the only question that matters to a caller: whether the configuration may be applied,
 * and if so, what the user should be told first.
 *
 *  * [isValid] false — the configuration cannot be applied; [errors] says why, and the configuration
 *    itself is exactly what the user proposed, untouched and unrepaired;
 *  * [isValid] true and [hasWarnings] true — the configuration may be applied after the warnings are
 *    presented;
 *  * [isValid] true and [hasWarnings] false — the configuration may simply be applied.
 *
 * The result carries no selection of its own: no repaired set of enabled exercises, no defaults, no
 * replacement for anything. A configuration that was rejected stays the user's rejected
 * configuration, and deciding what to do about it is the caller's business. Ordering is canonical —
 * errors by code and then by the id or domain they name, warnings by code and then by region — so
 * an identical request always produces an identical result.
 */
data class ProgramConfigurationValidation(
    val errors: List<ProgramConfigurationError>,
    val warnings: List<ProgramConfigurationWarning>
) {
    /** Whether the configuration may be applied at all. */
    val isValid: Boolean
        get() = errors.isEmpty()

    /** Whether a valid configuration carries balance concerns that should be presented first. */
    val hasWarnings: Boolean
        get() = warnings.isNotEmpty()
}
