/*
 * Copyright (C) 2025 José Rebelo and Gadgetbridge contributors
 * Copyright (C) 2026 Watch Coach contributors
 *
 * Record layouts for classic GloryFit step / heart-rate / sleep packets are
 * adapted from Gadgetbridge's GloryFitFetcher.kt, GNU Affero General Public
 * License v3.0. See NOTICE and LICENSE in this project, and
 * https://codeberg.org/Freeyourgadget/Gadgetbridge
 *
 * UTE EB/EC handling is a best-effort interpretation of packets described in
 * Gadgetbridge issue #5505 and is marked speculative where the field map is
 * not confirmed.
 */
package com.watchcoach.app.ble.gloryfit

import com.watchcoach.app.ble.toHex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

sealed class ParsedPacket {
    data class Version(val firmware: String) : ParsedPacket()
    data class Battery(val percent: Int, val charging: Boolean) : ParsedPacket()
    data class TimeAck(val raw: ByteArray) : ParsedPacket()
    data class Steps(val record: HourlySteps, val endOfFetch: Boolean) : ParsedPacket()
    data class HeartRate(val samples: List<HeartRateSample>, val endOfFetch: Boolean) : ParsedPacket()
    data class LiveHeartRate(val sample: HeartRateSample) : ParsedPacket()
    data class SleepInfoDate(val morning: LocalDate, val expectedStages: Int?) : ParsedPacket()
    data class SleepStages(val stages: List<SleepStage>) : ParsedPacket()
    data class SleepEnd(val raw: ByteArray) : ParsedPacket()
    data class WatchEventPacket(val event: WatchEvent) : ParsedPacket()
    data class Unknown(val opcode: Int, val raw: ByteArray) : ParsedPacket()
}

object GloryFitParser {
    private val zone: ZoneId = ZoneId.systemDefault()

    fun parse(value: ByteArray): ParsedPacket {
        if (value.isEmpty()) return ParsedPacket.Unknown(-1, value)
        return when (value[0]) {
            GloryFitProtocol.CMD_VERSION -> parseVersion(value)
            GloryFitProtocol.CMD_BATTERY -> parseBattery(value)
            GloryFitProtocol.CMD_DATE_TIME -> ParsedPacket.TimeAck(value)
            GloryFitProtocol.CMD_STEPS, GloryFitProtocol.CMD_REALTIME_STEPS -> parseClassicSteps(value)
            GloryFitProtocol.CMD_UTE_STEPS -> parseUteSteps(value)
            GloryFitProtocol.CMD_HEART_RATE -> parseHeartRate(value)
            GloryFitProtocol.CMD_LIVE_HR -> parseLiveHr(value)
            GloryFitProtocol.CMD_SLEEP_INFO -> parseSleepInfo(value)
            GloryFitProtocol.CMD_SLEEP_STAGES -> parseSleepStages(value, morning = null)
            GloryFitProtocol.CMD_UTE_SLEEP -> parseUteSleep(value)
            GloryFitProtocol.CMD_CAMERA -> parseCamera(value)
            GloryFitProtocol.CMD_ACTION -> parseAction(value)
            GloryFitProtocol.CMD_USER_INFO -> ParsedPacket.Unknown(0xA9, value)
            else -> ParsedPacket.Unknown(value[0].toInt() and 0xFF, value)
        }
    }

    private fun parseVersion(value: ByteArray): ParsedPacket {
        val fw = if (value.size > 1) {
            String(value, 1, value.size - 1, Charsets.US_ASCII).trim { it < ' ' || it == '\u0000' }
        } else {
            ""
        }
        return ParsedPacket.Version(fw)
    }

    private fun parseBattery(value: ByteArray): ParsedPacket {
        val pct = if (value.size > 1) value[1].toInt() and 0xFF else 0
        val charging = value.size > 2 && value[2].toInt() == 0x01
        return ParsedPacket.Battery(pct, charging)
    }

