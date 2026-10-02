package com.example.neuraauto.service

import android.content.Context
import android.content.Intent

/**
 * Shared hand-off from "something decided to run a workflow" to "the device is
 * actually driven".
 *
 * Both the alarm path ([SchedulerReceiver]) and the manual "Test Trigger Now"
 * button go through here, so the test exercises the same code as production
 * rather than a parallel implementation that could drift.
 */
object WorkflowRunner {

    /**
     * Queue [action] for the accessibility service and bring the target app to
     * the foreground.
     *
     * @return false when the target app is not installed, in which case nothing
     *         was queued.
     */
    fun dispatch(context: Context, action: AutomationAction): Boolean {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(action.targetPackage)
            ?: return false

        // Queue only once we know the app can actually be opened, so a failed
        // launch does not leave a stale action behind.
        AutomationAccessibility.pendingAction = action

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }
}
