package com.monkfitness.app

import android.app.Application
import androidx.lifecycle.asLiveData
import com.monkfitness.app.data.local.SettingsManager
import com.monkfitness.app.util.NotificationScheduler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MonkFitnessApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val settingsManager = SettingsManager(this)
        MainScope().launch {
            val time = settingsManager.notificationTimeFlow.first()
            NotificationScheduler.scheduleDailyReminder(this@MonkFitnessApplication, time.first, time.second)
        }
    }
}
