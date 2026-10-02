package com.example.neuraauto.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Owns the "observe an in-app interaction, persist a row" concern.
 *
 * Mirrors [ActivityRecorder]: the accessibility service stays a thin event
 * source, and the storage rule lives in exactly one place. All values are
 * extracted from the node *before* this call, so the caller is free to recycle
 * it immediately.
 */
object InAppActionRecorder {

    private const val TAG = "InAppActionRecorder"

    /** Application-scoped: outlives any single service callback. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Characters kept from captured text. */
    const val MAX_TEXT_SNIPPET = 60

    /** How long captured interactions are retained before pruning. */
    private const val RETENTION_DAYS = 14

    /** Prune roughly once per this many writes rather than on every insert. */
    private const val PRUNE_INTERVAL = 100

    private var writesSincePrune = 0

    /**
     * Persist one interaction.
     *
     * @param rawText typed text for TEXT_CHANGE events; ignored for clicks.
     */
    fun record(
        dao: InAppActionDao,
        packageName: String,
        eventType: String,
        viewId: String?,
        rawText: String?
    ) {
        val now = System.currentTimeMillis()
        val group = ActionSequenceTracker.next(packageName, now) ?: return

        scope.launch {
            try {
                val calendar = Calendar.getInstance().apply { timeInMillis = now }
                val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
                // Calendar.SUNDAY == 1; normalise to ISO where Monday == 1.
                val calendarDay = calendar.get(Calendar.DAY_OF_WEEK)
                val dayOfWeek = if (calendarDay == Calendar.SUNDAY) 7 else calendarDay - 1

                dao.insert(
                    InAppActionLog(
                        packageName = packageName,
                        eventType = eventType,
                        viewId = viewId,
                        textSnippet = sanitizeText(rawText),
                        sequenceGroupHash = group.sequenceGroupHash,
                        stepIndex = group.stepIndex,
                        timestampMillis = now,
                        hourOfDay = hourOfDay,
                        dayOfWeek = dayOfWeek
                    )
                )

                maybePrune(dao, now)
            } catch (e: Exception) {
                // Logging must never crash the accessibility service.
                Log.w(TAG, "Failed to record interaction in $packageName", e)
            }
        }
    }

    /**
     * Collapse whitespace and truncate.
     *
     * Typed text can contain newlines and runs of spaces that carry no meaning
     * for pattern detection but inflate the row; the ellipsis makes it visible
     * that the stored value is an excerpt, not the whole message.
     */
    fun sanitizeText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val collapsed = raw.replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length <= MAX_TEXT_SNIPPET) {
            collapsed
        } else {
            collapsed.take(MAX_TEXT_SNIPPET) + "…"
        }
    }

    private suspend fun maybePrune(dao: InAppActionDao, now: Long) {
        writesSincePrune++
        if (writesSincePrune < PRUNE_INTERVAL) return
        writesSincePrune = 0
        val cutoff = now - RETENTION_DAYS * 24L * 60L * 60L * 1000L
        val removed = dao.deleteOlderThan(cutoff)
        if (removed > 0) Log.i(TAG, "Pruned $removed interaction rows older than $RETENTION_DAYS days")
    }

    private val WHITESPACE = Regex("\\s+")
}
