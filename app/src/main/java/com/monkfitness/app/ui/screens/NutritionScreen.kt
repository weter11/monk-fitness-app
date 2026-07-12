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
import kotlin.math.roundToInt
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.NutritionDayPlan
import com.monkfitness.app.data.model.NutritionIngredientAmount
import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.data.model.NutritionQuantityUnit
import com.monkfitness.app.data.model.NutritionPlan
import com.monkfitness.app.data.model.NutritionShoppingGroup
import com.monkfitness.app.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings

@Composable
fun NutritionScreen(
    viewModel: MainViewModel,
    onOpenTodayPlan: () -> Unit,
    onOpenShoppingList: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val weightInput by viewModel.nutritionWeight.collectAsState()
    val heightInput by viewModel.nutritionHeight.collectAsState()
    val cycleLength by viewModel.nutritionCycleLength.collectAsState()
    val excludedFoods by viewModel.nutritionExcludedFoods.collectAsState()
    val availableProducts by viewModel.nutritionAvailableProducts.collectAsState()
    val showExcludedProductsInNutrition by viewModel.showExcludedProductsInNutrition.collectAsState()
    val plan by viewModel.nutritionPlan.collectAsState()
    val targets by viewModel.currentNutritionTargets.collectAsState()
    val currentProgramDay by viewModel.currentProgramDay.collectAsState()
    val activeCycle by viewModel.activeMealCycle.collectAsState()
    val pendingCycle by viewModel.pendingMealCycle.collectAsState()
    val showExpirationWarning by viewModel.shouldShowNutritionExpirationWarning.collectAsState()
    var showAvailableProductsDialog by remember { mutableStateOf(false) }
    val previewPlan by viewModel.previewNutritionPlan.collectAsState()
    val currentDate by viewModel.currentDate.collectAsState()
    var showPreviewDialog by remember { mutableStateOf(false) }

    LaunchedEffect(previewPlan) {
        if (previewPlan != null) {
            showPreviewDialog = true
        } else {
            showPreviewDialog = false
        }
    }

    if (showPreviewDialog && previewPlan != null) {
        NutritionPreviewDialog(
            previewPlan = previewPlan!!,
            currentPlan = plan,
            activeCycle = activeCycle,
            availableProducts = availableProducts,
            today = currentDate,
            onDismiss = { showPreviewDialog = false },
            onAccept = {
                viewModel.savePreviewCycle()
                showPreviewDialog = false
            },
            onDiscard = {
                viewModel.clearPreviewCycle()
                showPreviewDialog = false
            },
            onRangeSelect = viewModel::previewNextCycle
        )
    }

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

            if (showExcludedProductsInNutrition) {
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
                                            onCheckedChange = null
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
            } else {
                val formattedRestrictions = if (excludedFoods.isEmpty()) {
                    stringResource(R.string.diet_restrictions_none)
                } else {
                    excludedFoods.mapNotNull { key ->
                        viewModel.nutritionExclusionOptions.find { it.key == key }?.let {
                            stringResource(it.nameRes)
                        }
                    }.joinToString(", ")
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSettings() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.diet_restrictions_summary, formattedRestrictions),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                            tint = MaterialTheme.colorScheme.primary
                        )
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

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.previewNextCycle(cycleLength.coerceIn(1, 7)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Preview Next Cycle")
                }
            }

            if (previewPlan != null && !showPreviewDialog) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Active Preview Session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("You have a generated future cycle preview in memory.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { showPreviewDialog = true }) {
                                Text("Resume Preview")
                            }
                            OutlinedButton(onClick = viewModel::clearPreviewCycle) {
                                Text("Discard")
                            }
                        }
                    }
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

