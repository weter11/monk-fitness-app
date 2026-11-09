package com.monkfitness.app

import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.LibraryCounts
import com.monkfitness.app.data.model.MealType
import com.monkfitness.app.data.model.NutritionCatalogue
import com.monkfitness.app.data.model.NutritionIngredient
import com.monkfitness.app.data.model.NutritionMealTemplate
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.NutritionPreparationStyle
import com.monkfitness.app.data.model.NutritionQuantityUnit
import com.monkfitness.app.data.model.NutritionShoppingGroup
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The "In the app" counts are derived from the registries, so adding an exercise, a food or a dish
 * changes them without a test edit. A derivation that counts the wrong thing still fails: an exercise
 * with no dedicated animation, a food with no name and a template with no readable dish name are each
 * excluded by the same rule the card uses.
 */
class LibraryCountsTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadCatalogue() {
            NutritionCatalogueFixture.load()
        }
    }

    @Test
    fun theCardCountsWhatTheRegistriesActuallyHold() {
        val exercises = WorkoutGenerator().getExerciseLibrary()
        val dedicated = PoseRegistry.getDedicatedAnimationIds()
        val ingredients = NutritionCatalogue.ingredients
        val templates = NutritionCatalogue.templates

        val counts = LibraryCounts.of(exercises, dedicated, ingredients, templates)

        val expectedAnimated = exercises.count { exercise ->
            exercise.animationId.isNotBlank() &&
                dedicated.contains(exercise.animationId) &&
                PoseRegistry.getPoseConfig(exercise.animationId) != null
        }
        val expectedFoods = ingredients.count { ingredient -> ingredient.nameRes != 0 }
        val foodKeys = ingredients.map { ingredient -> ingredient.key }.toSet()
        val expectedDishes = templates.count { template ->
            template.mainIngredientKeys.isNotEmpty() &&
                template.mainIngredientKeys.all { key -> key in foodKeys }
        }

        assertEquals("animated exercises", expectedAnimated, counts.animatedExercises)
        assertEquals("foods (catalogue ingredients)", expectedFoods, counts.foods)
        assertEquals("dishes (catalogue templates)", expectedDishes, counts.dishes)
        assertTrue(counts.animatedExercises > 0)
        assertTrue(counts.foods > 0)
        assertTrue("every dish has a dish name", counts.dishes == templates.size)
        assertTrue("the catalogue is bigger than its animated subset", counts.foods > 10)
    }

    @Test
    fun anExerciseWithNoDedicatedAnimationIsNotCounted() {
        val animated = exercise("animated", "pushup_standard")
        val withoutAnimation = exercise("without-animation", "")
        val unknownAnimation = exercise("unknown-animation", "not_in_the_registry")

        val counts = LibraryCounts.of(
            exercises = listOf(animated, withoutAnimation, unknownAnimation),
            dedicatedAnimationIds = PoseRegistry.getDedicatedAnimationIds(),
            ingredients = NutritionCatalogue.ingredients,
            templates = NutritionCatalogue.templates
        )

        assertEquals("only the id the registry resolves is animated", 1, counts.animatedExercises)
    }

    @Test
    fun anUnnamedFoodIsNotCounted() {
        val named = ingredient("named", R.string.nutrition_ingredient_rice)
        val unnamed = ingredient("unnamed", 0)

        val counts = LibraryCounts.of(
            exercises = emptyList(),
            dedicatedAnimationIds = emptySet(),
            ingredients = listOf(named, unnamed),
            templates = emptyList()
        )

        assertEquals(1, counts.foods)
    }

    @Test
    fun aTemplateWithoutADishNameIsNotCounted() {
        val named = template("named", listOf("rice"))
        val unnamed = template("unnamed", emptyList())
        val dangling = template("dangling", listOf("not-a-food"))

        val counts = LibraryCounts.of(
            exercises = emptyList(),
            dedicatedAnimationIds = emptySet(),
            ingredients = listOf(ingredient("rice", R.string.nutrition_ingredient_rice)),
            templates = listOf(named, unnamed, dangling)
        )

        assertEquals("only a template whose name has foods behind it is a dish", 1, counts.dishes)
    }

    private fun exercise(id: String, animationId: String): Exercise = Exercise(
        id = id,
        familyId = "family",
        animationId = animationId,
        nameRes = R.string.ex_pushups,
        descriptionRes = R.string.ex_pushups_desc,
        techniqueRes = R.string.ex_pushups_tech,
        imageRes = null,
        sets = 1,
        reps = 1
    )

    private fun ingredient(key: String, nameRes: Int): NutritionIngredient = NutritionIngredient(
        key = key,
        nameRes = nameRes,
        group = NutritionShoppingGroup.CARBS,
        unit = NutritionQuantityUnit.GRAMS,
        preparationStyle = NutritionPreparationStyle.COOKED
    )

    private fun template(id: String, mainKeys: List<String>): NutritionMealTemplate = NutritionMealTemplate(
        id = id,
        type = NutritionMealType.LUNCH,
        mealType = MealType.BALANCED,
        ingredients = emptyList(),
        mainIngredientKeys = mainKeys,
        calories = 0,
        proteinGrams = 0
    )
}
