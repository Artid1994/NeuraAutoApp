package com.example.neuraauto.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neuraauto.brain.RoutinePattern
import com.example.neuraauto.data.AutomationWorkflow

/** Shown in the header so a build's phase is obvious at a glance. */
const val APP_VERSION_LABEL = "NeuraAuto AI v3.0.0 (Phase 3: Execution Engine)"

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

        VersionBadge()

        AccessibilityCard(onOpenAccessibilitySettings = onOpenAccessibilitySettings)

        ActivityDataCard(logCount = state.logCount)

        SmartRecommendationCard(
            state = state,
            onAnalyze = viewModel::analyze,
            onEnable = viewModel::enableWorkflow,
            onDisable = viewModel::disableWorkflow
        )

        WorkflowManagementCard(
            state = state,
            onToggle = { workflow, active ->
                if (active) {
                    viewModel.enableExistingWorkflow(workflow)
                } else {
                    viewModel.disableWorkflow(workflow)
                }
            },
            onTestTrigger = viewModel::testTrigger,
            onDismissTestResult = viewModel::clearTestResult
        )
    }
}

@Composable
private fun VersionBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = APP_VERSION_LABEL,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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
    onEnable: (String, Int) -> Unit,
    onDisable: (AutomationWorkflow) -> Unit
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
                            enabledWorkflow = state.workflowFor(
                                routine.packageName,
                                routine.hourOfDay
                            ),
                            onEnable = { onEnable(routine.packageName, routine.hourOfDay) },
                            onDisable = onDisable
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
    enabledWorkflow: AutomationWorkflow?,
    onEnable: () -> Unit,
    onDisable: (AutomationWorkflow) -> Unit
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

        val active = enabledWorkflow?.isActive == true
        if (active && enabledWorkflow != null) {
            Text(
                text = "✓ ตั้งเวลาอัตโนมัติทุกวันแล้ว",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = { onDisable(enabledWorkflow) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ปิดการทำงานอัตโนมัติ")
            }
        } else {
            OutlinedButton(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                Text("Enable Automation")
            }
        }
    }
}

@Composable
private fun WorkflowManagementCard(
    state: DashboardUiState,
    onToggle: (AutomationWorkflow, Boolean) -> Unit,
    onTestTrigger: (AutomationWorkflow) -> Unit,
    onDismissTestResult: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "⚙️ Active Workflows (Phase 3)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (state.workflows.isEmpty()) {
                Text("ยังไม่มีงานอัตโนมัติที่ตั้งไว้")
            } else {
                Text(
                    text = "ทั้งหมด ${state.workflows.size} รายการ " +
                        "(เปิดใช้งาน ${state.activeWorkflows.size})",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                state.workflows.forEach { workflow ->
                    WorkflowRow(
                        workflow = workflow,
                        onToggle = { active -> onToggle(workflow, active) },
                        onTestTrigger = { onTestTrigger(workflow) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            state.testResult?.let { message ->
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismissTestResult) { Text("ปิด") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowRow(
    workflow: AutomationWorkflow,
    onToggle: (Boolean) -> Unit,
    onTestTrigger: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workflow.targetApp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "ทุกวัน ${"%02d".format(workflow.scheduledHour)}:" +
                        "%02d".format(workflow.scheduledMinute) + " น.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = workflow.isActive,
                onCheckedChange = onToggle
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "ข้อความ: ${workflow.targetMessage}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onTestTrigger,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Test Trigger Now")
        }
    }
}
