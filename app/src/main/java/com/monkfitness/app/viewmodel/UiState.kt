package com.monkfitness.app.viewmodel

import androidx.compose.runtime.Immutable
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseFamily
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.Workout
import com.monkfitness.app.validation.ValidationCategory
import com.monkfitness.app.validation.ValidationPose

@Immutable
data class HomeUiState(
    val currentDay: Int,
    val workout: Workout,
    val completedCount: Int,
    val completedPostureCount: Int,
    val streak: Int,
    val additionalPostureTrainingEnabled: Boolean,
    val flexibilityTrainingType: FlexibilityTrainingType,
    val flexibilityFocusAreas: Set<ExerciseSubCategory>,
    val todayProgramDayState: ProgramDayState
)

@Immutable
data class WorkoutSessionUiState(
    val day: Int?,
    val workout: Workout,
    val warmupExercises: List<Exercise>,
    val isPostureMobilitySession: Boolean
)

@Immutable
data class PostureUiState(
    val selectedCategory: ExerciseCategory?,
    val selectedSubCategory: ExerciseSubCategory?,
    val availableSubCategories: List<ExerciseSubCategory>,
    val filteredExercises: List<Exercise>,
    val families: List<ExerciseFamily>,
    val exercisesByFamily: Map<String, List<Exercise>>,
    val expandedFamilyIds: Set<String>,
    /**
     * Engineering Validation category. Null whenever the "Show Engineering Validation
     * category" setting is OFF, or when it is filtered out — so the normal catalog,
     * workout generation, statistics and search never see validation poses.
     */
    val validationCategory: ValidationCategory? = null,
    val validationPoses: List<ValidationPose> = emptyList()
)
