package com.monkfitness.app.data.model

import androidx.annotation.StringRes
import com.monkfitness.app.R
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue
import kotlin.math.abs
import kotlin.math.roundToInt

enum class NutritionMealType(
    val key: String,
    @StringRes val labelRes: Int,
    val orderIndex: Int
) {
    BREAKFAST("breakfast", R.string.nutrition_breakfast, 1),
    LUNCH("lunch", R.string.nutrition_lunch, 2),
    DINNER("dinner", R.string.nutrition_dinner, 3),
    POST_WORKOUT("post_workout", R.string.nutrition_post_workout, 4),
    SNACK("snack", R.string.nutrition_snack, 5)
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
    val preparationStyle: NutritionPreparationStyle,
    val nutrientsPer100g: NutritionNutrientsPer100g? = null,
    val basis: String? = null,
    val source: NutritionSourceRef? = null,
    val secondSource: NutritionSecondSource? = null,
    val gramsPerPiece: Int? = null,
    val pieceSource: NutritionSourceRef? = null,
    /** The market's own word for this ingredient, by language tag; empty when none is known. */
    val names: Map<String, NutritionIngredientName> = emptyMap(),
    /** The food is pork, so a no-pork plan must leave it out. */
    val pork: Boolean = false,
    /** The food carries lactose, so a lactose-free plan must leave it out. */
    val lactose: Boolean = false
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
    val optional: Boolean = false,
    val cycleId: Long = 0
)

/** Whether a day's calorie field is a target to reach or the plan's own delivered total. */
enum class NutritionCalorieBasis { TARGET, PLANNED_TOTAL }

const val NUTRITION_PROGRAM_DAY_CAP = 56

data class NutritionDayPlan(
    val dayNumber: Int,
    val programDay: Int,
    val week: Int,
    val dayType: NutritionDayType,
    val targetCalories: Int,
    val meals: List<NutritionMeal>,
    val calorieBasis: NutritionCalorieBasis = NutritionCalorieBasis.TARGET
) {
    val totalCalories: Int = meals.sumOf { it.calories }
    val totalProteinGrams: Int = meals.sumOf { it.proteinGrams }
}

enum class NutritionDayStatus { PAST, TODAY, UPCOMING }

/** The 1-based day of a meal cycle that [date] falls on, or null when the cycle is not running then. */
fun nutritionCycleDayNumber(startDate: LocalDate, date: LocalDate, dayCount: Int): Int? {
    val number = ChronoUnit.DAYS.between(startDate, date).toInt() + 1
    return number.takeIf { it in 1..dayCount }
}

/** The day's shortfall or overshoot once it exceeds 5 % of the target; null when it is close enough. */
fun NutritionDayPlan.calorieTargetGap(): Int? {
    if (calorieBasis != NutritionCalorieBasis.TARGET || targetCalories <= 0) return null
    val gap = targetCalories - totalCalories
    return gap.takeIf { abs(it) * 100 > targetCalories * 5 }
}

fun NutritionDayPlan.isProgramDayCapped(): Boolean = programDay >= NUTRITION_PROGRAM_DAY_CAP

fun NutritionDayPlan.statusOn(todayDayNumber: Int?): NutritionDayStatus = when {
    todayDayNumber == null -> NutritionDayStatus.UPCOMING
    dayNumber < todayDayNumber -> NutritionDayStatus.PAST
    dayNumber == todayDayNumber -> NutritionDayStatus.TODAY
    else -> NutritionDayStatus.UPCOMING
}

/** Today first, then upcoming days, then past ones — a past day never reads as upcoming. */
fun NutritionPlan.orderedForDisplay(todayDayNumber: Int?): List<NutritionDayPlan> =
    days.sortedWith(
        compareBy<NutritionDayPlan> {
            when (it.statusOn(todayDayNumber)) {
                NutritionDayStatus.TODAY -> 0
                NutritionDayStatus.UPCOMING -> 1
                NutritionDayStatus.PAST -> 2
            }
        }.thenBy { it.dayNumber }
    )

data class NutritionShoppingListItem(
    val ingredient: NutritionIngredient,
    val totalAmount: Int,
    val cycleId: Long = 0
)

data class NutritionPlan(
    val days: List<NutritionDayPlan>,
    val cycleId: Long = 0
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
                    totalAmount = items.sumOf { it.amount },
                    cycleId = cycleId
                )
            }
            .groupBy { it.ingredient.group }
            .mapValues { (_, items) -> items.sortedBy { it.ingredient.key } }
}

