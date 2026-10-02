package com.example.neuraauto.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neuraauto.brain.RoutinePattern

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen(
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    onOpenAccessibilitySettings: () -> Unit,
    viewModel: DashboardViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "🧠 NeuraAuto AI Dashboard",
            style = MaterialTheme.typography.headlineMedium
        )

        AccessibilityCard(onOpenAccessibilitySettings = onOpenAccessibilitySettings)

        ActivityDataCard(logCount = state.logCount)

        SmartRecommendationCard(
            state = state,
            onAnalyze = viewModel::analyze,
            onEnable = viewModel::enableRoutine
        )
    }
}

@Composable
private fun AccessibilityCard(onOpenAccessibilitySettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "สถานะสิทธิ์การควบคุมเครื่อง", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("เปิดสิทธิ์ Accessibility Service")
            }
        }
    }
}

@Composable
private fun ActivityDataCard(logCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "ข้อมูลกิจกรรมที่เก็บได้", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "บันทึกแล้ว $logCount เหตุการณ์")
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "ระบบจะบันทึกทุกครั้งที่คุณสลับแอป (สูงสุด 1 ครั้ง/ชั่วโมง/วัน)",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SmartRecommendationCard(
    state: DashboardUiState,
    onAnalyze: () -> Unit,
    onEnable: (String, Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "✨ Smart AI Recommendation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            val analysis = state.analysis
            when {
                analysis == null -> {
                    Text("กดปุ่มด้านล่างเพื่อวิเคราะห์พฤติกรรมการใช้งานของคุณ")
                }

                analysis.routines.isEmpty() -> {
                    Text(
                        text = "ยังไม่พบรูปแบบที่มั่นใจเกิน 80%\n" +
                            "(วิเคราะห์จาก ${analysis.analyzedLogs} เหตุการณ์)"
                    )
                }

                else -> {
                    Text(
                        text = "พบ ${analysis.routines.size} รูปแบบ จาก ${analysis.analyzedLogs} เหตุการณ์",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    analysis.routines.forEach { routine ->
                        RoutineRow(
                            routine = routine,
                            enabled = state.enabledRoutines
                                .contains(state.routineKey(routine.packageName, routine.hourOfDay)),
                            onEnable = { onEnable(routine.packageName, routine.hourOfDay) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onAnalyze,
                enabled = !state.isAnalyzing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isAnalyzing) "กำลังวิเคราะห์..." else "วิเคราะห์รูปแบบการใช้งาน")
            }
        }
    }
}

@Composable
private fun RoutineRow(
    routine: RoutinePattern,
    enabled: Boolean,
    onEnable: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${routine.packageName} • ${"%02d".format(routine.hourOfDay)}:00 น.",
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "ความมั่นใจ ${routine.confidencePercent}% " +
                "(พบ ${routine.supportDays}/${routine.observedDays} วัน)",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (enabled) {
            Text(
                text = "✓ เปิดใช้งานอัตโนมัติแล้ว",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            OutlinedButton(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                Text("Enable Automation")
            }
        }
    }
}
