package com.example.neuraauto.service

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 4.0 — intent-based node resolution.
 *
 * View-id lookups only work for apps whose internal resource names we already
 * know. Everywhere else the engine has to reason about *what a node is for*,
 * using the text and content description the user actually sees.
 *
 * Two capabilities:
 *  1. [findByIntent] — match text / contentDescription against a set of intent
 *     keywords ("ส่ง", "Send", "พิมพ์", …) instead of a resource id.
 *  2. Clickable-parent traversal — a matching TextView is frequently not itself
 *     clickable; the clickable target is an ancestor. Every hit walks up to the
 *     nearest clickable ancestor before being returned.
 */
object SmartNodeFinder {

    private const val TAG = "SmartNodeFinder"

    /** How far up the tree to look for a clickable ancestor. */
    private const val MAX_PARENT_DEPTH = 6

    // ── Intent keyword tables ───────────────────────────────────────────────

    /** Words that indicate "send / submit this". */
    val SEND_INTENTS = listOf(
        "ส่ง", "ส่งข้อความ", "ส่งเลย", "ส่ง",
        "send", "submit", "post", "share",
        "ตกลง", "ยืนยัน", "ok", "confirm", "done"
    )

    /** Words that indicate "type / compose here". */
    val INPUT_INTENTS = listOf(
        "พิมพ์", "พิมพ์ข้อความ", "ข้อความ", "เขียน",
        "type", "message", "text", "compose", "reply", "chat",
        "ค้นหา", "search"
    )

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Find the first node whose text or contentDescription matches any of
     * [intents], walking up to the nearest clickable ancestor.
     *
     * @param rootNode Root of the hierarchy to search.
     * @param intents Keyword list (see [SEND_INTENTS] / [INPUT_INTENTS]).
     * @param clickableOnly When true, only return nodes that are (or have an
     *   ancestor that is) clickable.
     * @return The resolved node, or null when nothing matches.
     */
    fun findByIntent(
        rootNode: AccessibilityNodeInfo,
        intents: List<String>,
        clickableOnly: Boolean = true
    ): AccessibilityNodeInfo? {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectMatching(rootNode, intents, matches)
        if (matches.isEmpty()) {
            Log.d(TAG, "No node matched intents ${intents.take(3)}")
            return null
        }

        for (match in matches) {
            if (!clickableOnly) return match
            val clickable = findClickableAncestor(match)
            if (clickable != null) {
                Log.d(TAG, "Matched '${labelOf(match)}' -> clickable ancestor")
                return clickable
            }
        }
        Log.d(TAG, "${matches.size} intent match(es) but none clickable")
        return null
    }

    /**
     * Walk up from [node] to the nearest clickable ancestor.
     *
     * Returns [node] itself when it is already clickable. Bounded by
     * [MAX_PARENT_DEPTH] so a malformed tree cannot loop.
     */
    fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (current.isClickable && current.isEnabled) return current
            current = try {
                current.parent
            } catch (e: Exception) {
                Log.w(TAG, "parent lookup failed", e)
                null
            }
            depth++
        }
        return null
    }

    /**
     * Find an editable input field, preferring intent matches over the
     * "first editable node" heuristic.
     */
    fun findInputByIntent(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val intentMatch = findByIntent(rootNode, INPUT_INTENTS, clickableOnly = false)
        if (intentMatch != null && intentMatch.isEditable) return intentMatch

        val editable = mutableListOf<AccessibilityNodeInfo>()
        collectEditable(rootNode, editable)
        return editable.firstOrNull()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun collectMatching(
        node: AccessibilityNodeInfo,
        intents: List<String>,
        sink: MutableList<AccessibilityNodeInfo>
    ) {
        if (matchesAnyIntent(node, intents)) sink.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMatching(child, intents, sink)
        }
    }

    private fun collectEditable(
        node: AccessibilityNodeInfo,
        sink: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isEditable && !node.isPassword) sink.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditable(child, sink)
        }
    }

    /**
     * Case-insensitive substring match of the node's text or content
     * description against the intent list.
     */
    private fun matchesAnyIntent(
        node: AccessibilityNodeInfo,
        intents: List<String>
    ): Boolean {
        val haystack = buildString {
            node.text?.let { append(it).append(' ') }
            node.contentDescription?.let { append(it).append(' ') }
            node.hintText?.let { append(it) }
        }.trim().lowercase()

        if (haystack.isEmpty()) return false
        return intents.any { haystack.contains(it.lowercase()) }
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        (node.text ?: node.contentDescription)?.toString()?.take(24) ?: "?"
}
