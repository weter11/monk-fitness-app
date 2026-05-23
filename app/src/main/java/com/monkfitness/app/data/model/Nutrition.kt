package com.monkfitness.app.data.model

import androidx.annotation.StringRes
import com.monkfitness.app.R
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

enum class NutritionMealType(
    val key: String,
    @StringRes val labelRes: Int
) {
    BREAKFAST("breakfast", R.string.nutrition_breakfast),
    LUNCH("lunch", R.string.nutrition_lunch),
    DINNER("dinner", R.string.nutrition_dinner),
    POST_WORKOUT("post_workout", R.string.nutrition_post_workout),
    SNACK("snack", R.string.nutrition_snack)
}

enum class NutritionDayType(@StringRes val labelRes: Int) {
    TRAINING(R.string.nutrition_training_day),
    LIGHT(R.string.nutrition_light_day),
    REST(R.string.nutrition_rest_day)
}

enum class NutritionShoppingGroup(@StringRes val titleRes: Int) {
    PROTEIN(R.string.nutrition_group_protein),
    CARBS(R.string.nutrition_group_carbs),
    FATS(R.string.nutrition_group_fats),
    FRUITS_VEGETABLES(R.string.nutrition_group_fruits_vegetables)
}

enum class NutritionQuantityUnit(@StringRes val labelRes: Int) {
    GRAMS(R.string.nutrition_quantity_grams),
    MILLILITERS(R.string.nutrition_quantity_milliliters),
    PIECES(R.string.nutrition_quantity_pieces)
}

enum class NutritionPreparationStyle(@StringRes val labelRes: Int) {
    COOKED(R.string.nutrition_preparation_cooked),
    GRILLED(R.string.nutrition_preparation_grilled),
    BOILED(R.string.nutrition_preparation_boiled),
    FRESH(R.string.nutrition_preparation_fresh),
    PLAIN(R.string.nutrition_preparation_plain)
}

data class NutritionTargets(
    val dailyCalories: Int,
    val proteinMinGrams: Int,
    val proteinMaxGrams: Int
)

data class NutritionIngredient(
    val key: String,
    @StringRes val nameRes: Int,
    val group: NutritionShoppingGroup,
    val unit: NutritionQuantityUnit,
    val preparationStyle: NutritionPreparationStyle
)

data class NutritionIngredientAmount(
    val ingredient: NutritionIngredient,
    val amount: Int
)

data class NutritionMeal(
    val type: NutritionMealType,
    val ingredients: List<NutritionIngredientAmount>,
    val calories: Int,
    val proteinGrams: Int,
    val optional: Boolean = false
)

data class NutritionDayPlan(
    val dayNumber: Int,
    val programDay: Int,
    val week: Int,
    val dayType: NutritionDayType,
    val targetCalories: Int,
    val meals: List<NutritionMeal>
) {
    val totalCalories: Int = meals.sumOf { it.calories }
    val totalProteinGrams: Int = meals.sumOf { it.proteinGrams }
}

data class NutritionShoppingListItem(
    val ingredient: NutritionIngredient,
    val totalAmount: Int
)

data class NutritionPlan(
    val days: List<NutritionDayPlan>
) {
    val shoppingList: Map<NutritionShoppingGroup, List<NutritionShoppingListItem>>
        get() = days
            .flatMap { day -> day.meals }
            .flatMap { meal -> meal.ingredients }
            .groupBy { it.ingredient.key }
            .values
            .map { items ->
                NutritionShoppingListItem(
                    ingredient = items.first().ingredient,
                    totalAmount = items.sumOf { it.amount }
                )
            }
            .groupBy { it.ingredient.group }
            .mapValues { (_, items) -> items.sortedBy { it.ingredient.key } }
}

private data class NutritionMealTemplate(
    val type: NutritionMealType,
    val ingredients: List<NutritionIngredientAmount>,
    val calories: Int,
    val proteinGrams: Int,
    val optional: Boolean = false
) {
    fun toMeal(scale: Float = 1f): NutritionMeal = NutritionMeal(
        type = type,
        ingredients = ingredients.map { ingredientAmount ->
            ingredientAmount.copy(amount = scaleAmount(ingredientAmount.amount, ingredientAmount.ingredient.unit, scale))
        },
        calories = (calories * scale).roundToInt(),
        proteinGrams = (proteinGrams * scale).roundToInt(),
        optional = optional
    )
}

