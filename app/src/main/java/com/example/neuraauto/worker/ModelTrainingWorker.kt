package com.example.neuraauto.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.neuraauto.brain.ActionSequencePattern
import com.example.neuraauto.brain.ActionStepKind
import com.example.neuraauto.brain.DynamicNeuronLayer
import com.example.neuraauto.brain.NeuralBrainEngine
import com.example.neuraauto.data.AppDatabase
import com.example.neuraauto.data.AutomationSettings
import com.example.neuraauto.data.AutomationWorkflow
import com.example.neuraauto.data.WorkflowRepository
import com.example.neuraauto.service.WorkflowScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background pass that (1) trains the on-device layer on captured behaviour and
 * (2) turns high-confidence sequences into scheduled workflows.
 *
 * HOW THE DECISION IS MADE — stated plainly, because it matters:
 * *which* routines qualify is decided by [NeuralBrainEngine.detectSequences],
 * a frequency estimate over stored events (supporting weekdays / observed
 * weekdays, threshold 0.8). The [DynamicNeuronLayer] does not and cannot
 * "recognise routines": it is trained on the qualifying patterns' features and
 * produces a score that is logged alongside them. Treating the layer's output
 * as the basis for sending real messages would be dishonest, since the layer
 * has no labels and no way to learn what a "good" workflow is.
 *
 * SAFETY — this worker writes automation that sends real messages unattended:
 *  - the user's auto-enable switch is honoured ([AutomationSettings]);
 *  - an existing workflow the user disabled is never silently re-enabled;
 *  - at most [MAX_AUTO_ENABLES_PER_RUN] workflows are created per run;
 *  - the user is only ever notified, never asked.
 */
class ModelTrainingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val database = AppDatabase.getInstance(applicationContext)
            val activityDao = database.userActivityDao()
            val actionDao = database.inAppActionDao()
            val workflowDao = database.automationWorkflowDao()

            // ── 1. gather features ───────────────────────────────────────────
            val logs = activityDao.recentLogs(NeuralBrainEngine.ANALYSIS_WINDOW)
            val actions = actionDao.recentActions(NeuralBrainEngine.ANALYSIS_WINDOW)
            val observedDays = actionDao.distinctObservedDays()

            if (logs.isEmpty() && actions.isEmpty()) {
                Log.i(TAG, "No captured behaviour yet; nothing to train on")
                return@withContext Result.success()
            }

            // ── 2. load persisted weights, train, persist ────────────────────
            val store = ModelWeightStore(applicationContext)
            val layer = DynamicNeuronLayer(
                inputSize = FEATURE_COUNT,
                initialNeurons = HIDDEN_NEURONS
            )
            val hadSavedModel = store.load(layer)

            val engine = NeuralBrainEngine()
            val patterns = engine.detectSequences(actions, observedDays)
                .flatMap { it.patterns }

            var scoreSum = 0f
            var trained = 0
            for (pattern in patterns) {
                val features = featuresFor(pattern)
                scoreSum += layer.trainStep(features)
                trained++
            }
            store.save(layer)

            val meanScore = if (trained == 0) 0f else scoreSum / trained

            Log.i(
                TAG,
                "Training pass: ${patterns.size} pattern(s), $trained update(s), " +
                    "meanActivation=${"%.4f".format(meanScore)}, " +
                    "restored=${if (hadSavedModel) "yes" else "no"}"
            )

            // ── 3. auto-enable qualifying workflows ──────────────────────────
            var autoEnabled = 0
            if (patterns.isNotEmpty() && AutomationSettings.isAutoEnableEnabled(applicationContext)) {
                for (pattern in patterns) {
                    if (autoEnabled >= MAX_AUTO_ENABLES_PER_RUN) break

                    val slot = workflowDao.findBySlot(
                        packageName = pattern.packageName,
                        hour = pattern.hourOfDay,
                        minute = 0
                    )

                    // Never resurrect something the user switched off.
                    if (slot != null && !slot.isActive) {
                        Log.i(TAG, "Skipping ${pattern.packageName}@${pattern.hourOfDay}: user disabled it")
                        continue
                    }

                    val stored = WorkflowRepository.save(
                        dao = workflowDao,
                        workflow = AutomationWorkflow(
                            id = slot?.id ?: 0L,
                            targetApp = pattern.packageName,
                            scheduledHour = pattern.hourOfDay,
                            scheduledMinute = 0,
                            targetMessage = WorkflowRepository.encodeMessage(
                                message = messageFor(pattern),
                                steps = pattern.steps.map { it.kind.name }
                            ),
                            isActive = true
                        )
                    )
                    WorkflowScheduler.schedule(applicationContext, stored)
                    autoEnabled++
                    Log.i(
                        TAG,
                        "Auto-enabled ${pattern.packageName}@${pattern.hourOfDay}:00 " +
                            "(${pattern.confidencePercent}%, ${pattern.steps.size} steps)"
                    )
                }
            } else if (patterns.isNotEmpty()) {
                Log.i(TAG, "Auto-enable is switched off; ${patterns.size} pattern(s) left for the user")
            }

            AutomationSettings.recordTrainingRun(
                context = applicationContext,
                patternsFound = patterns.size,
                autoEnabled = autoEnabled,
                meanScore = meanScore,
                timestampMillis = System.currentTimeMillis()
            )

            Result.success()
        } catch (e: Exception) {
            // Never crash the background scheduler; a later run retries.
            Log.w(TAG, "Training pass failed", e)
            Result.retry()
        }
    }

    /**
     * Hand-built feature vector for one pattern.
     *
     * Deliberately small and interpretable — every value is already meaningful
     * on its own, which is what makes the layer's weights inspectable.
     */
    private fun featuresFor(pattern: ActionSequencePattern): FloatArray = floatArrayOf(
        pattern.confidence,
        pattern.supportDays.toFloat() / pattern.observedDays.coerceAtLeast(1),
        (pattern.occurrences / OCCURRENCE_SCALE).coerceAtMost(1f),
        (pattern.steps.size / STEP_SCALE).coerceAtMost(1f)
    )

    /** Message seeded from what the user actually typed, when available. */
    private fun messageFor(pattern: ActionSequencePattern): String =
        pattern.steps
            .firstOrNull { it.kind == ActionStepKind.TYPE_TEXT }
            ?.textSnippet
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MESSAGE

    companion object {
        private const val TAG = "ModelTrainingWorker"

        /** Features per pattern, see [featuresFor]. */
        private const val FEATURE_COUNT = 4

        private const val HIDDEN_NEURONS = 16

        /** Occurrences at which the frequency feature saturates. */
        private const val OCCURRENCE_SCALE = 10f

        /** Steps at which the complexity feature saturates. */
        private const val STEP_SCALE = 6f

        /**
         * Cap on workflows created per run. Keeps a first-run burst bounded so
         * a user does not wake up to a dozen scheduled automations.
         */
        private const val MAX_AUTO_ENABLES_PER_RUN = 3

        private const val DEFAULT_MESSAGE = "NeuraAuto AI automated message"
    }
}
