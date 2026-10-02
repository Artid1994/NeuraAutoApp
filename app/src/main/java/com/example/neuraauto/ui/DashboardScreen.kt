package com.example.neuraauto.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neuraauto.BuildConfig
import com.example.neuraauto.brain.ActionSequencePattern
import com.example.neuraauto.brain.RoutinePattern
import com.example.neuraauto.data.AutomationSettings
import com.example.neuraauto.data.AutomationWorkflow
import com.example.neuraauto.data.WorkflowRepository

/**
 * Shown in the header so a build's phase is obvious at a glance.
 *
 * Derived from versionName rather than hardcoded, so a version bump cannot
 * leave the badge stale.
 */
val APP_VERSION_LABEL: String =
    "NeuraAuto AI v${BuildConfig.VERSION_NAME} (Phase 3.2: Autonomous AI)"

/** 28dp app icon, or a lettered placeholder when the app cannot be resolved. */
@Composable
private fun AppIcon(packageName: String, size: Int = 28) {
    val context = LocalContext.current
    val resolver = remember(context) { AppInfoResolver(context) }
    val drawable = remember(packageName) { resolver.iconFor(packageName) }

    if (drawable != null) {
        val bitmap = remember(packageName) {
            // Rasterising once per package keeps recomposition cheap.
            runCatching { drawable.toBitmap(size, size).asImageBitmap() }.getOrNull()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = resolver.labelFor(packageName),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size.dp)
            )
            return
        }
    }

    // Uninstalled or hidden package: show the first letter rather than a gap.
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.size(size.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = resolver.labelFor(packageName).take(1).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Icon + human-readable name, used everywhere a package is shown. */
@Composable
private fun AppIdentity(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resolver = remember(context) { AppInfoResolver(context) }
    val label = remember(packageName) { resolver.labelFor(packageName) }
    val installed = remember(packageName) { resolver.isInstalled(packageName) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppIcon(packageName)
        Column {
            Text(text = label, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (installed) packageName else "$packageName (ไม่ได้ติดตั้ง)",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun AutonomousAiCard(
    state: DashboardUiState,
    onToggleAutoEnable: (Boolean) -> Unit,
    onRunTrainingNow: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "🤖 Autonomous AI (Phase 3.2)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "ระบบเรียนรู้และตั้งเวลาอัตโนมัติให้เองทุกวัน " +
                    "โดยไม่ต้องกดปุ่ม (เมื่อมั่นใจเกิน 80%)",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("เปิดใช้งานอัตโนมัติ", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (state.autoEnableEnabled) {
                            "ทำงานเบื้องหลัง — อาจส่งข้อความโดยไม่มีคนเฝ้า"
                        } else {
                            "ปิดอยู่ — ระบบจะแนะนำแต่ไม่ตั้งเวลาให้เอง"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = state.autoEnableEnabled,
                    onCheckedChange = onToggleAutoEnable
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            val run = state.lastTrainingRun
            if (run.hasRun) {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "รอบล่าสุด: ${formatTimestamp(run.timestampMillis)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "พบรูปแบบ ${run.patternsFound} รายการ • " +
                        "ตั้งเวลาให้อัตโนมัติ ${run.autoEnabled} รายการ",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "คะแนนจากโมเดล (mean activation): " +
                        "%.4f".format(run.meanScore),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            } else {
                Text(
                    text = "ยังไม่เคยรัน — จะเริ่มหลังเครื่องชาร์จและต่อ Wi-Fi",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRunTrainingNow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ฝึกโมเดลเดี๋ยวนี้ (Run Training Now)")
            }
        }
    }
}

/** Minimal local formatter; avoids pulling in a date library for one label. */
private fun formatTimestamp(millis: Long): String {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
    val month = calendar.get(java.util.Calendar.MONTH) + 1
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = calendar.get(java.util.Calendar.MINUTE)
    return "%02d/%02d %02d:%02d น.".format(day, month, hour, minute)
}

@Composable
fun DashboardScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshTrainingSummary: () -> Unit = {},
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

        ActivityDataCard(
            logCount = state.logCount,
            actionCount = state.actionCount
        )

        SmartRecommendationCard(
            state = state,
            onAnalyze = viewModel::analyze,
            onEnable = viewModel::enableWorkflow,
            onDisable = viewModel::disableWorkflow
        )

        AutonomousAiCard(
            state = state,
            onToggleAutoEnable = viewModel::setAutoEnableEnabled,
            onRunTrainingNow = {
                viewModel.runTrainingNow()
                onRefreshTrainingSummary()
            }
        )

        LearnedWorkflowCard(
            state = state,
            onEnable = viewModel::enableSequenceWorkflow,
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
private fun ActivityDataCard(logCount: Int, actionCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "ข้อมูลกิจกรรมที่เก็บได้", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "สลับแอป: บันทึกแล้ว $logCount เหตุการณ์")
            Text(text = "การใช้งานในแอป: $actionCount การกระทำ")
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "ระบบบันทึกการสลับแอป (1 ครั้ง/ชั่วโมง/วัน) และการคลิก/พิมพ์ข้อความในแอป",
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
        AppIdentity(packageName = routine.packageName)
        Text(
            text = "เวลา ${"%02d".format(routine.hourOfDay)}:00 น.",
            style = MaterialTheme.typography.bodySmall
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
private fun LearnedWorkflowCard(
    state: DashboardUiState,
    onEnable: (ActionSequencePattern) -> Unit,
    onDisable: (AutomationWorkflow) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "🧩 Learned Workflows (Phase 3.1)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "สร้างจากลำดับการคลิก/พิมพ์ที่ระบบเก็บได้ในแต่ละวัน",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (state.sequences.isEmpty()) {
                Text(
                    text = "ยังไม่พบลำดับงานที่มั่นใจเกิน 80%\n" +
                        "(เก็บได้ ${state.actionCount} การกระทำ — " +
                        "ต้องมีการพิมพ์ข้อความหรือกดส่งอย่างน้อย 1 ขั้นตอน)"
                )
            } else {
                state.sequences.forEach { sequence ->
                    sequence.patterns.forEach { pattern ->
                        SequenceRow(
                            pattern = pattern,
                            enabledWorkflow = state.workflowFor(
                                pattern.packageName,
                                pattern.hourOfDay
                            ),
                            onEnable = { onEnable(pattern) },
                            onDisable = onDisable
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SequenceRow(
    pattern: ActionSequencePattern,
    enabledWorkflow: AutomationWorkflow?,
    onEnable: () -> Unit,
    onDisable: (AutomationWorkflow) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            AppIdentity(packageName = pattern.packageName)
            Text(
                text = "เวลา ${"%02d".format(pattern.hourOfDay)}:00 น.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "ความมั่นใจ ${pattern.confidencePercent}% " +
                    "(พบ ${pattern.supportDays}/${pattern.observedDays} วัน, " +
                    "${pattern.occurrences} ครั้ง)",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(6.dp))

            // The actual step-by-step flow, one line per step.
            pattern.steps.forEachIndexed { index, step ->
                val detail = step.textSnippet?.let { " — \"$it\"" } ?: ""
                Text(
                    text = "${index + 1}. ${step.label}$detail",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
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
                AppIdentity(packageName = workflow.targetApp)
                Spacer(modifier = Modifier.height(4.dp))
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
            text = "ข้อความ: ${WorkflowRepository.messageOf(workflow)}",
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