private val chicken = NutritionIngredient("chicken", R.string.nutrition_ingredient_chicken_breast, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.GRAMS, NutritionPreparationStyle.GRILLED)
private val eggs = NutritionIngredient("eggs", R.string.nutrition_ingredient_eggs, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.PIECES, NutritionPreparationStyle.BOILED)
private val rice = NutritionIngredient("rice", R.string.nutrition_ingredient_rice, NutritionShoppingGroup.CARBS, NutritionQuantityUnit.GRAMS, NutritionPreparationStyle.COOKED)
private val oats = NutritionIngredient("oats", R.string.nutrition_ingredient_oats, NutritionShoppingGroup.CARBS, NutritionQuantityUnit.GRAMS, NutritionPreparationStyle.COOKED)
private val potatoes = NutritionIngredient("potatoes", R.string.nutrition_ingredient_potatoes, NutritionShoppingGroup.CARBS, NutritionQuantityUnit.GRAMS, NutritionPreparationStyle.BOILED)
private val buckwheat = NutritionIngredient("buckwheat", R.string.nutrition_ingredient_buckwheat, NutritionShoppingGroup.CARBS, NutritionQuantityUnit.GRAMS, NutritionPreparationStyle.COOKED)
private val cottageCheese = NutritionIngredient("cottage_cheese", R.string.nutrition_ingredient_cottage_cheese, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.GRAMS, NutritionPreparationStyle.PLAIN)
private val yogurt = NutritionIngredient("yogurt", R.string.nutrition_ingredient_yogurt, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.GRAMS, NutritionPreparationStyle.PLAIN)
private val banana = NutritionIngredient("banana", R.string.nutrition_ingredient_banana, NutritionShoppingGroup.FRUITS_VEGETABLES, NutritionQuantityUnit.PIECES, NutritionPreparationStyle.FRESH)
private val apple = NutritionIngredient("apple", R.string.nutrition_ingredient_apple, NutritionShoppingGroup.FRUITS_VEGETABLES, NutritionQuantityUnit.PIECES, NutritionPreparationStyle.FRESH)
private val nuts = NutritionIngredient("nuts", R.string.nutrition_ingredient_nuts, NutritionShoppingGroup.FATS, NutritionQuantityUnit.GRAMS, NutritionPreparationStyle.PLAIN)

private val trainingBreakfastTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(oats, 90),
            NutritionIngredientAmount(yogurt, 220),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 570,
        proteinGrams = 23
    ),
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(oats, 80),
            NutritionIngredientAmount(cottageCheese, 200),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 555,
        proteinGrams = 31
    ),
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(rice, 180),
            NutritionIngredientAmount(apple, 1),
            NutritionIngredientAmount(yogurt, 150)
        ),
        calories = 600,
        proteinGrams = 29
    )
)

private val trainingLunchTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 160),
            NutritionIngredientAmount(rice, 250),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 760,
        proteinGrams = 54
    ),
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 150),
            NutritionIngredientAmount(buckwheat, 280),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 735,
        proteinGrams = 50
    ),
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 4),
            NutritionIngredientAmount(potatoes, 380),
            NutritionIngredientAmount(yogurt, 180)
        ),
        calories = 780,
        proteinGrams = 37
    )
)

private val trainingDinnerTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 150),
            NutritionIngredientAmount(potatoes, 400),
            NutritionIngredientAmount(cottageCheese, 150)
        ),
        calories = 810,
        proteinGrams = 58
    ),
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 140),
            NutritionIngredientAmount(rice, 240),
            NutritionIngredientAmount(nuts, 20),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 790,
        proteinGrams = 49
    ),
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 4),
            NutritionIngredientAmount(buckwheat, 250),
            NutritionIngredientAmount(cottageCheese, 200)
        ),
        calories = 800,
        proteinGrams = 44
    )
)

