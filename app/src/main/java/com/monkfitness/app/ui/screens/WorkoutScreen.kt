package com.monkfitness.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.ui.components.ExerciseItem
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.ui.components.MonkProgressIndicator
import com.monkfitness.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay

enum class WorkoutStep {
    OVERVIEW,
    WARMUP,
    MAIN,
    POSTURE,
    COMPLETE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    day: Int,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onExerciseClick: (Exercise) -> Unit
) {
    val workout = remember(day) { viewModel.getWorkoutForDay(day) }
    val warmupExercises = remember { viewModel.getWarmupExercises() }
    val postureExercises = remember { viewModel.getPostureExercises().take(3) } // 3-5 as requested

    var currentStep by remember { mutableStateOf(WorkoutStep.OVERVIEW) }
    var exerciseIndex by remember { mutableIntStateOf(0) }

    val currentExerciseList = when (currentStep) {
        WorkoutStep.WARMUP -> warmupExercises
        WorkoutStep.MAIN -> workout.exercises
        WorkoutStep.POSTURE -> postureExercises
        else -> emptyList()
    }

    val currentExercise = currentExerciseList.getOrNull(exerciseIndex)

    val totalSteps = warmupExercises.size + workout.exercises.size + postureExercises.size
    val currentAbsoluteIndex = when (currentStep) {
        WorkoutStep.OVERVIEW -> 0
        WorkoutStep.WARMUP -> exerciseIndex
        WorkoutStep.MAIN -> warmupExercises.size + exerciseIndex
        WorkoutStep.POSTURE -> warmupExercises.size + workout.exercises.size + exerciseIndex
        WorkoutStep.COMPLETE -> totalSteps
    }
    val progress = if (totalSteps > 0) currentAbsoluteIndex.toFloat() / totalSteps else 0f

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when (currentStep) {
                                WorkoutStep.OVERVIEW -> workout.type.name.replace("_", " ")
                                WorkoutStep.WARMUP -> stringResource(R.string.warmup)
                                WorkoutStep.MAIN -> stringResource(R.string.main_workout)
                                WorkoutStep.POSTURE -> stringResource(R.string.posture_block)
                                WorkoutStep.COMPLETE -> stringResource(R.string.workout_complete)
                            },
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

            Box(modifier = Modifier.weight(1f)) {
                when (currentStep) {
                    WorkoutStep.OVERVIEW -> {
                        WorkoutOverview(
                            exercises = workout.exercises,
                            onStart = { currentStep = WorkoutStep.WARMUP },
                            onExerciseClick = onExerciseClick
                        )
                    }
                    WorkoutStep.WARMUP, WorkoutStep.MAIN, WorkoutStep.POSTURE -> {
                        currentExercise?.let { exercise ->
                            ExerciseSession(
                                exercise = exercise,
                                onNext = {
                                    if (exerciseIndex < currentExerciseList.size - 1) {
                                        exerciseIndex++
                                    } else {
                                        exerciseIndex = 0
                                        currentStep = when (currentStep) {
                                            WorkoutStep.WARMUP -> WorkoutStep.MAIN
                                            WorkoutStep.MAIN -> WorkoutStep.POSTURE
                                            WorkoutStep.POSTURE -> WorkoutStep.COMPLETE
                                            else -> WorkoutStep.COMPLETE
                                        }
                                    }
                                },
                                onInfo = { onExerciseClick(exercise) }
                            )
                        }
                    }
                    WorkoutStep.COMPLETE -> {
                        WorkoutComplete(
                            onFinish = {
                                viewModel.completeWorkout(day)
                                onBack()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WorkoutOverview(
    exercises: List<Exercise>,
    onStart: () -> Unit,
    onExerciseClick: (Exercise) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(exercises, key = { _, it -> it.id }) { index, exercise ->
                ExerciseItem(
                    exercise = exercise,
                    isCompleted = false,
                    onToggle = { },
                    onInfo = { onExerciseClick(exercise) },
                    isCurrent = index == 0
                )
            }
        }
        Box(modifier = Modifier.padding(16.dp)) {
            MonkButton(
                text = stringResource(R.string.start_workout),
                onClick = onStart
            )
        }
    }
}

@Composable
fun ExerciseSession(
    exercise: Exercise,
    onNext: () -> Unit,
    onInfo: () -> Unit
) {
    var timeLeft by remember(exercise.id) { mutableIntStateOf(exercise.durationSeconds) }
    var isTimerRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isTimerRunning, timeLeft) {
        if (isTimerRunning && timeLeft > 0) {
            delay(1000)
            timeLeft--
            if (timeLeft == 0) {
                isTimerRunning = false
                onNext()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(exercise.imageRes),
                contentDescription = null,
                modifier = Modifier.size(140.dp),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
            )
            IconButton(
                onClick = onInfo,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Default.Info, contentDescription = "Info", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(exercise.nameRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (exercise.isTimerBased) "${exercise.durationSeconds}s"
            else "${exercise.sets} × ${exercise.reps}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (exercise.isTimerBased) {
            Text(
                text = String.format("%02d:%02d", timeLeft / 60, timeLeft % 60),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { isTimerRunning = !isTimerRunning },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(56.dp).weight(1f)
                ) {
                    Icon(
                        if (isTimerRunning) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isTimerRunning) stringResource(R.string.timer_pause) else stringResource(R.string.timer_start))
                }

                OutlinedButton(
                    onClick = {
                        timeLeft = exercise.durationSeconds
                        isTimerRunning = false
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(stringResource(R.string.timer_reset))
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        MonkButton(
            text = stringResource(R.string.next),
            onClick = onNext
        )
    }
}

@Composable
fun WorkoutComplete(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.workout_complete),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.workout_complete_desc),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(48.dp))

        MonkButton(
            text = stringResource(R.string.finish),
            onClick = onFinish
        )
    }
}
