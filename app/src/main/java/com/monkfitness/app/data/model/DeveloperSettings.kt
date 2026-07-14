package com.monkfitness.app.data.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Centralized, developer-only configuration.
 *
 * [showEngineeringValidation] gates the visibility of the "Engineering Validation"
 * family (static biomechanics reference poses) in the Exercises browser and
 * Search. It defaults to **OFF**, so the category is invisible by default and
 * never mixes with normal exercise families.
 *
 * This is the single source of truth: the browser and search ask this object
 * whether the category should be visible — they do NOT implement their own
 * filtering. Training systems (workouts, recommendations, statistics,
 * progression, achievements, random generation) ignore this flag entirely and
 * always exclude test poses via [Exercise.isTestPose].
 */
object DeveloperSettings {
    private val _showEngineeringValidation = MutableStateFlow(false)
    val showEngineeringValidationFlow: StateFlow<Boolean> = _showEngineeringValidation

    var showEngineeringValidation: Boolean
        get() = _showEngineeringValidation.value
        set(value) { _showEngineeringValidation.value = value }
}
