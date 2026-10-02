package com.example.neuraauto.brain

import com.example.neuraauto.data.InAppActionLog
import com.example.neuraauto.data.UserActivityLog

/**
 * A recurring behaviour the engine believes it has detected.
 *
 * @param packageName  app involved in the routine.
 * @param hourOfDay    hour of day the routine occurs at.
 * @param confidence   0.0..1.0, see [NeuralBrainEngine.detectRoutines].
 * @param supportDays  number of distinct weekdays the routine was observed on.
 * @param observedDays total distinct weekdays present in the log.
 */
data class RoutinePattern(
    val packageName: String,
    val hourOfDay: Int,
    val confidence: Float,
    val supportDays: Int,
    val observedDays: Int
) {
    val confidencePercent: Int get() = (confidence * 100).toInt()
}

/** Everything the dashboard needs for one analysis pass. */
data class BrainAnalysis(
    val totalLogs: Int,
    val analyzedLogs: Int,
    val routines: List<RoutinePattern>
) {
    val topRoutine: RoutinePattern? get() = routines.firstOrNull()
}

/**
 * On-device routine detector.
 *
 * The scoring rule is deliberately simple and inspectable: a routine is a
 * `(package, hour)` pair and its confidence is the fraction of *observed*
 * weekdays on which that pair actually occurred. A pair seen on 5 of 5
 * observed days scores 1.0; one seen on 4 of 5 scores 0.8.
 *
 * This is a frequency estimate over real stored events. It is not a trained
 * neural network, and the class name should not be read as claiming one.
 */
class NeuralBrainEngine {

    /**
     * @param logs rows from [com.example.neuraauto.data.UserActivityDao.recentLogs].
     * @param confidenceThreshold routines at or above this score are reported.
     */
    fun detectRoutines(
        logs: List<UserActivityLog>,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
    ): BrainAnalysis {
        if (logs.isEmpty()) {
            return BrainAnalysis(totalLogs = 0, analyzedLogs = 0, routines = emptyList())
        }

        // Denominator: how many distinct weekdays we have any data for.
        val observedDays = logs.map { it.dayOfWeek }.toSet()

        val routines = logs
            .groupBy { it.packageName to it.hourOfDay }
            .map { (key, occurrences) ->
                val (packageName, hourOfDay) = key
                val supportDays = occurrences.map { it.dayOfWeek }.toSet().size
                val confidence = if (observedDays.isEmpty()) {
                    0f
                } else {
                    supportDays.toFloat() / observedDays.size.toFloat()
                }
                RoutinePattern(
                    packageName = packageName,
                    hourOfDay = hourOfDay,
                    confidence = confidence,
                    supportDays = supportDays,
                    observedDays = observedDays.size
                )
            }
            .filter { it.confidence >= confidenceThreshold }
            // Strongest first; ties broken by more supporting days, then earlier hour.
            .sortedWith(
                compareByDescending<RoutinePattern> { it.confidence }
                    .thenByDescending { it.supportDays }
                    .thenBy { it.hourOfDay }
            )

        return BrainAnalysis(
            totalLogs = logs.size,
            analyzedLogs = logs.size,
            routines = routines
        )
    }

    /**
     * Single-pair confidence, used by the dashboard's manual probe button.
     * Returns the same frequency estimate as [detectRoutines] for one pair.
     */
    fun predictWorkflowConfidence(
        logs: List<UserActivityLog>,
        packageName: String,
        hourOfDay: Int
    ): Float {
        if (logs.isEmpty()) return 0f
        val observedDays = logs.map { it.dayOfWeek }.toSet().size
        if (observedDays == 0) return 0f
        val supportDays = logs
            .filter { it.packageName == packageName && it.hourOfDay == hourOfDay }
            .map { it.dayOfWeek }
            .toSet()
            .size
        return supportDays.toFloat() / observedDays.toFloat()
    }

    /**
     * Reconstruct recurring multi-step workflows from captured interactions.
     *
     * Confidence uses the same frequency estimate as [detectRoutines]: the
     * fraction of observed weekdays on which this exact step shape occurred.
     * Two flows count as the same workflow when their step *kinds* match, so
     * "Type Text" with different content still matches, but "Type then Send"
     * and "Type then Click" do not.
     *
     * Sequences that never type or send anything are discarded — recommending
     * "Open LINE → Click" would not be worth acting on.
     *
     * @param actions rows from [com.example.neuraauto.data.InAppActionDao.recentActions].
     * @param observedDays distinct weekdays present in the action log.
     */
    fun detectSequences(
        actions: List<InAppActionLog>,
        observedDays: Int,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
    ): List<SequenceAnalysis> {
        if (actions.isEmpty() || observedDays <= 0) return emptyList()

        return actions
            .groupBy { it.packageName to it.hourOfDay }
            .mapNotNull { (key, rowsForPair) ->
                val (packageName, hourOfDay) = key

                // One entry per contiguous interaction group.
                val groups = rowsForPair
                    .groupBy { it.sequenceGroupHash }
                    .map { (_, rows) ->
                        ActionStepBuilder.build(packageName, rows) to
                            rows.map { it.dayOfWeek }.toSet()
                    }
                    .filter { it.first.isNotEmpty() }

                val patterns = groups
                    .groupBy { ActionStepBuilder.signature(it.first) }
                    .map { (_, sameShape) ->
                        // Longest observed instance best represents the shape.
                        val representative = sameShape.maxByOrNull { it.first.size }!!.first
                        val supportDays = sameShape.flatMap { it.second }.toSet().size
                        ActionSequencePattern(
                            packageName = packageName,
                            hourOfDay = hourOfDay,
                            steps = representative,
                            confidence = supportDays.toFloat() / observedDays.toFloat(),
                            supportDays = supportDays,
                            observedDays = observedDays,
                            occurrences = sameShape.size
                        )
                    }
                    .filter { it.confidence >= confidenceThreshold }
                    .filter { pattern -> pattern.steps.any { it.isActionable() } }
                    .sortedWith(
                        compareByDescending<ActionSequencePattern> { it.confidence }
                            .thenByDescending { it.occurrences }
                            .thenBy { it.steps.size }
                    )

                if (patterns.isEmpty()) {
                    null
                } else {
                    SequenceAnalysis(
                        packageName = packageName,
                        hourOfDay = hourOfDay,
                        observedDays = observedDays,
                        patterns = patterns
                    )
                }
            }
            .sortedWith(
                compareByDescending<SequenceAnalysis> {
                    it.topPattern?.confidence ?: 0f
                }.thenBy { it.hourOfDay }
            )
    }

    companion object {
        /** The 80% bar from the product spec. */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.8f

        /** Cap on how many rows one analysis pass reads. */
        const val ANALYSIS_WINDOW = 500
    }
}
