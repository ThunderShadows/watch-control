package com.watchcoach.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.ble.gloryfit.ClientState
import com.watchcoach.app.notifications.NotificationHelper
import java.util.concurrent.TimeUnit

class WatchBackgroundSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Connecting to the watch…")

    override suspend fun doWork(): Result {
        val app = applicationContext as WatchCoachApplication
        val features = app.featureSettings.current()
        if (!features.backgroundSyncEnabled) return Result.success()
        setForeground(foreground("Syncing the Everest 2.0…"))
        val paired = app.deviceSettings.current()
        val client = app.gloryFitClient
        return try {
            if (client.state.value != ClientState.Ready && client.state.value != ClientState.Syncing) {
                val connect = client.connect(paired.address, paired.name)
                if (connect.isFailure) {
                    val msg = "Could not connect to ${paired.address}. Force-stop CB SmartFit and check battery optimization. " +
                        (connect.exceptionOrNull()?.message ?: "")
                    app.featureSettings.recordBackgroundSync(false, msg.trim())
                    app.packetLogger.warn("Background sync: $msg")
                    NotificationHelper.postSyncResult(app, false, msg)
                    return Result.retry()
                }
            }
            val snapshot = client.sync()
            app.watchData.persist(snapshot)
            if (app.healthRepository.hasWritePermissions()) {
                runCatching { app.healthRepository.writeFromWatch(app.watchData.exportForHealthConnect()) }
            }
            val summary = snapshot.summary?.asText()?.replace("\n", " | ")
                ?: "Steps ${snapshot.hourlySteps.size} records, HR ${snapshot.heartRates.size} samples."
            app.featureSettings.recordBackgroundSync(true, summary)
            if (!client.retainConnection) client.disconnect()
            NotificationHelper.postSyncResult(app, true, summary)
            Result.success()
        } catch (t: Throwable) {
            val msg = t.message ?: "Background sync failed"
            app.featureSettings.recordBackgroundSync(false, msg)
            app.packetLogger.warn("Background sync failed: $msg")
            NotificationHelper.postSyncResult(app, false, "$msg — will retry. Force-stop CB SmartFit if it is holding the link.")
            Result.retry()
        }
    }

    private fun foreground(text: String): ForegroundInfo {
        val notification = NotificationHelper.syncForeground(applicationContext, text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.ID_SYNC_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            ForegroundInfo(NotificationHelper.ID_SYNC_FOREGROUND, notification)
        }
    }

    companion object {
        const val UNIQUE = "watch_coach_background_sync"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(UNIQUE)
                return
            }
            val request = PeriodicWorkRequestBuilder<WatchBackgroundSyncWorker>(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