    /**
     * Classic 18-byte B2/B1 record from Gadgetbridge GloryFitFetcher.handleSteps,
     * confirmed on the Everest 2.0 (2026-11-14 field test), e.g.
     *   B2 07 EA 0B 0B 08 02 55 00 00 00 00 00 0A 23 08 02 55
     *     year=2026 month=11 day=11 hour=8
     *     totalSteps u16 BE at bytes 6–7 = 0x0255 = 597
     *     bytes 8–17 are NOT distance or calories (Gadgetbridge + this capture):
     *       runStart, runEnd, unk, runningSteps u16, walkStart, walkEnd, unk, walkingSteps u16
     *     Here walkingSteps = 0x0255 = 597 and runningSteps = 0, summing to totalSteps.
     */
    private fun parseClassicSteps(value: ByteArray): ParsedPacket {
        if (GloryFitProtocol.isFetchEnd(value)) {
            return ParsedPacket.Steps(
                HourlySteps(
                    start = LocalDateTime.now(),
                    totalSteps = 0,
                    rawHex = value.toHex(),
                ),
                endOfFetch = true,
            )
        }
        if (value.size != 18) {
            return ParsedPacket.Unknown(value[0].toInt() and 0xFF, value)
        }
        val buf = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
        buf.get()
        val year = buf.short.toInt() and 0xFFFF
        val month = buf.get().toInt() and 0xFF
        val day = buf.get().toInt() and 0xFF
        val hour = buf.get().toInt() and 0xFF
        val total = buf.short.toInt() and 0xFFFF
        val runStart = buf.get().toInt() and 0xFF // minute-in-hour window; not used on the dashboard
        val runEnd = buf.get().toInt() and 0xFF
        buf.get()
        val running = buf.short.toInt() and 0xFFFF
        val walkStart = buf.get().toInt() and 0xFF
        val walkEnd = buf.get().toInt() and 0xFF
        buf.get()
        // Touch the window bytes so the layout stays documented if the compiler flags unused locals.
        if (runStart < 0 || runEnd < 0 || walkStart < 0 || walkEnd < 0) return ParsedPacket.Unknown(0xB2, value)
        val walking = buf.short.toInt() and 0xFFFF
        val start = safeDateTime(year, month, day, hour)
            ?: return ParsedPacket.Unknown(value[0].toInt() and 0xFF, value)
        return ParsedPacket.Steps(
            HourlySteps(
                start = start,
                totalSteps = total,
                runningSteps = running,
                walkingSteps = walking,
                dialect = ProtocolDialect.ClassicGloryFit,
                rawHex = value.toHex(),
                speculative = false,
            ),
            endOfFetch = false,
        )
    }

    /**
     * UTE steps, issue #5505 example:
     *   EB 02 07 E9 0A 0C 08 00 A1 00 7A 00 11 DE
     * Interpreted as: EB 02, year, month, day, hour, then 16-bit fields.
     * The first u16 after the hour is treated as step count; remaining u16s are
     * logged but not trusted (distance/calories mapping is unconfirmed).
     */
    private fun parseUteSteps(value: ByteArray): ParsedPacket {
        if (value.size >= 3 && value[1] == GloryFitProtocol.FETCH_END ||
            (value.size >= 3 && value[2] == GloryFitProtocol.FETCH_END)
        ) {
            return ParsedPacket.Steps(
                HourlySteps(LocalDateTime.now(), 0, rawHex = value.toHex(), speculative = true),
                endOfFetch = true,
            )
        }
        if (value.size < 10) return ParsedPacket.Unknown(0xEB, value)
        // Skip opcode + sub (EB 02)
        var offset = 2
        val year = u16(value, offset)
        offset += 2
        val month = value[offset].toInt() and 0xFF
        val day = value[offset + 1].toInt() and 0xFF
        val hour = value[offset + 2].toInt() and 0xFF
        offset += 3
        val first = if (offset + 1 < value.size) u16(value, offset) else 0
        val start = safeDateTime(year, month, day, hour)
            ?: return ParsedPacket.Unknown(0xEB, value)
        return ParsedPacket.Steps(
            HourlySteps(
                start = start,
                totalSteps = first,
                dialect = ProtocolDialect.UteVariant,
                rawHex = value.toHex(),
                speculative = true,
            ),
            endOfFetch = false,
        )
    }

