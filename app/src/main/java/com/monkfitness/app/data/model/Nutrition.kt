package com.monkfitness.app.data.model

import androidx.annotation.StringRes
import com.monkfitness.app.R
import kotlin.math.absoluteValue
import kotlin.math.abs
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

enum class MealType(@StringRes val labelRes: Int) {
    HIGH_CARB(R.string.nutrition_profile_high_carb),
    HIGH_PROTEIN(R.string.nutrition_profile_high_protein),
    BALANCED(R.string.nutrition_profile_balanced)
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
    val templateId: String,
    val type: NutritionMealType,
    val mealType: MealType,
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
    val id: String,
    val type: NutritionMealType,
    val mealType: MealType,
    val ingredients: List<NutritionIngredientAmount>,
    val calories: Int,
    val proteinGrams: Int,
    val optional: Boolean = false
) {
    fun toMeal(scale: Float = 1f): NutritionMeal = NutritionMeal(
        templateId = id,
        type = type,
        mealType = mealType,
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
private val tuna = NutritionIngredient("tuna", R.string.nutrition_ingredient_tuna, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.GRAMS, NutritionPreparationStyle.PLAIN)

val nutritionExclusionIngredients: List<NutritionIngredient> = listOf(
    chicken,
    eggs,
    tuna,
    rice,
    oats,
    potatoes,
    buckwheat,
    cottageCheese,
    yogurt,
    banana,
    apple,
    nuts
)

private val nutritionMealTemplates = listOf(
    NutritionMealTemplate(
        id = "breakfast_high_carb_1",
        type = NutritionMealType.BREAKFAST,
        mealType = MealType.HIGH_CARB,
        ingredients = listOf(
            NutritionIngredientAmount(oats, 90),
            NutritionIngredientAmount(yogurt, 220),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 520,
        proteinGrams = 24
    ),
    NutritionMealTemplate(
        id = "breakfast_high_carb_2",
        type = NutritionMealType.BREAKFAST,
        mealType = MealType.HIGH_CARB,
        ingredients = listOf(
            NutritionIngredientAmount(rice, 200),
            NutritionIngredientAmount(eggs, 2),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(yogurt, 150)
        ),
        calories = 540,
        proteinGrams = 27
    ),
    NutritionMealTemplate(
        id = "breakfast_high_protein_1",
        type = NutritionMealType.BREAKFAST,
        mealType = MealType.HIGH_PROTEIN,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 250),
            NutritionIngredientAmount(yogurt, 180),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 430,
        proteinGrams = 36
    ),
    NutritionMealTemplate(
        id = "breakfast_high_protein_2",
        type = NutritionMealType.BREAKFAST,
        mealType = MealType.HIGH_PROTEIN,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(cottageCheese, 180),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 450,
        proteinGrams = 34
    ),
    NutritionMealTemplate(
        id = "breakfast_balanced_1",
        type = NutritionMealType.BREAKFAST,
        mealType = MealType.BALANCED,
        ingredients = listOf(
            NutritionIngredientAmount(oats, 70),
            NutritionIngredientAmount(cottageCheese, 200),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 500,
        proteinGrams = 30
    ),
    NutritionMealTemplate(
        id = "breakfast_balanced_2",
        type = NutritionMealType.BREAKFAST,
        mealType = MealType.BALANCED,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(buckwheat, 180),
            NutritionIngredientAmount(yogurt, 180)
        ),
        calories = 510,
        proteinGrams = 29
    ),
    NutritionMealTemplate(
        id = "lunch_high_carb_1",
        type = NutritionMealType.LUNCH,
        mealType = MealType.HIGH_CARB,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 150),
            NutritionIngredientAmount(rice, 260),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 690,
        proteinGrams = 50
    ),
    NutritionMealTemplate(
        id = "lunch_high_carb_2",
        type = NutritionMealType.LUNCH,
        mealType = MealType.HIGH_CARB,
        ingredients = listOf(
            NutritionIngredientAmount(tuna, 140),
            NutritionIngredientAmount(potatoes, 360),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 670,
        proteinGrams = 42
    ),
    NutritionMealTemplate(
        id = "lunch_high_protein_1",
        type = NutritionMealType.LUNCH,
        mealType = MealType.HIGH_PROTEIN,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 180),
            NutritionIngredientAmount(buckwheat, 220),
            NutritionIngredientAmount(yogurt, 150)
        ),
        calories = 650,
        proteinGrams = 55
    ),
    NutritionMealTemplate(
        id = "lunch_high_protein_2",
        type = NutritionMealType.LUNCH,
        mealType = MealType.HIGH_PROTEIN,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 160),
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(potatoes, 250)
        ),
        calories = 660,
        proteinGrams = 53
    ),
    NutritionMealTemplate(
        id = "lunch_balanced_1",
        type = NutritionMealType.LUNCH,
        mealType = MealType.BALANCED,
        ingredients = listOf(
            NutritionIngredientAmount(tuna, 150),
            NutritionIngredientAmount(rice, 210),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 640,
        proteinGrams = 44
    ),
    NutritionMealTemplate(
        id = "lunch_balanced_2",
        type = NutritionMealType.LUNCH,
        mealType = MealType.BALANCED,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 150),
            NutritionIngredientAmount(potatoes, 300),
            NutritionIngredientAmount(nuts, 15)
        ),
        calories = 660,
        proteinGrams = 48
    ),
    NutritionMealTemplate(
        id = "dinner_high_carb_1",
        type = NutritionMealType.DINNER,
        mealType = MealType.HIGH_CARB,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 140),
            NutritionIngredientAmount(rice, 240),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 680,
        proteinGrams = 48
    ),
    NutritionMealTemplate(
        id = "dinner_high_carb_2",
        type = NutritionMealType.DINNER,
        mealType = MealType.HIGH_CARB,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(buckwheat, 240),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 650,
        proteinGrams = 32
    ),
    NutritionMealTemplate(
        id = "dinner_high_protein_1",
        type = NutritionMealType.DINNER,
        mealType = MealType.HIGH_PROTEIN,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 170),
            NutritionIngredientAmount(cottageCheese, 180),
            NutritionIngredientAmount(potatoes, 260)
        ),
        calories = 660,
        proteinGrams = 58
    ),
    NutritionMealTemplate(
        id = "dinner_high_protein_2",
        type = NutritionMealType.DINNER,
        mealType = MealType.HIGH_PROTEIN,
        ingredients = listOf(
            NutritionIngredientAmount(tuna, 170),
            NutritionIngredientAmount(eggs, 2),
            NutritionIngredientAmount(potatoes, 250)
        ),
        calories = 630,
        proteinGrams = 52
    ),
    NutritionMealTemplate(
        id = "dinner_balanced_1",
        type = NutritionMealType.DINNER,
        mealType = MealType.BALANCED,
        ingredients = listOf(
            NutritionIngredientAmount(chicken, 150),
            NutritionIngredientAmount(buckwheat, 220),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 640,
        proteinGrams = 47
    ),
    NutritionMealTemplate(
        id = "dinner_balanced_2",
        type = NutritionMealType.DINNER,
        mealType = MealType.BALANCED,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 220),
            NutritionIngredientAmount(potatoes, 280),
            NutritionIngredientAmount(nuts, 20)
        ),
        calories = 620,
        proteinGrams = 34
    ),
    NutritionMealTemplate(
        id = "post_workout_high_carb_1",
        type = NutritionMealType.POST_WORKOUT,
        mealType = MealType.HIGH_CARB,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 200),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(oats, 40)
        ),
        calories = 320,
        proteinGrams = 16
    ),
    NutritionMealTemplate(
        id = "post_workout_high_carb_2",
        type = NutritionMealType.POST_WORKOUT,
        mealType = MealType.HIGH_CARB,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 180),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 300,
        proteinGrams = 12
    ),
    NutritionMealTemplate(
        id = "post_workout_high_protein_1",
        type = NutritionMealType.POST_WORKOUT,
        mealType = MealType.HIGH_PROTEIN,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 180),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 260,
        proteinGrams = 22
    ),
    NutritionMealTemplate(
        id = "post_workout_high_protein_2",
        type = NutritionMealType.POST_WORKOUT,
        mealType = MealType.HIGH_PROTEIN,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 180),
            NutritionIngredientAmount(cottageCheese, 120),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 280,
        proteinGrams = 24
    ),
    NutritionMealTemplate(
        id = "post_workout_balanced_1",
        type = NutritionMealType.POST_WORKOUT,
        mealType = MealType.BALANCED,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 180),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(nuts, 15)
        ),
        calories = 310,
        proteinGrams = 14
    ),
    NutritionMealTemplate(
        id = "post_workout_balanced_2",
        type = NutritionMealType.POST_WORKOUT,
        mealType = MealType.BALANCED,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 2),
            NutritionIngredientAmount(apple, 1),
            NutritionIngredientAmount(yogurt, 150)
        ),
        calories = 300,
        proteinGrams = 20
    ),
    NutritionMealTemplate(
        id = "snack_high_carb_1",
        type = NutritionMealType.SNACK,
        mealType = MealType.HIGH_CARB,
        ingredients = listOf(
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(yogurt, 150)
        ),
        calories = 220,
        proteinGrams = 10,
        optional = true
    ),
    NutritionMealTemplate(
        id = "snack_high_carb_2",
        type = NutritionMealType.SNACK,
        mealType = MealType.HIGH_CARB,
        ingredients = listOf(
            NutritionIngredientAmount(apple, 1),
            NutritionIngredientAmount(oats, 35),
            NutritionIngredientAmount(yogurt, 150)
        ),
        calories = 240,
        proteinGrams = 12,
        optional = true
    ),
    NutritionMealTemplate(
        id = "snack_high_protein_1",
        type = NutritionMealType.SNACK,
        mealType = MealType.HIGH_PROTEIN,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 150),
            NutritionIngredientAmount(apple, 1)
        ),
        calories = 230,
        proteinGrams = 20,
        optional = true
    ),
    NutritionMealTemplate(
        id = "snack_high_protein_2",
        type = NutritionMealType.SNACK,
        mealType = MealType.HIGH_PROTEIN,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 2),
            NutritionIngredientAmount(yogurt, 150)
        ),
        calories = 250,
        proteinGrams = 18,
        optional = true
    ),
    NutritionMealTemplate(
        id = "snack_balanced_1",
        type = NutritionMealType.SNACK,
        mealType = MealType.BALANCED,
        ingredients = listOf(
            NutritionIngredientAmount(yogurt, 180),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(nuts, 15)
        ),
        calories = 300,
        proteinGrams = 12,
        optional = true
    ),
    NutritionMealTemplate(
        id = "snack_balanced_2",
        type = NutritionMealType.SNACK,
        mealType = MealType.BALANCED,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 150),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(nuts, 15)
        ),
        calories = 320,
        proteinGrams = 19,
        optional = true
    )
)

