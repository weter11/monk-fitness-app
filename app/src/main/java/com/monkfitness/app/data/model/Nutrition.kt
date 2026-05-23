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
    POST_WORKOUT("post_workout", R.string.nutrition_post_workout)
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

data class NutritionTargets(
    val dailyCalories: Int,
    val proteinMinGrams: Int,
    val proteinMaxGrams: Int
)

data class NutritionIngredient(
    val key: String,
    @StringRes val nameRes: Int,
    val group: NutritionShoppingGroup,
    val unit: NutritionQuantityUnit
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
    fun toMeal(): NutritionMeal = NutritionMeal(
        type = type,
        ingredients = ingredients,
        calories = calories,
        proteinGrams = proteinGrams,
        optional = optional
    )
}

private val oats = NutritionIngredient("oats", R.string.nutrition_ingredient_oats, NutritionShoppingGroup.CARBS, NutritionQuantityUnit.GRAMS)
private val milk = NutritionIngredient("milk", R.string.nutrition_ingredient_milk, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.MILLILITERS)
private val banana = NutritionIngredient("banana", R.string.nutrition_ingredient_banana, NutritionShoppingGroup.FRUITS_VEGETABLES, NutritionQuantityUnit.PIECES)
private val peanutButter = NutritionIngredient("peanut_butter", R.string.nutrition_ingredient_peanut_butter, NutritionShoppingGroup.FATS, NutritionQuantityUnit.GRAMS)
private val wheyProtein = NutritionIngredient("whey_protein", R.string.nutrition_ingredient_whey_protein, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.GRAMS)
private val greekYogurt = NutritionIngredient("greek_yogurt", R.string.nutrition_ingredient_greek_yogurt, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.GRAMS)
private val eggs = NutritionIngredient("eggs", R.string.nutrition_ingredient_eggs, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.PIECES)
private val wholeGrainBread = NutritionIngredient("whole_grain_bread", R.string.nutrition_ingredient_whole_grain_bread, NutritionShoppingGroup.CARBS, NutritionQuantityUnit.PIECES)
private val chickenBreast = NutritionIngredient("chicken_breast", R.string.nutrition_ingredient_chicken_breast, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.GRAMS)
private val rice = NutritionIngredient("rice", R.string.nutrition_ingredient_rice, NutritionShoppingGroup.CARBS, NutritionQuantityUnit.GRAMS)
private val oliveOil = NutritionIngredient("olive_oil", R.string.nutrition_ingredient_olive_oil, NutritionShoppingGroup.FATS, NutritionQuantityUnit.MILLILITERS)
private val tomatoes = NutritionIngredient("tomatoes", R.string.nutrition_ingredient_tomatoes, NutritionShoppingGroup.FRUITS_VEGETABLES, NutritionQuantityUnit.GRAMS)
private val cucumber = NutritionIngredient("cucumber", R.string.nutrition_ingredient_cucumber, NutritionShoppingGroup.FRUITS_VEGETABLES, NutritionQuantityUnit.GRAMS)
private val avocado = NutritionIngredient("avocado", R.string.nutrition_ingredient_avocado, NutritionShoppingGroup.FATS, NutritionQuantityUnit.PIECES)
private val carrots = NutritionIngredient("carrots", R.string.nutrition_ingredient_carrots, NutritionShoppingGroup.FRUITS_VEGETABLES, NutritionQuantityUnit.GRAMS)
private val tuna = NutritionIngredient("tuna", R.string.nutrition_ingredient_tuna, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.GRAMS)
private val potatoes = NutritionIngredient("potatoes", R.string.nutrition_ingredient_potatoes, NutritionShoppingGroup.CARBS, NutritionQuantityUnit.GRAMS)
private val spinach = NutritionIngredient("spinach", R.string.nutrition_ingredient_spinach, NutritionShoppingGroup.FRUITS_VEGETABLES, NutritionQuantityUnit.GRAMS)
private val cottageCheese = NutritionIngredient("cottage_cheese", R.string.nutrition_ingredient_cottage_cheese, NutritionShoppingGroup.PROTEIN, NutritionQuantityUnit.GRAMS)

