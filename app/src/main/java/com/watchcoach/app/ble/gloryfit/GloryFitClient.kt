/*
 * Coroutine-based GATT client for GloryFit / UTE watches.
 * Handshake and fetch command sequence follow Gadgetbridge GloryFitSupport
 * (AGPL-3.0) plus the UTE write-to-0x34F1 variant from issue #5505.
 *
 * Writes are limited to: CCCD enables, MTU request, time-sync (A3), and
 * read/query commands (A1, A2, B2 FA, F7 FA, 31 01, UTE EB 02 FA / EC FA
 * fallbacks, and optional live HR D6 02 + E5 11 / E5 00). No firmware,
 * factory reset, settings, or watch-face commands.
 */
package com.watchcoach.app.ble.gloryfit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.watchcoach.app.ble.PacketLogger
import com.watchcoach.app.ble.isAllZero
import com.watchcoach.app.ble.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class ClientState {
    Idle, Connecting, Discovering, Ready, Syncing, Disconnecting, Failed
}

class GloryFitClient(
    private val context: Context,
    private val log: PacketLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val queueMutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var connectAck: CompletableDeferred<Boolean>? = null
    private var discoverAck: CompletableDeferred<Boolean>? = null
    private var mtuAck: CompletableDeferred<Int>? = null
    private var writeAck: CompletableDeferred<Boolean>? = null
    private var descAck: CompletableDeferred<Boolean>? = null

    private val notifyListeners = mutableListOf<(UUID, ByteArray) -> Unit>()
    private var sleepMorning: java.time.LocalDate? = null

    private val _state = MutableStateFlow(ClientState.Idle)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private val _identity = MutableStateFlow<WatchIdentity?>(null)
    val identity: StateFlow<WatchIdentity?> = _identity.asStateFlow()

    private val _liveHr = MutableStateFlow<HeartRateSample?>(null)
    val liveHr: StateFlow<HeartRateSample?> = _liveHr.asStateFlow()

    private val _watchEvents = MutableSharedFlow<WatchEvent>(extraBufferCapacity = 64)
    val watchEvents: SharedFlow<WatchEvent> = _watchEvents.asSharedFlow()

    /**
     * When true, [disconnect] leaves the GATT link up so Gesture lab or the
     * event-listener service can keep receiving watch-originated packets.
     */
    @Volatile
    var retainConnection: Boolean = false

    /** 34F2 notify is off by default: enabling it floods all-zero frames on this watch. */
    private var dataNotifyEnabled = false

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, nameHint: String? = null): Result<Unit> =
        withContext(Dispatchers.Main) {
            closeInternal()
            _state.value = ClientState.Connecting
            log.info("Connecting to $address (TRANSPORT_LE)")
            val device = try {
                adapter?.getRemoteDevice(address)
            } catch (t: Throwable) {
                _state.value = ClientState.Failed
                return@withContext Result.failure(t)
            }
            if (device == null) {
                _state.value = ClientState.Failed
                return@withContext Result.failure(IllegalStateException("Bluetooth adapter missing or bad address"))
            }
            _identity.value = WatchIdentity(address, nameHint ?: deviceName(device))
            connectAck = CompletableDeferred()
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
            val ok = withTimeoutOrNull(20_000) { connectAck?.await() } == true
            if (!ok) {
                closeInternal()
                _state.value = ClientState.Failed
                log.warn("Connect timed out or failed")
                return@withContext Result.failure(IllegalStateException("Connect failed or timed out"))
            }
            _state.value = ClientState.Discovering
            discoverAck = CompletableDeferred()
            gatt?.discoverServices()
            val discovered = withTimeoutOrNull(20_000) { discoverAck?.await() } == true
            if (!discovered) {
                closeInternal()
                _state.value = ClientState.Failed
                log.warn("Service discovery failed")
                return@withContext Result.failure(IllegalStateException("GATT discovery failed"))
            }
            requestMtu(247)
            enableGloryFitNotifications()
            _state.value = ClientState.Ready
            log.info("Ready. Services=${gatt?.services?.size}")
            Result.success(Unit)
        }

    /**
     * User-triggered sync only. Never call this automatically.
     *
     * Unsolicited E5 11 live-HR frames (seen ~1/s on the Everest 2.0 during
     * sync even when we did not request a measurement) are treated as live
     * samples and shown on the Sync screen. Gadgetbridge's
     * onEnableRealtimeHeartRateMeasurement is still a TODO and does not send
     * E5 00, so we do *not* send a stop at the end of a normal sync — only
     * after a user-started live-HR session that we ourselves opened with E5 11.
     */
    suspend fun sync(): SyncSnapshot {
        check(_state.value == ClientState.Ready || _state.value == ClientState.Syncing) {
            "Not connected"
        }
        _state.value = ClientState.Syncing
        val startedAt = System.currentTimeMillis()
        log.info("Starting user-triggered sync (query + time-sync only)")
        val identity0 = _identity.value ?: WatchIdentity("unknown", null)
        var firmware = identity0.firmware
        var battery = identity0.batteryPercent
        var charging = identity0.charging
        val steps = mutableListOf<HourlySteps>()
        val hours = mutableListOf<HeartRateSample>()
        val sleep = mutableListOf<SleepStage>()
        val notes = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var dialect = ProtocolDialect.Unknown
        sleepMorning = null

        val collector: (UUID, ByteArray) -> Unit = { uuid, value ->
            val parsed = if (value.isNotEmpty() && value[0] == GloryFitProtocol.CMD_SLEEP_STAGES) {
                GloryFitParser.parseSleepStages(value, sleepMorning)
            } else {
                GloryFitParser.parse(value)
            }
            val note = describe(parsed)
            log.rx(GloryFitProtocol.shortName(uuid), value, note)
            when (parsed) {
                is ParsedPacket.Version -> firmware = parsed.firmware.ifBlank { firmware }
                is ParsedPacket.Battery -> {
                    battery = parsed.percent
                    charging = parsed.charging
                }
                is ParsedPacket.Steps -> if (!parsed.endOfFetch && parsed.record.totalSteps >= 0 && !isSentinel(parsed)) {
                    steps += parsed.record
                    if (parsed.record.dialect == ProtocolDialect.UteVariant) {
                        dialect = ProtocolDialect.UteVariant
                    } else if (dialect == ProtocolDialect.Unknown) {
                        dialect = ProtocolDialect.ClassicGloryFit
                    }
                }
                is ParsedPacket.HeartRate -> hours += parsed.samples
                is ParsedPacket.LiveHeartRate -> {
                    hours += parsed.sample
                    _liveHr.value = parsed.sample
                }
                is ParsedPacket.SleepInfoDate -> sleepMorning = parsed.morning
                is ParsedPacket.SleepStages -> sleep += parsed.stages
                else -> Unit
            }
        }

        notifyListeners += collector
        try {
            val cmdWrite = pickWriteChar(preferCmd = true)
            val dataWrite = pickWriteChar(preferCmd = false)

            // Register the wait *before* the write so a fast notify cannot sneak
            // in during the WRITE ack (v0.2 lost the A1/A2/A3 replies this way
            // and stalled 3 s on each handshake command).
            writeAndAwait(cmdWrite, GloryFitProtocol.version(), "A1 version", GloryFitProtocol.CMD_VERSION, 3_000, errors)
            writeAndAwait(cmdWrite, GloryFitProtocol.battery(), "A2 battery", GloryFitProtocol.CMD_BATTERY, 3_000, errors)
            writeAndAwait(cmdWrite, GloryFitProtocol.setTime(), "A3 set time", GloryFitProtocol.CMD_DATE_TIME, 3_000, errors)

            if (firmware != null || battery != null) {
                if (dialect == ProtocolDialect.Unknown) dialect = ProtocolDialect.ClassicGloryFit
            }

            val beforeSteps = steps.size
            writeAndAwaitFetch(
                cmdWrite,
                GloryFitProtocol.fetchStepsClassic(),
                "B2 FA fetch steps (classic)",
                setOf(GloryFitProtocol.CMD_STEPS, GloryFitProtocol.CMD_REALTIME_STEPS),
                10_000,
                errors,
            )
            if (steps.size == beforeSteps) {
                notes += "No classic B2 step records; trying UTE EB 02 FA"
                log.info(notes.last())
                setDataNotify(true)
                val target = if (dataWrite != null && dataWrite.uuid != cmdWrite?.uuid) dataWrite else cmdWrite
                writeAndAwaitFetch(
                    target,
                    GloryFitProtocol.fetchStepsUte(),
                    "EB 02 FA fetch steps (UTE, #5505)",
                    setOf(GloryFitProtocol.CMD_UTE_STEPS),
                    10_000,
                    errors,
                )
            }

            val historyBefore = hours.count { !it.live }
            writeAndAwaitFetch(
                cmdWrite,
                GloryFitProtocol.fetchHeartRate(),
                "F7 FA fetch HR history",
                setOf(GloryFitProtocol.CMD_HEART_RATE),
                10_000,
                errors,
            )
            if (hours.count { !it.live } == historyBefore && dataWrite != null && dataWrite.uuid != cmdWrite?.uuid) {
                notes += "No F7 records on 33F1; retrying F7 FA on 34F1"
                setDataNotify(true)
                writeAndAwaitFetch(
                    dataWrite,
                    GloryFitProtocol.fetchHeartRate(),
                    "F7 FA fetch HR (UTE channel)",
                    setOf(GloryFitProtocol.CMD_HEART_RATE),
                    10_000,
                    errors,
                )
            }

            // Sleep stages arrive on 34F2 as 0x32, so notify is enabled only
            // for this window (and any UTE fallback above).
            setDataNotify(true)
            val beforeSleep = sleep.size
            writeAndAwaitSleep(cmdWrite, GloryFitProtocol.fetchSleepClassic(), "31 01 fetch sleep (classic)", 10_000, errors)
            if (sleep.size == beforeSleep && sleepMorning == null) {
                notes += "No classic sleep; trying speculative UTE EC FA"
                val target = dataWrite ?: cmdWrite
                writeAndAwaitSleep(target, GloryFitProtocol.fetchSleepUte(), "EC FA fetch sleep (UTE, speculative)", 8_000, errors)
            }
            if (sleep.isEmpty() && sleepMorning == null) {
                notes += "Sleep: none / unsupported (no 31/32 or EC records)."
            }
        } finally {
            notifyListeners.remove(collector)
            try {
                setDataNotify(false)
            } catch (t: Throwable) {
                log.warn("Failed to disable 34F2 notify: ${t.message}")
            }
        }

        val ident = identity0.copy(
            firmware = firmware,
            batteryPercent = battery,
            charging = charging,
            dialect = dialect,
        )
        _identity.value = ident
        if (steps.any { it.speculative }) {
            notes += "UTE step field mapping is speculative (issue #5505); check the raw log if totals look wrong."
        }
        val duration = System.currentTimeMillis() - startedAt
        val summary = SyncSnapshot(ident, steps, hours, sleep, notes).toSummary(duration, errors)
        val snapshot = SyncSnapshot(ident, steps, hours, sleep, notes, summary)
        log.flush()
        log.info(summary.asText().replace("\n", " | "))
        log.lastSummary = summary.asText()
        log.flush()
        _state.value = ClientState.Ready
        return snapshot
    }

    /**
     * Optional, user-triggered live HR. Sends D6 02, waits 1.5s, E5 11,
     * collects for [durationMs], then E5 00. Not part of automatic sync.
     */
    suspend fun readLiveHeartRate(durationMs: Long = 20_000): List<HeartRateSample> {
        check(_state.value == ClientState.Ready) { "Not connected" }
        _state.value = ClientState.Syncing
        val cmdWrite = pickWriteChar(preferCmd = true)
        val collected = mutableListOf<HeartRateSample>()
        val listener: (UUID, ByteArray) -> Unit = { uuid, value ->
            when (val parsed = GloryFitParser.parse(value)) {
                is ParsedPacket.LiveHeartRate -> {
                    log.rx(GloryFitProtocol.shortName(uuid), value, "live HR ${parsed.sample.bpm}")
                    collected += parsed.sample
                    _liveHr.value = parsed.sample
                }
                else -> log.rx(GloryFitProtocol.shortName(uuid), value, describe(parsed))
            }
        }
        notifyListeners += listener
        try {
            writeCommand(cmdWrite, GloryFitProtocol.hrModeDynamic(), "D6 02 HR mode dynamic")
            delay(1_500)
            writeCommand(cmdWrite, GloryFitProtocol.liveHrStart(), "E5 11 start live HR")
            delay(durationMs)
            writeCommand(cmdWrite, GloryFitProtocol.liveHrStop(), "E5 00 stop live HR")
            delay(500)
        } finally {
            notifyListeners.remove(listener)
            _state.value = ClientState.Ready
        }
        return collected
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (retainConnection && (_state.value == ClientState.Ready || _state.value == ClientState.Syncing)) {
            log.info("Disconnect skipped — connection retained for event listening")
            return
        }
        scope.launch {
            _state.value = ClientState.Disconnecting
            closeInternal()
            _state.value = ClientState.Idle
            log.info("Disconnected")
        }
    }

    fun disconnectNow() {
        retainConnection = false
        disconnect()
    }

    /**
     * Writes the documented 19-byte A9 user-info packet, including raise-to-wake
     * at byte 10. Call only after an explicit in-app confirmation — this also
     * overwrites height, weight, timeout, step goal, HR alerts, age, gender and units.
     */
    suspend fun writeUserInfo(
        heightCm: Int,
        weightKg: Int,
        screenTimeoutSec: Int,
        stepsGoal: Int,
        raiseHandToWake: Boolean,
        hrAlertHigh: Int?,
        hrAlertLow: Int?,
        age: Int,
        male: Boolean,
        celsius: Boolean,
    ) {
        check(_state.value == ClientState.Ready || _state.value == ClientState.Syncing) { "Not connected" }
        val payload = GloryFitProtocol.userInfo(
            heightCm, weightKg, screenTimeoutSec, stepsGoal,
            raiseHandToWake, hrAlertHigh, hrAlertLow, age, male, celsius,
        )
        val note = "A9 user-info (raise-to-wake=${if (raiseHandToWake) "on" else "off"}; " +
            "also writes height/weight/timeout/goal/HR alerts/age/gender/units)"
        writeAndAwait(
            pickWriteChar(preferCmd = true),
            payload,
            note,
            GloryFitProtocol.CMD_USER_INFO,
            3_000,
            mutableListOf(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun closeInternal() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        dataNotifyEnabled = false
        connectAck?.cancel()
        discoverAck?.cancel()
        writeAck?.cancel()
        descAck?.cancel()
        mtuAck?.cancel()
        connectAck = null
        discoverAck = null
    }

    private fun pickWriteChar(preferCmd: Boolean): BluetoothGattCharacteristic? {
        val gatt = gatt ?: return null
        val cmd = gatt.getService(GloryFitProtocol.SERVICE_CMD)
            ?.getCharacteristic(GloryFitProtocol.CHAR_CMD_WRITE)
        val data = gatt.getService(GloryFitProtocol.SERVICE_DATA)
            ?.getCharacteristic(GloryFitProtocol.CHAR_DATA_WRITE)
        return if (preferCmd) cmd ?: data else data ?: cmd
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableGloryFitNotifications() {
        // Only 33F2 at connect. Enabling 34F2 on the Everest 2.0 floods
        // 84–96 byte all-zero notifications ~every 400 ms and bloated the
        // v0.2 log / UI thread. 34F2 is turned on later only for sleep stages
        // (opcode 0x32) or a UTE fallback.
        val gatt = gatt ?: return
        val ch = gatt.getService(GloryFitProtocol.SERVICE_CMD)
            ?.getCharacteristic(GloryFitProtocol.CHAR_CMD_NOTIFY)
        if (ch != null) {
            val props = ch.properties
            val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            if (canNotify) {
                val indicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
                    (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
                enableCccd(ch, indicate)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun setDataNotify(enabled: Boolean) {
        if (dataNotifyEnabled == enabled) return
        val ch = gatt?.getService(GloryFitProtocol.SERVICE_DATA)
            ?.getCharacteristic(GloryFitProtocol.CHAR_DATA_NOTIFY) ?: return
        val props = ch.properties
        val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        if (!canNotify) return
        if (enabled) {
            log.info("Enabling notify on 34F2 (sleep stages / UTE data only)")
            enableCccd(ch, indicate = false)
            dataNotifyEnabled = true
        } else {
            log.info("Disabling notify on 34F2")
            disableCccd(ch)
            dataNotifyEnabled = false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableCccd(characteristic: BluetoothGattCharacteristic, indicate: Boolean) {
        val gatt = gatt ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(GloryFitProtocol.CCCD) ?: run {
            log.warn("No CCCD on ${GloryFitProtocol.shortName(characteristic.uuid)}")
            return
        }
        val value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        log.info(
            "Enabling ${if (indicate) "indicate" else "notify"} on ${GloryFitProtocol.shortName(characteristic.uuid)}",
        )
        queueMutex.withLock {
            descAck = CompletableDeferred()
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            if (!started) {
                log.warn("CCCD write not started for ${characteristic.uuid}")
                return@withLock
            }
            val ok = withTimeoutOrNull(3_000) { descAck?.await() } == true
            if (!ok) log.warn("CCCD write timed out for ${GloryFitProtocol.shortName(characteristic.uuid)}")
        }
        delay(80)
    }

    @SuppressLint("MissingPermission")
    private suspend fun disableCccd(characteristic: BluetoothGattCharacteristic) {
        val gatt = gatt ?: return
        gatt.setCharacteristicNotification(characteristic, false)
        val descriptor = characteristic.getDescriptor(GloryFitProtocol.CCCD) ?: return
        val value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        queueMutex.withLock {
            descAck = CompletableDeferred()
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            if (!started) {
                log.warn("CCCD disable not started for ${characteristic.uuid}")
                return@withLock
            }
            val ok = withTimeoutOrNull(3_000) { descAck?.await() } == true
            if (!ok) log.warn("CCCD disable timed out for ${GloryFitProtocol.shortName(characteristic.uuid)}")
        }
        delay(80)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestMtu(mtu: Int) {
        val gatt = gatt ?: return
        queueMutex.withLock {
            mtuAck = CompletableDeferred()
            if (gatt.requestMtu(mtu)) {
                val got = withTimeoutOrNull(3_000) { mtuAck?.await() }
                log.info("MTU negotiated: ${got ?: "timeout"}")
            } else {
                log.warn("requestMtu($mtu) rejected")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeCommand(
        characteristic: BluetoothGattCharacteristic?,
        payload: ByteArray,
        note: String,
    ) {
        val gatt = gatt ?: return
        if (characteristic == null) {
            log.warn("No write characteristic for $note")
            return
        }
        val props = characteristic.properties
        val noRsp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val writeType = if (noRsp) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        log.tx(GloryFitProtocol.shortName(characteristic.uuid), payload, note)
        queueMutex.withLock {
            if (noRsp) {
                writeNoResponse(gatt, characteristic, payload, writeType)
                delay(120)
            } else {
                writeAck = CompletableDeferred()
                val started = writeCompat(gatt, characteristic, payload, writeType)
                if (!started) {
                    log.warn("Write not started: $note")
                    return@withLock
                }
                val ok = withTimeoutOrNull(3_000) { writeAck?.await() } == true
                if (!ok) log.warn("Write ack timeout: $note")
                delay(40)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNoResponse(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int,
    ) {
        writeCompat(gatt, characteristic, payload, writeType)
    }

    @SuppressLint("MissingPermission")
    private fun writeCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, payload, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private suspend fun writeAndAwait(
        characteristic: BluetoothGattCharacteristic?,
        payload: ByteArray,
        note: String,
        expect: Byte,
        timeoutMs: Long,
        errors: MutableList<String>,
    ) {
        val seen = CompletableDeferred<Unit>()
        val listener: (UUID, ByteArray) -> Unit = { _, value ->
            if (value.isNotEmpty() && value[0] == expect && !seen.isCompleted) {
                seen.complete(Unit)
            }
        }
        notifyListeners += listener
        try {
            writeCommand(characteristic, payload, note)
            val ok = withTimeoutOrNull(timeoutMs) { seen.await() } != null
            if (!ok) {
                val msg = "Timeout waiting for 0x%02X after %s".format(expect.toInt() and 0xFF, note)
                log.info(msg)
                errors += msg
            }
        } finally {
            notifyListeners.remove(listener)
        }
    }

    private suspend fun writeAndAwaitFetch(
        characteristic: BluetoothGattCharacteristic?,
        payload: ByteArray,
        note: String,
        opcodes: Set<Byte>,
        timeoutMs: Long,
        errors: MutableList<String>,
    ) {
        val seen = CompletableDeferred<Unit>()
        val listener: (UUID, ByteArray) -> Unit = { _, value ->
            if (value.isNotEmpty() && value[0] in opcodes && GloryFitProtocol.isFetchEnd(value)) {
                if (!seen.isCompleted) seen.complete(Unit)
            }
        }
        notifyListeners += listener
        try {
            writeCommand(characteristic, payload, note)
            val ok = withTimeoutOrNull(timeoutMs) { seen.await() } != null
            if (!ok) {
                val msg = "Fetch wait timed out after ${timeoutMs}ms for $note"
                log.info(msg)
                errors += msg
            }
        } finally {
            notifyListeners.remove(listener)
        }
    }

    private suspend fun writeAndAwaitSleep(
        characteristic: BluetoothGattCharacteristic?,
        payload: ByteArray,
        note: String,
        timeoutMs: Long,
        errors: MutableList<String>,
    ) {
        val seen = CompletableDeferred<Unit>()
        val listener: (UUID, ByteArray) -> Unit = { _, value ->
            if (GloryFitProtocol.isSleepEnd(value) ||
                (value.isNotEmpty() && value[0] == GloryFitProtocol.CMD_UTE_SLEEP && GloryFitProtocol.isFetchEnd(value))
            ) {
                if (!seen.isCompleted) seen.complete(Unit)
            }
        }
        notifyListeners += listener
        try {
            writeCommand(characteristic, payload, note)
            val ok = withTimeoutOrNull(timeoutMs) { seen.await() } != null
            if (!ok) {
                val msg = "Sleep fetch wait timed out after ${timeoutMs}ms ($note)"
                log.info(msg)
                errors += msg
            }
        } finally {
            notifyListeners.remove(listener)
        }
    }

    private suspend fun awaitOpcode(opcode: Byte, timeoutMs: Long) {
        val seen = CompletableDeferred<Unit>()
        val listener: (UUID, ByteArray) -> Unit = { _, value ->
            if (value.isNotEmpty() && value[0] == opcode && !seen.isCompleted) {
                seen.complete(Unit)
            }
        }
        notifyListeners += listener
        try {
            withTimeoutOrNull(timeoutMs) { seen.await() }
        } finally {
            notifyListeners.remove(listener)
        }
    }

    private suspend fun awaitFetchEnd(opcodes: Set<Byte>, timeoutMs: Long) {
        val seen = CompletableDeferred<Unit>()
        val listener: (UUID, ByteArray) -> Unit = { _, value ->
            if (value.isNotEmpty() && value[0] in opcodes && GloryFitProtocol.isFetchEnd(value)) {
                if (!seen.isCompleted) seen.complete(Unit)
            }
        }
        notifyListeners += listener
        try {
            withTimeoutOrNull(timeoutMs) { seen.await() }
                ?: log.info("Fetch wait timed out after ${timeoutMs}ms for $opcodes")
        } finally {
            notifyListeners.remove(listener)
        }
    }

    private suspend fun awaitSleepEnd(timeoutMs: Long) {
        val seen = CompletableDeferred<Unit>()
        val listener: (UUID, ByteArray) -> Unit = { _, value ->
            if (GloryFitProtocol.isSleepEnd(value) ||
                (value.isNotEmpty() && value[0] == GloryFitProtocol.CMD_UTE_SLEEP && GloryFitProtocol.isFetchEnd(value))
            ) {
                if (!seen.isCompleted) seen.complete(Unit)
            }
        }
        notifyListeners += listener
        try {
            withTimeoutOrNull(timeoutMs) { seen.await() }
                ?: log.info("Sleep fetch wait timed out after ${timeoutMs}ms")
        } finally {
            notifyListeners.remove(listener)
        }
    }

    private fun isSentinel(parsed: ParsedPacket.Steps): Boolean = parsed.endOfFetch

    private fun describe(parsed: ParsedPacket): String = when (parsed) {
        is ParsedPacket.Version -> "version ${parsed.firmware}"
        is ParsedPacket.Battery -> "battery ${parsed.percent}%${if (parsed.charging) " charging" else ""}"
        is ParsedPacket.TimeAck -> "time ack"
        is ParsedPacket.Steps -> if (parsed.endOfFetch) "steps fetch end" else
            "steps ${parsed.record.totalSteps} at ${parsed.record.start}" +
                if (parsed.record.speculative) " (speculative UTE)" else ""
        is ParsedPacket.HeartRate -> if (parsed.endOfFetch) "HR fetch end" else "HR ${parsed.samples.size} samples"
        is ParsedPacket.LiveHeartRate -> "live HR ${parsed.sample.bpm}"
        is ParsedPacket.SleepInfoDate -> "sleep date ${parsed.morning}"
        is ParsedPacket.SleepStages -> "sleep ${parsed.stages.size} stages"
        is ParsedPacket.SleepEnd -> "sleep fetch end"
        is ParsedPacket.WatchEventPacket -> parsed.event.kind.label
        is ParsedPacket.Unknown -> "unparsed opcode=0x%02X".format(parsed.opcode)
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String? =
        try { device.name } catch (_: SecurityException) { null }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log.info("Connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectAck?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectAck?.complete(false)
                if (_state.value != ClientState.Disconnecting && _state.value != ClientState.Idle) {
                    _state.value = ClientState.Failed
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log.info("Services discovered status=$status count=${gatt.services?.size}")
            discoverAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log.info("onMtuChanged mtu=$mtu status=$status")
            mtuAck?.complete(mtu)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            descAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleNotify(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotify(characteristic.uuid, value)
        }
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        // All-zero 34F2 frames must not reach parsers, waiters, or (when
        // not verbose) the log file — they arrived ~every 400 ms in v0.2.
        if (value.isAllZero()) {
            log.rx(GloryFitProtocol.shortName(uuid), value, "empty")
            return
        }
        when (val parsed = GloryFitParser.parse(value)) {
            is ParsedPacket.LiveHeartRate -> _liveHr.value = parsed.sample
            is ParsedPacket.WatchEventPacket -> {
                val ev = parsed.event.copy(characteristic = GloryFitProtocol.shortName(uuid))
                _watchEvents.tryEmit(ev)
            }
            is ParsedPacket.Unknown -> {
                _watchEvents.tryEmit(
                    WatchEvent(
                        kind = WatchEventKind.UNKNOWN,
                        rawHex = value.toHex(),
                        documented = false,
                        characteristic = GloryFitProtocol.shortName(uuid),
                    ),
                )
            }
            else -> Unit
        }
        if (notifyListeners.isEmpty()) {
            log.rx(GloryFitProtocol.shortName(uuid), value, "unsolicited")
        }
        notifyListeners.toList().forEach { listener ->
            runCatching { listener(uuid, value) }
        }
    }
}

