package com.example.neuraauto.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object TrainingScheduler {
    private const val UNIQUE_WORK_NAME = "NeuraAuto_OnDevice_Training_Work"

    fun scheduleNightlyTraining(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        val trainingWorkRequest = PeriodicWorkRequestBuilder<ModelTrainingWorker>(
            repeatInterval = 24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            trainingWorkRequest
        )
    }
}