private val trainingRecoveryTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.POST_WORKOUT,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 220),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(oats, 40)
        ),
        calories = 330,
        proteinGrams = 15
    ),
    NutritionMealTemplate(
        type = NutritionMealType.POST_WORKOUT,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 150),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 320,
        proteinGrams = 20
    ),
    NutritionMealTemplate(
        type = NutritionMealType.POST_WORKOUT,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 200),
            NutritionIngredientAmount(banana, 2)
        ),
        calories = 310,
        proteinGrams = 12
    )
)

private val lightBreakfastTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(oats, 80),
            NutritionIngredientAmount(yogurt, 200),
            NutritionIngredientAmount(apple, 1),
            NutritionIngredientAmount(nuts, 20)
        ),
        calories = 560,
        proteinGrams = 20
    ),
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(buckwheat, 180),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 540,
        proteinGrams = 24
    ),
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 200),
            NutritionIngredientAmount(oats, 70),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 550,
        proteinGrams = 28
    )
)

private val lightLunchTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 140),
            NutritionIngredientAmount(buckwheat, 240),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 700,
        proteinGrams = 48
    ),
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(rice, 220),
            NutritionIngredientAmount(yogurt, 150)
        ),
        calories = 690,
        proteinGrams = 28
    ),
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 130),
            NutritionIngredientAmount(potatoes, 350),
            NutritionIngredientAmount(nuts, 15)
        ),
        calories = 710,
        proteinGrams = 44
    )
)

private val lightDinnerTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 250),
            NutritionIngredientAmount(potatoes, 300),
            NutritionIngredientAmount(nuts, 20)
        ),
        calories = 700,
        proteinGrams = 33
    ),
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 130),
            NutritionIngredientAmount(rice, 200),
            NutritionIngredientAmount(apple, 1),
            NutritionIngredientAmount(nuts, 15)
        ),
        calories = 720,
        proteinGrams = 45
    ),
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(buckwheat, 220),
            NutritionIngredientAmount(yogurt, 200)
        ),
        calories = 700,
        proteinGrams = 30
    )
)

private val lightSnackTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.SNACK,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 200),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(nuts, 20)
        ),
        calories = 300,
        proteinGrams = 12,
        optional = true
    ),
    NutritionMealTemplate(
        type = NutritionMealType.SNACK,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 150),
            NutritionIngredientAmount(apple, 1),
            NutritionIngredientAmount(nuts, 15)
        ),
        calories = 290,
        proteinGrams = 19,
        optional = true
    ),
    NutritionMealTemplate(
        type = NutritionMealType.SNACK,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 180),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 280,
        proteinGrams = 10,
        optional = true
    )
)

private val restBreakfastTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 4),
            NutritionIngredientAmount(cottageCheese, 200),
            NutritionIngredientAmount(apple, 1),
            NutritionIngredientAmount(nuts, 30)
        ),
        calories = 640,
        proteinGrams = 38
    ),
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 250),
            NutritionIngredientAmount(nuts, 35),
            NutritionIngredientAmount(apple, 1),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 590,
        proteinGrams = 16
    ),
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 250),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(nuts, 25)
        ),
        calories = 600,
        proteinGrams = 31
    )
)

private val restLunchTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 150),
            NutritionIngredientAmount(potatoes, 250),
            NutritionIngredientAmount(nuts, 25)
        ),
        calories = 700,
        proteinGrams = 52
    ),
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 4),
            NutritionIngredientAmount(buckwheat, 180),
            NutritionIngredientAmount(nuts, 25)
        ),
        calories = 720,
        proteinGrams = 30
    ),
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 140),
            NutritionIngredientAmount(rice, 180),
            NutritionIngredientAmount(nuts, 30),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 760,
        proteinGrams = 49
    )
)

private val restDinnerTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 250),
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(apple, 1),
            NutritionIngredientAmount(nuts, 25)
        ),
        calories = 670,
        proteinGrams = 46
    ),
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 140),
            NutritionIngredientAmount(potatoes, 280),
            NutritionIngredientAmount(nuts, 30)
        ),
        calories = 710,
        proteinGrams = 48
    ),
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 220),
            NutritionIngredientAmount(yogurt, 200),
            NutritionIngredientAmount(nuts, 30)
        ),
        calories = 650,
        proteinGrams = 36
    )
)

