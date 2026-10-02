package com.example.neuraauto.worker

import android.content.Context
import com.example.neuraauto.brain.DynamicNeuronLayer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import android.util.Log

/**
 * Persists [DynamicNeuronLayer] weights between background runs.
 *
 * Without this the layer would be re-initialised with random weights on every
 * pass and "training" would mean nothing — each run would start from scratch.
 * Saving the weights makes the learning cumulative.
 *
 * The file carries a magic header and the expected shape; a mismatch (e.g. the
 * feature count changed in an update) is treated as "no saved model" rather
 * than loading garbage into the layer.
 */
class ModelWeightStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun save(layer: DynamicNeuronLayer) {
        try {
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(layer.inputSize)
                out.writeInt(layer.currentNeuronCount)
                val data = layer.exportWeights()
                out.writeInt(data.size)
                data.forEach { out.writeFloat(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist model weights", e)
        }
    }

    /**
     * Load saved weights into [layer] if they match its shape.
     *
     * @return true when a compatible model was loaded.
     */
    fun load(layer: DynamicNeuronLayer): Boolean {
        if (!file.exists()) return false
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return false
                if (input.readInt() != VERSION) return false
                val inputSize = input.readInt()
                val neurons = input.readInt()
                val count = input.readInt()

                if (inputSize != layer.inputSize || neurons != layer.currentNeuronCount) {
                    Log.i(
                        TAG,
                        "Saved model shape ($neurons x $inputSize) does not match " +
                            "current layer (${layer.currentNeuronCount} x ${layer.inputSize}); " +
                            "starting fresh"
                    )
                    return false
                }
                if (count != layer.expectedWeightCount()) return false

                val data = FloatArray(count) { input.readFloat() }
                layer.importWeights(data)
                true
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
        const val VERSION = 2 // v2: 32 neurons, 8 features (Phase 3.4)
    }
}