data class NutritionVariety(
    val distinctTemplates: Int,
    val repeatedFromPrevious: Int
) {
    /** Share of the plan's dishes that the previous cycle did not already contain. */
    val newRatio: Float = if (distinctTemplates == 0) 0f
    else (distinctTemplates - repeatedFromPrevious).toFloat() / distinctTemplates
}

fun nutritionVariety(plan: NutritionPlan, previousPlan: NutritionPlan?): NutritionVariety {
    val templates = plan.days.flatMap { it.meals }.map { it.templateId }.toSet()
    val previous = previousPlan?.days.orEmpty().flatMap { it.meals }.map { it.templateId }.toSet()
    return NutritionVariety(
        distinctTemplates = templates.size,
        repeatedFromPrevious = templates.count { it in previous }
    )
}

internal data class NutritionMealTemplate(
    val id: String,
    val type: NutritionMealType,
    val mealType: MealType,
    val ingredients: List<NutritionIngredientAmount>,
    /** The two or three ingredients the dish is named after, in the order the name reads. */
    val mainIngredientKeys: List<String>,
    val calories: Int,
    val proteinGrams: Int,
    val optional: Boolean = false
) {
    /** A meal priced from the amounts actually served, so its numbers never drift from its food. */
    fun toMeal(scaled: List<NutritionIngredientAmount>, cycleId: Long): NutritionMeal = NutritionMeal(
        templateId = id,
        type = type,
        mealType = mealType,
        ingredients = scaled,
        calories = scaled.sumOf { it.ingredient.kcalFor(it.amount) }.roundToInt(),
        proteinGrams = scaled.sumOf { it.ingredient.proteinFor(it.amount) }.roundToInt(),
        optional = optional,
        cycleId = cycleId
    )

    fun toMeal(scale: Float = 1f, cycleId: Long = 0): NutritionMeal = toMeal(
        ingredients.map { ingredientAmount ->
            ingredientAmount.copy(amount = scaleAmount(ingredientAmount.amount, ingredientAmount.ingredient.unit, scale))
        },
        cycleId
    )
}

val nutritionExclusionIngredients: List<NutritionIngredient>
    get() = NutritionCatalogue.ingredients

/**
 * The exclusion set a dietary filter adds: the foods of the one ingredient list that carry the tag.
 *
 * It is added to the user's own exclusions rather than kept apart, so a filtered food and a manually
 * excluded one take the same path through selection, replacement and the shopping list.
 */
fun nutritionDietaryExcludedKeys(noPork: Boolean = false, lactoseFree: Boolean = false): Set<String> {
    if (!noPork && !lactoseFree) return emptySet()
    return NutritionCatalogue.ingredients
        .filter { ingredient -> (noPork && ingredient.pork) || (lactoseFree && ingredient.lactose) }
        .map { it.key }
        .toSet()
}

/** Every food a new plan must leave out: the user's own exclusions plus what the filters tag. */
fun nutritionExcludedFoodKeys(
    manualExcludedKeys: Set<String>,
    noPork: Boolean,
    lactoseFree: Boolean
): Set<String> = manualExcludedKeys + nutritionDietaryExcludedKeys(noPork, lactoseFree)

private val nutritionMealTemplates: List<NutritionMealTemplate>
    get() = NutritionCatalogue.templates

private val mealsPool: Map<MealType, List<NutritionMealTemplate>>
    get() = nutritionMealTemplates.groupBy { it.mealType }

val nutritionIngredientsByKey: Map<String, NutritionIngredient>
    get() = NutritionCatalogue.ingredientsByKey

/**
 * The ingredients a dish is named after, in the one order a name reads in: the protein the dish is built
 * around, then the staple, then produce, then the fat that finishes it. A recipe's own order is authoring
 * order and must not change the name — the same set of foods has to read the same wherever it appears.
 *
 * A meal stored before this data existed has no main list of its own, so it falls back to its first three
 * ingredients and still reads as a dish.
 */
fun nutritionDishIngredientKeys(meal: NutritionMeal): List<String> {
    val keys = NutritionCatalogue.templatesById[meal.templateId]?.mainIngredientKeys
        ?.takeIf { it.isNotEmpty() }
        ?: meal.ingredients.map { it.ingredient.key }.take(3)
    return keys.sortedBy { key -> NUTRITION_NAME_ORDER.indexOf(key).let { if (it < 0) Int.MAX_VALUE else it } }
}

/** "A + B + C": names joined by a language-neutral separator, so no language's grammar leaks in. */
fun nutritionDishName(names: List<String>): String =
    names.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(NUTRITION_DISH_NAME_SEPARATOR)

internal const val NUTRITION_DISH_NAME_SEPARATOR = " + "

