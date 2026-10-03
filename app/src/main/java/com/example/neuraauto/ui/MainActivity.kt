package com.example.neuraauto.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neuraauto.service.FloatingRecorderService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Phase 4.3 — voice commands need RECORD_AUDIO. Asked here, from a
        // normal user-initiated screen, rather than from the countdown overlay
        // (which appears on top of whatever the user was doing). Denial is
        // fine: the countdown falls back to button-only control.
        VoiceFeedbackController.requestAudioPermission(this, AUDIO_PERMISSION_REQUEST)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: DashboardViewModel = viewModel()
                    // Re-read the training summary whenever the screen returns
                    // to the foreground, since the worker runs out of process.
                    LifecycleResumeEffect(Unit) {
                        viewModel.refreshTrainingSummary()
                        onPauseOrDispose { }
                    }
                    DashboardScreen(
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onRefreshTrainingSummary = viewModel::refreshTrainingSummary,
                        onRecordNewRoutine = {
                            FloatingRecorderService.start(this@MainActivity)
                        },
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    companion object {
        private const val AUDIO_PERMISSION_REQUEST = 1001
    }
}
