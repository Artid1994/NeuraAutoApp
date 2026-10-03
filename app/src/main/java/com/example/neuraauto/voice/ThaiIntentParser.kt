package com.example.neuraauto.voice

import android.util.Log

/**
 * High-level intent categories the parser can extract from Thai text.
 */
enum class IntentType {
    /** Send a message to a target app (e.g., LINE, Messenger). */
    SEND_MESSAGE,

    /** Open an installed app. */
    OPEN_APP,

    /** Toggle a system setting (Wi-Fi, Bluetooth, DND, etc.). */
    SYSTEM_ACTION
}

/**
 * Structured result of parsing a Thai intent.
 *
 * @property type       The high-level intent category.
 * @property target     For SEND_MESSAGE: the recipient name/number.
 *                      For OPEN_APP: the app name.
 *                      For SYSTEM_ACTION: the system action code (e.g., "TOGGLE_WIFI").
 * @property payload    For SEND_MESSAGE: the message text.
 *                      For OPEN_APP: empty.
 *                      For SYSTEM_ACTION: the full action command (e.g., "TOGGLE_WIFI:ON").
 * @property appName    For SEND_MESSAGE/OPEN_APP: the resolved app name (e.g., "ไลน์").
 *                      For SYSTEM_ACTION: empty.
 * @property confidence Parse confidence (0.0..1.0). 1.0 = deterministic keyword match.
 * @property raw        The original input text.
 */
data class ParsedIntent(
    val type: IntentType,
    val target: String = "",
    val payload: String = "",
    val appName: String = "",
    val confidence: Float = 1.0f,
    val raw: String = ""
)

/**
 * Phase 6.2 — Offline Thai Intent Parser Engine.
 *
 * Extracts structured [ParsedIntent] objects from Thai spoken or written input
 * using rule-based keyword matching. No LLM, no network — everything runs on-device,
 * so the parser works offline and has no external dependencies.
 *
 * Three intent types are supported:
 *  - [IntentType.SEND_MESSAGE]: "ส่งไลน์หา [target] ว่า [payload]"
 *  - [IntentType.OPEN_APP]: "เปิดแอป [app]" or "เปิด [app]"
 *  - [IntentType.SYSTEM_ACTION]: "ปิดไวฟ์", "เปิดบลูทูธ", "ปิดเสียง", etc.
 *
 * The parser uses Thai keyword anchors ("ส่ง", "เปิด", "ปิด", "หา", "ว่า")
 * and two curated lookup tables — app names and system keywords — to extract
 * intent components without Thai word segmentation, exploiting the fact that
 * target app names and recipients are typically short, recognisable tokens.
 *
 * Parse order is deliberately specific-first:
 *  1. System action (checks for system keywords that only make sense with
 *     เปิด/ปิด, so "เปิดไวฟ์" is never confused with "เปิดแอปไลน์").
 *  2. Send message (anchored on "ส่ง").
 *  3. Open app (anchored on "เปิด" with a recognised app name).
 */
object ThaiIntentParser {

    private const val TAG = "ThaiIntentParser"

    // ── Lookup tables ────────────────────────────────────────────────────────

    /**
     * Thai and English app names mapped to their Android package names.
     *
     * Keys are matched case-insensitively via substring search; longer keys are
     * tried first so that "เฟสบุ๊ค" is preferred over "เฟส" when both could
     * match the same text.
     */
    private val APP_NAME_MAP: Map<String, String> = linkedMapOf(
        // Messaging
        "ไลน์" to "com.linecorp.line",
        "line" to "com.linecorp.line",
        "เมสเซนเกอร์" to "com.facebook.orca",
        "messenger" to "com.facebook.orca",
        // Social
        "เฟส" to "com.facebook.katana",
        "เฟสบุ๊ค" to "com.facebook.katana",
        "facebook" to "com.facebook.katana",
        "อินสตาแกรม" to "com.instagram.android",
        "instagram" to "com.instagram.android",
        "ทวิตเตอร์" to "com.twitter.android",
        "twitter" to "com.twitter.android",
        "whatsapp" to "com.whatsapp",
        "วอตซ่าฟ์" to "com.whatsapp",
        // Media / productivity
        "ยูทูบ" to "com.google.android.youtube",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "เมล" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "แผนที่ google" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome"
    )

