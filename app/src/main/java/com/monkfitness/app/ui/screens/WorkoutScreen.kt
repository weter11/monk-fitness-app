package com.monkfitness.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.ui.components.ExerciseItem
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.ui.components.MonkProgressIndicator
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

    val totalExercises = workout.exercises.size
    val completedCount = completedExercises.values.count { it }
    val progress = if (totalExercises > 0) completedCount.toFloat() / totalExercises else 0f
    val allDone = totalExercises > 0 && completedCount == totalExercises

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            workout.type.name.replace("_", " "),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.day) + " $day",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            MonkProgressIndicator(
                progress = progress,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(workout.exercises, key = { _, it -> it.id }) { index, exercise ->
                    val isCurrent = !isCompleted(exercise.id, completedExercises) &&
                                    (index == 0 || workout.exercises.take(index).all { completedExercises[it.id] == true })

                    Box(modifier = Modifier.padding(vertical = 4.dp)) {
                        ExerciseItem(
                            exercise = exercise,
                            isCompleted = completedExercises[exercise.id] ?: false,
                            onToggle = { completedExercises[exercise.id] = it },
                            onInfo = { onExerciseClick(exercise) },
                            isCurrent = isCurrent
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = allDone,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
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
}

private fun isCompleted(id: String, completedMap: Map<String, Boolean>): Boolean {
    return completedMap[id] == true
}
