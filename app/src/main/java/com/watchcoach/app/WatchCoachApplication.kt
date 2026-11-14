package com.watchcoach.app

import android.app.Application
import com.watchcoach.app.ble.PacketLogger
import com.watchcoach.app.ble.gloryfit.GloryFitClient
import com.watchcoach.app.data.health.HealthConnectRepository
import com.watchcoach.app.data.local.WatchDataRepository
import com.watchcoach.app.data.local.WatchDatabase
import com.watchcoach.app.data.settings.AlertSettingsRepository
import com.watchcoach.app.data.settings.DeviceSettingsRepository
import com.watchcoach.app.data.settings.FeatureSettingsRepository
import com.watchcoach.app.notifications.NotificationHelper
import com.watchcoach.app.work.HealthAlertScheduler
import com.watchcoach.app.work.WatchBackgroundSyncWorker
import com.watchcoach.app.work.WeeklyReportWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WatchCoachApplication : Application() {

    lateinit var settingsRepository: AlertSettingsRepository
        private set
    lateinit var deviceSettings: DeviceSettingsRepository
        private set
    lateinit var healthRepository: HealthConnectRepository
        private set
    lateinit var packetLogger: PacketLogger
        private set
    lateinit var watchData: WatchDataRepository
        private set
    lateinit var gloryFitClient: GloryFitClient
        private set
    lateinit var featureSettings: FeatureSettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = AlertSettingsRepository(this)
        deviceSettings = DeviceSettingsRepository(this)
        featureSettings = FeatureSettingsRepository(this)
        healthRepository = HealthConnectRepository(this)
        packetLogger = PacketLogger(this)
        val db = WatchDatabase.create(this)
        watchData = WatchDataRepository(db.watchDao())
        gloryFitClient = GloryFitClient(this, packetLogger)
        NotificationHelper.ensureChannels(this)
        HealthAlertScheduler.ensureScheduled(this)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        appScope.launch {
            featureSettings.ensureToken()
            val features = featureSettings.current()
            WatchBackgroundSyncWorker.schedule(this@WatchCoachApplication, features.backgroundSyncEnabled)
            WeeklyReportWorker.schedule(this@WatchCoachApplication, features.weeklyReportEnabled)
        }
        packetLogger.info("Watch Coach 0.4 started")
    }
}
