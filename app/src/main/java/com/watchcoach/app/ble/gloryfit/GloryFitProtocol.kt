/*
 * Protocol constants and packet builders for GloryFit / UTE-family watches.
 *
 * Command opcodes, characteristic UUIDs, and the classic fetch/record layouts
 * are adapted from Gadgetbridge's GloryFit support (José Rebelo / Freeyourgadget
 * contributors), GNU Affero General Public License v3.0:
 *   https://codeberg.org/Freeyourgadget/Gadgetbridge
 *   service/devices/gloryfit/GloryFitSupport.kt
 *
 * The UTE dialect (write some fetch commands to 0x34F1; EB steps, EC sleep)
 * is described in Gadgetbridge issue #5505.
 */
package com.watchcoach.app.ble.gloryfit

import com.watchcoach.app.ble.GloryFitUuids
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.UUID

object GloryFitProtocol {
    val SERVICE_CMD: UUID = GloryFitUuids.SERVICE_55FF
    val CHAR_CMD_WRITE: UUID = GloryFitUuids.CHAR_33F1
    val CHAR_CMD_NOTIFY: UUID = uuid16(0x33F2)

    val SERVICE_DATA: UUID = GloryFitUuids.SERVICE_56FF
    val CHAR_DATA_WRITE: UUID = GloryFitUuids.CHAR_34F1
    val CHAR_DATA_NOTIFY: UUID = uuid16(0x34F2)

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_VERSION: Byte = 0xA1.toByte()
    const val CMD_BATTERY: Byte = 0xA2.toByte()
    const val CMD_DATE_TIME: Byte = 0xA3.toByte()
    const val CMD_STEPS: Byte = 0xB2.toByte()          // classic fetch
    const val CMD_REALTIME_STEPS: Byte = 0xB1.toByte()
    const val CMD_HEART_RATE: Byte = 0xF7.toByte()
    const val CMD_SLEEP_INFO: Byte = 0x31
    const val CMD_SLEEP_STAGES: Byte = 0x32
    const val CMD_SPO2: Byte = 0x34
    const val CMD_LIVE_HR: Byte = 0xE5.toByte()
    const val CMD_HR_MODE: Byte = 0xD6.toByte()
    const val CMD_UTE_STEPS: Byte = 0xEB.toByte()      // UTE dialect (#5505)
    const val CMD_UTE_SLEEP: Byte = 0xEC.toByte()      // UTE dialect (#5505)

    // Watch-originated events, from Gadgetbridge GloryFitSupport (documented).
    const val CMD_CAMERA: Byte = 0xC4.toByte()
    const val CAMERA_OPEN: Byte = 0x01
    const val CAMERA_TRIGGER: Byte = 0x02
    const val CAMERA_CLOSE: Byte = 0x03
    const val CMD_ACTION: Byte = 0xD1.toByte()
    const val ACTION_CALL_HANGUP: Byte = 0x02
    const val ACTION_MUSIC_PLAY: Byte = 0x07
    const val ACTION_MUSIC_NEXT: Byte = 0x08
    const val ACTION_MUSIC_PREV: Byte = 0x09
    const val ACTION_FIND_PHONE: Byte = 0x0A
    const val ACTION_MUSIC_VOL_UP: Byte = 0x0D
    const val ACTION_MUSIC_VOL_DOWN: Byte = 0x0E
    const val ACTION_CAMERA_CLOSE: Byte = 0x0F
    // Raise-to-wake is byte 10 of the 19-byte A9 user-info packet (not a dedicated opcode).
    const val CMD_USER_INFO: Byte = 0xA9.toByte()

    const val FETCH_START: Byte = 0xFA.toByte()
    const val FETCH_END: Byte = 0xFD.toByte()
    const val SLEEP_INFO_DATE: Byte = 0x01
    const val SLEEP_INFO_END: Byte = 0x02

