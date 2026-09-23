package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.viewmodel.MainViewModel

/**
 * **Home** — the app's landing screen, now over the **target Program System** (§30 step 15).
 *
 * Before this step Home ran the shipped 56-day program: a day number derived from a stored start date, a
 * `program_day_state` row for today, a routine generated from the day and a completion written back as
 * `user_progress`. All of that is retired. What Home shows now is what the Program System already owns
 * and can answer:
 *
 * ```text
 * the Program being worked on   ProgramLifecycleService   (the one global selection, §3)
 * the next opportunity          ProgramScheduler          (§20 — the earliest startable opportunity)
 * the calendar's own counts     ProgramProgressService    (§21 completed / missed / upcoming)
 * the Program's own streak      ProgramProgressService    (§21, per Program)
 * ```
 *
 * There is **no day number, no cycle and no completion grid**: a workout is identified by the
 * **opportunity** it is, so "Start workout" navigates to that opportunity's id and the session screen
 * asks the runtime to start *it*. Repeating a workout is not an action here either: the Scheduler may
 * hold a missed opportunity, and it is offered as itself rather than as a duplicate of a day.
 *
 * The optional posture / mobility track is retained unchanged (§4): it is a daily practice on its own
 * 56-day rhythm, not part of any Program, and it keeps its own card.
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onStartWorkout: (String) -> Unit,
    onOpenPrograms: () -> Unit,
    onStartPostureWorkout: () -> Unit
) {
    // P1 of the creation remediation: `ProgramHomeController.load()` existed but Home never called
    // it, so the card rendered the state holder's initial value — no Program, no next workout, no
    // Start — no matter what was created, selected or scheduled. This is ProgressScreen's own
    // mechanism (`LaunchedEffect(viewModel) { viewModel.refreshProgress() }`): a lifecycle-aware
    // effect keyed on the holder, re-read on every composition of this destination — first open and
    // every return to Home — and cancelled with the composition, so there is no read outliving the
    // screen that asked for it. The read itself stays in the controller; nothing here touches a
    // repository or a service.
    LaunchedEffect(viewModel) {
        viewModel.refreshHomeProgram()
    }

    val state by viewModel.homeProgramState.collectAsState()
    val postureCompleted by viewModel.postureCompletedCount.collectAsState()
    val additionalPostureTrainingEnabled by viewModel.additionalPostureTrainingEnabled.collectAsState()
    val flexibilityTrainingType by viewModel.flexibilityTrainingType.collectAsState()
    val flexibilityFocusAreas by viewModel.flexibilityFocusAreas.collectAsState()

    val postureProgress by remember(postureCompleted) {
        derivedStateOf {
            (postureCompleted.toFloat() / MainViewModel.POSTURE_TRACK_DAYS.toFloat()).coerceIn(0f, 1f)
        }
    }
    val fullBodyLabel = stringResource(ExerciseSubCategory.FULL_BODY.labelRes)
    val selectedFocusAreaLabels = flexibilityFocusAreas.map { area -> stringResource(area.labelRes) }
    val focusAreaSummary = if (ExerciseSubCategory.FULL_BODY in flexibilityFocusAreas) {
        fullBodyLabel
    } else {
        selectedFocusAreaLabels.joinToString()
    }
    val programProgress by remember(state.completed, state.missed, state.upcoming) {
        derivedStateOf {
            val total = state.completed + state.missed + state.upcoming
            if (total <= 0) 0f else state.completed.toFloat() / total.toFloat()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.programs_home_current_program),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = state.programName ?: stringResource(R.string.programs_home_no_program),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                state.lifecycleStatus?.let { lifecycle ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(lifecycleLabelRes(lifecycle)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.programs_home_next_workout),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = when {
                        state.scheduleUnreadable ->
                            stringResource(R.string.programs_home_schedule_unreadable)

                        state.nextPlannedFor != null -> stringResource(
                            R.string.programs_home_next_planned,
                            state.nextPlannedFor.toString()
                        )

                        else -> stringResource(R.string.programs_home_nothing_planned)
                    },
                    style = MaterialTheme.typography.titleMedium
                )

                if (state.hasAnyOpportunity) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { programProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.programs_home_calendar,
                            state.completed,
                            state.missed,
                            state.upcoming
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state.canStartWorkout) {
            val slotId = state.nextSlotId
            MonkButton(
                text = stringResource(R.string.programs_session_start),
                onClick = { if (slotId != null) onStartWorkout(slotId) }
            )
        } else {
            MonkButton(
                text = stringResource(R.string.programs_home_open_programs),
                onClick = onOpenPrograms
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            InfoCard(
                label = stringResource(R.string.programs_home_streak),
                value = state.streak.toString(),
                modifier = Modifier.weight(1f)
            )
            InfoCard(
                label = stringResource(R.string.programs_home_completed),
                value = state.completed.toString(),
                modifier = Modifier.weight(1f)
            )
            InfoCard(
                label = stringResource(R.string.programs_home_upcoming),
                value = state.upcoming.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (additionalPostureTrainingEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.additional_posture_training),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.flexibility_session_summary,
                            stringResource(flexibilityTrainingType.labelRes),
                            focusAreaSummary
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { postureProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.optional_session_duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    MonkButton(
                        text = stringResource(R.string.start_posture_mobility),
                        onClick = onStartPostureWorkout
                    )
                }
            }
        }
    }

    val notice = state.notice
    if (notice != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissHomeNotice() },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissHomeNotice() }) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.programs_home_notice_title)) },
            text = { Text(stringResource(notice.messageRes)) }
        )
    }
}

/** The localized label of a lifecycle status. A label only: no rule is decided from it here. */
private fun lifecycleLabelRes(status: com.monkfitness.app.domain.program.LifecycleStatus): Int =
    when (status) {
        com.monkfitness.app.domain.program.LifecycleStatus.NOT_STARTED ->
            R.string.programs_lifecycle_not_started

        com.monkfitness.app.domain.program.LifecycleStatus.RUNNING -> R.string.programs_lifecycle_running

        com.monkfitness.app.domain.program.LifecycleStatus.PAUSED -> R.string.programs_lifecycle_paused

        com.monkfitness.app.domain.program.LifecycleStatus.COMPLETED ->
            R.string.programs_lifecycle_completed
    }

@Composable
fun InfoCard(label: String, value: String, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, style = MaterialTheme.typography.headlineSmall, fontSize = 24.sp)
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