    /**
     * Classic 18-byte F7 history record, same math as Gadgetbridge
     * GloryFitFetcher.handleHeartRate, verified on the Everest 2.0 field test:
     *   F7 07 EA 0B 06 0A FF 47 6A 57 5B 59 58 56 5F 5B 57 4E
     *     year u16 BE = 0x07EA = 2026, month=11, day=6, hour=10
     *     then 12 bpm bytes (0xFF = no sample)
     * Observed hours on this watch are even (00, 02, …, 22): each record is a
     * 2-hour window of 12 × 10-minute bins *ending* at HH:00.
     * Gadgetbridge: start = HH:00 minus (10 * remainingSamples - 10) minutes,
     * then +10 min per bin. For 12 samples that is HH-2:10 … HH:00
     * (here 08:10–10:00). Byte 1 is the year high byte, not FETCH_DATA 0x07.
     */
    private fun parseHeartRate(value: ByteArray): ParsedPacket {
        if (GloryFitProtocol.isFetchEnd(value)) {
            return ParsedPacket.HeartRate(emptyList(), endOfFetch = true)
        }
        // Today's summary F7 04 yyyy MM dd HH mm max min avg — skip as history
        if (value.size < 18) {
            return ParsedPacket.Unknown(0xF7, value)
        }
        if (value.size == 18) {
            val buf = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
            buf.get()
            val year = buf.short.toInt() and 0xFFFF
            val month = buf.get().toInt() and 0xFF
            val day = buf.get().toInt() and 0xFF
            val hour = buf.get().toInt() and 0xFF
            val end = safeDateTime(year, month, day, hour) ?: return ParsedPacket.Unknown(0xF7, value)
            // 12 ten-minute bins ending at HH:00
            var t = end.minusMinutes((10 * (buf.remaining()) - 10).toLong())
            val samples = mutableListOf<HeartRateSample>()
            while (buf.hasRemaining()) {
                val hr = buf.get().toInt() and 0xFF
                if (hr != 0xFF && hr != 0) {
                    samples += HeartRateSample(
                        time = t.atZone(zone).toInstant(),
                        bpm = hr,
                        rawHex = value.toHex(),
                    )
                }
                t = t.plusMinutes(10)
            }
            return ParsedPacket.HeartRate(samples, endOfFetch = false)
        }
        return ParsedPacket.Unknown(0xF7, value)
    }

    private fun parseLiveHr(value: ByteArray): ParsedPacket {
        // E5 11 00 bpm
        if (value.size >= 4 && (value[1].toInt() and 0xFF) == 0x11) {
            val bpm = value[3].toInt() and 0xFF
            return ParsedPacket.LiveHeartRate(
                HeartRateSample(Instant.now(), bpm, live = true, rawHex = value.toHex()),
            )
        }
        return ParsedPacket.Unknown(0xE5, value)
    }

    private fun parseSleepInfo(value: ByteArray): ParsedPacket {
        if (value.size >= 2 && value[1] == GloryFitProtocol.SLEEP_INFO_END) {
            return ParsedPacket.SleepEnd(value)
        }
        if (value.size >= 2 && value[1] == GloryFitProtocol.SLEEP_INFO_DATE && value.size >= 6) {
            val buf = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
            buf.get()
            buf.get()
            val year = buf.short.toInt() and 0xFFFF
            val month = buf.get().toInt() and 0xFF
            val day = buf.get().toInt() and 0xFF
            val expected = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else null
            val date = safeDate(year, month, day) ?: return ParsedPacket.Unknown(0x31, value)
            return ParsedPacket.SleepInfoDate(date, expected)
        }
        return ParsedPacket.Unknown(0x31, value)
    }

