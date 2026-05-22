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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current

    LaunchedEffect(exercise) {
        Log.d("ExerciseScreen", "Opening ExerciseScreen with exercise: ${exercise?.id ?: "null"}")
        if (exercise != null) {
            Log.d("ExerciseScreen", "Exercise details: nameRes=${exercise.nameRes}, isTimerBased=${exercise.isTimerBased}")
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

    val timeLeft by viewModel.timeLeft.collectAsState()
    val isTimerRunning by viewModel.isTimerRunning.collectAsState()
    val imageRes = if (exercise.imageRes != 0) exercise.imageRes else R.drawable.ic_exercise_placeholder
    val descriptionText = exercise.descriptionRes.resolveString(context, R.string.default_exercise_description)
    val techniqueText = exercise.techniqueRes.resolveString(context, R.string.default_exercise_technique)

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
                    painter = painterResource(imageRes),
                    contentDescription = null,
                    modifier = Modifier.size(160.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(exercise.nameRes),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            ExerciseSection(
                title = stringResource(R.string.description),
                content = descriptionText
            )

            Spacer(modifier = Modifier.height(24.dp))

            ExerciseSection(
                title = stringResource(R.string.technique),
                content = techniqueText
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

    private fun Int.resolveString(context: android.content.Context, fallbackRes: Int): String {
        val fallback = context.getString(fallbackRes)
        if (this == 0) return fallback
        return runCatching { context.getString(this) }.getOrElse { fallback }
    }
}
