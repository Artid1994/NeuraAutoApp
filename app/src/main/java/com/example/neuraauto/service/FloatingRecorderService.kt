package com.example.neuraauto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.example.neuraauto.R
import com.example.neuraauto.data.AppDatabase
import com.example.neuraauto.data.AutomationWorkflow
import com.example.neuraauto.data.WorkflowRepository
import com.example.neuraauto.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phase 7.0 — Floating Record & Replay Engine.
 *
 * A foreground service that displays a floating bubble overlay, letting the
 * user record a new routine by:
 *  1. Tapping [🔴 Record] on the bubble.
 *  2. Switching to a target app (e.g. LINE) and performing actions.
 *  3. Tapping [⏹️ Stop & Save] on the bubble.
 *
 * During recording, [AutomationAccessibility] captures every interaction and
 * maps it to a [SemanticIntent] target. On stop, the captured targets are
 * persisted as a new [AutomationWorkflow] with encoded steps.
 *
 * The bubble uses TYPE_APPLICATION_OVERLAY so it stays visible across app
 * switches, and the service runs as a foreground service to avoid being
 * killed by the system.
 */
class FloatingRecorderService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var isRecording = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        showBubble()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideBubble()
        // Ensure recording state is cleared if the service is killed.
        if (isRecording) {
            AutomationAccessibility.isRecording = false
            AutomationAccessibility.capturedSemanticIntents.clear()
        }
    }

    // ── Bubble management ───────────────────────────────────────────────────

    private fun showBubble() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        val view = LayoutInflater.from(this).inflate(R.layout.floating_recorder_bubble, null)
        bubbleView = view

        // Record button
        view.findViewById<Button>(R.id.btnRecord).setOnClickListener {
            startRecording()
        }

        // Stop button
        view.findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopRecordingAndSave()
        }

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble overlay", e)
            stopSelf()
        }
    }

    private fun hideBubble() {
        bubbleView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove bubble", e)
            }
        }
        bubbleView = null
    }

    // ── Recording state ─────────────────────────────────────────────────────

    private fun startRecording() {
        isRecording = true
        AutomationAccessibility.isRecording = true
        AutomationAccessibility.capturedSemanticIntents.clear()

        // Update bubble UI
        bubbleView?.findViewById<TextView>(R.id.tvRecorderStatus)?.text = "🔴 Recording..."
        bubbleView?.findViewById<Button>(R.id.btnRecord)?.visibility = View.GONE
        bubbleView?.findViewById<Button>(R.id.btnStop)?.visibility = View.VISIBLE

        Log.i(TAG, "Recording started — switch to target app and perform actions")
    }

    private fun stopRecordingAndSave() {
        isRecording = false
        AutomationAccessibility.isRecording = false

        val captured = AutomationAccessibility.capturedSemanticIntents.toList()
        Log.i(TAG, "Recording stopped — captured ${captured.size} semantic intents")

        if (captured.isEmpty()) {
            Log.w(TAG, "No interactions captured — discarding empty recording")
            stopSelf()
            return
        }

        // Save as a new workflow
        serviceScope.launch {
            try {
                val database = AppDatabase.getInstance(applicationContext)
                val workflowDao = database.automationWorkflowDao()

                // Build a human-readable message from captured text
                val message = captured
                    .filterIsInstance<SemanticIntent.InputField>()
                    .firstOrNull { !it.text.isNullOrBlank() }
                    ?.text
                    ?: "Recorded routine"

                // Encode steps as SemanticIntent labels
                val steps = captured.map { it.label }

                val workflow = AutomationWorkflow(
                    targetApp = captured.first().viewId?.substringBefore(":")
                        ?: "unknown.app",
                    scheduledHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                    scheduledMinute = java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE),
                    targetMessage = WorkflowRepository.encodeMessage(message, steps),
                    isActive = false,  // User must enable it after review
                    isLocked = true
                )

                WorkflowRepository.save(workflowDao, workflow)
                Log.i(TAG, "Saved recorded workflow ${workflow.id} with ${steps.size} steps")

                // Show confirmation
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("recorded_workflow_id", workflow.id)
                }
                startActivity(intent)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to save recorded workflow", e)
            } finally {
                stopSelf()
            }
        }
    }

    // ── Notification ────────────────────────────────────────────────────────

    private fun createNotification(): Notification {
        val channelId = "floating_recorder_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating Recorder",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the floating record & replay bubble"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, channelId)
            .setContentTitle("NeuraAuto Recorder")
            .setContentText(if (isRecording) "Recording routine..." else "Ready to record")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "FloatingRecorderService"
        private const val NOTIFICATION_ID = 1007

        /** Start the floating recorder service. */
        fun start(context: Context) {
            val intent = Intent(context, FloatingRecorderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the floating recorder service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingRecorderService::class.java))
        }
    }
}
