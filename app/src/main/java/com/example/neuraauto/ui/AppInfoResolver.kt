package com.example.neuraauto.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns package names into something a human recognises.
 *
 * PackageManager lookups are binder calls that can be slow on a cold cache, so
 * results are memoised for the process lifetime. The cache is keyed by package
 * name only — labels and icons do not change while the app is running.
 *
 * Everything degrades gracefully: an unresolvable package yields a readable
 * fallback derived from the package name rather than a blank row, because the
 * dashboard should still show *something* for an app that was uninstalled
 * after its events were captured.
 */
class AppInfoResolver(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val labelCache = ConcurrentHashMap<String, String>()
    private val iconCache = ConcurrentHashMap<String, Drawable?>()
    private val resolvedCache = ConcurrentHashMap<String, Boolean>()

    /** Human-readable app name, e.g. "LINE". */
    fun labelFor(packageName: String): String =
        labelCache.getOrPut(packageName) { resolveLabel(packageName) }

    /**
     * The app's launcher icon, or null when it cannot be resolved (the caller
     * then renders a placeholder rather than a gap).
     */
    fun iconFor(packageName: String): Drawable? {
        if (iconCache.containsKey(packageName)) return iconCache[packageName]
        val icon = resolveIcon(packageName)
        iconCache[packageName] = icon
        return icon
    }

    /** True when the package is actually installed and visible to us. */
    fun isInstalled(packageName: String): Boolean =
        resolvedCache.getOrPut(packageName) { applicationInfoOrNull(packageName) != null }

    private fun resolveLabel(packageName: String): String {
        val info = applicationInfoOrNull(packageName)
        if (info != null) {
            val label = runCatching { packageManager.getApplicationLabel(info).toString() }
                .getOrNull()
            if (!label.isNullOrBlank()) return label
        }
        return fallbackLabel(packageName)
    }

    private fun resolveIcon(packageName: String): Drawable? {
        val info = applicationInfoOrNull(packageName) ?: return null
        return runCatching { packageManager.getApplicationIcon(info) }.getOrNull()
    }

    private fun applicationInfoOrNull(packageName: String): ApplicationInfo? = try {
        // 0 rather than MATCH_ALL: we only need the label and icon, and asking
        // for every component would be both slower and unnecessary.
        packageManager.getApplicationInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        // Expected for an app uninstalled after its events were captured, or
        // one hidden by Android 11+ package visibility.
        Log.d(TAG, "Package not visible: $packageName")
        null
    } catch (e: Exception) {
        Log.w(TAG, "Failed to resolve application info for $packageName", e)
        null
    }

    private companion object {
        const val TAG = "AppInfoResolver"

        /**
         * Last resort when PackageManager cannot help: `com.linecorp.line`
         * becomes `Line`. Better than showing a raw package id.
         */
        fun fallbackLabel(packageName: String): String {
            val segment = packageName.substringAfterLast('.')
            if (segment.isBlank()) return packageName
            return segment.replaceFirstChar { it.uppercase() }
        }
    }
}
