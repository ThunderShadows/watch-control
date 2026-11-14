package com.watchcoach.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.watchcoach.app.data.health.HealthAvailability
import com.watchcoach.app.data.health.HealthConnectRepository
import com.watchcoach.app.data.settings.AlertSettingsRepository
import com.watchcoach.app.notifications.NotificationHelper
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Hourly check:
 *  - During configured daytime hours, if steps in the last 2 hours are below the
 *    inactivity threshold, post an inactivity notification (2.5 h cooldown).
 *  - If current resting HR is more than [elevatedHrBpmDelta] (default 10) bpm
 *    above the 7-day baseline, post an elevated-HR notification (12 h cooldown).
 */
class HealthAlertWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val health = HealthConnectRepository(applicationContext)
        val settingsRepo = AlertSettingsRepository(applicationContext)
        val settings = settingsRepo.current()

        if (health.availability() !is HealthAvailability.Available) {
            return Result.success()
        }
        if (!health.hasAllRequiredPermissions()) {
            return Result.success()
        }

        val now = Instant.now()
        val local = ZonedDateTime.ofInstant(now, ZoneId.systemDefault())

        try {
            if (settings.inactivityAlertsEnabled && settings.isDaytime(local.hour)) {
                val steps = health.stepsInLastHours(2, now)
                if (steps != null && steps < settings.inactivityStepThreshold) {
                    val elapsed = now.toEpochMilli() - settings.lastInactivityNotificationEpochMs
                    if (elapsed >= INACTIVITY_COOLDOWN_MS) {
                        NotificationHelper.postInactivity(
                            applicationContext,
                            steps,
                            settings.inactivityStepThreshold,
                        )
                        settingsRepo.markInactivityNotified(now.toEpochMilli())
                    }
                }
            }

            if (settings.elevatedHrAlertsEnabled) {
                val current = health.currentRestingHr()
                val baseline = health.baselineRestingHr()
                if (current != null && baseline != null) {
                    val delta = current - baseline
                    if (delta > settings.elevatedHrBpmDelta) {
                        val elapsed = now.toEpochMilli() - settings.lastElevatedHrNotificationEpochMs
                        if (elapsed >= ELEVATED_HR_COOLDOWN_MS) {
                            NotificationHelper.postElevatedHr(
                                applicationContext,
                                current,
                                baseline,
                                settings.elevatedHrBpmDelta,
                            )
                            settingsRepo.markElevatedHrNotified(now.toEpochMilli())
                        }
                    }
                }
            }
            return Result.success()
        } catch (security: SecurityException) {
            return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        }
    }

    private companion object {
        const val INACTIVITY_COOLDOWN_MS = 150L * 60L * 1000L // 2.5 hours
        const val ELEVATED_HR_COOLDOWN_MS = 12L * 60L * 60L * 1000L
    }
}
