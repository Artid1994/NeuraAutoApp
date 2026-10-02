package com.example.neuraauto.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [UserActivityLog::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userActivityDao(): UserActivityDao

    companion object {
        private const val DB_NAME = "neuraauto.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Process-wide singleton. The accessibility service and the UI both
         * write/read the same database, so they must share one instance.
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
