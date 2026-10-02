package com.example.neuraauto.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One in-app UI interaction observed by the accessibility service.
 *
 * Rows are grouped into a *sequence* by [sequenceGroupHash]: contiguous events
 * in the same app, separated by no more than the tracker's window, share one
 * hash and are ordered by [stepIndex]. That grouping is what turns a flat event
 * stream into "Open LINE → Click Input → Type Text → Click Send".
 *
 * PRIVACY: [textSnippet] holds a short, whitespace-collapsed excerpt of what the
 * user typed. Password fields are never captured (the service checks
 * `isPassword` and bails out before calling the recorder), and the snippet is
 * hard-truncated. It is still user content stored on device — the retention
 * sweep in [InAppActionDao.deleteOlderThan] exists to bound how long it lives.
 */
@Entity(
    tableName = "in_app_action_log",
    indices = [
        Index(value = ["sequenceGroupHash", "stepIndex"]),
        Index(value = ["packageName", "hourOfDay"]),
        Index(value = ["timestampMillis"])
    ]
)
data class InAppActionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    /** App the interaction happened in, e.g. `com.linecorp.line`. */
    val packageName: String,

    /** [EVENT_CLICK] or [EVENT_TEXT_CHANGE]. */
    val eventType: String,

    /** `AccessibilityNodeInfo.viewIdResourceName`, or null when the node has none. */
    val viewId: String?,

    /** Short excerpt of typed text; null for clicks and empty fields. */
    val textSnippet: String?,

    /** Identifies the contiguous interaction group this row belongs to. */
    val sequenceGroupHash: String,

    /** 0-based position within the group. */
    val stepIndex: Int,

    /** Wall-clock capture time, epoch milliseconds. */
    val timestampMillis: Long,

    /** 0..23, local time. */
    val hourOfDay: Int,

    /** ISO weekday: 1 = Monday .. 7 = Sunday. */
    val dayOfWeek: Int
) {
    companion object {
        const val EVENT_CLICK = "CLICK"
        const val EVENT_TEXT_CHANGE = "TEXT_CHANGE"
    }
}
