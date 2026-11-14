package com.watchcoach.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.alertSettingsStore by preferencesDataStore(name = "alert_settings")

class AlertSettingsRepository(private val context: Context) {

    val settings: Flow<AlertSettings> = context.alertSettingsStore.data.map { prefs ->
        AlertSettings(
            inactivityAlertsEnabled = prefs[INACTIVITY_ENABLED] ?: true,
            inactivityStepThreshold = prefs[INACTIVITY_THRESHOLD] ?: 200,
            daytimeStartHour = prefs[DAY_START] ?: 8,
            daytimeEndHour = prefs[DAY_END] ?: 21,
            elevatedHrAlertsEnabled = prefs[ELEVATED_HR_ENABLED] ?: true,
            elevatedHrBpmDelta = prefs[ELEVATED_HR_DELTA] ?: 10,
            lastInactivityNotificationEpochMs = prefs[LAST_INACTIVITY_MS] ?: 0L,
            lastElevatedHrNotificationEpochMs = prefs[LAST_ELEVATED_MS] ?: 0L,
        )
    }

    suspend fun current(): AlertSettings = settings.first()

    suspend fun setInactivityEnabled(enabled: Boolean) {
        context.alertSettingsStore.edit { it[INACTIVITY_ENABLED] = enabled }
    }

    suspend fun setInactivityThreshold(steps: Int) {
        context.alertSettingsStore.edit { it[INACTIVITY_THRESHOLD] = steps.coerceIn(50, 5000) }
    }

    suspend fun setDaytimeHours(startHour: Int, endHour: Int) {
        context.alertSettingsStore.edit {
            it[DAY_START] = startHour.coerceIn(0, 23)
            it[DAY_END] = endHour.coerceIn(1, 24)
        }
    }

    suspend fun setElevatedHrEnabled(enabled: Boolean) {
        context.alertSettingsStore.edit { it[ELEVATED_HR_ENABLED] = enabled }
    }

    suspend fun setElevatedHrDelta(bpm: Int) {
        context.alertSettingsStore.edit { it[ELEVATED_HR_DELTA] = bpm.coerceIn(5, 30) }
    }

    suspend fun markInactivityNotified(atEpochMs: Long) {
        context.alertSettingsStore.edit { it[LAST_INACTIVITY_MS] = atEpochMs }
    }

    suspend fun markElevatedHrNotified(atEpochMs: Long) {
        context.alertSettingsStore.edit { it[LAST_ELEVATED_MS] = atEpochMs }
    }

    private companion object {
        val INACTIVITY_ENABLED = booleanPreferencesKey("inactivity_enabled")
        val INACTIVITY_THRESHOLD = intPreferencesKey("inactivity_threshold")
        val DAY_START = intPreferencesKey("day_start")
        val DAY_END = intPreferencesKey("day_end")
        val ELEVATED_HR_ENABLED = booleanPreferencesKey("elevated_hr_enabled")
        val ELEVATED_HR_DELTA = intPreferencesKey("elevated_hr_delta")
        val LAST_INACTIVITY_MS = longPreferencesKey("last_inactivity_ms")
        val LAST_ELEVATED_MS = longPreferencesKey("last_elevated_ms")
    }
}
