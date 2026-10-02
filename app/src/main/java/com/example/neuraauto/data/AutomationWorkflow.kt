package com.example.neuraauto.data

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

    val createdAtMillis: Long = System.currentTimeMillis()
)
