package com.example.neuraauto.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object TrainingScheduler {
    private const val UNIQUE_WORK_NAME = "NeuraAuto_OnDevice_Training_Work"

    /**
     * Periodic background training.
     *
     * Constrained to charging + unmetered network and battery-not-low: the
     * feature vectors are tiny, but a pass touches the database and writes a
     * model file, so it should not compete with the user's foreground session.
     * KEEP preserves an already-enqueued request rather than resetting the
     * interval on every app start.
     */
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

    /**
     * Kick off a one-off training pass immediately.
     *
     * Uses a separate unique name so it does not disturb the periodic schedule,
     * and REPLACE so repeated taps collapse into one queued run.
     */
    fun runTrainingNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ModelTrainingWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private const val IMMEDIATE_WORK_NAME = "NeuraAuto_OnDevice_Training_Now"
}
