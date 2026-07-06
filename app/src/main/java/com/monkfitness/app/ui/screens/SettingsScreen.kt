package com.monkfitness.app.ui.screens

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.NutritionIngredient
import com.monkfitness.app.data.model.flexibilityFocusAreas as flexibilityFocusAreaOptions
import com.monkfitness.app.viewmodel.MainViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val timerTicksEnabled by viewModel.timerTicksEnabled.collectAsState()
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsState()
    val additionalPostureTrainingEnabled by viewModel.additionalPostureTrainingEnabled.collectAsState()
    val flexibilityTrainingType by viewModel.flexibilityTrainingType.collectAsState()
    val selectedFlexibilityFocusAreas by viewModel.flexibilityFocusAreas.collectAsState()
    val userPreferences by viewModel.userPreferences.collectAsState()
    val nutritionCycleLength by viewModel.nutritionCycleLength.collectAsState()
    val showExcludedProductsInNutrition by viewModel.showExcludedProductsInNutrition.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
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
            Text(text = stringResource(R.string.notifications), style = MaterialTheme.typography.titleLarge)

            androidx.compose.material3.Button(
                onClick = {
                    val calendar = Calendar.getInstance()
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> viewModel.setNotificationTime(hour, minute) },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    ).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.set_notification_time))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = stringResource(R.string.language), style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { viewModel.setLanguage("en") }) {
                    Text(stringResource(R.string.lang_en))
                }
                androidx.compose.material3.Button(onClick = { viewModel.setLanguage("ru") }) {
                    Text(stringResource(R.string.lang_ru))
                }
                androidx.compose.material3.Button(onClick = { viewModel.setLanguage("uk") }) {
                    Text(stringResource(R.string.lang_uk))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = stringResource(R.string.feedback), style = MaterialTheme.typography.titleLarge)

            SettingSwitchRow(
                title = stringResource(R.string.timer_ticks),
                checked = timerTicksEnabled,
                onCheckedChange = viewModel::setTimerTicksEnabled
            )
            SettingSwitchRow(
                title = stringResource(R.string.vibration),
                checked = vibrationEnabled,
                onCheckedChange = viewModel::setVibrationEnabled
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = stringResource(R.string.nutrition), style = MaterialTheme.typography.titleLarge)
            NutritionCycleSelector(
                selectedDays = nutritionCycleLength,
                onSelect = viewModel::setNutritionCycleLength
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingSwitchRow(
                title = stringResource(R.string.show_excluded_products_nutrition),
                checked = showExcludedProductsInNutrition,
                onCheckedChange = viewModel::setShowExcludedProductsInNutrition
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = stringResource(R.string.mobility_and_posture), style = MaterialTheme.typography.titleLarge)
            SettingSwitchRow(
                title = stringResource(R.string.enable_additional_posture_training),
                checked = additionalPostureTrainingEnabled,
                onCheckedChange = viewModel::setAdditionalPostureTrainingEnabled
            )

            FocusAreaSelector(
                title = stringResource(R.string.flexibility_training_type_title),
                options = FlexibilityTrainingType.entries.toList(),
                selectedOption = flexibilityTrainingType,
                onSelect = viewModel::setFlexibilityTrainingType
            )

            MultiSelectFocusAreaSelector(
                title = stringResource(R.string.flexibility_focus_areas_title),
                options = flexibilityFocusAreaOptions,
                selectedOptions = selectedFlexibilityFocusAreas,
                onToggle = viewModel::toggleFlexibilityFocusArea
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = stringResource(R.string.personalization), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.equipment_selection_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            MultiSelectEquipmentSelector(
                title = stringResource(R.string.equipment_selection),
                options = Equipment.entries.filterNot { it == Equipment.NONE },
                selectedOptions = userPreferences.availableEquipment,
                onToggle = viewModel::toggleAvailableEquipment,
                onClear = viewModel::clearAvailableEquipment
            )

            FoodExclusionsSelector(
                title = stringResource(R.string.nutrition_exclusions),
                description = stringResource(R.string.nutrition_exclusions_desc),
                excludedFoods = userPreferences.excludedFoods,
                onToggle = viewModel::toggleNutritionExcludedFood,
                options = viewModel.nutritionExclusionOptions
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NutritionCycleSelector(
    selectedDays: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.nutrition_cycle_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 1, 3, 7).forEach { days ->
                val label = when (days) {
                    0 -> stringResource(R.string.nutrition_disabled)
                    1 -> stringResource(R.string.nutrition_plan_days_1)
                    3 -> stringResource(R.string.nutrition_plan_days_3)
                    else -> stringResource(R.string.nutrition_plan_days_7)
                }
                FilterChip(
                    selected = selectedDays == days,
                    onClick = { onSelect(days) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusAreaSelector(
    title: String,
    options: List<FlexibilityTrainingType>,
    selectedOption: FlexibilityTrainingType,
    onSelect: (FlexibilityTrainingType) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selectedOption,
                    onClick = { onSelect(option) },
                    label = { Text(stringResource(option.labelRes)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiSelectFocusAreaSelector(
    title: String,
    options: List<ExerciseSubCategory>,
    selectedOptions: Set<ExerciseSubCategory>,
    onToggle: (ExerciseSubCategory) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option in selectedOptions,
                    onClick = { onToggle(option) },
                    label = { Text(stringResource(option.labelRes)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiSelectEquipmentSelector(
    title: String,
    options: List<Equipment>,
    selectedOptions: Set<Equipment>,
    onToggle: (Equipment) -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedOptions.isEmpty(),
                onClick = onClear,
                label = { Text(stringResource(R.string.equipment_all_available)) }
            )
            options.forEach { option ->
                FilterChip(
                    selected = option in selectedOptions,
                    onClick = { onToggle(option) },
                    label = { Text(stringResource(option.labelRes)) }
                )
            }
        }
    }
}

@Composable
private fun FoodExclusionsSelector(
    title: String,
    description: String,
    excludedFoods: Set<String>,
    onToggle: (String) -> Unit,
    options: List<NutritionIngredient>
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        options.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { ingredient ->
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Checkbox(
                            checked = ingredient.key in excludedFoods,
                            onCheckedChange = { onToggle(ingredient.key) }
                        )
                        Text(
                            text = stringResource(ingredient.nameRes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
