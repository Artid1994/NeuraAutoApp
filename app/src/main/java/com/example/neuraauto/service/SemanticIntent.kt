package com.example.neuraauto.service

/**
 * Phase 7.0 — Semantic Intent targets.
 *
 * Raw View IDs and XY coordinates are fragile: a UI layout change breaks
 * replay. SemanticIntent maps those raw signals into stable, human-meaningful
 * targets (SEND_BUTTON, INPUT_FIELD, SEARCH_BAR, …) so the AI replay engine
 * can locate the right node even when resource names or positions shift.
 *
 * Each target carries the raw capture data alongside the semantic label, so
 * the replay engine can fall back to coordinate-based matching when intent
 * matching fails.
 */
sealed class SemanticIntent {

    /** Stable semantic label, e.g. "SEND_BUTTON". */
    abstract val label: String

    /** Raw View ID captured at record time, when available. */
    abstract val viewId: String?

    /** Centre X in screen pixels at record time. */
    abstract val centerX: Int

    /** Centre Y in screen pixels at record time. */
    abstract val centerY: Int

    // ── Concrete targets ────────────────────────────────────────────────────

    /**
     * A button whose purpose is to send / submit / confirm.
     *
     * Matched by intent keywords ("ส่ง", "Send", …) or by view-id fragments
     * ("send", "submit", "post").
     */
    data class SendButton(
        override val viewId: String?,
        val text: String?,
        val contentDescription: String?,
        override val centerX: Int,
        override val centerY: Int
    ) : SemanticIntent() {
        override val label: String get() = "SEND_BUTTON"
    }

    /**
     * An editable text input field where the user types a message.
     *
     * Matched by intent keywords ("พิมพ์", "type", "message", …) or by the
     * node's `isEditable` flag.
     */
    data class InputField(
        override val viewId: String?,
        val hint: String?,
        val text: String?,
        override val centerX: Int,
        override val centerY: Int
    ) : SemanticIntent() {
        override val label: String get() = "INPUT_FIELD"
    }

    /**
     * A search bar — an input field whose intent is search rather than
     * message composition.
     */
    data class SearchBar(
        override val viewId: String?,
        val hint: String?,
        override val centerX: Int,
        override val centerY: Int
    ) : SemanticIntent() {
        override val label: String get() = "SEARCH_BAR"
    }

    /**
     * A generic clickable target that doesn't fit a more specific category.
     */
    data class ClickTarget(
        override val viewId: String?,
        val text: String?,
        val contentDescription: String?,
        override val centerX: Int,
        override val centerY: Int
    ) : SemanticIntent() {
        override val label: String get() = "CLICK_TARGET"
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────

    companion object {

        /**
         * Map a raw captured interaction to a SemanticIntent target.
         *
         * Classification order:
         *  1. View-id fragment match (fast, deterministic)
         *  2. Intent keyword match on text / contentDescription
         *  3. Editable-node heuristic for input fields
         *  4. Generic clickable fallback
         *
         * @param viewId  raw resource name, or null
         * @param text    node text, or null
         * @param contentDescription  node content description, or null
         * @param isEditable  whether the node is an editable text field
         * @param isClickable  whether the node (or an ancestor) is clickable
         * @param centerX  centre X in screen pixels
         * @param centerY  centre Y in screen pixels
         * @param eventType  "CLICK" or "TEXT_CHANGE"
         */
        fun classify(
            viewId: String?,
            text: String?,
            contentDescription: String?,
            isEditable: Boolean,
            isClickable: Boolean,
            centerX: Int,
            centerY: Int,
            eventType: String
        ): SemanticIntent {
            val haystack = buildString {
                text?.let { append(it).append(' ') }
                contentDescription?.let { append(it).append(' ') }
            }.trim().lowercase()

            // 1. View-id fragment match
            val id = viewId?.lowercase()
            if (id != null) {
                if (SEND_ID_MARKERS.any { it in id }) {
                    return SendButton(viewId, text, contentDescription, centerX, centerY)
                }
                if (INPUT_ID_MARKERS.any { it in id }) {
                    return InputField(viewId, text, text, centerX, centerY)
                }
                if (SEARCH_ID_MARKERS.any { it in id }) {
                    return SearchBar(viewId, text, centerX, centerY)
                }
            }

            // 2. Intent keyword match
            if (haystack.isNotEmpty()) {
                if (SmartNodeFinder.SEND_INTENTS.any { haystack.contains(it.lowercase()) }) {
                    return SendButton(viewId, text, contentDescription, centerX, centerY)
                }
                if (SmartNodeFinder.INPUT_INTENTS.any { haystack.contains(it.lowercase()) }) {
                    // Distinguish search from message input
                    if (SEARCH_INTENTS.any { haystack.contains(it.lowercase()) }) {
                        return SearchBar(viewId, text, centerX, centerY)
                    }
                    return InputField(viewId, text, text, centerX, centerY)
                }
            }

            // 3. Editable-node heuristic
            if (isEditable && eventType == "TEXT_CHANGE") {
                return InputField(viewId, text, text, centerX, centerY)
            }

            // 4. Generic clickable fallback
            if (isClickable || eventType == "CLICK") {
                return ClickTarget(viewId, text, contentDescription, centerX, centerY)
            }

            // 5. Last resort: click target
            return ClickTarget(viewId, text, contentDescription, centerX, centerY)
        }

        private val SEND_ID_MARKERS = listOf("send", "submit", "post")
        private val INPUT_ID_MARKERS = listOf("input", "edit", "text_field", "textfield", "message")
        private val SEARCH_ID_MARKERS = listOf("search", "query")
        private val SEARCH_INTENTS = listOf("ค้นหา", "search", "ค้น")
    }
}
