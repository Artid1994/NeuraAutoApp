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
 *   v3 — + in_app_action_log (Phase 3.1 in-app interaction sequences)
 *   v4 — + isUserVerified, isUserRejected on automation_workflow (Phase 3.3)
 *   v5 — + isLocked on automation_workflow (Phase 3.4 long-term memory)
 *
 * Each step only *adds* a table or column, so Room can generate the migrations
 * itself; [AutoMigration] is validated at compile time against the exported
 * schemas in `app/schemas/`, which means a mismatch fails the build instead of
 * crashing at runtime on upgrade. Existing rows are preserved.
 */
@Database(
    entities = [
        UserActivityLog::class,
        AutomationWorkflow::class,
        InAppActionLog::class
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5)
    ]
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userActivityDao(): UserActivityDao

    abstract fun automationWorkflowDao(): AutomationWorkflowDao

    abstract fun inAppActionDao(): InAppActionDao

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
