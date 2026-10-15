package com.modradar.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AnalysisTaskEntity::class,
        StageCheckpointEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
    ],
    version = 2,
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
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN reasoning TEXT")
            }
        }
    }
}
