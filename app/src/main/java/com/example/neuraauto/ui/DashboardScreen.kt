package com.example.neuraauto.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neuraauto.BuildConfig
import com.example.neuraauto.brain.ActionSequencePattern
import com.example.neuraauto.brain.ActionStep
import com.example.neuraauto.brain.ActionStepKind
import com.example.neuraauto.brain.RoutinePattern
import com.example.neuraauto.data.AppExclusionManager
import com.example.neuraauto.data.AutomationSettings
import com.example.neuraauto.data.AutomationWorkflow
import com.example.neuraauto.data.WorkflowRepository
import kotlinx.coroutines.launch

/**
 * Shown in the header so a build's phase is obvious at a glance.
 *
 * Derived from versionName rather than hardcoded, so a version bump cannot
 * leave the badge stale.
 */
val APP_VERSION_LABEL: String =
    "NeuraAuto AI v${BuildConfig.VERSION_NAME} (Phase 6.1: Swipe Tabs)"

// ── Badge helpers ──────────────────────────────────────────────────────────

/** High-contrast badge for workflow status. */
@Composable
private fun StatusBadge(text: String, containerColor: Color, contentColor: Color) {
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** Green badge for active workflows. */
@Composable
private fun ActiveBadge() = StatusBadge(
    text = "● Active",
    containerColor = Color(0xFF1B5E20),
    contentColor = Color.White
)

/** Amber badge for locked / long-term memory. */
@Composable
private fun LockedBadge() = StatusBadge(
    text = "🔒 Locked Memory",
    containerColor = Color(0xFFE65100),
    contentColor = Color.White
)

/** Blue badge for confidence score. */
@Composable
private fun ConfidenceBadge(percent: Int) = StatusBadge(
    text = "Confidence: $percent%",
    containerColor = Color(0xFF0D47A1),
    contentColor = Color.White
)

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

// ── Main Screen ────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshTrainingSummary: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()
    var showAddWorkflowDialog by remember { mutableStateOf(false) }

    val tabs = listOf("Dashboard", "Workflows", "Learned AI", "Settings")
    val tabIcons = listOf(
        Icons.Default.Home,
        Icons.Default.List,
        Icons.Default.Star,
        Icons.Default.Settings
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    ) {
        // Tab Row — synced with HorizontalPager
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = { Text(title) },
                    icon = { Icon(tabIcons[index], contentDescription = title) }
                )
            }
        }

        // Swipeable tab content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> DashboardTab(
                    state = state,
                    onAddWorkflow = { showAddWorkflowDialog = true },
                    onTestTrigger = viewModel::testTrigger,
                    onAnalyze = viewModel::analyze
                )
                1 -> WorkflowsTab(
                    state = state,
                    onToggle = { workflow, active ->
                        if (active) {
                            viewModel.enableExistingWorkflow(workflow)
                        } else {
                            viewModel.disableWorkflow(workflow)
                        }
                    },
                    onTestTrigger = viewModel::testTrigger,
                    onDismissTestResult = viewModel::clearTestResult,
                    onEdit = viewModel::updateWorkflow,
                    onDelete = viewModel::deleteWorkflow,
                    onEditSteps = { workflow, steps ->
                        viewModel.saveCorrectedSteps(workflow, steps)
                    }
                )
                2 -> LearnedAiTab(
                    state = state,
                    onAnalyze = viewModel::analyze,
                    onEnable = viewModel::enableWorkflow,
                    onDisable = viewModel::disableWorkflow,
                    onVerify = viewModel::verifyWorkflow,
                    onReject = viewModel::rejectWorkflow,
                    onEnableSequence = viewModel::enableSequenceWorkflow
                )
                3 -> SettingsTab(
                    state = state,
                    onToggleAutoEnable = viewModel::setAutoEnableEnabled,
                    onRunTrainingNow = {
                        viewModel.runTrainingNow()
                        onRefreshTrainingSummary()
                    },
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings
                )
            }
        }
    }

    // Add Workflow dialog
    if (showAddWorkflowDialog) {
        AddWorkflowDialog(
            onDismiss = { showAddWorkflowDialog = false },
            onSave = { workflow ->
                viewModel.addWorkflow(workflow)
                showAddWorkflowDialog = false
            }
        )
    }
}

