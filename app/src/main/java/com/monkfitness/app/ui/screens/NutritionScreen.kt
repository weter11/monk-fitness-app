package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.NutritionDayPlan
import com.monkfitness.app.data.model.NutritionIngredientAmount
import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.data.model.NutritionQuantityUnit
import com.monkfitness.app.data.model.calculateMuscleGainNutritionTargets
import com.monkfitness.app.data.model.generateThreeDayMuscleGainPlan
import com.monkfitness.app.viewmodel.MainViewModel

@Composable
fun NutritionScreen(
    viewModel: MainViewModel,
    onOpenShoppingList: () -> Unit
) {
    val weightInput by viewModel.nutritionWeight.collectAsState()
    val heightInput by viewModel.nutritionHeight.collectAsState()
    val planSeed by viewModel.nutritionPlanSeed.collectAsState()

    val weightKg = weightInput.toIntOrNull()
    val heightCm = heightInput.toIntOrNull()
    val targets = calculateMuscleGainNutritionTargets(weightKg, heightCm)
    val plan = remember(planSeed) { generateThreeDayMuscleGainPlan(planSeed) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.nutrition),
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = stringResource(R.string.nutrition_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.nutrition_user_data),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = weightInput,
                        onValueChange = { viewModel.setNutritionWeight(it.filter(Char::isDigit).take(3)) },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.nutrition_weight)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = heightInput,
                        onValueChange = { viewModel.setNutritionHeight(it.filter(Char::isDigit).take(3)) },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.nutrition_height)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Text(
                    text = stringResource(
                        R.string.nutrition_goal_value,
                        stringResource(R.string.nutrition_goal_gain_mass)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.nutrition_targets),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                NutritionTargetRow(
                    title = stringResource(R.string.nutrition_daily_calories),
                    value = stringResource(R.string.nutrition_kcal_format, targets.dailyCalories)
                )
                NutritionTargetRow(
                    title = stringResource(R.string.nutrition_protein_target),
                    value = stringResource(
                        R.string.nutrition_protein_range_format,
                        targets.proteinMinGrams,
                        targets.proteinMaxGrams
                    )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.regenerateNutritionPlan() },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.nutrition_regenerate_plan))
            }
            OutlinedButton(
                onClick = onOpenShoppingList,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.nutrition_open_shopping_list))
            }
        }

        Text(
            text = stringResource(R.string.nutrition_three_day_plan),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        plan.days.forEach { day ->
            NutritionDayCard(day = day)
        }
    }
}

@Composable
private fun NutritionTargetRow(
    title: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun NutritionDayCard(day: NutritionDayPlan) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.day_display, day.dayNumber),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(
                    R.string.nutrition_meal_summary,
                    day.totalCalories,
                    day.totalProteinGrams
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            day.meals.forEach { meal ->
                NutritionMealCard(meal = meal)
            }
        }
    }
}

@Composable
private fun NutritionMealCard(meal: NutritionMeal) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(meal.type.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (meal.optional) {
                    Text(
                        text = stringResource(R.string.nutrition_optional),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            meal.ingredients.forEach { ingredient ->
                Text(
                    text = "• ${ingredientText(ingredient)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = stringResource(
                    R.string.nutrition_meal_summary,
                    meal.calories,
                    meal.proteinGrams
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun ingredientText(ingredientAmount: NutritionIngredientAmount): String {
    val quantity = when (ingredientAmount.ingredient.unit) {
        NutritionQuantityUnit.GRAMS -> stringResource(R.string.nutrition_quantity_grams, ingredientAmount.amount)
        NutritionQuantityUnit.MILLILITERS -> stringResource(R.string.nutrition_quantity_milliliters, ingredientAmount.amount)
        NutritionQuantityUnit.PIECES -> stringResource(R.string.nutrition_quantity_pieces, ingredientAmount.amount)
    }

    return "${stringResource(ingredientAmount.ingredient.nameRes)} — $quantity"
}
