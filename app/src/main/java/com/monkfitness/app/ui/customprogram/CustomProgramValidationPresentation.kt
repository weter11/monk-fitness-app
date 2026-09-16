package com.monkfitness.app.ui.customprogram

import com.monkfitness.app.R
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.domain.adaptive.BodyRegion
import com.monkfitness.app.domain.adaptive.ProgramConfigurationErrorCode
import com.monkfitness.app.domain.adaptive.ProgramConfigurationValidation
import com.monkfitness.app.domain.adaptive.ProgramConfigurationWarningCode
import com.monkfitness.app.domain.adaptive.TrainingDomain

/**
 * The one place where the validation vocabulary becomes words the user reads.
 *
 * The validator speaks in codes, body regions, training domains and exercise ids — deliberately, so it
 * can stay a pure domain function with no resource table in it. The editor renders those findings, and
 * this object is the single mapping: each code to its message resource, each domain and region to the
 * label resource the rest of the app already uses for it, and each offending exercise to the name and
 * equipment labels of the very library the screen is showing.
 *
 * Nothing is decided here. No finding is added, dropped, reordered, softened or hardened — the lists
 * come out in the validator's own canonical order, with the validator's own fields intact, so a test
 * can compare a presentation against a direct validator call and find them identical. In particular the
 * two hard-error kinds stay hard errors and the two balance findings stay warnings.
 */
object CustomProgramValidationPresentation {

    /**
     * Turns hard errors into presentable findings.
     *
     * @param validation the domain validator's result.
     * @param libraryById the library the screen is showing, used to resolve an offending exercise's
     *   display name and its equipment requirements. An id the library does not define is presented
     *   without a name rather than invented one.
     */
    fun errors(
        validation: ProgramConfigurationValidation,
        libraryById: Map<String, Exercise>
    ): List<CustomProgramErrorPresentation> = validation.errors.map { error ->
        when (error.code) {
            ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN ->
                CustomProgramErrorPresentation(
                    code = error.code,
                    trainingDomain = error.trainingDomain,
                    exerciseId = error.exerciseId,
                    messageRes = R.string.custom_program_error_missing_domain,
                    subjectRes = error.trainingDomain?.let { domain -> domainLabelRes(domain) } ?: 0
                )

            ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT -> {
                val exercise = error.exerciseId?.let { id -> libraryById[id] }
                CustomProgramErrorPresentation(
                    code = error.code,
                    trainingDomain = error.trainingDomain,
                    exerciseId = error.exerciseId,
                    messageRes = R.string.custom_program_error_unavailable_equipment,
                    subjectRes = exercise?.nameRes ?: 0,
                    requiredEquipmentRes = equipmentLabelsOf(exercise)
                )
            }
        }
    }

    /** Turns balance findings into presentable warnings, in the validator's own order. */
    fun warnings(
        validation: ProgramConfigurationValidation
    ): List<CustomProgramWarningPresentation> = validation.warnings.map { warning ->
        CustomProgramWarningPresentation(
            code = warning.code,
            bodyRegion = warning.bodyRegion,
            usableExerciseCount = warning.usableExerciseCount,
            messageRes = when (warning.code) {
                ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED ->
                    R.string.custom_program_warning_region_not_covered

                ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION ->
                    R.string.custom_program_warning_region_concentration
            },
            subjectRes = bodyRegionLabelRes(warning.bodyRegion)
        )
    }

    /** The label resource of a training domain, in the vocabulary the validation reports. */
    fun domainLabelRes(domain: TrainingDomain): Int = when (domain) {
        TrainingDomain.STRENGTH -> R.string.custom_program_domain_strength
        TrainingDomain.FLEXIBILITY -> R.string.custom_program_domain_flexibility
    }

    /**
     * The label resource of a body region. The regions are the app's own body regions, so this reuses
     * the subcategory labels the exercise library, the posture screen and the filters already show.
     */
    fun bodyRegionLabelRes(region: BodyRegion): Int = when (region) {
        BodyRegion.SHOULDERS -> R.string.subcategory_shoulders
        BodyRegion.SPINE -> R.string.subcategory_spine
        BodyRegion.HIPS -> R.string.subcategory_hips
        BodyRegion.LEGS -> R.string.subcategory_legs
        BodyRegion.CORE -> R.string.subcategory_core
        BodyRegion.FULL_BODY -> R.string.subcategory_full_body
        BodyRegion.HYPERLORDOSIS -> R.string.subcategory_hyperlordosis
    }

    /** What an exercise cannot be performed without, in a stable order, as label resources. */
    private fun equipmentLabelsOf(exercise: Exercise?): List<Int> = exercise
        ?.requiredEquipment
        .orEmpty()
        .filter { equipment -> equipment != Equipment.NONE }
        .sortedBy { equipment -> equipment.ordinal }
        .map { equipment -> equipment.labelRes }
}
