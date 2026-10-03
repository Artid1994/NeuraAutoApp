package com.example.neuraauto.voice

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.neuraauto.safety.SafetyEngine
import com.example.neuraauto.service.AutomationAction
import com.example.neuraauto.service.LaunchOutcome
import com.example.neuraauto.service.SmartNodeFinder
import com.example.neuraauto.service.WorkflowRunner

/**
 * Outcome of executing a voice intent.
 */
sealed class VoiceActionResult {
    /** The intent was parsed, passed the safety gate, and dispatched. */
    data class Success(val message: String) : VoiceActionResult()

    /** SafetyEngine blocked the action. */
    data class Blocked(val reason: String) : VoiceActionResult()

    /** The action could not be completed (e.g., app not installed, permission denied). */
    data class Error(val message: String) : VoiceActionResult()

    /** Human-readable result for display in the dashboard banner. */
    val displayMessage: String
        get() = when (this) {
            is Success -> "✓ $message"
            is Blocked -> "🚫 $reason"
            is Error -> "⚠️ $message"
        }
}

/**
 * Phase 6.2 — Voice Intent Bridge.
 *
 * Converts a [ParsedIntent] from [ThaiIntentParser] into an [AutomationAction],
 * then routes it through [SafetyEngine.isActionSafe] and onto
 * [WorkflowRunner.dispatch] — the same execution pipeline used by scheduled
 * alarms and manual test triggers.
 *
 * The bridge never bypasses the safety gate: every voice-derived action must
 * pass [SafetyEngine] before reaching the device-driving layer, exactly like
 * any other automation.
 *
 * SYSTEM_ACTION intents are handled specially — they do not launch an app, so
 * the bridge dispatches them through a dedicated toggle path inside
 * [WorkflowRunner] rather than the app-launch path.
 */
object VoiceIntentBridge {

    private const val TAG = "VoiceIntentBridge"

    /**
     * Convert [intent] into an [AutomationAction] without executing it.
     *
     * The returned action is ready for [SafetyEngine.isActionSafe] and
     * [WorkflowRunner.dispatch]. For SEND_MESSAGE the app name is resolved to
     * a package via [ThaiIntentParser.resolvePackage]; when the name is
     * unrecognised the raw text is passed through so WorkflowRunner can report
     * a clear "package not found" error.
     */
    fun toAction(intent: ParsedIntent): AutomationAction {
        val (actionType, targetPackage, message) = when (intent.type) {
            IntentType.SEND_MESSAGE -> {
                val pkg = ThaiIntentParser.resolvePackage(intent.appName) ?: intent.appName
                Triple(
                    AutomationAction.ACTION_SEND_MESSAGE,
                    pkg,
                    intent.payload
                )
            }

            IntentType.OPEN_APP -> {
                val pkg = ThaiIntentParser.resolvePackage(intent.appName) ?: intent.appName
                Triple(
                    AutomationAction.ACTION_OPEN_APP,
                    pkg,
                    ""
                )
            }

            IntentType.SYSTEM_ACTION -> Triple(
                AutomationAction.ACTION_SYSTEM_TOGGLE,
                "",
                intent.payload  // e.g. "TOGGLE_WIFI:ON"
            )
        }

        return AutomationAction(
            workflowId = -1L,  // one-off voice action, not tied to a stored workflow
            targetPackage = targetPackage,
            actionType = actionType,
            message = message,
            steps = emptyList()
        )
    }

    /**
     * Execute a parsed voice intent end-to-end.
     *
     * Steps:
     *  1. Convert to [AutomationAction] via [toAction].
     *  2. Pass through [SafetyEngine.isActionSafe].
     *  3. If safe, dispatch via [WorkflowRunner.dispatch].
     *
     * Returns a [VoiceActionResult] suitable for display in the dashboard.
     */
    fun execute(context: Context, intent: ParsedIntent): VoiceActionResult {
        if (intent.type != IntentType.SYSTEM_ACTION) {
            val packageName = ThaiIntentParser.resolvePackage(intent.appName)
            if (packageName == null) {
                return VoiceActionResult.Error(
                    "ไม่พบแอป \"${intent.appName}\" ในรายการที่รองรับ"
                )
            }
        }

        val action = toAction(intent)

        // ── SafetyEngine gate ──────────────────────────────────────────
        // Every voice command must pass the same safety check as any other
        // automation before reaching the device-driving layer.
        val safetyDecision = SafetyEngine.isActionSafe(context, action)
        if (!safetyDecision.isSafe) {
            val reason = (safetyDecision as SafetyEngine.SafetyDecision.Unsafe).reason
            Log.w(TAG, "SafetyEngine blocked voice intent: $reason")
            return VoiceActionResult.Blocked(reason)
        }

        // ── Execute ────────────────────────────────────────────────────
        // System toggles are performed directly (no app launch); all other
        // action types go through the standard dispatch path.
        return try {
            val outcome = WorkflowRunner.dispatch(context, action)
            if (outcome.succeeded) {
                VoiceActionResult.Success(outcome.message)
            } else {
                VoiceActionResult.Error(outcome.message)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voice intent execution failed", e)
            VoiceActionResult.Error(
                "เกิดข้อผิดพลาด: ${e.javaClass.simpleName}"
            )
        }
    }
}
