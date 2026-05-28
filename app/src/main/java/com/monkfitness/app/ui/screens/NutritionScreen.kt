package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.NutritionDayPlan
import com.monkfitness.app.data.model.NutritionIngredientAmount
import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.data.model.NutritionQuantityUnit
import com.monkfitness.app.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate

@Composable
fun NutritionScreen(
    viewModel: MainViewModel,
    onOpenTodayPlan: () -> Unit,
    onOpenShoppingList: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val weightInput by viewModel.nutritionWeight.collectAsState()
    val heightInput by viewModel.nutritionHeight.collectAsState()
    val cycleLength by viewModel.nutritionCycleLength.collectAsState()
    val excludedFoods by viewModel.nutritionExcludedFoods.collectAsState()
    val availableProducts by viewModel.nutritionAvailableProducts.collectAsState()
    val plan by viewModel.nutritionPlan.collectAsState()
    val targets by viewModel.currentNutritionTargets.collectAsState()
    val currentProgramDay by viewModel.currentProgramDay.collectAsState()
    val activeCycle by viewModel.activeMealCycle.collectAsState()
    val pendingCycle by viewModel.pendingMealCycle.collectAsState()
    val showExpirationWarning by viewModel.shouldShowNutritionExpirationWarning.collectAsState()
    var showAvailableProductsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.nutritionMessageEvents.collectLatest { messageRes ->
            snackbarHostState.showSnackbar(context.getString(messageRes))
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.nutrition),
                style = MaterialTheme.typography.headlineLarge
            )

            if (cycleLength == 0) {
                DisabledNutritionCard()
                return@Column
            }

            if (showAvailableProductsDialog) {
                AvailableProductsDialog(
                    selectedProducts = availableProducts,
                    options = viewModel.nutritionExclusionOptions,
                    onDismiss = { showAvailableProductsDialog = false },
                    onGenerate = { selected, days ->
                        showAvailableProductsDialog = false
                        viewModel.generateNutritionFromAvailableProducts(selected, days)
                    }
                )
            }

            if (showExpirationWarning && activeCycle != null) {
                ExpirationWarningCard(
                    cycle = activeCycle!!,
                    onGenerateNext = viewModel::generateNextNutritionCycle,
                    onLater = viewModel::dismissNutritionExpirationWarning
                )
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = stringResource(R.string.nutrition_user_data), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            label = { Text(stringResource(R.string.nutrition_height_optional)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }

            ActiveCycleCard(activeCycle = activeCycle, pendingCycle = pendingCycle)

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = stringResource(R.string.nutrition_targets), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    NutritionTargetRow(
                        title = stringResource(R.string.nutrition_daily_calories),
                        value = stringResource(R.string.nutrition_kcal_format, targets.dailyCalories)
                    )
                    NutritionTargetRow(
                        title = stringResource(R.string.nutrition_current_program_day),
                        value = stringResource(R.string.nutrition_program_day_week, currentProgramDay, ((currentProgramDay - 1) / 7) + 1)
                    )
                    NutritionTargetRow(
                        title = stringResource(R.string.nutrition_protein_target),
                        value = stringResource(R.string.nutrition_protein_range_format, targets.proteinMinGrams, targets.proteinMaxGrams)
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = stringResource(R.string.nutrition_exclusions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = stringResource(R.string.nutrition_exclusions_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    viewModel.nutritionExclusionOptions.chunked(2).forEach { rowItems ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { ingredient ->
                                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Checkbox(
                                        checked = ingredient.key in excludedFoods,
                                        onCheckedChange = { viewModel.toggleNutritionExcludedFood(ingredient.key) }
                                    )
                                    Text(
                                        text = stringResource(ingredient.nameRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenTodayPlan, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nutrition_open_today_plan))
                }
                OutlinedButton(onClick = onOpenShoppingList, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nutrition_open_shopping_list))
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::generateNextNutritionCycle, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nutrition_generate_next_cycle))
                }
                OutlinedButton(onClick = { showAvailableProductsDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nutrition_generate_from_products))
                }
            }

            Text(
                text = stringResource(R.string.nutrition_plan_preview_title, plan.days.size),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            plan.days.forEach { day ->
                NutritionDayCard(day = day)
            }
        }
    }
}

@Composable
private fun DisabledNutritionCard() {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.nutrition_disabled), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = stringResource(R.string.nutrition_disabled_message), color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun ExpirationWarningCard(
    cycle: MealCycle,
    onGenerateNext: () -> Unit,
    onLater: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.nutrition_cycle_ends_tomorrow, cycle.durationDays),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onGenerateNext, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nutrition_generate_next_cycle))
                }
                OutlinedButton(onClick = onLater, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nutrition_later))
                }
            }
        }
    }
}

@Composable
private fun ActiveCycleCard(activeCycle: MealCycle?, pendingCycle: MealCycle?) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.nutrition_cycle_status), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = if (activeCycle == null) stringResource(R.string.nutrition_no_active_cycle)
                else stringResource(R.string.nutrition_active_cycle_summary, activeCycle.durationDays, activeCycle.startDate),
                color = MaterialTheme.colorScheme.secondary
            )
            if (pendingCycle != null) {
                Text(
                    text = stringResource(R.string.nutrition_pending_cycle_summary, pendingCycle.durationDays, pendingCycle.startDate),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun NutritionTargetRow(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NutritionDayCard(day: NutritionDayPlan) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = stringResource(R.string.day_display, day.dayNumber), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = stringResource(day.dayType.labelRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = stringResource(R.string.nutrition_program_day_week, day.programDay, day.week),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = stringResource(R.string.nutrition_meal_summary, day.totalCalories, day.totalProteinGrams),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            day.meals.forEach { meal -> NutritionMealCard(meal = meal) }
        }
    }
}

@Composable
private fun NutritionMealCard(meal: NutritionMeal) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(meal.type.labelRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = stringResource(meal.mealType.labelRes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (meal.optional) {
                    Text(text = stringResource(R.string.nutrition_optional), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            meal.ingredients.forEach { ingredient ->
                Text(text = "• ${ingredientText(ingredient)}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = stringResource(R.string.nutrition_meal_summary, meal.calories, meal.proteinGrams),
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

@Composable
private fun AvailableProductsDialog(
    selectedProducts: Set<String>,
    options: List<com.monkfitness.app.data.model.NutritionIngredient>,
    onDismiss: () -> Unit,
    onGenerate: (Set<String>, Int) -> Unit
) {
    var selected by remember(selectedProducts) { mutableStateOf(selectedProducts) }
    var duration by remember { mutableStateOf(3) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onGenerate(selected, duration) }) {
                Text(stringResource(R.string.nutrition_generate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.nutrition_later))
            }
        },
        title = { Text(stringResource(R.string.nutrition_generate_from_products)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nutrition_available_products_hint))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 7).forEach { days ->
                        OutlinedButton(onClick = { duration = days }) {
                            Text(if (duration == days) "✓ $days" else days.toString())
                        }
                    }
                }
                options.chunked(2).forEach { rowItems ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { ingredient ->
                            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Checkbox(
                                    checked = ingredient.key in selected,
                                    onCheckedChange = {
                                        selected = if (ingredient.key in selected) selected - ingredient.key else selected + ingredient.key
                                    }
                                )
                                Text(text = stringResource(ingredient.nameRes), modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    )
}
