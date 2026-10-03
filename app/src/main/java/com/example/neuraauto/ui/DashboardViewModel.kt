package com.example.neuraauto.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.neuraauto.brain.ActionSequencePattern
import com.example.neuraauto.brain.ActionStepKind
import com.example.neuraauto.brain.BrainAnalysis
import com.example.neuraauto.brain.NeuralBrainEngine
import com.example.neuraauto.brain.SequenceAnalysis
import com.example.neuraauto.brain.SparseNeuronLayer
import com.example.neuraauto.worker.ModelWeightStore
import com.example.neuraauto.data.AppDatabase
import com.example.neuraauto.data.AutomationSettings
import com.example.neuraauto.data.AutomationWorkflow
import com.example.neuraauto.data.WorkflowRepository
import com.example.neuraauto.service.AutomationAction
import com.example.neuraauto.service.LaunchOutcome
import com.example.neuraauto.service.WorkflowRunner
import com.example.neuraauto.service.WorkflowScheduler
import com.example.neuraauto.voice.ParsedIntent
import com.example.neuraauto.voice.ThaiIntentParser
import com.example.neuraauto.voice.VoiceIntentBridge
import com.example.neuraauto.worker.TrainingScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state for the dashboard. */
data class DashboardUiState(
    val logCount: Int = 0,
    val actionCount: Int = 0,
    val analysis: BrainAnalysis? = null,
    /** Multi-step workflows reconstructed from captured interactions (Phase 3.1). */
    val sequences: List<SequenceAnalysis> = emptyList(),
    val isAnalyzing: Boolean = false,
    val workflows: List<AutomationWorkflow> = emptyList(),
    /** Whether background training may enable workflows on its own. */
    val autoEnableEnabled: Boolean = true,
    /** Summary of the last background training pass (Phase 3.2). */
    val lastTrainingRun: AutomationSettings.TrainingRunSummary =
        AutomationSettings.TrainingRunSummary(0L, 0, 0, 0f),
    /** Transient feedback from "Test Trigger Now". */
    val testResult: String? = null,

    /** Phase 6.2: parsed intent from Thai voice/text input. */
    val parsedVoiceIntent: ParsedIntent? = null,

    /** Phase 6.2: result of executing a voice-driven intent. */
    val voiceActionResult: String? = null
) {
    val activeWorkflows: List<AutomationWorkflow> get() = workflows.filter { it.isActive }

    fun workflowFor(packageName: String, hourOfDay: Int): AutomationWorkflow? =
        workflows.firstOrNull {
            it.targetApp == packageName &&
                it.scheduledHour == hourOfDay &&
                it.scheduledMinute == 0
        }
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = NeuralBrainEngine()
    private val database = AppDatabase.getInstance(application)
    private val activityDao = database.userActivityDao()
    private val workflowDao = database.automationWorkflowDao()
    private val actionDao = database.inAppActionDao()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        // Settings are not reactive, so they are read once per ViewModel.
        _uiState.value = _uiState.value.copy(
            autoEnableEnabled = AutomationSettings.isAutoEnableEnabled(application),
            lastTrainingRun = AutomationSettings.lastTrainingRun(application)
        )

        // Live count so the card reflects newly captured events without a reload.
        viewModelScope.launch {
            activityDao.observeLogCount().collect { count ->
                _uiState.value = _uiState.value.copy(logCount = count)
            }
        }
        // Live workflow list so enabling a routine updates the card immediately.
        viewModelScope.launch {
            workflowDao.observeAll().collect { workflows ->
                _uiState.value = _uiState.value.copy(workflows = workflows)
            }
        }
        // Live count of captured in-app interactions.
        viewModelScope.launch {
            actionDao.observeActionCount().collect { count ->
                _uiState.value = _uiState.value.copy(actionCount = count)
            }
        }
    }

    /** Queue an immediate training pass (same worker the nightly job uses). */
    fun runTrainingNow() {
        TrainingScheduler.runTrainingNow(getApplication())
        Log.i(TAG, "Queued an immediate training pass")
    }

    /** Turn background auto-enable on or off. */
    fun setAutoEnableEnabled(enabled: Boolean) {
        AutomationSettings.setAutoEnableEnabled(getApplication(), enabled)
        _uiState.value = _uiState.value.copy(autoEnableEnabled = enabled)
        Log.i(TAG, "Background auto-enable set to $enabled")
    }

    /** Re-read the last training summary (e.g. after returning to the screen). */
    fun refreshTrainingSummary() {
        _uiState.value = _uiState.value.copy(
            lastTrainingRun = AutomationSettings.lastTrainingRun(getApplication())
        )
    }

    /** Run routine detection over the most recent activity rows. */
    fun analyze() {
        if (_uiState.value.isAnalyzing) return
        _uiState.value = _uiState.value.copy(isAnalyzing = true)
        viewModelScope.launch {
            val logs = withContext(Dispatchers.IO) {
                activityDao.recentLogs(NeuralBrainEngine.ANALYSIS_WINDOW)
            }
            val analysis = engine.detectRoutines(logs)

            // Phase 3.1: reconstruct multi-step workflows from interactions.
            val actions = withContext(Dispatchers.IO) {
                actionDao.recentActions(NeuralBrainEngine.ANALYSIS_WINDOW)
            }
            val observedDays = withContext(Dispatchers.IO) {
                actionDao.distinctObservedDays()
            }
            val sequences = engine.detectSequences(actions, observedDays)

            _uiState.value = _uiState.value.copy(
                analysis = analysis,
                sequences = sequences,
                isAnalyzing = false
            )
        }
    }

    /**
     * Persist the accepted routine and register its daily alarm.
     *
     * The recommendation carries no message of its own, so a default is used
     * here; editing it per workflow is a later UI concern.
     */
    fun enableWorkflow(packageName: String, hourOfDay: Int) {
        viewModelScope.launch {
            try {
                val workflow = AutomationWorkflow(
                    targetApp = packageName,
                    scheduledHour = hourOfDay,
                    scheduledMinute = 0,
                    targetMessage = defaultMessageFor(packageName),
                    isActive = true
                )
                // save() keeps an existing row's id, so the alarm slot (keyed on
                // the id) is reused instead of orphaned.
                val stored = WorkflowRepository.save(workflowDao, workflow)
                withContext(Dispatchers.IO) {
                    WorkflowScheduler.schedule(getApplication(), stored)
                }
                Log.i(TAG, "Enabled workflow ${stored.id} for $packageName at $hourOfDay:00")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable workflow for $packageName", e)
            }
        }
    }

    /**
     * Enable a workflow reconstructed from a detected sequence.
     *
     * The message is seeded from the text actually typed in the captured flow,
     * so the recommendation reflects what the user really does rather than a
     * generic placeholder. Falls back to the default when nothing was typed.
     */
    fun enableSequenceWorkflow(pattern: ActionSequencePattern) {
        viewModelScope.launch {
            try {
                val typed = pattern.steps
                    .firstOrNull { it.kind == ActionStepKind.TYPE_TEXT }
                    ?.textSnippet
                    ?.takeIf { it.isNotBlank() }

                val workflow = AutomationWorkflow(
                    targetApp = pattern.packageName,
                    scheduledHour = pattern.hourOfDay,
                    scheduledMinute = 0,
                    targetMessage = WorkflowRepository.encodeMessage(
                        message = typed ?: defaultMessageFor(pattern.packageName),
                        steps = pattern.steps.map { it.kind.name }
                    ),
                    isActive = true
                )
                val stored = WorkflowRepository.save(workflowDao, workflow)
                withContext(Dispatchers.IO) {
                    WorkflowScheduler.schedule(getApplication(), stored)
                }
                Log.i(
                    TAG,
                    "Enabled sequence workflow ${stored.id} for ${pattern.packageName} " +
                        "(${pattern.steps.size} steps) at ${pattern.hourOfDay}:00"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable sequence workflow", e)
            }
        }
    }

    /** Disable a workflow and cancel its alarm. */
    fun disableWorkflow(workflow: AutomationWorkflow) {
        viewModelScope.launch {
            try {
                workflowDao.setActive(workflow.id, false)
                withContext(Dispatchers.IO) {
                    WorkflowScheduler.cancel(getApplication(), workflow)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable workflow ${workflow.id}", e)
            }
        }
    }

    /** Re-enable a previously disabled workflow. */
    fun enableExistingWorkflow(workflow: AutomationWorkflow) {
        viewModelScope.launch {
            try {
                workflowDao.setActive(workflow.id, true)
                val stored = workflowDao.byId(workflow.id) ?: return@launch
                withContext(Dispatchers.IO) {
                    WorkflowScheduler.schedule(getApplication(), stored)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-enable workflow ${workflow.id}", e)
            }
        }
    }

    /**
     * Fire a workflow immediately, for manual verification.
     *
     * Uses [WorkflowRunner] — the same dispatch the alarm path uses — so a
     * successful test genuinely exercises the execution engine.
     *
     * Phase 6.1: After dispatch, triggers a SparseNeuronLayer reinforcement
     * training pass to strengthen pattern weights for this workflow.
     */
    fun testTrigger(workflow: AutomationWorkflow) {
        viewModelScope.launch {
            val action = AutomationAction(
                workflowId = workflow.id,
                targetPackage = workflow.targetApp,
                actionType = AutomationAction.ACTION_SEND_MESSAGE,
                message = WorkflowRepository.messageOf(workflow),
                steps = WorkflowRepository.stepsFor(workflow)
            )
            val outcome = try {
                withContext(Dispatchers.Main) {
                    WorkflowRunner.dispatch(getApplication(), action)
                }
            } catch (e: Exception) {
                // startActivity from a non-activity context can throw on some OEMs.
                Log.w(TAG, "Test trigger failed for ${workflow.targetApp}", e)
                LaunchOutcome.Failed(
                    "Failed to launch ${workflow.targetApp}: ${e.javaClass.simpleName}"
                )
            }
            _uiState.value = _uiState.value.copy(testResult = outcome.message)

            // Phase 6.1: Reinforcement training — strengthen pattern weights
            // every time a workflow is executed (manual test or scheduled).
            withContext(Dispatchers.IO) {
                val store = ModelWeightStore(getApplication())
                val layer = SparseNeuronLayer(
                    inputSize = 10,
                    capacity = SparseNeuronLayer.DEFAULT_CAPACITY
                )
                store.load(layer)

                val features = FloatArray(10) { 0.5f }
                features[0] = 1.0f  // Confidence (user-initiated)
                features[1] = 1.0f  // SupportRatio
                features[2] = (WorkflowRepository.stepsFor(workflow).size / 6f).coerceAtMost(1f)
                features[3] = workflow.scheduledHour / 23f
                features[7] = 1.0f  // UserVerifiedWeight

                layer.trainStep(features)
                store.save(layer)
                Log.i(TAG, "Reinforcement training completed for workflow ${workflow.id}")
            }
        }
    }

    /**
     * Add a new user-created workflow.
     *
     * Persists the workflow, schedules its daily alarm, and triggers
     * reinforcement training on the new pattern.
     */
    fun addWorkflow(workflow: AutomationWorkflow) {
        viewModelScope.launch {
            try {
                val stored = WorkflowRepository.save(workflowDao, workflow)
                withContext(Dispatchers.IO) {
                    WorkflowScheduler.schedule(getApplication(), stored)
                }

                // Reinforcement training on the new user-created pattern
                withContext(Dispatchers.IO) {
                    val store = ModelWeightStore(getApplication())
                    val layer = SparseNeuronLayer(
                        inputSize = 10,
                        capacity = SparseNeuronLayer.DEFAULT_CAPACITY
                    )
                    store.load(layer)

                    val features = FloatArray(10) { 0.5f }
                    features[0] = 1.0f
                    features[1] = 1.0f
                    features[2] = (WorkflowRepository.stepsFor(stored).size / 6f).coerceAtMost(1f)
                    features[3] = stored.scheduledHour / 23f
                    features[7] = 1.0f

                    layer.trainStep(features)
                    store.save(layer)
                }

                _uiState.value = _uiState.value.copy(
                    testResult = "Workflow created and scheduled for ${"%02d".format(stored.scheduledHour)}:${"%02d".format(stored.scheduledMinute)} น."
                )
                Log.i(TAG, "Added workflow ${stored.id} for ${stored.targetApp}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add workflow", e)
                _uiState.value = _uiState.value.copy(
                    testResult = "Failed to add workflow: ${e.javaClass.simpleName}"
                )
            }
        }
    }

    /** Clear the test feedback banner. */
    fun clearTestResult() {
        _uiState.value = _uiState.value.copy(testResult = null)
    }

    /** Phase 6.2: Parse a Thai text/voice input into a structured [ParsedIntent]. */
    fun parseVoiceInput(input: String) {
        val intent = ThaiIntentParser.parse(input)
        _uiState.value = _uiState.value.copy(
            parsedVoiceIntent = intent,
            voiceActionResult = if (intent == null) "ไม่สามารถจับคู่ intent ได้" else null
        )
        if (intent != null) {
            Log.i(
                TAG,
                "Parsed voice intent: ${intent.type} → target=${intent.target}, " +
                    "payload=${intent.payload}, conf=${intent.confidence}"
            )
        } else {
            Log.d(TAG, "No intent matched for input: $input")
        }
    }

    /** Phase 6.2: Execute the currently parsed voice intent. */
    fun executeVoiceIntent() {
        val intent = _uiState.value.parsedVoiceIntent ?: return
        viewModelScope.launch {
            val result = VoiceIntentBridge.execute(getApplication(), intent)
            _uiState.value = _uiState.value.copy(
                voiceActionResult = result.displayMessage,
                parsedVoiceIntent = null
            )
            Log.i(TAG, "Voice intent execution result: ${result.displayMessage}")
        }
    }

    /** Phase 6.2: Clear voice intent parsing and execution results. */
    fun clearVoiceResult() {
        _uiState.value = _uiState.value.copy(
            parsedVoiceIntent = null,
            voiceActionResult = null
        )
    }

    /** Mark a workflow as explicitly verified by the user and lock it. */
    fun verifyWorkflow(workflow: AutomationWorkflow) {
        viewModelScope.launch {
            try {
                workflowDao.verifyWorkflow(workflow.id)
                workflowDao.lockWorkflow(workflow.id)
                Log.i(TAG, "Verified and locked workflow ${workflow.id}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to verify workflow ${workflow.id}", e)
            }
        }
    }

    /** Mark a workflow as explicitly rejected by the user. */
    fun rejectWorkflow(workflow: AutomationWorkflow) {
        viewModelScope.launch {
            try {
                workflowDao.rejectWorkflow(workflow.id)
                Log.i(TAG, "Rejected workflow ${workflow.id}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reject workflow ${workflow.id}", e)
            }
        }
    }

    /** Delete a workflow permanently. */
    fun deleteWorkflow(workflow: AutomationWorkflow) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WorkflowScheduler.cancel(getApplication(), workflow)
                }
                workflowDao.delete(workflow.id)
                Log.i(TAG, "Deleted workflow ${workflow.id}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete workflow ${workflow.id}", e)
            }
        }
    }

    /** Update a workflow's fields (edit dialog). */
    fun updateWorkflow(workflow: AutomationWorkflow) {
        viewModelScope.launch {
            try {
                workflowDao.updateWorkflow(workflow)
                val stored = workflowDao.byId(workflow.id) ?: return@launch
                if (stored.isActive) {
                    withContext(Dispatchers.IO) {
                        WorkflowScheduler.schedule(getApplication(), stored)
                    }
                }
                Log.i(TAG, "Updated workflow ${workflow.id}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update workflow ${workflow.id}", e)
            }
        }
    }

    /**
     * Save a corrected step sequence from the Visual Step Editor.
     *
     * Updates the workflow row in Room, re-trains the sparse neuron layer
     * on the corrected pattern, and locks the workflow for long-term memory
     * protection.
     */
    fun saveCorrectedSteps(workflow: AutomationWorkflow, correctedSteps: List<String>) {
        viewModelScope.launch {
            try {
                val updated = WorkflowRepository.updateAndLock(
                    dao = workflowDao,
                    workflow = workflow,
                    correctedSteps = correctedSteps
                )

                // Re-train the sparse neuron layer on the corrected pattern.
                withContext(Dispatchers.IO) {
                    val store = ModelWeightStore(getApplication())
                    val layer = SparseNeuronLayer(
                        inputSize = 10,
                        capacity = SparseNeuronLayer.DEFAULT_CAPACITY
                    )
                    store.load(layer)

                    // Build a feature vector from the corrected steps.
                    val features = FloatArray(10) { 0.5f }
                    features[0] = 1.0f  // Confidence (user-verified)
                    features[1] = 1.0f  // SupportRatio
                    features[2] = (correctedSteps.size / 6f).coerceAtMost(1f)  // SequenceLength
                    features[3] = workflow.scheduledHour / 23f  // HourWindow
                    features[7] = 1.0f  // UserVerifiedWeight

                    layer.trainStep(features)
                    store.save(layer)
                }

                // The workflow list Flow in init{} will emit the updated row.
                _uiState.value = _uiState.value.copy(
                    testResult = "Steps saved and locked for long-term memory"
                )

                Log.i(
                    TAG,
                    "Saved corrected steps for workflow ${updated.id}: " +
                        "${correctedSteps.size} steps, locked=${updated.isLocked}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save corrected steps for ${workflow.id}", e)
            }
        }
    }

    private fun defaultMessageFor(packageName: String): String =
        if (packageName == LINE_PACKAGE) {
            "สวัสดีครับ ข้อความนี้ถูกส่งโดย NeuraAuto AI"
        } else {
            "NeuraAuto AI automated message"
        }

    private companion object {
        const val TAG = "DashboardViewModel"
        const val LINE_PACKAGE = "com.linecorp.line"
    }
}
