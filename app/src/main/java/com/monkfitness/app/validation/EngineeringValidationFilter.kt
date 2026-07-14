package com.monkfitness.app.validation

/**
 * Decides whether the Engineering Validation section is visible in the Exercise library.
 *
 * This is the single place where the validation subsystem plugs into the existing category
 * filter semantics, without the exercise catalog, workout generator or statistics code ever
 * knowing about validation poses:
 *
 * - OFF (default): never visible. The app behaves exactly as before.
 * - ON + "Show exercises matching selected categories" OFF: visible regardless of disabled
 *   families (all normal exercises plus Engineering Validation).
 * - ON + filter ON: visible only when the Engineering Validation family is not disabled,
 *   i.e. it participates in the same `disabledExerciseFamilies` set as every other family.
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
