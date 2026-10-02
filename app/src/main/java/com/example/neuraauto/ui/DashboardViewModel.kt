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
import com.example.neuraauto.data.AppDatabase
import com.example.neuraauto.data.AutomationWorkflow
import com.example.neuraauto.service.AutomationAction
import com.example.neuraauto.service.LaunchOutcome
import com.example.neuraauto.service.WorkflowRunner
import com.example.neuraauto.service.WorkflowScheduler
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
    /** Transient feedback from "Test Trigger Now". */
    val testResult: String? = null
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
                // REPLACE on the (targetApp, hour, minute) slot keeps the row id
                // stable for an already-enabled routine, so its alarm slot is reused.
                val id = workflowDao.upsert(workflow)
                val stored = workflowDao.byId(id) ?: workflow.copy(id = id)
                withContext(Dispatchers.IO) {
                    WorkflowScheduler.schedule(getApplication(), stored)
                }
                Log.i(TAG, "Enabled workflow $id for $packageName at $hourOfDay:00")
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
                    targetMessage = typed ?: defaultMessageFor(pattern.packageName),
                    isActive = true
                )
                val id = workflowDao.upsert(workflow)
                val stored = workflowDao.byId(id) ?: workflow.copy(id = id)
                withContext(Dispatchers.IO) {
                    WorkflowScheduler.schedule(getApplication(), stored)
                }
                Log.i(
                    TAG,
                    "Enabled sequence workflow $id for ${pattern.packageName} " +
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
     */
    fun testTrigger(workflow: AutomationWorkflow) {
        viewModelScope.launch {
            val action = AutomationAction(
                workflowId = workflow.id,
                targetPackage = workflow.targetApp,
                actionType = AutomationAction.ACTION_SEND_MESSAGE,
                message = workflow.targetMessage
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
        }
    }

    /** Clear the test feedback banner. */
    fun clearTestResult() {
        _uiState.value = _uiState.value.copy(testResult = null)
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