/** Every food the app knows, in name order; the parser refuses a catalogue with a food missing from it. */
internal val NUTRITION_NAME_ORDER: List<String> = listOf(
    // the protein the dish is built around
    "chicken", "eggs", "tuna", "salmon", "cod", "ham", "tofu",
    "cottage_cheese", "yogurt", "milk", "soy_milk",
    // the staple or pulse that carries it
    "lentils", "chickpeas", "rice", "pasta", "oats", "buckwheat", "potatoes", "bread",
    // the produce
    "carrots", "tomatoes", "courgette", "cauliflower", "banana", "apple",
    // the fat that finishes it
    "nuts"
)

enum class NutritionGenerationIssue(@StringRes val messageRes: Int) {
    NOT_ENOUGH_PROTEIN(R.string.nutrition_not_enough_protein_sources),
    NOT_ENOUGH_CARBS(R.string.nutrition_not_enough_carbs),
    NOT_ENOUGH_FRUITS_OR_VEGETABLES(R.string.nutrition_add_more_fruits_vegetables)
}

fun generateNutritionPlan(
    seed: Int,
    energy: NutritionEnergyPlan,
    startDay: Int = 1,
    daysCount: Int = 3,
    excludedIngredientKeys: Set<String> = emptySet(),
    replacements: Map<String, String> = emptyMap(),
    preferredIngredientKeys: Set<String> = emptySet(),
    cycleId: Long = 0,
    previousPlan: NutritionPlan? = null,
    workoutTypeForDay: (Int) -> WorkoutType = ::defaultWorkoutTypeForDay
): NutritionPlan = generateNutritionPlan(
    seed = seed,
    energy = energy,
    startDay = startDay,
    daysCount = daysCount,
    excludedIngredientKeys = excludedIngredientKeys,
    replacements = replacements,
    preferredIngredientKeys = preferredIngredientKeys,
    cycleId = cycleId,
    previousPlan = previousPlan,
    workoutTypeForDay = workoutTypeForDay,
    templateIds = null
)

/**
 * [templateIds] limits selection to a named set, so a fixture recorded from an earlier catalogue can be
 * replayed against the templates that existed then. Null means the whole catalogue.
 */
internal fun generateNutritionPlan(
    seed: Int,
    energy: NutritionEnergyPlan,
    startDay: Int,
    daysCount: Int,
    excludedIngredientKeys: Set<String>,
    replacements: Map<String, String>,
    preferredIngredientKeys: Set<String>,
    cycleId: Long,
    previousPlan: NutritionPlan?,
    workoutTypeForDay: (Int) -> WorkoutType,
    templateIds: Set<String>?
): NutritionPlan {
    val base = seed.absoluteValue
    val safeStartDay = startDay.coerceIn(1, 56)
    val safeDaysCount = daysCount.coerceIn(1, 7)
    val selection = NutritionSelection(
        seed = base,
        excludedIngredientKeys = excludedIngredientKeys,
        preferredIngredientKeys = preferredIngredientKeys,
        templateIds = templateIds,
        previousPlan = previousPlan
    )
    // The day's protein lands this far under the top of its range, so no rounding can push it over.
    val proteinGoal = energy.proteinMaxGrams - PROTEIN_HEADROOM_GRAMS

    val days = (0 until safeDaysCount).map { index ->
        val programDay = (safeStartDay + index).coerceAtMost(NUTRITION_PROGRAM_DAY_CAP)
        val week = ((programDay - 1) / 7) + 1
        val dayType = resolveNutritionDayType(workoutTypeForDay(programDay))
        val targetCalories = energy.targetsFor(dayType).dailyCalories
        val slots = slotsForDayType(dayType)
        val rotation = base + index + (programDay * 3)

        val meals = slots.mapIndexedNotNull { slotIndex, slot ->
            val mealType = MealType.entries[(rotation + slotIndex) % MealType.entries.size]
            val replacementKey = nutritionReplacementKey(programDay, slot)
            val replacementTemplateId = replacements[replacementKey]
            // A pinned dish is pinned: the day's own profile rotation must not stop the dish a stored
            // day already serves from being found again.
            val explicit = replacementTemplateId?.let { id ->
                templatesFor(slot, mealType, excludedIngredientKeys, templateIds).firstOrNull { it.id == id }
                    ?: nutritionMealTemplates.firstOrNull { template ->
                        template.id == id &&
                            template.type == slot &&
                            (templateIds == null || id in templateIds) &&
                            template.ingredients.none { it.ingredient.key in excludedIngredientKeys }
                    }
            }
            if (explicit != null) {
                selection.record(slot, explicit)
                explicit
            } else {
                selection.chooseTemplate(
                    slot = slot,
                    dayPosition = index + 1,
                    preferredProfile = mealType
                )
            }
        }

        val portions = scaleDay(meals, targetCalories, proteinGoal)

        NutritionDayPlan(
            dayNumber = index + 1,
            programDay = programDay,
            week = week,
            dayType = dayType,
            targetCalories = targetCalories,
            meals = meals.mapIndexed { mealIndex, template ->
                template.toMeal(portions.getOrElse(mealIndex) { template.ingredients }, cycleId)
            }
        )
    }

    return NutritionPlan(days = days, cycleId = cycleId)
}

