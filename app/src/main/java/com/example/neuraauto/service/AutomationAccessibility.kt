package com.example.neuraauto.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.neuraauto.brain.ActionStepKind
import com.example.neuraauto.data.ActionSequenceTracker
import com.example.neuraauto.data.ActivityRecorder
import com.example.neuraauto.data.AmbientState
import com.example.neuraauto.data.AppDatabase
import com.example.neuraauto.data.AppExclusionManager
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

    /** Position within [AutomationAction.steps] for a multi-step flow. */
    private var stepCursor = 0

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
     * Execute the queued action: either the learned multi-step flow or the
     * legacy open-app / type / send path.
     */
    private fun performSendMessage(action: AutomationAction) {
        val rootNode = rootInActiveWindow ?: return

        if (action.steps.isNotEmpty()) {
            performSteps(action, rootNode)
            return
        }

        val textSet = setTextOnInput(action, rootNode)
        if (!textSet) return

        val clicked = clickSend(action, rootNode)
        if (clicked) {
            Log.i(TAG, "Executed workflow ${action.workflowId} for ${action.targetPackage}")
            finishAction()
        }
    }

    /**
     * Walk the learned step list in order.
     *
     * The cursor advances only after a step has actually been performed, so a
     * step whose node has not appeared yet is retried on the next window event
     * (bounded by [MAX_ACTION_ATTEMPTS]) rather than being skipped or repeated.
     */
    private fun performSteps(action: AutomationAction, rootNode: AccessibilityNodeInfo) {
        if (stepCursor >= action.steps.size) {
            finishAction()
            return
        }

        val kind = action.steps[stepCursor]
        val performed = when (kind) {
            ActionStepKind.TYPE_TEXT.name -> setTextOnInput(action, rootNode)

            ActionStepKind.CLICK_SEND.name -> clickSend(action, rootNode)

            // Locating the input field is folded into the TYPE_TEXT step, and
            // opening the app already happened in WorkflowRunner.
            ActionStepKind.CLICK_INPUT.name,
            ActionStepKind.OPEN_APP.name -> true

            else -> {
                Log.w(TAG, "Unknown step '$kind'; skipping")
                true
            }
        }

        if (!performed) {
            Log.d(TAG, "Step $kind not ready yet; will retry on next window event")
            return
        }

        stepCursor++
        Log.d(TAG, "Step $kind done (${stepCursor}/${action.steps.size})")

        if (stepCursor >= action.steps.size) {
            Log.i(
                TAG,
                "Executed ${action.steps.size}-step workflow ${action.workflowId} " +
                    "for ${action.targetPackage}"
            )
            finishAction()
        }
    }

    /** Inject the message into the target app's input field. */
    private fun setTextOnInput(
        action: AutomationAction,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        val inputNodes = findInputNodes(rootNode, action.targetPackage)
        if (inputNodes.isEmpty()) return false

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                action.message
            )
        }
        val textSet = inputNodes.first().performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        )
        if (!textSet) {
            Log.w(TAG, "ACTION_SET_TEXT rejected for ${action.targetPackage}")
        }
        return textSet
    }

    /** Activate the send button. */
    private fun clickSend(
        action: AutomationAction,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        val sendButton = findSendButton(rootNode, action.targetPackage)
        if (sendButton == null) {
            Log.d(TAG, "Send button not found yet for ${action.targetPackage}")
            return false
        }
        val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            Log.w(TAG, "Send button click rejected for ${action.targetPackage}")
        }
        return clicked
    }

    /** The workflow finished; return the user to where they were. */
    private fun finishAction() {
        clearPendingAction()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Locate the message input. Uses the known view id when present, otherwise
     * falls back to intent-based matching (text/contentDescription such as
     * "พิมพ์" / "type"), and finally to the first editable, non-password field
     * in the hierarchy so the engine is not hard-wired to one app's internal
     * resource names.
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

        // Phase 4.0 — view id failed; try to resolve the field by intent.
        val byIntent = SmartNodeFinder.findInputByIntent(rootNode)
        if (byIntent != null) {
            Log.i(TAG, "Resolved input via SmartNodeFinder for $targetPackage")
            return listOf(byIntent)
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

        // Phase 4.0 — view id failed; match on what the button says.
        val byIntent = SmartNodeFinder.findByIntent(rootNode, SmartNodeFinder.SEND_INTENTS)
        if (byIntent != null) {
            Log.i(TAG, "Resolved send button via SmartNodeFinder for $targetPackage")
        }
        return byIntent
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
        stepCursor = 0
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
        if (AppExclusionManager.isPackageExcluded(applicationContext, packageName)) return

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
        if (AppExclusionManager.isPackageExcluded(applicationContext, packageName)) return

        ActivityRecorder.record(dao, packageName) { readAmbientState() }
    }

    /**
     * Snapshot the ambient conditions for a capture. Read on the recorder's
     * IO thread — each field is a system-service binder call.
     *
     * Wi-Fi SSID is best-effort: Android 8.1+ requires a granted location
     * permission and Android 13+ also requires location services to be on.
     * A refused read yields null, which the feature hasher treats as its own
     * "unknown network" bucket rather than an error.
     */
    private fun readAmbientState(): AmbientState {
        val batteryManager =
            getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val isCharging = batteryManager?.isCharging ?: false
        val batteryPercent = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "battery capacity unavailable", e)
            -1
        }

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val isWifiConnected = capabilities
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            ?: false

        val wifiSsid = if (isWifiConnected) readWifiSsid() else null

        return AmbientState(
            isCharging = isCharging,
            isWifiConnected = isWifiConnected,
            wifiSsid = wifiSsid,
            batteryPercent = batteryPercent
        )
    }

    /**
     * Best-effort SSID read.
     *
     * Returns null rather than throwing when the platform withholds the value
     * (missing location permission, location services off, or an SSID the
     * system redacts as "<unknown ssid>").
     */
    private fun readWifiSsid(): String? = try {
        val wifiManager =
            applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val raw = wifiManager?.connectionInfo?.ssid
            ?.removeSurrounding("\"")
            ?.trim()
        if (raw.isNullOrBlank() || raw == "<unknown ssid>") null else raw
    } catch (e: Exception) {
        // SecurityException when location is not granted; treat as unknown.
        Log.d(TAG, "SSID unavailable: ${e.javaClass.simpleName}")
        null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        clearPendingAction()
        // End the current interaction window so a group cannot span a service
        // restart (which could be hours later).
        ActionSequenceTracker.reset()
        return super.onUnbind(intent)
    }

    // ── Phase 3.3: gesture primitives ───────────────────────────────────────

    /**
     * Perform a swipe gesture on the screen.
     *
     * @param startX Start X coordinate in pixels.
     * @param startY Start Y coordinate in pixels.
     * @param endX End X coordinate in pixels.
     * @param endY End Y coordinate in pixels.
     * @param durationMs Duration of the gesture in milliseconds.
     * @return true if the gesture was dispatched.
     */
    fun performSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 300L
    ): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val builder = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
        return dispatchGesture(builder.build(), null, null)
    }

    /**
     * Tap at a screen coordinate.
     *
     * @param x X coordinate in pixels.
     * @param y Y coordinate in pixels.
     * @return true if the gesture was dispatched.
     */
    fun performTap(x: Int, y: Int): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val builder = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
        return dispatchGesture(builder.build(), null, null)
    }

    override fun onInterrupt() {}
}
