package com.monkfitness.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.platform.VibrationFeedback
import com.monkfitness.app.ui.programs.ProgramSessionController
import com.monkfitness.app.ui.programs.ProgramSessionNotice
import com.monkfitness.app.ui.programs.ProgramSessionUiState
import com.monkfitness.app.ui.programs.SessionExerciseUi
import com.monkfitness.app.ui.programs.SessionStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A temporary UI rest affordance; rest duration is not a session or prescription fact. */
private const val REST_SECONDS = 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramSessionScreen(
    controller: ProgramSessionController,
    slotId: String,
    vibrationEnabled: Boolean,
    onBack: () -> Unit,
    onExerciseClick: (String) -> Unit
) {
    LaunchedEffect(slotId) { controller.open(slotId) }

    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    DisposableEffect(view, state.isPresenting) {
        view.keepScreenOn = state.isPresenting
        onDispose { view.keepScreenOn = false }
    }

    var restSecondsLeft by remember { mutableIntStateOf(0) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(false) }
    var confirmPending by remember { mutableStateOf(false) }

    val current = state.currentExercise
    var actualValue by remember(current?.sessionExerciseId, current?.completedSets) {
        mutableStateOf(current?.initialActualValue().orEmpty())
    }
    var actualInputInvalid by remember(current?.sessionExerciseId, current?.completedSets) {
        mutableStateOf(false)
    }
    var timerRemaining by remember(current?.sessionExerciseId, current?.completedSets, current?.nextTarget) {
        mutableIntStateOf(current?.nextTarget ?: 0)
    }
    var timerRunning by remember(current?.sessionExerciseId, current?.completedSets) {
        mutableStateOf(false)
    }

    val notice = state.notice
    LaunchedEffect(notice) { showNotice = notice != null }

    LaunchedEffect(restSecondsLeft, state.confirmedSetCount, current?.sessionExerciseId) {
        if (restSecondsLeft > 0) {
            delay(1_000)
            restSecondsLeft = (restSecondsLeft - 1).coerceAtLeast(0)
            if (restSecondsLeft == 0 && vibrationEnabled) VibrationFeedback.buzz(context)
        }
    }

    LaunchedEffect(timerRunning, timerRemaining, current?.sessionExerciseId, current?.completedSets) {
        if (timerRunning && timerRemaining > 0) {
            delay(1_000)
            timerRemaining = (timerRemaining - 1).coerceAtLeast(0)
            actualValue = ((current?.nextTarget ?: 0) - timerRemaining).coerceAtLeast(0).toString()
            if (timerRemaining == 0) {
                timerRunning = false
                if (vibrationEnabled) VibrationFeedback.buzz(context)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.programName ?: stringResource(R.string.programs_session_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        state.plannedFor?.let { date ->
                            Text(
                                text = stringResource(R.string.programs_session_planned_date, date.toString()),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        controller.back()
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.previous)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state.stage) {
                SessionStage.LOADING -> Text(
                    text = stringResource(R.string.programs_session_loading),
                    style = MaterialTheme.typography.bodyLarge
                )

                SessionStage.UNAVAILABLE -> Text(
                    text = stringResource(
                        (state.notice ?: ProgramSessionNotice.SLOT_UNAVAILABLE).messageRes
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )

                SessionStage.CANCELLED -> {
                    Text(
                        text = stringResource(R.string.programs_session_result_cancelled),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ok))
                    }
                }

                SessionStage.COMPLETED -> CompletedWorkout(state = state, onDone = onBack)

                SessionStage.PRESENTING -> {
                    WorkoutProgress(state = state)

                    if (restSecondsLeft > 0 && current != null) {
                        RestScreen(
                            exercise = current,
                            secondsLeft = restSecondsLeft,
                            onSkipRest = { restSecondsLeft = 0 }
                        )
                    } else if (current != null) {
                        CurrentExercise(
                            exercise = current,
                            actualValue = actualValue,
                            actualInputInvalid = actualInputInvalid,
                            timerRemaining = timerRemaining,
                            timerRunning = timerRunning,
                            confirmPending = confirmPending,
                            onExerciseClick = onExerciseClick,
                            onActualValueChange = { value ->
                                actualValue = value.filter(Char::isDigit)
                                actualInputInvalid = false
                            },
                            onConfirm = {
                                if (!confirmPending) {
                                    confirmPending = true
                                    scope.launch {
                                        try {
                                            val stored = controller.confirmActualSet(actualValue)
                                            actualInputInvalid = !stored
                                            if (stored) {
                                                restSecondsLeft =
                                                    if (controller.state.value.currentExercise != null) {
                                                        REST_SECONDS
                                                    } else {
                                                        0
                                                    }
                                                if (vibrationEnabled) VibrationFeedback.buzz(context)
                                            }
                                        } finally {
                                            confirmPending = false
                                        }
                                    }
                                }
                            },
                            onTimerToggle = {
                                if (timerRunning) {
                                    timerRunning = false
                                } else if (timerRemaining > 0) {
                                    timerRunning = true
                                }
                            },
                            onTimerReset = {
                                timerRemaining = current.nextTarget
                                timerRunning = false
                                actualValue = "0"
                                actualInputInvalid = false
                            }
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.programs_session_completed),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (restSecondsLeft == 0) {
                        ExerciseNavigation(
                            state = state,
                            onPrevious = { controller.moveExerciseFocus(-1) },
                            onNext = { controller.moveExerciseFocus(1) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCancelDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.programs_session_cancel))
                        }
                        Button(
                            onClick = {
                                if (state.everythingConfirmed) {
                                    scope.launch { controller.finish() }
                                } else {
                                    showFinishDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.programs_session_finish))
                        }
                    }
                }
            }
        }
    }

    if (showFinishDialog) {
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinishDialog = false
                        scope.launch { controller.finish() }
                    }
                ) {
                    Text(stringResource(R.string.programs_session_finish))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.programs_session_finish_early_title)) },
            text = { Text(stringResource(R.string.programs_session_finish_early_text)) }
        )
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        scope.launch { controller.cancel() }
                    }
                ) {
                    Text(stringResource(R.string.programs_session_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.programs_session_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.programs_session_cancel_confirm_text)) }
        )
    }

    val shownNotice = state.notice
    if (showNotice && shownNotice != null && state.stage == SessionStage.PRESENTING) {
        AlertDialog(
            onDismissRequest = {
                showNotice = false
                controller.dismissNotice()
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotice = false
                        controller.dismissNotice()
                    }
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.programs_session_notice_title)) },
            text = { Text(stringResource(shownNotice.messageRes)) }
        )
    }
}

