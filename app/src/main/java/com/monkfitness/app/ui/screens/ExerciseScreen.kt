package com.monkfitness.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.util.TimerManager
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseScreen(
    exercise: Exercise,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val timerManager = remember { TimerManager(context) }
    var timeLeft by rememberSaveable { mutableIntStateOf(exercise.durationSeconds) }
    var isTimerRunning by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            timerManager.release()
        }
    }

    LaunchedEffect(isTimerRunning) {
        if (isTimerRunning && timeLeft > 0) {
            while (timeLeft > 0 && isTimerRunning) {
                delay(1000)
                if (isTimerRunning) {
                    timeLeft--
                }
            }
            if (timeLeft == 0) {
                isTimerRunning = false
                timerManager.playCompletionSound()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(exercise.nameRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Image(
                    painter = painterResource(exercise.imageRes),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (exercise.isTimerBased) {
                TimerDisplay(
                    timeLeft = timeLeft,
                    isRunning = isTimerRunning,
                    onToggle = { isTimerRunning = !isTimerRunning },
                    onReset = { timeLeft = exercise.durationSeconds; isTimerRunning = false }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            Text(
                text = stringResource(R.string.description),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(exercise.descriptionRes),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.technique),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(exercise.techniqueRes),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun TimerDisplay(
    timeLeft: Int,
    isRunning: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = String.format("%02d:%02d", timeLeft / 60, timeLeft % 60),
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 48.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onToggle) {
                    Text(if (isRunning) stringResource(R.string.timer_pause) else stringResource(R.string.timer_start))
                }
                OutlinedButton(onClick = onReset) {
                    Text(stringResource(R.string.timer_reset))
                }
            }
        }
    }
}
