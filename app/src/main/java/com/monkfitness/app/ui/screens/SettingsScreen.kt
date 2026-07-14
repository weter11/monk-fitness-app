package com.monkfitness.app.ui.screens

import android.app.TimePickerDialog
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.LibraryStats
import com.monkfitness.app.data.model.NutritionIngredient
import com.monkfitness.app.data.model.ExerciseCategoryFilter
import com.monkfitness.app.data.model.exerciseCategoryGroups
import com.monkfitness.app.data.model.exerciseToFamiliesMap
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
    val estimatedPoolSize by viewModel.estimatedExercisePoolSize.collectAsState()

    val adjustedMessageFlow = viewModel.showAdjustedValidationMessage
    LaunchedEffect(adjustedMessageFlow) {
        adjustedMessageFlow.collectLatest { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // SECTION: General Settings (Notifications, Language, Feedback)
            Text(text = "General Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

                Spacer(modifier = Modifier.height(8.dp))

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

                Spacer(modifier = Modifier.height(8.dp))

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
            }

            Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))

            // SECTION: WORKOUT
            Text(text = "Workout Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Workout -> Training Type
                FocusAreaSelector(
                    title = stringResource(R.string.flexibility_training_type_title) + " (Training Type)",
                    options = FlexibilityTrainingType.entries.toList(),
                    selectedOption = flexibilityTrainingType,
                    onSelect = viewModel::setFlexibilityTrainingType,
                    disabledFamilies = disabledExerciseFamilies
                )

                // Workout -> Focus Areas
                MultiSelectFocusAreaSelector(
                    title = stringResource(R.string.flexibility_focus_areas_title) + " (Focus Areas)",
                    options = flexibilityFocusAreaOptions,
                    selectedOptions = selectedFlexibilityFocusAreas,
                    onToggle = viewModel::toggleFlexibilityFocusArea
                )

                // Workout -> Training Styles & Special Programs (Separate categories into TWO groups)
                val counts = viewModel.categoryExerciseCounts
                exerciseCategoryGroups.forEach { group ->
                    CategoryGroupSelector(
                        title = group.title,
                        categories = group.categories,
                        disabledFamilies = disabledExerciseFamilies,
                        onToggle = viewModel::toggleExerciseFamily,
                        counts = counts
                    )
                }

                // Workout -> Exercise Library
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "Exercise Library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    SettingSwitchRow(
                        title = "Show only selected exercise families",
                        checked = filterLibraryByCategories,
                        onCheckedChange = viewModel::setFilterLibraryByCategories
                    )
                    Text(
                        text = "When enabled, search displays only the exercises from enabled categories. Otherwise, search displays the entire database.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Available Equipment",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    MultiSelectEquipmentSelector(
                        title = "",
                        options = Equipment.entries.filterNot { it == Equipment.NONE },
                        selectedOptions = userPreferences.availableEquipment,
                        onToggle = viewModel::toggleAvailableEquipment,
                        onClear = viewModel::clearAvailableEquipment
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.enable_additional_posture_training),
                        checked = additionalPostureTrainingEnabled,
                        onCheckedChange = viewModel::setAdditionalPostureTrainingEnabled
                    )
                }

                // Workout -> Preview Summary
                CurrentWorkoutConfigurationCard(
                    flexibilityTrainingType = flexibilityTrainingType,
                    selectedFlexibilityFocusAreas = selectedFlexibilityFocusAreas,
                    disabledFamilies = disabledExerciseFamilies,
                    filterLibrary = filterLibraryByCategories,
                    estimatedPoolSize = estimatedPoolSize
                )
            }

            Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))

            // SECTION: NUTRITION (Workout settings should always appear before Nutrition)
            Text(text = "Nutrition Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Nutrition -> Nutrition Preferences
                FoodExclusionsSelector(
                    title = "Nutrition Preferences",
                    description = stringResource(R.string.nutrition_exclusions_desc),
                    excludedFoods = userPreferences.excludedFoods,
                    onToggle = viewModel::toggleNutritionExcludedFood,
                    options = viewModel.nutritionExclusionOptions
                )

                // Nutrition -> Shopping
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Shopping", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SettingSwitchRow(
                        title = stringResource(R.string.show_excluded_products_nutrition),
                        checked = showExcludedProductsInNutrition,
                        onCheckedChange = viewModel::setShowExcludedProductsInNutrition
                    )
                }

                // Nutrition -> Meal Planning
                NutritionCycleSelector(
                    selectedDays = nutritionCycleLength,
                    onSelect = viewModel::setNutritionCycleLength
                )
            }

            Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))

            // SECTION: Library Statistics
            LibraryStatisticsSection(stats = libraryStats)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryGroupSelector(
    title: String,
    categories: List<ExerciseCategoryFilter>,
    disabledFamilies: Set<String>,
    onToggle: (String) -> Unit,
    counts: Map<String, Int>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { category ->
                val isChecked = category.key !in disabledFamilies

                // Determine if it is disabled due to a constraint
                val isConstraintDisabled = when (category.key) {
                    "functional_fitness" -> "hyperlordosis" !in disabledFamilies
                    "calisthenics" -> "senior" !in disabledFamilies || "rehabilitation" !in disabledFamilies
                    "shaolin" -> "senior" !in disabledFamilies
                    else -> false
                }

                val explanation = when (category.key) {
                    "functional_fitness" -> if ("hyperlordosis" !in disabledFamilies) "Disabled because \"Hyperlordosis\" is enabled." else null
                    "calisthenics" -> {
                        if ("senior" !in disabledFamilies) "Disabled because \"Senior Friendly\" is enabled."
                        else if ("rehabilitation" !in disabledFamilies) "Disabled because \"Rehabilitation\" is enabled."
                        else null
                    }
                    "shaolin" -> if ("senior" !in disabledFamilies) "Disabled because \"Senior Friendly\" is enabled." else null
                    else -> null
                }

                Column(horizontalAlignment = Alignment.Start) {
                    FilterChip(
                        selected = isChecked && !isConstraintDisabled,
                        onClick = { if (!isConstraintDisabled) onToggle(category.key) },
                        enabled = !isConstraintDisabled,
                        label = {
                            Text("${category.displayName} (${counts[category.key] ?: 0})")
                        }
                    )
                    if (isConstraintDisabled && explanation != null) {
                        Text(
                            text = explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentWorkoutConfigurationCard(
    flexibilityTrainingType: FlexibilityTrainingType,
    selectedFlexibilityFocusAreas: Set<ExerciseSubCategory>,
    disabledFamilies: Set<String>,
    filterLibrary: Boolean,
    estimatedPoolSize: Int
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Current Workout Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            val typeStr = when (flexibilityTrainingType) {
                FlexibilityTrainingType.STRETCHING -> "Stretching only"
                FlexibilityTrainingType.POSTURE -> "Posture only"
                FlexibilityTrainingType.BOTH -> "Both"
            }

            val focusStr = selectedFlexibilityFocusAreas.joinToString(", ") { context.getString(it.labelRes) }

            val activeStyles = exerciseCategoryGroups[0].categories
                .filter { it.key !in disabledFamilies }
                .joinToString(", ") { it.displayName }
                .ifEmpty { "None" }

            val activePrograms = exerciseCategoryGroups[1].categories
                .filter { it.key !in disabledFamilies }
                .joinToString(", ") { it.displayName }
                .ifEmpty { "None" }

            val filterStr = if (filterLibrary) "Enabled" else "Disabled"

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Training Type:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(typeStr, style = MaterialTheme.typography.bodyMedium)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Focus Areas:", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(focusStr, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Training Styles:", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(activeStyles, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Special Programs:", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(activePrograms, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Exercise Library filtering:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(filterStr, style = MaterialTheme.typography.bodyMedium)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Estimated exercise pool size:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("$estimatedPoolSize", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
    onSelect: (FlexibilityTrainingType) -> Unit,
    disabledFamilies: Set<String>
) {
    val postureEnabled = "posture_correction" !in disabledFamilies
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val isDisabled = option == FlexibilityTrainingType.STRETCHING && postureEnabled
                Column(horizontalAlignment = Alignment.Start) {
                    FilterChip(
                        selected = option == selectedOption && !isDisabled,
                        onClick = { if (!isDisabled) onSelect(option) },
                        enabled = !isDisabled,
                        label = { Text(stringResource(option.labelRes)) }
                    )
                    if (isDisabled) {
                        Text(
                            text = "Disabled because \"Posture Correction\" is enabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                }
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
        if (title.isNotEmpty()) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
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
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
