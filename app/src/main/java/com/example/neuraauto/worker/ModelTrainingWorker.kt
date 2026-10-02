package com.example.neuraauto.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.neuraauto.brain.DynamicNeuronLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelTrainingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            val hiddenLayer = DynamicNeuronLayer(inputSize = 4, initialNeurons = 16)
            hiddenLayer.addNeuron(2)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
