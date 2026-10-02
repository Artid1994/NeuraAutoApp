package com.example.neuraauto.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.neuraauto.data.AutomationWorkflow
import com.example.neuraauto.data.WorkflowRepository
import java.util.Calendar

/**
 * Registers one exact daily alarm per active workflow.
 *
 * Alarms do not survive a reboot, and `setExactAndAllowWhileIdle` fires only
 * once, so the schedule is re-armed from two places:
 *   - [SchedulerReceiver] after each trigger (rolls to the next day)
 *   - [SchedulerReceiver] on BOOT_COMPLETED (restores all active workflows)
 */
object WorkflowScheduler {

    private const val TAG = "WorkflowScheduler"
    const val ACTION_RUN_WORKFLOW = "com.example.neuraauto.action.RUN_WORKFLOW"

    /** Register (or replace) the alarm for [workflow]. */
    fun schedule(context: Context, workflow: AutomationWorkflow) {
        if (!workflow.isActive) {
            cancel(context, workflow)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Log.w(TAG, "AlarmManager unavailable; workflow ${workflow.id} not scheduled")
            return
        }

        val triggerAt = nextTriggerMillis(workflow)
        val pendingIntent = buildPendingIntent(context, workflow)

        try {
            if (canScheduleExact(alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                // Exact-alarm permission not granted (API 31+). Fall back to an
                // inexact alarm so the automation still runs, just less punctually.
                Log.w(TAG, "Exact alarms not permitted; using inexact alarm for ${workflow.id}")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            // Some OEM builds throw even after a canScheduleExactAlarms() check.
            Log.w(TAG, "Falling back to inexact alarm for ${workflow.id}", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context, workflow: AutomationWorkflow) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        alarmManager.cancel(buildPendingIntent(context, workflow))
    }

    fun scheduleAll(context: Context, workflows: List<AutomationWorkflow>) {
        workflows.forEach { schedule(context, it) }
    }

    /**
     * Today at the workflow's hour/minute if that is still ahead of us,
     * otherwise the same time tomorrow.
     */
    fun nextTriggerMillis(workflow: AutomationWorkflow): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            set(Calendar.HOUR_OF_DAY, workflow.scheduledHour)
            set(Calendar.MINUTE, workflow.scheduledMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    /** Request code is the workflow id, so each workflow owns one alarm slot. */
    private fun requestCode(workflow: AutomationWorkflow): Int = workflow.id.toInt()

    private fun buildPendingIntent(
        context: Context,
        workflow: AutomationWorkflow
    ): PendingIntent {
        val intent = Intent(context, SchedulerReceiver::class.java).apply {
            action = ACTION_RUN_WORKFLOW
            AutomationAction(
                workflowId = workflow.id,
                targetPackage = workflow.targetApp,
                actionType = AutomationAction.ACTION_SEND_MESSAGE,
                message = WorkflowRepository.messageOf(workflow),
                steps = WorkflowRepository.stepsFor(workflow)
            ).writeTo(this)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(workflow),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
}
