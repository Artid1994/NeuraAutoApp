package com.example.neuraauto.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.neuraauto.brain.BrainAnalysis
import com.example.neuraauto.brain.NeuralBrainEngine
import com.example.neuraauto.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state for the dashboard. */
data class DashboardUiState(
    val logCount: Int = 0,
    val analysis: BrainAnalysis? = null,
    val isAnalyzing: Boolean = false,
    val enabledRoutines: Set<String> = emptySet()
) {
    /** Key used to track which routine cards the user has enabled. */
    fun routineKey(packageName: String, hourOfDay: Int) = "$packageName@$hourOfDay"
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = NeuralBrainEngine()
    private val dao = AppDatabase.getInstance(application).userActivityDao()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        // Live count so the card reflects newly captured events without a reload.
        viewModelScope.launch {
            dao.observeLogCount().collect { count ->
                _uiState.value = _uiState.value.copy(logCount = count)
            }
        }
    }

    /** Run routine detection over the most recent activity rows. */
    fun analyze() {
        if (_uiState.value.isAnalyzing) return
        _uiState.value = _uiState.value.copy(isAnalyzing = true)
        viewModelScope.launch {
            val logs = withContext(Dispatchers.IO) {
                dao.recentLogs(NeuralBrainEngine.ANALYSIS_WINDOW)
            }
            val analysis = engine.detectRoutines(logs)
            _uiState.value = _uiState.value.copy(analysis = analysis, isAnalyzing = false)
        }
    }

    /** Records that the user accepted a recommendation. */
    fun enableRoutine(packageName: String, hourOfDay: Int) {
        val key = _uiState.value.routineKey(packageName, hourOfDay)
        _uiState.value = _uiState.value.copy(
            enabledRoutines = _uiState.value.enabledRoutines + key
        )
    }
}
