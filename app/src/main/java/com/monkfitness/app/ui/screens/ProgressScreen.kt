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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.viewmodel.MainViewModel

@Composable
fun ProgressScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val progressList by viewModel.allProgress.collectAsState()
    val postureProgressList by viewModel.postureProgress.collectAsState()
    val volumeHistory by viewModel.volumeHistory.collectAsState()
    val workoutFrequencyHistory by viewModel.workoutFrequencyHistory.collectAsState()
    val personalRecords by viewModel.exercisePersonalRecords.collectAsState()
    val currentProgramDay by viewModel.currentProgramDay.collectAsState()
    val streak by viewModel.streak.collectAsState()

    val legacyEntries = progressList
        .filter { it.isCompleted }
        .groupBy { ((it.day - 1) / 7) + 1 }
        .map { (week, list) -> BarEntry(week.toFloat(), list.size.toFloat()) }
        .sortedBy { it.x }

    val postureEntries = postureProgressList
        .filter { it.isCompleted }
        .groupBy { ((it.day - 1) / 7) + 1 }
        .map { (week, list) -> BarEntry(week.toFloat(), list.size.toFloat()) }
        .sortedBy { it.x }

    val volumeEntries = remember(volumeHistory) { volumeHistory.takeLast(7) }
    val frequencyEntries = remember(workoutFrequencyHistory) { workoutFrequencyHistory.takeLast(8) }
    val totalVolume = volumeHistory.sumOf { it.totalReps }
    val averageVolume = if (volumeHistory.isNotEmpty()) totalVolume / volumeHistory.size else 0
    val latestVolume = volumeHistory.lastOrNull()?.totalReps ?: 0
    val secondaryColor = MaterialTheme.colorScheme.secondary.toArgb()
    val postureCompletionRatio = postureProgressList.count { it.isCompleted }.toFloat() / 56f
    val topPersonalRecords = remember(personalRecords, currentProgramDay) {
        personalRecords.entries
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
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.your_progress),
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardStatCard(
                title = stringResource(R.string.completed_days_short),
                value = progressList.count { it.isCompleted }.toString(),
                modifier = Modifier.weight(1f)
            )
            DashboardStatCard(
                title = stringResource(R.string.total_volume_short),
                value = totalVolume.toString(),
                modifier = Modifier.weight(1f)
            )
            DashboardStatCard(
                title = stringResource(R.string.streak),
                value = streak.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        MetricChartCard(
            title = stringResource(R.string.daily_volume_title),
            subtitle = stringResource(R.string.daily_volume_subtitle, averageVolume, latestVolume),
            emptyText = stringResource(R.string.no_volume_history_yet),
            entries = volumeEntries.mapIndexed { index, point -> BarEntry(index.toFloat(), point.totalReps.toFloat()) },
            labels = volumeEntries.map { formatSessionDateLabel(it.sessionDate) },
            dataSetLabel = stringResource(R.string.daily_volume_dataset),
            color = MaterialTheme.colorScheme.primary.toArgb()
        )

        Spacer(modifier = Modifier.height(24.dp))

        MetricChartCard(
            title = stringResource(R.string.workout_frequency_title),
            subtitle = stringResource(R.string.workout_frequency_subtitle),
            emptyText = stringResource(R.string.no_workout_frequency_yet),
            entries = frequencyEntries.mapIndexed { index, point -> BarEntry(index.toFloat(), point.sessionCount.toFloat()) },
            labels = frequencyEntries.map { formatWeekLabel(it.weekLabel) },
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
