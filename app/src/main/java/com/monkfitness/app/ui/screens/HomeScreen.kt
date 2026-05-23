package com.monkfitness.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val completedCount by viewModel.completedDaysCount.collectAsState()
    val completedPostureCount by viewModel.completedPostureDaysCount.collectAsState()
    val streak by viewModel.streak.collectAsState()
    val additionalPostureTrainingEnabled by viewModel.additionalPostureTrainingEnabled.collectAsState()
    val flexibilityTrainingType by viewModel.flexibilityTrainingType.collectAsState()
    val flexibilityFocusAreas by viewModel.flexibilityFocusAreas.collectAsState()
    val currentDay = viewModel.getCurrentDay()
    val workout = viewModel.getWorkoutForDay(currentDay)

    val targetProgress = completedCount.toFloat() / 56f
    val postureProgress = completedPostureCount.toFloat() / 56f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        label = "HomeProgressAnimation"
    )
    val animatedPostureProgress by animateFloatAsState(
        targetValue = postureProgress,
        label = "PostureProgressAnimation"
    )
    val fullBodyLabel = stringResource(ExerciseSubCategory.FULL_BODY.labelRes)
    val selectedFocusAreaLabels = flexibilityFocusAreas.map { stringResource(it.labelRes) }
    val focusAreaSummary = if (ExerciseSubCategory.FULL_BODY in flexibilityFocusAreas) {
        fullBodyLabel
    } else {
        selectedFocusAreaLabels.joinToString(", ")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.week_label, ((currentDay - 1) / 7) + 1),
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
                    text = stringResource(workout.type.nameRes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
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
                value = "$streak",
                modifier = Modifier.weight(1f),
                icon = "🔥"
            )
            InfoCard(
                label = stringResource(R.string.day),
                value = stringResource(R.string.day_format, currentDay),
                modifier = Modifier.weight(1f),
                icon = "📅"
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        MonkButton(
            text = stringResource(R.string.start_workout),
            onClick = { onStartWorkout(currentDay) }
        )

        if (additionalPostureTrainingEnabled) {
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
                            stringResource(flexibilityTrainingType.labelRes),
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
                        onClick = { onStartPostureWorkout(currentDay) }
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
