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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.LibraryStats
import com.monkfitness.app.data.model.NutritionIngredient
import com.monkfitness.app.data.model.flexibilityFocusAreas as flexibilityFocusAreaOptions
import com.monkfitness.app.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
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
    val libraryStats by viewModel.libraryStats.collectAsState()
    val disabledExerciseFamilies by viewModel.disabledExerciseFamilies.collectAsState()
    val filterLibraryByCategories by viewModel.filterLibraryByCategories.collectAsState()
    val showCategoryErrorDialog by viewModel.showCategoryErrorDialog.collectAsState()
    val disabledOptionsExplanations by viewModel.disabledOptionsExplanations.collectAsState()
    val categoryExerciseCounts by viewModel.categoryExerciseCounts.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.showAdjustedValidationMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    if (showCategoryErrorDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCategoryErrorDialog,
            confirmButton = {
                TextButton(onClick = viewModel::dismissCategoryErrorDialog) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text("At least one category must remain enabled") },
            text = { Text("Please enable at least one exercise category to generate workouts.") }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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
            // WORKOUT SECTION
            Text(text = "Workout Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

            // Training Type Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusAreaSelector(
                        title = stringResource(R.string.flexibility_training_type_title),
                        options = FlexibilityTrainingType.entries.toList(),
                        selectedOption = flexibilityTrainingType,
                        onSelect = viewModel::setFlexibilityTrainingType
                    )
                }
            }

            // Focus Areas Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MultiSelectFocusAreaSelector(
                        title = stringResource(R.string.flexibility_focus_areas_title),
                        options = flexibilityFocusAreaOptions,
                        selectedOptions = selectedFlexibilityFocusAreas,
                        onToggle = viewModel::toggleFlexibilityFocusArea
                    )
                }
            }

            // Training Styles, Special Programs, and Exercise Library filters
            ExerciseFamiliesSelector(
                disabledFamilies = disabledExerciseFamilies,
                disabledExplanations = disabledOptionsExplanations,
                categoryExerciseCounts = categoryExerciseCounts,
                filterLibrary = filterLibraryByCategories,
                onToggleFilterLibrary = viewModel::setFilterLibraryByCategories,
                onToggle = viewModel::toggleExerciseFamily,
                onEnableAll = viewModel::enableAllInGroup,
                onDisableAll = viewModel::disableAllInGroup
            )

            // Current Workout Configuration Summary Card
            WorkoutConfigurationPreview(
                trainingType = flexibilityTrainingType,
                focusAreas = selectedFlexibilityFocusAreas,
                disabledFamilies = disabledExerciseFamilies,
                filterLibrary = filterLibraryByCategories
            )

            Spacer(modifier = Modifier.height(8.dp))

            // NUTRITION SECTION
            Text(text = "Nutrition Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

            // Meal Planning Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
            }

            // Nutrition Preferences Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FoodExclusionsSelector(
                        title = stringResource(R.string.nutrition_exclusions),
                        description = stringResource(R.string.nutrition_exclusions_desc),
                        excludedFoods = userPreferences.excludedFoods,
                        onToggle = viewModel::toggleNutritionExcludedFood,
                        options = viewModel.nutritionExclusionOptions
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // SYSTEM SETTINGS SECTION
            Text(text = "System Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = stringResource(R.string.notifications), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = stringResource(R.string.language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = stringResource(R.string.feedback), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = stringResource(R.string.mobility_and_posture), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SettingSwitchRow(
                        title = stringResource(R.string.enable_additional_posture_training),
                        checked = additionalPostureTrainingEnabled,
                        onCheckedChange = viewModel::setAdditionalPostureTrainingEnabled
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = stringResource(R.string.personalization), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LibraryStatisticsSection(stats = libraryStats)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExerciseFamiliesSelector(
    disabledFamilies: Set<String>,
    disabledExplanations: Map<String, String>,
    categoryExerciseCounts: Map<String, Int>,
    filterLibrary: Boolean,
    onToggleFilterLibrary: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onEnableAll: (List<String>) -> Unit,
    onDisableAll: (List<String>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = "Exercises", style = MaterialTheme.typography.titleLarge)

        SettingSwitchRow(
            title = "Show only exercises matching selected categories",
            checked = filterLibrary,
            onCheckedChange = onToggleFilterLibrary
        )
        Text(
            text = "When enabled, the Exercise Library and Search only display exercises belonging to the selected categories.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val groups = com.monkfitness.app.data.model.exerciseCategoryGroups
        groups.forEach { group ->
            var isExpanded by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isExpanded = !isExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val enabledCount = group.categories.count { it.key !in disabledFamilies }
                            Text(
                                text = "$enabledCount of ${group.categories.size} enabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        IconButton(onClick = { isExpanded = !isExpanded }) {
                            Icon(
                                imageVector = if (isExpanded) androidx.compose.material.icons.Icons.Default.KeyboardArrowUp else androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "Collapse" else "Expand"
                            )
                        }
                    }

                    if (isExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { onEnableAll(group.categories.map { it.key }) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Enable All")
                            }
                            TextButton(
                                onClick = { onDisableAll(group.categories.map { it.key }) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Disable All")
                            }
                        }

                        group.categories.chunked(2).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { family ->
                                    val isOptionDisabled = disabledExplanations.containsKey(family.key)
                                    val explanation = disabledExplanations[family.key]
                                    val count = categoryExerciseCounts[family.key] ?: 0

                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(enabled = !isOptionDisabled) { onToggle(family.key) },
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = family.key !in disabledFamilies,
                                            onCheckedChange = { if (!isOptionDisabled) onToggle(family.key) },
                                            enabled = !isOptionDisabled
                                        )
                                        Column {
                                            Text(
                                                text = "${family.displayName} ($count)",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isOptionDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isOptionDisabled && explanation != null) {
                                                Text(
                                                    text = explanation,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                                if (rowItems.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutConfigurationPreview(
    trainingType: FlexibilityTrainingType,
    focusAreas: Set<ExerciseSubCategory>,
    disabledFamilies: Set<String>,
    filterLibrary: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Current Workout Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            val allCategoryFilterKeys = com.monkfitness.app.data.model.ExerciseCategoryFilter.entries
            val enabledCategories = allCategoryFilterKeys.filter { it.key !in disabledFamilies }

            val trainingStyles = enabledCategories.filter { it in com.monkfitness.app.data.model.exerciseCategoryGroups[0].categories }
                .map { it.displayName }
            val specialPrograms = enabledCategories.filter { it in com.monkfitness.app.data.model.exerciseCategoryGroups[1].categories }
                .map { it.displayName }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Training Type:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(trainingType.labelRes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            val focusLabels = focusAreas.map { stringResource(it.labelRes) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Focus:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = focusLabels.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Styles:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = if (trainingStyles.isEmpty()) "None" else trainingStyles.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Special Programs:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = if (specialPrograms.isEmpty()) "None" else specialPrograms.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Exercise Filter:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(if (filterLibrary) "Enabled" else "Disabled", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun LibraryStatisticsSection(stats: LibraryStats) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.library_statistics),
            style = MaterialTheme.typography.titleLarge
        )

        StatRow(label = stringResource(R.string.stats_total_exercises), value = stats.totalExercises.toString())
        StatRow(label = stringResource(R.string.stats_total_families), value = stats.totalFamilies.toString())
        StatRow(label = stringResource(R.string.stats_main_categories), value = stats.totalCategories.toString())
        StatRow(label = stringResource(R.string.stats_body_regions), value = stats.totalBodyRegions.toString())
        StatRow(label = stringResource(R.string.stats_languages), value = stats.totalLanguages.toString())

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.stats_animation_coverage),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        StatRow(
            label = stringResource(R.string.stats_animated_label),
            value = stringResource(R.string.stats_animated_format, stats.animatedExercisesCount, stats.totalExercises)
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
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
