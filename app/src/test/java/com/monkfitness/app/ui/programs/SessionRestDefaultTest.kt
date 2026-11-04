package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.adaptive.integration.NoDeclaredProgression
import com.monkfitness.app.domain.adaptive.integration.NoExerciseFamilyClassification
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.usecase.ProgramAdaptiveIntegration
import com.monkfitness.app.domain.usecase.SessionRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rest a new interval starts from, over the production runtime: an unset preference is the documented
 * default and is not written, and a stored value — zero included — is the one used.
 */
class SessionRestDefaultTest {

    @Test
    fun anUnsetPreferenceStartsAtSixtySecondsAndWritesNothing() = runBlocking {
        val preference = RecordingRestPreference(stored = null)
        withPresentedSession(preference) { session ->
            assertEquals(
                "an unset rest preference means the domain's documented default",
                60,
                session.state.value.restSeconds
            )
            assertEquals(
                "and opening a session stores no choice the user never made",
                emptyList<Int>(),
                preference.written
            )
        }
    }

    @Test
    fun aStoredPreferenceIsUsedAsItStands() = runBlocking {
        withPresentedSession(RecordingRestPreference(stored = 0)) { session ->
            assertEquals(
                "a stored value, zero included, is the user's own choice and is not replaced",
                0,
                session.state.value.restSeconds
            )
        }
    }

    private suspend fun withPresentedSession(
        preference: RecordingRestPreference,
        body: suspend (ProgramSessionController) -> Unit
    ) {
        val rig = ProgramsRig("rest-default")
        try {
            val runtime = SessionRuntime(
                planRepository = rig.transfer.planRepository,
                scheduleRepository = rig.transfer.scheduleRepository,
                sessionRepository = rig.transfer.sessionRepository,
                adaptiveRepository = rig.transfer.adaptiveRepository,
                clock = rig.clock,
                idGenerator = rig.ids,
                zone = rig.zone,
                inTransaction = { block -> rig.database.transaction { block() } }
            )
            val integration = ProgramAdaptiveIntegration(
                planRepository = rig.transfer.planRepository,
                scheduleRepository = rig.transfer.scheduleRepository,
                sessionRepository = rig.transfer.sessionRepository,
                adaptiveRepository = rig.transfer.adaptiveRepository,
                relations = NoDeclaredProgression,
                classification = NoExerciseFamilyClassification,
                clock = rig.clock,
                idGenerator = rig.ids,
                zone = rig.zone
            )
            val session = ProgramSessionController(
                runtime = runtime,
                adaptive = integration,
                lifecycle = rig.transfer.lifecycleService,
                catalogue = { rig.catalogueOptions },
                familyCatalogue = { emptyMap() },
                restPreference = preference
            )

            val createdId = rig.createProgramThroughTheUi("Rest Default Program")
            val id = ProgramId(requireNotNull(createdId))
            rig.controller.select(createdId)
            rig.transfer.scheduler.schedule(id)
            val next = (rig.transfer.scheduler.nextOpportunity(id) as ProgramSchedulingResult.Success).value

            session.open(requireNotNull(next).slotId.value)
            assertEquals(SessionStage.PRESENTING, session.state.value.stage)
            body(session)
        } finally {
            rig.close()
        }
    }

    private class RecordingRestPreference(private val stored: Int?) : SessionRestPreference {

        val written = mutableListOf<Int>()

        override suspend fun read(): Int? = stored

        override suspend fun remember(seconds: Int) {
            written += seconds
        }
    }
}
