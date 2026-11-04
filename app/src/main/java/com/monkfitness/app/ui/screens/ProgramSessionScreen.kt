package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.platform.VibrationFeedback
import com.monkfitness.app.ui.components.ExerciseHeroMedia
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.ui.programs.ProgramSessionController
import com.monkfitness.app.ui.programs.ProgramSessionNotice
import com.monkfitness.app.ui.programs.ProgramSessionUiState
import com.monkfitness.app.ui.programs.SessionExerciseUi
import com.monkfitness.app.ui.programs.SessionStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The seconds one `+`/`−` press changes a timed set by. */
private const val SECONDS_STEP = 5

/**
 * **The workout session screen** — the one production workout runtime.
 *
 * It is deliberately thin: **every** operation it offers is a call on [ProgramSessionController], which
 * calls `SessionRuntime`. The screen starts nothing, counts nothing, decides nothing about completion and
 * writes nothing — opening the route starts or restores, Confirm set appends one set row, Finish and
 * Cancel are the controller's, and Back is not a fact about the workout. The rest interval and the set
 * timer are read from the controller's own state; the screen only moves the clock on.
 *
 * The attempt's identity is the **slot** it is opened for, and it is restored by what is stored — which is
 * what makes "close the app mid-workout and come back" work without a second model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramSessionScreen(
    controller: ProgramSessionController,
    slotId: String,
    vibrationEnabled: Boolean,
    onBack: () -> Unit,
    onExerciseClick: (String) -> Unit,
    now: () -> Long,
    exerciseOf: (String) -> Exercise? = { null },
    onBeep: () -> Unit = {}
) {
    LaunchedEffect(slotId) { controller.open(slotId) }

    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The screen a workout is running on stays on, owned by the only screen that knows one is running.
    val view = LocalView.current
    LaunchedEffect(state.isPresenting) { view.keepScreenOn = state.isPresenting }

    var showFinishDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(false) }
    var showRestDialog by remember { mutableStateOf(false) }

    val notice = state.notice
    LaunchedEffect(notice) { showNotice = notice != null }

    // One second of rest, ticked by the controller so what the screen shows and what the next set stores
    // are the same interval.
    LaunchedEffect(state.restSecondsLeft) {
        if (state.restSecondsLeft <= 0) return@LaunchedEffect
        delay(1_000)
        if (controller.tickRest()) {
            onBeep()
            if (vibrationEnabled) VibrationFeedback.buzz(context)
        }
    }

    var nowMillis by remember { mutableLongStateOf(now()) }
    LaunchedEffect(state.isPresenting) {
        while (state.isPresenting) {
            nowMillis = now()
            delay(1_000)
        }
    }
    val elapsedSeconds = state.startedAtMillis
        ?.let { started -> ((nowMillis - started) / 1_000).coerceAtLeast(0).toInt() }
        ?: 0

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
                    IconButton(
                        onClick = {
                            controller.back()
                            onBack()
                        }
                    ) {
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
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

                SessionStage.CANCELLED -> Text(
                    text = stringResource(R.string.programs_session_result_cancelled),
                    style = MaterialTheme.typography.headlineSmall
                )

                SessionStage.COMPLETED -> CompletedSummary(state)

                SessionStage.PRESENTING -> PresentingSession(
                    state = state,
                    controller = controller,
                    elapsedSeconds = elapsedSeconds,
                    vibrationEnabled = vibrationEnabled,
                    exerciseOf = exerciseOf,
                    onBeep = onBeep,
                    onRestLengthClick = { showRestDialog = true },
                    onExerciseClick = onExerciseClick,
                    onFinishClick = {
                        if (state.everythingConfirmed) {
                            scope.launch { controller.finish() }
                        } else {
                            showFinishDialog = true
                        }
                    },
                    onCancelClick = { showCancelDialog = true }
                )
            }
        }
    }

    if (showRestDialog) {
        RestLengthDialog(
            currentSeconds = state.restSeconds,
            onDismiss = { showRestDialog = false },
            onSave = { raw ->
                scope.launch {
                    if (controller.setRestSecondsFromInput(raw)) {
                        showRestDialog = false
                    }
                }
            },
            onNoRest = {
                scope.launch {
                    controller.chooseNoRest()
                    showRestDialog = false
                }
            }
        )
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
private fun CompletedSummary(state: ProgramSessionUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.programs_session_result_completed),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.programs_session_sets_progress,
                state.confirmedSetCount,
                state.prescribedSetCount
            ),
            style = MaterialTheme.typography.bodyLarge
        )
        if (state.outcomes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.programs_session_outcomes_title),
                style = MaterialTheme.typography.titleMedium
            )
            for (outcome in state.outcomes) {
                val sentence = stringResource(outcome.messageRes)
                Text(
                    text = outcome.familyNameRes?.let { nameRes ->
                        stringResource(
                            R.string.programs_session_outcome_line,
                            stringResource(nameRes),
                            sentence
                        )
                    } ?: sentence,
                    style = MaterialTheme.typography.bodyMedium
                )
                outcome.noteRes?.let { noteRes ->
                    Text(
                        text = stringResource(noteRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        state.notice?.let { completedNotice ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(completedNotice.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun PresentingSession(
    state: ProgramSessionUiState,
    controller: ProgramSessionController,
    elapsedSeconds: Int,
    vibrationEnabled: Boolean,
    exerciseOf: (String) -> Exercise?,
    onBeep: () -> Unit,
    onRestLengthClick: () -> Unit,
    onExerciseClick: (String) -> Unit,
    onFinishClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = state.currentExercise
    val timed = current?.takeIf { exercise -> exercise.dimension == PrescriptionDimension.TIME_BASED }

    var actualValue by remember(current?.sessionExerciseId, current?.completedSets) {
        mutableStateOf(current?.nextTarget?.toString().orEmpty())
    }
    var actualInputInvalid by remember { mutableStateOf(false) }

    var timerLeft by remember(timed?.sessionExerciseId, timed?.completedSets) {
        mutableIntStateOf(timed?.nextTarget ?: 0)
    }
    var timerRunning by remember(timed?.sessionExerciseId, timed?.completedSets) {
        mutableStateOf(false)
    }

    // The countdown writes what it has measured into the field the user confirms, so one value is stored
    // whether the set ran to zero or was stopped early.
    LaunchedEffect(timerRunning, timed?.sessionExerciseId, timed?.completedSets) {
        if (!timerRunning) return@LaunchedEffect
        var left = timerLeft
        while (left > 0 && !state.isResting) {
            delay(1_000)
            left -= 1
            timerLeft = left
            actualValue = ((timed?.nextTarget ?: 0) - left).coerceAtLeast(1).toString()
        }
        if (left <= 0) {
            timerRunning = false
            onBeep()
            if (vibrationEnabled) VibrationFeedback.buzz(context)
            actualValue = (timed?.nextTarget ?: 0).toString()
            scope.launch { controller.confirmSet(durationSeconds = timed?.nextTarget ?: 0) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.programs_session_elapsed,
                            stringResource(R.string.timer_format)
                                .format(elapsedSeconds / 60, elapsedSeconds % 60)
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = stringResource(
                            R.string.logged_sets_format,
                            state.confirmedSetCount,
                            state.prescribedSetCount
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                TextButton(onClick = onRestLengthClick) {
                    Text(text = stringResource(R.string.programs_session_rest, state.restSeconds))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = {
                    if (state.prescribedSetCount == 0) {
                        0f
                    } else {
                        state.confirmedSetCount.toFloat() / state.prescribedSetCount.toFloat()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (state.isResting) {
                RestCard(
                    state = state,
                    onSkipRest = { controller.skipRest() },
                    onExtendRest = { controller.extendRest(15) }
                )
            } else {
                current?.let { exercise ->
                    FocusCard(
                        exercise = exercise,
                        demo = exerciseOf(exercise.exerciseId),
                        actualValue = actualValue,
                        actualInputInvalid = actualInputInvalid,
                        timeLeft = timerLeft,
                        timerRunning = timerRunning,
                        onActualChange = { value ->
                            actualValue = value.filter(Char::isDigit)
                            actualInputInvalid = false
                        },
                        onStep = { delta ->
                            val step = if (exercise.dimension == PrescriptionDimension.TIME_BASED) {
                                SECONDS_STEP
                            } else {
                                1
                            }
                            val next = (actualValue.toIntOrNull() ?: 0) + delta * step
                            actualValue = next.coerceAtLeast(1).toString()
                            actualInputInvalid = false
                        },
                        onToggleTimer = {
                            if (timerLeft <= 0) timerLeft = exercise.nextTarget
                            timerRunning = !timerRunning
                        },
                        onResetTimer = {
                            timerRunning = false
                            timerLeft = exercise.nextTarget
                            actualValue = exercise.nextTarget.toString()
                        },
                        onExerciseClick = { onExerciseClick(exercise.exerciseId) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            ExerciseStrip(
                exercises = state.exercises,
                onSelect = { sessionExerciseId -> scope.launch { controller.focus(sessionExerciseId) } }
            )
        }

        if (!state.isResting && current != null) {
            Spacer(modifier = Modifier.height(8.dp))
            MonkButton(
                text = stringResource(R.string.programs_session_confirm_set),
                onClick = {
                    scope.launch {
                        val stored = controller.confirmActualSet(actualValue)
                        actualInputInvalid = !stored
                        if (stored && vibrationEnabled) VibrationFeedback.buzz(context)
                    }
                },
                enabled = state.canConfirmCurrent
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { scope.launch { controller.undoLastSet() } },
                modifier = Modifier.weight(1f),
                enabled = state.confirmedSetCount > 0
            ) {
                Text(text = stringResource(R.string.undo_set))
            }
            OutlinedButton(
                onClick = { scope.launch { controller.skipCurrentExercise() } },
                modifier = Modifier.weight(1f),
                enabled = current != null && current.isPerformable && current.completedSets == 0
            ) {
                Text(text = stringResource(R.string.programs_session_skip_exercise))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancelClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.programs_session_cancel))
            }
            Button(
                onClick = onFinishClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.programs_session_finish))
            }
        }
    }
}

@Composable
private fun RestCard(
    state: ProgramSessionUiState,
    onSkipRest: () -> Unit,
    onExtendRest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.rest),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.timer_format)
                    .format(state.restSecondsLeft / 60, state.restSecondsLeft % 60),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
            state.restNext?.let { next ->
                Text(
                    text = stringResource(
                        R.string.programs_session_rest_next,
                        exerciseNameOf(next),
                        next.setNumber
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onSkipRest, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.programs_session_skip_rest))
                }
                OutlinedButton(onClick = onExtendRest, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.programs_session_extend_rest))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusCard(
    exercise: SessionExerciseUi,
    demo: Exercise?,
    actualValue: String,
    actualInputInvalid: Boolean,
    timeLeft: Int,
    timerRunning: Boolean,
    onActualChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    onToggleTimer: () -> Unit,
    onResetTimer: () -> Unit,
    onExerciseClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            demo?.let { illustration ->
                ExerciseHeroMedia(
                    exercise = illustration,
                    modifier = Modifier.fillMaxWidth(),
                    height = 80.dp
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Text(
                text = exerciseNameOf(exercise),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.current_set_format,
                    exercise.setNumber,
                    exercise.setCount
                ),
                style = MaterialTheme.typography.titleMedium
            )
            if (!exercise.isFinished && !exercise.skipped) {
                Text(
                    text = when (exercise.dimension) {
                        PrescriptionDimension.TIME_BASED ->
                            stringResource(R.string.programs_session_seconds_target, exercise.nextTarget)

                        else -> stringResource(R.string.programs_session_reps_target, exercise.nextTarget)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (exercise.skipped) {
                Text(
                    text = stringResource(R.string.programs_session_skipped),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
            TextButton(onClick = onExerciseClick) {
                Text(text = stringResource(R.string.programs_session_exercise_details))
            }

            Spacer(modifier = Modifier.height(8.dp))
            SetIndicators(exercise)
            Spacer(modifier = Modifier.height(12.dp))

            if (exercise.isPerformable) {
                if (exercise.dimension == PrescriptionDimension.TIME_BASED) {
                    TimerDisplay(
                        timeLeft = timeLeft,
                        isRunning = timerRunning,
                        onToggle = onToggleTimer,
                        onReset = onResetTimer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = stringResource(
                        when (exercise.dimension) {
                            PrescriptionDimension.TIME_BASED -> R.string.programs_session_actual_seconds
                            else -> R.string.programs_session_actual_reps
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedIconButton(
                        onClick = { onStep(-1) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text(text = "−", style = MaterialTheme.typography.titleLarge)
                    }
                    OutlinedTextField(
                        value = actualValue,
                        onValueChange = onActualChange,
                        singleLine = true,
                        isError = actualInputInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedIconButton(
                        onClick = { onStep(1) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text(text = "+", style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (actualInputInvalid) {
                    Text(
                        text = stringResource(R.string.programs_session_actual_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetIndicators(exercise: SessionExerciseUi) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        (1..exercise.setCount).forEach { index ->
            val isDone = index <= exercise.completedSets
            val isCurrent = !isDone && index == exercise.setNumber && exercise.isPerformable
            Surface(
                shape = CircleShape,
                color = when {
                    isDone -> MaterialTheme.colorScheme.primary
                    isCurrent -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.programs_session_completed),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            text = index.toString(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseStrip(
    exercises: List<SessionExerciseUi>,
    onSelect: (String) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(exercises, key = { exercise -> exercise.sessionExerciseId }) { exercise ->
            FilterChip(
                selected = exercise.isCurrent,
                onClick = { onSelect(exercise.sessionExerciseId) },
                label = {
                    Text(
                        text = exerciseNameOf(exercise),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = when {
                    exercise.skipped -> {
                        {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.programs_session_skipped)
                            )
                        }
                    }

                    exercise.isFinished -> {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.programs_session_completed)
                            )
                        }
                    }

                    else -> null
                }
            )
        }
    }
}

@Composable
private fun RestLengthDialog(
    currentSeconds: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onNoRest: () -> Unit
) {
    var input by remember { mutableStateOf(currentSeconds.toString()) }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_session_rest_value)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        input = value.filter(Char::isDigit)
                        invalid = false
                    },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text(stringResource(R.string.programs_session_actual_invalid)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = onNoRest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.programs_session_no_rest))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val seconds = input.toIntOrNull()
                    if (seconds == null || seconds <= 0) {
                        invalid = true
                    } else {
                        onSave(input)
                    }
                }
            ) {
                Text(stringResource(R.string.programs_editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** The occurrence's display name, or its id when the catalogue cannot name it. */
@Composable
private fun exerciseNameOf(exercise: SessionExerciseUi): String =
    if (exercise.nameRes == 0) exercise.exerciseId else stringResource(exercise.nameRes)
