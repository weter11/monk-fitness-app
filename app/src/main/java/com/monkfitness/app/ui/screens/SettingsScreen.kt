package com.monkfitness.app.ui.screens

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.monkfitness.app.language.AppLanguageSettings
import com.monkfitness.app.ui.language.LanguagePickerDialog
import com.monkfitness.app.data.model.flexibilityFocusAreas as flexibilityFocusAreaOptions
import com.monkfitness.app.viewmodel.MaintenanceResult
import com.monkfitness.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenPrograms: () -> Unit,
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
    val showEngineeringValidation by viewModel.showEngineeringValidation.collectAsState()

    if (showCategoryErrorDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCategoryErrorDialog,
            confirmButton = {
                TextButton(onClick = viewModel::dismissCategoryErrorDialog) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.settings_category_required_title)) },
            text = { Text(stringResource(R.string.settings_category_required_text)) }
        )
    }

    // C3: every maintenance action reports Success or Failure. A destructive operation must
    // never end in a state the user cannot distinguish from success — the snackbar is the
    // "obvious result" the spec asks for on both outcomes. A Failure stays visible until the
    // user dismisses it, so a failed wipe is never mistaken for a completed one.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        viewModel.maintenanceEvents.collect { result: MaintenanceResult ->
            val message = context.getString(result.messageRes)
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = if (result is MaintenanceResult.Failure) {
                        SnackbarDuration.Indefinite
                    } else {
                        SnackbarDuration.Short
                    }
                )
            }
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
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
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

            // §6: one settings entry for the language, opening a single-choice list — the seven languages
            // plus "System language". The three buttons this replaces were already one language short of a
            // row that could hold them all.
            val currentLanguage = viewModel.currentAppLanguage()
            var showLanguagePicker by remember { mutableStateOf(false) }

            SettingActionRow(
                title = stringResource(R.string.language),
                value = stringResource(currentLanguage.labelRes),
                onClick = { showLanguagePicker = true }
            )

            // §7: Android's own per-app language screen, offered only where the platform has one. It edits
            // the same application locale this screen edits — a second way to the one setting, not a second
            // setting — and the app keeps no state that could disagree with it.
            val systemLanguageIntent = remember(context) { AppLanguageSettings.intentFor(context) }
            if (systemLanguageIntent != null) {
                TextButton(onClick = { context.startActivity(systemLanguageIntent) }) {
                    Text(stringResource(R.string.language_system_settings))
                }
            }

            if (showLanguagePicker) {
                LanguagePickerDialog(
                    current = currentLanguage,
                    onSelect = { language ->
                        showLanguagePicker = false
                        viewModel.selectAppLanguage(language)
                    },
                    onDismiss = { showLanguagePicker = false }
                )
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

            Text(text = stringResource(R.string.developer_tools), style = MaterialTheme.typography.titleLarge)
            SettingSwitchRow(
                title = stringResource(R.string.show_engineering_validation),
                checked = showEngineeringValidation,
                onCheckedChange = viewModel::setShowEngineeringValidation
            )
            Text(
                text = stringResource(R.string.show_engineering_validation_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
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

            Spacer(modifier = Modifier.height(24.dp))

            // The Program System's entry point (§30 step 14). Settings owns global application settings;
            // Programs are managed by the Program System's own screens, and this section is the way in —
            // it is not a second program manager and it decides nothing.
            Text(text = stringResource(R.string.programs_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.programs_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            androidx.compose.material3.Button(
                onClick = onOpenPrograms,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.programs_title))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Spacer(modifier = Modifier.height(24.dp))

            ExerciseFamiliesSelector(
                disabledFamilies = disabledExerciseFamilies,
                filterLibrary = filterLibraryByCategories,
                onToggleFilterLibrary = viewModel::setFilterLibraryByCategories,
                onToggle = viewModel::toggleExerciseFamily,
                onEnableAll = viewModel::enableAllInGroup,
                onDisableAll = viewModel::disableAllInGroup,
                showEngineeringValidation = showEngineeringValidation
            )

            Spacer(modifier = Modifier.height(24.dp))

            ProgramControlsSection(
                onFullReset = viewModel::fullReset
            )

            Spacer(modifier = Modifier.height(24.dp))

            LibraryStatisticsSection(stats = libraryStats)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExerciseFamiliesSelector(
    disabledFamilies: Set<String>,
    filterLibrary: Boolean,
    onToggleFilterLibrary: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onEnableAll: (List<String>) -> Unit,
    onDisableAll: (List<String>) -> Unit,
    showEngineeringValidation: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = stringResource(R.string.settings_exercises_title), style = MaterialTheme.typography.titleLarge)

        SettingSwitchRow(
            title = stringResource(R.string.settings_filter_library_title),
            checked = filterLibrary,
            onCheckedChange = onToggleFilterLibrary
        )
        Text(
            text = stringResource(R.string.settings_filter_library_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val groups = com.monkfitness.app.data.model.exerciseCategoryGroups
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(group.titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { onEnableAll(group.categories.map { it.key }) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.settings_enable_all))
                        }
                        TextButton(
                            onClick = { onDisableAll(group.categories.map { it.key }) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.settings_disable_all))
                        }
                    }
                }

                group.categories.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { family ->
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = family.key !in disabledFamilies,
                                    onCheckedChange = { onToggle(family.key) }
                                )
                                Text(
                                    text = stringResource(family.labelRes),
                                    style = MaterialTheme.typography.bodyMedium
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

        // Engineering Validation — only visible when the developer setting is ON. It
        // participates in the same disabled-families filter as every other family.
        if (showEngineeringValidation) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = stringResource(R.string.engineering_validation_category),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = com.monkfitness.app.validation.ENGINEERING_VALIDATION_FAMILY_ID !in disabledFamilies,
                        onCheckedChange = { onToggle(com.monkfitness.app.validation.ENGINEERING_VALIDATION_FAMILY_ID) }
                    )
                    Text(
                        text = stringResource(R.string.engineering_validation_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgramControlsSection(
    onFullReset: () -> Unit
) {
    var showFullResetDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // §16, after §30 step 15: **only** the one genuinely global maintenance operation is left here.
        //
        // "Restart current cycle" is gone — there is no target Program concept of a current cycle — and
        // "Start revised program" is gone with the legacy implementation it had. Its *intent* is provided
        // by the target architecture the way §16 requires (`Edit / Copy → a new immutable Revision →
        // lifecycle Start`, in the Programs section above), and keeping a second way to restart a program
        // would be exactly the cycle semantics this stage retires.
        //
        // What remains is a wipe, and its contract is mapped onto the current schema rather than named
        // after the retired tables: it clears the user's Programs and every opportunity, attempt,
        // snapshot and confirmed set, the pauses and the target adaptive rows, and the retained
        // posture/mobility track and body-weight log — while keeping the built-in Standard Program and
        // the nutrition plans.
        Text(text = stringResource(R.string.program_controls_title), style = MaterialTheme.typography.titleLarge)

        Text(
            text = stringResource(R.string.full_reset_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        androidx.compose.material3.Button(
            onClick = { showFullResetDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.full_reset),
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (showFullResetDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.full_reset_confirm),
            text = stringResource(R.string.full_reset_confirm_text),
            confirmLabel = stringResource(R.string.full_reset),
            isError = true,
            onConfirm = {
                showFullResetDialog = false
                onFullReset()
            },
            onDismiss = { showFullResetDialog = false }
        )
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    text: String,
    confirmLabel: String,
    isError: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
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

/**
 * A settings entry that opens something else, showing the value it currently holds — the shape a settings
 * list needs once a choice no longer fits in the row itself (§6).
 */
@Composable
private fun SettingActionRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
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
