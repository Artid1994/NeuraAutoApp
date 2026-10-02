package com.example.neuraauto.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutomationAccessibility.pendingTaskAction = "SEND_LINE_MSG"
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.linecorp.line")
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launchIntent != null) {
            context.startActivity(launchIntent)
        }
    }
}