/**
 * Re-portions the days after [todayDayNumber] onto [energy], keeping every dish those days already serve.
 *
 * A past day is a record of what was eaten and is never rebuilt; only future days are. Dishes are pinned
 * by the replacement map, so the portions move to the new target and the menu stays the one the user
 * chose.
 */
fun reportionNutritionPlan(
    stored: NutritionPlan,
    todayDayNumber: Int?,
    energy: NutritionEnergyPlan,
    excludedIngredientKeys: Set<String> = emptySet()
): NutritionPlan {
    if (todayDayNumber == null) return stored
    val firstFutureIndex = stored.days.indexOfFirst { it.dayNumber > todayDayNumber }
    if (firstFutureIndex < 0) return stored

    val future = stored.days.drop(firstFutureIndex)
    val pinnedDishes = future.flatMap { day ->
        day.meals.map { meal -> nutritionReplacementKey(day.programDay, meal.type) to meal.templateId }
    }.toMap()
    val rebuilt = generateNutritionPlan(
        seed = future.first().programDay,
        energy = energy,
        startDay = future.first().programDay,
        daysCount = future.size,
        excludedIngredientKeys = excludedIngredientKeys,
        replacements = pinnedDishes,
        cycleId = stored.cycleId
    )

    return stored.copy(
        days = stored.days.mapIndexed { index, day ->
            if (index < firstFutureIndex) return@mapIndexed day
            val rebuiltDay = rebuilt.days.getOrNull(index - firstFutureIndex) ?: return@mapIndexed day
            val meals = rebuiltDay.meals.map { it.copy(cycleId = stored.cycleId) }
            if (day.calorieBasis == NutritionCalorieBasis.PLANNED_TOTAL) {
                day.copy(meals = meals, targetCalories = meals.sumOf { it.calories })
            } else {
                day.copy(meals = meals)
            }
        }
    )
}

/** Whether a stored cycle's day is far enough from today's target to be worth re-portioning. */
fun nutritionReportionNeeded(storedDayCalories: Int, targetCalories: Int): Boolean {
    if (storedDayCalories <= 0 || targetCalories <= 0) return false
    return abs(targetCalories - storedDayCalories) * 100 > targetCalories * 5
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
        "pasta" in ingredientKeys -> R.string.nutrition_step_cook_pasta
        else -> null
    }
    val proteinStep = when {
        "chicken" in ingredientKeys -> R.string.nutrition_step_grill_chicken
        "eggs" in ingredientKeys -> R.string.nutrition_step_boil_eggs
        "tuna" in ingredientKeys -> R.string.nutrition_step_prepare_tuna
        "salmon" in ingredientKeys || "cod" in ingredientKeys -> R.string.nutrition_step_grill_fish
        else -> null
    }
    val freshStep = if (ingredientKeys.any { it in NUTRITION_FRESH_INGREDIENTS }) {
        R.string.nutrition_step_prepare_fresh
    } else {
        null
    }

    if (carbStep != null) {
        steps += carbStep
    }
    if (proteinStep != null) {
        steps += proteinStep
    }
    // Nothing to cook yet, so the base of the dish is what the cook starts with.
    if (steps.isEmpty() && ingredientKeys.any { it in NUTRITION_DAIRY_BASES }) {
        steps += R.string.nutrition_step_prepare_dairy_base
    }
    if (steps.isEmpty() && freshStep != null) {
        steps += freshStep
    }

    steps += when {
        ingredientKeys.any { it in NUTRITION_FINISHING_INGREDIENTS } ->
            R.string.nutrition_step_finish_with_toppings
        else -> R.string.nutrition_step_finish_simple
    }

    return steps.distinct().take(3)
}

private val NUTRITION_DAIRY_BASES = setOf("yogurt", "cottage_cheese", "milk")

/** Eaten as they come, so a dish whose only step is serving is still dished up from something fresh. */
private val NUTRITION_FRESH_INGREDIENTS =
    setOf("carrots", "tomatoes", "courgette", "cauliflower", "banana", "apple")

