package com.watchcoach.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(rows: List<StepHourEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHeartRates(rows: List<HeartRateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSleep(rows: List<SleepStageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: SyncMetaEntity)

    @Query("SELECT * FROM step_hours WHERE epochHourMs >= :fromMs AND epochHourMs < :toMs ORDER BY epochHourMs")
    suspend fun stepsBetween(fromMs: Long, toMs: Long): List<StepHourEntity>

    @Query("SELECT * FROM hr_samples WHERE epochMs >= :fromMs AND epochMs < :toMs ORDER BY epochMs")
    suspend fun heartRatesBetween(fromMs: Long, toMs: Long): List<HeartRateEntity>

    @Query("SELECT * FROM sleep_stages WHERE epochMs >= :fromMs AND epochMs < :toMs ORDER BY epochMs")
    suspend fun sleepBetween(fromMs: Long, toMs: Long): List<SleepStageEntity>

    @Query("SELECT * FROM sync_meta WHERE id = 0")
    fun observeMeta(): Flow<SyncMetaEntity?>

    @Query("SELECT * FROM sync_meta WHERE id = 0")
    suspend fun meta(): SyncMetaEntity?
}
