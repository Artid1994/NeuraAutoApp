package com.example.neuraauto.worker

import android.content.Context
import android.util.Log
import com.example.neuraauto.brain.SparseNeuronLayer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Persists [SparseNeuronLayer] weights between background runs.
 *
 * Without this the layer would be re-initialised on every pass and "training"
 * would mean nothing — each run would start from scratch. Saving makes the
 * learning cumulative.
 *
 * FILE FORMAT (v3, sparse):
 *   int    magic
 *   int    version
 *   int    inputSize
 *   int    capacity
 *   int    neuronCount          — materialised rows only, NOT capacity
 *   int[neuronCount] indices
 *   float[neuronCount * inputSize] rows (row-major)
 *   float[neuronCount] biases
 *
 * Storing only materialised neurons is what keeps the file small: a layer with
 * 100,000 addressable indices but 300 touched neurons writes 300 rows, not
 * 100,000. A header/shape mismatch (e.g. the feature count changed in an
 * update) is treated as "no saved model" rather than loading garbage.
 */
class ModelWeightStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun save(layer: SparseNeuronLayer) {
        try {
            val snapshot = layer.exportSparse()
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(snapshot.inputSize)
                out.writeInt(snapshot.capacity)
                out.writeInt(snapshot.indices.size)

                snapshot.indices.forEach { out.writeInt(it) }
                snapshot.rows.forEach { row -> row.forEach { out.writeFloat(it) } }
                snapshot.biasValues.forEach { out.writeFloat(it) }
            }
            Log.i(
                TAG,
                "Saved ${snapshot.indices.size} neuron(s), " +
                    "${layer.residentBytes} B resident (capacity ${snapshot.capacity})"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist model weights", e)
        }
    }

    /**
     * Load saved weights into [layer] if they match its shape.
     *
     * @return true when a compatible model was loaded.
     */
    fun load(layer: SparseNeuronLayer): Boolean {
        if (!file.exists()) return false
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return false
                if (input.readInt() != VERSION) return false
                val inputSize = input.readInt()
                val capacity = input.readInt()
                val neuronCount = input.readInt()

                if (inputSize != layer.inputSize || capacity != layer.capacity) {
                    Log.i(
                        TAG,
                        "Saved model shape (capacity $capacity, $inputSize features) does not " +
                            "match current layer (capacity ${layer.capacity}, " +
                            "${layer.inputSize} features); starting fresh"
                    )
                    return false
                }
                if (neuronCount < 0 || neuronCount > SparseNeuronLayer.MAX_MATERIALIZED_NEURONS) {
                    Log.w(TAG, "Refusing implausible neuron count $neuronCount")
                    return false
                }

                val indices = IntArray(neuronCount) { input.readInt() }
                val rows = ArrayList<FloatArray>(neuronCount)
                repeat(neuronCount) {
                    rows.add(FloatArray(inputSize) { input.readFloat() })
                }
                val biasValues = FloatArray(neuronCount) { input.readFloat() }

                val snapshot = SparseNeuronLayer.SparseSnapshot(
                    inputSize = inputSize,
                    capacity = capacity,
                    indices = indices,
                    rows = rows,
                    biasValues = biasValues
                )
                layer.importSparse(snapshot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load model weights", e)
            false
        }
    }

    private companion object {
        const val TAG = "ModelWeightStore"
        const val FILE_NAME = "neuraauto_model.bin"
        const val MAGIC = 0x4E455552 // "NEUR"
        const val VERSION = 3 // v3: sparse 100k-capacity layer (Phase 4.1)
    }
}
