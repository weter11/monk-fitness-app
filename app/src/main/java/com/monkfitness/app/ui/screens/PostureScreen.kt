package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.ui.components.ExerciseItem
import com.monkfitness.app.viewmodel.MainViewModel

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PostureScreen(
    viewModel: MainViewModel,
    onExerciseClick: (Exercise) -> Unit
) {
    val uiState by viewModel.postureUiState.collectAsState()
    val searchQuery by viewModel.postureSearchQuery.collectAsState()
    var isFilterSheetVisible by rememberSaveable { mutableStateOf(false) }
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.exercise_library),
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = stringResource(R.string.exercise_library_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        PostureSearchField(
            query = searchQuery,
            onQueryChange = viewModel::setPostureSearchQuery
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { isFilterSheetVisible = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.filter_button))
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.filteredExercises.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_exercises_match),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            PostureExerciseList(
                exercises = uiState.filteredExercises,
                onExerciseClick = onExerciseClick,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (isFilterSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { isFilterSheetVisible = false },
            sheetState = filterSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .navigationBarsPadding()
            ) {
                FilterSection(
                    title = stringResource(R.string.filter_category),
                    allLabel = stringResource(R.string.filter_all_categories),
                    selectedOption = uiState.selectedCategory,
                    options = ExerciseCategory.entries.map { it.labelRes to it },
                    onSelect = viewModel::setPostureSelectedCategory
                )

                Spacer(modifier = Modifier.height(12.dp))

                FilterSection(
                    title = stringResource(R.string.filter_body_part),
                    allLabel = stringResource(R.string.filter_all_body_parts),
                    selectedOption = uiState.selectedSubCategory,
                    options = uiState.availableSubCategories.map { it.labelRes to it },
                    onSelect = viewModel::setPostureSelectedSubCategory
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = viewModel::clearPostureFilters,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.clear_filters),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PostureSearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.search_exercises_placeholder)) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }
        },
        singleLine = true
    )
}

@Composable
private fun PostureExerciseList(
    exercises: List<Exercise>,
    onExerciseClick: (Exercise) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier) {
        items(exercises, key = { it.id }) { exercise ->
            ExerciseItem(
                exercise = exercise,
                isCompleted = false,
                onInfo = { onExerciseClick(exercise) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FilterSection(
    title: String,
    allLabel: String,
    selectedOption: T?,
    options: List<Pair<Int, T>>,
    onSelect: (T?) -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedOption == null,
                onClick = { onSelect(null) },
                label = {
                    Text(
                        text = allLabel,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
            options.forEach { (labelRes, value) ->
                FilterChip(
                    selected = selectedOption == value,
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            text = stringResource(labelRes),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}
