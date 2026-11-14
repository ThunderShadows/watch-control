package com.watchcoach.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.deviceStore by preferencesDataStore(name = "device_settings")

data class PairedDevice(
    val address: String,
    val name: String?,
)

class DeviceSettingsRepository(private val context: Context) {

    val device: Flow<PairedDevice?> = context.deviceStore.data.map { prefs ->
        val address = prefs[ADDRESS] ?: return@map DEFAULT
        PairedDevice(address, prefs[NAME])
    }

    suspend fun current(): PairedDevice = device.first() ?: DEFAULT

    suspend fun save(address: String, name: String?) {
        context.deviceStore.edit {
            it[ADDRESS] = address
            if (name != null) it[NAME] = name else it.remove(NAME)
        }
    }

    companion object {
        const val DEFAULT_ADDRESS = "78:02:B7:55:68:40"
        val DEFAULT = PairedDevice(DEFAULT_ADDRESS, "CB SmartFit Everest 2.0")
        private val ADDRESS = stringPreferencesKey("watch_address")
        private val NAME = stringPreferencesKey("watch_name")
    }
}
