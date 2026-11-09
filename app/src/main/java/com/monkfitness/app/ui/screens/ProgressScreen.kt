package com.monkfitness.app.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.monkfitness.app.R
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.LibraryCounts
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.ui.programs.ProgressExerciseVolumeRowUi
import com.monkfitness.app.ui.programs.ProgressSessionVolumeRowUi
import com.monkfitness.app.ui.programs.ProgressWeekVolumeRowUi
import com.monkfitness.app.viewmodel.MainViewModel
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ProgressScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val progressState by viewModel.programProgressState.collectAsState()
    val postureProgressList by viewModel.postureProgress.collectAsState()
    val bodyWeightHistory by viewModel.bodyWeightHistory.collectAsState()
    val latestBodyWeight by viewModel.latestBodyWeight.collectAsState()
    val libraryCounts by viewModel.libraryCounts.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.refreshProgress()
        viewModel.bodyWeightErrorEvents.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    if (progressState.loading && !progressState.hasProgram && progressState.notice == null) {
        // The measures are still being read. The screen is not blanked while that happens: the body-weight
        // log and the posture track are retained features that do not depend on this read.
    }

    val postureEntries = remember(postureProgressList) {
        postureProgressList
            .filter { it.isCompleted }
            .groupBy { ((it.trackDay - 1) / 7) + 1 }
            .map { (week, list) -> BarEntry(week.toFloat(), list.size.toFloat()) }
            .sortedBy { it.x }
    }
    val secondaryColor = MaterialTheme.colorScheme.secondary.toArgb()
    val postureCompletionRatio = remember(postureProgressList) {
        postureProgressList.count { it.isCompleted }.toFloat() /
            MainViewModel.POSTURE_TRACK_DAYS.toFloat()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.your_progress),
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Which Program these numbers are about, said out loud: one Program's own measures and the
            // "All Programs" aggregate are different facts, and a screen that showed one as the other
            // would attribute a total to a program that did not earn it.
            Text(
                text = listOfNotNull(
                    progressState.programName
                        ?: if (progressState.isAggregate) {
                            stringResource(R.string.progress_scope_all)
                        } else {
                            null
                        },
                    progressState.lifecycleStatus?.let { lifecycle ->
                        stringResource(lifecycleLabelRes(lifecycle))
                    }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(24.dp))

            BodyWeightCard(
                history = bodyWeightHistory,
                latestEntry = latestBodyWeight,
                onLogWeight = viewModel::logBodyWeight
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DashboardStatCard(
                    title = stringResource(R.string.program_completed_sessions_label),
                    value = progressState.completed.toString(),
                    modifier = Modifier.weight(1f)
                )
                DashboardStatCard(
                    title = stringResource(R.string.program_missed_sessions_label),
                    value = progressState.missed.toString(),
                    modifier = Modifier.weight(1f)
                )
                DashboardStatCard(
                    title = stringResource(R.string.streak),
                    value = (progressState.streaks.firstOrNull()?.current ?: 0).toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            LibraryCountsCard(counts = libraryCounts)

            Spacer(modifier = Modifier.height(24.dp))

            ProgressLineCard(
                title = stringResource(R.string.progress_calendar_title),
                lines = if (progressState.hasOpportunities) {
                    listOf(
                        stringResource(
                            R.string.progress_calendar_value,
                            progressState.completed,
                            progressState.missed,
                            progressState.upcoming,
                            progressState.superseded
                        )
                    )
                } else {
                    emptyList()
                },
                emptyText = stringResource(R.string.no_progress_yet),
                progress = if (progressState.hasOpportunities) progressState.completedShare else null
            )

            Spacer(modifier = Modifier.height(24.dp))

            ProgressLineCard(
                title = stringResource(R.string.progress_frequency_title),
                lines = listOfNotNull(
                    progressState.frequency?.let { frequency ->
                        stringResource(
                            R.string.progress_frequency_training_days,
                            frequency.trainingDays,
                            frequency.calendarDays
                        )
                    },
                    progressState.frequency?.let { frequency ->
                        stringResource(
                            R.string.progress_frequency_per_week,
                            String.format(Locale.US, "%.1f", frequency.sessionsPerSevenDays)
                        )
                    },
                    if (progressState.measuredSessions > 0) {
                        stringResource(
                            R.string.progress_duration_value,
                            progressState.measuredSessions,
                            formatDuration(progressState.averageSessionSeconds)
                        )
                    } else {
                        stringResource(R.string.progress_duration_unmeasured)
                    }
                ),
                emptyText = stringResource(R.string.no_workout_frequency_yet)
            )

            if (progressState.streaks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                ProgressLineCard(
                    title = stringResource(R.string.progress_streak_title),
                    lines = progressState.streaks.map { streak ->
                        stringResource(
                            R.string.progress_streak_row,
                            streak.programName,
                            streak.current,
                            streak.longest
                        )
                    },
                    emptyText = stringResource(R.string.no_progress_yet)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            VolumeCard(
                sessions = progressState.volumeSessions,
                weeks = progressState.volumeWeeks,
                exercises = progressState.volumeExercises,
                hasVolume = progressState.hasVolume
            )

            Spacer(modifier = Modifier.height(24.dp))

            ProgressLineCard(
                title = stringResource(R.string.progress_performance_title),
                lines = progressState.performance.mapNotNull { row ->
                    val name = if (row.nameRes != 0) {
                        stringResource(row.nameRes)
                    } else {
                        stringResource(R.string.progress_unknown_exercise)
                    }
                    row.best?.let { best ->
                        if (row.dimension == PrescriptionDimension.TIME_BASED) {
                            stringResource(
                                R.string.progress_performance_time_row,
                                name,
                                row.observations,
                                best
                            )
                        } else {
                            stringResource(
                                R.string.progress_performance_rep_row,
                                name,
                                row.observations,
                                best
                            )
                        }
                    }
                },
                emptyText = stringResource(R.string.progress_performance_empty)
            )

            Spacer(modifier = Modifier.height(24.dp))

            ProgressLineCard(
                title = stringResource(R.string.progress_history_title),
                lines = progressState.history.map { attempt ->
                    stringResource(
                        R.string.progress_history_row,
                        attempt.plannedFor.toString(),
                        stringResource(sessionStatusLabelRes(attempt.status)),
                        attempt.performedSets,
                        attempt.exposedExercises
                    )
                },
                emptyText = stringResource(R.string.progress_history_empty)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Measures the target facts cannot carry yet, reported as *not computed* rather than as a
            // zero: "you have no personal records" and "this cannot be measured" are different claims,
            // and only one of them is true.
            ProgressLineCard(
                title = stringResource(R.string.progress_deferred_title),
                lines = progressState.deferred.map { measure -> measure.reason },
                emptyText = stringResource(R.string.progress_performance_empty)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.additional_posture_training),
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { postureCompletionRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                if (postureEntries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.no_posture_progress_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    AndroidView(
                        factory = { chartContext ->
                            BarChart(chartContext).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                description.isEnabled = false
                                setDrawGridBackground(false)
                                setDrawBarShadow(false)
                                setTouchEnabled(false)
                                xAxis.position = XAxis.XAxisPosition.BOTTOM
                                xAxis.setDrawGridLines(false)
                                xAxis.textColor = android.graphics.Color.WHITE
                                xAxis.granularity = 1f
                                axisLeft.textColor = android.graphics.Color.WHITE
                                axisLeft.axisMinimum = 0f
                                axisLeft.axisMaximum = 7f
                                axisRight.isEnabled = false
                                legend.isEnabled = false
                            }
                        },
                        update = { chart ->
                            val dataSet = BarDataSet(postureEntries, context.getString(R.string.posture_chart_label))
                            dataSet.color = secondaryColor
                            dataSet.valueTextColor = android.graphics.Color.WHITE
                            dataSet.valueTextSize = 10f
                            chart.data = BarData(dataSet)
                            chart.invalidate()
                        },
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(
                    R.string.completed_posture_sessions,
                    postureProgressList.count { it.isCompleted }
                ),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }

    val notice = progressState.notice
    if (notice != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissProgressNotice,
            confirmButton = {
                TextButton(onClick = viewModel::dismissProgressNotice) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.progress_notice_title)) },
            text = { Text(stringResource(notice.messageRes)) }
        )
    }
}

/** The lifecycle's own label, for the "which program is this" line. A label only, never a rule. */
private fun lifecycleLabelRes(status: LifecycleStatus): Int = when (status) {
    LifecycleStatus.NOT_STARTED -> R.string.programs_lifecycle_not_started
    LifecycleStatus.RUNNING -> R.string.programs_lifecycle_running
    LifecycleStatus.PAUSED -> R.string.programs_lifecycle_paused
    LifecycleStatus.COMPLETED -> R.string.programs_lifecycle_completed
}

/** An attempt's own status label. */
private fun sessionStatusLabelRes(status: SessionStatus): Int = when (status) {
    SessionStatus.IN_PROGRESS -> R.string.programs_session_in_progress
    SessionStatus.COMPLETED -> R.string.programs_session_completed
    SessionStatus.CANCELLED -> R.string.programs_session_cancelled
}

/** A measured duration, as `m:ss`; `--` when the layer did not measure one. */
private fun formatDuration(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0.0) return "--"
    val total = seconds.toLong()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

/**
 * The app's own size: how many exercises the engine can animate, and how many foods and dishes the
 * nutrition catalogue ships. Every number is derived from a registry, so adding an item changes the
 * card without a second place to edit.
 */
@Composable
private fun LibraryCountsCard(counts: LibraryCounts) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.library_counts_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            LibraryCountRow(
                label = stringResource(R.string.library_counts_exercises),
                value = counts.animatedExercises
            )
            LibraryCountRow(
                label = stringResource(R.string.library_counts_foods),
                value = counts.foods
            )
            LibraryCountRow(
                label = stringResource(R.string.library_counts_dishes),
                value = counts.dishes
            )
        }
    }
}

