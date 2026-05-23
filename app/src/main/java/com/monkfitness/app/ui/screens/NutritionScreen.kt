package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.NutritionMealPlan
import com.monkfitness.app.data.model.calculateMuscleGainNutritionTargets
import com.monkfitness.app.data.model.muscleGainMealPlan
import com.monkfitness.app.ui.components.MonkProgressIndicator
import com.monkfitness.app.viewmodel.MainViewModel

@Composable
fun NutritionScreen(viewModel: MainViewModel) {
    val weightInput by viewModel.nutritionWeight.collectAsState()
    val heightInput by viewModel.nutritionHeight.collectAsState()
    val completedMeals by viewModel.completedNutritionMeals.collectAsState()

    val weightKg = weightInput.toIntOrNull()
    val heightCm = heightInput.toIntOrNull()
    val targets = calculateMuscleGainNutritionTargets(weightKg, heightCm)
    val meals = muscleGainMealPlan()
    val completion = completedMeals.size / meals.size.toFloat()

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

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.nutrition_completion),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                MonkProgressIndicator(progress = completion, modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(
                        R.string.nutrition_completion_percent,
                        (completion * 100).toInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Text(
            text = stringResource(R.string.nutrition_daily_plan),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        meals.forEach { meal ->
            NutritionMealCard(
                meal = meal,
                checked = meal.type.key in completedMeals,
                onCheckedChange = { isChecked ->
                    viewModel.setNutritionMealCompleted(meal.type, isChecked)
                }
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.nutrition_tips),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• ${stringResource(R.string.nutrition_tip_nuts)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• ${stringResource(R.string.nutrition_tip_post_workout)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
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
private fun NutritionMealCard(
    meal: NutritionMealPlan,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(meal.type.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                meal.options.forEach { optionRes ->
                    Text(
                        text = "• ${stringResource(optionRes)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (checked) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
