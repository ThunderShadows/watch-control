package com.watchcoach.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "step_hours")
data class StepHourEntity(
    @PrimaryKey val epochHourMs: Long,
    val totalSteps: Int,
    val runningSteps: Int?,
    val walkingSteps: Int?,
    val dialect: String,
    val speculative: Boolean,
    val rawHex: String,
    val syncedAtMs: Long,
)

@Entity(tableName = "hr_samples")
data class HeartRateEntity(
    @PrimaryKey val epochMs: Long,
    val bpm: Int,
    val live: Boolean,
    val rawHex: String,
    val syncedAtMs: Long,
)

@Entity(tableName = "sleep_stages")
data class SleepStageEntity(
    @PrimaryKey val epochMs: Long,
    val durationMinutes: Int,
    val stage: Int,
    val rawHex: String,
    val syncedAtMs: Long,
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val id: Int = 0,
    val lastSyncEpochMs: Long,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val firmware: String?,
    val dialect: String?,
    val address: String?,
    val name: String?,
    val notes: String?,
)
