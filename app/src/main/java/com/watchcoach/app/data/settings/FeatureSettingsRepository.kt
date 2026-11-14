package com.watchcoach.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.watchcoach.app.ble.gloryfit.WatchEventKind
import com.watchcoach.app.gestures.MappedAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.featureStore by preferencesDataStore(name = "feature_settings")

class FeatureSettingsRepository(private val context: Context) {

    val settings: Flow<FeatureSettings> = context.featureStore.data.map { prefs ->
        val token = prefs[DESKTOP_TOKEN] ?: ""
        FeatureSettings(
            backgroundSyncEnabled = prefs[BG_SYNC] ?: false,
            lastBackgroundSyncEpochMs = prefs[BG_SYNC_AT] ?: 0L,
            lastBackgroundSyncOk = prefs[BG_SYNC_OK]?.let { it != 0 },
            lastBackgroundSyncMessage = prefs[BG_SYNC_MSG],
            weeklyReportEnabled = prefs[WEEKLY] ?: true,
            lastWeeklyReportEpochMs = prefs[WEEKLY_AT] ?: 0L,
            ageYears = prefs[AGE] ?: 30,
            desktopHost = prefs[DESKTOP_HOST] ?: "",
            desktopPort = prefs[DESKTOP_PORT] ?: 8765,
            desktopToken = token,
            heightCm = prefs[HEIGHT] ?: 170,
            weightKg = prefs[WEIGHT] ?: 70,
            screenTimeoutSec = prefs[TIMEOUT] ?: 5,
            stepsGoal = prefs[GOAL] ?: 8_000,
            userMale = prefs[MALE] ?: true,
            celsius = prefs[CELSIUS] ?: true,
            mappings = decodeMappings(prefs[MAPPINGS]),
        )
    }

    suspend fun current(): FeatureSettings = settings.first()

    suspend fun ensureToken(): String {
        val existing = current().desktopToken
        if (existing.isNotBlank()) return existing
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        context.featureStore.edit { it[DESKTOP_TOKEN] = token }
        return token
    }

    suspend fun setBackgroundSyncEnabled(enabled: Boolean) {
        context.featureStore.edit { it[BG_SYNC] = enabled }
    }

    suspend fun recordBackgroundSync(ok: Boolean, message: String, atMs: Long = System.currentTimeMillis()) {
        context.featureStore.edit {
            it[BG_SYNC_AT] = atMs
            it[BG_SYNC_OK] = if (ok) 1 else 0
            it[BG_SYNC_MSG] = message
        }
    }

    suspend fun setWeeklyReportEnabled(enabled: Boolean) {
        context.featureStore.edit { it[WEEKLY] = enabled }
    }

    suspend fun markWeeklyReportSent(atMs: Long = System.currentTimeMillis()) {
        context.featureStore.edit { it[WEEKLY_AT] = atMs }
    }

    suspend fun setAge(age: Int) {
        context.featureStore.edit { it[AGE] = age.coerceIn(3, 100) }
    }

    suspend fun setDesktop(host: String, port: Int) {
        context.featureStore.edit {
            it[DESKTOP_HOST] = host.trim()
            it[DESKTOP_PORT] = port.coerceIn(1, 65535)
        }
    }

    suspend fun rotateToken(): String {
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        context.featureStore.edit { it[DESKTOP_TOKEN] = token }
        return token
    }

    suspend fun setUserInfoDefaults(
        heightCm: Int,
        weightKg: Int,
        screenTimeoutSec: Int,
        stepsGoal: Int,
        male: Boolean,
        celsius: Boolean,
    ) {
        context.featureStore.edit {
            it[HEIGHT] = heightCm.coerceIn(91, 241)
            it[WEIGHT] = weightKg.coerceIn(20, 255)
            it[TIMEOUT] = screenTimeoutSec.coerceIn(5, 15)
            it[GOAL] = stepsGoal.coerceIn(1_000, 30_000)
            it[MALE] = male
            it[CELSIUS] = celsius
        }
    }

    suspend fun setMappings(mappings: List<GestureMapping>) {
        context.featureStore.edit { it[MAPPINGS] = encodeMappings(mappings) }
    }

    suspend fun upsertMapping(mapping: GestureMapping) {
        val cur = current().mappings.toMutableList()
        val idx = cur.indexOfFirst { it.kind == mapping.kind }
        if (idx >= 0) cur[idx] = mapping else cur += mapping
        setMappings(cur)
    }

    private fun encodeMappings(list: List<GestureMapping>): String {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put("kind", m.kind.name)
                    .put("action", m.action.name)
                    .put("payload", m.payload),
            )
        }
        return arr.toString()
    }

    private fun decodeMappings(raw: String?): List<GestureMapping> {
        if (raw.isNullOrBlank()) return GestureMapping.defaults()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                GestureMapping(
                    kind = WatchEventKind.valueOf(o.getString("kind")),
                    action = MappedAction.valueOf(o.getString("action")),
                    payload = o.optString("payload"),
                )
            }
        }.getOrElse { GestureMapping.defaults() }
    }

    private companion object {
        val BG_SYNC = booleanPreferencesKey("bg_sync")
        val BG_SYNC_AT = longPreferencesKey("bg_sync_at")
        val BG_SYNC_OK = intPreferencesKey("bg_sync_ok")
        val BG_SYNC_MSG = stringPreferencesKey("bg_sync_msg")
        val WEEKLY = booleanPreferencesKey("weekly_report")
        val WEEKLY_AT = longPreferencesKey("weekly_report_at")
        val AGE = intPreferencesKey("age_years")
        val DESKTOP_HOST = stringPreferencesKey("desktop_host")
        val DESKTOP_PORT = intPreferencesKey("desktop_port")
        val DESKTOP_TOKEN = stringPreferencesKey("desktop_token")
        val HEIGHT = intPreferencesKey("height_cm")
        val WEIGHT = intPreferencesKey("weight_kg")
        val TIMEOUT = intPreferencesKey("screen_timeout")
        val GOAL = intPreferencesKey("steps_goal")
        val MALE = booleanPreferencesKey("user_male")
        val CELSIUS = booleanPreferencesKey("celsius")
        val MAPPINGS = stringPreferencesKey("gesture_mappings")
    }
}
