package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.NutritionDayPlan
import com.monkfitness.app.data.model.NutritionIngredientAmount
import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionTodayScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val todayPlan by viewModel.todayNutritionPlan.collectAsState()
    val activeCycle by viewModel.activeMealCycle.collectAsState()
    val pendingCycle by viewModel.pendingMealCycle.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nutrition_today_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.previous))
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
                text = stringResource(R.string.nutrition_today_desc_cycle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            todayPlan?.let { day ->
                NutritionTodaySummaryCard(
                    day = day,
                    cycleSummary = activeCycle?.let { stringResource(R.string.nutrition_active_cycle_summary, it.durationDays, it.startDate) }.orEmpty(),
                    pendingSummary = pendingCycle?.let { stringResource(R.string.nutrition_pending_cycle_summary, it.durationDays, it.startDate) },
                    onGenerateNextCycle = viewModel::generateNextNutritionCycle
                )

                day.meals.forEach { meal ->
                    NutritionTodayMealCard(
                        meal = meal,
                        onReplace = { viewModel.replaceNutritionMeal(day.programDay, meal.type) }
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
    cycleSummary: String,
    pendingSummary: String?,
    onGenerateNextCycle: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = stringResource(R.string.nutrition_today_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = cycleSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            if (pendingSummary != null) {
                Text(text = pendingSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(text = stringResource(R.string.nutrition_program_day_week, day.programDay, day.week), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Text(text = stringResource(day.dayType.labelRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(text = stringResource(R.string.nutrition_meal_summary, day.totalCalories, day.totalProteinGrams), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = onGenerateNextCycle, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nutrition_generate_next_cycle))
            }
        }
    }
}

@Composable
private fun NutritionTodayMealCard(
    meal: NutritionMeal,
    onReplace: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = stringResource(meal.type.labelRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = stringResource(meal.mealType.labelRes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(text = stringResource(R.string.nutrition_today_foods), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            meal.ingredients.forEach { ingredient ->
                Text(text = "• ${todayIngredientText(ingredient)}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(text = stringResource(R.string.nutrition_today_instructions), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            com.monkfitness.app.data.model.getTodayCookingInstructionResIds(meal).forEachIndexed { index, stepRes ->
                Text(text = "${index + 1}. ${stringResource(stepRes)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Button(onClick = onReplace, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nutrition_replace_meal))
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