    const val HR_MODE_DYNAMIC: Byte = 0x02
    const val LIVE_HR_START: Byte = 0x11
    const val LIVE_HR_STOP: Byte = 0x00

    fun uuid16(id: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(id))

    fun shortName(uuid: UUID): String {
        val s = uuid.toString().lowercase()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            s.substring(4, 8).uppercase()
        } else {
            uuid.toString()
        }
    }

    fun version(): ByteArray = byteArrayOf(CMD_VERSION)

    fun battery(): ByteArray = byteArrayOf(CMD_BATTERY)

    fun setTime(now: LocalDateTime = LocalDateTime.now()): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.put(CMD_DATE_TIME)
        buf.putShort(now.year.toShort())
        buf.put(now.monthValue.toByte())
        buf.put(now.dayOfMonth.toByte())
        buf.put(now.hour.toByte())
        buf.put(now.minute.toByte())
        buf.put(now.second.toByte())
        return buf.array()
    }

    fun fetchStepsClassic(): ByteArray = byteArrayOf(CMD_STEPS, FETCH_START)
    fun fetchHeartRate(): ByteArray = byteArrayOf(CMD_HEART_RATE, FETCH_START)
    fun fetchSleepClassic(): ByteArray = byteArrayOf(CMD_SLEEP_INFO, SLEEP_INFO_DATE)
    /** UTE variant from issue #5505: written to 0x34F1 as three bytes. */
    fun fetchStepsUte(): ByteArray = byteArrayOf(CMD_UTE_STEPS, 0x02, FETCH_START)
    /** Speculative UTE sleep fetch (opcode documented in #5505, exact sub-bytes not confirmed). */
    fun fetchSleepUte(): ByteArray = byteArrayOf(CMD_UTE_SLEEP, FETCH_START)

    fun hrModeDynamic(): ByteArray = byteArrayOf(CMD_HR_MODE, HR_MODE_DYNAMIC)
    fun liveHrStart(): ByteArray = byteArrayOf(CMD_LIVE_HR, LIVE_HR_START)
    fun liveHrStop(): ByteArray = byteArrayOf(CMD_LIVE_HR, LIVE_HR_STOP)

    /**
     * Gadgetbridge GloryFitSupport.setUserInfo — 19-byte A9 packet.
     * Byte 10 is raise-hand-to-display (1 = on, 0 = off). The same write also
     * sets height, weight, screen timeout, steps goal, HR alerts, age, gender
     * and temperature unit. There is no stand-alone raise-to-wake command.
     */
    fun userInfo(
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
    ): ByteArray {
        val buf = ByteBuffer.allocate(19).order(ByteOrder.BIG_ENDIAN)
        buf.put(CMD_USER_INFO)
        buf.putShort(heightCm.coerceIn(91, 241).toShort())
        buf.putShort(weightKg.coerceIn(20, 255).toShort())
        buf.put(screenTimeoutSec.coerceIn(5, 15).toByte())
        buf.put(0x00)
        buf.put(0x00)
        buf.putShort(stepsGoal.coerceIn(1000, 30_000).toShort())
        buf.put(if (raiseHandToWake) 0x01 else 0x00)
        buf.put((hrAlertHigh?.coerceIn(100, 200) ?: 0xFF).toByte())
        buf.put(0x00)
        buf.put(age.coerceIn(3, 100).toByte())
        buf.put(if (male) 0x01 else 0x02)
        buf.put(0x00)
        buf.put(if (celsius) 0x02 else 0x01)
        buf.put(0x01)
        buf.put((hrAlertLow?.coerceIn(40, 100) ?: 0x00).toByte())
        return buf.array()
    }

    fun isFetchEnd(value: ByteArray): Boolean =
        value.size >= 2 && value[1] == FETCH_END && (value.size == 2 || value.size == 3)

    fun isSleepEnd(value: ByteArray): Boolean =
        value.size >= 2 && value[0] == CMD_SLEEP_INFO && value[1] == SLEEP_INFO_END
}
