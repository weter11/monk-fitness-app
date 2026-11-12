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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.ui.components.HeroOverlay
import com.monkfitness.app.ui.components.MediaHero
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.ui.components.exerciseSummaryText
import com.monkfitness.app.viewmodel.MainViewModel

/** Requested overlay-zone heights; HeroGeometry caps each to a fraction of the hero. */
private const val POSTURE_OVERLAY_TOP_DP = 64f
private const val POSTURE_OVERLAY_BOTTOM_DP = 48f

/**
 * **The optional posture / mobility session** — the retained daily track's own screen.
 *
 * A mobility session is not a Program workout and must not pretend to be one: it has no Program, no
 * revision, no opportunity and no plan element, so there is nothing an `IN_PROGRESS` session could be
 * *about*. A synthetic opportunity would be the second runtime, and logging its sets into
 * `program_set_log` would claim it was planned work. So the routine is generated and never stored, the
 * session is in-memory, and completing it writes one `posture_session_progress` row.
 *
 * The same [MediaHero] the workout session uses is the dominant element here: it shows the selected
 * exercise's animation (or the same still-image fallback), the list below selects which exercise, and
 * a compact overlay names it and states its target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostureSessionScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val exercises: List<Exercise> = remember { viewModel.postureMobilityWorkout().exercises }
    // The list selects the exercise the hero shows. It starts on the first exercise of the routine.
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (exercises.isEmpty()) {
                Text(
                    text = stringResource(R.string.posture_session_nothing_to_present),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                // THE SAME HERO COMPONENT the workout session uses, reused (no copy). 0.6 weight
                // keeps it dominant; the list below (0.4) selects the exercise it animates.
                MediaHero(
                    exercise = selected,
                    overlay = postureHeroOverlay(selected),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.6f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // The list keeps what it shows today (name + description) and now also selects the
                // exercise the hero animates.
                LazyColumn(modifier = Modifier.weight(0.4f)) {
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

/** The overlay names the selected exercise (top) and states its target (bottom), capped by geometry. */
@Composable
private fun postureHeroOverlay(exercise: Exercise?): HeroOverlay {
    val name = exercise?.let { stringResource(it.nameRes) } ?: ""
    val target = exercise?.let { exerciseSummaryText(it) } ?: ""
    return HeroOverlay(
        topDp = POSTURE_OVERLAY_TOP_DP,
        bottomDp = POSTURE_OVERLAY_BOTTOM_DP,
        topContent = {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        bottomContent = {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(
                    text = target,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

/** One row of the posture routine: what it showed today, plus the selection state for the hero. */
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
