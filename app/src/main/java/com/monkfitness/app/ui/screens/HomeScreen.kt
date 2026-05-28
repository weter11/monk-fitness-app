package com.monkfitness.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onStartWorkout: (Int) -> Unit,
    onStartPostureWorkout: (Int) -> Unit
) {
    val uiState by viewModel.homeUiState.collectAsState()
    val showProgramSummary by viewModel.showProgramSummary.collectAsState()
    val programStatistics by viewModel.programStatistics.collectAsState()
    val progressValues by remember(uiState.completedCount, uiState.completedPostureCount) {
        derivedStateOf {
            (uiState.completedCount.toFloat() / 56f) to (uiState.completedPostureCount.toFloat() / 56f)
        }
    }
    val targetProgress = progressValues.first
    val postureProgress = progressValues.second
    val animatedProgress by animateFloatAsState(targetValue = targetProgress, label = "HomeProgressAnimation")
    val animatedPostureProgress by animateFloatAsState(targetValue = postureProgress, label = "PostureProgressAnimation")
    val fullBodyLabel = stringResource(ExerciseSubCategory.FULL_BODY.labelRes)
    val selectedFocusAreaLabels = uiState.flexibilityFocusAreas.map { stringResource(it.labelRes) }
    val focusAreaSummary = if (ExerciseSubCategory.FULL_BODY in uiState.flexibilityFocusAreas) {
        fullBodyLabel
    } else {
        selectedFocusAreaLabels.joinToString(", ")
    }
    val todayStatus = when {
        uiState.todayProgramDayState.isMissed -> stringResource(R.string.program_status_missed)
        uiState.todayProgramDayState.isCompleted -> stringResource(R.string.program_status_completed)
        uiState.todayProgramDayState.isWorkoutDay -> stringResource(R.string.program_status_workout_day)
        else -> stringResource(R.string.program_status_recovery_day)
    }

    if (showProgramSummary) {
        AlertDialog(
            onDismissRequest = viewModel::dismissProgramSummary,
            confirmButton = {
                TextButton(onClick = viewModel::dismissProgramSummary) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.program_completed_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.program_completion_percent, programStatistics.completionPercentage))
                    Text(stringResource(R.string.program_completed_sessions, programStatistics.totalWorkoutsCompleted))
                    Text(stringResource(R.string.program_missed_sessions, programStatistics.totalMissed))
                    Text(stringResource(R.string.program_total_sets, programStatistics.totalSets))
                    Text(stringResource(R.string.program_total_exercises, programStatistics.totalExercisesCompleted))
                    Text(stringResource(R.string.program_prs_achieved, programStatistics.totalPersonalRecords))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.week_label, ((uiState.currentDay - 1) / 7) + 1),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.today_workout),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(uiState.workout.type.nameRes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = todayStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Gray.copy(alpha = 0.3f),
                )

                Text(
                    text = stringResource(R.string.progress_percent, (targetProgress * 100).toInt()),
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            InfoCard(
                label = stringResource(R.string.streak),
                value = "${uiState.streak}",
                modifier = Modifier.weight(1f),
                icon = "🔥"
            )
            InfoCard(
                label = stringResource(R.string.day),
                value = stringResource(R.string.day_format, uiState.currentDay),
                modifier = Modifier.weight(1f),
                icon = "📅"
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        MonkButton(
            text = stringResource(R.string.start_workout),
            onClick = { onStartWorkout(uiState.currentDay) }
        )

        if (uiState.additionalPostureTrainingEnabled) {
            Spacer(modifier = Modifier.height(24.dp))

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
                            stringResource(uiState.flexibilityTrainingType.labelRes),
                            focusAreaSummary
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { animatedPostureProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = Color.Gray.copy(alpha = 0.3f)
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
                        onClick = { onStartPostureWorkout(uiState.currentDay) }
                    )
                }
            }
        }
    }
}

@Composable
fun InfoCard(label: String, value: String, modifier: Modifier, icon: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = icon, fontSize = 24.sp)
            Text(text = value, style = MaterialTheme.typography.titleLarge)
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