    /**
     * Thai and English system keywords mapped to their action codes.
     *
     * The action code becomes the [ParsedIntent.target] for SYSTEM_ACTION,
     * and the full "CODE:STATE" string becomes the [ParsedIntent.payload].
     */
    private val SYSTEM_KEYWORDS: Map<String, String> = linkedMapOf(
        // Connectivity
        "ไวฟ์" to "TOGGLE_WIFI",
        "wifi" to "TOGGLE_WIFI",
        "wi-fi" to "TOGGLE_WIFI",
        "บลูทูธ" to "TOGGLE_BLUETOOTH",
        "bluetooth" to "TOGGLE_BLUETOOTH",
        "อินเทอร์เน็ต" to "TOGGLE_WIFI",
        "internet" to "TOGGLE_WIFI",
        // Audio
        "เสียง" to "TOGGLE_SOUND",
        "เสียงเพลง" to "TOGGLE_SOUND",
        "โฟกัส" to "TOGGLE_DND",
        "dnd" to "TOGGLE_DND",
        "do not disturb" to "TOGGLE_DND",
        "ไม่รบสาย" to "TOGGLE_DND",
        // Airplane
        "เครื่องบิน" to "TOGGLE_AIRPLANE_MODE",
        "airplane" to "TOGGLE_AIRPLANE_MODE"
    )

    /** Thai verbs that indicate a system on/off intent. */
    private const val VERB_OPEN = "เปิด"
    private const val VERB_CLOSE = "ปิด"

    /** Thai keyword that introduces a message payload (quotative particle). */
    private const val KW_WA = "ว่า"

    /** Thai keyword that separates the recipient from the rest of a send command. */
    private const val KW_HA = "หา"

    /** Thai keyword that introduces a send command. */
    private const val KW_SEND = "ส่ง"

    /** Thai keyword that introduces an open command. */
    private const val KW_OPEN = "เปิด"

    /** Thai keyword for "app" — may follow เปิด as a particle. */
    private const val KW_APP = "แอป"

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Parse [input] into a [ParsedIntent], or return null when no pattern matches.
     *
     * The parser is intentionally fail-closed: if the input does not contain a
     * recognizable keyword anchor with a matching target, it returns null
     * rather than guessing. This prevents the engine from acting on ambiguous
     * voice input.
     */
    fun parse(input: String): ParsedIntent? {
        val normalized = input.trim()
        if (normalized.isBlank()) return null

        val lower = normalized.lowercase()

        // 1. System action — must be checked first because "เปิด" also
        //    means "open an app". A system keyword (ไวฟ์, บลูทูธ, ...)
        //    disambiguates toward SYSTEM_ACTION.
        parseSystemAction(normalized, lower)?.let { return it }

        // 2. Send message — anchored on "ส่ง".
        parseSendMessage(normalized, lower)?.let { return it }

        // 3. Open app — anchored on "เปิด" with a recognised app name.
        parseOpenApp(normalized, lower)?.let { return it }

        Log.d(TAG, "No intent matched for input: $normalized")
        return null
    }

    /**
     * Resolve a display app name (Thai or English) to its Android package name.
     *
     * @return the package name, or null when the name is not recognised.
     */
    fun resolvePackage(appName: String): String? =
        APP_NAME_MAP[appName.lowercase()]

    /**
     * Reverse lookup: package name → human-readable label from the table, or
     * a fallback derived from the last segment of the package name.
     */
    fun resolveLabel(packageName: String): String {
        APP_NAME_MAP.entries.firstOrNull { it.value == packageName }?.let { return it.key }
        val segment = packageName.substringAfterLast('.')
        if (segment.isBlank()) return packageName
        return segment.replaceFirstChar { it.uppercase() }
    }

