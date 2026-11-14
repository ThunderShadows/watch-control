package com.watchcoach.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        StepHourEntity::class,
        HeartRateEntity::class,
        SleepStageEntity::class,
        SyncMetaEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class WatchDatabase : RoomDatabase() {
    abstract fun watchDao(): WatchDao

    companion object {
        fun create(context: Context): WatchDatabase =
            Room.databaseBuilder(context, WatchDatabase::class.java, "watch_coach.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
