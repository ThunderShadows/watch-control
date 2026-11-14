package com.watchcoach.app.data.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_AVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.watchcoach.app.ble.gloryfit.DistanceEstimate
import com.watchcoach.app.data.local.SyncExport
import java.time.ZoneOffset
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId

class HealthConnectRepository(private val context: Context) {

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )

    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    val backgroundReadPermission: String =
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun availability(): HealthAvailability {
        return when (HealthConnectClient.getSdkStatus(context)) {
            SDK_AVAILABLE -> HealthAvailability.Available
            SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UpdateRequired
            SDK_UNAVAILABLE -> HealthAvailability.Unavailable
            else -> HealthAvailability.NotInstalled
        }
    }

    fun isBackgroundReadAvailable(): Boolean {
        val client = clientOrNull() ?: return false
        return try {
            val status = client.features.getFeatureStatus(
                androidx.health.connect.client.HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            )
            status == androidx.health.connect.client.HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (_: Exception) {
            false
        }
    }

    fun permissionsToRequest(): Set<String> {
        val base = requiredPermissions + writePermissions
        return if (isBackgroundReadAvailable()) {
            base + backgroundReadPermission
        } else {
            base
        }
    }

    suspend fun hasWritePermissions(): Boolean {
        val granted = grantedPermissions()
        return granted.containsAll(writePermissions)
    }

    fun clientOrNull(): HealthConnectClient? {
        return if (HealthConnectClient.getSdkStatus(context) == SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    suspend fun grantedPermissions(): Set<String> {
        val client = clientOrNull() ?: return emptySet()
        return try {
            client.permissionController.getGrantedPermissions()
        } catch (_: Exception) {
            emptySet()
        }
    }

    suspend fun hasAllRequiredPermissions(): Boolean {
        val granted = grantedPermissions()
        return granted.containsAll(requiredPermissions)
    }

    suspend fun loadDashboard(zone: ZoneId = ZoneId.systemDefault()): DashboardData {
        val today = LocalDate.now(zone)
        val todayRange = dayRange(today, zone)
        val weekStart = today.minusDays(6)

        val todaySteps = aggregateSteps(todayRange.first, todayRange.second)
        val last7 = aggregateStepsByDay(weekStart, today, zone)
        val heart = readHeartRateSnapshot(todayRange.first, Instant.now())
        val sleep = readLastNightSleep(today, zone)
        val currentRhr = aggregateRestingHr(today.minusDays(1).atStart(zone), Instant.now())
        val baselineRhr = aggregateRestingHr(
            today.minusDays(7).atStart(zone),
            today.atStart(zone),
        )
        val distance = aggregateDistance(todayRange.first, todayRange.second)
        val calories = aggregateCalories(todayRange.first, todayRange.second)
        val recovery = RecoveryCalculator.compute(sleep.duration, currentRhr, baselineRhr)

        return DashboardData(
            todaySteps = todaySteps,
            last7DaysSteps = last7,
            heartRate = heart,
            lastNightSleep = sleep,
            currentRestingHr = currentRhr,
            baselineRestingHr = baselineRhr,
            todayDistanceMeters = distance,
            todayCaloriesKcal = calories,
            recovery = recovery,
        )
    }

    suspend fun stepsInLastHours(hours: Long, now: Instant = Instant.now()): Long? {
        val start = now.minus(Duration.ofHours(hours))
        return aggregateSteps(start, now)
    }

    suspend fun currentRestingHr(zone: ZoneId = ZoneId.systemDefault()): Long? {
        val today = LocalDate.now(zone)
        return aggregateRestingHr(today.minusDays(1).atStart(zone), Instant.now())
    }

    suspend fun baselineRestingHr(zone: ZoneId = ZoneId.systemDefault()): Long? {
        val today = LocalDate.now(zone)
        return aggregateRestingHr(today.minusDays(7).atStart(zone), today.atStart(zone))
    }

    private suspend fun aggregateSteps(start: Instant, end: Instant): Long? {
        val client = clientOrNull() ?: return null
        if (end <= start) return 0L
        return try {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            result[StepsRecord.COUNT_TOTAL]
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun aggregateStepsByDay(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        zone: ZoneId,
    ): List<DaySteps> {
        val client = clientOrNull() ?: return emptyList()
        val start = startDate.atStart(zone)
        val end = endDateInclusive.plusDays(1).atStart(zone)
        return try {
            val grouped = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            )
            val byDate = grouped.associate { rec ->
                rec.startTime.atZone(zone).toLocalDate() to (rec.result[StepsRecord.COUNT_TOTAL] ?: 0L)
            }
            generateSequence(startDate) { it.plusDays(1) }
                .takeWhile { !it.isAfter(endDateInclusive) }
                .map { date -> DaySteps(date, byDate[date] ?: 0L) }
                .toList()
        } catch (_: Exception) {
            generateSequence(startDate) { it.plusDays(1) }
                .takeWhile { !it.isAfter(endDateInclusive) }
                .map { DaySteps(it, 0L) }
                .toList()
        }
    }

    private suspend fun readHeartRateSnapshot(start: Instant, end: Instant): HeartRateSnapshot {
        val client = clientOrNull() ?: return HeartRateSnapshot(null, null, null)
        return try {
            val aggregate = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            val average = aggregate[HeartRateRecord.BPM_AVG]

            // Most recent sample in the last 48 hours (covers overnight devices).
            val recentStart = end.minus(Duration.ofHours(48)).coerceAtLeast(start.minus(Duration.ofHours(24)))
            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(recentStart, end),
                    ascendingOrder = false,
                    pageSize = 20,
                ),
            )
            val latestSample = records.records
                .asSequence()
                .flatMap { rec -> rec.samples.asSequence().map { rec to it } }
                .maxByOrNull { it.second.time }

            HeartRateSnapshot(
                latestBpm = latestSample?.second?.beatsPerMinute,
                latestTime = latestSample?.second?.time,
                averageBpm = average,
            )
        } catch (_: Exception) {
            HeartRateSnapshot(null, null, null)
        }
    }

    private suspend fun readLastNightSleep(today: LocalDate, zone: ZoneId): SleepSnapshot {
        val client = clientOrNull() ?: return SleepSnapshot(null, null, null)
        val windowStart = today.minusDays(1).atTime(LocalTime.of(18, 0)).atZone(zone).toInstant()
        val windowEnd = today.atTime(LocalTime.of(14, 0)).atZone(zone).toInstant()
        return try {
            val durationTotal = client.aggregate(
                AggregateRequest(
                    metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(windowStart, windowEnd),
                ),
            )[SleepSessionRecord.SLEEP_DURATION_TOTAL]

            val sessions = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(windowStart, windowEnd),
                ),
            ).records

            val primary = sessions.maxByOrNull { Duration.between(it.startTime, it.endTime) }
            SleepSnapshot(
                duration = durationTotal ?: primary?.let { Duration.between(it.startTime, it.endTime) },
                start = primary?.startTime,
                end = primary?.endTime,
            )
        } catch (_: Exception) {
            SleepSnapshot(null, null, null)
        }
    }

    private suspend fun aggregateRestingHr(start: Instant, end: Instant): Long? {
        val client = clientOrNull() ?: return null
        if (end <= start) return null
        return try {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(RestingHeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            result[RestingHeartRateRecord.BPM_AVG]
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun aggregateDistance(start: Instant, end: Instant): Double? {
        val client = clientOrNull() ?: return null
        return try {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            result[DistanceRecord.DISTANCE_TOTAL]?.inMeters
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun aggregateCalories(start: Instant, end: Instant): Double? {
        val client = clientOrNull() ?: return null
        return try {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
        } catch (_: Exception) {
            null
        }
    }


    /**
     * Optional: push locally synced watch records into Health Connect when the
     * user has granted write access. Distance and calories are written only as
     * phone-side estimates from step counts (the watch does not send them).
     */
    suspend fun writeFromWatch(export: SyncExport, zone: ZoneId = ZoneId.systemDefault()): String? {
        val client = clientOrNull() ?: return "Health Connect is not available."
        if (!hasWritePermissions()) return "Health Connect write permission was not granted."
        val offset: ZoneOffset = zone.rules.getOffset(java.time.Instant.now())
        val metadata = try {
            Metadata.autoRecorded(device = Device(type = Device.TYPE_WATCH))
        } catch (_: Throwable) {
            Metadata.manualEntry()
        }
        val records = mutableListOf<androidx.health.connect.client.records.Record>()
        for (hour in export.steps) {
            if (hour.totalSteps <= 0) continue
            val start = java.time.Instant.ofEpochMilli(hour.epochHourMs)
            val end = start.plusSeconds(3600)
            records += StepsRecord(
                startTime = start,
                endTime = end,
                startZoneOffset = offset,
                endZoneOffset = offset,
                count = hour.totalSteps.toLong(),
                metadata = metadata,
            )
        }
        if (export.heartRates.isNotEmpty()) {
            val samples = export.heartRates
                .filter { it.bpm in 1..250 }
                .map { HeartRateRecord.Sample(java.time.Instant.ofEpochMilli(it.epochMs), it.bpm.toLong()) }
                .sortedBy { it.time }
            if (samples.isNotEmpty()) {
                val start = samples.first().time
                val end = samples.last().time.plusSeconds(60)
                records += HeartRateRecord(
                    startTime = start,
                    endTime = if (end > start) end else start.plusSeconds(60),
                    startZoneOffset = offset,
                    endZoneOffset = offset,
                    samples = samples,
                    metadata = metadata,
                )
            }
        }
        if (export.sleep.isNotEmpty()) {
            val start = java.time.Instant.ofEpochMilli(export.sleep.minOf { it.epochMs })
            val last = export.sleep.maxByOrNull { it.epochMs }!!
            val end = java.time.Instant.ofEpochMilli(last.epochMs).plusSeconds(last.durationMinutes * 60L)
            if (end > start) {
                records += SleepSessionRecord(
                    startTime = start,
                    endTime = end,
                    startZoneOffset = offset,
                    endZoneOffset = offset,
                    metadata = metadata,
                )
            }
        }
        val todaySteps = export.steps
            .filter {
                java.time.Instant.ofEpochMilli(it.epochHourMs).atZone(zone).toLocalDate() == java.time.LocalDate.now(zone)
            }
            .sumOf { it.totalSteps }
        if (todaySteps > 0) {
            val dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val dayEnd = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
            records += DistanceRecord(
                startTime = dayStart,
                endTime = dayEnd,
                startZoneOffset = offset,
                endZoneOffset = offset,
                distance = Length.meters(DistanceEstimate.metersFromSteps(todaySteps)),
                metadata = metadata,
            )
            records += TotalCaloriesBurnedRecord(
                startTime = dayStart,
                endTime = dayEnd,
                startZoneOffset = offset,
                endZoneOffset = offset,
                energy = Energy.kilocalories(DistanceEstimate.kcalFromSteps(todaySteps)),
                metadata = metadata,
            )
        }
        if (records.isEmpty()) return "Nothing to write."
        return try {
            client.insertRecords(records)
            "Wrote ${records.size} record(s) to Health Connect."
        } catch (t: SecurityException) {
            "Health Connect write denied: ${t.message}"
        } catch (t: Throwable) {
            "Health Connect write failed: ${t.message}"
        }
    }

    companion object {
        fun installOrUpdateIntent(): Intent {
            val play = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=com.google.android.apps.healthdata"),
            )
            play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return play
        }

        fun settingsIntent(): Intent {
            val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return intent
        }
    }
}

private fun LocalDate.atStart(zone: ZoneId): Instant =
    this.atStartOfDay(zone).toInstant()

private fun dayRange(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
    val start = date.atStartOfDay(zone).toInstant()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant()
    return start to end
}
