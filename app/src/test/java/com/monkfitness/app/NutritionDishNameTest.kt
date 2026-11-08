package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.data.model.NutritionMealType
import com.monkfitness.app.data.model.MealType
import com.monkfitness.app.data.model.nutritionDishIngredientKeys
import com.monkfitness.app.data.model.nutritionDishName
import com.monkfitness.app.data.model.nutritionIngredientsByKey
import com.monkfitness.app.data.model.parseCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * A dish is named after its own foods and nothing else: two or three of them, joined by a plain
 * separator. The name must not acquire a language's article or the word "with", because the same name
 * is shown in seven interfaces and four market languages.
 */
class NutritionDishNameTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = NutritionCatalogueFixture.load()
    }

    private val catalogue = parseCatalogue(NutritionCatalogueFixture.asset("catalogue.json").readText())

    @Test
    fun namesJoinWithAPlainSeparatorAndAddNoGrammar() {
        assertEquals("Oats + Yogurt + Banana", nutritionDishName(listOf("Oats", "Yogurt", "Banana")))
        assertEquals("Rice + Salmon", nutritionDishName(listOf("Rice", " ", "Salmon")))
        assertEquals("", nutritionDishName(emptyList()))
    }

    @Test
    fun everyDishIsNamedAfterTwoOrThreeOfItsOwnIngredients() {
        catalogue.templates.forEach { template ->
            val main = template.mainIngredientKeys
            assertTrue("${template.id}: named after ${main.size} foods", main.size in 2..3)
            assertEquals("${template.id}: a food named twice", main.size, main.toSet().size)
            main.forEach { key ->
                assertTrue(
                    "${template.id}: is named after $key, which it does not contain",
                    template.ingredients.any { it.ingredient.key == key }
                )
            }
        }
    }

    @Test
    fun aDishNameReadsAsItsFoodsInAnyOfTheCataloguesLanguages() {
        // Naming is per language, so no composed name may carry a word that belongs to one of them only.
        val separators = catalogue.templates.map { template ->
            nutritionDishName(template.mainIngredientKeys.map { key -> nutritionIngredientsByKey.getValue(key).key })
        }
        separators.forEach { name ->
            assertTrue("a name lost its separator: $name", name.contains(" + ") || !name.contains("+"))
            assertTrue("a name reads as a sentence: $name", !name.contains(" with "))
        }
    }

    @Test
    fun aMealStoredWithoutItsTemplateStillReadsAsADish() {
        val meal = NutritionMeal(
            templateId = "a_template_this_build_no_longer_has",
            type = NutritionMealType.LUNCH,
            mealType = MealType.BALANCED,
            ingredients = listOf("chicken", "rice", "apple", "nuts").map { key ->
                com.monkfitness.app.data.model.NutritionIngredientAmount(
                    nutritionIngredientsByKey.getValue(key), 100
                )
            },
            calories = 500,
            proteinGrams = 40
        )

        assertEquals(listOf("chicken", "rice", "apple"), nutritionDishIngredientKeys(meal))
    }

    @Test
    fun aKnownTemplateNamesItsMealAfterItsOwnMainFoods() {
        val template = catalogue.templatesById.getValue("dinner_balanced_2")
        val meal = NutritionMeal(
            templateId = template.id,
            type = template.type,
            mealType = template.mealType,
            ingredients = template.ingredients,
            calories = template.calories,
            proteinGrams = template.proteinGrams
        )

        assertEquals(template.mainIngredientKeys, nutritionDishIngredientKeys(meal))
    }
}
