package com.monkfitness.app.ui.nutrition

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.data.model.NutritionNaming
import com.monkfitness.app.data.model.nutritionDishIngredientKeys
import com.monkfitness.app.data.model.nutritionDishName
import com.monkfitness.app.data.model.nutritionIngredientsByKey

/**
 * The dish's own name: its main foods joined by " + ", in the market's words when a market is chosen and
 * in the interface's own otherwise. No article and no "with" is added — a name is not a sentence.
 */
@Composable
fun nutritionDishTitle(meal: NutritionMeal, naming: NutritionNaming): String {
    val parts = nutritionDishIngredientKeys(meal).mapNotNull { key ->
        val ingredient = nutritionIngredientsByKey[key] ?: return@mapNotNull null
        naming.nameOf(ingredient)?.name?.replaceFirstChar { it.titlecase() }
            ?: stringResource(ingredient.nameRes)
    }
    return nutritionDishName(parts)
}
