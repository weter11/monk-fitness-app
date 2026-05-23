package com.monkfitness.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.ui.components.ExerciseItem
import com.monkfitness.app.viewmodel.MainViewModel

@Composable
fun PostureScreen(
    viewModel: MainViewModel,
    onExerciseClick: (Exercise) -> Unit
) {
    val exercises = remember { viewModel.getExerciseLibrary() }
    var selectedCategory by remember { mutableStateOf<ExerciseCategory?>(null) }
    var selectedSubCategory by remember { mutableStateOf<ExerciseSubCategory?>(null) }
    var draftCategory by remember { mutableStateOf<ExerciseCategory?>(null) }
    var draftSubCategory by remember { mutableStateOf<ExerciseSubCategory?>(null) }
    var isFilterPanelVisible by rememberSaveable { mutableStateOf(false) }

    val filteredExercises = remember(exercises, selectedCategory, selectedSubCategory) {
        exercises.filter { exercise ->
            (selectedCategory == null || exercise.category == selectedCategory) &&
                (selectedSubCategory == null || exercise.subCategory == selectedSubCategory)
        }
    }

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

        OutlinedButton(
            onClick = {
                draftCategory = selectedCategory
                draftSubCategory = selectedSubCategory
                isFilterPanelVisible = !isFilterPanelVisible
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.filter_button))
        }

        AnimatedVisibility(visible = isFilterPanelVisible) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    FilterSection(
                        title = stringResource(R.string.filter_category),
                        allLabel = stringResource(R.string.filter_all_categories),
                        selectedLabel = draftCategory?.let { stringResource(it.labelRes) },
                        options = ExerciseCategory.entries.map { it.labelRes to it },
                        onSelect = { draftCategory = it }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    FilterSection(
                        title = stringResource(R.string.filter_body_part),
                        allLabel = stringResource(R.string.filter_all_body_parts),
                        selectedLabel = draftSubCategory?.let { stringResource(it.labelRes) },
                        options = ExerciseSubCategory.entries.map { it.labelRes to it },
                        onSelect = { draftSubCategory = it }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                draftCategory = null
                                draftSubCategory = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.clear_filters))
                        }
                        Button(
                            onClick = {
                                selectedCategory = draftCategory
                                selectedSubCategory = draftSubCategory
                                isFilterPanelVisible = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.apply_filters))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredExercises.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_exercises_match),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredExercises, key = { it.id }) { exercise ->
                    ExerciseItem(
                        exercise = exercise,
                        isCompleted = false,
                        onToggle = {},
                        onInfo = { onExerciseClick(exercise) }
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> FilterSection(
    title: String,
    allLabel: String,
    selectedLabel: String?,
    options: List<Pair<Int, T>>,
    onSelect: (T?) -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedLabel == null,
                onClick = { onSelect(null) },
                label = { Text(allLabel) }
            )
            options.forEach { (labelRes, value) ->
                val label = stringResource(labelRes)
                FilterChip(
                    selected = selectedLabel == label,
                    onClick = { onSelect(value) },
                    label = { Text(label) }
                )
            }
        }
    }
}
