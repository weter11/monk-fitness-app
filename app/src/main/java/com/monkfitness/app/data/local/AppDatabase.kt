package com.monkfitness.app.data.local

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.SetLog
import com.monkfitness.app.data.model.UserProgress

@Database(entities = [UserProgress::class, PostureSessionProgress::class, SetLog::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `posture_session_progress` (
                        `day` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL,
                        `completionDate` INTEGER NOT NULL,
                        `focusArea` TEXT NOT NULL,
                        PRIMARY KEY(`day`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `set_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `exerciseId` TEXT NOT NULL,
                        `repsCompleted` INTEGER NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `sessionDate` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "monk_fitness_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
