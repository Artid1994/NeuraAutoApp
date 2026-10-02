package com.example.neuraauto.brain

import kotlin.math.exp
import kotlin.random.Random

/**
 * Phase 4.1 — 100,000-neuron sparse indexed layer.
 *
 * CAPACITY vs RESIDENT — stated plainly, because the distinction is the whole
 * point of this design:
 *
 *  * [capacity] is 100,000 *addressable* neuron indices. That is the size of
 *    the representation space, not memory that is allocated up front.
 *  * Weights live in a [HashMap] keyed by neuron index, so a neuron occupies
 *    memory only once it has actually won an activation. At 9 floats per row
 *    (8 features + 1 bias, 4 bytes each = 36 bytes) a fully materialised layer
 *    would be ~3.6 MB; realistic usage touches a few hundred neurons, i.e.
 *    tens of kilobytes. Nothing close to 100k rows is ever allocated.
 *
 * HOW A NEURON IS SELECTED — sparse activation, not a dense sweep:
 * iterating 100,000 neurons per training step would be pointless work, so the
 * input is hashed into a small set of candidate indices (one per quantised
 * feature, plus a bias slot). Only those candidates are scored and only the
 * winner is updated. The hash is deterministic, which is what makes persisted
 * weights meaningful across runs: the same input must always address the same
 * neuron.
 *
 * SCOPE — same honest caveat as its predecessor: this layer is trained
 * unsupervised on hand-built features and produces a *score*. Which routines
 * get recommended is decided by [NeuralBrainEngine.detectSequences]. The layer
 * has no labels, so its output is a learned summary, not a decision.
 */
