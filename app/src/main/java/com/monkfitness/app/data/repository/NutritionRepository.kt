package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.NutritionDao
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.MealEntity
import com.monkfitness.app.data.model.ShoppingItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * The nutrition domain's storage, and the body-weight log its targets are computed from: meal cycles,
 * their meals, their shopping lists and body-weight entries — and no Program table at all.
 *
 * The meal calendar is its own (`MealCycle.startDate` + `durationDays`) and a body-weight row is a date
 * and a weight, so nothing here is keyed by a cycle or a day number of any program. The reads that were
 * silent-empty on failure keep that contract — a chart with no data is a chart, not a broken screen —
 * while the writes surface their failure to the caller.
 */
class NutritionRepository(
    private val dao: NutritionDao
) {

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

    fun getBodyWeightEntriesSince(cutoff: String): Flow<List<BodyWeightEntry>> =
        dao.getEntriesSince(cutoff).catch { emit(emptyList()) }

    suspend fun insertBodyWeightEntry(entry: BodyWeightEntry) = dao.insertEntry(entry)

    suspend fun getLatestBodyWeightEntry(): BodyWeightEntry? = try {
        dao.getLatestEntry()
    } catch (_: Exception) {
        null
    }
}