private val mealsPool: Map<MealType, List<NutritionMealTemplate>> =
    nutritionMealTemplates.groupBy { it.mealType }

fun generateNutritionPlan(
    seed: Int,
    startDay: Int = 1,
    daysCount: Int = 3,
    weightKg: Int?,
    heightCm: Int? = null,
    excludedIngredientKeys: Set<String> = emptySet(),
    replacements: Map<String, String> = emptyMap(),
    workoutTypeForDay: (Int) -> WorkoutType = ::defaultWorkoutTypeForDay
): NutritionPlan {
    val base = seed.absoluteValue
    val safeStartDay = startDay.coerceIn(1, 56)
    val safeDaysCount = daysCount.coerceIn(1, 7)

    val days = (0 until safeDaysCount).map { index ->
        val programDay = (safeStartDay + index).coerceAtMost(56)
        val week = ((programDay - 1) / 7) + 1
        val dayType = resolveNutritionDayType(workoutTypeForDay(programDay))
        val targetCalories = calculateTargetCalories(weightKg = weightKg, heightCm = heightCm, dayType = dayType)
        val slots = slotsForDayType(dayType)
        val rotation = base + index + (programDay * 3)

        val meals = slots.mapIndexed { slotIndex, slot ->
            val mealType = MealType.entries[(rotation + slotIndex) % MealType.entries.size]
            val replacementKey = nutritionReplacementKey(programDay, slot)
            val replacementTemplateId = replacements[replacementKey]
            selectTemplate(
                mealType = slot,
                replacementType = mealType,
                rotation = rotation + slotIndex,
                excludedIngredientKeys = excludedIngredientKeys,
                explicitTemplateId = replacementTemplateId
            )
        }

        val baseCalories = meals.sumOf { it.calories }.coerceAtLeast(1)
        val scale = (targetCalories.toFloat() / baseCalories.toFloat()).coerceIn(0.85f, 1.35f)

        NutritionDayPlan(
            dayNumber = index + 1,
            programDay = programDay,
            week = week,
            dayType = dayType,
            targetCalories = targetCalories,
            meals = meals.map { it.toMeal(scale) }
        )
    }

    return NutritionPlan(days)
}

