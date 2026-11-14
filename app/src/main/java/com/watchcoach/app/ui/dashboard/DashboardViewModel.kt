
package com.watchcoach.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.data.health.DashboardData
import com.watchcoach.app.data.health.DashboardSources
import com.watchcoach.app.data.health.DaySteps
import com.watchcoach.app.data.health.HealthAvailability
import com.watchcoach.app.data.health.HeartRateSnapshot
import com.watchcoach.app.data.health.RecoveryCalculator
import com.watchcoach.app.data.health.RecoveryScore
import com.watchcoach.app.data.health.SleepSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DashboardUiState(
    val availability: HealthAvailability = HealthAvailability.Unavailable,
    val hasPermissions: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val hasWatchData: Boolean = false,
    val data: DashboardData = emptyDashboard(),
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchCoachApplication
    private val health = app.healthRepository

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    val requiredPermissions: Set<String> get() = health.permissionsToRequest()

    init {
        refresh()
    }

    fun onPermissionsResult(granted: Set<String>) {
        val ok = granted.containsAll(health.requiredPermissions)
        _state.update { it.copy(hasPermissions = ok) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val availability = health.availability()
            _state.update { it.copy(availability = availability, loading = true, error = null) }
            val has = if (availability is HealthAvailability.Available) {
                health.hasAllRequiredPermissions()
            } else {
                false
            }
            _state.update { it.copy(hasPermissions = has) }

            val watch = runCatching { app.watchData.asDashboard() }.getOrNull()
            val hc = if (availability is HealthAvailability.Available && has) {
                runCatching { health.loadDashboard() }.getOrElse { err ->
                    _state.update { it.copy(error = err.message) }
                    null
                }
            } else {
                null
            }

            val merged = merge(hc, watch)
            _state.update {
                it.copy(
                    loading = false,
                    hasWatchData = watch != null,
                    data = merged,
                )
            }
        }
    }
}

private const val SRC_HC = "Health Connect"
private const val SRC_WATCH = "Watch (BLE)"
private const val SRC_EST = "Estimated from watch steps"