@Composable
fun NutritionPreviewDialog(
    previewPlan: NutritionPlan,
    currentPlan: NutritionPlan,
    activeCycle: com.monkfitness.app.data.model.MealCycle?,
    availableProducts: Set<String>,
    today: java.time.LocalDate,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
    onDiscard: () -> Unit,
    onRangeSelect: (Int) -> Unit
) {
    var showConfirmationSummary by remember { mutableStateOf(false) }

    fun formatPlanPeriod(startDateStr: String, duration: Int, today: java.time.LocalDate): String {
        val start = runCatching { java.time.LocalDate.parse(startDateStr) }.getOrDefault(today)
        val end = start.plusDays(duration.toLong() - 1)

        val startLabel = if (start == today) "Today" else start.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val endLabel = end.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        return "$startLabel → $endLabel"
    }

    val currentPeriodText = if (activeCycle != null) {
        formatPlanPeriod(activeCycle.startDate, activeCycle.durationDays, today)
    } else {
        "None"
    }

    val previewStartDate = activeCycle?.let {
        val start = runCatching { java.time.LocalDate.parse(it.startDate) }.getOrDefault(today)
        start.plusDays(it.durationDays.toLong())
    } ?: today

    val previewPeriodText = formatPlanPeriod(previewStartDate.toString(), previewPlan.days.size, today)

    if (showConfirmationSummary) {
        AlertDialog(
            onDismissRequest = { showConfirmationSummary = false },
            confirmButton = {
                Button(onClick = {
                    showConfirmationSummary = false
                    onAccept()
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmationSummary = false }) {
                    Text("Go Back")
                }
            },
            title = { Text("Confirm New Cycle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This will:", fontWeight = FontWeight.Bold)
                    Text("• replace your future nutrition plan")
                    Text("• update the shopping list")
                    Text("• preserve today's meals")
                    Text("• activate the new cycle starting tomorrow")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Are you sure you want to proceed?", style = MaterialTheme.typography.bodyMedium)
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDiscard) {
                    Text("Discard")
                }
                Button(onClick = { showConfirmationSummary = true }) {
                    Text("Apply")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Preview Next Cycle",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Time Range Toggle
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select Preview Duration:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 3, 7).forEach { days ->
                            val isSelected = previewPlan.days.size == days
                            FilterChip(
                                selected = isSelected,
                                onClick = { onRangeSelect(days) },
                                label = { Text("$days Day${if (days > 1) "s" else ""}") }
                            )
                        }
                    }
                }

                // Preview Period Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Plan Periods", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Current Plan:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(currentPeriodText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Preview:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(previewPeriodText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Cycle Summary Card (as requested)
                val totalRecipes = previewPlan.days.flatMap { it.meals }
                val uniqueRecipesCount = totalRecipes.map { it.templateId }.distinct().size
                val repeatedRecipesCount = totalRecipes.size - uniqueRecipesCount
                val shoppingItems = previewPlan.shoppingList.values.flatten()
                val availableCount = shoppingItems.count { it.ingredient.key in availableProducts }
                val missingCount = shoppingItems.count { it.ingredient.key !in availableProducts }

                // Variety Score calculation
                val uniqueRatio = if (totalRecipes.isNotEmpty()) uniqueRecipesCount.toFloat() / totalRecipes.size else 0f
                val varietyIndicator = when {
                    uniqueRatio >= 0.8f -> "Excellent Variety"
                    uniqueRatio >= 0.5f -> "Good Variety"
                    else -> "Low Variety"
                }
                val varietyColor = when {
                    uniqueRatio >= 0.8f -> MaterialTheme.colorScheme.primary
                    uniqueRatio >= 0.5f -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                }

                // Shopping Indicator
                val shoppingIndicator = if (missingCount == 0) "Ready to cook" else "Need shopping"
                val shoppingIndicatorColor = if (missingCount == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cycle Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Duration:", style = MaterialTheme.typography.bodyMedium)
                            Text("${previewPlan.days.size} days", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Recipes:", style = MaterialTheme.typography.bodyMedium)
                            Text("${totalRecipes.size}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Unique recipes:", style = MaterialTheme.typography.bodyMedium)
                            Text("${uniqueRecipesCount}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Repeated recipes:", style = MaterialTheme.typography.bodyMedium)
                            Text("${repeatedRecipesCount}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Products required:", style = MaterialTheme.typography.bodyMedium)
                            Text("${shoppingItems.size}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Already available:", style = MaterialTheme.typography.bodyMedium)
                            Text("$availableCount", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Need to buy:", style = MaterialTheme.typography.bodyMedium)
                            Text("$missingCount", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Variety Score:", style = MaterialTheme.typography.bodyMedium)
                            Text(varietyIndicator, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = varietyColor)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Shopping Status:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (missingCount == 0) shoppingIndicator else "$shoppingIndicator ($missingCount missing)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = shoppingIndicatorColor
                            )
                        }

                        // Optional comparison with current cycle
                        if (currentPlan.days.isNotEmpty()) {
                            val currentRecipes = currentPlan.days.flatMap { it.meals }
                            val currentTemplates = currentRecipes.map { it.templateId }.toSet()
                            val previewTemplates = totalRecipes.map { it.templateId }.toSet()

                            val newRecipesCount = previewTemplates.count { it !in currentTemplates }
                            val repeatedFromPrevCount = previewTemplates.count { it in currentTemplates }

                            val currentIngredients = currentRecipes.flatMap { it.ingredients }.map { it.ingredient.key }.toSet()
                            val previewIngredients = totalRecipes.flatMap { it.ingredients }.map { it.ingredient.key }.toSet()

                            val newIngredientsCount = previewIngredients.count { it !in currentIngredients }

                            fun getPlanProtein(plan: NutritionPlan): Int =
                                if (plan.days.isEmpty()) 0 else plan.days.map { it.totalProteinGrams }.average().roundToInt()

                            fun getPlanCalories(plan: NutritionPlan): Int =
                                if (plan.days.isEmpty()) 0 else plan.days.map { it.totalCalories }.average().roundToInt()

                            fun getPlanCarbs(plan: NutritionPlan): Int {
                                if (plan.days.isEmpty()) return 0
                                val totalAmountOfCarbIngredients = plan.days.flatMap { it.meals }.flatMap { it.ingredients }
                                    .filter { it.ingredient.group == NutritionShoppingGroup.CARBS }
                                    .sumOf { it.amount }
                                return (totalAmountOfCarbIngredients / 8).coerceIn(100, 400)
                            }

                            fun getPlanFat(plan: NutritionPlan): Int {
                                if (plan.days.isEmpty()) return 0
                                val totalAmountOfFatIngredients = plan.days.flatMap { it.meals }.flatMap { it.ingredients }
                                    .filter { it.ingredient.group == NutritionShoppingGroup.FATS }
                                    .sumOf { it.amount }
                                return (totalAmountOfFatIngredients / 4).coerceIn(40, 120)
                            }

                            fun getPlanFiber(plan: NutritionPlan): Int {
                                if (plan.days.isEmpty()) return 0
                                val totalAmountOfVegIngredients = plan.days.flatMap { it.meals }.flatMap { it.ingredients }
                                    .filter { it.ingredient.group == NutritionShoppingGroup.FRUITS_VEGETABLES }
                                    .sumOf { it.amount }
                                return (totalAmountOfVegIngredients / 15).coerceIn(15, 50)
                            }

                            val previewProtein = getPlanProtein(previewPlan)
                            val currentProtein = getPlanProtein(currentPlan)
                            val diffProtein = previewProtein - currentProtein

                            val previewCalories = getPlanCalories(previewPlan)
                            val currentCalories = getPlanCalories(currentPlan)
                            val diffCalories = previewCalories - currentCalories

                            val previewCarbs = getPlanCarbs(previewPlan)
                            val currentCarbs = getPlanCarbs(currentPlan)
                            val diffCarbs = previewCarbs - currentCarbs

                            val previewFat = getPlanFat(previewPlan)
                            val currentFat = getPlanFat(currentPlan)
                            val diffFat = previewFat - currentFat

                            val previewFiber = getPlanFiber(previewPlan)
                            val currentFiber = getPlanFiber(currentPlan)
                            val diffFiber = previewFiber - currentFiber

                            val previewShoppingCount = previewPlan.shoppingList.values.flatten().size
                            val currentShoppingCount = currentPlan.shoppingList.values.flatten().size
                            val diffShopping = previewShoppingCount - currentShoppingCount

                            fun formatDiff(diff: Int, unit: String): String {
                                val sign = if (diff >= 0) "+" else ""
                                return "$sign$diff $unit"
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))
                            Spacer(modifier = Modifier.height(4.dp))

                            Text("Comparison with Current Cycle", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("New recipes:", style = MaterialTheme.typography.bodySmall)
                                Text("+$newRecipesCount", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Repeated from previous cycle:", style = MaterialTheme.typography.bodySmall)
                                Text("$repeatedFromPrevCount", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("New ingredients introduced:", style = MaterialTheme.typography.bodySmall)
                                Text("$newIngredientsCount", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Ingredients already stocked:", style = MaterialTheme.typography.bodySmall)
                                Text("$availableCount", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Missing ingredients:", style = MaterialTheme.typography.bodySmall)
                                Text("$missingCount", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Macro Difference Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Calories:", style = MaterialTheme.typography.bodySmall)
                                Text(formatDiff(diffCalories, "kcal"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Protein:", style = MaterialTheme.typography.bodySmall)
                                Text(formatDiff(diffProtein, "g"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Carbohydrates:", style = MaterialTheme.typography.bodySmall)
                                Text(formatDiff(diffCarbs, "g"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Fat:", style = MaterialTheme.typography.bodySmall)
                                Text(formatDiff(diffFat, "g"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Fiber:", style = MaterialTheme.typography.bodySmall)
                                Text(formatDiff(diffFiber, "g"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Shopping items:", style = MaterialTheme.typography.bodySmall)
                                Text(formatDiff(diffShopping, "products"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Daily Menu Section
                Text("Daily Menu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                previewPlan.days.forEach { day ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Day ${day.dayNumber}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(day.dayType.labelRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Text("Target Calories: ${day.targetCalories} kcal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

                            day.meals.forEach { meal ->
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(stringResource(meal.type.labelRes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    meal.ingredients.forEach { ingredientAmount ->
                                        val quantity = when (ingredientAmount.ingredient.unit) {
                                            NutritionQuantityUnit.GRAMS -> stringResource(R.string.nutrition_quantity_grams, ingredientAmount.amount)
                                            NutritionQuantityUnit.MILLILITERS -> stringResource(R.string.nutrition_quantity_milliliters, ingredientAmount.amount)
                                            NutritionQuantityUnit.PIECES -> stringResource(R.string.nutrition_quantity_pieces, ingredientAmount.amount)
                                        }
                                        Text(
                                            text = "• ${stringResource(ingredientAmount.ingredient.nameRes)} — $quantity",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    // Recipe / Cooking instructions
                                    Text("Instructions:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    com.monkfitness.app.data.model.getTodayCookingInstructionResIds(meal).forEachIndexed { i, stepRes ->
                                        Text("${i + 1}. ${stringResource(stepRes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }
                }

                // Required Products Section
                Text("Required Products Preview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                val alreadyAvailable = shoppingItems.filter { it.ingredient.key in availableProducts }
                val needToBuy = shoppingItems.filter { it.ingredient.key !in availableProducts }

                val substitutions = needToBuy.mapNotNull { item ->
                    when (item.ingredient.key) {
                        "chicken" -> "Turkey instead of chicken breast"
                        "cottage_cheese" -> "Greek yogurt instead of cottage cheese"
                        "tuna" -> "Salmon instead of tuna"
                        "eggs" -> "Tofu instead of whole eggs"
                        "rice" -> "Quinoa instead of rice"
                        "oats" -> "Spelt flakes instead of oats"
                        "potatoes" -> "Sweet potatoes instead of potatoes"
                        "buckwheat" -> "Brown rice instead of buckwheat"
                        "yogurt" -> "Kefir instead of yogurt"
                        "banana" -> "Mango instead of banana"
                        "apple" -> "Pear instead of apple"
                        "nuts" -> "Seeds instead of nuts"
                        else -> null
                    }
                }.distinct()

                // Section 1: Already available
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Already available:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        if (alreadyAvailable.isEmpty()) {
                            Text("None", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            alreadyAvailable.forEach { item ->
                                val quantity = when (item.ingredient.unit) {
                                    NutritionQuantityUnit.GRAMS -> stringResource(R.string.nutrition_quantity_grams, item.totalAmount)
                                    NutritionQuantityUnit.MILLILITERS -> stringResource(R.string.nutrition_quantity_milliliters, item.totalAmount)
                                    NutritionQuantityUnit.PIECES -> stringResource(R.string.nutrition_quantity_pieces, item.totalAmount)
                                }
                                Text("✓ ${stringResource(item.ingredient.nameRes)} — $quantity", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Section 2: Need to buy
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Need to buy:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        if (needToBuy.isEmpty()) {
                            Text("None", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            needToBuy.forEach { item ->
                                val quantity = when (item.ingredient.unit) {
                                    NutritionQuantityUnit.GRAMS -> stringResource(R.string.nutrition_quantity_grams, item.totalAmount)
                                    NutritionQuantityUnit.MILLILITERS -> stringResource(R.string.nutrition_quantity_milliliters, item.totalAmount)
                                    NutritionQuantityUnit.PIECES -> stringResource(R.string.nutrition_quantity_pieces, item.totalAmount)
                                }
                                Text("• ${stringResource(item.ingredient.nameRes)} — $quantity", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Section 3: Optional substitutions (when applicable)
                if (substitutions.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Optional substitutions:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            substitutions.forEach { substitution ->
                                Text("• $substitution", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        }
    )
}
