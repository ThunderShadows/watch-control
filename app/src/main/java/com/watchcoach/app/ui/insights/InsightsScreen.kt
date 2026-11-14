package com.watchcoach.app.ui.insights

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.insights.Insights
import com.watchcoach.app.insights.InsightsCalculator
import com.watchcoach.app.ui.components.EmptyState
import com.watchcoach.app.ui.components.ErrorState
import com.watchcoach.app.ui.components.LoadingState
import com.watchcoach.app.ui.components.WatchCoachTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

class InsightsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchCoachApplication
    private val _insights = MutableStateFlow<Insights?>(null)
    val insights = _insights.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                val age = app.featureSettings.current().ageYears
                val today = LocalDate.now()
                val (steps, hrs) = app.watchData.history(today.minusDays(30), today.plusDays(1))
                _insights.value = InsightsCalculator.compute(steps, hrs, age)
            }.onFailure { _error.value = it.message ?: "Couldn’t read stored history." }
            _loading.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    onOpenWeekly: () -> Unit,
    viewModel: InsightsViewModel = viewModel(),
) {
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(topBar = { WatchCoachTopBar(title = "Insights") }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading && insights == null) {
                LoadingState("Reading stored steps and heart rate…")
            }
            error?.let { message ->
                ErrorState(
                    title = "Insights didn’t load",
                    body = "$message\n\nTap Refresh. If this keeps happening, sync again from the Dashboard.",
                )
            }
            val data = insights
            if (data == null) {
                if (!loading) {
                    EmptyState(
                        title = "No history yet",
                        body = "Sync from the Dashboard first. If the watch is busy, force-stop CB SmartFit and try again. " +
                            "Insights use the steps and heart-rate records stored on this phone.",
                    )
                }
                return@Column
            }
            if (data.todaySteps == 0 && data.weekSteps == 0 && data.lastNightRhr == null) {
                EmptyState(
                    title = "History is still empty",
                    body = "A sync finished without step or heart-rate records. Force-stop CB SmartFit, wear the watch, and sync again.",
                )
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Resting heart rate (night)", style = MaterialTheme.typography.titleMedium)
                    Text("Last night: ${data.lastNightRhr?.let { "$it bpm" } ?: "—"}", fontWeight = FontWeight.SemiBold)
                    Text("7-day: ${data.rhr7?.let { "$it bpm" } ?: "—"}    30-day: ${data.rhr30?.let { "$it bpm" } ?: "—"}")
                    Text(data.rhrMethod, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Adaptive step goal", style = MaterialTheme.typography.titleMedium)
                    Text("${data.adaptiveGoal} steps / day", fontWeight = FontWeight.SemiBold)
                    Text("Today: ${data.todaySteps}    Last 7 days: ${data.weekSteps}")
                    Text(data.adaptiveGoalNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Trends", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "7-day steps: ${pct(data.trend7.stepChangePct)} vs first half of the window. " +
                            "RHR ${bpmDelta(data.trend7.rhrChangeBpm)}.",
                    )
                    Text(
                        "30-day steps: ${pct(data.trend30.stepChangePct)}. " +
                            "RHR ${bpmDelta(data.trend30.rhrChangeBpm)}.",
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Most / least active hours (last 7 days)", style = MaterialTheme.typography.titleMedium)
                    Text("Most: ${data.heatmap.mostActiveLabel}    Least: ${data.heatmap.leastActiveLabel}")
                    HourHeatmapChart(
                        data.heatmap.byHour,
                        Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .semantics {
                                contentDescription =
                                    "Steps by hour. Most active ${data.heatmap.mostActiveLabel}. Least active ${data.heatmap.leastActiveLabel}."
                            },
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("HR zones (last 7 days of history bins)", style = MaterialTheme.typography.titleMedium)
                    Text("Max HR ${data.zones.maxHr} from 220 − ${data.zones.age}. Set your age in Settings.")
                    data.zones.labels.forEach { (name, range) ->
                        Text("$name  $range  —  ${data.zones.counts[name] ?: 0} samples")
                    }
                }
            }
            Button(
                onClick = onOpenWeekly,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Open weekly report")
            }
            OutlinedButton(
                onClick = { viewModel.refresh() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Refresh")
            }
        }
    }
}

@Composable
fun HourHeatmapChart(byHour: IntArray, modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val max = max(1, byHour.maxOrNull() ?: 1)
    Canvas(modifier) {
        val slot = size.width / 24f
        val gap = slot * 0.2f
        byHour.forEachIndexed { i, v ->
            val h = (v.toFloat() / max) * size.height
            drawRect(
                color = bar,
                topLeft = Offset(i * slot + gap / 2, size.height - h),
                size = Size(slot - gap, h),
            )
        }
    }
}

private fun pct(value: Double?): String =
    value?.let { "%+.0f%%".format(it) } ?: "—"

private fun bpmDelta(value: Double?): String =
    value?.let { "%+.1f bpm".format(it) } ?: "—"
