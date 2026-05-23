package com.monkfitness.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.postureFocusAreas
import com.monkfitness.app.data.model.stretchFocusAreas
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
        val POSTURE_FOCUS_AREA = stringPreferencesKey("posture_focus_area")
        val STRETCH_FOCUS_AREA = stringPreferencesKey("stretch_focus_area")
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

    val postureFocusAreaFlow: Flow<ExerciseSubCategory> = context.dataStore.data.map { preferences ->
        preferences[POSTURE_FOCUS_AREA].toExerciseSubCategory(postureFocusAreas, ExerciseSubCategory.SPINE)
    }

    suspend fun setPostureFocusArea(focusArea: ExerciseSubCategory) {
        context.dataStore.edit { preferences ->
            preferences[POSTURE_FOCUS_AREA] = focusArea.name
        }
    }

    val stretchFocusAreaFlow: Flow<ExerciseSubCategory> = context.dataStore.data.map { preferences ->
        preferences[STRETCH_FOCUS_AREA].toExerciseSubCategory(stretchFocusAreas, ExerciseSubCategory.SPINE)
    }

    suspend fun setStretchFocusArea(focusArea: ExerciseSubCategory) {
        context.dataStore.edit { preferences ->
            preferences[STRETCH_FOCUS_AREA] = focusArea.name
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

    private fun String?.toExerciseSubCategory(
        allowed: List<ExerciseSubCategory>,
        defaultValue: ExerciseSubCategory
    ): ExerciseSubCategory {
        return ExerciseSubCategory.entries.firstOrNull { it.name == this && it in allowed } ?: defaultValue
    }
}
