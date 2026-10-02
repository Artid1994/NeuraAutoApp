package com.example.neuraauto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("DELETE FROM automation_workflow WHERE id = :id")
    suspend fun delete(id: Long)
}