private val NUTRITION_FINISHING_INGREDIENTS =
    setOf("yogurt", "cottage_cheese", "milk", "banana", "apple", "nuts")

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
        ?.toMeal(calorieScale, meal.cycleId)
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

/**
 * How far a day's portions may move: the day as a whole, the protein foods, everything else, and any one
 * food. A day reaches its calorie target by portioning, never by serving an implausible amount of one
 * food — no food is more than [TEMPLATE_MULTIPLE] of its own recipe, and no meal is either.
 */
private const val DAY_SCALE_MIN = 0.85f
private const val DAY_SCALE_MAX = 2.6f
private const val PROTEIN_SCALE_MIN = 0.25f
private const val PROTEIN_SCALE_MAX = 2.5f
private const val ENERGY_SCALE_MIN = 0.5f
private const val ENERGY_SCALE_MAX = 3.5f
private const val TEMPLATE_MULTIPLE = 3.5f

/** Protein is aimed this far below the top of the range, so 5 g rounding cannot push a day past it. */
private const val PROTEIN_HEADROOM_GRAMS = 8

/** The most of one food a single portion may serve, in the ingredient's own unit (g, ml or pieces). */
internal val NUTRITION_PORTION_CEILING: Map<String, Int> = mapOf(
    "chicken" to 320,
    "tuna" to 250,
    "salmon" to 250,
    "cod" to 300,
    "tofu" to 350,
    "ham" to 200,
    "cottage_cheese" to 600,
    "yogurt" to 700,
    "milk" to 800,
    "soy_milk" to 800,
    "rice" to 850,
    "oats" to 300,
    "potatoes" to 1000,
    "buckwheat" to 800,
    "pasta" to 500,
    "bread" to 450,
    "lentils" to 700,
    "chickpeas" to 700,
    "nuts" to 60,
    "carrots" to 450,
    "tomatoes" to 350,
    "courgette" to 350,
    "cauliflower" to 450,
    "eggs" to 4,
    "banana" to 2,
    "apple" to 2
)

/** One food in one dish, with the amount the recipe calls for and the amount being served. */
private class ScaledPortion(
    val ingredient: NutritionIngredient,
    val recipeAmount: Int,
    var amount: Int
)

private fun portionCeiling(ingredient: NutritionIngredient, recipeAmount: Int): Int {
    val ceiling = NUTRITION_PORTION_CEILING[ingredient.key] ?: Int.MAX_VALUE
    if (ingredient.unit == NutritionQuantityUnit.PIECES) return ceiling
    // 5 g steps, so a scaled amount can never land between two steps and read as a stricter cap than it is.
    val multiple = ((recipeAmount * TEMPLATE_MULTIPLE) / 5f).toInt() * 5
    return minOf(ceiling, maxOf(multiple, 5))
}

private fun scaledAmount(ingredient: NutritionIngredient, recipeAmount: Int, scale: Float): Int {
    val ceiling = portionCeiling(ingredient, recipeAmount)
    return when (ingredient.unit) {
        NutritionQuantityUnit.PIECES -> (recipeAmount * scale).roundToInt().coerceIn(1, ceiling)
        NutritionQuantityUnit.GRAMS,
        NutritionQuantityUnit.MILLILITERS -> (((recipeAmount * scale) / 5f).roundToInt() * 5).coerceIn(5, ceiling)
    }
}

private fun NutritionIngredient.isProteinFood(): Boolean = group == NutritionShoppingGroup.PROTEIN

/** Protein per calorie, which is the order the leftover energy is handed out in. */
private fun NutritionIngredient.proteinDensity(): Double {
    val nutrients = nutrientsPer100g ?: return 0.0
    return if (nutrients.kcal <= 0.0) 0.0 else nutrients.proteinGrams / nutrients.kcal
}

private fun List<List<ScaledPortion>>.kcalOfDay(): Double =
    sumOf { meal -> meal.sumOf { it.ingredient.kcalFor(it.amount) } }

private fun List<List<ScaledPortion>>.proteinOfDay(): Double =
    sumOf { meal -> meal.sumOf { it.ingredient.proteinFor(it.amount) } }

private fun scaleMeal(meal: NutritionMealTemplate, proteinScale: Float, energyScale: Float): List<ScaledPortion> =
    meal.ingredients.map { amount ->
        val scale = if (amount.ingredient.isProteinFood()) proteinScale else energyScale
        ScaledPortion(amount.ingredient, amount.amount, scaledAmount(amount.ingredient, amount.amount, scale))
    }