@Composable
private fun WorkoutProgress(state: ProgramSessionUiState) {
    val progress = if (state.prescribedSetCount == 0) {
        0f
    } else {
        (state.confirmedSetCount.toFloat() / state.prescribedSetCount.toFloat()).coerceIn(0f, 1f)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(
                R.string.programs_session_sets_progress,
                state.confirmedSetCount,
                state.prescribedSetCount
            ),
            style = MaterialTheme.typography.labelLarge
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )
    }
}

@Composable
private fun CurrentExercise(
    exercise: SessionExerciseUi,
    actualValue: String,
    actualInputInvalid: Boolean,
    timerRemaining: Int,
    timerRunning: Boolean,
    confirmPending: Boolean,
    onExerciseClick: (String) -> Unit,
    onActualValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onTimerToggle: () -> Unit,
    onTimerReset: () -> Unit
) {
    val exerciseName = exercise.displayName()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(exercise.imageRes ?: R.drawable.ic_exercise_placeholder),
                    contentDescription = exerciseName,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Text(
                text = exerciseName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (exercise.descriptionRes != 0) {
                Text(
                    text = stringResource(exercise.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = { onExerciseClick(exercise.exerciseId) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text(stringResource(R.string.programs_session_exercise_details))
            }

            if (!exercise.isSkipped) {
                Text(
                    text = stringResource(
                        R.string.programs_session_sets_progress,
                        exercise.completedSets.coerceAtMost(exercise.setCount),
                        exercise.setCount
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            when {
                exercise.isSkipped -> Text(
                    text = stringResource(R.string.programs_session_exercise_skipped),
                    style = MaterialTheme.typography.titleMedium
                )

                exercise.isFinished -> Text(
                    text = stringResource(R.string.programs_session_completed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                else -> {
                    val target = when (exercise.dimension) {
                        PrescriptionDimension.TIME_BASED ->
                            stringResource(R.string.programs_session_seconds_target, exercise.nextTarget)

                        else -> stringResource(R.string.programs_session_reps_target, exercise.nextTarget)
                    }
                    Text(
                        text = stringResource(R.string.programs_session_target_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = target,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (exercise.dimension == PrescriptionDimension.TIME_BASED) {
                        TimeSetTimer(
                            secondsLeft = timerRemaining,
                            timerRunning = timerRunning,
                            controlsEnabled = !confirmPending,
                            actualValue = actualValue,
                            onToggle = onTimerToggle,
                            onReset = onTimerReset
                        )
                    }

                    OutlinedTextField(
                        value = actualValue,
                        onValueChange = onActualValueChange,
                        label = {
                            Text(
                                stringResource(
                                    if (exercise.dimension == PrescriptionDimension.TIME_BASED) {
                                        R.string.programs_session_actual_seconds
                                    } else {
                                        R.string.programs_session_actual_reps
                                    }
                                )
                            )
                        },
                        singleLine = true,
                        isError = actualInputInvalid,
                        supportingText = if (actualInputInvalid) {
                            { Text(stringResource(R.string.programs_session_actual_invalid)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.headlineMedium,
                        enabled = !timerRunning && !confirmPending,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onConfirm,
                        enabled = !timerRunning && !confirmPending &&
                            actualValue.toIntOrNull()?.let { value -> value > 0 } == true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(stringResource(R.string.programs_session_confirm_set))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeSetTimer(
    secondsLeft: Int,
    timerRunning: Boolean,
    controlsEnabled: Boolean,
    actualValue: String,
    onToggle: () -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "%02d:%02d".format(secondsLeft / 60, secondsLeft % 60),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        if (secondsLeft == 0 && actualValue.toIntOrNull()?.let { it > 0 } == true) {
            Text(
                text = stringResource(R.string.programs_session_timer_complete),
                style = MaterialTheme.typography.labelLarge
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onToggle,
                enabled = controlsEnabled && (timerRunning || secondsLeft > 0)
            ) {
                Text(
                    stringResource(
                        if (timerRunning) R.string.timer_pause else R.string.timer_start
                    )
                )
            }
            TextButton(
                onClick = onReset,
                enabled = controlsEnabled && !timerRunning
            ) {
                Text(stringResource(R.string.timer_reset))
            }
        }
    }
}

@Composable
private fun RestScreen(
    exercise: SessionExerciseUi,
    secondsLeft: Int,
    onSkipRest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.programs_session_rest, secondsLeft),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.programs_session_next_exercise, exercise.displayName()),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(onClick = onSkipRest) {
                Text(stringResource(R.string.programs_session_skip_rest))
            }
        }
    }
}

@Composable
private fun ExerciseNavigation(
    state: ProgramSessionUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val currentIndex = state.exercises.indexOfFirst { exercise -> exercise.isCurrent }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onPrevious,
            enabled = currentIndex > 0,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.previous))
        }
        OutlinedButton(
            onClick = onNext,
            enabled = currentIndex >= 0 && currentIndex < state.exercises.lastIndex,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.next))
        }
    }
}

@Composable
private fun CompletedWorkout(state: ProgramSessionUiState, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.programs_session_result_completed),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(
                R.string.programs_session_sets_progress,
                state.confirmedSetCount,
                state.prescribedSetCount
            ),
            style = MaterialTheme.typography.bodyLarge
        )
        state.notice?.let { completedNotice ->
            Text(
                text = stringResource(completedNotice.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ok))
        }
    }
}

@Composable
private fun SessionExerciseUi.displayName(): String =
    if (nameRes == 0) exerciseId else stringResource(nameRes)

private fun SessionExerciseUi.initialActualValue(): String =
    when (dimension) {
        PrescriptionDimension.TIME_BASED -> "0"
        else -> nextTarget.takeIf { target -> target > 0 }?.toString().orEmpty()
    }