    /**
     * Human-readable label for a system action code.
     */
    fun resolveSystemLabel(actionCode: String): String = when (actionCode) {
        "TOGGLE_WIFI" -> "ไวฟ์"
        "TOGGLE_BLUETOOTH" -> "บลูทูธ"
        "TOGGLE_SOUND" -> "เสียง"
        "TOGGLE_DND" -> "DND"
        "TOGGLE_AIRPLANE_MODE" -> "เครื่องบิน"
        else -> actionCode
    }

    // ── Private parsers ─────────────────────────────────────────────────────

    /**
     * Parse a system toggle command.
     *
     * Recognises patterns like:
     *  - "ปิดไวฟ์"   (turn Wi-Fi off)
     *  - "เปิดบลูทูธ"  (turn Bluetooth on)
     *  - "เปิด DND"   (turn Do Not Disturb on)
     *
     * The state (ON/OFF) is derived from which verb appears closer to the
     * end of the string: if "เปิด" wins, the action is ON; if "ปิด" wins, OFF.
     */
    private fun parseSystemAction(input: String, lower: String): ParsedIntent? {
        val hasOpen = lower.contains(VERB_OPEN)
        val hasClose = lower.contains(VERB_CLOSE)
        if (!hasOpen && !hasClose) return null

        // Try each known system keyword.
        for ((keyword, actionCode) in SYSTEM_KEYWORDS) {
            val kwLower = keyword.lowercase()
            if (!lower.contains(kwLower)) continue

            // Determine desired state: last verb wins (more intuitive for
            // phrases like "เปิดแล้วปิด" = effectively off).
            val lastOpen = lower.lastIndexOf(VERB_OPEN)
            val lastClose = lower.lastIndexOf(VERB_CLOSE)
            val isOn = if (lastOpen >= 0 && lastClose >= 0) {
                lastOpen > lastClose
            } else {
                lastOpen >= 0
            }
            val state = if (isOn) "ON" else "OFF"

            return ParsedIntent(
                type = IntentType.SYSTEM_ACTION,
                target = actionCode,
                payload = "$actionCode:$state",
                appName = "",
                confidence = 0.95f,
                raw = input
            )
        }
        return null
    }

