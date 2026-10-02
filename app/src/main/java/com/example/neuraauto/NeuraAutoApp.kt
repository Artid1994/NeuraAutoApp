package com.example.neuraauto

import android.app.Application
import com.example.neuraauto.worker.TrainingScheduler

class NeuraAutoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TrainingScheduler.scheduleNightlyTraining(applicationContext)
    }
}