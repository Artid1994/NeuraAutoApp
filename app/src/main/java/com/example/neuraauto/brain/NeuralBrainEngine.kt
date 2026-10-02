package com.example.neuraauto.brain

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

    companion object {
        /** The 80% bar from the product spec. */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.8f

        /** Cap on how many rows one analysis pass reads. */
        const val ANALYSIS_WINDOW = 500
    }
}