/**
 * Hands the day's remaining calories to the meals that can take them, and within a meal to the foods
 * lowest in protein per calorie — so a day that needs more energy gets it from the rice and the potatoes
 * before it gets it from the chicken, and no single meal swallows the whole gap.
 */
private fun List<List<ScaledPortion>>.fillDay(delta: Double) {
    val growing = delta > 0
    var remaining = delta
    repeat(3) {
        if (abs(remaining) <= 1.0) return
        val roomPerMeal = map { meal -> meal.kcalRoom(growing) }
        if (roomPerMeal.sum() <= 0.0) return
        // Each dish grows in proportion to its own size, so a big lunch stays the big meal of the day.
        val sizePerMeal = map { meal -> meal.sumOf { it.ingredient.kcalFor(it.recipeAmount) } }
        val totalSize = sizePerMeal.sum().takeIf { it > 0.0 } ?: return
        var moved = 0.0
        forEachIndexed { index, meal ->
            val want = minOf(roomPerMeal[index], abs(remaining) * sizePerMeal[index] / totalSize)
            if (want > 1.0) moved += meal.fillWithin(want, growing)
        }
        if (moved <= 0.5) return
        remaining -= if (growing) moved else -moved
    }
}

/** Energy a meal can still take (or give back) without any one food leaving its own limits. */
private fun List<ScaledPortion>.kcalRoom(growing: Boolean): Double =
    sumOf { portion ->
        if (portion.ingredient.unit == NutritionQuantityUnit.PIECES) {
            0.0
        } else {
            val room = if (growing) portionCeiling(portion.ingredient, portion.recipeAmount) - portion.amount
            else portion.amount - 5
            if (room < 5) 0.0 else abs(portion.ingredient.kcalFor(room))
        }
    }

private fun List<ScaledPortion>.fillWithin(wantKcal: Double, growing: Boolean): Double {
    val order = withIndex()
        .filter { (_, portion) -> portion.ingredient.unit != NutritionQuantityUnit.PIECES }
        .sortedBy { (_, portion) -> portion.ingredient.proteinDensity() }
        .map { (index, _) -> index }
        .let { if (growing) it else it.asReversed() }
    var remaining = if (growing) wantKcal else -wantKcal
    var moved = 0.0
    order.forEach { index ->
        if (abs(remaining) <= 1.0) return@forEach
        val portion = this[index]
        val room = if (growing) portionCeiling(portion.ingredient, portion.recipeAmount) - portion.amount
        else portion.amount - 5
        if (room < 5) return@forEach
        val stepKcal = portion.ingredient.kcalFor(5)
        if (stepKcal <= 0.0) return@forEach
        val steps = minOf(room / 5, (abs(remaining) / stepKcal).toInt())
        if (steps <= 0) return@forEach
        portion.amount += if (growing) steps * 5 else -steps * 5
        val change = steps * stepKcal
        moved += change
        remaining -= if (growing) change else -change
    }
    return moved
}

/**
 * Portions one day so its meals land on [targetCalories] and its protein lands at the top of the day's
 * range, or as near to it as the dishes allow.
 *
 * Calories come first: being short of the day's energy is the failure a user feels, while protein above
 * its range is what a dish set this protein-dense can do without turning a meal into a plate of rice.
 * The two scales are solved together — move the protein foods one way and the day rebalances the rest —
 * so the dish keeps its shape instead of becoming a different dish.
 */
