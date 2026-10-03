package com.example.neuraauto.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.example.neuraauto.safety.SafetyEngine

/**
 * Outcome of a launch attempt, carrying a message suitable for display in the
 * dashboard's diagnostic banner.
 */
sealed class LaunchOutcome {
    abstract val message: String

    /** A launcher activity was resolved and started. */
    data class Launched(override val message: String) : LaunchOutcome()

    /**
     * No launcher activity could be resolved. On API 30+ this also covers a
     * target that IS installed but not visible to us because it is missing
     * from the manifest `<queries>` block.
     */
    data class NotResolved(override val message: String) : LaunchOutcome()

    /** The stored package name failed validation. */
    data class InvalidPackage(override val message: String) : LaunchOutcome()

    /** Resolution succeeded but startActivity threw. */
    data class Failed(override val message: String) : LaunchOutcome()

    val succeeded: Boolean get() = this is Launched
}

/**
 * Shared hand-off from "something decided to run a workflow" to "the device is
 * actually driven".
 *
 * Both the alarm path ([SchedulerReceiver]) and the manual "Test Trigger Now"
 * button go through here, so the test exercises the same code as production
 * rather than a parallel implementation that could drift.
 */
object WorkflowRunner {

    private const val TAG = "WorkflowRunner"

    /**
     * A conservative Android package-name check: at least two segments, each
     * starting with a letter.
     *
     * Values reach us from the database and from alarm intents, so a malformed
     * string (leading/trailing space, a stray newline, a display label) must be
     * rejected before it is handed to PackageManager.
     */
    private val PACKAGE_PATTERN =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    /**
     * Trim and validate a raw package name.
     *
     * @return the cleaned name, or null when it cannot be a package name.
     */
    fun sanitizePackageName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (PACKAGE_PATTERN.matches(trimmed)) trimmed else null
    }

    /**
     * Queue [action] for the accessibility service and bring the target app to
     * the foreground.
     *
     * Nothing is queued unless a launcher activity is successfully started, so a
     * failed launch cannot leave a stale action behind that would fire later.
     *
     * Context-aware skip: if the target app is already in the foreground,
     * execution is skipped gracefully to avoid interrupting the active user.
     */
    fun dispatch(context: Context, action: AutomationAction): LaunchOutcome {
        // SafetyEngine gate — unified safety check before any execution
        val safetyDecision = SafetyEngine.isActionSafe(context, action)
        if (!safetyDecision.isSafe) {
            Log.w(TAG, "SafetyEngine blocked dispatch: ${(safetyDecision as SafetyEngine.SafetyDecision.Unsafe).reason}")
            return LaunchOutcome.Failed("Safety check failed: ${(safetyDecision as SafetyEngine.SafetyDecision.Unsafe).reason}")
        }

        val rawPackage = action.targetPackage
        val packageName = sanitizePackageName(rawPackage)
            ?: return LaunchOutcome.InvalidPackage(
                "Invalid package name: '$rawPackage'"
            )

        // Context-aware skip: if the target app is already in the foreground,
        // skip execution gracefully to avoid interrupting the active user.
        if (isAppInForeground(context, packageName)) {
            Log.i(TAG, "Skipping dispatch: $packageName is already in the foreground")
            return LaunchOutcome.Launched(
                "Skipped: $packageName is already in the foreground"
            )
        }

        val launchIntent = resolveLaunchIntent(context.packageManager, packageName)
            ?: return LaunchOutcome.NotResolved(
                "Package not found: $packageName " +
                    "(not installed, or not visible — check <queries> in AndroidManifest)"
            )

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)

            // Queue only after the launch actually succeeded.
            AutomationAccessibility.pendingAction = action.copy(targetPackage = packageName)

            LaunchOutcome.Launched(
                "Target app $packageName found and launched successfully"
            )
        } catch (e: Exception) {
            Log.w(TAG, "startActivity failed for $packageName", e)
            LaunchOutcome.Failed("Failed to launch $packageName: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Check whether the given package is currently in the foreground.
     *
     * Uses AccessibilityService to get the current window's package name.
     * Returns false if the service is not available or the package cannot
     * be determined.
     */
    private fun isAppInForeground(context: Context, packageName: String): Boolean {
        val service = AutomationAccessibility.instance ?: return false
        return try {
            val rootNode = service.rootInActiveWindow ?: return false
            val currentPackage = rootNode.packageName?.toString() ?: return false
            currentPackage == packageName
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check foreground package", e)
            false
        }
    }

    /**
     * Show the 10-second countdown, then dispatch if the user does not intervene.
     *
     * This is the path taken by alarm-triggered executions. The manual
     * "Test Trigger Now" button calls [dispatch] directly, since the user has
     * already chosen to run the workflow.
     */
    fun dispatchWithCountdown(
        context: Context,
        action: AutomationAction
    ) {
        com.example.neuraauto.ui.ExecutionCountdownActivity.start(context, action)
    }

    /**
     * Resolve a launcher intent for [packageName].
     *
     * `getLaunchIntentForPackage` is the normal route. Some apps do not expose a
     * CATEGORY_INFO/LAUNCHER entry in the form that method expects, so fall back
     * to an explicit MAIN/LAUNCHER query and build a component intent from the
     * resolved activity.
     */
    private fun resolveLaunchIntent(
        packageManager: PackageManager,
        packageName: String
    ): Intent? {
        packageManager.getLaunchIntentForPackage(packageName)?.let { return it }

        val queryIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val resolved = try {
            packageManager.queryIntentActivities(queryIntent, 0)
        } catch (e: Exception) {
            Log.w(TAG, "queryIntentActivities failed for $packageName", e)
            null
        }
        val first = resolved?.firstOrNull() ?: return null

        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(packageName, first.activityInfo.name)
        }
    }
}
