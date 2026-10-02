package com.example.neuraauto.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.neuraauto.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives alarm triggers and boot broadcasts.
 *
 * A BroadcastReceiver has no lifecycle, so any work that touches the database
 * is pushed onto [goAsync] with a short-lived coroutine scope and finished
 * with `pendingResult.finish()`.
 */
class SchedulerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            WorkflowScheduler.ACTION_RUN_WORKFLOW -> handleWorkflowTrigger(context, intent)
            Intent.ACTION_BOOT_COMPLETED -> handleBoot(context)
            else -> Log.d(TAG, "Ignoring unsupported action $action")
        }
    }

    /**
     * Hand the queued action to the accessibility service and open the target
     * app. The service performs the actual text injection once the app's
     * window appears.
     */
    private fun handleWorkflowTrigger(context: Context, intent: Intent) {
        val action = AutomationAction.fromIntent(intent)
        if (action == null) {
            Log.w(TAG, "Trigger intent missing payload; ignoring")
            return
        }

        // Same dispatch path the manual "Test Trigger Now" button uses.
        val dispatched = WorkflowRunner.dispatch(context, action)
        if (!dispatched) {
            Log.w(TAG, "Target package ${action.targetPackage} is not installed")
            return
        }

        // The alarm is one-shot; re-arm for the next day.
        reschedule(context, action.workflowId)
    }

    /** Alarms are cleared on reboot — restore every active workflow. */
    private fun handleBoot(context: Context) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val workflows = AppDatabase.getInstance(context)
                    .automationWorkflowDao()
                    .activeWorkflows()
                WorkflowScheduler.scheduleAll(context, workflows)
                Log.i(TAG, "Restored ${workflows.size} workflow alarm(s) after boot")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore workflows after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun reschedule(context: Context, workflowId: Long) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val workflow = AppDatabase.getInstance(context)
                    .automationWorkflowDao()
                    .byId(workflowId)
                if (workflow != null && workflow.isActive) {
                    WorkflowScheduler.schedule(context, workflow)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reschedule workflow $workflowId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SchedulerReceiver"
    }
}
