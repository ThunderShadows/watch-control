package com.watchcoach.app.ui.ble

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.ble.BleConnectionState
import com.watchcoach.app.ble.BleScannerController
import com.watchcoach.app.ble.GattInspection
import com.watchcoach.app.ble.formatGattDump
import kotlinx.coroutines.launch
import java.util.UUID

class BleScannerViewModel(application: Application) : AndroidViewModel(application) {
    val controller = BleScannerController(application)
    private val app = application as WatchCoachApplication

    val devices = controller.devices
    val scanning = controller.scanning
    val connectionState = controller.connectionState
    val inspection = controller.inspection
    val error = controller.error

    fun hasPermissions() = controller.hasScanPermissions()
    fun isBluetoothEnabled() = controller.isBluetoothEnabled()

    fun startScan() = controller.startScan()
    fun stopScan() = controller.stopScan()

    fun connect(address: String, name: String?) {
        controller.connect(address)
        viewModelScope.launch {
            app.deviceSettings.save(address, name)
        }
    }

    fun disconnect() = controller.disconnect()
    fun read(service: UUID, characteristic: UUID) =
        controller.readCharacteristic(service, characteristic)

    fun copyAndLogUuids(): String? {
        val inspection = controller.inspection.value ?: return null
        val text = formatGattDump(inspection, controller.connectionState.value)
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Watch Coach GATT UUIDs", text))
        app.packetLogger.info("GATT UUID dump copied:\n$text")
        return text
    }

    fun logInspectionIfNew(inspection: GattInspection, state: BleConnectionState) {
        if (inspection.services.isEmpty()) return
        // Avoid spamming: only log a dump when service count changes via explicit copy,
        // but record a one-line summary on discovery.
        app.packetLogger.info(
            "GATT discovered on ${inspection.address}: ${inspection.services.size} services, " +
                "GloryFit=${inspection.gloryFitUuidsPresent}, state=${state.name}",
        )
    }

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
