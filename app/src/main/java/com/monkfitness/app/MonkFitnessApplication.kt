package com.monkfitness.app

import android.app.Application
import com.monkfitness.app.data.local.SettingsManager
import com.monkfitness.app.di.AppContainer
import com.monkfitness.app.language.AppLanguageManager
import com.monkfitness.app.language.SettingsLegacyLanguageStore
import com.monkfitness.app.util.NotificationScheduler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The one owner of the app's composition root: the only place a container is created, so nothing
 * else in production acquires a database, a DAO or a Program System repository.
 */
class MonkFitnessApplication : Application() {

    /**
     * Stored, not `get()`: one container and one database per process, lazily created so the database is
     * acquired on first use rather than in `onCreate`. View models are handed collaborators by injection —
     * they never reach in here.
     */
    val container: AppContainer by lazy { AppContainer.create(this) }

    /**
     * The app's only language state is the platform application locale, reached only through
     * [com.monkfitness.app.language.AppCompatAppLocaleStore]. The legacy-selection migration is not run
     * here but from `MainActivity`: the app-locale API cannot reach the platform before an Activity exists.
     */
    val appLanguageManager: AppLanguageManager by lazy {
        AppLanguageManager.create(SettingsLegacyLanguageStore(SettingsManager(this)))
    }

    override fun onCreate() {
        super.onCreate()

        val settingsManager = SettingsManager(this)
        // Seeded at application start, before any adaptive pass can run: one is only reachable once a
        // Session completes, which is a user action well after start-up. The provider itself never
        // seeds, so an early consumer sees an honestly empty catalogue rather than a race.
        MainScope().launch {
            container.builtInProgressionCatalogueBootstrap.bootstrap()
        }
        MainScope().launch {
            container.standardProgramBootstrap.bootstrap()
        }
        MainScope().launch {
            val time = settingsManager.notificationTimeFlow.first()
            NotificationScheduler.scheduleDailyReminder(this@MonkFitnessApplication, time.first, time.second)
        }
    }
}
