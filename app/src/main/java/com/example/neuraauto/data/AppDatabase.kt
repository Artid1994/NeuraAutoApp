package com.example.neuraauto.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema history:
 *   v1 — user_activity_log (Phase 2 activity capture)
 *   v2 — + automation_workflow (Phase 3 user-enabled automations)
 *
 * The 1→2 change only *adds* a table, so Room can generate the migration
 * itself; [AutoMigration] is validated at compile time, which means a
 * mismatch fails the build instead of crashing at runtime on upgrade.
 * Existing activity logs are preserved.
 */
@Database(
    entities = [UserActivityLog::class, AutomationWorkflow::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userActivityDao(): UserActivityDao

    abstract fun automationWorkflowDao(): AutomationWorkflowDao

    companion object {
        private const val DB_NAME = "neuraauto.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Process-wide singleton. The accessibility service, the alarm
         * receiver and the UI all touch the same database, so they must
         * share one instance.
         */
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).build().also { instance = it }
            }
    }
}
