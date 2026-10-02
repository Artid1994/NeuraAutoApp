package com.example.neuraauto.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.neuraauto.data.ActivityRecorder
import com.example.neuraauto.data.AppDatabase

class AutomationAccessibility : AccessibilityService() {

    companion object {
        var instance: AutomationAccessibility? = null
            private set
        var pendingTaskAction: String? = null
    }

    private val dao by lazy { AppDatabase.getInstance(applicationContext).userActivityDao() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentEvent = event ?: return

        // Phase 2 data collection — independent of any pending automation task,
        // so it runs before the early returns below.
        if (currentEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            captureForegroundChange(currentEvent)
        }

        // Automation only runs when a task is actually queued; skipping the
        // root-node lookup otherwise keeps this callback cheap.
        if (pendingTaskAction != "SEND_LINE_MSG") return

        val rootNode = rootInActiveWindow ?: return
        executeLineWorkflow(rootNode)
    }

    /**
     * Log which app the user moved to, together with the ambient conditions at
     * that moment. Written off the main thread by [ActivityRecorder]; failures
     * are swallowed there so the service survives.
     */
    private fun captureForegroundChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        // Ignore our own UI and system chrome — only third-party app switches
        // are interesting for routine detection.
        if (packageName == applicationContext.packageName) return
        if (packageName == "com.android.systemui") return

        ActivityRecorder.record(dao, packageName) { readAmbientState() }
    }

    /** @return (isCharging, isWifiConnected). Read on the recorder's IO thread. */
    private fun readAmbientState(): Pair<Boolean, Boolean> {
        val batteryManager =
            getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val isCharging = batteryManager?.isCharging ?: false

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val isWifiConnected = capabilities
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            ?: false

        return isCharging to isWifiConnected
    }

    private fun executeLineWorkflow(rootNode: AccessibilityNodeInfo) {
        val inputNodes = rootNode.findAccessibilityNodeInfosByViewId("com.linecorp.line:id/chat_ui_input_edit_text")
        if (!inputNodes.isNullOrEmpty()) {
            val inputNode = inputNodes[0]
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "สวัสดีครับ ข้อความนี้ถูกส่งโดย NeuraAuto AI"
                )
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            val sendButtons = rootNode.findAccessibilityNodeInfosByViewId("com.linecorp.line:id/chat_ui_send_button")
            if (!sendButtons.isNullOrEmpty()) {
                sendButtons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingTaskAction = null
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}
}
