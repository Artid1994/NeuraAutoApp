package com.example.neuraauto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserActivityDao {

    @Insert
    suspend fun insert(log: UserActivityLog): Long

    /**
     * Most recent [limit] events, newest first. Routine detection reads this
     * once per analysis rather than scanning the whole table.
     */
    @Query("SELECT * FROM user_activity_log ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun recentLogs(limit: Int): List<UserActivityLog>

    /** Live row count, used by the dashboard to show how much data exists. */
    @Query("SELECT COUNT(*) FROM user_activity_log")
    fun observeLogCount(): Flow<Int>

    /**
     * Number of rows already stored for this exact (package, hour, weekday).
     *
     * The accessibility service calls this before inserting: a routine is
     * counted once per day, so opening LINE ten times in the 08:00 hour still
     * contributes a single supporting day. Without this the confidence
     * denominator and numerator would both be inflated by repeat visits.
     */
    @Query(
        "SELECT COUNT(*) FROM user_activity_log " +
            "WHERE packageName = :packageName AND hourOfDay = :hourOfDay AND dayOfWeek = :dayOfWeek"
    )
    suspend fun countFor(packageName: String, hourOfDay: Int, dayOfWeek: Int): Int

    @Query("DELETE FROM user_activity_log")
    suspend fun clearAll()
}
