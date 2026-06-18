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
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.UserPreferences
import com.monkfitness.app.data.model.flexibilityFocusAreas
import com.monkfitness.app.data.model.flexibilitySpecificFocusAreas
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        private const val EXERCISE_DIFFICULTY_PREFIX = "exercise_difficulty_"
        private const val EXERCISE_PERSONAL_RECORD_PREFIX = "exercise_personal_record_"
        val LANGUAGE_KEY = stringPreferencesKey("language")
        val NOTIFICATION_HOUR = intPreferencesKey("notification_hour")
        val NOTIFICATION_MINUTE = intPreferencesKey("notification_minute")
        val IS_ONBOARDING_COMPLETED = booleanPreferencesKey("is_onboarding_completed")
        val TIMER_TICKS_ENABLED = booleanPreferencesKey("timer_ticks_enabled")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val ADDITIONAL_POSTURE_TRAINING_ENABLED = booleanPreferencesKey("additional_posture_training_enabled")
        val FLEXIBILITY_TRAINING_TYPE = stringPreferencesKey("flexibility_training_type")
        val FLEXIBILITY_FOCUS_AREAS = stringPreferencesKey("flexibility_focus_areas")
        val AVAILABLE_EQUIPMENT = stringSetPreferencesKey("available_equipment")
        val NUTRITION_WEIGHT = stringPreferencesKey("nutrition_weight")
        val NUTRITION_HEIGHT = stringPreferencesKey("nutrition_height")
        val NUTRITION_PLAN_DAYS = intPreferencesKey("nutrition_plan_days")
        val NUTRITION_CYCLE_LENGTH = intPreferencesKey("nutrition_cycle_length")
        val NUTRITION_EXCLUDED_FOODS = stringSetPreferencesKey("nutrition_excluded_foods")
        val NUTRITION_AVAILABLE_PRODUCTS = stringSetPreferencesKey("nutrition_available_products")
        val PROGRAM_START_DATE = stringPreferencesKey("program_start_date")
        val PROGRAM_SUMMARY_DISMISSED = booleanPreferencesKey("program_summary_dismissed")
        val NUTRITION_WARNING_DISMISSED_FOR = stringPreferencesKey("nutrition_warning_dismissed_for")
        val SHOW_EXCLUDED_PRODUCTS_IN_NUTRITION = booleanPreferencesKey("show_excluded_products_in_nutrition")
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

    val availableEquipmentFlow: Flow<Set<Equipment>> = context.dataStore.data.map { preferences ->
        preferences[AVAILABLE_EQUIPMENT].toEquipmentSet()
    }

    suspend fun setAvailableEquipment(equipment: Set<Equipment>) {
        val normalizedEquipment = normalizeAvailableEquipment(equipment)
        context.dataStore.edit { preferences ->
            preferences[AVAILABLE_EQUIPMENT] = normalizedEquipment.map { it.name }.toSet()
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

    val nutritionCycleLengthFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        val explicit = preferences[NUTRITION_CYCLE_LENGTH]
        if (explicit != null) {
            explicit.toNutritionCycleLength()
        } else {
            (preferences[NUTRITION_PLAN_DAYS] ?: 3).legacyNutritionPlanDaysToCycleLength()
        }
    }

    suspend fun setNutritionCycleLength(days: Int) {
        context.dataStore.edit { preferences ->
            preferences[NUTRITION_CYCLE_LENGTH] = days.toNutritionCycleLength()
        }
    }

    val nutritionExcludedFoodsFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[NUTRITION_EXCLUDED_FOODS].orEmpty()
    }

    suspend fun setNutritionExcludedFoods(foodKeys: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[NUTRITION_EXCLUDED_FOODS] = foodKeys
        }
    }

    val nutritionAvailableProductsFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[NUTRITION_AVAILABLE_PRODUCTS].orEmpty()
    }

    suspend fun setNutritionAvailableProducts(productKeys: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[NUTRITION_AVAILABLE_PRODUCTS] = productKeys
        }
    }

    val userPreferencesFlow: Flow<UserPreferences> = combine(
        nutritionExcludedFoodsFlow,
        availableEquipmentFlow
    ) { excludedFoods, availableEquipment ->
        UserPreferences(
            excludedFoods = excludedFoods,
            availableEquipment = availableEquipment
        )
    }

    val programStartDateFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PROGRAM_START_DATE] ?: LocalDate.now().toString()
    }

    suspend fun ensureProgramStartDate() {
        context.dataStore.edit { preferences ->
            if (preferences[PROGRAM_START_DATE] == null) {
                preferences[PROGRAM_START_DATE] = LocalDate.now().toString()
            }
        }
    }

    suspend fun resetProgramStartDate(date: String = LocalDate.now().toString()) {
        context.dataStore.edit { preferences ->
            preferences[PROGRAM_START_DATE] = date
            preferences[PROGRAM_SUMMARY_DISMISSED] = false
        }
    }

    val programSummaryDismissedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PROGRAM_SUMMARY_DISMISSED] ?: false
    }

    suspend fun setProgramSummaryDismissed(dismissed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PROGRAM_SUMMARY_DISMISSED] = dismissed
        }
    }

    val nutritionWarningDismissedForFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[NUTRITION_WARNING_DISMISSED_FOR]
    }

    suspend fun dismissNutritionWarningFor(key: String?) {
        context.dataStore.edit { preferences ->
            if (key == null) {
                preferences.remove(NUTRITION_WARNING_DISMISSED_FOR)
            } else {
                preferences[NUTRITION_WARNING_DISMISSED_FOR] = key
            }
        }
    }

    val showExcludedProductsInNutritionFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_EXCLUDED_PRODUCTS_IN_NUTRITION] ?: false
    }

    suspend fun setShowExcludedProductsInNutrition(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_EXCLUDED_PRODUCTS_IN_NUTRITION] = show
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

    val exercisePersonalRecordsFlow: Flow<Map<String, Int>> = context.dataStore.data.map { preferences ->
        preferences.asMap()
            .mapNotNull { (key, value) ->
                if (!key.name.startsWith(EXERCISE_PERSONAL_RECORD_PREFIX) || value !is Int) {
                    null
                } else {
                    key.name.removePrefix(EXERCISE_PERSONAL_RECORD_PREFIX) to value.coerceAtLeast(0)
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

    fun getExercisePersonalRecordFlow(exerciseId: String): Flow<Int> {
        val key = intPreferencesKey("$EXERCISE_PERSONAL_RECORD_PREFIX$exerciseId")
        return context.dataStore.data.map { preferences ->
            (preferences[key] ?: 0).coerceAtLeast(0)
        }
    }

    suspend fun setExercisePersonalRecord(exerciseId: String, recordValue: Int) {
        val key = intPreferencesKey("$EXERCISE_PERSONAL_RECORD_PREFIX$exerciseId")
        context.dataStore.edit { preferences ->
            preferences[key] = recordValue.coerceAtLeast(0)
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

    private fun Set<String>?.toEquipmentSet(): Set<Equipment> {
        val parsed = this
            .orEmpty()
            .mapNotNull { raw -> Equipment.entries.firstOrNull { it.name == raw } }
            .toSet()

        return normalizeAvailableEquipment(parsed)
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

    private fun normalizeAvailableEquipment(equipment: Set<Equipment>): Set<Equipment> {
        return when {
            equipment.isEmpty() -> emptySet()
            equipment == setOf(Equipment.NONE) -> setOf(Equipment.NONE)
            else -> equipment.filter { it != Equipment.NONE }.toSet()
        }
    }

    private fun Int.legacyNutritionPlanDaysToCycleLength(): Int {
        return when {
            this <= 0 -> 0
            this == 1 -> 1
            this >= 7 -> 7
            else -> 3
        }
    }

    private fun Int.toNutritionCycleLength(): Int {
        return when (this) {
            0, 1, 3, 7 -> this
            in Int.MIN_VALUE..0 -> 0
            2 -> 3
            in 4..6 -> 7
            else -> 7
        }
    }
}
