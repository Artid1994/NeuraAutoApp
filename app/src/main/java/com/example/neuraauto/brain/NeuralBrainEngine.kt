package com.example.neuraauto.brain

import kotlin.math.exp

class NeuralBrainEngine {
    private var weights = floatArrayOf(0.8f, 0.5f)
    private var bias = -6.5f

    fun predictWorkflowConfidence(hour: Int, isWeekday: Boolean): Float {
        val weekdayVal = if (isWeekday) 1.0f else 0.0f
        val z = (hour * weights[0]) + (weekdayVal * weights[1]) + bias
        return (1.0f / (1.0f + exp(-z)))
    }
}
