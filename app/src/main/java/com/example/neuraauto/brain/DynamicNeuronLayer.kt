package com.example.neuraauto.brain

import kotlin.math.exp
import kotlin.random.Random

/**
 * Small mutable fully-connected layer with ReLU activations and a competitive
 * training step.
 *
 * SCOPE, stated plainly: this is an unsupervised layer over hand-built features
 * (confidence, support, occurrences, step count). It does NOT decide what gets
 * recommended — [NeuralBrainEngine.detectSequences] does that with a frequency
 * estimate. The layer produces a learned *score* alongside those patterns, and
 * its weights persist between runs so the training accumulates.
 *
 * Weights are exposed for serialisation by
 * [com.example.neuraauto.worker.ModelWeightStore].
 */
class DynamicNeuronLayer(
    val inputSize: Int,
    initialNeurons: Int = 32
) {
    private val weights = MutableList(initialNeurons) {
        FloatArray(inputSize) { Random.nextFloat() * 0.1f - 0.05f }
    }
    private val biases = MutableList(initialNeurons) { 0.0f }

    val currentNeuronCount: Int
        get() = weights.size

    fun forward(inputs: FloatArray): FloatArray {
        require(inputs.size == inputSize) {
            "Expected $inputSize inputs but received ${inputs.size}"
        }
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

    /**
     * One competitive-learning update.
     *
     * The neuron with the strongest response moves its weights toward the input
     * (and its bias toward 1); the others are untouched. This is a standard
     * winner-take-all Hebbian rule — no labels, no backpropagation through a
     * loss, because there is no ground-truth target to compute a gradient from.
     *
     * @return the winning neuron's activation before the update.
     */
    fun trainStep(input: FloatArray, learningRate: Float = DEFAULT_LEARNING_RATE): Float {
        require(input.size == inputSize) {
            "Expected $inputSize inputs but received ${input.size}"
        }
        val outputs = forward(input)
        val winner = outputs.indices.maxByOrNull { outputs[it] } ?: return 0f

        val w = weights[winner]
        for (j in 0 until inputSize) {
            w[j] += learningRate * (input[j] - w[j])
        }
        biases[winner] += learningRate * (1f - biases[winner])
        return outputs[winner]
    }

    fun addNeuron(count: Int = 1) {
        repeat(count) {
            weights.add(FloatArray(inputSize) { Random.nextFloat() * 0.1f - 0.05f })
            biases.add(0.0f)
        }
    }

    /** Sigmoid helper for callers that need a bounded 0..1 score. */
    fun sigmoid(z: Float): Float = (1.0f / (1.0f + exp(-z)))

    // ── serialisation ────────────────────────────────────────────────────────

    /** Number of floats [exportWeights] produces for the current shape. */
    fun expectedWeightCount(): Int = currentNeuronCount * inputSize + currentNeuronCount

    /** Flattened weights (row-major) followed by the biases. */
    fun exportWeights(): FloatArray {
        val out = FloatArray(expectedWeightCount())
        var k = 0
        for (i in 0 until currentNeuronCount) {
            for (j in 0 until inputSize) {
                out[k++] = weights[i][j]
            }
        }
        for (i in 0 until currentNeuronCount) {
            out[k++] = biases[i]
        }
        return out
    }

    /**
     * Restore a previously exported weight vector.
     *
     * @return false when [data] does not match the current shape.
     */
    fun importWeights(data: FloatArray): Boolean {
        if (data.size != expectedWeightCount()) return false
        var k = 0
        for (i in 0 until currentNeuronCount) {
            for (j in 0 until inputSize) {
                weights[i][j] = data[k++]
            }
        }
        for (i in 0 until currentNeuronCount) {
            biases[i] = data[k++]
        }
        return true
    }

    /** Mean activation across neurons, a compact summary of a forward pass. */
    fun meanActivation(inputs: FloatArray): Float {
        val outputs = forward(inputs)
        if (outputs.isEmpty()) return 0f
        var sum = 0f
        for (v in outputs) sum += v
        return sum / outputs.size
    }

    companion object {
        /** Small step: the feature vectors are few and highly correlated. */
        const val DEFAULT_LEARNING_RATE = 0.01f
    }
}
