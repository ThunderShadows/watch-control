package com.watchcoach.app.ui.insights

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.insights.InsightsCalculator
import com.watchcoach.app.insights.WeeklyReport
import com.watchcoach.app.insights.WeeklyReportShare
import com.watchcoach.app.ui.components.EmptyState
import com.watchcoach.app.ui.components.ErrorState
import com.watchcoach.app.ui.components.LoadingState
import com.watchcoach.app.ui.components.WatchCoachTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

class WeeklyReportViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchCoachApplication
    private val _report = MutableStateFlow<WeeklyReport?>(null)
    val report = _report.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { load() }

    fun load(weekStart: LocalDate? = null) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                val today = LocalDate.now()
                val start = weekStart ?: today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val age = app.featureSettings.current().ageYears
                val (steps, hrs) = app.watchData.history(start.minusDays(1), start.plusDays(8))
                _report.value = InsightsCalculator.weekReport(steps, hrs, age, today = today, weekStart = start)
            }.onFailure { _error.value = it.message ?: "Couldn’t build the report." }
            _loading.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyReportScreen(
    onBack: (() -> Unit)? = null,
    viewModel: WeeklyReportViewModel = viewModel(),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(topBar = { WatchCoachTopBar(title = "Weekly report", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading && report == null) {
                LoadingState("Putting this week together…")
            }
            error?.let { message ->
                ErrorState(
                    title = "The report didn’t build",
                    body = "$message\n\nSync from the Dashboard, then open the report again.",
                )
            }
            val data = report
            if (data == null) {
                if (!loading) {
                    EmptyState(
                        title = "No week to show",
                        body = "Sync the watch from the Dashboard. If CB SmartFit is still connected, force-stop it first.",
                    )
                }
                return@Column
            }
            if (data.totalSteps == 0) {
                EmptyState(
                    title = "This week has no steps yet",
                    body = "The report is ready, but nothing was stored for these dates. Sync the watch, then come back.",
                )
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${data.weekStart} – ${data.weekEnd}", style = MaterialTheme.typography.titleMedium)
                    Text("Steps ${data.totalSteps}  ·  ${data.avgSteps}/day  ·  goal ${data.goal}  ·  met ${data.daysGoalMet}/7")
                    Text("Night RHR ${data.weekRhr?.let { "$it bpm" } ?: "—"}  ·  last night ${data.lastNightRhr?.let { "$it bpm" } ?: "—"}")
                    Text("Most active ${data.heatmap.mostActiveLabel}  ·  least ${data.heatmap.leastActiveLabel}")
                    HourHeatmapChart(data.heatmap.byHour, Modifier.fillMaxWidth().height(72.dp))
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Text(
                    data.asText(),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Button(
                onClick = {
                    runCatching {
                        val file = WeeklyReportShare.writePng(context, data)
                        WeeklyReportShare.share(context, file, "image/png")
                    }.onFailure {
                        Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Share image") }
            OutlinedButton(
                onClick = {
                    runCatching {
                        val file = WeeklyReportShare.writePdf(context, data)
                        WeeklyReportShare.share(context, file, "application/pdf")
                    }.onFailure {
                        Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Share PDF") }
            OutlinedButton(
                onClick = {
                    val file = WeeklyReportShare.writeText(context, data)
                    WeeklyReportShare.share(context, file, "text/plain")
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Share text") }
            OutlinedButton(
                onClick = { viewModel.load(data.weekStart.minusDays(7)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Previous week") }
            OutlinedButton(
                onClick = { viewModel.load(data.weekStart.plusDays(7)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Next week") }
        }
    }
}
