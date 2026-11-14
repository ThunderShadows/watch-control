package com.watchcoach.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.insights.InsightsCalculator
import com.watchcoach.app.notifications.NotificationHelper
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

class WeeklyReportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as WatchCoachApplication
        val features = app.featureSettings.current()
        if (!features.weeklyReportEnabled) return Result.success()
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        if (now.dayOfWeek != DayOfWeek.SUNDAY || now.hour < 18) {
            return Result.success()
        }
        val today = LocalDate.now(zone)
        // Avoid double-sending in the same Sunday evening window.
        val last = features.lastWeeklyReportEpochMs
        if (last > 0) {
            val lastDate = java.time.Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
            if (lastDate == today) return Result.success()
        }
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val (steps, hrs) = app.watchData.history(weekStart.minusDays(1), today.plusDays(1), zone)
        val report = InsightsCalculator.weekReport(steps, hrs, features.ageYears, zone, today, weekStart)
        val text = "Steps ${report.totalSteps} (${report.avgSteps}/day, goal ${report.goal}). " +
            "Night RHR ${report.weekRhr ?: "—"} bpm. Most active ${report.heatmap.mostActiveLabel}."
        NotificationHelper.postWeekly(app, "Your week on Watch Coach", text)
        app.featureSettings.markWeeklyReportSent()
        return Result.success()
    }

    companion object {
        const val UNIQUE = "watch_coach_weekly_report"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(UNIQUE)
                return
            }
            val request = PeriodicWorkRequestBuilder<WeeklyReportWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayUntilSundayEvening(), TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        private fun delayUntilSundayEvening(): Long {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            var target = now.with(LocalTime.of(18, 0))
            while (target.dayOfWeek != DayOfWeek.SUNDAY || !target.isAfter(now)) {
                target = target.plusDays(1).with(LocalTime.of(18, 0))
            }
            return Duration.between(now, target).toMillis().coerceAtLeast(1_000)
        }
    }
}
