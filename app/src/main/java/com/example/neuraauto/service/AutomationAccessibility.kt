package com.example.neuraauto.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutomationAccessibility : AccessibilityService() {

    companion object {
        var instance: AutomationAccessibility? = null
            private set
        var pendingTaskAction: String? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return

        if (pendingTaskAction == "SEND_LINE_MSG") {
            executeLineWorkflow(rootNode)
        }
    }

    private fun executeLineWorkflow(rootNode: AccessibilityNodeInfo) {
        val inputNodes = rootNode.findAccessibilityNodeInfosByViewId("com.linecorp.line:id/chat_ui_input_edit_text")
        if (!inputNodes.isNullOrEmpty()) {
            val inputNode = inputNodes[0]
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "สวัสดีครับ ข้อความนี้ถูกส่งโดย NeuraAuto AI"
                )
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            val sendButtons = rootNode.findAccessibilityNodeInfosByViewId("com.linecorp.line:id/chat_ui_send_button")
            if (!sendButtons.isNullOrEmpty()) {
                sendButtons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingTaskAction = null
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}
}
