package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.platform.VibrationFeedback
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.ui.programs.ProgramSessionController
import com.monkfitness.app.ui.programs.ProgramSessionNotice
import com.monkfitness.app.ui.programs.SessionExerciseUi
import com.monkfitness.app.ui.programs.SessionStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the rest between two sets of one occurrence lasts. The same minute the app always used. */
private const val REST_SECONDS = 60

/**
 * **The workout session screen** — the one production workout runtime (§30 step 15, §19, §27).
 *
 * It exists to replace the retired day-based `WorkoutScreen`, and it is deliberately thin: **every**
 * operation it offers is a call on [ProgramSessionController], which calls `SessionRuntime`. The screen
 * starts nothing, counts nothing, decides nothing about completion and writes nothing:
 *
 * ```text
 * opening the route   controller.open(slotId)   → start, or restore what is already in progress
 * Confirm set         controller.confirmSet(…)  → one appended set row, position from the stored rows
 * Finish              controller.finish()       → session + opportunity + adaptive, one transaction
 * Cancel              controller.cancel()       → CANCELLED; the opportunity is left as it was
 * Back                controller.back()         → nothing at all: leaving is not a fact about the workout
 * ```
 *
 * The attempt's identity is the **slot** it is opened for, and its persisted identity is the session the
 * runtime returns. Nothing here reconstructs a day from a calendar: a session is restored by what is
 * stored, which is what makes "close the app mid-workout and come back" work without a second model.
 *
 * The rest countdown between two sets is the screen's own affordance (and the only state it owns): it
 * decides nothing about the workout, and every set the user confirms is confirmed explicitly.
 */
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
    val scope = rememberCoroutineScope()

    // The screen the workout is running on stays on: the affordance the navigation host used to apply
    // from the shipped step machine's state, now owned by the only screen that knows a workout is running.
    val view = LocalView.current
    LaunchedEffect(state.isPresenting) { view.keepScreenOn = state.isPresenting }

    var restSecondsLeft by remember { mutableIntStateOf(0) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(false) }

    val notice = state.notice
    LaunchedEffect(notice) { showNotice = notice != null }

    restSecondsLeft.coerceAtLeast(0).takeIf { it > 0 }?.let { remaining ->
        LaunchedEffect(remaining, state.confirmedSetCount) {
            delay(1_000)
            restSecondsLeft = remaining - 1
            if (remaining - 1 == 0 && vibrationEnabled) VibrationFeedback.buzz(context)
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
                .padding(16.dp)
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

                SessionStage.COMPLETED -> Column {
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
                    state.notice?.let { completedNotice ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(completedNotice.messageRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                SessionStage.PRESENTING -> {
                    Text(
                        text = stringResource(
                            R.string.programs_session_sets_progress,
                            state.confirmedSetCount,
                            state.prescribedSetCount
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                            .height(12.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(state.exercises, key = { exercise -> exercise.sessionExerciseId }) { exercise ->
                            SessionExerciseRow(
                                exercise = exercise,
                                onExerciseClick = onExerciseClick
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (restSecondsLeft > 0) {
                        Text(
                            text = stringResource(R.string.programs_session_rest, restSecondsLeft),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { restSecondsLeft = 0 },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.programs_session_skip_rest))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    state.currentExercise?.let { current ->
                        MonkButton(
                            text = stringResource(R.string.programs_session_confirm_set),
                            onClick = {
                                restSecondsLeft = if (current.completedSets + 1 < current.setCount) {
                                    REST_SECONDS
                                } else {
                                    0
                                }
                                if (vibrationEnabled) VibrationFeedback.buzz(context)
                                val target = current.nextTarget
                                if (current.dimension == PrescriptionDimension.TIME_BASED) {
                                    scope.launch { controller.confirmSet(durationSeconds = target) }
                                } else {
                                    scope.launch { controller.confirmSet(completedReps = target) }
                                }
                            },
                            enabled = restSecondsLeft == 0
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCancelDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = stringResource(R.string.programs_session_cancel))
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
                            Text(text = stringResource(R.string.programs_session_finish))
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

/** One occurrence: its name, its set progress and its next target. */
@Composable
private fun SessionExerciseRow(
    exercise: SessionExerciseUi,
    onExerciseClick: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (exercise.isCurrent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (exercise.nameRes == 0) {
                    exercise.exerciseId
                } else {
                    stringResource(exercise.nameRes)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.programs_session_set_of,
                    exercise.completedSets.coerceAtMost(exercise.setCount),
                    exercise.setCount
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            if (!exercise.isFinished) {
                Text(
                    text = when (exercise.dimension) {
                        PrescriptionDimension.TIME_BASED ->
                            stringResource(R.string.programs_session_seconds_target, exercise.nextTarget)

                        else -> stringResource(R.string.programs_session_reps_target, exercise.nextTarget)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            TextButton(onClick = { onExerciseClick(exercise.exerciseId) }) {
                Text(text = stringResource(R.string.programs_session_exercise_details))
            }
        }
    }
}
