package com.example.neuraauto.safety

import android.content.Context
import com.example.neuraauto.data.AppExclusionManager
import com.example.neuraauto.service.AutomationAction

/**
 * Phase 6.0 — Unified SafetyEngine gatekeeper.
 *
 * Single authoritative enforcement point for all execution paths. Every
 * component that drives the device (WorkflowRunner, AutomationAccessibility,
 * ModelTrainingWorker) MUST call [isActionSafe] before performing any action.
 *
 * Four policy layers:
 *  - [AppPolicy]    — which apps are allowed to be automated
 *  - [DataPolicy]   — what content is safe to type/send
 *  - [ActionPolicy] — which action types are permitted
 *  - [ExecutionPolicy] — when and how actions may run
 */
object SafetyEngine {

    // ── AppPolicy ────────────────────────────────────────────────────────────

    /**
     * Check whether [packageName] is allowed to be automated.
     *
     * Rejects:
     *  - System packages (com.android.*, android)
     *  - Packages in the user's exclusion list
     *  - The app's own package
     */
    fun isAppAllowed(context: Context, packageName: String): Boolean {
        if (packageName.startsWith("com.android.") ||
            packageName.startsWith("android") ||
            packageName == context.packageName
        ) return false
        if (AppExclusionManager.isPackageExcluded(context, packageName)) return false
        return true
    }

    // ── DataPolicy ───────────────────────────────────────────────────────────

    /**
     * Check whether [message] is safe to type and send.
     *
     * Rejects:
     *  - Empty or blank messages
     *  - Messages containing password-like patterns
     *  - Messages containing financial data patterns (credit card, bank account)
     *  - Messages exceeding MAX_MESSAGE_LENGTH
     */
    fun isMessageSafe(message: String): Boolean {
        if (message.isBlank()) return false
        if (message.length > MAX_MESSAGE_LENGTH) return false
        if (containsPasswordPattern(message)) return false
        if (containsFinancialPattern(message)) return false
        return true
    }

    private fun containsPasswordPattern(message: String): Boolean {
        val lower =message.lowercase()
        return PASSWORD_PATTERNS.any { lower.contains(it) }
    }

    private fun containsFinancialPattern(message: String): Boolean {
        // Credit card: 13-19 digits, optionally spaced or dashed
        if (CREDIT_CARD_REGEX.containsMatchIn(message)) return true
        // Bank account: 10-14 digits
        if (BANK_ACCOUNT_REGEX.containsMatchIn(message)) return true
        return false
    }

    // ── ActionPolicy ─────────────────────────────────────────────────────────

    /**
     * Check whether [actionType] is a permitted action.
     *
     * Only [AutomationAction.ACTION_SEND_MESSAGE] is currently whitelisted.
     * Future action types (e.g., OPEN_APP, DISMISS_POPUP) must be explicitly
     * added here after safety review.
     */
    fun isActionTypeAllowed(actionType: String): Boolean {
        return actionType in ALLOWED_ACTION_TYPES
    }

    // ── ExecutionPolicy ──────────────────────────────────────────────────────

    /**
     * Check whether an action may be executed at this time.
     *
     * Rejects:
     *  - Expired actions (older than [AutomationAction.MAX_AGE_MILLIS])
     *  - Actions with no steps and no message (nothing to do)
     */
    fun isExecutionAllowed(action: AutomationAction): Boolean {
        if (action.isExpired()) return false
        if (action.steps.isEmpty() && action.message.isBlank()) return false
        return true
    }

    // ── Unified gate ─────────────────────────────────────────────────────────

    /**
     * Evaluate all four policy layers for [action].
     *
     * This is the single entry point that all execution paths must call.
     * Returns a [SafetyDecision] with the verdict and a human-readable reason.
     */
    fun isActionSafe(context: Context, action: AutomationAction): SafetyDecision {
        // AppPolicy
        if (!isAppAllowed(context, action.targetPackage)) {
            return SafetyDecision.Unsafe(
                reason = "App policy violation: ${action.targetPackage} is not allowed"
            )
        }

        // ActionPolicy
        if (!isActionTypeAllowed(action.actionType)) {
            return SafetyDecision.Unsafe(
                reason = "Action policy violation: ${action.actionType} is not permitted"
            )
        }

        // DataPolicy
        if (!isMessageSafe(action.message)) {
            return SafetyDecision.Unsafe(
                reason = "Data policy violation: message content is not safe"
            )
        }

        // ExecutionPolicy
        if (!isExecutionAllowed(action)) {
            return SafetyDecision.Unsafe(
                reason = "Execution policy violation: action is expired or empty"
            )
        }

        return SafetyDecision.Safe
    }

    // ── Types ────────────────────────────────────────────────────────────────

    sealed class SafetyDecision {
        object Safe : SafetyDecision()
        data class Unsafe(val reason: String) : SafetyDecision()

        val isSafe: Boolean get() = this is Safe
    }

    // ── Constants ────────────────────────────────────────────────────────────

    private const val MAX_MESSAGE_LENGTH = 2000

    private val PASSWORD_PATTERNS = listOf(
        "password", "passwd", "pwd",
        "รหัสผ่าน", "รหัส",
        "passcode", "pin code", "otp",
        "cvv", "cvc", "security code"
    )

    private val CREDIT_CARD_REGEX = Regex(
        "\\b(?:\\d[ -]?){13,19}\\b"
    )

    private val BANK_ACCOUNT_REGEX = Regex(
        "\\b\\d{10,14}\\b"
    )

    private val ALLOWED_ACTION_TYPES = setOf(
        AutomationAction.ACTION_SEND_MESSAGE
    )
}
