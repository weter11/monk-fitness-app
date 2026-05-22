package com.monkfitness.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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

    val doneCount = workout.exercises.count { completedExercises[it.id] == true }
    val targetProgress = if (workout.exercises.isEmpty()) 0f else doneCount.toFloat() / workout.exercises.size.toFloat()
    val progress by animateFloatAsState(targetValue = targetProgress, animationSpec = tween(550), label = "workoutProgress")
    val currentIndex = workout.exercises.indexOfFirst { completedExercises[it.id] != true }
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
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            Text("$doneCount/${workout.exercises.size} completed", modifier = Modifier.padding(bottom = 8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(workout.exercises, key = { _, e -> e.id }) { index, exercise ->
                    ExerciseItem(
                        exercise = exercise,
                        isCompleted = completedExercises[exercise.id] ?: false,
                        isCurrent = index == currentIndex,
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
