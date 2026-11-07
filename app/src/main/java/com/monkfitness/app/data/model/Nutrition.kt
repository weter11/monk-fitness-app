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
    val pieceSource: NutritionSourceRef? = null
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
    val calories: Int,
    val proteinGrams: Int,
    val optional: Boolean = false
) {
    fun toMeal(scale: Float = 1f, cycleId: Long = 0): NutritionMeal = NutritionMeal(
        templateId = id,
        type = type,
        mealType = mealType,
        ingredients = ingredients.map { ingredientAmount ->
            ingredientAmount.copy(amount = scaleAmount(ingredientAmount.amount, ingredientAmount.ingredient.unit, scale))
        },
        calories = (calories * scale).roundToInt(),
        proteinGrams = (proteinGrams * scale).roundToInt(),
        optional = optional,
        cycleId = cycleId
    )
}

val nutritionExclusionIngredients: List<NutritionIngredient>
    get() = NutritionCatalogue.ingredients

private val nutritionMealTemplates: List<NutritionMealTemplate>
    get() = NutritionCatalogue.templates

private val mealsPool: Map<MealType, List<NutritionMealTemplate>>
    get() = nutritionMealTemplates.groupBy { it.mealType }

val nutritionIngredientsByKey: Map<String, NutritionIngredient>
    get() = NutritionCatalogue.ingredientsByKey

enum class NutritionGenerationIssue(@StringRes val messageRes: Int) {
    NOT_ENOUGH_PROTEIN(R.string.nutrition_not_enough_protein_sources),
    NOT_ENOUGH_CARBS(R.string.nutrition_not_enough_carbs),
    NOT_ENOUGH_FRUITS_OR_VEGETABLES(R.string.nutrition_add_more_fruits_vegetables)
}

fun generateNutritionPlan(
    seed: Int,
    startDay: Int = 1,
    daysCount: Int = 3,
    weightKg: Int?,
    heightCm: Int? = null,
    excludedIngredientKeys: Set<String> = emptySet(),
    replacements: Map<String, String> = emptyMap(),
    preferredIngredientKeys: Set<String> = emptySet(),
    cycleId: Long = 0,
    previousPlan: NutritionPlan? = null,
    workoutTypeForDay: (Int) -> WorkoutType = ::defaultWorkoutTypeForDay
): NutritionPlan {
    val base = seed.absoluteValue
    val safeStartDay = startDay.coerceIn(1, 56)
    val safeDaysCount = daysCount.coerceIn(1, 7)
    val selection = NutritionSelection(
        seed = base,
        excludedIngredientKeys = excludedIngredientKeys,
        preferredIngredientKeys = preferredIngredientKeys,
        previousPlan = previousPlan
    )

    val days = (0 until safeDaysCount).map { index ->
        val programDay = (safeStartDay + index).coerceAtMost(NUTRITION_PROGRAM_DAY_CAP)
        val week = ((programDay - 1) / 7) + 1
        val dayType = resolveNutritionDayType(workoutTypeForDay(programDay))
        val targetCalories = calculateTargetCalories(weightKg = weightKg, heightCm = heightCm, dayType = dayType)
        val slots = slotsForDayType(dayType)
        val rotation = base + index + (programDay * 3)

        val meals = slots.mapIndexedNotNull { slotIndex, slot ->
            val mealType = MealType.entries[(rotation + slotIndex) % MealType.entries.size]
            val replacementKey = nutritionReplacementKey(programDay, slot)
            val replacementTemplateId = replacements[replacementKey]
            val explicit = replacementTemplateId?.let { id ->
                templatesFor(slot, mealType, excludedIngredientKeys).firstOrNull { it.id == id }
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

        val baseCalories = meals.sumOf { it.calories }.coerceAtLeast(1)
        val scale = (targetCalories.toFloat() / baseCalories.toFloat()).coerceIn(0.85f, 1.35f)

        NutritionDayPlan(
            dayNumber = index + 1,
            programDay = programDay,
            week = week,
            dayType = dayType,
            targetCalories = targetCalories,
            meals = meals.map { it.toMeal(scale, cycleId) }
        )
    }

    return NutritionPlan(days = days, cycleId = cycleId)
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
 * Picks one template per slot with a seeded order: no template repeats within a slot until its pool is
 * exhausted, never on consecutive days, and the previous cycle's pick for the same slot and position is
 * avoided while an alternative exists. Rules are relaxed in that order when a pool is too small.
 */
private class NutritionSelection(
    private val seed: Int,
    private val excludedIngredientKeys: Set<String>,
    private val preferredIngredientKeys: Set<String>,
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
                .flatMap { profile -> templatesFor(slot, profile, excludedIngredientKeys) }
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