    /**
     * Parse a send-message command.
     *
     * Recognises patterns like:
     *  - "ส่งไลน์หาโจ๊ว่าสวัสดีครับ"
     *  - "ส่งข้อความหาภูมิว่าไปทำงานแล้ว"
     *
     * Grammar: ส่ง [app?] หา [recipient] ว่า [message]
     *  - "ส่ง" anchors the start.
     *  - "ว่า" separates the message payload.
     *  - "หา" (if present) separates the app name from the recipient.
     *  - If no recognised app name is found, LINE is used as the default.
     */
    private fun parseSendMessage(input: String, lower: String): ParsedIntent? {
        val sendIdx = lower.indexOf(KW_SEND)
        if (sendIdx < 0) return null

        val sendLen = KW_SEND.length

        // Find "ว่า" — the message-payload separator.
        val waIdx = lower.indexOf(KW_WA, sendIdx + sendLen)
        if (waIdx < 0) {
            // No "ว่า" → cannot determine payload.
            // Try a fallback: app name + remaining text as payload.
            val afterSend = input.substring(sendIdx + sendLen).trim()
            val appName = findAppName(afterSend.lowercase())
            if (appName != null) {
                val remaining = afterSend
                    .replaceFirst(Regex("(?i)${Regex.escape(appName)}"), "")
                    .trim()
                if (remaining.isNotBlank()) {
                    Log.d(TAG, "SendMessage without ว่า: app=$appName, payload=$remaining")
                    return ParsedIntent(
                        type = IntentType.SEND_MESSAGE,
                        target = "",
                        payload = remaining,
                        appName = appName,
                        confidence = 0.55f,
                        raw = input
                    )
                }
            }
            return null
        }

        val waLen = KW_WA.length
        val payload = input.substring(waIdx + waLen).trim()
        if (payload.isBlank()) return null

        // The segment between "ส่ง" and "ว่า" holds the app name and recipient.
        val segment = input.substring(sendIdx + sendLen, waIdx)

        // Locate "หา" inside the segment to split app-name from recipient.
        val haIdx = lower.indexOf(KW_HA, sendIdx + sendLen)
        val recipient: String
        val appName: String?

        if (haIdx >= 0 && haIdx < waIdx) {
            val haLen = KW_HA.length
            val appSegment = input.substring(sendIdx + sendLen, haIdx).trim()
            recipient = input.substring(haIdx + haLen, waIdx).trim()
            appName = if (appSegment.isNotBlank()) {
                findAppName(appSegment.lowercase())
            } else {
                null
            }
        } else {
            // No "หา" → everything in the segment is the recipient text.
            recipient = segment.trim()
            appName = findAppName(recipient.lowercase())
            // If an app name was found in the recipient position, the recipient
            // is actually empty (the user said "ส่งไลน์ว่า..." with no recipient).
        }

        if (recipient.isBlank() && appName == null) return null

        val resolvedApp = appName ?: DEFAULT_APP_NAME
        val confidence = when {
            appName != null && recipient.isNotBlank() -> 0.90f
            appName != null && recipient.isBlank() -> 0.70f
            else -> 0.50f
        }

        Log.d(TAG, "SendMessage: app=$resolvedApp, target=$recipient, payload=$payload, conf=$confidence")

        return ParsedIntent(
            type = IntentType.SEND_MESSAGE,
            target = recipient,
            payload = payload,
            appName = resolvedApp,
            confidence = confidence,
            raw = input
        )
    }

    /**
     * Parse an open-app command.
     *
     * Recognises patterns like:
     *  - "เปิดแอปไลน์"
     *  - "เปิด YouTube"
     *
     * Grammar: เปิด [แอป?] [app-name]
     *  - "เปิด" anchors the start.
     *  - Optional particle "แอป".
     *  - A recognised app name must follow.
     */
    private fun parseOpenApp(input: String, lower: String): ParsedIntent? {
        val openIdx = lower.indexOf(KW_OPEN)
        if (openIdx < 0) return null

        val openLen = KW_OPEN.length
        var afterOpen = input.substring(openIdx + openLen).trim()

        // Strip the optional "แอป" particle.
        if (afterOpen.lowercase().startsWith(KW_APP)) {
            afterOpen = afterOpen.substring(KW_APP.length).trim()
        }

        // Also strip "แอปพลิเคชั่น" (full word for "app").
        if (afterOpen.lowercase().startsWith("แอปพลิเคชั่น")) {
            afterOpen = afterOpen.substring("แอปพลิเคชั่น".length).trim()
        }

        if (afterOpen.isBlank()) return null

        // Try exact match first, then substring match (handles trailing punctuation).
        val matched = findAppName(afterOpen.lowercase())
        if (matched != null) {
            Log.d(TAG, "OpenApp: $matched -> ${resolvePackage(matched)}")
            return ParsedIntent(
                type = IntentType.OPEN_APP,
                target = matched,
                payload = "",
                appName = matched,
                confidence = 0.90f,
                raw = input
            )
        }
        return null
    }

    /**
     * Search [text] for the first recognised app name, preferring the longest
     * match so that "เฟสบุ๊ค" wins over "เฟส".
     */
    private fun findAppName(text: String): String? {
        val lower = text.lowercase()
        return APP_NAME_MAP.keys
            .sortedByDescending { it.length }
            .firstOrNull { lower.contains(it.lowercase()) }
    }

    private companion object {
        /** App used when the user says "ส่งข้อความ" without naming one. */
        const val DEFAULT_APP_NAME = "ไลน์"
    }
}
