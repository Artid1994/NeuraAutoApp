package com.example.neuraauto.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-enabled automation: at [scheduledHour]:[scheduledMinute] every day,
 * open [targetApp] and send [targetMessage].
 *
 * The unique index on the schedule slot means re-enabling the same routine
 * replaces the previous row rather than stacking duplicate alarms.
 */
@Entity(
    tableName = "automation_workflow",
    indices = [
        Index(
            value = ["targetApp", "scheduledHour", "scheduledMinute"],
            unique = true
        )
    ]
)
data class AutomationWorkflow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    /** Package to bring to the foreground, e.g. `com.linecorp.line`. */
    val targetApp: String,

    /** 0..23 local time. */
    val scheduledHour: Int,

    /** 0..59 local time. */
    val scheduledMinute: Int,

    /** Text injected into the target app's input field. */
    val targetMessage: String,

    /** Only active workflows are scheduled and executed. */
    val isActive: Boolean = true,

    /**
     * Whether the user has explicitly confirmed this workflow is correct.
     * Set when the user clicks "Verify Pattern" rather than just "Enable".
     */
    @ColumnInfo(defaultValue = "0")
    val isUserVerified: Boolean = false,

    /**
     * Whether the user has explicitly rejected this workflow. A rejected
     * workflow is never auto-enabled by background training.
     */
    @ColumnInfo(defaultValue = "0")
    val isUserRejected: Boolean = false,

    /**
     * Whether this workflow is locked as long-term memory.
     *
     * Locked workflows are never deleted by auto-pruning or background
     * training. Verified and user-created workflows default to locked;
     * auto-created workflows start unlocked until the user verifies them.
     */
    @ColumnInfo(defaultValue = "1")
    val isLocked: Boolean = true,

    val createdAtMillis: Long = System.currentTimeMillis()
)
