package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.NutritionDayPlan
import com.monkfitness.app.data.model.NutritionIngredientAmount
import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.data.model.calculateNutritionCompletionPercent
import com.monkfitness.app.data.model.generateThreeDayMuscleGainPlan
import com.monkfitness.app.data.model.getTodayCookingInstructionResIds
import com.monkfitness.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionTodayScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val planSeed by viewModel.nutritionPlanSeed.collectAsState()
    val currentProgramDay by viewModel.currentProgramDay.collectAsState()
    val completedMeals by viewModel.completedNutritionMeals.collectAsState()
    val plan = remember(planSeed, currentProgramDay) {
        generateThreeDayMuscleGainPlan(
            seed = planSeed,
            startDay = currentProgramDay,
            workoutTypeForDay = viewModel::getWorkoutTypeForDay
        )
    }
    val todayPlan = plan.days.firstOrNull()
    val completionPercent = todayPlan?.let { calculateNutritionCompletionPercent(it, completedMeals) } ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nutrition_today_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.previous)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.nutrition_today_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            todayPlan?.let { day ->
                NutritionTodaySummaryCard(
                    day = day,
                    completionPercent = completionPercent
                )

                day.meals.forEach { meal ->
                    NutritionTodayMealCard(
                        meal = meal,
                        checked = meal.type.key in completedMeals,
                        onCheckedChange = { checked ->
                            viewModel.setNutritionMealCompleted(meal.type, checked)
                        }
                    )
                }
            } ?: Text(
                text = stringResource(R.string.nutrition_today_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun NutritionTodaySummaryCard(
    day: NutritionDayPlan,
    completionPercent: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.nutrition_today_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.nutrition_program_day_week, day.programDay, day.week),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(day.dayType.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.nutrition_kcal_format, day.totalCalories),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(R.string.nutrition_completion_percent, completionPercent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            LinearProgressIndicator(
                progress = { completionPercent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NutritionTodayMealCard(
    meal: NutritionMeal,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = onCheckedChange
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                }
                Text(
                    text = stringResource(R.string.nutrition_kcal_format, meal.calories),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = stringResource(R.string.nutrition_today_foods),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            meal.ingredients.forEach { ingredient ->
                Text(
                    text = "• ${todayIngredientText(ingredient)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = stringResource(R.string.nutrition_today_instructions),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            getTodayCookingInstructionResIds(meal).forEachIndexed { index, stepRes ->
                Text(
                    text = "${index + 1}. ${stringResource(stepRes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun todayIngredientText(ingredientAmount: NutritionIngredientAmount): String {
    return stringResource(
        R.string.nutrition_prepared_ingredient,
        stringResource(ingredientAmount.ingredient.nameRes),
        stringResource(ingredientAmount.ingredient.preparationStyle.labelRes)
    )
}
