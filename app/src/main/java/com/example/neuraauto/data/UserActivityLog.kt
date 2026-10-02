package com.example.neuraauto.data

import androidx.room.ColumnInfo
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
    val isWifiConnected: Boolean,

    /**
     * SSID of the connected Wi-Fi network, or null when not on Wi-Fi or the
     * platform refused the read (see [AmbientState.wifiSsid]).
     *
     * Nullable, so the v5→v6 migration needs no SQL default: existing rows
     * simply read back NULL, which the feature hasher treats as "unknown".
     */
    val wifiSsid: String? = null,

    /**
     * Battery percentage 0..100 at capture time, or -1 when unavailable.
     *
     * NOT NULL, so the v5→v6 auto-migration requires an explicit SQL default;
     * without it Room refuses the migration at compile time. The Kotlin
     * default is not enough — Kotlin defaults are a constructor concern, the
     * SQL default is what existing rows receive.
     */
    @ColumnInfo(defaultValue = "-1")
    val batteryPercent: Int = -1
)
