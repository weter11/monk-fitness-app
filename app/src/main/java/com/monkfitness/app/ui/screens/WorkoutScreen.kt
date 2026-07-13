package com.monkfitness.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.WorkoutType
import com.monkfitness.app.ui.components.ExerciseHeroMedia
import com.monkfitness.app.ui.components.ExerciseItem
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.ui.components.MonkProgressIndicator
import com.monkfitness.app.ui.components.exerciseSummaryText
import com.monkfitness.app.viewmodel.MainViewModel

enum class WorkoutStep {
    OVERVIEW,
    WARMUP,
    MAIN,
    COMPLETE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    day: Int,
    viewModel: MainViewModel,
    isPostureMobilitySession: Boolean = false,
    onBack: () -> Unit,
    onExerciseClick: (Exercise) -> Unit
) {
    LaunchedEffect(day, isPostureMobilitySession) {
        viewModel.startWorkoutSession(
            day = day,
            mode = if (isPostureMobilitySession) MainViewModel.SessionMode.POSTURE_MOBILITY else MainViewModel.SessionMode.DAILY
        )
    }

    val uiState by viewModel.workoutSessionUiState.collectAsState()

    val currentStep by viewModel.currentStep.collectAsState()
    val exerciseIndex by viewModel.exerciseIndex.collectAsState()
    val isRestTime by viewModel.isRestTime.collectAsState()
    val restTargetIndex by viewModel.restTargetIndex.collectAsState()
    val completedExercises by viewModel.completedExercises.collectAsState()

    val sessionWarmupExercises = uiState.warmupExercises
    val isRestDay = uiState.workout.type == WorkoutType.REST || uiState.workout.exercises.isEmpty()
    val currentExerciseList by remember(currentStep, sessionWarmupExercises, uiState.workout.exercises) {
        derivedStateOf {
            when (currentStep) {
                WorkoutStep.WARMUP -> sessionWarmupExercises
                WorkoutStep.MAIN -> uiState.workout.exercises
                else -> emptyList()
            }
        }
    }
    val currentExercise by remember(currentExerciseList, exerciseIndex) {
        derivedStateOf { currentExerciseList.getOrNull(exerciseIndex) }
    }
    val progress by remember(currentStep, completedExercises, sessionWarmupExercises, uiState.workout.exercises) {
        derivedStateOf {
            val sessionExercises = sessionWarmupExercises + uiState.workout.exercises
            val totalSets = sessionExercises.sumOf { it.sets.coerceAtLeast(1) }
            if (totalSets <= 0) {
                0f
            } else {
                val completedSets = sessionExercises.sumOf { exercise ->
                    (completedExercises[exercise.id] ?: 0).coerceAtMost(exercise.sets.coerceAtLeast(1))
                }
                when (currentStep) {
                    WorkoutStep.OVERVIEW -> 0f
                    WorkoutStep.COMPLETE -> 1f
                    else -> completedSets.toFloat() / totalSets
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (isRestDay) stringResource(R.string.rest_day)
                            else when (currentStep) {
                                WorkoutStep.OVERVIEW -> stringResource(uiState.workout.type.nameRes)
                                WorkoutStep.WARMUP -> stringResource(R.string.warmup)
                                WorkoutStep.MAIN -> stringResource(R.string.main_workout)
                                WorkoutStep.COMPLETE -> stringResource(R.string.workout_complete)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.day_display, day),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.previous)
            )
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
            if (!isRestDay) {
                MonkProgressIndicator(
                    progress = progress,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (isRestDay) {
                    RestDayUI(onComplete = {
                        viewModel.completeCurrentSession(day)
                        onBack()
                    })
                } else if (isRestTime) {
                    RestUI(
                        viewModel = viewModel,
                        nextExercise = currentExerciseList.getOrNull(restTargetIndex ?: (exerciseIndex + 1)),
                        onSkip = { viewModel.nextExercise(currentExerciseList) }
                    )
                } else {
                    when (currentStep) {
                        WorkoutStep.OVERVIEW -> {
                            WorkoutOverview(
                                exercises = uiState.workout.exercises,
                                onStart = {
                                    viewModel.setWorkoutStep(
                                        if (sessionWarmupExercises.isEmpty()) WorkoutStep.MAIN else WorkoutStep.WARMUP
                                    )
                                },
                                onExerciseClick = onExerciseClick
                            )
                        }
                        WorkoutStep.WARMUP, WorkoutStep.MAIN -> {
                            currentExercise?.let { exercise ->
                                ExerciseSession(
                                    exercise = exercise,
                                    viewModel = viewModel,
                                    onNext = { viewModel.nextExercise(currentExerciseList) },
                                    onPrevious = { viewModel.previousExercise() },
                                    onInfo = { onExerciseClick(exercise) }
                                )
                            }
                        }
                        WorkoutStep.COMPLETE -> {
                            WorkoutComplete(
                                onFinish = {
                                    viewModel.completeCurrentSession(day)
                                    onBack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RestUI(
    viewModel: MainViewModel,
    nextExercise: Exercise?,
    onSkip: () -> Unit
) {
    val timeLeft by viewModel.timeLeft.collectAsState()
    val isTimerRunning by viewModel.isTimerRunning.collectAsState()

    LaunchedEffect(timeLeft, isTimerRunning) {
        if (timeLeft == 0 && isTimerRunning == false && viewModel.timeLeft.value == 0) {
            onSkip()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.rest),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.timer_format, timeLeft / 60, timeLeft % 60),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (nextExercise != null) {
            Text(
                text = stringResource(R.string.next_label, stringResource(nextExercise.nameRes)),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        MonkButton(
            text = stringResource(R.string.skip_rest),
            onClick = onSkip
        )
    }
}

@Composable
fun RestDayUI(onComplete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_exercise_placeholder),
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.rest_day),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.rest_day_message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(48.dp))

        MonkButton(
            text = stringResource(R.string.recovery_completed),
            onClick = onComplete
        )
    }
}

@Composable
fun WorkoutOverview(
    exercises: List<Exercise>,
    onStart: () -> Unit,
    onExerciseClick: (Exercise) -> Unit
) {
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(exercises, key = { _, it -> it.id }) { index, exercise ->
                ExerciseItem(
                    exercise = exercise,
                    isCompleted = false,
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
    viewModel: MainViewModel,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onInfo: () -> Unit
) {
    val scrollState = rememberScrollState()
    val timeLeft by viewModel.timeLeft.collectAsState()
    val isTimerRunning by viewModel.isTimerRunning.collectAsState()
    val completedExercises by viewModel.completedExercises.collectAsState()

    val difficultyAdjustment by viewModel.getExerciseDifficultyAdjustment(exercise.id).collectAsState(initial = 0)
    val personalRecord by viewModel.getExercisePersonalRecord(exercise.id).collectAsState(initial = 0)
    val totalSets = remember(exercise.sets) { exercise.sets.coerceAtLeast(1) }
    val completedSetCount = (completedExercises[exercise.id] ?: 0).coerceAtMost(totalSets)
    val currentSetNumber = (completedSetCount + 1).coerceAtMost(totalSets)
    val recordTarget = if (exercise.isTimerBased) exercise.durationSeconds else exercise.maxReps.coerceAtLeast(exercise.reps)
    val isNewRecordTarget = recordTarget > personalRecord

    LaunchedEffect(exercise.id, exercise.durationSeconds) {
        if (exercise.isTimerBased && !viewModel.isTimerRunning.value && viewModel.timeLeft.value == 0) {
            viewModel.resetTimer(exercise.durationSeconds)
            viewModel.startTimer(exercise.durationSeconds)
        }
    }

    LaunchedEffect(timeLeft, isTimerRunning) {
        if (exercise.isTimerBased && timeLeft == 0 && isTimerRunning == false && viewModel.timeLeft.value == 0) {
            onNext()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
            contentAlignment = Alignment.Center
        ) {
            ExerciseHeroMedia(
                exercise = exercise,
                modifier = Modifier.fillMaxSize(),
                height = 250.dp
            )
            IconButton(
                onClick = onInfo,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = stringResource(R.string.label_description),
                    tint = MaterialTheme.colorScheme.primary
                )
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
            text = exerciseSummaryText(exercise),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.current_set_format, currentSetNumber, totalSets),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                LinearProgressIndicator(
                    progress = { completedSetCount.toFloat() / totalSets },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.logged_sets_format, completedSetCount, totalSets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = if (exercise.isTimerBased) {
                        stringResource(R.string.personal_record_seconds, personalRecord)
                    } else {
                        stringResource(R.string.personal_record_reps, personalRecord)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (isNewRecordTarget) {
                    SuggestionChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.personal_record_target)) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        DifficultyAdjustmentCard(
            adjustment = difficultyAdjustment,
            label = stringResource(viewModel.getDifficultyLevelLabel(difficultyAdjustment)),
            onDecrease = { viewModel.adjustExerciseDifficulty(exercise.id, -1) },
            onIncrease = { viewModel.adjustExerciseDifficulty(exercise.id, 1) }
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (exercise.isTimerBased) {
            Text(
                text = stringResource(R.string.timer_format, timeLeft / 60, timeLeft % 60),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { viewModel.toggleTimer(exercise.durationSeconds) },
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
                    onClick = { viewModel.resetTimer(exercise.durationSeconds) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(stringResource(R.string.timer_reset))
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onPrevious,
                modifier = Modifier.height(56.dp).weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(if (completedSetCount > 0) R.string.undo_set else R.string.previous))
            }
            MonkButton(
                text = stringResource(if (completedSetCount + 1 >= totalSets) R.string.finish_exercise else R.string.complete_set),
                onClick = onNext,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun WorkoutComplete(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
