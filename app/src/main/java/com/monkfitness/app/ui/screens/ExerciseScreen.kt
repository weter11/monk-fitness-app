package com.monkfitness.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import android.util.Log
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseScreen(
    exercise: Exercise?,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(exercise) {
        Log.d("ExerciseScreen", "Opening ExerciseScreen with exercise: ${exercise?.id ?: "null"}")
        if (exercise != null) {
            Log.d("ExerciseScreen", "Exercise details: name=${exercise.id}, nameRes=${exercise.nameRes}, descRes=${exercise.descriptionRes}, techRes=${exercise.techniqueRes}, stepsRes=${exercise.stepsRes}, mistakesRes=${exercise.mistakesRes}")
            // Only reset if timer is NOT already running (prevents interrupting active workout)
            if (exercise.isTimerBased && !viewModel.isTimerRunning.value) {
                viewModel.resetTimer(exercise.durationSeconds)
            }
        }
    }

    if (exercise == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.previous)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.description), style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val timeLeft by viewModel.timeLeft.collectAsState()
    val isTimerRunning by viewModel.isTimerRunning.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(exercise.nameRes), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val imageRes = if (exercise.imageRes != 0) exercise.imageRes else R.drawable.ic_exercise_placeholder

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(imageRes),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(exercise.nameRes),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoChip(
                    label = if (exercise.isTimerBased) stringResource(R.string.time) else stringResource(R.string.sets),
                    value = if (exercise.isTimerBased) stringResource(R.string.seconds_format, exercise.durationSeconds) else exercise.sets.toString()
                )
                if (!exercise.isTimerBased) {
                    InfoChip(label = stringResource(R.string.reps), value = exercise.reps.toString())
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            ExerciseSection(
                title = stringResource(R.string.label_description),
                content = if (exercise.descriptionRes != 0 && exercise.descriptionRes != R.string.description)
                    stringResource(exercise.descriptionRes) else stringResource(R.string.description)
            )

            Spacer(modifier = Modifier.height(24.dp))

            ExerciseSection(
                title = stringResource(R.string.label_steps),
                content = if (exercise.stepsRes != 0) stringResource(exercise.stepsRes) else stringResource(R.string.steps_default)
            )

            Spacer(modifier = Modifier.height(24.dp))

            ExerciseSection(
                title = stringResource(R.string.label_technique),
                content = if (exercise.techniqueRes != 0 && exercise.techniqueRes != R.string.technique)
                    stringResource(exercise.techniqueRes) else stringResource(R.string.technique)
            )

            Spacer(modifier = Modifier.height(24.dp))

            ExerciseSection(
                title = stringResource(R.string.label_mistakes),
                content = if (exercise.mistakesRes != 0) stringResource(exercise.mistakesRes) else stringResource(R.string.mistakes_default)
            )

            if (exercise.isTimerBased) {
                Spacer(modifier = Modifier.height(32.dp))
                TimerDisplay(
                    timeLeft = timeLeft,
                    isRunning = isTimerRunning,
                    onToggle = { viewModel.toggleTimer(exercise.durationSeconds) },
                    onReset = { viewModel.resetTimer(exercise.durationSeconds) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun TimerDisplay(
    timeLeft: Int,
    isRunning: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "%02d:%02d".format(timeLeft / 60, timeLeft % 60),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = if (timeLeft < 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(64.dp),
                shape = CircleShape
            ) {
                if (isRunning) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pause),
                        contentDescription = stringResource(R.string.timer_pause),
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.timer_start),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            OutlinedIconButton(
                onClick = onReset,
                modifier = Modifier.size(64.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.timer_reset))
            }
        }
    }
}

@Composable
fun ExerciseSection(title: String, content: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                text = content,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