private fun scaleDay(
    meals: List<NutritionMealTemplate>,
    targetCalories: Int,
    proteinGoalGrams: Int
): List<List<NutritionIngredientAmount>> {
    if (meals.isEmpty()) return emptyList()

    val proteinKcal = meals.sumOf { meal ->
        meal.ingredients.filter { it.ingredient.isProteinFood() }.sumOf { it.ingredient.kcalFor(it.amount) }
    }
    val energyKcal = meals.sumOf { meal ->
        meal.ingredients.filterNot { it.ingredient.isProteinFood() }.sumOf { it.ingredient.kcalFor(it.amount) }
    }
    val proteinGrams = meals.sumOf { meal ->
        meal.ingredients.filter { it.ingredient.isProteinFood() }.sumOf { it.ingredient.proteinFor(it.amount) }
    }
    val energyProteinGrams = meals.sumOf { meal ->
        meal.ingredients.filterNot { it.ingredient.isProteinFood() }.sumOf { it.ingredient.proteinFor(it.amount) }
    }
    if (proteinKcal + energyKcal <= 0.0) return emptyList()

    val dayScale = (targetCalories / (proteinKcal + energyKcal)).toFloat().coerceIn(DAY_SCALE_MIN, DAY_SCALE_MAX)
    var proteinScale = dayScale
    var energyScale = dayScale
    repeat(6) {
        if (proteinGrams > 0.0) {
            proteinScale = ((proteinGoalGrams - energyScale * energyProteinGrams) / proteinGrams)
                .toFloat().coerceIn(PROTEIN_SCALE_MIN, PROTEIN_SCALE_MAX)
        }
        if (energyKcal > 0.0) {
            energyScale = ((targetCalories - proteinScale * proteinKcal) / energyKcal)
                .toFloat().coerceIn(ENERGY_SCALE_MIN, ENERGY_SCALE_MAX)
        }
    }

    var portions = meals.map { scaleMeal(it, proteinScale, energyScale) }
    if (portions.kcalOfDay() < targetCalories - 2) {
        portions.fillDay(targetCalories - portions.kcalOfDay())
        if (portions.kcalOfDay() < targetCalories - 2 && proteinKcal > 0.0) {
            val toppedUp = (proteinScale + (targetCalories - portions.kcalOfDay()) / proteinKcal)
                .toFloat().coerceIn(proteinScale, PROTEIN_SCALE_MAX)
            portions = meals.map { scaleMeal(it, toppedUp, energyScale) }
            if (portions.kcalOfDay() < targetCalories - 2) {
                portions.fillDay(targetCalories - portions.kcalOfDay())
            }
        }
    } else if (portions.kcalOfDay() > targetCalories + 2) {
        portions.fillDay(targetCalories - portions.kcalOfDay())
    }

    return portions.map { meal -> meal.map { NutritionIngredientAmount(it.ingredient, it.amount) } }
}

/**
 * Picks one template per slot with a seeded order: no template repeats within a slot until its pool is
 * exhausted, never on consecutive days, and the previous cycle's pick for the same slot and position is
 * avoided while an alternative exists. Rules are relaxed in that order when a pool is too small.
 */
private class NutritionSelection(
    private val seed: Int,
    private val excludedIngredientKeys: Set<String>,
    private val preferredIngredientKeys: Set<String>,
    private val templateIds: Set<String>?,
    previousPlan: NutritionPlan?
) {
    private val usedBySlot = mutableMapOf<NutritionMealType, MutableSet<String>>()
    private val lastBySlot = mutableMapOf<NutritionMealType, String>()
    private val pools = mutableMapOf<NutritionMealType, List<NutritionMealTemplate>>()
    private val previousByPosition: Map<String, String> = previousPlan?.days.orEmpty()
        .flatMap { day -> day.meals.map { meal -> "${meal.type.key}:${day.dayNumber}" to meal.templateId } }
        .toMap()

    fun record(slot: NutritionMealType, template: NutritionMealTemplate) {
        usedBySlot.getOrPut(slot) { mutableSetOf() } += template.id
        lastBySlot[slot] = template.id
    }

    fun chooseTemplate(
        slot: NutritionMealType,
        dayPosition: Int,
        preferredProfile: MealType
    ): NutritionMealTemplate? {
        val pool = pools.getOrPut(slot) {
            MealType.entries
                .flatMap { profile -> templatesFor(slot, profile, excludedIngredientKeys, templateIds) }
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<NutritionMealTemplate> { preferenceScore(it, preferredIngredientKeys) }
                        .thenBy { selectionRank(seed, slot, it.id) }
                        .thenBy { it.id }
                )
        }
        if (pool.isEmpty()) return null

        val used = usedBySlot.getOrPut(slot) { mutableSetOf() }
        val last = lastBySlot[slot]
        val previous = previousByPosition["${slot.key}:$dayPosition"]
        val preferred = pool.filter { it.mealType == preferredProfile }

        val tiers = listOf(
            preferred.filter { it.id !in used && it.id != last && it.id != previous },
            pool.filter { it.id !in used && it.id != last && it.id != previous },
            preferred.filter { it.id !in used && it.id != last },
            pool.filter { it.id !in used && it.id != last },
            preferred.filter { it.id !in used },
            pool.filter { it.id !in used },
            pool.filter { it.id != last && it.id != previous },
            pool.filter { it.id != last },
            pool
        )
        val chosen = tiers.firstOrNull { it.isNotEmpty() }?.first() ?: return null
        record(slot, chosen)
        return chosen
    }
}

private fun selectionRank(seed: Int, slot: NutritionMealType, templateId: String): Int {
    var hash = seed xor (slot.ordinal * -1640531527) xor templateId.hashCode()
    hash *= -1640531527
    hash = hash xor (hash ushr 16)
    return hash
}

