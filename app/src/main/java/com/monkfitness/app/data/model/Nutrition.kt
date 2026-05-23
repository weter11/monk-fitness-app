package com.monkfitness.app.data.model

import androidx.annotation.StringRes
import com.monkfitness.app.R
import kotlin.math.roundToInt

enum class NutritionMealType(
    val key: String,
    @StringRes val labelRes: Int
) {
    BREAKFAST("breakfast", R.string.nutrition_breakfast),
    LUNCH("lunch", R.string.nutrition_lunch),
    DINNER("dinner", R.string.nutrition_dinner),
    POST_WORKOUT("post_workout", R.string.nutrition_post_workout)
}

data class NutritionMealPlan(
    val type: NutritionMealType,
    @StringRes val options: List<Int>
)

data class NutritionTargets(
    val dailyCalories: Int,
    val proteinMinGrams: Int,
    val proteinMaxGrams: Int
)

fun muscleGainMealPlan(): List<NutritionMealPlan> = listOf(
    NutritionMealPlan(
        type = NutritionMealType.BREAKFAST,
        options = listOf(
            R.string.nutrition_breakfast_option_1,
            R.string.nutrition_breakfast_option_2,
            R.string.nutrition_breakfast_option_3
        )
    ),
    NutritionMealPlan(
        type = NutritionMealType.LUNCH,
        options = listOf(
            R.string.nutrition_lunch_option_1,
            R.string.nutrition_lunch_option_2,
            R.string.nutrition_lunch_option_3
        )
    ),
    NutritionMealPlan(
        type = NutritionMealType.DINNER,
        options = listOf(
            R.string.nutrition_dinner_option_1,
            R.string.nutrition_dinner_option_2,
            R.string.nutrition_dinner_option_3
        )
    ),
    NutritionMealPlan(
        type = NutritionMealType.POST_WORKOUT,
        options = listOf(
            R.string.nutrition_post_workout_option_1,
            R.string.nutrition_post_workout_option_2,
            R.string.nutrition_post_workout_option_3
        )
    )
)

fun calculateMuscleGainNutritionTargets(
    weightKg: Int?,
    heightCm: Int?
): NutritionTargets {
    val safeWeight = weightKg?.takeIf { it in 35..220 }
    val safeHeight = heightCm?.takeIf { it in 120..230 }

    val calculatedCalories = when {
        safeWeight != null && safeHeight != null -> (safeWeight * 22) + (safeHeight * 5) + 100
        safeWeight != null -> (safeWeight * 30) + 400
        safeHeight != null -> (safeHeight * 12) + 500
        else -> 2500
    }.coerceIn(2400, 2800)

    val proteinCenter = safeWeight?.let { (it * 1.8f).roundToInt() } ?: 110
    val proteinMin = (proteinCenter - 10).coerceIn(100, 110)
    val proteinMax = (proteinMin + 20).coerceAtMost(120)

    return NutritionTargets(
        dailyCalories = calculatedCalories,
        proteinMinGrams = proteinMin,
        proteinMaxGrams = proteinMax
    )
}
