package com.watchcoach.app.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeenEpochMs: Long,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Unknown device"

    val isLikelyEverest: Boolean
        get() {
            val n = name.orEmpty()
            return n.contains("Everest", ignoreCase = true) ||
                n.contains("CB", ignoreCase = true)
        }
}

data class GattCharacteristicInfo(
    val uuid: UUID,
    val properties: List<String>,
    val isGloryFit: Boolean,
    val lastReadHex: String? = null,
    val readError: String? = null,
)

data class GattServiceInfo(
    val uuid: UUID,
    val isGloryFit: Boolean,
    val characteristics: List<GattCharacteristicInfo>,
)

data class GattInspection(
    val address: String,
    val services: List<GattServiceInfo>,
    val gloryFitUuidsPresent: Boolean,
    val connected: Boolean,
    val statusMessage: String,
)

enum class BleConnectionState {
    Idle,
    Connecting,
    Discovering,
    Connected,
    Failed,
    Disconnected,
}

fun characteristicProperties(characteristic: BluetoothGattCharacteristic): List<String> {
    val p = characteristic.properties
    val labels = mutableListOf<String>()
    fun has(mask: Int) = p and mask != 0
    if (has(BluetoothGattCharacteristic.PROPERTY_BROADCAST)) labels += "BROADCAST"
    if (has(BluetoothGattCharacteristic.PROPERTY_READ)) labels += "READ"
    if (has(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) labels += "WRITE_NO_RESPONSE"
    if (has(BluetoothGattCharacteristic.PROPERTY_WRITE)) labels += "WRITE"
    if (has(BluetoothGattCharacteristic.PROPERTY_NOTIFY)) labels += "NOTIFY"
    if (has(BluetoothGattCharacteristic.PROPERTY_INDICATE)) labels += "INDICATE"
    if (has(BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE)) labels += "SIGNED_WRITE"
    if (has(BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS)) labels += "EXTENDED"
    return labels
}

fun BluetoothGattService.toInfo(): GattServiceInfo {
    val chars = characteristics.map { ch ->
        GattCharacteristicInfo(
            uuid = ch.uuid,
            properties = characteristicProperties(ch),
            isGloryFit = GloryFitUuids.isKnown(ch.uuid),
        )
    }
    return GattServiceInfo(
        uuid = uuid,
        isGloryFit = GloryFitUuids.isKnown(uuid) || chars.any { it.isGloryFit },
        characteristics = chars,
    )
}


fun formatGattDump(inspection: GattInspection, state: BleConnectionState): String {
    val sb = StringBuilder()
    sb.appendLine("Watch Coach GATT dump")
    sb.appendLine("MAC: ${inspection.address}")
    sb.appendLine("State: ${state.name}")
    sb.appendLine("GloryFit/UTE UUIDs: ${if (inspection.gloryFitUuidsPresent) "yes" else "no"}")
    sb.appendLine(inspection.statusMessage)
    sb.appendLine("Services: ${inspection.services.size}")
    sb.appendLine()
    inspection.services.forEach { service ->
        val sShort = GloryFitUuids.shortLabel(service.uuid)
        sb.appendLine(
            "Service $sShort  ${service.uuid}" + if (service.isGloryFit) "  · GloryFit" else "",
        )
        service.characteristics.forEach { ch ->
            val cShort = GloryFitUuids.shortLabel(ch.uuid)
            val props = ch.properties.joinToString(", ").ifEmpty { "none" }
            sb.appendLine(
                "  $cShort  ${ch.uuid}  [$props]" + if (ch.isGloryFit) "  · GloryFit" else "",
            )
            if (ch.lastReadHex != null) sb.appendLine("    value: ${ch.lastReadHex}")
            if (ch.readError != null) sb.appendLine("    error: ${ch.readError}")
        }
        sb.appendLine()
    }
    return sb.toString()
}