fun generateThreeDayMuscleGainPlan(
    seed: Int,
    startDay: Int = 1,
    workoutTypeForDay: (Int) -> WorkoutType = ::defaultWorkoutTypeForDay
): NutritionPlan = generateNutritionPlan(
    seed = seed,
    startDay = startDay,
    daysCount = 3,
    weightKg = null,
    heightCm = null,
    workoutTypeForDay = workoutTypeForDay
)

fun calculateMuscleGainNutritionTargets(
    weightKg: Int?,
    heightCm: Int?,
    dayType: NutritionDayType = NutritionDayType.TRAINING
): NutritionTargets {
    val normalizedWeight = normalizeNutritionWeight(weightKg, heightCm)
    val dailyCalories = calculateTargetCalories(weightKg = weightKg, heightCm = heightCm, dayType = dayType)
    val proteinTarget = (normalizedWeight * 2f).roundToInt().coerceIn(110, 190)

    return NutritionTargets(
        dailyCalories = dailyCalories,
        proteinMinGrams = (proteinTarget - 10).coerceAtLeast(100),
        proteinMaxGrams = proteinTarget + 10
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
        "tuna" in ingredientKeys -> R.string.nutrition_step_prepare_tuna
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

fun replaceMeal(
    meal: NutritionMeal,
    excludedIngredientKeys: Set<String>
): NutritionMeal? {
    val currentTemplate = nutritionMealTemplates.firstOrNull { it.id == meal.templateId } ?: return null
    val calorieScale = meal.calories.toFloat() / currentTemplate.calories.toFloat()

    return mealsPool[meal.mealType]
        .orEmpty()
        .asSequence()
        .filter { candidate ->
            candidate.type == meal.type &&
                candidate.id != meal.templateId &&
                candidate.ingredients.none { it.ingredient.key in excludedIngredientKeys }
        }
        .map { candidate ->
            candidate to abs((candidate.calories * calorieScale).roundToInt() - meal.calories)
        }
        .filter { (_, calorieDifference) -> calorieDifference <= 100 }
        .minByOrNull { (_, calorieDifference) -> calorieDifference }
        ?.first
        ?.toMeal(calorieScale)
}

fun findReplacementMealTemplateId(
    meal: NutritionMeal,
    excludedIngredientKeys: Set<String>
): String? {
    return replaceMeal(meal, excludedIngredientKeys)?.templateId
}

fun nutritionReplacementKey(programDay: Int, mealType: NutritionMealType): String {
    return "$programDay:${mealType.key}"
}

private fun slotsForDayType(dayType: NutritionDayType): List<NutritionMealType> = when (dayType) {
    NutritionDayType.TRAINING -> listOf(
        NutritionMealType.BREAKFAST,
        NutritionMealType.LUNCH,
        NutritionMealType.DINNER,
        NutritionMealType.POST_WORKOUT
    )
    NutritionDayType.LIGHT,
    NutritionDayType.REST -> listOf(
        NutritionMealType.BREAKFAST,
        NutritionMealType.LUNCH,
        NutritionMealType.DINNER,
        NutritionMealType.SNACK
    )
}

private fun selectTemplate(
    mealType: NutritionMealType,
    replacementType: MealType,
    rotation: Int,
    excludedIngredientKeys: Set<String>,
    explicitTemplateId: String?
): NutritionMealTemplate {
    val filtered = templatesFor(mealType, replacementType, excludedIngredientKeys)
    val explicit = explicitTemplateId?.let { id -> filtered.firstOrNull { it.id == id } }
    if (explicit != null) return explicit

    if (filtered.isNotEmpty()) {
        return filtered[rotation.absoluteValue % filtered.size]
    }

    val fallbackSameType = templatesFor(mealType, replacementType, emptySet())
    if (fallbackSameType.isNotEmpty()) {
        return fallbackSameType[rotation.absoluteValue % fallbackSameType.size]
    }

    return nutritionMealTemplates.first { it.type == mealType }
}

private fun templatesFor(
    mealType: NutritionMealType,
    replacementType: MealType,
    excludedIngredientKeys: Set<String>
): List<NutritionMealTemplate> {
    return mealsPool[replacementType].orEmpty().filter { template ->
        template.type == mealType &&
            template.ingredients.none { it.ingredient.key in excludedIngredientKeys }
    }
}

private fun resolveNutritionDayType(workoutType: WorkoutType): NutritionDayType = when (workoutType) {
    WorkoutType.STRENGTH_A,
    WorkoutType.STRENGTH_B,
    WorkoutType.FUNCTIONAL -> NutritionDayType.TRAINING

    WorkoutType.MOBILITY,
    WorkoutType.POSTURE_MOBILITY -> NutritionDayType.LIGHT

    WorkoutType.REST -> NutritionDayType.REST
}

private fun calculateTargetCalories(
    weightKg: Int?,
    heightCm: Int?,
    dayType: NutritionDayType
): Int {
    val normalizedWeight = normalizeNutritionWeight(weightKg, heightCm)
    val baseCalories = (normalizedWeight * 35) + 300
    val adjustment = when (dayType) {
        NutritionDayType.TRAINING -> 200
        NutritionDayType.LIGHT -> 0
        NutritionDayType.REST -> -100
    }
    return (baseCalories + adjustment).coerceAtLeast(1800)
}

private fun normalizeNutritionWeight(weightKg: Int?, heightCm: Int?): Int {
    val safeWeight = weightKg?.takeIf { it in 35..220 }
    if (safeWeight != null) return safeWeight

    val safeHeight = heightCm?.takeIf { it in 120..230 }
    if (safeHeight != null) {
        return (safeHeight - 100).coerceIn(50, 120)
    }

    return 70
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
