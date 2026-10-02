package com.example.neuraauto.brain

import com.example.neuraauto.data.InAppActionLog

/** What one step of a learned workflow does. */
enum class ActionStepKind {
    OPEN_APP,
    CLICK_INPUT,
    TYPE_TEXT,
    CLICK_SEND,
    CLICK
}

/**
 * One step of a reconstructed workflow.
 *
 * @param label  human-readable rendering, e.g. "Type text".
 * @param viewId resource name when the node exposed one.
 * @param textSnippet excerpt of what was typed, for TYPE_TEXT steps.
 */
data class ActionStep(
    val kind: ActionStepKind,
    val label: String,
    val viewId: String? = null,
    val textSnippet: String? = null
) {
    /** True when this step actually does something worth automating. */
    fun isActionable(): Boolean = kind == ActionStepKind.TYPE_TEXT ||
        kind == ActionStepKind.CLICK_SEND
}

/**
 * A recurring multi-step workflow, e.g.
 * `Open LINE → Click Input → Type Text → Click Send`.
 */
data class ActionSequencePattern(
    val packageName: String,
    val hourOfDay: Int,
    val steps: List<ActionStep>,
    val confidence: Float,
    val supportDays: Int,
    val observedDays: Int,
    val occurrences: Int
) {
    val confidencePercent: Int get() = (confidence * 100).toInt()

    /** `Open LINE → Type Text → Click Send` */
    val summary: String get() = steps.joinToString(" → ") { it.label }
}

/** Sequence-detection result for one app/hour pair. */
data class SequenceAnalysis(
    val packageName: String,
    val hourOfDay: Int,
    val observedDays: Int,
    val patterns: List<ActionSequencePattern>
) {
    val topPattern: ActionSequencePattern? get() = patterns.firstOrNull()
}

/**
 * Turns a flat list of captured interactions into readable steps.
 *
 * The reconstruction rules are deliberately simple and inspectable:
 *  - a sequence always starts by opening the app;
 *  - consecutive text edits to the *same* field collapse into one TYPE_TEXT
 *    step, because typing a sentence emits one event per keystroke and nobody
 *    wants a twenty-step workflow that says "Type text" twenty times;
 *  - clicks are classified by their view id, falling back to a generic CLICK
 *    when the id is unhelpful.
 */
object ActionStepBuilder {

    /** Consecutive edits to the same field merge into one step. */
    fun build(packageName: String, rows: List<InAppActionLog>): List<ActionStep> {
        if (rows.isEmpty()) return emptyList()

        val steps = mutableListOf(
            ActionStep(ActionStepKind.OPEN_APP, "Open ${appLabel(packageName)}")
        )
        var lastTextStepIndex = -1

        for (row in rows.sortedBy { it.stepIndex }) {
            when (row.eventType) {
                InAppActionLog.EVENT_CLICK -> {
                    val kind = classifyClick(row.viewId)
                    steps += ActionStep(kind, clickLabel(kind), row.viewId)
                    // A click ends the current typing run.
                    lastTextStepIndex = -1
                }

                InAppActionLog.EVENT_TEXT_CHANGE -> {
                    val existing = lastTextStepIndex
                    if (existing >= 0 && steps[existing].viewId == row.viewId) {
                        // Same field, keep the longest excerpt we have seen.
                        val current = steps[existing]
                        if ((row.textSnippet?.length ?: 0) > (current.textSnippet?.length ?: 0)) {
                            steps[existing] = current.copy(textSnippet = row.textSnippet)
                        }
                    } else {
                        steps += ActionStep(
                            kind = ActionStepKind.TYPE_TEXT,
                            label = "Type Text",
                            viewId = row.viewId,
                            textSnippet = row.textSnippet
                        )
                        lastTextStepIndex = steps.size - 1
                    }
                }
            }
        }

        return steps
    }

    /** Signature used to decide whether two sequences are the "same" workflow. */
    fun signature(steps: List<ActionStep>): String =
        steps.joinToString(">") { it.kind.name }

    private fun classifyClick(viewId: String?): ActionStepKind {
        val id = viewId?.lowercase() ?: return ActionStepKind.CLICK
        return when {
            SEND_MARKERS.any { it in id } -> ActionStepKind.CLICK_SEND
            INPUT_MARKERS.any { it in id } -> ActionStepKind.CLICK_INPUT
            else -> ActionStepKind.CLICK
        }
    }

    private fun clickLabel(kind: ActionStepKind): String = when (kind) {
        ActionStepKind.CLICK_SEND -> "Click Send"
        ActionStepKind.CLICK_INPUT -> "Click Input"
        ActionStepKind.OPEN_APP -> "Open App"
        ActionStepKind.TYPE_TEXT -> "Type Text"
        ActionStepKind.CLICK -> "Click"
    }

    /** Readable app name for the OPEN_APP step. */
    fun appLabel(packageName: String): String = when (packageName) {
        "com.linecorp.line" -> "LINE"
        else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private val SEND_MARKERS = listOf("send", "submit", "post")
    private val INPUT_MARKERS = listOf("input", "edit", "text_field", "textfield", "message")
}
