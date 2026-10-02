package com.example.neuraauto.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.neuraauto.brain.NeuralBrainEngine

class MainActivity : ComponentActivity() {
    private val brainEngine = NeuralBrainEngine()

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
                        },
                        brainEngine = brainEngine
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    onOpenAccessibilitySettings: () -> Unit,
    brainEngine: NeuralBrainEngine
) {
    var confidenceText by remember { mutableStateOf("ยังไม่ได้ประมวลผล") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "🧠 NeuraAuto AI Dashboard",
            style = MaterialTheme.typography.headlineMedium
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "สถานะสิทธิ์การควบคุมเครื่อง", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("เปิดสิทธิ์ Accessibility Service")
                }            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "การทำนายของสมองนิวรอน", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "ความมั่นใจสำหรับ Workflow 08:00 น.: $confidenceText")
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val score = brainEngine.predictWorkflowConfidence(hour = 8, isWeekday = true)
                        confidenceText = "%.2f%%".format(score * 100)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ทดสอบคำนวณ Neural Confidence")
                }
            }        }
    }
}
