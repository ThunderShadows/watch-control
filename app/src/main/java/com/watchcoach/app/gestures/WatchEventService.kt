package com.watchcoach.app.gestures

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.ble.gloryfit.ClientState
import com.watchcoach.app.ble.gloryfit.WatchEventKind
import com.watchcoach.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground connected-device service that keeps a GATT link up and dispatches
 * documented watch-originated events (D1 music / find-phone, C4 camera).
 */
class WatchEventService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private val dispatcher by lazy { GestureActionDispatcher(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = NotificationHelper.eventsForeground(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.ID_EVENTS_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NotificationHelper.ID_EVENTS_FOREGROUND, notification)
        }
        _running.value = true
        val app = applicationContext as WatchCoachApplication
        app.gloryFitClient.retainConnection = true
        scope.launch {
            val paired = app.deviceSettings.current()
            if (app.gloryFitClient.state.value != ClientState.Ready &&
                app.gloryFitClient.state.value != ClientState.Syncing
            ) {
                val result = app.gloryFitClient.connect(paired.address, paired.name)
                if (result.isFailure) {
                    app.packetLogger.warn(
                        "Event listener could not connect: ${result.exceptionOrNull()?.message}. Force-stop CB SmartFit.",
                    )
                    stopSelf()
                    return@launch
                }
            }
        }
        collectJob = scope.launch {
            app.gloryFitClient.watchEvents.collect { event ->
                if (event.kind == WatchEventKind.UNKNOWN) return@collect
                val mapping = app.featureSettings.current().mappings.firstOrNull { it.kind == event.kind }
                val result = runCatching { dispatcher.dispatch(event, mapping) }.getOrElse { it.message ?: "action failed" }
                app.packetLogger.info("Watch event ${event.kind.label}: $result")
            }
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        dispatcher.stopFindPhone()
        val app = applicationContext as WatchCoachApplication
        app.gloryFitClient.retainConnection = false
        app.gloryFitClient.disconnect()
        _running.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, WatchEventService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchEventService::class.java))
        }
    }
}