private val breakfastTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(oats, 90),
            NutritionIngredientAmount(milk, 300),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(peanutButter, 20),
            NutritionIngredientAmount(wheyProtein, 25)
        ),
        calories = 650,
        proteinGrams = 29
    ),
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(oats, 80),
            NutritionIngredientAmount(greekYogurt, 200),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(peanutButter, 20),
            NutritionIngredientAmount(wholeGrainBread, 2)
        ),
        calories = 620,
        proteinGrams = 30
    ),
    NutritionMealTemplate(
        type = NutritionMealType.BREAKFAST,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(wholeGrainBread, 4),
            NutritionIngredientAmount(greekYogurt, 200),
            NutritionIngredientAmount(banana, 1)
        ),
        calories = 610,
        proteinGrams = 32
    )
)

private val lunchTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(chickenBreast, 150),
            NutritionIngredientAmount(rice, 250),
            NutritionIngredientAmount(oliveOil, 15),
            NutritionIngredientAmount(tomatoes, 150),
            NutritionIngredientAmount(cucumber, 150)
        ),
        calories = 760,
        proteinGrams = 36
    ),
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(chickenBreast, 130),
            NutritionIngredientAmount(rice, 220),
            NutritionIngredientAmount(avocado, 1),
            NutritionIngredientAmount(carrots, 150),
            NutritionIngredientAmount(oliveOil, 10)
        ),
        calories = 790,
        proteinGrams = 34
    ),
    NutritionMealTemplate(
        type = NutritionMealType.LUNCH,
        ingredients = listOf(
            NutritionIngredientAmount(tuna, 120),
            NutritionIngredientAmount(potatoes, 350),
            NutritionIngredientAmount(oliveOil, 10),
            NutritionIngredientAmount(tomatoes, 150),
            NutritionIngredientAmount(cucumber, 150)
        ),
        calories = 740,
        proteinGrams = 32
    )
)

private val dinnerTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(eggs, 3),
            NutritionIngredientAmount(rice, 220),
            NutritionIngredientAmount(avocado, 1),
            NutritionIngredientAmount(spinach, 100),
            NutritionIngredientAmount(wholeGrainBread, 2)
        ),
        calories = 780,
        proteinGrams = 28
    ),
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(cottageCheese, 250),
            NutritionIngredientAmount(potatoes, 300),
            NutritionIngredientAmount(oliveOil, 10),
            NutritionIngredientAmount(tomatoes, 150)
        ),
        calories = 760,
        proteinGrams = 30
    ),
    NutritionMealTemplate(
        type = NutritionMealType.DINNER,
        ingredients = listOf(
            NutritionIngredientAmount(chickenBreast, 120),
            NutritionIngredientAmount(wholeGrainBread, 4),
            NutritionIngredientAmount(avocado, 1),
            NutritionIngredientAmount(cucumber, 150),
            NutritionIngredientAmount(spinach, 100)
        ),
        calories = 800,
        proteinGrams = 31
    )
)

private val postWorkoutTemplates = listOf(
    NutritionMealTemplate(
        type = NutritionMealType.POST_WORKOUT,
        ingredients = listOf(
            NutritionIngredientAmount(greekYogurt, 200),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(peanutButter, 20)
        ),
        calories = 310,
        proteinGrams = 18,
        optional = true
    ),
    NutritionMealTemplate(
        type = NutritionMealType.POST_WORKOUT,
        ingredients = listOf(
            NutritionIngredientAmount(milk, 300),
            NutritionIngredientAmount(banana, 1),
            NutritionIngredientAmount(wheyProtein, 20),
            NutritionIngredientAmount(oats, 30)
        ),
        calories = 320,
        proteinGrams = 22,
        optional = true
    )
)

fun generateThreeDayMuscleGainPlan(seed: Int): NutritionPlan {
    val base = seed.absoluteValue

    val days = (0 until 3).map { index ->
        val rotation = base + index
        NutritionDayPlan(
            dayNumber = index + 1,
            meals = listOf(
                breakfastTemplates[rotation % breakfastTemplates.size].toMeal(),
                lunchTemplates[(rotation + 1) % lunchTemplates.size].toMeal(),
                dinnerTemplates[(rotation + 2) % dinnerTemplates.size].toMeal(),
                postWorkoutTemplates[rotation % postWorkoutTemplates.size].toMeal()
            )
        )
    }

    return NutritionPlan(days)
}

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