@Composable
private fun LibraryCountRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End
        )
    }
}

/**
 * Training volume over the layer's window: per attempt, per calendar week and per exercise, each in
 * the unit it was performed in. Repetitions and seconds are printed side by side and never added, and
 * there is no number at all until a measured set exists.
 */
@Composable
private fun VolumeCard(
    sessions: List<ProgressSessionVolumeRowUi>,
    weeks: List<ProgressWeekVolumeRowUi>,
    exercises: List<ProgressExerciseVolumeRowUi>,
    hasVolume: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.progress_volume_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.progress_volume_window),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            if (!hasVolume) {
                Text(
                    text = stringResource(R.string.progress_volume_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                VolumeSection(
                    title = stringResource(R.string.progress_volume_by_week),
                    rows = weeks.map { week ->
                        stringResource(
                            R.string.progress_volume_week_row,
                            week.weekStart.toString(),
                            volumeAmount(week.repetitions, week.seconds)
                        )
                    }
                )
                VolumeSection(
                    title = stringResource(R.string.progress_volume_by_exercise),
                    rows = exercises.map { exercise ->
                        val name = if (exercise.nameRes != 0) {
                            stringResource(exercise.nameRes)
                        } else {
                            stringResource(R.string.progress_unknown_exercise)
                        }
                        stringResource(
                            R.string.progress_volume_exercise_row,
                            name,
                            volumeAmount(exercise.repetitions ?: 0, exercise.seconds ?: 0)
                        )
                    }
                )
                VolumeSection(
                    title = stringResource(R.string.progress_volume_recent_sessions),
                    rows = sessions.map { session ->
                        stringResource(
                            R.string.progress_volume_session_row,
                            session.startedOn.toString(),
                            volumeAmount(session.repetitions, session.seconds)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun VolumeSection(title: String, rows: List<String>) {
    if (rows.isEmpty()) return
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    rows.forEach { row ->
        Text(text = row, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One volume in its own units: repetitions, seconds, or both — never one added number. */
@Composable
private fun volumeAmount(repetitions: Int, seconds: Int): String = when {
    repetitions > 0 && seconds > 0 ->
        stringResource(R.string.progress_volume_reps_and_seconds, repetitions, seconds)

    repetitions > 0 -> stringResource(R.string.progress_volume_reps, repetitions)
    seconds > 0 -> stringResource(R.string.progress_volume_seconds, seconds)
    else -> stringResource(R.string.progress_volume_empty)
}

/**
 * A titled card of plain lines, with an optional progress bar.
 *
 * It is the *shape* the target measures need: each one is a number with a sentence around it or a list of
 * rows, never a per-day series. The one series still plotted — the posture track's own weekly bars — is a
 * retained feature and keeps its chart.
 */
@Composable
private fun ProgressLineCard(
    title: String,
    lines: List<String>,
    emptyText: String,
    progress: Float? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                )
            }
            if (lines.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                lines.forEach { line ->
                    Text(text = line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DashboardStatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun BodyWeightCard(
    history: List<BodyWeightEntry>,
    latestEntry: BodyWeightEntry?,
    onLogWeight: (Float) -> Unit
) {
    val todayDate = remember { LocalDate.now().toString() }
    val todayEntry = remember(history, todayDate) {
        history.lastOrNull { it.date == todayDate }
    }
    val latestSubtitle = latestEntry?.let {
        stringResource(
            R.string.body_weight_latest_subtitle,
            formatBodyWeight(it.weightKg),
            it.date
        )
    } ?: stringResource(R.string.body_weight_not_logged_yet)
    val chartEntries = remember(history) { history.takeLast(30) }
    val dataSetLabel = stringResource(R.string.body_weight_chart_dataset)
    var weightInput by rememberSaveable(todayDate) { mutableStateOf<String?>(null) }

    LaunchedEffect(todayEntry?.id) {
        if (weightInput == null && todayEntry != null) {
            weightInput = formatBodyWeight(todayEntry.weightKg)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.body_weight_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = latestSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = weightInput.orEmpty(),
                    onValueChange = { weightInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(text = stringResource(R.string.body_weight_input_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        val parsedWeight = weightInput
                            .orEmpty()
                            .trim()
                            .replace(',', '.')
                            .toFloatOrNull()
                            ?: Float.NaN
                        onLogWeight(parsedWeight)
                        weightInput = ""
                    }
                ) {
                    Text(text = stringResource(R.string.log))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (chartEntries.size < 2) {
                Text(
                    text = stringResource(R.string.body_weight_chart_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                )
            } else {
                val lineEntries = remember(chartEntries) {
                    chartEntries.mapIndexed { index, entry ->
                        Entry(index.toFloat(), entry.weightKg)
                    }
                }
                val labels = remember(chartEntries) {
                    chartEntries.map { formatSessionDateLabel(it.date) }
                }
                val minWeight = remember(chartEntries) { chartEntries.minOf { it.weightKg } }
                val maxWeight = remember(chartEntries) { chartEntries.maxOf { it.weightKg } }
                val color = MaterialTheme.colorScheme.primary.toArgb()

                AndroidView(
                    factory = { chartContext ->
                        LineChart(chartContext).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            description.isEnabled = false
                            setTouchEnabled(false)
                            setDrawGridBackground(false)
                            xAxis.position = XAxis.XAxisPosition.BOTTOM
                            xAxis.setDrawGridLines(false)
                            xAxis.textColor = android.graphics.Color.WHITE
                            xAxis.granularity = 1f
                            axisLeft.textColor = android.graphics.Color.WHITE
                            axisRight.isEnabled = false
                            legend.isEnabled = false
                        }
                    },
                    update = { chart ->
                        val dataSet = LineDataSet(lineEntries, dataSetLabel).apply {
                            this.color = color
                            setCircleColor(color)
                            lineWidth = 2f
                            circleRadius = 4f
                            valueTextColor = android.graphics.Color.WHITE
                            setDrawValues(false)
                        }
                        chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                        chart.xAxis.axisMinimum = 0f
                        chart.xAxis.axisMaximum = (lineEntries.lastIndex).toFloat()
                        chart.axisLeft.axisMinimum = (minWeight - 2f).coerceAtLeast(0f)
                        chart.axisLeft.axisMaximum = maxWeight + 2f
                        chart.data = LineData(dataSet)
                        chart.invalidate()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
            }
        }
    }
}




private fun formatBodyWeight(weightKg: Float): String = String.format(Locale.US, "%.1f", weightKg)

/** The body-weight chart's x labels: the month and day of a stored date, `yyyy-MM-dd` shaped. */
private fun formatSessionDateLabel(sessionDate: String): String = sessionDate.takeLast(5)
