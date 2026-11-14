package com.watchcoach.app.ui.alerts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.data.settings.AlertSettings
import com.watchcoach.app.data.settings.FeatureSettings
import com.watchcoach.app.work.WatchBackgroundSyncWorker
import com.watchcoach.app.work.WeeklyReportWorker
import com.watchcoach.app.notifications.NotificationHelper
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlertsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchCoachApplication
    private val repo = app.settingsRepository

    val settings: StateFlow<AlertSettings> = repo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AlertSettings(),
    )

    val notificationsAllowed: Boolean
        get() = NotificationHelper.canPostNotifications(getApplication())

    fun setInactivityEnabled(enabled: Boolean) = viewModelScope.launch {
        repo.setInactivityEnabled(enabled)
    }

    fun setInactivityThreshold(steps: Int) = viewModelScope.launch {
        repo.setInactivityThreshold(steps)
    }

    fun setDaytimeHours(start: Int, end: Int) = viewModelScope.launch {
        repo.setDaytimeHours(start, end)
    }

    fun setElevatedHrEnabled(enabled: Boolean) = viewModelScope.launch {
        repo.setElevatedHrEnabled(enabled)
    }

    fun setElevatedHrDelta(bpm: Int) = viewModelScope.launch {
        repo.setElevatedHrDelta(bpm)
    }

    val features: StateFlow<FeatureSettings> = app.featureSettings.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FeatureSettings(),
    )

    fun setBackgroundSync(enabled: Boolean) = viewModelScope.launch {
        app.featureSettings.setBackgroundSyncEnabled(enabled)
        WatchBackgroundSyncWorker.schedule(getApplication(), enabled)
    }

    fun setWeeklyReport(enabled: Boolean) = viewModelScope.launch {
        app.featureSettings.setWeeklyReportEnabled(enabled)
        WeeklyReportWorker.schedule(getApplication(), enabled)
    }

    fun setAge(age: Int) = viewModelScope.launch {
        app.featureSettings.setAge(age)
    }
}