private fun templatesFor(
    mealType: NutritionMealType,
    replacementType: MealType,
    excludedIngredientKeys: Set<String>,
    templateIds: Set<String>? = null
): List<NutritionMealTemplate> {
    return mealsPool[replacementType].orEmpty().filter { template ->
        template.type == mealType &&
            (templateIds == null || template.id in templateIds) &&
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

internal fun defaultWorkoutTypeForDay(day: Int): WorkoutType = when ((day.coerceIn(1, 56) - 1) % 7) {
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


fun validateAvailableProductSelection(selectedIngredientKeys: Set<String>): NutritionGenerationIssue? {
    if (selectedIngredientKeys.isEmpty()) return null
    val selectedIngredients = selectedIngredientKeys.mapNotNull { nutritionIngredientsByKey[it] }
    if (selectedIngredients.none { it.group == NutritionShoppingGroup.PROTEIN }) {
        return NutritionGenerationIssue.NOT_ENOUGH_PROTEIN
    }
    if (selectedIngredients.none { it.group == NutritionShoppingGroup.CARBS }) {
        return NutritionGenerationIssue.NOT_ENOUGH_CARBS
    }
    if (selectedIngredients.none { it.group == NutritionShoppingGroup.FRUITS_VEGETABLES }) {
        return NutritionGenerationIssue.NOT_ENOUGH_FRUITS_OR_VEGETABLES
    }
    return null
}

fun NutritionPlan.toMealEntities(cycleId: Long): List<MealEntity> {
    return days.flatMap { day ->
        day.meals.map { meal ->
            MealEntity(
                cycleId = cycleId,
                dayNumber = day.dayNumber,
                programDay = day.programDay,
                week = day.week,
                dayType = day.dayType.name,
                mealTypeKey = meal.type.key,
                mealProfile = meal.mealType.name,
                templateId = meal.templateId,
                ingredientData = encodeIngredients(meal.ingredients),
                calories = meal.calories,
                proteinGrams = meal.proteinGrams,
                optional = meal.optional
            )
        }
    }
}

fun NutritionPlan.toShoppingItemEntities(cycleId: Long): List<ShoppingItemEntity> {
    return shoppingList.values.flatten().map { item ->
        ShoppingItemEntity(
            cycleId = cycleId,
            ingredientKey = item.ingredient.key,
            totalAmount = item.totalAmount
        )
    }
}

fun mealEntitiesToNutritionPlan(cycleId: Long, meals: List<MealEntity>): NutritionPlan {
    val days = meals.groupBy { it.dayNumber }
        .toSortedMap()
        .map { (_, items) ->
            val first = items.first()
            NutritionDayPlan(
                dayNumber = first.dayNumber,
                programDay = first.programDay,
                week = first.week,
                dayType = NutritionDayType.valueOf(first.dayType),
                targetCalories = items.sumOf { it.calories },
                calorieBasis = NutritionCalorieBasis.PLANNED_TOTAL,
                meals = items.map { meal ->
                    NutritionMeal(
                        templateId = meal.templateId,
                        type = NutritionMealType.entries.first { it.key == meal.mealTypeKey },
                        mealType = MealType.valueOf(meal.mealProfile),
                        ingredients = decodeIngredients(meal.ingredientData),
                        calories = meal.calories,
                        proteinGrams = meal.proteinGrams,
                        optional = meal.optional,
                        cycleId = cycleId
                    )
                }.sortedBy { it.type.orderIndex }
            )
        }
    return NutritionPlan(days = days, cycleId = cycleId)
}

private fun preferenceScore(template: NutritionMealTemplate, preferredIngredientKeys: Set<String>): Int {
    if (preferredIngredientKeys.isEmpty()) return 0
    val ingredientKeys = template.ingredients.map { it.ingredient.key }
    val matches = ingredientKeys.count { it in preferredIngredientKeys }
    return (matches * 10) - (ingredientKeys.size - matches)
}

private fun encodeIngredients(ingredients: List<NutritionIngredientAmount>): String {
    return ingredients.joinToString("|") { "${it.ingredient.key}:${it.amount}" }
}

private fun decodeIngredients(encoded: String): List<NutritionIngredientAmount> {
    if (encoded.isBlank()) return emptyList()
    return encoded.split("|").mapNotNull { token ->
        val parts = token.split(":")
        if (parts.size != 2) return@mapNotNull null
        val ingredient = nutritionIngredientsByKey[parts[0]] ?: return@mapNotNull null
        NutritionIngredientAmount(ingredient = ingredient, amount = parts[1].toIntOrNull() ?: return@mapNotNull null)
    }
}
