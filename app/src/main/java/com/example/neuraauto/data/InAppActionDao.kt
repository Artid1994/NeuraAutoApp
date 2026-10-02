package com.example.neuraauto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InAppActionDao {

    @Insert
    suspend fun insert(action: InAppActionLog): Long

    /** Most recent interactions, newest first. */
    @Query("SELECT * FROM in_app_action_log ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun recentActions(limit: Int): List<InAppActionLog>

    /**
     * Everything captured for one app in one hour-of-day. Feeds sequence
     * detection: the routine's `(package, hour)` pair selects the rows, then
     * grouping by `sequenceGroupHash` reconstructs the flows.
     */
    @Query(
        """
        SELECT * FROM in_app_action_log
        WHERE packageName = :packageName AND hourOfDay = :hourOfDay
        ORDER BY sequenceGroupHash, stepIndex
        """
    )
    suspend fun actionsForPackageHour(
        packageName: String,
        hourOfDay: Int
    ): List<InAppActionLog>

    /** Live count for the dashboard. */
    @Query("SELECT COUNT(*) FROM in_app_action_log")
    fun observeActionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM in_app_action_log")
    suspend fun actionCount(): Int

    /** Distinct ISO weekdays with any captured interaction. */
    @Query("SELECT COUNT(DISTINCT dayOfWeek) FROM in_app_action_log")
    suspend fun distinctObservedDays(): Int

    /** Retention sweep: drop interactions older than [cutoffMillis]. */
    @Query("DELETE FROM in_app_action_log WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM in_app_action_log")
    suspend fun clearAll()
}
