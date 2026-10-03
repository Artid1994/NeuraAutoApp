package com.example.neuraauto.service

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
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

        // System toggles don't launch apps — perform the toggle directly.
        // Phase 6.2: voice-driven system actions (Wi-Fi, Bluetooth, DND, …).
        if (action.actionType == AutomationAction.ACTION_SYSTEM_TOGGLE) {
            return performSystemToggle(context, action)
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

            // Queue a typing queue only for SEND_MESSAGE — the accessibility
            // service needs to type and tap send. OPEN_APP just launches.
            if (action.actionType == AutomationAction.ACTION_SEND_MESSAGE) {
                AutomationAccessibility.pendingAction =
                    action.copy(targetPackage = packageName)
            }

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

    // ── Phase 6.2: system toggles ─────────────────────────────────────────
    //
    // System-level actions (Wi-Fi, Bluetooth, DND, ...) don't launch an app.
    // The command lives in [AutomationAction.message] as "CODE:STATE", e.g.
    // "TOGGLE_WIFI:ON". Each helper tries the platform API and, when that is
    // permission-gated on modern Android, falls back to opening the relevant
    // system Settings screen so the user can toggle manually.

    private fun performSystemToggle(
        context: Context,
        action: AutomationAction
    ): LaunchOutcome {
        val command = action.message
        val parts = command.split(":")
        if (parts.size != 2) {
            Log.w(TAG, "Invalid system toggle command: $command")
            return LaunchOutcome.Failed("Invalid system action command: $command")
        }
        val systemAction = parts[0]
        val enable = parts[1].equals("ON", ignoreCase = true)

        return when (systemAction) {
            "TOGGLE_WIFI" -> toggleWifi(context, enable)
            "TOGGLE_BLUETOOTH" -> toggleBluetooth(context, enable)
            "TOGGLE_DND" -> toggleDnd(context, enable)
            "TOGGLE_SOUND" -> toggleSound(context, enable)
            "TOGGLE_AIRPLANE_MODE" -> toggleAirplaneMode(context, enable)
            else -> {
                Log.w(TAG, "Unknown system action: $systemAction")
                LaunchOutcome.Failed("Unknown system action: $systemAction")
            }
        }
    }

    private fun toggleWifi(context: Context, enable: Boolean): LaunchOutcome {
        return try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enable
            LaunchOutcome.Launched("Wi-Fi " + if (enable) "เปิด" else "ปิด" + " แล้ว")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot toggle Wi-Fi directly on this Android version", e)
            openSystemSettings(context, SETTING_WIFI)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi toggle failed", e)
            LaunchOutcome.Failed("Wi-Fi toggle failed: ${e.javaClass.simpleName}")
        }
    }

    private fun toggleBluetooth(context: Context, enable: Boolean): LaunchOutcome {
        return try {
            val bluetoothManager =
                context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE)
                    as BluetoothManager
            val adapter: BluetoothAdapter = bluetoothManager.adapter
            if (enable) adapter.enable() else adapter.disable()
            LaunchOutcome.Launched("บลูทูธ" + if (enable) " เปิด" else " ปิด" + " แล้ว")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot toggle Bluetooth directly", e)
            openSystemSettings(context, SETTING_BLUETOOTH)
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth toggle failed", e)
            LaunchOutcome.Failed("Bluetooth toggle failed: ${e.javaClass.simpleName}")
        }
    }

    private fun toggleDnd(context: Context, enable: Boolean): LaunchOutcome {
        return try {
            val nm =
                context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                return openSystemSettings(context, SETTING_DND)
            }
            nm.setInterruptionFilter(
                if (enable) NotificationManager.INTERRUPTION_FILTER_ALL
                else NotificationManager.INTERRUPTION_FILTER_NONE
            )
            LaunchOutcome.Launched("DND " + if (enable) "ปิด" else "เปิด" + " แล้ว")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot toggle DND directly", e)
            openSystemSettings(context, SETTING_DND)
        } catch (e: Exception) {
            Log.w(TAG, "DND toggle failed", e)
            LaunchOutcome.Failed("DND toggle failed: ${e.javaClass.simpleName}")
        }
    }

    private fun toggleSound(context: Context, enable: Boolean): LaunchOutcome {
        return try {
            val am =
                context.applicationContext.getSystemService(Context.AUDIO_SERVICE)
                    as AudioManager
            if (enable) {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            } else {
                am.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
            LaunchOutcome.Launched("เสียง" + if (enable) " เปิด" else " ปิด" + " แล้ว")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot toggle sound directly", e)
            LaunchOutcome.Failed("เสียง: ต้องให้สิทธิ์ใน Settings ก่อน")
        } catch (e: Exception) {
            Log.w(TAG, "Sound toggle failed", e)
            LaunchOutcome.Failed("Sound toggle failed: ${e.javaClass.simpleName}")
        }
    }

    private fun toggleAirplaneMode(context: Context, enable: Boolean): LaunchOutcome {
        // Airplane mode requires WRITE_SETTINGS (system-level); open settings.
        return openSystemSettings(context, SETTING_AIRPLANE)
    }

    /**
     * Open the system Settings screen identified by [settingKey].
     *
     * Used as a fallback when a direct API toggle is permission-gated.
     */
    private fun openSystemSettings(context: Context, settingKey: String): LaunchOutcome {
        return try {
            val intent = Intent(settingKey).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            LaunchOutcome.Launched("เปิดหน้าต่างตั้งค่า")
        } catch (e: Exception) {
            LaunchOutcome.Failed("Cannot open settings: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val SETTING_WIFI = Settings.ACTION_WIFI_SETTINGS
        const val SETTING_BLUETOOTH = Settings.ACTION_BLUETOOTH_SETTINGS
        const val SETTING_DND = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
        const val SETTING_AIRPLANE = Settings.ACTION_AIRPLANE_MODE_SETTINGS
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
