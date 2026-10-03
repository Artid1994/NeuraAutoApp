package com.example.neuraauto.brain

/**
 * Phase 6.0 — Multi-Dimensional Confidence Vector.
 *
 * Replaces the single scalar confidence with a six-dimensional vector that
 * captures the full context of a detected pattern. The composite score is a
 * weighted sum that drives execution decisions.
 *
 * Dimensions:
 *  - [frequency]    — temporal regularity (supportDays / observedDays)
 *  - [contextual]   — ambient context match (charging, wifi, weekend)
 *  - [content]      — semantic content quality (text length, field type)
 *  - [user]         — explicit user verification
 *  - [neural]       — SparseNeuronLayer activation score
 *  - [safety]       — inverse of risk level (1.0 = no risk)
 */
data class ConfidenceVector(
    val frequency: Float,
    val contextual: Float,
    val content: Float,
    val user: Float,
    val neural: Float,
    val safety: Float
) {
    /**
     * Weighted composite score in 0..1.
     *
     * Weights sum to 1.0. Safety and user verification are weighted highest
     * because they represent explicit trust signals. Neural is weighted lowest
     * because it is an unsupervised score with no labels.
     */
    val composite: Float
        get() = frequency * W_FREQUENCY +
                contextual * W_CONTEXTUAL +
                content * W_CONTENT +
                user * W_USER +
                neural * W_NEURAL +
                safety * W_SAFETY

    val compositePercent: Int get() = (composite * 100).toInt()

    /**
     * Whether this vector meets the execution threshold.
     *
     * Requires BOTH a high composite score AND a minimum safety floor.
     * A pattern with high frequency but low safety must not be automated.
     */
    fun shouldExecute(threshold: Float = EXECUTION_THRESHOLD): Boolean {
        return composite >= threshold && safety >= SAFETY_FLOOR
    }

    companion object {
        const val W_FREQUENCY = 0.20f
        const val W_CONTEXTUAL = 0.15f
        const val W_CONTENT = 0.15f
        const val W_USER = 0.25f
        const val W_NEURAL = 0.10f
        const val W_SAFETY = 0.15f

        const val EXECUTION_THRESHOLD = 0.70f
        const val SAFETY_FLOOR = 0.50f

        /** A neutral vector with all dimensions at 0.5. */
        val NEUTRAL = ConfidenceVector(
            frequency = 0.5f,
            contextual = 0.5f,
            content = 0.5f,
            user = 0.5f,
            neural = 0.5f,
            safety = 0.5f
        )
    }
}
