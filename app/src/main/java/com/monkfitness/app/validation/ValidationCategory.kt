package com.monkfitness.app.validation

import androidx.annotation.StringRes
import com.monkfitness.app.R

/**
 * Developer-only category, present only when the "Show Engineering Validation category" setting is on,
 * and intentionally kept out of the exercise family and the workout catalog.
 */
const val ENGINEERING_VALIDATION_FAMILY_ID = "engineering_validation"

/** Display descriptor for that family, living here so the catalog remains unaware of it. */
data class ValidationCategory(
    val id: String = ENGINEERING_VALIDATION_FAMILY_ID,
    @StringRes val nameRes: Int = R.string.validation_category_name,
    @StringRes val descriptionRes: Int = R.string.validation_category_desc
)
