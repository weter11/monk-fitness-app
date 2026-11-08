package com.monkfitness.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The two switches are preferences, so their promise is a stored value, not a remembered one: both read
 * off until the user touches them, and the value the store wrote is the value a later start reads.
 *
 * This repository has no Robolectric, so the test drives the real `DataStore` on the JVM rather than an
 * `AndroidViewModel`; the second read comes from the bytes the first manager left on disk.
 */
class SettingsManagerDietaryFilterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { file }

    @Test
    fun bothTogglesAreOffOnAFreshInstall() = runBlocking {
        val settings = SettingsManager(store(File(folder.root, "settings.preferences_pb")))

        assertFalse(settings.nutritionNoPorkFlow.first())
        assertFalse(settings.nutritionLactoseFreeFlow.first())
    }

    @Test
    fun bothTogglesSurviveTheNextStartOfTheApp() = runBlocking {
        val file = File(folder.root, "settings.preferences_pb")
        val settings = SettingsManager(store(file))

        settings.setNutritionNoPork(true)
        settings.setNutritionLactoseFree(true)
        assertTrue(settings.nutritionNoPorkFlow.first())
        assertTrue(settings.nutritionLactoseFreeFlow.first())

        // What a restart reads: the file the store left behind, opened by a store that never saw a write.
        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val persisted = File(folder.root, "restart.preferences_pb")
            file.copyTo(persisted, overwrite = true)
            val restarted = SettingsManager(
                PreferenceDataStoreFactory.create(scope = restartScope) { persisted }
            )

            assertTrue("no pork did not survive the restart", restarted.nutritionNoPorkFlow.first())
            assertTrue("lactose-free did not survive the restart", restarted.nutritionLactoseFreeFlow.first())
        } finally {
            restartScope.cancel()
            restartScope.coroutineContext[Job]?.join()
        }
    }
}
