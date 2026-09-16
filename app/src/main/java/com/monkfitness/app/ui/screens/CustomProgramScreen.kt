package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.ui.customprogram.CustomProgramEditorState
import com.monkfitness.app.ui.customprogram.CustomProgramErrorPresentation
import com.monkfitness.app.ui.customprogram.CustomProgramFamilyGroup
import com.monkfitness.app.ui.customprogram.CustomProgramWarningPresentation
import com.monkfitness.app.ui.customprogram.FamilySelectionState
import com.monkfitness.app.viewmodel.MainViewModel

/**
 * Custom Program: the editor for which exercises a generated workout may use.
 *
 * The screen is a renderer and nothing else. It shows the state the editor publishes, sends taps back
 * through the view model, and owns no rule of its own: it does not decide what is valid, what a family
 * toggle means, what a search matches, whether Apply may proceed or what a stored configuration is. The
 * validation wording it displays arrives resolved to string resources, so no domain vocabulary is
 * branched on here.
 *
 * Three deliberate presentation choices:
 *
 *  * a family's tri-state is spelled out in words next to its control, with the enabled count, so the
 *    meaning never rests on colour alone;
 *  * hard errors and balance warnings are rendered as standing panels, not transient messages, so an
 *    error stays on screen until the user fixes it or leaves;
 *  * Apply stays available while errors are shown — pressing it is how the user finds out that the
 *    selection is refused, and the editor reports it without closing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomProgramScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.customProgramState.collectAsState()

    LaunchedEffect(state.appliedConfiguration) {
        if (state.appliedConfiguration != null) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_program)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.cancelCustomProgramEditor()
                            onBack()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.previous)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (!state.isOpen) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EditorSummary(state)

            SearchField(
                query = state.searchQuery,
                onQueryChange = viewModel::setCustomProgramSearchQuery
            )

            ValidationSection(errors = state.errors, warnings = state.warnings)

            if (state.visibleFamilies.isEmpty()) {
                Text(
                    text = stringResource(R.string.custom_program_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.visibleFamilies.forEach { family ->
                FamilySection(
                    family = family,
                    onToggleFamily = { viewModel.toggleCustomProgramFamily(family.familyId) },
                    onToggleExercise = viewModel::toggleCustomProgramExercise
                )
            }

            HorizontalDivider()

            EditorActions(
                onCancel = {
                    viewModel.cancelCustomProgramEditor()
                    onBack()
                },
                onApply = viewModel::applyCustomProgram,
                onReset = viewModel::requestCustomProgramReset
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (state.isResetConfirmationVisible) {
        ResetConfirmationDialog(
            onConfirm = viewModel::confirmCustomProgramReset,
            onDismiss = viewModel::dismissCustomProgramReset
        )
    }
}

@Composable
private fun EditorSummary(state: CustomProgramEditorState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.custom_program_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(
                if (state.isDefaultSelection) {
                    R.string.custom_program_default_selection
                } else {
                    R.string.custom_program_custom_selection
                }
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.custom_program_enabled_count, state.enabledExerciseCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.custom_program_future_only_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.custom_program_search_label)) },
        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange(EMPTY_QUERY) }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.custom_program_search_clear)
                    )
                }
            }
        }
    )
}

@Composable
private fun ValidationSection(
    errors: List<CustomProgramErrorPresentation>,
    warnings: List<CustomProgramWarningPresentation>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (errors.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = null)
                        Text(
                            text = stringResource(R.string.custom_program_error_header),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    errors.forEach { error -> ErrorLine(error) }
                }
            }
        }

        if (warnings.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null)
                        Text(
                            text = stringResource(R.string.custom_program_warning_header),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    warnings.forEach { warning ->
                        Text(
                            text = stringResource(
                                warning.messageRes,
                                stringResource(warning.subjectRes),
                                warning.usableExerciseCount
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorLine(error: CustomProgramErrorPresentation) {
    val subject = if (error.subjectRes != 0) {
        stringResource(error.subjectRes)
    } else {
        error.exerciseId ?: EMPTY_QUERY
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(error.messageRes, subject),
            style = MaterialTheme.typography.bodyMedium
        )
        if (error.requiredEquipmentRes.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.custom_program_requires_equipment),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error.requiredEquipmentRes.forEach { labelRes ->
                    EquipmentTag(labelRes)
                }
            }
        }
    }
}

@Composable
private fun EquipmentTag(labelRes: Int) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun FamilySection(
    family: CustomProgramFamilyGroup,
    onToggleFamily: () -> Unit,
    onToggleExercise: (String) -> Unit
) {
    val stateLabel = stringResource(familyStateLabelRes(family.selectionState))
    val familyName = stringResource(family.nameRes)
    val toggleDescription = stringResource(
        R.string.custom_program_family_toggle_description,
        familyName,
        stateLabel
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TriStateCheckbox(
                state = when (family.selectionState) {
                    FamilySelectionState.ALL_ENABLED -> ToggleableState.On
                    FamilySelectionState.PARTIAL -> ToggleableState.Indeterminate
                    FamilySelectionState.NONE_ENABLED -> ToggleableState.Off
                },
                onClick = onToggleFamily,
                modifier = Modifier.semantics { contentDescription = toggleDescription }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = familyName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.custom_program_family_count,
                        family.enabledCount,
                        family.exerciseCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        family.visibleExercises.forEach { exercise ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = exercise.enabled,
                        role = Role.Switch,
                        onValueChange = { onToggleExercise(exercise.id) }
                    )
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(exercise.nameRes),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = exercise.enabled,
                    onCheckedChange = null
                )
            }
        }
    }
}

@Composable
private fun EditorActions(
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.cancel))
            }
            Button(onClick = onApply, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.custom_program_apply))
            }
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.custom_program_reset))
        }
    }
}

@Composable
private fun ResetConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.custom_program_reset_confirm_title)) },
        text = { Text(text = stringResource(R.string.custom_program_reset_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.custom_program_reset))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

private fun familyStateLabelRes(state: FamilySelectionState): Int = when (state) {
    FamilySelectionState.ALL_ENABLED -> R.string.custom_program_family_all_enabled
    FamilySelectionState.PARTIAL -> R.string.custom_program_family_partial
    FamilySelectionState.NONE_ENABLED -> R.string.custom_program_family_none_enabled
}

private const val EMPTY_QUERY = ""