class SparseNeuronLayer(
    val inputSize: Int,
    val capacity: Int = DEFAULT_CAPACITY
) {
    /** neuronIndex -> weight row. Absent means "never activated". */
    private val weights = HashMap<Int, FloatArray>()

    /** neuronIndex -> bias. */
    private val biases = HashMap<Int, Float>()

    /** Indices touched at least once. This is the resident footprint. */
    val materializedCount: Int
        get() = weights.size

    /** Approximate resident bytes for the currently materialised neurons. */
    val residentBytes: Long
        get() = materializedCount.toLong() * (inputSize + 1) * 4L

    /**
     * The last activation produced by [trainStep]/[forward], as
     * `(neuronIndex, activation)` pairs. Exposed for logging and tests.
     */
    var lastActivation: List<Activation> = emptyList()
        private set

    // ── forward ─────────────────────────────────────────────────────────────

    /**
     * Score the candidate neurons for [input].
     *
     * Candidates are materialised on first use (random small weights, so a new
     * neuron starts near-neutral rather than strongly opinionated).
     */
    fun forward(input: FloatArray): List<Activation> {
        require(input.size == inputSize) {
            "Expected $inputSize inputs but received ${input.size}"
        }
        val candidates = candidateIndices(input)
        val scored = ArrayList<Activation>(candidates.size)
        for (index in candidates) {
            val row = weights.getOrPut(index) { randomRow() }
            var sum = biases.getOrPut(index) { 0f }
            for (j in 0 until inputSize) {
                sum += input[j] * row[j]
            }
            scored.add(Activation(index, if (sum > 0f) sum else 0f))
        }
        lastActivation = scored
        return scored
    }

    /**
     * One competitive-learning update.
     *
     * The strongest candidate moves its weights toward the input and its bias
     * toward 1; the others are untouched. Standard winner-take-all Hebbian
     * rule — no labels, no gradient, because there is no ground-truth target.
     *
     * @return the winning neuron's activation before the update.
     */
    fun trainStep(input: FloatArray, learningRate: Float = DEFAULT_LEARNING_RATE): Float {
        require(input.size == inputSize) {
            "Expected $inputSize inputs but received ${input.size}"
        }

        // Growth check must happen BEFORE forward(), because forward()
        // materialises any candidate it scores. Checking afterwards would
        // always report "not new" and the cap would never bite.
        val candidates = candidateIndices(input)
        val wouldAddNew = candidates.any { !weights.containsKey(it) }
        if (wouldAddNew && materializedCount >= MAX_MATERIALIZED_NEURONS) {
            // At capacity: score with what exists, learn nothing new.
            return forward(input).maxByOrNull { it.activation }?.activation ?: 0f
        }

        val scored = forward(input)
        val winner = scored.maxByOrNull { it.activation } ?: return 0f

        val row = weights.getOrPut(winner.index) { randomRow() }
        for (j in 0 until inputSize) {
            row[j] += learningRate * (input[j] - row[j])
        }
        val bias = biases.getOrPut(winner.index) { 0f }
        biases[winner.index] = bias + learningRate * (1f - bias)

        return winner.activation
    }

    /** Mean activation across the candidate set for [input]. */
    fun meanActivation(input: FloatArray): Float {
        val scored = forward(input)
        if (scored.isEmpty()) return 0f
        var sum = 0f
        for (a in scored) sum += a.activation
        return sum / scored.size
    }

    /** Sigmoid helper for callers that need a bounded 0..1 score. */
    fun sigmoid(z: Float): Float = 1.0f / (1.0f + exp(-z))

    // ── sparse addressing ───────────────────────────────────────────────────

    /**
     * Deterministic candidate set for [input].
     *
     * One index per feature (quantised value folded into the hash) plus a bias
     * slot, de-duplicated. Bounded by [inputSize] + 1, so cost per step is
     * constant regardless of [capacity].
     */
    fun candidateIndices(input: FloatArray): IntArray {
        val out = LinkedHashSet<Int>(inputSize + 1)
        for (j in 0 until inputSize) {
            val bucket = quantize(input[j]).toLong()
            val h = mix(j.toLong() * FEATURE_SALT + bucket)
            out.add(((h % capacity + capacity) % capacity).toInt())
        }
        // Bias slot: lets a neuron specialise on "always active in this
        // context" without depending on any single feature.
        val biasHash = mix(BIAS_SALT)
        out.add(((biasHash % capacity + capacity) % capacity).toInt())
        return out.toIntArray()
    }

    private fun quantize(value: Float): Int {
        val scaled = (value * QUANT_SCALE).toInt()
        return scaled.coerceIn(-QUANT_BUCKETS, QUANT_BUCKETS)
    }

    private fun randomRow(): FloatArray =
        FloatArray(inputSize) { (Random.nextFloat() * 0.1f) - 0.05f }

    // ── serialisation ───────────────────────────────────────────────────────

    /** Sparse snapshot: only materialised neurons, not the whole capacity. */
    data class SparseSnapshot(
        val inputSize: Int,
        val capacity: Int,
        val indices: IntArray,
        val rows: List<FloatArray>,
        val biasValues: FloatArray
    )

    fun exportSparse(): SparseSnapshot {
        val indices = weights.keys.sorted().toIntArray()
        val rows = ArrayList<FloatArray>(indices.size)
        val biasValues = FloatArray(indices.size)
        indices.forEachIndexed { i, index ->
            rows.add(weights.getValue(index).copyOf())
            biasValues[i] = biases[index] ?: 0f
        }
        return SparseSnapshot(inputSize, capacity, indices, rows, biasValues)
    }

    /**
     * Restore a snapshot.
     *
     * @return false when the snapshot shape does not match this layer.
     */
    fun importSparse(snapshot: SparseSnapshot): Boolean {
        if (snapshot.inputSize != inputSize) return false
        if (snapshot.capacity != capacity) return false
        if (snapshot.indices.size != snapshot.rows.size) return false
        if (snapshot.indices.size != snapshot.biasValues.size) return false

        weights.clear()
        biases.clear()
        snapshot.indices.forEachIndexed { i, index ->
            val row = snapshot.rows[i]
            if (row.size != inputSize) return false
            weights[index] = row.copyOf()
            biases[index] = snapshot.biasValues[i]
        }
        return true
    }

    /** A scored neuron. */
    data class Activation(val index: Int, val activation: Float)

    companion object {
        /** Addressable neuron indices. Capacity, not allocated memory. */
        const val DEFAULT_CAPACITY = 100_000

        /**
         * Hard ceiling on resident neurons. At 36 bytes/row this caps the
         * layer at ~720 KB even in pathological use.
         */
        const val MAX_MATERIALIZED_NEURONS = 20_000

        /** Small step: the feature vectors are few and highly correlated. */
        const val DEFAULT_LEARNING_RATE = 0.01f

        /** Quantisation resolution for feature hashing: 1/16 steps. */
        private const val QUANT_SCALE = 16f
        private const val QUANT_BUCKETS = 16

        /** splitmix64 salts. Plain Longs: `.toLong()` on a ULong literal is not
         *  a compile-time constant, so `const val` is not allowed here. */
        private val FEATURE_SALT = 0x9E3779B97F4A7C15uL.toLong()
        private val BIAS_SALT = 0xD1B54A32D192ED03uL.toLong()

        /** splitmix64 finaliser — deterministic across runs and processes. */
        private fun mix(x: Long): Long {
            var z = x + 0x9E3779B97F4A7C15uL.toLong()
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }
    }
}
