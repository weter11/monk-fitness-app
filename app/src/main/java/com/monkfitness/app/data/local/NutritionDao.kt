package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.MealEntity
import com.monkfitness.app.data.model.ShoppingItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * The nutrition domain's own storage: the meal-cycle calendar, its meals, its shopping list — and the
 * body-weight log, which the calorie targets are computed from.
 *
 * Global concerns only, with no Program table reached from either side. Nothing here is keyed by a
 * Program, a cycle or a day number of any program: a meal cycle is its own calendar (`startDate` +
 * `durationDays`), and a body-weight row is a date and a weight.
 */
@Dao
interface NutritionDao {

    @Query("SELECT * FROM meal_cycles ORDER BY startDate ASC, id ASC")
    fun getMealCycles(): Flow<List<MealCycle>>

    @Query("SELECT * FROM meal_cycles ORDER BY startDate ASC, id ASC")
    suspend fun getMealCyclesSnapshot(): List<MealCycle>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealCycle(cycle: MealCycle): Long

    @Query("SELECT * FROM meals WHERE cycleId = :cycleId ORDER BY dayNumber ASC, mealTypeKey ASC")
    fun getMealsForCycle(cycleId: Long): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE cycleId = :cycleId ORDER BY dayNumber ASC, mealTypeKey ASC")
    suspend fun getMealsForCycleSnapshot(cycleId: Long): List<MealEntity>

    @Query(
        "SELECT * FROM meals WHERE cycleId = :cycleId AND programDay = :programDay " +
            "AND mealTypeKey = :mealTypeKey LIMIT 1"
    )
    suspend fun getMealForCycleAndType(cycleId: Long, programDay: Int, mealTypeKey: String): MealEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeals(meals: List<MealEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeal(meal: MealEntity)

    @Query("DELETE FROM meals WHERE cycleId = :cycleId")
    suspend fun deleteMealsForCycle(cycleId: Long)

    @Query("SELECT * FROM shopping_items WHERE cycleId = :cycleId ORDER BY ingredientKey ASC")
    fun getShoppingItemsForCycle(cycleId: Long): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM shopping_items WHERE cycleId = :cycleId ORDER BY ingredientKey ASC")
    suspend fun getShoppingItemsForCycleSnapshot(cycleId: Long): List<ShoppingItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShoppingItems(items: List<ShoppingItemEntity>)

    @Query("DELETE FROM shopping_items WHERE cycleId = :cycleId")
    suspend fun deleteShoppingItemsForCycle(cycleId: Long)

    @Transaction
    suspend fun replaceCycleMeals(cycleId: Long, meals: List<MealEntity>, shoppingItems: List<ShoppingItemEntity>) {
        deleteMealsForCycle(cycleId)
        deleteShoppingItemsForCycle(cycleId)
        if (meals.isNotEmpty()) {
            insertMeals(meals)
        }
        if (shoppingItems.isNotEmpty()) {
            insertShoppingItems(shoppingItems)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: BodyWeightEntry)

    @Query(
        """
        SELECT * FROM body_weight_log
        WHERE date >= :cutoff
        ORDER BY date ASC
        """
    )
    fun getEntriesSince(cutoff: String): Flow<List<BodyWeightEntry>>

    @Query(
        """
        SELECT * FROM body_weight_log
        ORDER BY date DESC
        LIMIT 1
        """
    )
    suspend fun getLatestEntry(): BodyWeightEntry?

    @Query("DELETE FROM body_weight_log")
    suspend fun clearBodyWeightEntries()
}
