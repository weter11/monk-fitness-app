package com.monkfitness.app.validation

/**
 * Decides whether the Engineering Validation section is visible in the Exercise library — the single
 * place the subsystem plugs into the category filter, so the catalog, generator and statistics never
 * learn about validation poses. With the category filter off, visibility ignores disabled families;
 * with it on, this family joins the same `disabledExerciseFamilies` set as every other.
 */
object EngineeringValidationFilter {

    fun isVisible(
        settingEnabled: Boolean,
        filterLibraryByCategories: Boolean,
        disabledExerciseFamilies: Set<String>
    ): Boolean {
        if (!settingEnabled) return false
        if (!filterLibraryByCategories) return true
        return ENGINEERING_VALIDATION_FAMILY_ID !in disabledExerciseFamilies
    }
}