private fun merge(hc: DashboardData?, watch: DashboardData?): DashboardData {
    if (hc == null && watch == null) return emptyDashboard()
    if (hc == null) {
        return watch!!.copy(
            sources = DashboardSources(
                steps = SRC_WATCH,
                heart = SRC_WATCH,
                sleep = SRC_WATCH,
                distance = if (watch.todayDistanceMeters != null) SRC_EST else null,
                summary = "Source: $SRC_WATCH — Health Connect had no usable data.",
            ),
        )
    }
    if (watch == null) {
        return hc.copy(
            last7DaysSteps = hc.last7DaysSteps.ifEmpty { placeholderWeek() },
            sources = DashboardSources(
                steps = SRC_HC.takeIf { hc.todaySteps != null },
                heart = SRC_HC.takeIf { hc.heartRate.latestBpm != null || hc.heartRate.averageBpm != null },
                sleep = SRC_HC.takeIf { hc.lastNightSleep.duration != null },
                distance = SRC_HC.takeIf { hc.todayDistanceMeters != null },
                summary = "Source: $SRC_HC",
            ),
        )
    }

    fun pickLong(h: Long?, w: Long?): Pair<Long?, String?> = when {
        h != null && h > 0L -> h to SRC_HC
        w != null && w > 0L -> w to SRC_WATCH
        h != null -> h to SRC_HC
        w != null -> w to SRC_WATCH
        else -> null to null
    }

    val (steps, stepsSrc) = pickLong(hc.todaySteps, watch.todaySteps)
    val week = mergeWeek(hc.last7DaysSteps, watch.last7DaysSteps)
    val latest = hc.heartRate.latestBpm ?: watch.heartRate.latestBpm
    val latestTime = if (hc.heartRate.latestBpm != null) hc.heartRate.latestTime else watch.heartRate.latestTime
    val avg = hc.heartRate.averageBpm ?: watch.heartRate.averageBpm
    val heartSrc = when {
        hc.heartRate.latestBpm != null || hc.heartRate.averageBpm != null -> SRC_HC
        watch.heartRate.latestBpm != null || watch.heartRate.averageBpm != null -> SRC_WATCH
        else -> null
    }
    val sleepDur = hc.lastNightSleep.duration ?: watch.lastNightSleep.duration
    val sleepStart = if (hc.lastNightSleep.duration != null) hc.lastNightSleep.start else watch.lastNightSleep.start
    val sleepEnd = if (hc.lastNightSleep.duration != null) hc.lastNightSleep.end else watch.lastNightSleep.end
    val sleepSrc = when {
        hc.lastNightSleep.duration != null -> SRC_HC
        watch.lastNightSleep.duration != null -> SRC_WATCH
        else -> null
    }
    val rhr = hc.currentRestingHr ?: watch.currentRestingHr
    val baseline = hc.baselineRestingHr ?: watch.baselineRestingHr
    val (distance, distanceSrc) = when {
        hc.todayDistanceMeters != null && hc.todayDistanceMeters > 0 -> hc.todayDistanceMeters to SRC_HC
        watch.todayDistanceMeters != null && watch.todayDistanceMeters > 0 -> watch.todayDistanceMeters to SRC_EST
        else -> null to null
    }
    val calories = hc.todayCaloriesKcal ?: watch.todayCaloriesKcal
    val recovery = RecoveryCalculator.compute(sleepDur, rhr, baseline)
    val usedWatch = listOf(stepsSrc, heartSrc, sleepSrc).any { it == SRC_WATCH }
    val usedHc = listOf(stepsSrc, heartSrc, sleepSrc).any { it == SRC_HC }
    val summary = when {
        usedWatch && usedHc -> "Sources mixed: Health Connect where present, otherwise Watch (BLE)."
        usedWatch -> "Source: $SRC_WATCH — Health Connect was empty or unavailable."
        usedHc -> "Source: $SRC_HC"
        else -> null
    }
    return DashboardData(
        todaySteps = steps,
        last7DaysSteps = week,
        heartRate = HeartRateSnapshot(latest, latestTime, avg),
        lastNightSleep = SleepSnapshot(sleepDur, sleepStart, sleepEnd),
        currentRestingHr = rhr,
        baselineRestingHr = baseline,
        todayDistanceMeters = distance,
        todayCaloriesKcal = calories,
        recovery = recovery,
        sources = DashboardSources(stepsSrc, heartSrc, sleepSrc, distanceSrc, summary),
    )
}

private fun mergeWeek(hc: List<DaySteps>, watch: List<DaySteps>): List<DaySteps> {
    val today = LocalDate.now()
    val watchBy = watch.associateBy { it.date }
    val hcBy = hc.associateBy { it.date }
    return (6 downTo 0).map { offset ->
        val d = today.minusDays(offset.toLong())
        val h = hcBy[d]?.steps
        val w = watchBy[d]?.steps
        DaySteps(d, when {
            h != null && h > 0L -> h
            w != null && w > 0L -> w
            else -> h ?: w ?: 0L
        })
    }
}

private fun placeholderWeek(): List<DaySteps> {
    val today = LocalDate.now()
    return (6 downTo 0).map { DaySteps(today.minusDays(it.toLong()), 0) }
}

private fun emptyDashboard() = DashboardData(
    todaySteps = null,
    last7DaysSteps = placeholderWeek(),
    heartRate = HeartRateSnapshot(null, null, null),
    lastNightSleep = SleepSnapshot(null, null, null),
    currentRestingHr = null,
    baselineRestingHr = null,
    todayDistanceMeters = null,
    todayCaloriesKcal = null,
    recovery = RecoveryScore(null, null, null, null, null, null, ""),
)
