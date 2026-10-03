package com.example.neuraauto.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.neuraauto.brain.ActionSequencePattern
import com.example.neuraauto.brain.ActionStepKind
import com.example.neuraauto.brain.NeuralBrainEngine
import com.example.neuraauto.brain.SparseNeuronLayer
import com.example.neuraauto.data.AppDatabase
import com.example.neuraauto.data.AutomationSettings
import com.example.neuraauto.data.AutomationWorkflow
import com.example.neuraauto.data.UserActivityLog
import com.example.neuraauto.data.WorkflowRepository
import com.example.neuraauto.safety.SafetyEngine
import com.example.neuraauto.service.AutomationAction
import com.example.neuraauto.service.WorkflowScheduler
import com.example.neuraauto.worker.TrainingScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background pass that (1) trains the on-device layer on captured behaviour and
 * (2) turns high-confidence sequences into scheduled workflows.
 *
 * HOW THE DECISION IS MADE — stated plainly, because it matters:
 * *which* routines qualify is decided by [NeuralBrainEngine.detectSequences],
 * a frequency estimate over stored events (supporting weekdays / observed
 * weekdays, threshold 0.8). The [SparseNeuronLayer] does not and cannot
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
            val layer = SparseNeuronLayer(
                inputSize = FEATURE_COUNT,
                capacity = NEURON_CAPACITY
            )
            val hadSavedModel = store.load(layer)

            val engine = NeuralBrainEngine()
            val patterns = engine.detectSequences(actions, observedDays)
                .flatMap { it.patterns }

            var scoreSum = 0f
            var trained = 0
            for (pattern in patterns) {
                val slot = workflowDao.findBySlot(
                    packageName = pattern.packageName,
                    hour = pattern.hourOfDay,
                    minute = 0
                )
                val sample = activityDao.sampleFor(pattern.packageName, pattern.hourOfDay)
                val features = featuresFor(
                    pattern = pattern,
                    sample = sample,
                    isUserVerified = slot?.isUserVerified == true
                )
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

                    // Deduplication: check for existing workflow within ±15 min
                    val nearby = WorkflowRepository.findNearby(
                        dao = workflowDao,
                        packageName = pattern.packageName,
                        hour = pattern.hourOfDay,
                        minute = 0,
                        toleranceMinutes = 15
                    )
                    if (nearby != null) {
                        Log.i(TAG, "Skipping ${pattern.packageName}@${pattern.hourOfDay}: nearby workflow ${nearby.id} exists")
                        continue
                    }

                    val slot = workflowDao.findBySlot(
                        packageName = pattern.packageName,
                        hour = pattern.hourOfDay,
                        minute = 0
                    )

                    // Never resurrect something the user switched off or rejected.
                    if (slot != null && (!slot.isActive || slot.isUserRejected)) {
                        Log.i(TAG, "Skipping ${pattern.packageName}@${pattern.hourOfDay}: user disabled/rejected it")
                        continue
                    }

                    // SafetyEngine gate — verify the action is safe before auto-enabling
                    val actionToCheck = AutomationAction(
                        workflowId = slot?.id ?: 0L,
                        targetPackage = pattern.packageName,
                        actionType = AutomationAction.ACTION_SEND_MESSAGE,
                        message = messageFor(pattern),
                        steps = pattern.steps.map { it.kind.name }
                    )
                    val safetyDecision = SafetyEngine.isActionSafe(applicationContext, actionToCheck)
                    if (!safetyDecision.isSafe) {
                        Log.w(TAG, "SafetyEngine blocked auto-enable for ${pattern.packageName}@${pattern.hourOfDay}: ${(safetyDecision as SafetyEngine.SafetyDecision.Unsafe).reason}")
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
                            isActive = true,
                            isLocked = false // auto-created; user can lock via verify
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

            // ── 4. adaptive high-frequency training ─────────────────────────
            // For unverified/new patterns with confidence < 0.8, schedule
            // background passes every 15-30 minutes. Once confidence hits 0.8
            // or becomes locked, drop to standard daily runs.
            val unverifiedPatterns = patterns.filter { pattern ->
                val slot = workflowDao.findBySlot(
                    packageName = pattern.packageName,
                    hour = pattern.hourOfDay,
                    minute = 0
                )
                pattern.confidence < 0.8f && (slot == null || !slot.isLocked)
            }
            if (unverifiedPatterns.isNotEmpty()) {
                TrainingScheduler.scheduleAdaptiveTraining(applicationContext, unverifiedPatterns.size)
                Log.i(TAG, "Scheduled adaptive training for ${unverifiedPatterns.size} unverified pattern(s)")
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
     * Hand-built feature vector for one pattern — 10 context features.
     *
     * Features 1–4 come from the pattern itself. Features 5–8 are the ambient
     * context the pattern was actually observed under, read from the stored
     * activity sample rather than guessed: IsWeekend and IsCharging come from
     * the row, IsWifi from the transport, and the Wi-Fi SSID is folded into a
     * stable 0..1 bucket (0.5 = unknown network). Features 9–10 are the
     * battery level and the user-verification weight.
     *
     * When no sample exists the ambient slots fall back to 0.5 (neutral), so a
     * pattern is never credited with context it was not observed in.
     */
    private fun featuresFor(
        pattern: ActionSequencePattern,
        sample: UserActivityLog?,
        isUserVerified: Boolean
    ): FloatArray = floatArrayOf(
        pattern.confidence,                                              // 1 Confidence
        pattern.supportDays.toFloat() / pattern.observedDays.coerceAtLeast(1), // 2 SupportRatio
        (pattern.steps.size / STEP_SCALE).coerceAtMost(1f),               // 3 SequenceLength
        pattern.hourOfDay / 23f,                                         // 4 HourWindow
        sample?.let { if (it.dayOfWeek >= 6) 1f else 0f } ?: 0.5f,       // 5 IsWeekend
        sample?.let { if (it.isCharging) 1f else 0f } ?: 0.5f,           // 6 IsCharging
        sample?.let { if (it.isWifiConnected) 1f else 0f } ?: 0.5f,      // 7 IsWifi
        if (isUserVerified) 1.0f else 0.0f,                              // 8 UserVerifiedWeight
        ssidBucket(sample?.wifiSsid),                                    // 9 SsidBucket
        batteryBucket(sample?.batteryPercent)                            // 10 BatteryBucket
    )

    /**
     * Fold an SSID into a stable 0..1 bucket.
     *
     * The SSID itself is never a feature value — only its hash, so the network
     * name is not recoverable from the weight file. Unknown/null maps to 0.5.
     */
    private fun ssidBucket(ssid: String?): Float {
        if (ssid.isNullOrBlank()) return 0.5f
        var h = 2166136261u
        for (c in ssid) {
            h = (h xor c.code.toUInt()) * 16777619u
        }
        return (h % 1000u).toFloat() / 1000f
    }

    /** Battery percentage as 0..1, or 0.5 when unavailable. */
    private fun batteryBucket(percent: Int?): Float {
        if (percent == null || percent < 0) return 0.5f
        return (percent.coerceIn(0, 100)) / 100f
    }

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
        private const val FEATURE_COUNT = 10

        /**
         * Addressable neuron indices. This is representation capacity, not
         * allocated memory — [SparseNeuronLayer] materialises a row only when
         * a neuron actually wins.
         */
        private const val NEURON_CAPACITY = SparseNeuronLayer.DEFAULT_CAPACITY

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
