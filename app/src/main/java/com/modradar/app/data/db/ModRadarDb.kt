package com.modradar.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AnalysisTaskEntity::class,
        StageCheckpointEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ModRadarDb : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: ModRadarDb? = null

        fun get(context: Context): ModRadarDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ModRadarDb::class.java,
                    "modradar.db",
                ).build().also { INSTANCE = it }
            }
    }
}
