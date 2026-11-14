package com.watchcoach.app.data.local

import com.watchcoach.app.ble.gloryfit.DistanceEstimate
import com.watchcoach.app.ble.gloryfit.SyncSnapshot
import com.watchcoach.app.data.health.DashboardData
import com.watchcoach.app.data.health.DaySteps
import com.watchcoach.app.data.health.HeartRateSnapshot
import com.watchcoach.app.data.health.RecoveryCalculator
import com.watchcoach.app.data.health.SleepSnapshot
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WatchDataRepository(private val dao: WatchDao) {

    val meta: Flow<SyncMetaEntity?> = dao.observeMeta()

    suspend fun latestMeta(): SyncMetaEntity? = dao.meta()

    suspend fun persist(snapshot: SyncSnapshot) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val stepRows = snapshot.hourlySteps.map { rec ->
            StepHourEntity(
                epochHourMs = rec.start.atZone(zone).toInstant().toEpochMilli(),
                totalSteps = rec.totalSteps,
                runningSteps = rec.runningSteps,
                walkingSteps = rec.walkingSteps,
                dialect = rec.dialect.name,
                speculative = rec.speculative,
                rawHex = rec.rawHex,
                syncedAtMs = now,
            )
        }
        val hrRows = snapshot.heartRates.map { rec ->
            HeartRateEntity(
                epochMs = rec.time.toEpochMilli(),
                bpm = rec.bpm,
                live = rec.live,
                rawHex = rec.rawHex,
                syncedAtMs = now,
            )
        }
        val sleepRows = snapshot.sleepStages.map { rec ->
            SleepStageEntity(
                epochMs = rec.start.toEpochMilli(),
                durationMinutes = rec.durationMinutes,
                stage = rec.stage,
                rawHex = rec.rawHex,
                syncedAtMs = now,
            )
        }
        if (stepRows.isNotEmpty()) dao.upsertSteps(stepRows)
        if (hrRows.isNotEmpty()) dao.upsertHeartRates(hrRows)
        if (sleepRows.isNotEmpty()) dao.upsertSleep(sleepRows)
        dao.upsertMeta(
            SyncMetaEntity(
                lastSyncEpochMs = now,
                batteryPercent = snapshot.identity.batteryPercent,
                charging = snapshot.identity.charging,
                firmware = snapshot.identity.firmware,
                dialect = snapshot.identity.dialect.name,
                address = snapshot.identity.address,
                name = snapshot.identity.name,
                notes = snapshot.notes.joinToString("\n").ifBlank { null },
            ),
        )
    }

    suspend fun asDashboard(zone: ZoneId = ZoneId.systemDefault()): DashboardData? {
        val today = LocalDate.now(zone)
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()

        val weekSteps = dao.stepsBetween(weekStart, tomorrow)
        if (weekSteps.isEmpty() && dao.heartRatesBetween(weekStart, tomorrow).isEmpty() &&
            dao.sleepBetween(weekStart - 24 * 3600_000L, tomorrow).isEmpty() &&
            dao.meta() == null
        ) {
            return null
        }

        val byDate = weekSteps.groupBy {
            Instant.ofEpochMilli(it.epochHourMs).atZone(zone).toLocalDate()
        }.mapValues { e -> e.value.sumOf { it.totalSteps }.toLong() }

        val last7 = (0..6).map { offset ->
            val d = today.minusDays((6 - offset).toLong())
            DaySteps(d, byDate[d] ?: 0L)
        }
        val todaySteps = byDate[today]

        val hrs = dao.heartRatesBetween(todayStart - 48 * 3600_000L, tomorrow)
        val latest = hrs.maxByOrNull { it.epochMs }
        val todayHrs = hrs.filter { it.epochMs >= todayStart }
        val avg = todayHrs.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toLong()

        val sleepFrom = today.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val sleepTo = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val stages = dao.sleepBetween(sleepFrom, sleepTo)
        val sleepDur = stages.sumOf { it.durationMinutes }.takeIf { stages.isNotEmpty() }?.let {
            Duration.ofMinutes(it.toLong())
        }
        val sleepStart = stages.minByOrNull { it.epochMs }?.let { Instant.ofEpochMilli(it.epochMs) }
        val sleepEnd = stages.maxByOrNull { it.epochMs }?.let { row ->
            Instant.ofEpochMilli(row.epochMs).plusSeconds(row.durationMinutes * 60L)
        }

        // Resting HR approximation: lowest 10-minute bin (or live) in the last 24h vs previous 6 days.
        val last24 = hrs.filter { it.epochMs >= todayStart - 24 * 3600_000L }
        val currentRhr = last24.minByOrNull { it.bpm }?.bpm?.toLong()
        val baselineWindow = dao.heartRatesBetween(todayStart - 7 * 24 * 3600_000L, todayStart)
        val baselineRhr = baselineWindow.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toLong()

        val distance = todaySteps?.let { DistanceEstimate.metersFromSteps(it.toInt()) }
        val calories = todaySteps?.let { DistanceEstimate.kcalFromSteps(it.toInt()) }
        val recovery = RecoveryCalculator.compute(sleepDur, currentRhr, baselineRhr)

        return DashboardData(
            todaySteps = todaySteps,
            last7DaysSteps = last7,
            heartRate = HeartRateSnapshot(
                latestBpm = latest?.bpm?.toLong(),
                latestTime = latest?.let { Instant.ofEpochMilli(it.epochMs) },
                averageBpm = avg,
            ),
            lastNightSleep = SleepSnapshot(sleepDur, sleepStart, sleepEnd),
            currentRestingHr = currentRhr,
            baselineRestingHr = baselineRhr,
            todayDistanceMeters = distance,
            todayCaloriesKcal = calories,
            recovery = recovery,
        )
    }

    suspend fun exportForHealthConnect(zone: ZoneId = ZoneId.systemDefault()): SyncExport {
        val today = LocalDate.now(zone)
        val from = today.minusDays(6).atStartOfDay(zone).toInstant()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant()
        return SyncExport(
            steps = dao.stepsBetween(from.toEpochMilli(), to.toEpochMilli()),
            heartRates = dao.heartRatesBetween(from.toEpochMilli(), to.toEpochMilli()),
            sleep = dao.sleepBetween(
                today.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
                to.toEpochMilli(),
            ),
        )
    }

    suspend fun history(
        from: LocalDate,
        toExclusive: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<List<StepHourEntity>, List<HeartRateEntity>> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = toExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.stepsBetween(start, end) to dao.heartRatesBetween(start, end)
    }
}

data class SyncExport(
    val steps: List<StepHourEntity>,
    val heartRates: List<HeartRateEntity>,
    val sleep: List<SleepStageEntity>,
)
