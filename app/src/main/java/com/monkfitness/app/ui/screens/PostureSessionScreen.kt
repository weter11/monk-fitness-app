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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.ui.components.MonkButton
import com.monkfitness.app.viewmodel.MainViewModel

/**
 * **The optional posture / mobility session** — the retained daily track's own screen (§4).
 *
 * ### Why it is separate from the Program session screen
 *
 * A mobility session is not a Program workout and must not pretend to be one. It has no Program, no
 * revision, no opportunity and no plan element, so it has nothing an `IN_PROGRESS` session could be
 * *about*: `SessionRuntime` starts attempts at opportunities (§19), and a mobility routine is not one.
 * Giving it a synthetic opportunity would be the second runtime §30 step 15 forbids, and logging its sets
 * into `program_set_log` would claim it was planned work.
 *
 * So the screen is deliberately small and, on the persistence side, deliberately empty until the end:
 *
 * ```text
 * the routine     generated from the app's own catalogue and the user's settings (a presentation,
 *                 never stored) — the same rules the app has always used for this track
 * the session     in-memory: page through the drills, nothing is written per drill
 * completing it   one row of `posture_session_progress`, keyed by the track's own cycle and day
 * ```
 *
 * That is exactly what this feature did before §30 step 15 — a mobility session recorded as one completed
 * track day — with the per-drill `set_log` rows it also used to write retired along with the shipped
 * program's logging.
 *
 * @param viewModel the retained global state this screen reads: the routine and the track's own settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostureSessionScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val exercises: List<Exercise> = remember { viewModel.postureMobilityWorkout().exercises }

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
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(exercises, key = { exercise -> exercise.id }) { exercise ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
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
