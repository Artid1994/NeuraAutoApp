package com.example.neuraauto.brain

import kotlin.random.Random

class DynamicNeuronLayer(
    val inputSize: Int,
    initialNeurons: Int = 16
) {
    private val weights = MutableList(initialNeurons) { FloatArray(inputSize) { Random.nextFloat() * 0.1f - 0.05f } }
    private val biases = MutableList(initialNeurons) { 0.0f }

    val currentNeuronCount: Int
        get() = weights.size

    fun forward(inputs: FloatArray): FloatArray {
        val outputs = FloatArray(currentNeuronCount)
        for (i in 0 until currentNeuronCount) {
            var sum = biases[i]
            for (j in 0 until inputSize) {
                sum += inputs[j] * weights[i][j]
            }
            outputs[i] = if (sum > 0f) sum else 0f
        }
        return outputs
    }

    fun addNeuron(count: Int = 1) {
        repeat(count) {
            weights.add(FloatArray(inputSize) { Random.nextFloat() * 0.1f - 0.05f })
            biases.add(0.0f)
        }
    }
}
