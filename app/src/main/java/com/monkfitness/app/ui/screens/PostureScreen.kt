package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.ui.components.ExerciseItem
import com.monkfitness.app.viewmodel.MainViewModel

@Composable
fun PostureScreen(
    viewModel: MainViewModel,
    onExerciseClick: (Exercise) -> Unit
) {
    val exercises = remember { viewModel.getPostureExercises() }

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

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(exercises, key = { it.id }) { exercise ->
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