private val restSnackTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.SNACK,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 200),
            NutritionIngredientAmount(nuts, 25),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 365,
        proteinGrams = 13,
        optional = true
    ),
    NutritionMealTemplate(
        type = NutritionMealType.SNACK,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 150),
            NutritionIngredientAmount(nuts, 20),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 355,
        proteinGrams = 20,
        optional = true
    ),
    NutritionMealTemplate(
        type = NutritionMealType.SNACK,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 2),
            NutritionIngredientAmount(apple, 1),
            NutritionIngredientAmount(nuts, 20)
        ),
        calories = 330,
        proteinGrams = 16,
        optional = true
    )
)

fun generateThreeDayMuscleGainPlan(
    seed: Int,
    startDay: Int = 1,
    workoutTypeForDay: (Int) -> WorkoutType = ::defaultWorkoutTypeForDay
): NutritionPlan {
    val base = seed.absoluteValue
    val safeStartDay = startDay.coerceIn(1, 56)

    val days = (0 until 3).map { index ->
        val programDay = (safeStartDay + index).coerceAtMost(56)
        val week = ((programDay - 1) / 7) + 1
        val dayType = resolveNutritionDayType(workoutTypeForDay(programDay))
        val targetCalories = calculateTargetCalories(programDay)
        val rotation = base + index + (programDay * 3)

        NutritionDayPlan(
            dayNumber = index + 1,
            programDay = programDay,
            week = week,
            dayType = dayType,
            targetCalories = targetCalories,
            meals = buildMealsForDay(dayType = dayType, rotation = rotation, targetCalories = targetCalories)
        )
    }

    return NutritionPlan(days)
}

fun calculateMuscleGainNutritionTargets(
    weightKg: Int?,
    heightCm: Int?,
    programDay: Int = 1,
    dayType: NutritionDayType = NutritionDayType.TRAINING
): NutritionTargets {
    val safeWeight = weightKg?.takeIf { it in 35..220 }
    val safeHeight = heightCm?.takeIf { it in 120..230 }
    val calorieTarget = calculateTargetCalories(programDay)

    val baselineProtein = when {
        safeWeight != null -> (safeWeight * 1.7f).roundToInt()
        safeHeight != null -> (safeHeight * 0.7f).roundToInt()
        else -> when (dayType) {
            NutritionDayType.TRAINING -> 145
            NutritionDayType.LIGHT -> 130
            NutritionDayType.REST -> 120
        }
    }

    val proteinMin = when (dayType) {
        NutritionDayType.TRAINING -> (baselineProtein - 10).coerceIn(120, 170)
        NutritionDayType.LIGHT -> (baselineProtein - 10).coerceIn(110, 160)
        NutritionDayType.REST -> (baselineProtein - 10).coerceIn(100, 150)
    }
    val proteinMax = (proteinMin + 25).coerceAtMost(185)

    return NutritionTargets(
        dailyCalories = calorieTarget,
        proteinMinGrams = proteinMin,
        proteinMaxGrams = proteinMax
    )
}

fun calculateNutritionCompletionPercent(
    dayPlan: NutritionDayPlan,
    completedMealKeys: Set<String>
): Int {
    if (dayPlan.meals.isEmpty()) return 0
    val completedCount = dayPlan.meals.count { it.type.key in completedMealKeys }
    return ((completedCount * 100f) / dayPlan.meals.size).roundToInt()
}

fun getTodayCookingInstructionResIds(meal: NutritionMeal): List<Int> {
    val ingredientKeys = meal.ingredients.map { it.ingredient.key }.toSet()
    val steps = mutableListOf<Int>()

    val carbStep = when {
        "oats" in ingredientKeys -> R.string.nutrition_step_cook_oats
        "rice" in ingredientKeys -> R.string.nutrition_step_cook_rice
        "buckwheat" in ingredientKeys -> R.string.nutrition_step_cook_buckwheat
        "potatoes" in ingredientKeys -> R.string.nutrition_step_boil_potatoes
        else -> null
    }
    val proteinStep = when {
        "chicken" in ingredientKeys -> R.string.nutrition_step_grill_chicken
        "eggs" in ingredientKeys -> R.string.nutrition_step_boil_eggs
        else -> null
    }

    if (carbStep != null) {
        steps += carbStep
    }
    if (proteinStep != null) {
        steps += proteinStep
    }
    if (steps.isEmpty() && ingredientKeys.any { it == "yogurt" || it == "cottage_cheese" }) {
        steps += R.string.nutrition_step_prepare_dairy_base
    }

    steps += when {
        ingredientKeys.any { it == "yogurt" || it == "cottage_cheese" || it == "banana" || it == "apple" || it == "nuts" } ->
            R.string.nutrition_step_finish_with_toppings
        else -> R.string.nutrition_step_finish_simple
    }

    return steps.distinct().take(3)
}

