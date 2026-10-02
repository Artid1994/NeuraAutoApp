package com.example.neuraauto.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single captured app-foreground event.
 *
 * One row is written every time the accessibility service observes a
 * `TYPE_WINDOW_STATE_CHANGED` event for another package. The ambient
 * conditions at that moment (hour, weekday, charging, Wi-Fi) are stored on
 * the row so routine detection is a pure aggregation with no extra lookups.
 */
@Entity(
    tableName = "user_activity_log",
    indices = [Index(value = ["packageName", "hourOfDay"])]
)
data class UserActivityLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    /** Package that came to the foreground, e.g. `com.linecorp.line`. */
    val packageName: String,

    /** Wall-clock capture time, epoch milliseconds. */
    val timestampMillis: Long,

    /** 0..23, local time. */
    val hourOfDay: Int,

    /** ISO weekday: 1 = Monday .. 7 = Sunday. */
    val dayOfWeek: Int,

    /** Device was charging when the event was captured. */
    val isCharging: Boolean,

    /** Active network transport was Wi-Fi when the event was captured. */
    val isWifiConnected: Boolean
)
