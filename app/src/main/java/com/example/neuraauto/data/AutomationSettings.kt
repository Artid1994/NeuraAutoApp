package com.example.neuraauto.data

import android.content.Context

/**
 * User-facing automation preferences and the record of the last autonomous
 * training run.
 *
 * Backed by SharedPreferences rather than Room: these are two scalars plus a
 * small run summary, and putting them in the database would force a schema
 * migration for no query benefit.
 */
object AutomationSettings {

    private const val PREFS = "neuraauto_settings"

    private const val KEY_AUTO_ENABLE = "auto_enable_enabled"
    private const val KEY_LAST_RUN_AT = "last_run_at"
    private const val KEY_LAST_PATTERNS = "last_patterns"
    private const val KEY_LAST_AUTO_ENABLED = "last_auto_enabled"
    private const val KEY_LAST_SCORE = "last_score"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether background training may enable workflows on its own.
     *
     * Defaults to true (autonomous mode is the point of this phase), but it is
     * a kill switch the user controls: an auto-enabled workflow sends a real
     * message without anyone watching, so it must be switchable off.
     */
    fun isAutoEnableEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ENABLE, true)

    fun setAutoEnableEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ENABLE, enabled).apply()
    }

    /** Record what the last training pass did, for display in the dashboard. */
    fun recordTrainingRun(
        context: Context,
        patternsFound: Int,
        autoEnabled: Int,
        meanScore: Float,
        timestampMillis: Long
    ) {
        prefs(context).edit()
            .putLong(KEY_LAST_RUN_AT, timestampMillis)
            .putInt(KEY_LAST_PATTERNS, patternsFound)
            .putInt(KEY_LAST_AUTO_ENABLED, autoEnabled)
            .putFloat(KEY_LAST_SCORE, meanScore)
            .apply()
    }

    data class TrainingRunSummary(
        val timestampMillis: Long,
        val patternsFound: Int,
        val autoEnabled: Int,
        val meanScore: Float
    ) {
        val hasRun: Boolean get() = timestampMillis > 0L
    }

    fun lastTrainingRun(context: Context): TrainingRunSummary {
        val p = prefs(context)
        return TrainingRunSummary(
            timestampMillis = p.getLong(KEY_LAST_RUN_AT, 0L),
            patternsFound = p.getInt(KEY_LAST_PATTERNS, 0),
            autoEnabled = p.getInt(KEY_LAST_AUTO_ENABLED, 0),
            meanScore = p.getFloat(KEY_LAST_SCORE, 0f)
        )
    }
}
