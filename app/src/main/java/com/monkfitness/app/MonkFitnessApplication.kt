package com.monkfitness.app

import android.app.Application
import com.monkfitness.app.data.local.SettingsManager
import com.monkfitness.app.di.AppContainer
import com.monkfitness.app.util.NotificationScheduler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The application object, and the one owner of the app's composition root (§26).
 *
 * `Application` is the top of the blueprint's wiring diagram, so it is where the container lives and
 * the only place it is created. Nothing else in production acquires a database, a DAO or a Program
 * System repository.
 */
class MonkFitnessApplication : Application() {

    /**
     * The app's composition root: the app's one database, the DAOs taken from it once, and every
     * Program System repository constructed over them ([AppContainer]).
     *
     * It is a stored `val` created on first use, for two reasons. **Stored**, because the process must
     * hold exactly one container: one place where the clock, the id generator and the transaction
     * runner are decided, and one database behind every repository (a `get()` accessor would build a
     * fresh graph — and re-ask for the database — on every read). **On first use**, because acquiring
     * the database is the one thing this stage must not move: the build that read it from the view
     * model acquired it when a view model was first constructed, and the container preserves that
     * moment exactly.
     *
     * A ViewModel does not fetch its collaborators from here — §26's composition root hands them to
     * it, and a view model reaching into the container would be the service locator the same section
     * forbids. The one thing a view model may take from here is the shared database itself, for the
     * shipped Stage-1 path that §30 step 15 retires.
     */
    val container: AppContainer by lazy { AppContainer.create(this) }

    override fun onCreate() {
        super.onCreate()

        val settingsManager = SettingsManager(this)
        MainScope().launch {
            val time = settingsManager.notificationTimeFlow.first()
            NotificationScheduler.scheduleDailyReminder(this@MonkFitnessApplication, time.first, time.second)
        }
    }
}
