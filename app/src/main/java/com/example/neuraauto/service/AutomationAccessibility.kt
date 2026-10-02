package com.example.neuraauto.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.neuraauto.data.ActionSequenceTracker
import com.example.neuraauto.data.ActivityRecorder
import com.example.neuraauto.data.AppDatabase
import com.example.neuraauto.data.InAppActionLog
import com.example.neuraauto.data.InAppActionRecorder

/**
 * Single place where the device is actually driven.
 *
 * Two responsibilities, deliberately separated:
 *  1. Phase 2 — record app-foreground changes (always on).
 *  2. Phase 3 — execute a queued [AutomationAction] (only when one is pending).
 */
class AutomationAccessibility : AccessibilityService() {

    companion object {
        var instance: AutomationAccessibility? = null
            private set

        /**
         * Work handed over by [SchedulerReceiver]. Volatile because it is
         * written on the receiver's thread and read on the service's.
         */
        @Volatile
        var pendingAction: AutomationAction? = null

        private const val TAG = "AutomationAccessibility"
        private const val LINE_PACKAGE = "com.linecorp.line"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val MAX_ACTION_ATTEMPTS = 10
    }

    private val dao by lazy { AppDatabase.getInstance(applicationContext).userActivityDao() }

    private val actionDao by lazy {
        AppDatabase.getInstance(applicationContext).inAppActionDao()
    }

    /** How many window events an action may consume before it is abandoned. */
    private var actionAttempts = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentEvent = event ?: return

        when (currentEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                captureForegroundChange(currentEvent)
                tryExecutePendingAction(currentEvent)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                captureInteraction(currentEvent, InAppActionLog.EVENT_CLICK)

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                captureInteraction(currentEvent, InAppActionLog.EVENT_TEXT_CHANGE)
        }
    }

    // ── Phase 3: dynamic execution ───────────────────────────────────────────

    /**
     * Attempts the queued action whenever the foreground window belongs to its
     * target package. Called on every window-state change; [actionAttempts]
     * bounds the retries so a target that never exposes the expected input
     * field cannot loop forever.
     */
    private fun tryExecutePendingAction(event: AccessibilityEvent) {
        val action = pendingAction ?: return

        // Never replay a stale alarm: the accessibility service may have been
        // disconnected when it fired, and executing it much later would drive
        // the UI at an unintended moment.
        if (action.isExpired()) {
            Log.w(TAG, "Discarding expired action for ${action.targetPackage}")
            clearPendingAction()
            return
        }

        val foregroundPackage = event.packageName?.toString() ?: return
        if (foregroundPackage != action.targetPackage) return

        if (actionAttempts >= MAX_ACTION_ATTEMPTS) {
            Log.w(TAG, "Abandoning action for ${action.targetPackage} after $actionAttempts attempts")
            clearPendingAction()
            return
        }
        actionAttempts++

        when (action.actionType) {
            AutomationAction.ACTION_SEND_MESSAGE -> performSendMessage(action)
            else -> {
                Log.w(TAG, "Unsupported actionType ${action.actionType}")
                clearPendingAction()
            }
        }
    }

    /**
     * Inject [AutomationAction.message] into the target app's input field and
     * click send. Only clears the pending action once the send button has
     * actually been activated.
     */
    private fun performSendMessage(action: AutomationAction) {
        val rootNode = rootInActiveWindow ?: return

        val inputNodes = findInputNodes(rootNode, action.targetPackage)
        if (inputNodes.isEmpty()) return

        val inputNode = inputNodes.first()
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                action.message
            )
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!textSet) {
            Log.w(TAG, "ACTION_SET_TEXT rejected for ${action.targetPackage}")
            return
        }

        val sendButton = findSendButton(rootNode, action.targetPackage)
        if (sendButton == null) {
            Log.d(TAG, "Send button not found yet; will retry on next window event")
            return
        }

        val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Log.i(TAG, "Executed workflow ${action.workflowId} for ${action.targetPackage}")
            clearPendingAction()
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            Log.w(TAG, "Send button click rejected for ${action.targetPackage}")
        }
    }

    /**
     * Locate the message input. Uses the known view id when present, otherwise
     * falls back to the first editable, non-password field in the hierarchy so
     * the engine is not hard-wired to one app's internal resource names.
     */
    private fun findInputNodes(
        rootNode: AccessibilityNodeInfo,
        targetPackage: String
    ): List<AccessibilityNodeInfo> {
        val knownIds = knownInputViewIds(targetPackage)
        for (viewId in knownIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) return nodes
        }
        val fallback = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(rootNode, fallback)
        return fallback
    }

    private fun findSendButton(
        rootNode: AccessibilityNodeInfo,
        targetPackage: String
    ): AccessibilityNodeInfo? {
        for (viewId in knownSendViewIds(targetPackage)) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) return nodes.first()
        }
        return null
    }

    /** View ids known to work for supported targets. */
    private fun knownInputViewIds(targetPackage: String): List<String> = when (targetPackage) {
        LINE_PACKAGE -> listOf("com.linecorp.line:id/chat_ui_input_edit_text")
        else -> emptyList()
    }

    private fun knownSendViewIds(targetPackage: String): List<String> = when (targetPackage) {
        LINE_PACKAGE -> listOf("com.linecorp.line:id/chat_ui_send_button")
        else -> emptyList()
    }

    private fun collectEditableNodes(
        node: AccessibilityNodeInfo,
        sink: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isEditable && !node.isPassword) sink.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditableNodes(child, sink)
        }
    }

    private fun clearPendingAction() {
        pendingAction = null
        actionAttempts = 0
    }

    // ── Phase 3.1: in-app interaction capture ────────────────────────────────

    /**
     * Record one click or text edit.
     *
     * Everything is read from the event/node up front because the accessibility
     * framework may recycle the source node as soon as this callback returns.
     */
    private fun captureInteraction(event: AccessibilityEvent, eventType: String) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return
        if (packageName == SYSTEM_UI_PACKAGE) return

        // Do not learn from our own automation. While an action is pending for
        // this package, the events being emitted are the ones WE caused; storing
        // them would feed the detector its own output and reinforce the pattern
        // it just executed.
        if (pendingAction?.targetPackage == packageName) return

        // Never capture anything from a password field.
        if (event.isPassword) return

        val source = event.source
        val viewId = try {
            source?.viewIdResourceName
        } catch (e: Exception) {
            Log.w(TAG, "viewIdResourceName unavailable", e)
            null
        }

        val rawText = if (eventType == InAppActionLog.EVENT_TEXT_CHANGE) {
            // AccessibilityEvent.text is the full field contents, not the delta.
            try {
                event.text?.lastOrNull()?.toString()
            } catch (e: Exception) {
                Log.w(TAG, "text read failed", e)
                null
            }
        } else {
            null
        }

        InAppActionRecorder.record(
            dao = actionDao,
            packageName = packageName,
            eventType = eventType,
            viewId = viewId,
            rawText = rawText
        )
    }

    // ── Phase 2: activity capture ────────────────────────────────────────────

    private fun captureForegroundChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return
        if (packageName == SYSTEM_UI_PACKAGE) return

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

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        clearPendingAction()
        // End the current interaction window so a group cannot span a service
        // restart (which could be hours later).
        ActionSequenceTracker.reset()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}
}
