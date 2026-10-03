package com.example.neuraauto.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import com.example.neuraauto.R
import com.example.neuraauto.service.WorkflowRunner

/**
 * Phase 7.0 — Step-by-step visual progress overlay during replay.
 *
 * Shows a full-screen overlay with the current step, a progress bar, and
 * a human-readable description of what the engine is doing.
 *
 * Lifecycle:
 *  1. [start] is called with the action parameters.
 *  2. The overlay displays "Step 1/N: <description>" for each step.
 *  3. When all steps complete, the overlay finishes itself.
 *
 * The overlay is deliberately simple: it reads the step list and displays
 * progress as the accessibility service reports completion.
 */
class ReplayProgressActivity : Activity() {

    private lateinit var tvStep: TextView
    private lateinit var tvDetail: TextView
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private var currentStep = 0
    private var totalSteps = 0
    private var stepDescriptions: List<String> = emptyList()

    private var targetPackage: String = ""
    private var actionType: String = ""
    private var message: String = ""
    private var steps: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.replay_progress)

        tvStep = findViewById(R.id.tvProgressStep)
        tvDetail = findViewById(R.id.tvProgressDetail)
        progressBar = findViewById(R.id.progressBar)

        // Read action parameters from intent extras
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""
        actionType = intent.getStringExtra(EXTRA_ACTION_TYPE) ?: "SEND_MESSAGE"
        message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        steps = intent.getStringArrayExtra(EXTRA_STEPS)?.toList() ?: emptyList()

        if (targetPackage.isEmpty()) {
            finish()
            return
        }

        totalSteps = steps.size
        stepDescriptions = buildStepDescriptions()

        // Show initial state
        updateProgress(0)

        // Start the replay
        val action = com.example.neuraauto.service.AutomationAction(
            workflowId = -1L,
            targetPackage = targetPackage,
            actionType = actionType,
            message = message,
            steps = steps
        )
        WorkflowRunner.dispatch(this, action)

        // Poll for progress updates
        startProgressPolling()
    }

    private fun buildStepDescriptions(): List<String> {
        val descriptions = mutableListOf<String>()

        // Step 1 is always "Opening App"
        descriptions.add("Opening $targetPackage")

        // Remaining steps come from the step list
        for (step in steps) {
            val desc = when (step) {
                "TYPE_TEXT" -> "Typing Text"
                "CLICK_SEND" -> "Clicking Send"
                "CLICK_INPUT" -> "Clicking Input"
                "OPEN_APP" -> "Opening App"
                "CLICK" -> "Clicking"
                else -> step.replace("_", " ").lowercase()
                    .replaceFirstChar { it.uppercase() }
            }
            descriptions.add(desc)
        }

        return descriptions
    }

    private fun updateProgress(stepIndex: Int) {
        currentStep = stepIndex
        val displayStep = (stepIndex + 1).coerceAtMost(totalSteps)
        tvStep.text = "Step $displayStep/$totalSteps: ${stepDescriptions.getOrElse(stepIndex) { "Working..." }}"

        val progress = if (totalSteps > 0) {
            (stepIndex * 100) / totalSteps
        } else {
            0
        }
        progressBar.progress = progress

        // Update detail text
        tvDetail.text = when {
            stepIndex < stepDescriptions.size -> stepDescriptions[stepIndex]
            else -> "Completing..."
        }
    }

    private fun startProgressPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                // Check if the workflow is still running
                val pendingAction = com.example.neuraauto.service.AutomationAccessibility.pendingAction
                if (pendingAction == null) {
                    // Workflow finished
                    updateProgress(totalSteps)
                    handler.postDelayed({ finish() }, 1000)
                    return
                }

                // Estimate current step based on accessibility service state
                val estimatedStep = com.example.neuraauto.service.AutomationAccessibility.stepCursor
                if (estimatedStep != currentStep && estimatedStep < totalSteps) {
                    updateProgress(estimatedStep)
                }

                handler.postDelayed(this, 500)
            }
        }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_ACTION_TYPE = "extra_action_type"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_STEPS = "extra_steps"

        /**
         * Start the replay progress overlay.
         *
         * @param context  The calling context.
         * @param targetPackage  The target app package.
         * @param actionType  The action type.
         * @param message  The message to send.
         * @param steps  The step list.
         */
        fun start(
            context: android.content.Context,
            targetPackage: String,
            actionType: String,
            message: String,
            steps: List<String>
        ) {
            val intent = Intent(context, ReplayProgressActivity::class.java).apply {
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                putExtra(EXTRA_ACTION_TYPE, actionType)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_STEPS, steps.toTypedArray())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
