package com.example.neuraauto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationWorkflowDao {

    /**
     * Insert, or replace the row occupying the same (targetApp, hour, minute)
     * slot. Returns the row id.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workflow: AutomationWorkflow): Long

    /** Workflows that should currently have an alarm registered. */
    @Query("SELECT * FROM automation_workflow WHERE isActive = 1 ORDER BY scheduledHour, scheduledMinute")
    suspend fun activeWorkflows(): List<AutomationWorkflow>

    @Query("SELECT * FROM automation_workflow WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): AutomationWorkflow?

    @Query("SELECT * FROM automation_workflow ORDER BY scheduledHour, scheduledMinute")
    fun observeAll(): Flow<List<AutomationWorkflow>>

    @Query(
        "SELECT * FROM automation_workflow " +
            "WHERE targetApp = :packageName AND scheduledHour = :hour AND scheduledMinute = :minute LIMIT 1"
    )
    suspend fun findBySlot(packageName: String, hour: Int, minute: Int): AutomationWorkflow?

    @Query("UPDATE automation_workflow SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    /**
     * Update in place, preserving the row id.
     *
     * Preferred over [upsert] when the slot already exists: `REPLACE` is a
     * DELETE+INSERT that mints a new id, which would orphan the alarm whose
     * PendingIntent request code is derived from the id.
     */
    @Update
    suspend fun update(workflow: AutomationWorkflow)

    @Query("DELETE FROM automation_workflow WHERE id = :id")
    suspend fun delete(id: Long)

    /** Full update preserving the row id. */
    @Update
    suspend fun updateWorkflow(workflow: AutomationWorkflow)

    /** Mark a workflow as explicitly verified by the user. */
    @Query("UPDATE automation_workflow SET isUserVerified = 1, isUserRejected = 0 WHERE id = :id")
    suspend fun verifyWorkflow(id: Long)

    /** Mark a workflow as explicitly rejected by the user. */
    @Query("UPDATE automation_workflow SET isUserRejected = 1, isUserVerified = 0 WHERE id = :id")
    suspend fun rejectWorkflow(id: Long)
}
