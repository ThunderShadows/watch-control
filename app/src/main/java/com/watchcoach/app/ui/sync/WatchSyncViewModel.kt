package com.watchcoach.app.ui.sync

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.ble.gloryfit.ClientState
import com.watchcoach.app.ble.gloryfit.HeartRateSample
import com.watchcoach.app.ble.gloryfit.SyncSnapshot
import com.watchcoach.app.data.local.SyncMetaEntity
import com.watchcoach.app.data.settings.DeviceSettingsRepository
import com.watchcoach.app.data.settings.PairedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SyncUiState(
    val message: String? = null,
    val error: String? = null,
    val snapshot: SyncSnapshot? = null,
    val hcWriteResult: String? = null,
    val liveHr: HeartRateSample? = null,
    val liveRunning: Boolean = false,
)

class WatchSyncViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchCoachApplication
    private val client get() = app.gloryFitClient

    val device: StateFlow<PairedDevice?> = app.deviceSettings.device.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DeviceSettingsRepository.DEFAULT,
    )
    val clientState: StateFlow<ClientState> = client.state
    val meta: StateFlow<SyncMetaEntity?> = app.watchData.meta.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )
    val liveHr: StateFlow<HeartRateSample?> = client.liveHr

    private val _ui = MutableStateFlow(SyncUiState())
    val ui: StateFlow<SyncUiState> = _ui.asStateFlow()

    fun hasBlePermissions(): Boolean {
        val ctx = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun syncFromWatch() {
        viewModelScope.launch {
            val paired = app.deviceSettings.current()
            _ui.value = _ui.value.copy(message = "Connecting to ${paired.address}…", error = null)
            try {
                if (client.state.value != ClientState.Ready && client.state.value != ClientState.Syncing) {
                    val connect = client.connect(paired.address, paired.name)
                    if (connect.isFailure) {
                        _ui.value = SyncUiState(
                            error = connect.exceptionOrNull()?.message
                                ?: "Could not connect. Force-stop CB SmartFit and try again.",
                        )
                        return@launch
                    }
                }
                _ui.value = _ui.value.copy(message = "Querying the watch…")
                val snapshot = client.sync()
                app.watchData.persist(snapshot)
                app.deviceSettings.save(snapshot.identity.address, snapshot.identity.name)
                var hcMsg: String? = null
                if (app.healthRepository.hasWritePermissions()) {
                    hcMsg = app.healthRepository.writeFromWatch(app.watchData.exportForHealthConnect())
                }
                _ui.value = SyncUiState(
                    message = "Sync finished.",
                    snapshot = snapshot,
                    hcWriteResult = hcMsg,
                    liveHr = snapshot.heartRates.lastOrNull { it.live } ?: client.liveHr.value,
                )
            } catch (t: Throwable) {
                _ui.value = SyncUiState(error = t.message ?: "Sync failed")
                app.packetLogger.warn("Sync failed: ${t.message}")
            }
        }
    }

    fun readLiveHr() {
        viewModelScope.launch {
            val paired = app.deviceSettings.current()
            _ui.value = _ui.value.copy(liveRunning = true, message = "Starting live HR (≈20 s)…", error = null)
            try {
                if (client.state.value != ClientState.Ready) {
                    val connect = client.connect(paired.address, paired.name)
                    if (connect.isFailure) {
                        _ui.value = SyncUiState(
                            error = connect.exceptionOrNull()?.message ?: "Connect failed",
                        )
                        return@launch
                    }
                }
                val samples = client.readLiveHeartRate(20_000)
                val last = samples.lastOrNull()
                _ui.value = _ui.value.copy(
                    liveRunning = false,
                    liveHr = last,
                    message = if (samples.isEmpty()) {
                        "No live HR samples. The watch may need to be on the wrist, or this model may not stream E5 packets."
                    } else {
                        "Live HR: ${samples.size} sample(s), last ${last?.bpm} bpm."
                    },
                )
                if (samples.isNotEmpty()) {
                    // Persist just the live samples via a tiny snapshot merge
                    val ident = client.identity.value
                    if (ident != null) {
                        app.watchData.persist(
                            com.watchcoach.app.ble.gloryfit.SyncSnapshot(
                                identity = ident,
                                hourlySteps = emptyList(),
                                heartRates = samples,
                                sleepStages = emptyList(),
                                notes = emptyList(),
                            ),
                        )
                    }
                }
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(liveRunning = false, error = t.message)
            }
        }
    }

    fun disconnect() = client.disconnect()
}
