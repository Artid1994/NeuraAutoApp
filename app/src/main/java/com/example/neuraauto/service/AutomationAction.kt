package com.example.neuraauto.service

import android.content.Intent
import android.os.SystemClock

/**
 * The work the accessibility service should perform when an alarm fires.
 *
 * Kept as a plain data class so it can be handed to
 * [AutomationAccessibility.pendingAction] in-process, and also serialised
 * into the alarm [Intent] so a cold start can rebuild it.
 */
data class AutomationAction(
    val workflowId: Long,
    val targetPackage: String,
    val actionType: String,
    val message: String,
    /** Elapsed-realtime stamp (survives clock changes) of when the alarm fired. */
    val createdAtElapsedRealtime: Long = SystemClock.elapsedRealtime()
) {

    /**
     * An alarm that could not be serviced immediately must not be replayed much
     * later. Without this, an action queued while the accessibility service was
     * disconnected would fire whenever the user next opened the target app,
     * potentially hours after the scheduled time.
     */
    fun isExpired(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): Boolean =
        nowElapsedRealtime - createdAtElapsedRealtime > MAX_AGE_MILLIS

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_WORKFLOW_ID, workflowId)
        putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
        putExtra(EXTRA_ACTION_TYPE, actionType)
        putExtra(EXTRA_MESSAGE, message)
        // Elapsed-realtime is not comparable across reboots, so the receiver
        // re-stamps it; only the in-process handoff relies on this value.
        putExtra(EXTRA_CREATED_ELAPSED, createdAtElapsedRealtime)
    }

    companion object {
        const val ACTION_SEND_MESSAGE = "SEND_MESSAGE"

        /** Actions older than this are discarded rather than executed late. */
        const val MAX_AGE_MILLIS = 5 * 60 * 1000L

        const val EXTRA_WORKFLOW_ID = "extra_workflow_id"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_ACTION_TYPE = "extra_action_type"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_CREATED_ELAPSED = "extra_created_elapsed"

        /**
         * Rebuild from the alarm intent; null when the payload is incomplete.
         * [createdAtElapsedRealtime] is deliberately re-stamped to now: the
         * intent may have been written before a reboot, and elapsed-realtime
         * values from a previous boot are meaningless.
         */
        fun fromIntent(intent: Intent?): AutomationAction? {
            val current = intent ?: return null
            val targetPackage = current.getStringExtra(EXTRA_TARGET_PACKAGE) ?: return null
            val message = current.getStringExtra(EXTRA_MESSAGE) ?: return null
            val actionType = current.getStringExtra(EXTRA_ACTION_TYPE) ?: ACTION_SEND_MESSAGE
            return AutomationAction(
                workflowId = current.getLongExtra(EXTRA_WORKFLOW_ID, -1L),
                targetPackage = targetPackage,
                actionType = actionType,
                message = message,
                createdAtElapsedRealtime = SystemClock.elapsedRealtime()
            )
        }
    }
}
