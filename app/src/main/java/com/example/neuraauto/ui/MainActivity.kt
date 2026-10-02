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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}
