package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import kotlin.math.roundToInt
import com.monkfitness.app.data.model.BiologicalSex
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.NutritionCalorieLimit
import com.monkfitness.app.data.model.NutritionDayEnergy
import com.monkfitness.app.data.model.NutritionEnergyOutcome
import com.monkfitness.app.data.model.NutritionEnergyPhase
import com.monkfitness.app.data.model.NutritionProfileError
import com.monkfitness.app.data.model.NutritionProfileField
import com.monkfitness.app.data.model.NutritionWeightSource
import com.monkfitness.app.data.model.nutritionProfileError
import com.monkfitness.app.data.model.NUTRITION_PROGRAM_DAY_CAP
import com.monkfitness.app.data.model.NutritionDayPlan
import com.monkfitness.app.data.model.NutritionDayStatus
import com.monkfitness.app.data.model.NutritionIngredientAmount
import com.monkfitness.app.data.model.NutritionMeal
import com.monkfitness.app.data.model.NutritionQuantityUnit
import com.monkfitness.app.data.model.NutritionPlan
import com.monkfitness.app.data.model.NutritionShoppingGroup
import com.monkfitness.app.data.model.calorieTargetGap
import com.monkfitness.app.data.model.nutritionDietaryExcludedKeys
import com.monkfitness.app.data.model.isProgramDayCapped
import com.monkfitness.app.data.model.nutritionVariety
import com.monkfitness.app.data.model.orderedForDisplay
import com.monkfitness.app.data.model.statusOn
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
import com.monkfitness.app.data.model.NutritionNaming
import com.monkfitness.app.ui.nutrition.nutritionDishTitle
import com.monkfitness.app.ui.nutrition.rememberNutritionNaming
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
    val ageInput by viewModel.nutritionAge.collectAsState()
    val goalInput by viewModel.nutritionGoalWeight.collectAsState()
    val sex by viewModel.nutritionSex.collectAsState()
    val energyOutcome by viewModel.nutritionEnergy.collectAsState()
    val todayEnergyState by viewModel.todayNutritionEnergy.collectAsState()
    // A plain local, so the null check below narrows the type for the whole card.
    val todayEnergy = todayEnergyState
    val reportionOffered by viewModel.nutritionReportionOffered.collectAsState()
    val profilePrompt by viewModel.nutritionProfilePrompt.collectAsState()
    var showReportionDialog by remember { mutableStateOf(false) }
    val cycleLength by viewModel.nutritionCycleLength.collectAsState()
    val excludedFoods by viewModel.nutritionExcludedFoods.collectAsState()
    val nutritionNoPork by viewModel.nutritionNoPork.collectAsState()
    val nutritionLactoseFree by viewModel.nutritionLactoseFree.collectAsState()
    // Both read-only surfaces state every food a new plan avoids; the editable list in Settings stays the
    // user's own exclusions, so a filter can never edit it.
    val effectiveExcludedFoods = excludedFoods + nutritionDietaryExcludedKeys(nutritionNoPork, nutritionLactoseFree)
    val availableProducts by viewModel.nutritionAvailableProducts.collectAsState()
    val showExcludedProductsInNutrition by viewModel.showExcludedProductsInNutrition.collectAsState()
    val plan by viewModel.nutritionPlan.collectAsState()
    val currentTrackDay by viewModel.currentTrackDay.collectAsState()
    val activeCycle by viewModel.activeMealCycle.collectAsState()
    val pendingCycle by viewModel.pendingMealCycle.collectAsState()
    val todayDayNumber by viewModel.nutritionTodayDayNumber.collectAsState()
    val todayPlan by viewModel.todayNutritionPlan.collectAsState()
    val showExpirationWarning by viewModel.shouldShowNutritionExpirationWarning.collectAsState()
    var showAvailableProductsDialog by remember { mutableStateOf(false) }
    val previewPlan by viewModel.previewNutritionPlan.collectAsState()
    val storedMarket by viewModel.nutritionMarket.collectAsState()
    val storedLanguage by viewModel.nutritionMarketLanguage.collectAsState()
    val naming = rememberNutritionNaming(storedMarket, storedLanguage)

    if (showReportionDialog) {
        AlertDialog(
            onDismissRequest = { showReportionDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReportionDialog = false
                        viewModel.reportionNutritionCycle()
                    }
                ) {
                    Text(stringResource(R.string.nutrition_reportion_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportionDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.nutrition_reportion_confirm_title)) },
            text = { Text(stringResource(R.string.nutrition_reportion_confirm_body)) }
        )
    }

    if (previewPlan != null) {
        NutritionPreviewDialog(
            previewPlan = previewPlan!!,
            naming = naming,
            currentPlan = plan,
            availableProducts = availableProducts,
            onDismiss = viewModel::clearPreviewCycle,
            onAccept = viewModel::savePreviewCycle,
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

            when (energyOutcome) {
                NutritionEnergyOutcome.IncompleteProfile -> ProfilePromptCard(
                    onComplete = { viewModel.requestNutritionProfilePrompt() }
                )
                NutritionEnergyOutcome.Underage -> UnderageCard()
                is NutritionEnergyOutcome.Ready -> Unit
            }

            NutritionProfileCard(
                weightInput = weightInput,
                heightInput = heightInput,
                ageInput = ageInput,
                goalInput = goalInput,
                sex = sex,
                promptOpen = profilePrompt,
                onWeightChange = viewModel::setNutritionWeight,
                onHeightChange = viewModel::setNutritionHeight,
                onAgeChange = viewModel::setNutritionAge,
                onGoalChange = viewModel::setNutritionGoalWeight,
                onSexChange = viewModel::setNutritionSex,
                onDismissPrompt = viewModel::dismissNutritionProfilePrompt
            )

            ActiveCycleCard(activeCycle = activeCycle, pendingCycle = pendingCycle)

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = stringResource(R.string.nutrition_targets), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val cycleDayNumber = todayDayNumber
                    if (todayEnergy != null) {
                        NutritionTargetRow(
                            title = stringResource(R.string.nutrition_phase),
                            value = phaseLabel(todayEnergy.phase),
                            supporting = surplusLine(todayEnergy)
                        )
                        NutritionTargetRow(
                            title = stringResource(R.string.nutrition_maintenance),
                            value = stringResource(R.string.nutrition_kcal_format, todayEnergy.maintenanceCalories)
                        )
                        NutritionTargetRow(
                            title = stringResource(R.string.nutrition_weight_progress_title),
                            value = stringResource(
                                R.string.nutrition_weight_progress,
                                todayEnergy.currentWeightKg,
                                todayEnergy.goalWeightKg
                            ),
                            supporting = weightSourceLabel(todayEnergy.weightSource)
                        )
                    }
                    NutritionTargetRow(
                        title = stringResource(R.string.nutrition_daily_calories),
                        value = todayPlan?.let { day ->
                            stringResource(R.string.nutrition_planned_totals, day.totalCalories, day.totalProteinGrams)
                        } ?: todayEnergy?.let { energy ->
                            stringResource(R.string.nutrition_kcal_format, energy.targets.dailyCalories)
                        } ?: "—",
                        supporting = todayPlan?.let { day ->
                            todayEnergy?.let { energy ->
                                calorieTargetLine(targetCalories = energy.targets.dailyCalories, deliveredCalories = day.totalCalories)
                            }
                        }
                    )
                    todayEnergy?.limit?.let { limit ->
                        Text(
                            text = stringResource(
                                when (limit) {
                                    NutritionCalorieLimit.FLOOR -> R.string.nutrition_calorie_floor_applied
                                    NutritionCalorieLimit.CEILING -> R.string.nutrition_calorie_ceiling_applied
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    NutritionTargetRow(
                        title = stringResource(R.string.nutrition_current_program_day),
                        value = if (currentTrackDay >= NUTRITION_PROGRAM_DAY_CAP && cycleDayNumber != null && plan.days.isNotEmpty()) {
                            stringResource(R.string.nutrition_cycle_day_of, cycleDayNumber, plan.days.size)
                        } else {
                            stringResource(
                                R.string.nutrition_program_day_week,
                                currentTrackDay,
                                ((currentTrackDay - 1) / 7) + 1
                            )
                        }
                    )
                    if (todayEnergy != null) {
                        NutritionTargetRow(
                            title = stringResource(R.string.nutrition_protein_target),
                            value = stringResource(
                                R.string.nutrition_protein_range_format,
                                todayEnergy.targets.proteinMinGrams,
                                todayEnergy.targets.proteinMaxGrams
                            )
                        )
                    }
                    Text(
                        text = stringResource(R.string.nutrition_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = stringResource(R.string.nutrition_data_sources),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (reportionOffered) {
                OutlinedButton(onClick = { showReportionDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nutrition_reportion_action))
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
                                            checked = ingredient.key in effectiveExcludedFoods,
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
                val formattedRestrictions = if (effectiveExcludedFoods.isEmpty()) {
                    stringResource(R.string.diet_restrictions_none)
                } else {
                    effectiveExcludedFoods.mapNotNull { key ->
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
                    Text(stringResource(R.string.nutrition_preview_next_cycle))
                }
            }

            Text(
                text = stringResource(R.string.nutrition_plan_preview_title, plan.days.size),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            plan.orderedForDisplay(todayDayNumber).forEach { day ->
                NutritionDayCard(
                    day = day,
                    status = day.statusOn(todayDayNumber),
                    dayCount = plan.days.size,
                    naming = naming
                )
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
private fun ProfilePromptCard(onComplete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.nutrition_profile_incomplete_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(text = stringResource(R.string.nutrition_profile_incomplete_body), color = MaterialTheme.colorScheme.secondary)
            Button(onClick = onComplete) {
                Text(stringResource(R.string.nutrition_profile_incomplete_action))
            }
        }
    }
}

@Composable
private fun UnderageCard() {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.nutrition_profile_adults_only_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(text = stringResource(R.string.nutrition_profile_adults_only), color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/**
 * The body the targets are computed from. Each field keeps what is being typed even while the range
 * refuses it — the store only ever sees an accepted value, and the error under the field says why.
 */
@Composable
private fun NutritionProfileCard(
    weightInput: String,
    heightInput: String,
    ageInput: String,
    goalInput: String,
    sex: BiologicalSex?,
    promptOpen: Boolean,
    onWeightChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onAgeChange: (String) -> Unit,
    onGoalChange: (String) -> Unit,
    onSexChange: (BiologicalSex) -> Unit,
    onDismissPrompt: () -> Unit
) {
    // The field keeps what is being typed; the store only answers when it holds something else. Keying
    // the state on the stored value would rebuild it mid-edit and let the keyboard commit twice.
    var weightText by remember { mutableStateOf(weightInput) }
    var heightText by remember { mutableStateOf(heightInput) }
    var ageText by remember { mutableStateOf(ageInput) }
    var goalText by remember { mutableStateOf(goalInput) }
    LaunchedEffect(weightInput) { if (weightInput != weightText) weightText = weightInput }
    LaunchedEffect(heightInput) { if (heightInput != heightText) heightText = heightInput }
    LaunchedEffect(ageInput) { if (ageInput != ageText) ageText = ageInput }
    LaunchedEffect(goalInput) { if (goalInput != goalText) goalText = goalInput }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.nutrition_user_data), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (promptOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.nutrition_profile_incomplete_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissPrompt) {
                        Text(stringResource(R.string.nutrition_later))
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NutritionNumberField(
                    value = weightText,
                    label = stringResource(R.string.nutrition_weight),
                    error = profileFieldError(NutritionProfileField.WEIGHT, weightText),
                    modifier = Modifier.weight(1f),
                    onValueChange = { typed ->
                        weightText = typed.filter(Char::isDigit).take(3)
                        onWeightChange(weightText)
                    }
                )
                NutritionNumberField(
                    value = heightText,
                    label = stringResource(R.string.nutrition_height),
                    error = profileFieldError(NutritionProfileField.HEIGHT, heightText),
                    modifier = Modifier.weight(1f),
                    onValueChange = { typed ->
                        heightText = typed.filter(Char::isDigit).take(3)
                        onHeightChange(heightText)
                    }
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NutritionNumberField(
                    value = ageText,
                    label = stringResource(R.string.nutrition_age),
                    error = profileFieldError(NutritionProfileField.AGE, ageText),
                    modifier = Modifier.weight(1f),
                    onValueChange = { typed ->
                        ageText = typed.filter(Char::isDigit).take(3)
                        onAgeChange(ageText)
                    }
                )
                NutritionNumberField(
                    value = goalText,
                    label = stringResource(R.string.nutrition_goal_weight),
                    error = profileFieldError(NutritionProfileField.GOAL_WEIGHT, goalText),
                    modifier = Modifier.weight(1f),
                    onValueChange = { typed ->
                        goalText = typed.filter(Char::isDigit).take(3)
                        onGoalChange(goalText)
                    }
                )
            }
            Text(text = stringResource(R.string.nutrition_sex), style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = sex == BiologicalSex.MALE,
                    onClick = { onSexChange(BiologicalSex.MALE) },
                    label = { Text(stringResource(R.string.nutrition_sex_male)) }
                )
                FilterChip(
                    selected = sex == BiologicalSex.FEMALE,
                    onClick = { onSexChange(BiologicalSex.FEMALE) },
                    label = { Text(stringResource(R.string.nutrition_sex_female)) }
                )
            }
        }
    }
}

@Composable
private fun NutritionNumberField(
    value: String,
    label: String,
    error: String?,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun profileFieldError(field: NutritionProfileField, raw: String): String? =
    when (nutritionProfileError(field, raw)) {
        null -> null
        NutritionProfileError.ADULTS_ONLY -> stringResource(R.string.nutrition_profile_adults_only)
        NutritionProfileError.OUT_OF_RANGE -> stringResource(
            when (field) {
                NutritionProfileField.WEIGHT -> R.string.nutrition_error_weight
                NutritionProfileField.HEIGHT -> R.string.nutrition_error_height
                NutritionProfileField.AGE -> R.string.nutrition_error_age
                NutritionProfileField.GOAL_WEIGHT -> R.string.nutrition_error_goal_weight
            }
        )
    }

@Composable
private fun phaseLabel(phase: NutritionEnergyPhase): String = stringResource(
    when (phase) {
        NutritionEnergyPhase.GAIN -> R.string.nutrition_phase_gain
        NutritionEnergyPhase.TAPER -> R.string.nutrition_phase_taper
        NutritionEnergyPhase.MAINTAIN -> R.string.nutrition_phase_maintain
    }
)

@Composable
private fun surplusLine(energy: NutritionDayEnergy): String =
    if (energy.surplusCalories > 0) {
        stringResource(R.string.nutrition_surplus_over_maintenance, energy.surplusCalories)
    } else {
        stringResource(R.string.nutrition_surplus_none)
    }

@Composable
private fun weightSourceLabel(source: NutritionWeightSource): String = stringResource(
    when (source) {
        NutritionWeightSource.SEVEN_DAY_AVERAGE -> R.string.nutrition_weight_source_average
        NutritionWeightSource.LATEST_WEIGH_IN -> R.string.nutrition_weight_source_latest
        NutritionWeightSource.SETTINGS -> R.string.nutrition_weight_source_settings
    }
)

@Composable
private fun NutritionTargetRow(title: String, value: String, supporting: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(text = supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun calorieTargetLine(targetCalories: Int, deliveredCalories: Int): String {
    val gap = targetCalories - deliveredCalories
    return when {
        kotlin.math.abs(gap) * 100 <= targetCalories * 5 -> stringResource(R.string.nutrition_preview_target_calories, targetCalories)
        gap > 0 -> stringResource(R.string.nutrition_target_gap_below, targetCalories, gap)
        else -> stringResource(R.string.nutrition_target_gap_above, targetCalories, -gap)
    }
}

@Composable
private fun dayStatusLine(day: NutritionDayPlan, status: NutritionDayStatus, dayCount: Int): String {
    val programLine = if (day.isProgramDayCapped()) {
        stringResource(R.string.nutrition_cycle_day_of, day.dayNumber, dayCount)
    } else {
        stringResource(R.string.nutrition_program_day_week, day.programDay, day.week)
    }
    return when (status) {
        NutritionDayStatus.TODAY -> "${stringResource(R.string.nutrition_today_title)} · $programLine"
        NutritionDayStatus.PAST -> "${stringResource(R.string.nutrition_day_past)} · $programLine"
        NutritionDayStatus.UPCOMING -> programLine
    }
}

@Composable
private fun NutritionDayCard(day: NutritionDayPlan, status: NutritionDayStatus, dayCount: Int, naming: NutritionNaming) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = stringResource(R.string.day_display, day.dayNumber), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = dayStatusLine(day, status, dayCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status == NutritionDayStatus.TODAY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                }
                Text(text = stringResource(day.dayType.labelRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = stringResource(R.string.nutrition_planned_totals, day.totalCalories, day.totalProteinGrams),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            day.calorieTargetGap()?.let {
                Text(
                    text = calorieTargetLine(targetCalories = day.targetCalories, deliveredCalories = day.totalCalories),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            day.meals.forEach { meal -> NutritionMealCard(meal = meal, naming = naming) }
        }
    }
}

@Composable
private fun NutritionMealCard(meal: NutritionMeal, naming: NutritionNaming) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = nutritionDishTitle(meal = meal, naming = naming), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = stringResource(meal.type.labelRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
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

/**
 * A preview range, in the phrasing the app already uses for a 1/3/7-day plan: the day count belongs to the
 * sentence, so each language supplies its own form of it instead of the screen assembling a number and an
 * English word.
 */
@Composable
private fun previewRangeLabel(days: Int): String = when (days) {
    1 -> stringResource(R.string.nutrition_plan_days_1)
    3 -> stringResource(R.string.nutrition_plan_days_3)
    else -> stringResource(R.string.nutrition_plan_days_7)
}

/**
 * One label/value line in the preview's summary and comparison blocks.
 *
 * The two halves share the width instead of pushing the value past the row's edge: at font scale 1.5
 * a long translated label and a long value cannot both stay on one line, and a `SpaceBetween` row
 * with an unmeasured value is what sent the value off-screen. Both wrap inside their own half, and
 * the value stays right-aligned on the lines it occupies.
 */
@Composable
private fun PreviewSummaryRow(
    label: String,
    value: String,
    small: Boolean = false,
    valueColor: Color = Color.Unspecified
) {
    val style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = style,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = style,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NutritionPreviewDialog(
    previewPlan: NutritionPlan,
    naming: NutritionNaming,
    currentPlan: NutritionPlan,
    availableProducts: Set<String>,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
    onRangeSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onAccept) {
                Text(stringResource(R.string.nutrition_preview_accept))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.nutrition_preview_next_cycle),
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nutrition_preview_duration_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 3, 7).forEach { days ->
                            val isSelected = previewPlan.days.size == days
                            FilterChip(
                                selected = isSelected,
                                onClick = { onRangeSelect(days) },
                                label = {
                                    Text(
                                        text = previewRangeLabel(days),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }
                }

                val totalRecipes = previewPlan.days.flatMap { it.meals }
                val uniqueRecipesCount = totalRecipes.map { it.templateId }.distinct().size
                val repeatedRecipesCount = totalRecipes.size - uniqueRecipesCount
                val variety = nutritionVariety(previewPlan, currentPlan)
                val shoppingItems = previewPlan.shoppingList.values.flatten()
                val availableCount = shoppingItems.count { it.ingredient.key in availableProducts }
                val missingCount = shoppingItems.count { it.ingredient.key !in availableProducts }

                val varietyIndicator = when {
                    variety.newRatio >= 0.8f -> stringResource(R.string.nutrition_variety_excellent)
                    variety.newRatio >= 0.5f -> stringResource(R.string.nutrition_variety_good)
                    else -> stringResource(R.string.nutrition_variety_low)
                }
                val varietyColor = when {
                    variety.newRatio >= 0.8f -> MaterialTheme.colorScheme.primary
                    variety.newRatio >= 0.5f -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                }

                val shoppingIndicator = if (missingCount == 0) stringResource(R.string.nutrition_shopping_ready_to_cook) else stringResource(R.string.nutrition_shopping_needed)
                val shoppingIndicatorColor = if (missingCount == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.nutrition_preview_summary_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                        PreviewSummaryRow(
                            label = stringResource(R.string.nutrition_preview_duration),
                            value = previewRangeLabel(previewPlan.days.size)
                        )
                        PreviewSummaryRow(
                            label = stringResource(R.string.nutrition_preview_recipes),
                            value = "${totalRecipes.size}"
                        )
                        PreviewSummaryRow(
                            label = stringResource(R.string.nutrition_preview_unique_recipes),
                            value = "${uniqueRecipesCount}"
                        )
                        PreviewSummaryRow(
                            label = stringResource(R.string.nutrition_preview_repeated_recipes),
                            value = "${repeatedRecipesCount}"
                        )
                        PreviewSummaryRow(
                            label = stringResource(R.string.nutrition_preview_products_required),
                            value = "${shoppingItems.size}"
                        )
                        PreviewSummaryRow(
                            label = stringResource(R.string.nutrition_preview_already_available),
                            value = "$availableCount"
                        )
                        PreviewSummaryRow(
                            label = stringResource(R.string.nutrition_preview_need_to_buy),
                            value = "$missingCount"
                        )
                        PreviewSummaryRow(
                            label = stringResource(R.string.nutrition_preview_variety_score),
                            value = varietyIndicator,
                            valueColor = varietyColor
                        )
                        PreviewSummaryRow(
                            label = stringResource(R.string.nutrition_preview_shopping_status),
                            value = if (missingCount == 0) {
                                shoppingIndicator
                            } else {
                                stringResource(
                                    R.string.nutrition_preview_shopping_status_missing,
                                    shoppingIndicator,
                                    missingCount
                                )
                            },
                            valueColor = shoppingIndicatorColor
                        )

                        if (currentPlan.days.isNotEmpty()) {
                            val currentRecipes = currentPlan.days.flatMap { it.meals }
                            val currentTemplates = currentRecipes.map { it.templateId }.toSet()
                            val previewTemplates = totalRecipes.map { it.templateId }.toSet()

                            val newRecipesCount = previewTemplates.count { it !in currentTemplates }
                            val repeatedFromPrevCount = variety.repeatedFromPrevious

                            val currentIngredients = currentRecipes.flatMap { it.ingredients }.map { it.ingredient.key }.toSet()
                            val previewIngredients = totalRecipes.flatMap { it.ingredients }.map { it.ingredient.key }.toSet()

                            val newIngredientsCount = previewIngredients.count { it !in currentIngredients }

                            val currentAvg = currentPlan.days.map { it.totalCalories }.average().roundToInt()
                            val previewAvg = previewPlan.days.map { it.totalCalories }.average().roundToInt()

                            Spacer(modifier = Modifier.height(4.dp))
                            Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))
                            Spacer(modifier = Modifier.height(4.dp))

                            Text(stringResource(R.string.nutrition_preview_comparison_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                            PreviewSummaryRow(
                                label = stringResource(R.string.nutrition_preview_new_recipes),
                                value = "+$newRecipesCount",
                                small = true
                            )
                            PreviewSummaryRow(
                                label = stringResource(R.string.nutrition_preview_repeated_from_previous),
                                value = "$repeatedFromPrevCount",
                                small = true
                            )
                            PreviewSummaryRow(
                                label = stringResource(R.string.nutrition_preview_new_ingredients),
                                value = "$newIngredientsCount",
                                small = true
                            )
                            PreviewSummaryRow(
                                label = stringResource(R.string.nutrition_preview_ingredients_stocked),
                                value = "$availableCount",
                                small = true
                            )
                            PreviewSummaryRow(
                                label = stringResource(R.string.nutrition_preview_missing_ingredients),
                                value = "$missingCount",
                                small = true
                            )
                            PreviewSummaryRow(
                                label = stringResource(R.string.nutrition_preview_avg_daily_calories),
                                value = stringResource(
                                    R.string.nutrition_preview_calories_comparison,
                                    previewAvg,
                                    currentAvg
                                ),
                                small = true
                            )
                        }
                    }
                }

                Text(stringResource(R.string.nutrition_preview_daily_menu), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                previewPlan.days.forEach { day ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.day_display, day.dayNumber), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(day.dayType.labelRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = stringResource(R.string.nutrition_planned_totals, day.totalCalories, day.totalProteinGrams),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = calorieTargetLine(targetCalories = day.targetCalories, deliveredCalories = day.totalCalories),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )

                            day.meals.forEach { meal ->
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(nutritionDishTitle(meal = meal, naming = naming), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text(stringResource(meal.type.labelRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
                                    Text(stringResource(R.string.nutrition_preview_instructions), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    com.monkfitness.app.data.model.getTodayCookingInstructionResIds(meal).forEachIndexed { i, stepRes ->
                                        Text(stringResource(R.string.nutrition_preview_step, i + 1, stringResource(stepRes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }
                }

                Text(stringResource(R.string.nutrition_preview_required_products), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                NutritionShoppingGroup.entries.forEach { group ->
                    val items = previewPlan.shoppingList[group].orEmpty()
                    if (items.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(group.titleRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                items.forEach { item ->
                                    val isAvailable = item.ingredient.key in availableProducts
                                    val color = if (isAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    val icon = if (isAvailable) "✓" else "⚠"
                                    val quantity = when (item.ingredient.unit) {
                                        NutritionQuantityUnit.GRAMS -> stringResource(R.string.nutrition_quantity_grams, item.totalAmount)
                                        NutritionQuantityUnit.MILLILITERS -> stringResource(R.string.nutrition_quantity_milliliters, item.totalAmount)
                                        NutritionQuantityUnit.PIECES -> stringResource(R.string.nutrition_quantity_pieces, item.totalAmount)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "• ${stringResource(item.ingredient.nameRes)} — $quantity",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(icon, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.Bold)
                                            Text(
                                            if (isAvailable) stringResource(R.string.nutrition_preview_product_available)
                                            else stringResource(R.string.nutrition_preview_product_missing),
                                            style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}
