package com.watchcoach.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * BLE scan + read-only GATT inspection. This controller never writes to a
 * characteristic or descriptor (no CCCD subscriptions, no commands).
 */
class BleScannerController(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _connectionState = MutableStateFlow(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _inspection = MutableStateFlow<GattInspection?>(null)
    val inspection: StateFlow<GattInspection?> = _inspection.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var connectedAddress: String? = null

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun hasScanPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) &&
                granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        _error.value = null
        if (!hasScanPermissions()) {
            _error.value = "Bluetooth scan permission is not granted."
            return
        }
        if (!isBluetoothEnabled()) {
            _error.value = "Bluetooth is turned off. Enable it and try again."
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _error.value = "This phone does not expose a BLE scanner."
            return
        }
        if (_scanning.value) return
        _devices.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        _scanning.value = true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_scanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // Ignore: adapter may have been toggled off mid-scan.
        }
        _scanning.value = false
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        _error.value = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !granted(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            _error.value = "Bluetooth connect permission is not granted."
            return
        }
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        }
        if (device == null) {
            _error.value = "Could not resolve device $address."
            return
        }
        stopScan()
        closeGatt()
        connectedAddress = address
        _connectionState.value = BleConnectionState.Connecting
        _inspection.value = GattInspection(
            address = address,
            services = emptyList(),
            gloryFitUuidsPresent = false,
            connected = false,
            statusMessage = "Connecting…",
        )
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        closeGatt()
        _connectionState.value = BleConnectionState.Disconnected
        _inspection.update { current ->
            current?.copy(connected = false, statusMessage = "Disconnected.")
        }
    }

    /**
     * Read a single characteristic value. Never writes, never enables notify.
     */
    @SuppressLint("MissingPermission")
    fun readCharacteristic(serviceUuid: UUID, characteristicUuid: UUID) {
        val gatt = gatt ?: return
        val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            _error.value = "Characteristic $characteristicUuid was not found."
            return
        }
        val readable = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        if (!readable) {
            _error.value = "Characteristic does not support READ."
            return
        }
        if (!gatt.readCharacteristic(characteristic)) {
            _error.value = "Read request was rejected by the Android Bluetooth stack."
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        closeGatt()
        _connectionState.value = BleConnectionState.Idle
        _inspection.value = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        connectedAddress = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address ?: return
            val name = result.scanRecord?.deviceName ?: deviceNameSafe(device)
            val scanned = ScannedDevice(
                address = address,
                name = name,
                rssi = result.rssi,
                lastSeenEpochMs = System.currentTimeMillis(),
            )
            _devices.update { current ->
                val without = current.filterNot { it.address == address }
                (without + scanned).sortedWith(
                    compareByDescending<ScannedDevice> { it.isLikelyEverest }
                        .thenByDescending { it.rssi },
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _error.value = "Scan failed (error $errorCode)."
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = BleConnectionState.Discovering
                _inspection.update {
                    it?.copy(connected = true, statusMessage = "Connected. Discovering services…")
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = if (status == BluetoothGatt.GATT_SUCCESS) {
                    BleConnectionState.Disconnected
                } else {
                    BleConnectionState.Failed
                }
                _inspection.update {
                    it?.copy(
                        connected = false,
                        statusMessage = "Disconnected (status $status).",
                    )
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Failed
                _inspection.update {
                    it?.copy(statusMessage = "Service discovery failed (status $status).")
                }
                return
            }
            val services = gatt.services.orEmpty().map { it.toInfo() }
            val glory = services.any { svc ->
                GloryFitUuids.isKnown(svc.uuid) || svc.characteristics.any { it.isGloryFit }
            }
            val message = if (glory) {
                "GloryFit / UTE signature UUIDs are present (0x55FF / 0x33F1 and/or 0x56FF / 0x34F1)."
            } else {
                "No known GloryFit / UTE UUIDs (0x55FF, 0x33F1, 0x56FF, 0x34F1) were found."
            }
            _connectionState.value = BleConnectionState.Connected
            _inspection.value = GattInspection(
                address = gatt.device.address,
                services = services,
                gloryFitUuidsPresent = glory,
                connected = true,
                statusMessage = message,
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleRead(characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(characteristic, value, status)
        }

        private fun handleRead(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray?,
            status: Int,
        ) {
            val hex = if (status == BluetoothGatt.GATT_SUCCESS) {
                value?.joinToString(" ") { b -> "%02X".format(b) } ?: "(empty)"
            } else {
                null
            }
            val error = if (status == BluetoothGatt.GATT_SUCCESS) null else "Read failed (status $status)"
            _inspection.update { current ->
                current?.copy(
                    services = current.services.map { svc ->
                        svc.copy(
                            characteristics = svc.characteristics.map { ch ->
                                if (ch.uuid == characteristic.uuid) {
                                    ch.copy(lastReadHex = hex ?: ch.lastReadHex, readError = error)
                                } else {
                                    ch
                                }
                            },
                        )
                    },
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceNameSafe(device: BluetoothDevice): String? {
        return try {
            device.name
        } catch (_: SecurityException) {
            null
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
