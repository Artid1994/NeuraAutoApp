package com.example.neuraauto.service

import android.content.Intent

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
    val message: String
) {
    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_WORKFLOW_ID, workflowId)
        putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
        putExtra(EXTRA_ACTION_TYPE, actionType)
        putExtra(EXTRA_MESSAGE, message)
    }

    companion object {
        const val ACTION_SEND_MESSAGE = "SEND_MESSAGE"

        const val EXTRA_WORKFLOW_ID = "extra_workflow_id"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_ACTION_TYPE = "extra_action_type"
        const val EXTRA_MESSAGE = "extra_message"

        /** Rebuild from the alarm intent; null when the payload is incomplete. */
        fun fromIntent(intent: Intent?): AutomationAction? {
            val current = intent ?: return null
            val targetPackage = current.getStringExtra(EXTRA_TARGET_PACKAGE) ?: return null
            val message = current.getStringExtra(EXTRA_MESSAGE) ?: return null
            val actionType = current.getStringExtra(EXTRA_ACTION_TYPE) ?: ACTION_SEND_MESSAGE
            return AutomationAction(
                workflowId = current.getLongExtra(EXTRA_WORKFLOW_ID, -1L),
                targetPackage = targetPackage,
                actionType = actionType,
                message = message
            )
        }
    }
}