private fun buildMealsForDay(
    dayType: NutritionDayType,
    rotation: Int,
    targetCalories: Int
): List<NutritionMeal> {
    val templates = when (dayType) {
        NutritionDayType.TRAINING -> listOf(
            trainingBreakfastTemplates[rotation % trainingBreakfastTemplates.size],
            trainingLunchTemplates[(rotation + 1) % trainingLunchTemplates.size],
            trainingDinnerTemplates[(rotation + 2) % trainingDinnerTemplates.size],
            trainingRecoveryTemplates[(rotation + 3) % trainingRecoveryTemplates.size]
        )
        NutritionDayType.LIGHT -> listOf(
            lightBreakfastTemplates[rotation % lightBreakfastTemplates.size],
            lightLunchTemplates[(rotation + 1) % lightLunchTemplates.size],
            lightDinnerTemplates[(rotation + 2) % lightDinnerTemplates.size],
            lightSnackTemplates[(rotation + 3) % lightSnackTemplates.size]
        )
        NutritionDayType.REST -> listOf(
            restBreakfastTemplates[rotation % restBreakfastTemplates.size],
            restLunchTemplates[(rotation + 1) % restLunchTemplates.size],
            restDinnerTemplates[(rotation + 2) % restDinnerTemplates.size],
            restSnackTemplates[(rotation + 3) % restSnackTemplates.size]
        )
    }

    val baseCalories = templates.sumOf { it.calories }.coerceAtLeast(1)
    val scale = (targetCalories.toFloat() / baseCalories.toFloat()).coerceIn(0.9f, 1.35f)

    return templates.map { it.toMeal(scale) }
}

private fun resolveNutritionDayType(workoutType: WorkoutType): NutritionDayType = when (workoutType) {
    WorkoutType.STRENGTH_A,
    WorkoutType.STRENGTH_B,
    WorkoutType.FUNCTIONAL -> NutritionDayType.TRAINING

    WorkoutType.MOBILITY,
    WorkoutType.POSTURE_MOBILITY -> NutritionDayType.LIGHT

    WorkoutType.REST -> NutritionDayType.REST
}

private fun calculateTargetCalories(programDay: Int): Int {
    val week = ((programDay.coerceIn(1, 56) - 1) / 7) + 1
    val multiplier = when (week) {
        1, 2 -> 1.0f
        3, 4 -> 1.1f
        5, 6 -> 1.2f
        else -> 1.3f
    }
    return (2500 * multiplier).roundToInt()
}

private fun defaultWorkoutTypeForDay(day: Int): WorkoutType = when ((day.coerceIn(1, 56) - 1) % 7) {
    0 -> WorkoutType.STRENGTH_A
    1 -> WorkoutType.MOBILITY
    2 -> WorkoutType.STRENGTH_B
    3 -> WorkoutType.REST
    4 -> WorkoutType.FUNCTIONAL
    5 -> WorkoutType.MOBILITY
    else -> WorkoutType.REST
}

private fun scaleAmount(amount: Int, unit: NutritionQuantityUnit, scale: Float): Int {
    val scaled = amount * scale
    return when (unit) {
        NutritionQuantityUnit.PIECES -> scaled.roundToInt().coerceAtLeast(1)
        NutritionQuantityUnit.GRAMS,
        NutritionQuantityUnit.MILLILITERS -> {
            val rounded = (scaled / 5f).roundToInt() * 5
            rounded.coerceAtLeast(5)
        }
    }
}
