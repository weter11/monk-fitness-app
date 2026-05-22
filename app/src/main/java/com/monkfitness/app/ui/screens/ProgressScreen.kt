package com.monkfitness.app.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.monkfitness.app.R
import com.monkfitness.app.viewmodel.MainViewModel

@Composable
fun ProgressScreen(viewModel: MainViewModel) {
    val progressList by viewModel.allProgress.collectAsState()

    val entries = progressList
        .filter { it.isCompleted }
        .groupBy { ((it.day - 1) / 7) + 1 }
        .map { (week, list) -> BarEntry(week.toFloat(), list.size.toFloat()) }
        .sortedBy { it.x }

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

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            if (entries.isEmpty()) {
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
                    factory = { context ->
                        BarChart(context).apply {
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
                        val dataSet = BarDataSet(entries, "Workouts per week")
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
    }
}
