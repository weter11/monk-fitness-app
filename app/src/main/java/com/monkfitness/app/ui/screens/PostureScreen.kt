package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.MainCategory
import com.monkfitness.app.data.model.SubCategory
import com.monkfitness.app.ui.components.ExerciseItem
import com.monkfitness.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostureScreen(
    viewModel: MainViewModel,
    onExerciseClick: (Exercise) -> Unit
) {
    val allExercises = remember { viewModel.getAllLibraryExercises() }
    var selectedMainCategory by remember { mutableStateOf<MainCategory?>(null) }
    var selectedSubCategory by remember { mutableStateOf<SubCategory?>(null) }

    val filteredExercises = remember(selectedMainCategory, selectedSubCategory) {
        allExercises.filter { exercise ->
            (selectedMainCategory == null || exercise.mainCategory == selectedMainCategory) &&
            (selectedSubCategory == null || exercise.subCategory == selectedSubCategory)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.posture_training),
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = stringResource(R.string.posture_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Main Category Filter
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = selectedMainCategory == null,
                    onClick = { selectedMainCategory = null },
                    label = { Text(stringResource(R.string.filter_all)) }
                )
            }
            items(MainCategory.values()) { category ->
                FilterChip(
                    selected = selectedMainCategory == category,
                    onClick = { selectedMainCategory = category },
                    label = { Text(stringResource(category.nameRes)) }
                )
            }
        }

        // Sub Category Filter
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedSubCategory == null,
                    onClick = { selectedSubCategory = null },
                    label = { Text(stringResource(R.string.filter_all)) }
                )
            }
            items(SubCategory.values()) { subCategory ->
                FilterChip(
                    selected = selectedSubCategory == subCategory,
                    onClick = { selectedSubCategory = subCategory },
                    label = { Text(stringResource(subCategory.nameRes)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