    fun parseSleepStages(value: ByteArray, morning: LocalDate?): ParsedPacket {
        if (value.size < 7 || (value.size - 1) % 6 != 0) {
            return ParsedPacket.Unknown(0x32, value)
        }
        val buf = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
        buf.get()
        val stages = mutableListOf<SleepStage>()
        var afterMidnight = false
        val base = morning ?: LocalDate.now()
        while (buf.hasRemaining() && buf.remaining() >= 6) {
            val hour = buf.get().toInt() and 0xFF
            val minute = buf.get().toInt() and 0xFF
            val stage = buf.get().toInt() and 0xFF
            buf.get() // typically 0x01
            val duration = buf.short.toInt() and 0xFFFF
            val date = if (hour > 12 && !afterMidnight) base.minusDays(1) else {
                if (hour <= 12) afterMidnight = true
                base
            }
            val start = date.atTime(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
                .atZone(zone).toInstant()
            stages += SleepStage(start, duration, stage, value.toHex())
        }
        return ParsedPacket.SleepStages(stages)
    }

    /** Speculative EC sleep: log and try to reuse 31/32-style date if the payload looks like one. */
    private fun parseUteSleep(value: ByteArray): ParsedPacket {
        if (GloryFitProtocol.isFetchEnd(value) ||
            (value.size >= 3 && value[2] == GloryFitProtocol.FETCH_END)
        ) {
            return ParsedPacket.SleepEnd(value)
        }
        if (value.size >= 8) {
            // Try year at offset 2 (EC xx yyyy MM dd …)
            val year = u16(value, 2)
            val month = value[4].toInt() and 0xFF
            val day = value[5].toInt() and 0xFF
            val date = safeDate(year, month, day)
            if (date != null) return ParsedPacket.SleepInfoDate(date, expectedStages = null)
        }
        return ParsedPacket.Unknown(0xEC, value)
    }

    private fun u16(value: ByteArray, offset: Int): Int {
        if (offset + 1 >= value.size) return 0
        return ((value[offset].toInt() and 0xFF) shl 8) or (value[offset + 1].toInt() and 0xFF)
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? {
        return try {
            if (year !in 2000..2100 || month !in 1..12 || day !in 1..31) null
            else LocalDate.of(year, month, day)
        } catch (_: Exception) {
            null
        }
    }

    private fun safeDateTime(year: Int, month: Int, day: Int, hour: Int): LocalDateTime? {
        val date = safeDate(year, month, day) ?: return null
        val h = hour.coerceIn(0, 23)
        return date.atTime(h, 0)
    }
    /** Camera remote (C4), documented in Gadgetbridge GloryFitSupport.handleCommand. */
    private fun parseCamera(value: ByteArray): ParsedPacket {
        if (value.size < 2) return ParsedPacket.Unknown(0xC4, value)
        val kind = when (value[1]) {
            GloryFitProtocol.CAMERA_OPEN -> WatchEventKind.CAMERA_OPEN
            GloryFitProtocol.CAMERA_TRIGGER -> WatchEventKind.CAMERA_TRIGGER
            GloryFitProtocol.CAMERA_CLOSE -> WatchEventKind.CAMERA_CLOSE
            else -> return ParsedPacket.Unknown(0xC4, value)
        }
        return ParsedPacket.WatchEventPacket(WatchEvent(kind, value.toHex(), documented = true))
    }

    /** Button / find-phone / music actions (D1), documented in GloryFitSupport. */
    private fun parseAction(value: ByteArray): ParsedPacket {
        if (value.size < 2) return ParsedPacket.Unknown(0xD1, value)
        val kind = when (value[1]) {
            GloryFitProtocol.ACTION_CALL_HANGUP -> WatchEventKind.CALL_HANGUP
            GloryFitProtocol.ACTION_CAMERA_CLOSE -> WatchEventKind.CAMERA_CLOSE
            GloryFitProtocol.ACTION_FIND_PHONE -> {
                if (value.size == 2) WatchEventKind.FIND_PHONE_START
                else if (value.size >= 4 && value[2] == 0x01.toByte() && value[3] == 0x00.toByte()) {
                    WatchEventKind.FIND_PHONE_STOP
                } else WatchEventKind.FIND_PHONE_START
            }
            GloryFitProtocol.ACTION_MUSIC_PLAY -> WatchEventKind.MUSIC_PLAY_PAUSE
            GloryFitProtocol.ACTION_MUSIC_NEXT -> WatchEventKind.MUSIC_NEXT
            GloryFitProtocol.ACTION_MUSIC_PREV -> WatchEventKind.MUSIC_PREV
            GloryFitProtocol.ACTION_MUSIC_VOL_UP -> WatchEventKind.MUSIC_VOL_UP
            GloryFitProtocol.ACTION_MUSIC_VOL_DOWN -> WatchEventKind.MUSIC_VOL_DOWN
            else -> return ParsedPacket.Unknown(0xD1, value)
        }
        return ParsedPacket.WatchEventPacket(WatchEvent(kind, value.toHex(), documented = true))
    }
}
