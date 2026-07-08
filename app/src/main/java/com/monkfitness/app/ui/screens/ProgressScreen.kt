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
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.viewmodel.MainViewModel
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ProgressScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val progressList by viewModel.allProgress.collectAsState()
    val postureProgressList by viewModel.postureProgress.collectAsState()
    val volumeHistory by viewModel.volumeHistory.collectAsState()
    val workoutFrequencyHistory by viewModel.workoutFrequencyHistory.collectAsState()
    val bodyWeightHistory by viewModel.bodyWeightHistory.collectAsState()
    val latestBodyWeight by viewModel.latestBodyWeight.collectAsState()
    val personalRecords by viewModel.exercisePersonalRecords.collectAsState()
    val currentProgramDay by viewModel.currentProgramDay.collectAsState()
    val streak by viewModel.streak.collectAsState()
    val programStatistics by viewModel.programStatistics.collectAsState()

    val legacyEntries = remember(progressList) {
        progressList
            .filter { it.isCompleted }
            .groupBy { ((it.day - 1) / 7) + 1 }
            .map { (week, list) -> BarEntry(week.toFloat(), list.size.toFloat()) }
            .sortedBy { it.x }
    }

    val postureEntries = remember(postureProgressList) {
        postureProgressList
            .filter { it.isCompleted }
            .groupBy { ((it.day - 1) / 7) + 1 }
            .map { (week, list) -> BarEntry(week.toFloat(), list.size.toFloat()) }
            .sortedBy { it.x }
    }

    val volumeEntries = remember(volumeHistory) { volumeHistory.takeLast(7) }
    val frequencyEntries = remember(workoutFrequencyHistory) { workoutFrequencyHistory.takeLast(8) }

    val volumeChartEntries = remember(volumeEntries) {
        volumeEntries.mapIndexed { index, point -> BarEntry(index.toFloat(), point.totalReps.toFloat()) }
    }
    val volumeChartLabels = remember(volumeEntries) {
        volumeEntries.map { formatSessionDateLabel(it.sessionDate) }
    }
    val frequencyChartEntries = remember(frequencyEntries) {
        frequencyEntries.mapIndexed { index, point -> BarEntry(index.toFloat(), point.sessionCount.toFloat()) }
    }
    val frequencyChartLabels = remember(frequencyEntries) {
        frequencyEntries.map { formatWeekLabel(it.weekLabel) }
    }

    val volumeStats = remember(volumeHistory) {
        val total = volumeHistory.sumOf { it.totalReps }
        val average = if (volumeHistory.isNotEmpty()) total / volumeHistory.size else 0
        val latest = volumeHistory.lastOrNull()?.totalReps ?: 0
        Triple(total, average, latest)
    }
    val totalVolume = volumeStats.first
    val averageVolume = volumeStats.second
    val latestVolume = volumeStats.third

    val secondaryColor = MaterialTheme.colorScheme.secondary.toArgb()
    val postureCompletionRatio = remember(postureProgressList) {
        postureProgressList.count { it.isCompleted }.toFloat() / 56f
    }
    val topPersonalRecords = remember(personalRecords, currentProgramDay) {
        personalRecords.entries
            .asSequence()
            .sortedByDescending { it.value }
            .mapNotNull { entry ->
                val exercise = viewModel.findExerciseById(
                    exerciseId = entry.key,
                    day = currentProgramDay,
                    availableEquipment = Equipment.entries.toSet()
                )
                exercise?.let { it to entry.value }
            }
            .take(3)
            .toList()
    }
    LaunchedEffect(viewModel) {
        viewModel.bodyWeightErrorEvents.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
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
                    value = programStatistics.totalWorkoutsCompleted.toString(),
                    modifier = Modifier.weight(1f)
                )
                DashboardStatCard(
                    title = stringResource(R.string.program_missed_sessions_label),
                    value = programStatistics.totalMissed.toString(),
                    modifier = Modifier.weight(1f)
                )
                DashboardStatCard(
                    title = stringResource(R.string.streak),
                    value = streak.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.program_statistics_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(stringResource(R.string.program_completion_percent, programStatistics.completionPercentage))
                    Text(stringResource(R.string.program_total_sets, programStatistics.totalSets))
                    Text(stringResource(R.string.program_total_reps, programStatistics.totalReps))
                    Text(stringResource(R.string.program_total_timer_seconds, programStatistics.totalTimerSeconds))
                    Text(stringResource(R.string.program_prs_achieved, programStatistics.totalPersonalRecords))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            MetricChartCard(
                title = stringResource(R.string.daily_volume_title),
                subtitle = stringResource(R.string.daily_volume_subtitle, averageVolume, latestVolume),
                emptyText = stringResource(R.string.no_volume_history_yet),
                entries = volumeChartEntries,
                labels = volumeChartLabels,
                dataSetLabel = stringResource(R.string.daily_volume_dataset),
                color = MaterialTheme.colorScheme.primary.toArgb()
            )

            Spacer(modifier = Modifier.height(24.dp))

            MetricChartCard(
                title = stringResource(R.string.workout_frequency_title),
                subtitle = stringResource(R.string.workout_frequency_subtitle),
                emptyText = stringResource(R.string.no_workout_frequency_yet),
                entries = frequencyChartEntries,
                labels = frequencyChartLabels,
                dataSetLabel = stringResource(R.string.workout_frequency_dataset),
                color = MaterialTheme.colorScheme.tertiary.toArgb(),
                maxVisibleValue = 7f
            )

            Spacer(modifier = Modifier.height(24.dp))

            PersonalRecordsCard(
                records = topPersonalRecords,
                emptyText = stringResource(R.string.no_personal_records_yet)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                if (legacyEntries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.no_progress_yet),
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
                            val dataSet = BarDataSet(legacyEntries, context.getString(R.string.chart_label))
                            dataSet.color = android.graphics.Color.GREEN
                            dataSet.valueTextColor = android.graphics.Color.WHITE
                            dataSet.valueTextSize = 10f
                            chart.data = BarData(dataSet)
                            chart.invalidate()
                        },
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.completed_days, progressList.count { it.isCompleted }),
                style = MaterialTheme.typography.titleMedium
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
                text = stringResource(R.string.completed_posture_sessions, postureProgressList.count { it.isCompleted }),
                style = MaterialTheme.typography.titleMedium
            )
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
private fun MetricChartCard(
    title: String,
    subtitle: String,
    emptyText: String,
    entries: List<BarEntry>,
    labels: List<String>,
    dataSetLabel: String,
    color: Int,
    maxVisibleValue: Float? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
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
                            setFitBars(true)
                            xAxis.position = XAxis.XAxisPosition.BOTTOM
                            xAxis.setDrawGridLines(false)
                            xAxis.textColor = android.graphics.Color.WHITE
                            xAxis.granularity = 1f
                            axisLeft.textColor = android.graphics.Color.WHITE
                            axisLeft.axisMinimum = 0f
                            axisRight.isEnabled = false
                            legend.isEnabled = false
                        }
                    },
                    update = { chart ->
                        val dataSet = BarDataSet(entries, dataSetLabel)
                        dataSet.color = color
                        dataSet.valueTextColor = android.graphics.Color.WHITE
                        dataSet.valueTextSize = 10f
                        chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                        chart.axisLeft.axisMaximum = maxVisibleValue ?: (entries.maxOf { it.y } + 2f).coerceAtLeast(5f)
                        chart.data = BarData(dataSet).apply {
                            barWidth = 0.6f
                        }
                        chart.invalidate()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
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

@Composable
private fun PersonalRecordsCard(
    records: List<Pair<Exercise, Int>>,
    emptyText: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.personal_records_highlights),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (records.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                records.forEach { (exercise, value) ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(exercise.nameRes),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (exercise.isTimerBased) {
                                    stringResource(R.string.personal_record_seconds, value)
                                } else {
                                    stringResource(R.string.personal_record_reps, value)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSessionDateLabel(sessionDate: String): String = sessionDate.takeLast(5)

private fun formatWeekLabel(weekLabel: String): String = "W${weekLabel.substringAfter('W', weekLabel)}"

private fun formatBodyWeight(weightKg: Float): String = String.format(Locale.US, "%.1f", weightKg)