// ── Tab 0: Dashboard (Home) ────────────────────────────────────────────────

@Composable
private fun DashboardTab(
    state: DashboardUiState,
    onAddWorkflow: () -> Unit,
    onTestTrigger: (AutomationWorkflow) -> Unit,
    onAnalyze: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Welcome header
        Text(
            text = "NeuraAuto AI",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "ระบบอัตโนมัติอัจฉริยะที่เรียนรู้จากพฤติกรรมของคุณ",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                label = "Workflows",
                value = "${state.workflows.size}",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Active",
                value = "${state.activeWorkflows.size}",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Logs",
                value = "${state.logCount}",
                modifier = Modifier.weight(1f)
            )
        }

        // Quick actions
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Quick Actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onAddWorkflow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("+ Add Workflow")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onAnalyze,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("วิเคราะห์พฤติกรรม (Analyze)")
                }
            }
        }

        // Active workflows quick view
        if (state.activeWorkflows.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Active Workflows",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    state.activeWorkflows.take(3).forEach { workflow ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = workflow.targetApp,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${"%02d".format(workflow.scheduledHour)}:${"%02d".format(workflow.scheduledMinute)} น.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                            TextButton(onClick = { onTestTrigger(workflow) }) {
                                Text("Test")
                            }
                        }
                        HorizontalDivider()
                    }

                    if (state.activeWorkflows.size > 3) {
                        Text(
                            text = "และอีก ${state.activeWorkflows.size - 3} รายการ...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // Test result
        state.testResult?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Version badge
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
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
}

// ── Add Workflow Dialog ────────────────────────────────────────────────────

@Composable
private fun AddWorkflowDialog(
    onDismiss: () -> Unit,
    onSave: (AutomationWorkflow) -> Unit
) {
    var packageName by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("12") }
    var minute by remember { mutableStateOf("00") }
    var message by remember { mutableStateOf("") }
    var stepsText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("+ Add Workflow") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "สร้าง workflow ใหม่ — ระบบจะเรียนรู้และเสริมสร้าง pattern weights ทุกครั้งที่ execute",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Package Name (e.g. com.linecorp.line)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = hour,
                        onValueChange = { hour = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Hour") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = minute,
                        onValueChange = { minute = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Minute") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = stepsText,
                    onValueChange = { stepsText = it },
                    label = { Text("Steps (comma-separated, optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pkg = packageName.trim()
                    if (pkg.isNotEmpty()) {
                        val h = hour.toIntOrNull()?.coerceIn(0, 23) ?: 12
                        val m = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0
                        val steps = stepsText.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val encodedMessage = WorkflowRepository.encodeMessage(
                            message = message.ifEmpty { "NeuraAuto AI automated message" },
                            steps = steps
                        )
                        onSave(
                            AutomationWorkflow(
                                targetApp = pkg,
                                scheduledHour = h,
                                scheduledMinute = m,
                                targetMessage = encodedMessage,
                                isActive = true,
                                isLocked = true
                            )
                        )
                    }
                },
                enabled = packageName.trim().isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Tab 1: Workflows ───────────────────────────────────────────────────────

@Composable
private fun WorkflowsTab(
    state: DashboardUiState,
    onToggle: (AutomationWorkflow, Boolean) -> Unit,
    onTestTrigger: (AutomationWorkflow) -> Unit,
    onDismissTestResult: () -> Unit,
    onEdit: (AutomationWorkflow) -> Unit,
    onDelete: (AutomationWorkflow) -> Unit,
    onEditSteps: (AutomationWorkflow, List<String>) -> Unit
) {
    var editingWorkflow by remember { mutableStateOf<AutomationWorkflow?>(null) }
    var stepEditorWorkflow by remember { mutableStateOf<AutomationWorkflow?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Active Workflows",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        if (state.workflows.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ยังไม่มีงานอัตโนมัติที่ตั้งไว้")
                    Text(
                        text = "ไปที่แท็บ Learned AI เพื่อเปิดใช้งานรูปแบบที่ตรวจพบ",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        } else {
            Text(
                text = "ทั้งหมด ${state.workflows.size} รายการ " +
                    "(เปิดใช้งาน ${state.activeWorkflows.size})",
                style = MaterialTheme.typography.bodySmall
            )

            state.workflows.forEach { workflow ->
                WorkflowCard(
                    workflow = workflow,
                    onToggle = { active -> onToggle(workflow, active) },
                    onTestTrigger = { onTestTrigger(workflow) },
                    onEdit = { editingWorkflow = workflow },
                    onDelete = { onDelete(workflow) },
                    onEditSteps = { stepEditorWorkflow = workflow }
                )
                HorizontalDivider()
            }
        }

        state.testResult?.let { message ->
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

    // Edit dialog
    editingWorkflow?.let { workflow ->
        WorkflowEditDialog(
            workflow = workflow,
            onDismiss = { editingWorkflow = null },
            onSave = { updated ->
                onEdit(updated)
                editingWorkflow = null
            }
        )
    }

    // Visual Step Editor dialog
    stepEditorWorkflow?.let { workflow ->
        StepEditorDialog(
            workflow = workflow,
            onDismiss = { stepEditorWorkflow = null },
            onSave = { correctedSteps ->
                onEditSteps(workflow, correctedSteps)
                stepEditorWorkflow = null
            }
        )
    }
}

@Composable
private fun WorkflowCard(
    workflow: AutomationWorkflow,
    onToggle: (Boolean) -> Unit,
    onTestTrigger: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEditSteps: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row with app identity and status badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AppIdentity(packageName = workflow.targetApp, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (workflow.isActive) ActiveBadge()
                    if (workflow.isLocked) LockedBadge()
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "ทุกวัน ${"%02d".format(workflow.scheduledHour)}:" +
                    "%02d".format(workflow.scheduledMinute) + " น.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "ข้อความ: ${WorkflowRepository.messageOf(workflow)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            // Show step count if multi-step
            val steps = WorkflowRepository.stepsFor(workflow)
            if (steps.isNotEmpty()) {
                Text(
                    text = "Steps: ${steps.size} (${steps.joinToString(" → ")})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (workflow.isActive) "เปิดใช้งาน" else "ปิดอยู่",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = workflow.isActive,
                    onCheckedChange = onToggle
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            OutlinedButton(
                onClick = onTestTrigger,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test Trigger Now")
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onEditSteps,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Edit Steps")
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Edit")
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

// ── Tab 2: Learned AI ──────────────────────────────────────────────────────

@Composable
private fun LearnedAiTab(
    state: DashboardUiState,
    onAnalyze: () -> Unit,
    onEnable: (String, Int) -> Unit,
    onDisable: (AutomationWorkflow) -> Unit,
    onVerify: (AutomationWorkflow) -> Unit,
    onReject: (AutomationWorkflow) -> Unit,
    onEnableSequence: (ActionSequencePattern) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "AI Discoveries",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Smart Recommendation section
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
                                onDisable = onDisable,
                                onVerify = onVerify,
                                onReject = onReject
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

        // Learned Workflows section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "🧩 Learned Workflows",
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
                                onEnable = { onEnableSequence(pattern) },
                                onDisable = onDisable,
                                onVerify = onVerify,
                                onReject = onReject
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineRow(
    routine: RoutinePattern,
    enabledWorkflow: AutomationWorkflow?,
    onEnable: () -> Unit,
    onDisable: (AutomationWorkflow) -> Unit,
    onVerify: (AutomationWorkflow) -> Unit,
    onReject: (AutomationWorkflow) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AppIdentity(packageName = routine.packageName)
        Text(
            text = "เวลา ${"%02d".format(routine.hourOfDay)}:00 น.",
            style = MaterialTheme.typography.bodySmall
        )
        ConfidenceBadge(routine.confidencePercent)
        Text(
            text = "(พบ ${routine.supportDays}/${routine.observedDays} วัน)",
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
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val wf = enabledWorkflow ?: AutomationWorkflow(
                            targetApp = routine.packageName,
                            scheduledHour = routine.hourOfDay,
                            scheduledMinute = 0,
                            targetMessage = "NeuraAuto AI automated message"
                        )
                        onVerify(wf)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Verify Pattern")
                }
                OutlinedButton(
                    onClick = {
                        val wf = enabledWorkflow ?: AutomationWorkflow(
                            targetApp = routine.packageName,
                            scheduledHour = routine.hourOfDay,
                            scheduledMinute = 0,
                            targetMessage = "NeuraAuto AI automated message"
                        )
                        onReject(wf)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reject")
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
    onDisable: (AutomationWorkflow) -> Unit,
    onVerify: (AutomationWorkflow) -> Unit,
    onReject: (AutomationWorkflow) -> Unit
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
            ConfidenceBadge(pattern.confidencePercent)
            Text(
                text = "(พบ ${pattern.supportDays}/${pattern.observedDays} วัน, " +
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
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val wf = enabledWorkflow ?: AutomationWorkflow(
                                targetApp = pattern.packageName,
                                scheduledHour = pattern.hourOfDay,
                                scheduledMinute = 0,
                                targetMessage = "NeuraAuto AI automated message"
                            )
                            onVerify(wf)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Verify Pattern")
                    }
                    OutlinedButton(
                        onClick = {
                            val wf = enabledWorkflow ?: AutomationWorkflow(
                                targetApp = pattern.packageName,
                                scheduledHour = pattern.hourOfDay,
                                scheduledMinute = 0,
                                targetMessage = "NeuraAuto AI automated message"
                            )
                            onReject(wf)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reject")
                    }
                }
            }
        }
    }
}

// ── Tab 3: Settings ────────────────────────────────────────────────────────

@Composable
private fun SettingsTab(
    state: DashboardUiState,
    onToggleAutoEnable: (Boolean) -> Unit,
    onRunTrainingNow: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Accessibility
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

        // Autonomous AI
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "🤖 Autonomous AI",
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

        // App Exclusion Settings
        AppExclusionSettingsCard()

        // Activity data info
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "ข้อมูลกิจกรรมที่เก็บได้", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "สลับแอป: บันทึกแล้ว ${state.logCount} เหตุการณ์")
                Text(text = "การใช้งานในแอป: ${state.actionCount} การกระทำ")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "ระบบบันทึกการสลับแอป (1 ครั้ง/ชั่วโมง/วัน) และการคลิก/พิมพ์ข้อความในแอป",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Version badge
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
}

// ── Visual Step Editor Dialog ──────────────────────────────────────────────

@Composable
private fun StepEditorDialog(
    workflow: AutomationWorkflow,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val currentSteps = WorkflowRepository.stepsFor(workflow)
    var steps by remember { mutableStateOf(currentSteps.toMutableList()) }
    var newStepText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Visual Step Editor") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Edit steps for ${workflow.targetApp}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (steps.isEmpty()) {
                    Text(
                        text = "No steps defined (legacy mode)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                steps.forEachIndexed { index, step ->
                    StepEditorRow(
                        step = step,
                        index = index,
                        onMoveUp = {
                            if (index > 0) {
                                val mutable = steps.toMutableList()
                                val item = mutable.removeAt(index)
                                mutable.add(index - 1, item)
                                steps = mutable
                            }
                        },
                        onMoveDown = {
                            if (index < steps.size - 1) {
                                val mutable = steps.toMutableList()
                                val item = mutable.removeAt(index)
                                mutable.add(index + 1, item)
                                steps = mutable
                            }
                        },
                        onDelete = {
                            val mutable = steps.toMutableList()
                            mutable.removeAt(index)
                            steps = mutable
                        },
                        onUpdate = { updated ->
                            val mutable = steps.toMutableList()
                            mutable[index] = updated
                            steps = mutable
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Add new step
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newStepText,
                        onValueChange = { newStepText = it },
                        label = { Text("New step") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (newStepText.isNotBlank()) {
                                val mutable = steps.toMutableList()
                                mutable.add(newStepText.trim())
                                steps = mutable
                                newStepText = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add step")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(steps) }) {
                Text("Save & Lock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StepEditorRow(
    step: String,
    index: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(step) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${index + 1}.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(24.dp)
            )

            if (editing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                TextButton(onClick = {
                    onUpdate(editText)
                    editing = false
                }) { Text("OK") }
            } else {
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    editText = step
                    editing = true
                }) { Text("Edit") }
            }

            IconButton(onClick = onMoveUp, enabled = index > 0) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = "Move up",
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onMoveDown) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = "Move down",
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Edit Dialog ────────────────────────────────────────────────────────────

@Composable
private fun WorkflowEditDialog(
    workflow: AutomationWorkflow,
    onDismiss: () -> Unit,
    onSave: (AutomationWorkflow) -> Unit
) {
    var hour by remember { mutableStateOf(workflow.scheduledHour.toString()) }
    var minute by remember { mutableStateOf(workflow.scheduledMinute.toString()) }
    var message by remember { mutableStateOf(WorkflowRepository.messageOf(workflow)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("แก้ไข Workflow") },
        text = {
            Column {
                Text("เวลา (ชั่วโมง):", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = hour,
                    onValueChange = { hour = it.filter { c -> c.isDigit() }.take(2) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("เวลา (นาที):", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = minute,
                    onValueChange = { minute = it.filter { c -> c.isDigit() }.take(2) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("ข้อความ:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = hour.toIntOrNull()?.coerceIn(0, 23) ?: workflow.scheduledHour
                val m = minute.toIntOrNull()?.coerceIn(0, 59) ?: workflow.scheduledMinute
                onSave(workflow.copy(scheduledHour = h, scheduledMinute = m, targetMessage = message))
            }) { Text("บันทึก") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ยกเลิก") }
        }
    )
}

// ── App Exclusion Settings ─────────────────────────────────────────────────

@Composable
private fun AppExclusionSettingsCard() {
    val context = LocalContext.current
    var bankingExcluded by remember { mutableStateOf(AppExclusionManager.isCategoryExcluded(context, AppExclusionManager.CATEGORY_BANKING)) }
    var mediaExcluded by remember { mutableStateOf(AppExclusionManager.isCategoryExcluded(context, AppExclusionManager.CATEGORY_MEDIA)) }
    var systemExcluded by remember { mutableStateOf(AppExclusionManager.isCategoryExcluded(context, AppExclusionManager.CATEGORY_SYSTEM)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "🚫 App Exclusion Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "เลือกประเภทแอปที่ไม่ต้การให้ระบบเรียนรู้",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Banking / Finance", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (bankingExcluded) "Excluded" else "Learning enabled",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = bankingExcluded,
                    onCheckedChange = {
                        bankingExcluded = it
                        AppExclusionManager.setCategoryExcluded(context, AppExclusionManager.CATEGORY_BANKING, it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Media / Entertainment", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (mediaExcluded) "Excluded" else "Learning enabled",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = mediaExcluded,
                    onCheckedChange = {
                        mediaExcluded = it
                        AppExclusionManager.setCategoryExcluded(context, AppExclusionManager.CATEGORY_MEDIA, it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("System Settings", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (systemExcluded) "Excluded" else "Learning enabled",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = systemExcluded,
                    onCheckedChange = {
                        systemExcluded = it
                        AppExclusionManager.setCategoryExcluded(context, AppExclusionManager.CATEGORY_SYSTEM, it)
                    }
                )
            }
        }
    }
}

// ── Utility ────────────────────────────────────────────────────────────────

/** Minimal local formatter; avoids pulling in a date library for one label. */
private fun formatTimestamp(millis: Long): String {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
    val month = calendar.get(java.util.Calendar.MONTH) + 1
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = calendar.get(java.util.Calendar.MINUTE)
    return "%02d/%02d %02d:%02d น.".format(day, month, hour, minute)
}
