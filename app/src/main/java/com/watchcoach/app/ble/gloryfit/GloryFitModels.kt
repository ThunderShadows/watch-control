package com.watchcoach.app.ble.gloryfit

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

enum class ProtocolDialect {
    Unknown,
    ClassicGloryFit,
    UteVariant,
}

data class WatchIdentity(
    val address: String,
    val name: String?,
    val firmware: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val dialect: ProtocolDialect = ProtocolDialect.Unknown,
)

data class HourlySteps(
    val start: LocalDateTime,
    val totalSteps: Int,
    val runningSteps: Int? = null,
    val walkingSteps: Int? = null,
    val dialect: ProtocolDialect = ProtocolDialect.ClassicGloryFit,
    val rawHex: String,
    /** True when field layout is inferred (UTE EB records). */
    val speculative: Boolean = false,
)

data class HeartRateSample(
    val time: Instant,
    val bpm: Int,
    val live: Boolean = false,
    val rawHex: String,
)

data class SleepStage(
    val start: Instant,
    val durationMinutes: Int,
    /** Raw stage code from the watch (typically 1–4). Labels are speculative. */
    val stage: Int,
    val rawHex: String,
)

data class SyncSnapshot(
    val identity: WatchIdentity,
    val hourlySteps: List<HourlySteps>,
    val heartRates: List<HeartRateSample>,
    val sleepStages: List<SleepStage>,
    val notes: List<String>,
    val summary: SyncSummary? = null,
) {
    fun stepsOn(date: LocalDate): Int =
        hourlySteps.filter { it.start.toLocalDate() == date }.sumOf { it.totalSteps }

    fun todaySteps(today: LocalDate = LocalDate.now()): Int = stepsOn(today)

    fun toSummary(durationMs: Long, errors: List<String> = emptyList()): SyncSummary {
        val history = heartRates.filter { !it.live }
        val live = heartRates.filter { it.live }
        val stepDates = hourlySteps.map { it.start.toLocalDate() }.sorted()
        val hrTimes = history.map { it.time }.sorted()
        val sleepLabel = when {
            sleepStages.isNotEmpty() -> "${sleepStages.size} stage(s)"
            else -> "none / unsupported"
        }
        return SyncSummary(
            firmware = identity.firmware,
            batteryPercent = identity.batteryPercent,
            charging = identity.charging,
            dialect = identity.dialect.name,
            stepRecords = hourlySteps.size,
            stepDateRange = dateRange(stepDates.map { it.toString() }),
            todaySteps = todaySteps(),
            walkingSteps = hourlySteps.mapNotNull { it.walkingSteps }.sum().takeIf { it > 0 },
            runningSteps = hourlySteps.mapNotNull { it.runningSteps }.sum().takeIf { it > 0 },
            hrHistorySamples = history.size,
            hrHistoryRange = instantRange(hrTimes),
            liveHrSamples = live.size,
            lastLiveBpm = live.lastOrNull()?.bpm ?: heartRates.lastOrNull { it.live }?.bpm,
            sleepStages = sleepStages.size,
            sleepStatus = sleepLabel,
            durationMs = durationMs,
            errors = errors,
            notes = notes,
        )
    }
}

data class SyncSummary(
    val firmware: String?,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val dialect: String?,
    val stepRecords: Int,
    val stepDateRange: String?,
    val todaySteps: Int,
    val walkingSteps: Int?,
    val runningSteps: Int?,
    val hrHistorySamples: Int,
    val hrHistoryRange: String?,
    val liveHrSamples: Int,
    val lastLiveBpm: Int?,
    val sleepStages: Int,
    val sleepStatus: String,
    val durationMs: Long,
    val errors: List<String>,
    val notes: List<String>,
) {
    fun asText(): String {
        val lines = mutableListOf("Watch Coach sync summary")
        lines += "Firmware: ${firmware ?: "—"}"
        lines += "Battery: ${batteryPercent?.let { "$it%" } ?: "—"}${if (charging == true) " (charging)" else ""}"
        lines += "Dialect: ${dialect ?: "—"}"
        lines += "Steps: $stepRecords hourly record(s)${stepDateRange?.let { " ($it)" } ?: ""}"
        lines += "Today's steps: $todaySteps" +
            (walkingSteps?.let { ", walking $it" } ?: "") +
            (runningSteps?.let { ", running $it" } ?: "")
        lines += "HR history: $hrHistorySamples sample(s)${hrHistoryRange?.let { " ($it)" } ?: ""}"
        lines += "Live HR: $liveHrSamples sample(s)${lastLiveBpm?.let { ", last $it bpm" } ?: ""}"
        lines += "Sleep: $sleepStatus"
        lines += "Duration: ${"%.1f".format(durationMs / 1000.0)} s"
        if (errors.isNotEmpty()) {
            lines += "Errors:"
            errors.forEach { lines += "  - $it" }
        }
        if (notes.isNotEmpty()) {
            lines += "Notes:"
            notes.forEach { lines += "  - $it" }
        }
        return lines.joinToString("\n")
    }
}

private fun dateRange(dates: List<String>): String? {
    if (dates.isEmpty()) return null
    val first = dates.first()
    val last = dates.last()
    return if (first == last) first else "$first .. $last"
}

private fun instantRange(times: List<java.time.Instant>): String? {
    if (times.isEmpty()) return null
    val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    val first = fmt.format(times.first())
    val last = fmt.format(times.last())
    return if (first == last) first else "$first .. $last"
}

object DistanceEstimate {
    /** Vendor-style walking stride: km = steps * heightCm * 0.418 / 100_000. */
    const val DEFAULT_HEIGHT_CM = 170
    const val STRIDE_FACTOR = 0.418

    fun metersFromSteps(steps: Int, heightCm: Int = DEFAULT_HEIGHT_CM): Double =
        steps * heightCm * STRIDE_FACTOR / 100.0

    fun kcalFromSteps(steps: Int, weightKg: Int = 70): Double =
        steps * weightKg * 0.0005
}

enum class WatchEventKind(val label: String, val documented: Boolean = true) {
    CAMERA_OPEN("Camera open"),
    CAMERA_TRIGGER("Camera shutter"),
    CAMERA_CLOSE("Camera close"),
    FIND_PHONE_START("Find phone start"),
    FIND_PHONE_STOP("Find phone stop"),
    MUSIC_PLAY_PAUSE("Music play/pause"),
    MUSIC_NEXT("Music next"),
    MUSIC_PREV("Music previous"),
    MUSIC_VOL_UP("Volume up"),
    MUSIC_VOL_DOWN("Volume down"),
    CALL_HANGUP("Call hang up"),
    UNKNOWN("Unknown / undocumented", documented = false),
}

data class WatchEvent(
    val kind: WatchEventKind,
    val rawHex: String,
    val documented: Boolean,
    val epochMs: Long = System.currentTimeMillis(),
    val characteristic: String = "33F2",
)

