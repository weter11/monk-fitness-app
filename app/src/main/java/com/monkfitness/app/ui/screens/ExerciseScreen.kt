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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseScreen(
    exercise: Exercise?,
    onBack: () -> Unit
) {
    LaunchedEffect(exercise) {
        Log.d("ExerciseScreen", "Opening ExerciseScreen with exercise: ${exercise?.id ?: "null"}")
        if (exercise != null) {
            Log.d("ExerciseScreen", "Exercise details: nameRes=${exercise.nameRes}, isTimerBased=${exercise.isTimerBased}")
        }
    }

    if (exercise == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Exercise Not Found") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No description available", style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    var timeLeft by rememberSaveable(exercise.id) { mutableIntStateOf(exercise.durationSeconds) }
    var isTimerRunning by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isTimerRunning, timeLeft) {
        if (isTimerRunning && timeLeft > 0) {
            kotlinx.coroutines.delay(1000L)
            timeLeft--
            if (timeLeft == 0) isTimerRunning = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(exercise.nameRes), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(exercise.imageRes),
                    contentDescription = null,
                    modifier = Modifier.size(160.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoChip(
                    label = if (exercise.isTimerBased) "Time" else "Sets",
                    value = if (exercise.isTimerBased) "${exercise.durationSeconds}s" else "${exercise.sets}"
                )
                if (!exercise.isTimerBased) {
                    InfoChip(label = "Reps", value = "${exercise.reps}")
                }
            }

            if (exercise.isTimerBased) {
                Spacer(modifier = Modifier.height(32.dp))
                TimerDisplay(
                    timeLeft = timeLeft,
                    isRunning = isTimerRunning,
                    onToggle = { isTimerRunning = !isTimerRunning },
                    onReset = {
                        isTimerRunning = false
                        timeLeft = exercise.durationSeconds
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            ExerciseSection(
                title = stringResource(R.string.description),
                content = stringResource(exercise.descriptionRes)
            )

            Spacer(modifier = Modifier.height(24.dp))

            ExerciseSection(
                title = stringResource(R.string.technique),
                content = stringResource(exercise.techniqueRes)
            )
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
                        contentDescription = "Pause",
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            OutlinedIconButton(
                onClick = onReset,
                modifier = Modifier.size(64.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset")
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
