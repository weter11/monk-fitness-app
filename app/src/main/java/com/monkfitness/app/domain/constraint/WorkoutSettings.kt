package com.monkfitness.app.domain.constraint

import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.Equipment

data class WorkoutSettings(
    val flexibilityTrainingType: FlexibilityTrainingType = FlexibilityTrainingType.BOTH,
    val flexibilityFocusAreas: Set<ExerciseSubCategory> = setOf(ExerciseSubCategory.FULL_BODY),
    val availableEquipment: Set<Equipment> = emptySet(),
    val disabledExerciseFamilies: Set<String> = emptySet(),
    val additionalPostureTrainingEnabled: Boolean = false
)

data class ConstraintResult(
    val adjustedSettings: WorkoutSettings,
    val adjustmentsMade: Boolean,
    val messages: List<String>
)

data class RuleResult(
    val settings: WorkoutSettings,
    val message: String? = null
)

interface ConstraintRule {
    fun validate(settings: WorkoutSettings): RuleResult
}
