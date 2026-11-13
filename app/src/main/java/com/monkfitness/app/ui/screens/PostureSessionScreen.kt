package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.ui.components.HeroGeometry
import com.monkfitness.app.ui.components.MediaHero
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.ui.components.exerciseSummaryText
import com.monkfitness.app.viewmodel.MainViewModel

/**
 * **The optional posture / mobility session** — the retained daily track's own screen.
 *
 * A mobility session is not a Program workout and must not pretend to be one: it has no Program, no
 * revision, no opportunity and no plan element, so there is nothing an `IN_PROGRESS` session could be
 * *about*. A synthetic opportunity would be the second runtime, and logging its sets into
 * `program_set_log` would claim it was planned work. So the routine is generated and never stored, the
 * session is in-memory, and completing it writes one `posture_session_progress` row.
 *
 * The hero is the same [MediaHero] the workout session uses, under the same layout contract: it fills
 * the content width and carries no text, the selected exercise is named in the block directly below
 * it, and the routine's own list stays below that and selects which exercise the hero animates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostureSessionScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val exercises: List<Exercise> = remember { viewModel.postureMobilityWorkout().exercises }
    // The list selects the exercise the hero shows; it starts on the routine's first one.
    var selectedId by remember(exercises) { mutableStateOf(exercises.firstOrNull()?.id) }
    val selected = exercises.firstOrNull { it.id == selectedId }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.additional_posture_training),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.optional_session_duration),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                },
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
        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val contentHeight = maxHeight - 32.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                if (exercises.isEmpty()) {
                    Text(
                        text = stringResource(R.string.posture_session_nothing_to_present),
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    MediaHero(
                        exercise = selected,
                        contentHeight = contentHeight,
                        // The list and the button are the screen's other half; the hero never takes
                        // more than its own share of the content, whatever the rig would like.
                        room = contentHeight * HeroGeometry.MAX_CONTENT_FRACTION
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = selected?.let { stringResource(it.nameRes) }.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = selected?.let { stringResource(it.descriptionRes) }.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            selected?.let {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = exerciseSummaryText(it),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(exercises, key = { exercise -> exercise.id }) { exercise ->
                            PostureExerciseRow(
                                exercise = exercise,
                                selected = exercise.id == selectedId,
                                onSelect = { selectedId = exercise.id }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MonkButton(
                            text = stringResource(R.string.posture_session_complete),
                            onClick = {
                                viewModel.completePostureWorkout()
                                onBack()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** One row of the posture routine: what it showed before, plus the selection state for the hero. */
@Composable
private fun PostureExerciseRow(
    exercise: Exercise,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(exercise.nameRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(exercise.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
