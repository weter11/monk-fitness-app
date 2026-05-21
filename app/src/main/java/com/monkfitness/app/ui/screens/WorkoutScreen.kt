package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.ui.components.ExerciseItem
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    day: Int,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onExerciseClick: (Exercise) -> Unit
) {
    val workout = remember(day) { viewModel.getWorkoutForDay(day) }
    val completedExercises = remember { mutableStateMapOf<String, Boolean>() }

    val allDone = workout.exercises.isNotEmpty() && workout.exercises.all { completedExercises[it.id] == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workout.type.name.replace("_", " ")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(workout.exercises, key = { it.id }) { exercise ->
                    ExerciseItem(
                        exercise = exercise,
                        isCompleted = completedExercises[exercise.id] ?: false,
                        onToggle = { completedExercises[exercise.id] = it },
                        onInfo = { onExerciseClick(exercise) }
                    )
                }
            }

            if (allDone) {
                MonkButton(
                    text = stringResource(R.string.complete_workout),
                    onClick = {
                        viewModel.completeWorkout(day)
                        onBack()
                    }
                )
            }
        }
    }
}
