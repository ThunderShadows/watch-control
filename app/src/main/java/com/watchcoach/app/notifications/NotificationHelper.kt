package com.watchcoach.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watchcoach.app.MainActivity
import com.watchcoach.app.R

object NotificationHelper {
    const val CHANNEL_INACTIVITY = "inactivity"
    const val CHANNEL_ELEVATED_HR = "elevated_rhr"
    const val CHANNEL_SYNC = "watch_sync"
    const val CHANNEL_WEEKLY = "weekly_report"
    const val CHANNEL_EVENTS = "watch_events"

    const val ID_INACTIVITY = 1001
    const val ID_ELEVATED_HR = 1002
    const val ID_SYNC_FOREGROUND = 1003
    const val ID_WEEKLY = 1004
    const val ID_EVENTS_FOREGROUND = 1005
    const val ID_FIND_PHONE = 1006
    const val ID_SYNC_RESULT = 1007

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INACTIVITY,
                "Inactivity reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Nudge when step counts stay low during the day."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ELEVATED_HR,
                "Resting heart rate alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alert when resting heart rate is well above your 7-day baseline."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                "Watch sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while a background Bluetooth sync is running." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WEEKLY,
                "Weekly report",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Sunday evening weekly activity summary." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "Watch event listener",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while listening for watch buttons (music, camera, find phone)." },
        )
    }

    fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun postInactivity(context: Context, steps: Long, threshold: Int) {
        if (!canPostNotifications(context)) return
        val text = "Only $steps steps in the last 2 hours (threshold $threshold). " +
            "A short walk would help."
        post(
            context = context,
            id = ID_INACTIVITY,
            channel = CHANNEL_INACTIVITY,
            title = "You've been sitting a while",
            text = text,
        )
    }

    fun postElevatedHr(
        context: Context,
        current: Long,
        baseline: Long,
        deltaThreshold: Int,
    ) {
        if (!canPostNotifications(context)) return
        val delta = current - baseline
        val text = "Resting heart rate is $current bpm, $delta above your 7-day " +
            "baseline of $baseline (alert at +$deltaThreshold)."
        post(
            context = context,
            id = ID_ELEVATED_HR,
            channel = CHANNEL_ELEVATED_HR,
            title = "Resting heart rate is elevated",
            text = text,
        )
    }

    fun syncForeground(context: Context, text: String = "Syncing the Everest 2.0…"): android.app.Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_stat_watch_coach)
            .setContentTitle("Watch Coach sync")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun eventsForeground(context: Context, text: String = "Listening for watch buttons…"): android.app.Notification {
        ensureChannels(context)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "gestures")
        }
        val pending = PendingIntent.getActivity(
            context, ID_EVENTS_FOREGROUND, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_watch_coach)
            .setContentTitle("Gesture lab listening")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun postWeekly(context: Context, title: String, text: String) {
        post(context, ID_WEEKLY, CHANNEL_WEEKLY, title, text)
    }

    fun postSyncResult(context: Context, ok: Boolean, text: String) {
        post(
            context, ID_SYNC_RESULT, CHANNEL_SYNC,
            if (ok) "Background sync finished" else "Background sync failed",
            text,
        )
    }

    fun postFindPhone(context: Context, starting: Boolean) {
        post(
            context, ID_FIND_PHONE, CHANNEL_EVENTS,
            if (starting) "Find phone" else "Find phone stopped",
            if (starting) "The watch asked the phone to ring." else "The watch stopped find-phone.",
        )
    }

    private fun post(

        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
    ) {
        ensureChannels(context)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_watch_coach)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        if (!canPostNotifications(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS can be revoked between the check and this call.
        }
    }
}
