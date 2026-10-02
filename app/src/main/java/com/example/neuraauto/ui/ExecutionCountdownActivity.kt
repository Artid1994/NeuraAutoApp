package com.example.neuraauto.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.neuraauto.service.WorkflowScheduler

/**
 * Full-screen countdown shown before an automation actually executes.
 *
 * The alarm fires at the scheduled time; this activity then gives the user
 * [COUNTDOWN_SECONDS] to intervene. Three outcomes:
 *  - countdown reaches zero → the workflow is dispatched as normal;
 *  - "Skip Today" → the alarm is re-armed for tomorrow and nothing is sent;
 *  - "Cancel" → the workflow is disabled entirely.
 *
 * Shown as a translucent activity rather than a system alert window so it needs
 * no SYSTEM_ALERT_WINDOW permission (which cannot be granted from the UI and
 * would require a settings detour).
 */
class ExecutionCountdownActivity : Activity() {

    private var timer: CountDownTimer? = null
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen so the countdown is not silently missed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val action = com.example.neuraauto.service.AutomationAction.fromIntent(intent)
        if (action == null) {
            Log.w(TAG, "Countdown started without a payload; finishing")
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xE6000000.toInt())
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "NeuraAuto AI"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        val detail = TextView(this).apply {
            text = buildString {
                append("กำลังจะเปิด ${action.targetPackage}\n")
                append("และส่งข้อความอัตโนมัติ\n\n")
                append("ข้อความ: ${action.message}")
            }
            textSize = 15f
            setTextColor(0xFFE0E0E0.toInt())
            gravity = Gravity.CENTER
        }
        val countdown = TextView(this).apply {
            text = COUNTDOWN_SECONDS.toString()
            textSize = 56f
            setTextColor(0xFFFFD54F.toInt())
            gravity = Gravity.CENTER
        }

        val skip = Button(this).apply {
            text = "ข้ามวันนี้ (Skip Today)"
            setOnClickListener { skipToday(action) }
        }
        val cancel = Button(this).apply {
            text = "ยกเลิก (Cancel)"
            setOnClickListener { cancelWorkflow(action) }
        }

        root.addView(title)
        root.addView(detail)
        root.addView(countdown)
        root.addView(skip)
        root.addView(cancel)
        setContentView(root)

        timer = object : CountDownTimer(COUNTDOWN_SECONDS * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                countdown.text = (millisUntilFinished / 1000L).toString()
            }

            override fun onFinish() {
                if (resolved) return
                resolved = true
                Log.i(TAG, "Countdown elapsed; dispatching ${action.targetPackage}")
                dispatchNow(action)
                finish()
            }
        }.start()
    }

    /** Hand the action to the shared dispatch path and close. */
    private fun dispatchNow(action: com.example.neuraauto.service.AutomationAction) {
        com.example.neuraauto.service.WorkflowRunner.dispatch(this, action)
    }

    /**
     * Skip this one occurrence: re-arm the alarm for tomorrow and send nothing.
     *
     * The workflow stays active, so the next scheduled time fires normally.
     */
    private fun skipToday(action: com.example.neuraauto.service.AutomationAction) {
        if (resolved) return
        resolved = true
        timer?.cancel()
        Log.i(TAG, "User skipped ${action.targetPackage} for today")

        Thread {
            try {
                val workflow = com.example.neuraauto.data.AppDatabase.getInstance(this)
                    .automationWorkflowDao()
                    .byId(action.workflowId)
                if (workflow != null && workflow.isActive) {
                    WorkflowScheduler.schedule(this, workflow)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-arm after skip", e)
            }
        }.start()
        finish()
    }

    /** Disable the workflow outright. */
    private fun cancelWorkflow(action: com.example.neuraauto.service.AutomationAction) {
        if (resolved) return
        resolved = true
        timer?.cancel()
        Log.i(TAG, "User cancelled workflow ${action.workflowId}")

        Thread {
            try {
                val dao = com.example.neuraauto.data.AppDatabase.getInstance(this)
                    .automationWorkflowDao()
                dao.setActive(action.workflowId, false)
                val workflow = dao.byId(action.workflowId)
                if (workflow != null) {
                    WorkflowScheduler.cancel(this, workflow)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel workflow", e)
            }
        }.start()
        finish()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ExecutionCountdown"

        /** How long the user has to intervene before the automation runs. */
        const val COUNTDOWN_SECONDS = 10

        /**
         * Launch the countdown for [action].
         *
         * @param skipCountdown bypasses the countdown entirely — used only by
         * the manual "Test Trigger Now" button, where the user has already
         * chosen to run the workflow.
         */
        fun start(context: Context, action: com.example.neuraauto.service.AutomationAction) {
            val intent = Intent(context, ExecutionCountdownActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                action.writeTo(this)
            }
            context.startActivity(intent)
        }
    }
}
