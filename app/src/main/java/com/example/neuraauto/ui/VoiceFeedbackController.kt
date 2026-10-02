package com.example.neuraauto.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Phase 4.3 — Thai voice feedback and voice commands for the countdown.
 *
 * Two independent halves, both deliberately fail-soft: if TTS or speech
 * recognition is unavailable the countdown still works, it is just silent.
 *
 *  * [speak] announces what is about to happen and counts down, so the user
 *    does not have to be looking at the screen.
 *  * [listen] accepts a spoken command — "ข้าม" (skip) or "ส่งเลย" (send now) —
 *    so the user can resolve the countdown hands-free.
 *
 * Recognition is started only when RECORD_AUDIO is already granted; it is
 * never requested from here, because the countdown appears over whatever the
 * user was doing and a permission dialog on top of that is hostile.
 */
class VoiceFeedbackController(
    private val context: Context,
    private val onCommand: (Command) -> Unit
) {
    enum class Command { SKIP, SEND_NOW }

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var listening = false

    /** Thai command phrases mapped to their action. */
    private val commandPhrases = mapOf(
        Command.SKIP to listOf("ข้าม", "ข้ามวันนี้", "skip", "ยกเลิก"),
        Command.SEND_NOW to listOf("ส่งเลย", "ส่ง", "send", "send now", "ยืนยัน")
    )

    // ── TTS ─────────────────────────────────────────────────────────────────

    /**
     * Initialise TTS. Safe to call repeatedly; a second call is ignored.
     *
     * Thai locale is requested explicitly. When the device has no Thai voice
     * installed `setLanguage` reports missing data and speech is skipped
     * rather than read out in the wrong language.
     */
    fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed with status $status")
                ttsReady = false
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val thai = Locale("th", "TH")
            val result = try {
                engine.setLanguage(thai)
            } catch (e: Exception) {
                Log.w(TAG, "setLanguage(th) failed", e)
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ttsReady) {
                Log.w(TAG, "Thai TTS voice unavailable; running silent")
            }
        }
    }

    /** Speak [text] in Thai, if the engine is ready. Never throws. */
    fun speak(text: String) {
        if (!ttsReady) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (e: Exception) {
            Log.w(TAG, "speak failed", e)
        }
    }

    // ── speech recognition ──────────────────────────────────────────────────

    /**
     * Start listening for a voice command.
     *
     * Returns false (and does nothing) when RECORD_AUDIO is not granted or the
     * device has no recogniser, so the caller can fall back to button-only.
     */
    fun startListening(): Boolean {
        if (listening) return true
        if (!hasAudioPermission()) {
            Log.i(TAG, "RECORD_AUDIO not granted; voice commands disabled")
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.i(TAG, "No speech recogniser available")
            return false
        }

        return try {
            val engine = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = engine
            engine.setRecognitionListener(listener)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            engine.startListening(intent)
            listening = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            false
        }
    }

    /** Stop listening and release the recogniser. Safe to call repeatedly. */
    fun stopListening() {
        listening = false
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening failed", e)
        }
        recognizer = null
    }

    /** Release both engines. Call from the host's onDestroy. */
    fun shutdown() {
        stopListening()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "TTS shutdown failed", e)
        }
        tts = null
        ttsReady = false
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            resolveCommand(matches)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            resolveCommand(matches)
        }

        override fun onError(error: Int) {
            Log.d(TAG, "Recognition error $error")
            listening = false
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Match recognised phrases against the command table. First hit wins. */
    private fun resolveCommand(matches: List<String>) {
        for (raw in matches) {
            val said = raw.lowercase().trim()
            for ((command, phrases) in commandPhrases) {
                if (phrases.any { said.contains(it) }) {
                    Log.i(TAG, "Voice command '$raw' -> $command")
                    listening = false
                    onCommand(command)
                    return
                }
            }
        }
    }

    private fun hasAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "VoiceFeedback"
        private const val UTTERANCE_ID = "neuraauto_countdown"

        /** Permission the countdown needs for voice commands. */
        const val AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO

        /**
         * Ask for RECORD_AUDIO from a normal, user-initiated screen.
         *
         * Called by the dashboard, never by the countdown itself: the
         * countdown appears on top of whatever the user was doing, and a
         * permission dialog there would be an ambush.
         */
        fun requestAudioPermission(activity: Activity, requestCode: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            if (activity.checkSelfPermission(AUDIO_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            activity.requestPermissions(arrayOf(AUDIO_PERMISSION), requestCode)
        }
    }
}
