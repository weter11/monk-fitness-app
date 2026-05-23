package com.monkfitness.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.flexibilityFocusAreas
import com.monkfitness.app.data.model.flexibilitySpecificFocusAreas
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        private const val EXERCISE_DIFFICULTY_PREFIX = "exercise_difficulty_"
        val LANGUAGE_KEY = stringPreferencesKey("language")
        val NOTIFICATION_HOUR = intPreferencesKey("notification_hour")
        val NOTIFICATION_MINUTE = intPreferencesKey("notification_minute")
        val IS_ONBOARDING_COMPLETED = booleanPreferencesKey("is_onboarding_completed")
        val TIMER_TICKS_ENABLED = booleanPreferencesKey("timer_ticks_enabled")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val ADDITIONAL_POSTURE_TRAINING_ENABLED = booleanPreferencesKey("additional_posture_training_enabled")
        val FLEXIBILITY_TRAINING_TYPE = stringPreferencesKey("flexibility_training_type")
        val FLEXIBILITY_FOCUS_AREAS = stringPreferencesKey("flexibility_focus_areas")
        val NUTRITION_WEIGHT = stringPreferencesKey("nutrition_weight")
        val NUTRITION_HEIGHT = stringPreferencesKey("nutrition_height")
        val NUTRITION_TRACKING_DATE = stringPreferencesKey("nutrition_tracking_date")
        val NUTRITION_COMPLETED_MEALS = stringSetPreferencesKey("nutrition_completed_meals")
    }

    val languageFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: "ru"
    }

    suspend fun setLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language
        }
    }

    val notificationTimeFlow: Flow<Pair<Int, Int>> = context.dataStore.data.map { preferences ->
        val hour = preferences[NOTIFICATION_HOUR] ?: 9
        val minute = preferences[NOTIFICATION_MINUTE] ?: 0
        Pair(hour, minute)
    }

    suspend fun setNotificationTime(hour: Int, minute: Int) {
        context.dataStore.edit { preferences ->
            preferences[NOTIFICATION_HOUR] = hour
            preferences[NOTIFICATION_MINUTE] = minute
        }
    }

    val isOnboardingCompletedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setOnboardingCompleted() {
        context.dataStore.edit { preferences ->
            preferences[IS_ONBOARDING_COMPLETED] = true
        }
    }

    val timerTicksEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TIMER_TICKS_ENABLED] ?: true
    }

    suspend fun setTimerTicksEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TIMER_TICKS_ENABLED] = enabled
        }
    }

    val vibrationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[VIBRATION_ENABLED] ?: true
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[VIBRATION_ENABLED] = enabled
        }
    }

    val additionalPostureTrainingEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[ADDITIONAL_POSTURE_TRAINING_ENABLED] ?: false
    }

    suspend fun setAdditionalPostureTrainingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ADDITIONAL_POSTURE_TRAINING_ENABLED] = enabled
        }
    }

    val flexibilityTrainingTypeFlow: Flow<FlexibilityTrainingType> = context.dataStore.data.map { preferences ->
        preferences[FLEXIBILITY_TRAINING_TYPE].toFlexibilityTrainingType()
    }

    suspend fun setFlexibilityTrainingType(trainingType: FlexibilityTrainingType) {
        context.dataStore.edit { preferences ->
            preferences[FLEXIBILITY_TRAINING_TYPE] = trainingType.name
        }
    }

    val flexibilityFocusAreasFlow: Flow<Set<ExerciseSubCategory>> = context.dataStore.data.map { preferences ->
        preferences[FLEXIBILITY_FOCUS_AREAS].toExerciseSubCategorySet()
    }

    suspend fun setFlexibilityFocusAreas(focusAreas: Set<ExerciseSubCategory>) {
        val normalizedFocusAreas = normalizeFocusAreas(focusAreas)
        context.dataStore.edit { preferences ->
            preferences[FLEXIBILITY_FOCUS_AREAS] = normalizedFocusAreas.joinToString(",") { it.name }
        }
    }

    val nutritionWeightFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[NUTRITION_WEIGHT] ?: ""
    }

    suspend fun setNutritionWeight(weight: String) {
        context.dataStore.edit { preferences ->
            preferences[NUTRITION_WEIGHT] = weight
        }
    }

    val nutritionHeightFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[NUTRITION_HEIGHT] ?: ""
    }

    suspend fun setNutritionHeight(height: String) {
        context.dataStore.edit { preferences ->
            preferences[NUTRITION_HEIGHT] = height
        }
    }

    val completedNutritionMealsFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        val storedDate = preferences[NUTRITION_TRACKING_DATE]
        val completedMeals = preferences[NUTRITION_COMPLETED_MEALS].orEmpty()
        if (storedDate == currentNutritionTrackingDate()) completedMeals else emptySet()
    }

    suspend fun setNutritionMealCompleted(mealKey: String, completed: Boolean) {
        context.dataStore.edit { preferences ->
            val today = currentNutritionTrackingDate()
            val currentCompletedMeals =
                if (preferences[NUTRITION_TRACKING_DATE] == today) {
                    preferences[NUTRITION_COMPLETED_MEALS].orEmpty().toMutableSet()
                } else {
                    mutableSetOf()
                }

            if (completed) {
                currentCompletedMeals += mealKey
            } else {
                currentCompletedMeals -= mealKey
            }

            preferences[NUTRITION_TRACKING_DATE] = today
            preferences[NUTRITION_COMPLETED_MEALS] = currentCompletedMeals
        }
    }

    val exerciseDifficultyAdjustmentsFlow: Flow<Map<String, Int>> = context.dataStore.data.map { preferences ->
        preferences.asMap()
            .mapNotNull { (key, value) ->
                if (!key.name.startsWith(EXERCISE_DIFFICULTY_PREFIX) || value !is Int) {
                    null
                } else {
                    key.name.removePrefix(EXERCISE_DIFFICULTY_PREFIX) to value.coerceIn(-2, 2)
                }
            }
            .toMap()
    }

    fun getExerciseDifficultyAdjustmentFlow(exerciseId: String): Flow<Int> {
        val key = intPreferencesKey("$EXERCISE_DIFFICULTY_PREFIX$exerciseId")
        return context.dataStore.data.map { preferences ->
            (preferences[key] ?: 0).coerceIn(-2, 2)
        }
    }

    suspend fun setExerciseDifficultyAdjustment(exerciseId: String, adjustment: Int) {
        val key = intPreferencesKey("$EXERCISE_DIFFICULTY_PREFIX$exerciseId")
        context.dataStore.edit { preferences ->
            preferences[key] = adjustment.coerceIn(-2, 2)
        }
    }

    private fun String?.toFlexibilityTrainingType(): FlexibilityTrainingType {
        return FlexibilityTrainingType.entries.firstOrNull { it.name == this } ?: FlexibilityTrainingType.BOTH
    }

    private fun String?.toExerciseSubCategorySet(): Set<ExerciseSubCategory> {
        val parsed = this
            ?.split(",")
            ?.mapNotNull { raw -> ExerciseSubCategory.entries.firstOrNull { it.name == raw } }
            ?.toSet()
            .orEmpty()

        return normalizeFocusAreas(parsed)
    }

    private fun normalizeFocusAreas(focusAreas: Set<ExerciseSubCategory>): Set<ExerciseSubCategory> {
        val allowed = flexibilityFocusAreas.toSet()
        val filtered = focusAreas.filter { it in allowed }.toSet()

        return when {
            filtered.isEmpty() -> setOf(ExerciseSubCategory.FULL_BODY)
            ExerciseSubCategory.FULL_BODY in filtered -> setOf(ExerciseSubCategory.FULL_BODY)
            filtered.none { it in flexibilitySpecificFocusAreas } -> setOf(ExerciseSubCategory.FULL_BODY)
            else -> filtered
        }
    }

    private fun currentNutritionTrackingDate(): String = LocalDate.now().toString()
}
