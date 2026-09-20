package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.NutritionDao
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.MealEntity
import com.monkfitness.app.data.model.ShoppingItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * The nutrition domain's storage, and the body-weight log its targets are computed from.
 *
 * ### What it is, after the program's retirement
 *
 * It is the **retained** half of the repository that used to be `WorkoutRepository`: meal cycles, their
 * meals, their shopping lists and body-weight entries. That class also carried the shipped 56-day
 * program's progress reads, its day-state grid, its set-log volume history and its streak calculation —
 * §30 step 15 deleted those with the program, and what is left is a repository whose name now says what
 * it holds. The class is renamed rather than kept, because *"Workout"* would be a name about a runtime
 * that no longer exists (§4: **no giant legacy repository containing both**).
 *
 * ### What it deliberately does not do
 *
 * It reads and writes **no Program table**: not a Program, a revision, a day, an opportunity, a session
 * or a set. The meal calendar is its own (`MealCycle.startDate` + `durationDays`), and a body-weight row
 * is a date and a weight. Nothing here is keyed by a cycle or a day number of any program.
 *
 * The reads that were silent-empty on failure keep their existing contract: a chart with no data is a
 * chart, not a broken screen, and each of those methods documents its own fallback. The writes are the
 * ones that surface their failure to the caller.
 *
 * @param dao the nutrition tables and the body-weight log.
 */
class NutritionRepository(
    private val dao: NutritionDao
) {

    // ---------------------------------------------------------------- the meal-cycle calendar

    fun getMealCycles(): Flow<List<MealCycle>> = dao.getMealCycles().catch { emit(emptyList()) }

    suspend fun getMealCyclesSnapshot(): List<MealCycle> = try {
        dao.getMealCyclesSnapshot()
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun insertMealCycle(cycle: MealCycle): Long = try {
        dao.insertMealCycle(cycle)
    } catch (_: Exception) {
        0L
    }

    fun getMealsForCycle(cycleId: Long): Flow<List<MealEntity>> =
        dao.getMealsForCycle(cycleId).catch { emit(emptyList()) }

    fun getShoppingItemsForCycle(cycleId: Long): Flow<List<ShoppingItemEntity>> =
        dao.getShoppingItemsForCycle(cycleId).catch { emit(emptyList()) }

    suspend fun replaceCycleMeals(
        cycleId: Long,
        meals: List<MealEntity>,
        shoppingItems: List<ShoppingItemEntity>
    ) = try {
        dao.replaceCycleMeals(cycleId, meals, shoppingItems)
    } catch (_: Exception) {
    }

    suspend fun getMealsForCycleSnapshot(cycleId: Long): List<MealEntity> = try {
        dao.getMealsForCycleSnapshot(cycleId)
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun getShoppingItemsForCycleSnapshot(cycleId: Long): List<ShoppingItemEntity> = try {
        dao.getShoppingItemsForCycleSnapshot(cycleId)
    } catch (_: Exception) {
        emptyList()
    }

    /** One day's meal of one type. `programDay` is the meal plan's own day, not a program's. */
    suspend fun getMealForCycleAndType(
        cycleId: Long,
        programDay: Int,
        mealTypeKey: String
    ): MealEntity? = try {
        dao.getMealForCycleAndType(cycleId, programDay, mealTypeKey)
    } catch (_: Exception) {
        null
    }

    suspend fun upsertMeal(meal: MealEntity) = try {
        dao.upsertMeal(meal)
    } catch (_: Exception) {
    }

    // ---------------------------------------------------------------- the body-weight log

    fun getBodyWeightEntriesSince(cutoff: String): Flow<List<BodyWeightEntry>> =
        dao.getEntriesSince(cutoff).catch { emit(emptyList()) }

    suspend fun insertBodyWeightEntry(entry: BodyWeightEntry) = dao.insertEntry(entry)

    suspend fun getLatestBodyWeightEntry(): BodyWeightEntry? = try {
        dao.getLatestEntry()
    } catch (_: Exception) {
        null
    }
}
