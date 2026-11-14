package com.watchcoach.app.data.health

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class DaySteps(
    val date: LocalDate,
    val steps: Long,
)

data class HeartRateSnapshot(
    val latestBpm: Long?,
    val latestTime: Instant?,
    val averageBpm: Long?,
)

data class SleepSnapshot(
    val duration: Duration?,
    val start: Instant?,
    val end: Instant?,
)

/**
 * Recovery score (0–100) from last night's sleep and resting HR vs a 7-day baseline.
 *
 *   score = 0.6 * sleepScore + 0.4 * heartScore
 *
 * Sleep score (0–100):
 *   hours = last night's sleep duration
 *   ≥ 8.0 h → 100
 *   ≤ 4.0 h → 0
 *   otherwise linear: ((hours - 4) / 4) * 100
 *
 * Heart score (0–100), using today's (or most recent) resting HR vs the 7-day mean:
 *   delta = currentRestingHr - baselineRestingHr
 *   delta ≤ 0  → 100  (at or below baseline)
 *   delta ≥ 15 → 0
 *   otherwise linear: ((15 - delta) / 15) * 100
 *
 * If only one input is available, the score equals that component (sleep or heart).
 * If neither is available, [value] is null.
 *
 * The alert worker uses a separate, tighter threshold (default +10 bpm) to notify;
 * the score uses a 15 bpm window so a modest elevation does not collapse the score.
 */
data class RecoveryScore(
    val value: Int?,
    val sleepScore: Int?,
    val heartScore: Int?,
    val sleepHours: Double?,
    val currentRestingHr: Long?,
    val baselineRestingHr: Long?,
    val explanation: String,
)

data class DashboardData(
    val todaySteps: Long?,
    val last7DaysSteps: List<DaySteps>,
    val heartRate: HeartRateSnapshot,
    val lastNightSleep: SleepSnapshot,
    val currentRestingHr: Long?,
    val baselineRestingHr: Long?,
    val todayDistanceMeters: Double?,
    val todayCaloriesKcal: Double?,
    val recovery: RecoveryScore,
    val sources: DashboardSources = DashboardSources(),
)

data class DashboardSources(
    val steps: String? = null,
    val heart: String? = null,
    val sleep: String? = null,
    val distance: String? = null,
    val summary: String? = null,
)

sealed class HealthAvailability {
    data object Available : HealthAvailability()
    data object NotInstalled : HealthAvailability()
    data object UpdateRequired : HealthAvailability()
    data object Unavailable : HealthAvailability()
}
