package com.monkfitness.app

import com.monkfitness.app.data.model.MealEntity
import com.monkfitness.app.data.model.NutritionCatalogue
import com.monkfitness.app.data.model.mealEntitiesToNutritionPlan
import com.monkfitness.app.data.model.nutritionIngredientsByKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test

/**
 * Ids are the only link between the catalogue and cycles already stored in Room, and the decoder drops
 * a key it does not know without an error. These ids were shipped before the catalogue became data;
 * they are listed once, here, so a rename has to break a test.
 */
class NutritionCatalogueIdStabilityTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = NutritionCatalogueFixture.load()

        private val ingredientIds = listOf(
            "chicken", "eggs", "rice", "oats", "potatoes", "buckwheat",
            "cottage_cheese", "yogurt", "banana", "apple", "nuts", "tuna",
            "carrots", "tomatoes", "courgette", "cauliflower", "lentils", "chickpeas",
            "salmon", "cod", "pasta", "bread", "soy_milk", "tofu", "ham", "milk"
        )

        private val templateIds = listOf(
            "breakfast_high_carb_1", "breakfast_high_carb_2",
            "breakfast_high_protein_1", "breakfast_high_protein_2",
            "breakfast_balanced_1", "breakfast_balanced_2",
            "lunch_high_carb_1", "lunch_high_carb_2",
            "lunch_high_protein_1", "lunch_high_protein_2",
            "lunch_balanced_1", "lunch_balanced_2",
            "dinner_high_carb_1", "dinner_high_carb_2",
            "dinner_high_protein_1", "dinner_high_protein_2",
            "dinner_balanced_1", "dinner_balanced_2",
            "post_workout_high_carb_1", "post_workout_high_carb_2",
            "post_workout_high_protein_1", "post_workout_high_protein_2",
            "post_workout_balanced_1", "post_workout_balanced_2",
            "snack_high_carb_1", "snack_high_carb_2",
            "snack_high_protein_1", "snack_high_protein_2",
            "snack_balanced_1", "snack_balanced_2",
            "breakfast_high_carb_3", "breakfast_high_carb_4",
            "breakfast_high_protein_3", "breakfast_high_protein_4",
            "breakfast_balanced_3", "breakfast_balanced_4", "breakfast_balanced_5",
            "lunch_high_carb_3", "lunch_high_carb_4",
            "lunch_high_protein_3", "lunch_high_protein_4",
            "lunch_balanced_3", "lunch_balanced_4",
            "dinner_high_carb_3", "dinner_high_carb_4",
            "dinner_high_protein_3", "dinner_high_protein_4",
            "dinner_balanced_3", "dinner_balanced_4",
            "post_workout_high_carb_3", "post_workout_high_carb_4",
            "post_workout_high_protein_3", "post_workout_high_protein_4",
            "post_workout_balanced_3", "post_workout_balanced_4",
            "snack_high_carb_3", "snack_high_carb_4",
            "snack_high_protein_3", "snack_high_protein_4",
            "snack_balanced_3", "snack_balanced_4"
        )
    }

    @Test
    fun everyIdShippedBeforeTheCatalogueBecameDataStillResolves() {
        ingredientIds.forEach { id ->
            assertNotNull("ingredient $id no longer resolves", nutritionIngredientsByKey[id])
        }
        templateIds.forEach { id ->
            assertNotNull("template $id no longer resolves", NutritionCatalogue.templatesById[id])
        }
    }

    @Test
    fun aStoredCycleWrittenByThePreviousCatalogueStillRendersItsOwnNumbers() {
        val stored = MealEntity(
            cycleId = 4L,
            dayNumber = 1,
            programDay = 1,
            week = 1,
            dayType = "TRAINING",
            mealTypeKey = "breakfast",
            mealProfile = "HIGH_CARB",
            templateId = "breakfast_high_carb_1",
            ingredientData = "oats:90|yogurt:220|banana:1",
            calories = 520,
            proteinGrams = 24,
            optional = false
        )

        val meal = mealEntitiesToNutritionPlan(4L, listOf(stored)).days.single().meals.single()

        assertEquals(listOf("oats", "yogurt", "banana"), meal.ingredients.map { it.ingredient.key })
        assertEquals(listOf(90, 220, 1), meal.ingredients.map { it.amount })
        assertEquals(520, meal.calories)
        assertEquals(24, meal.proteinGrams)
    }

    @Test
    fun anUnknownStoredIngredientKeyIsStillDroppedWithoutAnError() {
        val stored = MealEntity(
            cycleId = 4L,
            dayNumber = 1,
            programDay = 1,
            week = 1,
            dayType = "TRAINING",
            mealTypeKey = "breakfast",
            mealProfile = "HIGH_CARB",
            templateId = "breakfast_high_carb_1",
            ingredientData = "oats:90|kale:50",
            calories = 520,
            proteinGrams = 24,
            optional = false
        )

        val meal = mealEntitiesToNutritionPlan(4L, listOf(stored)).days.single().meals.single()

        assertEquals(listOf("oats"), meal.ingredients.map { it.ingredient.key })
        assertNull(nutritionIngredientsByKey["kale"])
    }
}
