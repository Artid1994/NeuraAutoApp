package com.example.neuraauto.data

/**
 * Groups a stream of in-app events into contiguous interaction windows.
 *
 * Two events continue the same group when they come from the same package and
 * arrive within [WINDOW_MS] of each other; anything else starts a new group.
 * The group is identified by a stable hash of `(package, windowStart)`, so rows
 * written seconds apart still line up when read back from the database.
 *
 * State is in-memory and process-scoped: it survives across accessibility
 * callbacks, which is what makes "contiguous" meaningful, and it is cheap
 * enough to run on the event thread (a couple of comparisons, no allocation
 * beyond the returned group).
 */
object ActionSequenceTracker {

    /** Gap that ends the current interaction window. */
    const val WINDOW_MS = 10_000L

    /**
     * Cap on steps per group. A long scroll session can emit hundreds of
     * clicks; only the first [MAX_STEPS_PER_GROUP] are kept so one burst cannot
     * dominate the table or the detected pattern.
     */
    const val MAX_STEPS_PER_GROUP = 20

    private var currentPackage: String? = null
    private var windowStartMillis: Long = 0L
    private var stepIndex: Int = 0
    private var lastEventAtMillis: Long = 0L

    /** Where a single event belongs. */
    data class Group(
        val sequenceGroupHash: String,
        val stepIndex: Int,
        val windowStartMillis: Long
    )

    /**
     * Classify [packageName] at time [now].
     *
     * @return the group this event belongs to, or null when the group is
     *         already full ([MAX_STEPS_PER_GROUP]) and the event should be
     *         dropped.
     */
    @Synchronized
    fun next(packageName: String, now: Long): Group? {
        val continues = packageName == currentPackage &&
            (now - lastEventAtMillis) <= WINDOW_MS

        if (!continues) {
            currentPackage = packageName
            windowStartMillis = now
            stepIndex = 0
        }

        if (stepIndex >= MAX_STEPS_PER_GROUP) {
            lastEventAtMillis = now
            return null
        }

        lastEventAtMillis = now
        val index = stepIndex++
        return Group(
            sequenceGroupHash = hashFor(packageName, windowStartMillis),
            stepIndex = index,
            windowStartMillis = windowStartMillis
        )
    }

    /** Drop the current window, e.g. when the service unbinds. */
    @Synchronized
    fun reset() {
        currentPackage = null
        windowStartMillis = 0L
        stepIndex = 0
        lastEventAtMillis = 0L
    }

    /**
     * Stable 64-bit FNV-1a hash, rendered as fixed-width hex.
     *
     * `String.hashCode()` would also be deterministic, but FNV gives a
     * fixed-width key that reads the same on every JVM and every device, which
     * matters because the value is persisted and compared across runs.
     */
    private fun hashFor(packageName: String, windowStart: Long): String {
        val input = "$packageName#$windowStart"
        var hash = FNV_OFFSET_BASIS
        for (ch in input) {
            hash = hash xor ch.code.toLong()
            hash *= FNV_PRIME
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    /** 0xcbf29ce484222325 as a signed Long. */
    private const val FNV_OFFSET_BASIS = -3750763034362895579L

    /** 0x100000001b3. */
    private const val FNV_PRIME = 1099511628211L
}
