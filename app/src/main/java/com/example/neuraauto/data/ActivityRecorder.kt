package com.example.neuraauto.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Owns the "observe an app change, persist a row" concern.
 *
 * Kept separate from [com.example.neuraauto.service.AutomationAccessibility] so
 * the service stays a thin event source and the storage rule is testable and
 * has exactly one implementation.
 */
object ActivityRecorder {

    private const val TAG = "ActivityRecorder"

    /** Application-scoped: outlives any single service callback. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Persist one foreground event, at most once per (package, hour, weekday).
     *
     * @param ambientState supplier of (isCharging, isWifiConnected), evaluated
     *        on the IO dispatcher so the caller is never blocked by a
     *        system-service binder call.
     */
    fun record(
        dao: UserActivityDao,
        packageName: String,
        ambientState: () -> Pair<Boolean, Boolean>
    ) {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val calendar = Calendar.getInstance().apply { timeInMillis = now }
                val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
                // Calendar.SUNDAY == 1; normalise to ISO where Monday == 1.
                val calendarDay = calendar.get(Calendar.DAY_OF_WEEK)
                val dayOfWeek = if (calendarDay == Calendar.SUNDAY) 7 else calendarDay - 1

                if (dao.countFor(packageName, hourOfDay, dayOfWeek) > 0) return@launch

                val (isCharging, isWifiConnected) = ambientState()
                dao.insert(
                    UserActivityLog(
                        packageName = packageName,
                        timestampMillis = now,
                        hourOfDay = hourOfDay,
                        dayOfWeek = dayOfWeek,
                        isCharging = isCharging,
                        isWifiConnected = isWifiConnected
                    )
                )
            } catch (e: Exception) {
                // Logging must never crash the accessibility service.
                Log.w(TAG, "Failed to record activity for $packageName", e)
            }
        }
    }
}
