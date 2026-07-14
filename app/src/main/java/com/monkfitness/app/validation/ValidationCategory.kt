package com.monkfitness.app.validation

import androidx.annotation.StringRes
import com.monkfitness.app.R

/**
 * Identifier of the Engineering Validation family inside the Exercise library.
 *
 * This is a developer-only category that exists only when the
 * "Show Engineering Validation category" setting is enabled. It is intentionally kept
 * separate from [com.monkfitness.app.data.model.ExerciseFamily] and the workout catalog.
 */
const val ENGINEERING_VALIDATION_FAMILY_ID = "engineering_validation"

/**
 * Display descriptor for the Engineering Validation family.
 *
 * Lives in the validation subsystem so the normal [com.monkfitness.app.data.model.ExerciseFamily]
 * catalog (and therefore workout generation, statistics, etc.) remains unaware of it.
 */
data class ValidationCategory(
    val id: String = ENGINEERING_VALIDATION_FAMILY_ID,
    @StringRes val nameRes: Int = R.string.validation_category_name,
    @StringRes val descriptionRes: Int = R.string.validation_category_desc
)
