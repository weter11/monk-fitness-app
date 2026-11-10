package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.adaptive.integration.NoDeclaredProgression
import com.monkfitness.app.domain.adaptive.integration.NoExerciseFamilyClassification
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.usecase.ProgramAdaptiveIntegration
import com.monkfitness.app.domain.usecase.SessionEditingService
import com.monkfitness.app.domain.usecase.SessionRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recording a load from the session screen, driven through the production controller over the production
 * runtime and a real SQLite engine.
 *
 * Four claims, each one a way this could silently be wrong: only a movement the catalogue records as
 * loaded is asked for a weight; the field starts at the weight that movement was last performed with; a
 * rejected entry stores nothing; and a bodyweight set is stored with an absent load rather than a zero.
 */
class SetLoadRecordingTest {

    private class RecordingLoadSource(private val byExercise: Map<String, Double>) : LastSetLoadSource {

        val read = mutableListOf<String>()

        override suspend fun loadKgOf(exerciseId: String): Double? {
            read += exerciseId
            return byExercise[exerciseId]
        }
    }

    private suspend fun withSession(
        lastLoads: Map<String, Double> = emptyMap(),
        body: suspend (ProgramSessionController, ProgramsRig, RecordingLoadSource) -> Unit
    ) {
        val rig = ProgramsRig("load")
        try {
            rig.catalogueOptions.replaceAll { option ->
                when (option.exerciseId) {
                    "pushups" -> option.copy(acceptsLoad = true)
                    "plank" -> option.copy(isTimerBased = true, acceptsLoad = false)
                    else -> option
                }
            }
            rig.controller.openCreateDraft(ProgramMode.MANUAL)
            rig.controller.setDraftName("Load Program")
            rig.controller.addDraftDay(ProgramDayType.TRAINING)
            val dayId = requireNotNull(rig.state.draft?.days?.firstOrNull()?.programDayId)
            rig.controller.addDraftElement(dayId, "pushups")
            rig.controller.addDraftElement(dayId, "plank")
            assertTrue("the plan was saved", rig.controller.saveDraft())

            val createdId = requireNotNull(
                rig.state.rows.firstOrNull { row -> row.name == "Load Program" }?.programId
            )
            val programId = ProgramId(createdId)
            rig.controller.select(createdId)
            assertTrue(
                "the plan scheduled its opportunities",
                rig.transfer.scheduler.schedule(programId) is ProgramSchedulingResult.Success
            )
            val slotId = requireNotNull(
                (rig.transfer.scheduler.nextOpportunity(programId) as ProgramSchedulingResult.Success)
                    .value
            ).slotId.value

            val loadSource = RecordingLoadSource(lastLoads)
            val controller = ProgramSessionController(
                runtime = SessionRuntime(
                    planRepository = rig.transfer.planRepository,
                    scheduleRepository = rig.transfer.scheduleRepository,
                    sessionRepository = rig.transfer.sessionRepository,
                    adaptiveRepository = rig.transfer.adaptiveRepository,
                    clock = rig.clock,
                    idGenerator = rig.ids,
                    zone = rig.zone,
                    inTransaction = { block -> rig.database.transaction { block() } }
                ),
                editing = SessionEditingService(rig.transfer.sessionRepository),
                adaptive = ProgramAdaptiveIntegration(
                    planRepository = rig.transfer.planRepository,
                    scheduleRepository = rig.transfer.scheduleRepository,
                    sessionRepository = rig.transfer.sessionRepository,
                    adaptiveRepository = rig.transfer.adaptiveRepository,
                    relations = NoDeclaredProgression,
                    classification = NoExerciseFamilyClassification,
                    clock = rig.clock,
                    idGenerator = rig.ids,
                    zone = rig.zone
                ),
                lifecycle = rig.transfer.lifecycleService,
                catalogue = { rig.catalogueOptions },
                familyCatalogue = { emptyMap() },
                restPreference = object : SessionRestPreference {
                    override suspend fun read(): Int? = null
                    override suspend fun remember(seconds: Int) = Unit
                },
                lastLoad = loadSource
            )
            controller.open(slotId)
            body(controller, rig, loadSource)
        } finally {
            rig.close()
        }
    }

    private suspend fun storedSets(rig: ProgramsRig, sessionExerciseId: String) =
        rig.transfer.sessionRepository.setsOf(SessionExerciseId(sessionExerciseId))

    @Test
    fun onlyAMovementTheCatalogueCallsLoadedIsAskedForAWeight() = runBlocking {
        withSession { controller, _, _ ->
            val exercises = controller.state.value.exercises
            val pushups = requireNotNull(exercises.firstOrNull { it.exerciseId == "pushups" })
            val plank = requireNotNull(exercises.firstOrNull { it.exerciseId == "plank" })

            assertTrue("the catalogue records pushups as performed with a load", pushups.acceptsLoad)
            assertFalse("a hold records no weight", plank.acceptsLoad)
        }
    }

    @Test
    fun theFieldStartsAtTheWeightTheMovementWasLastPerformedWith() = runBlocking {
        withSession(lastLoads = mapOf("pushups" to 62.5)) { controller, _, loadSource ->
            val pushups = requireNotNull(
                controller.state.value.exercises.firstOrNull { it.exerciseId == "pushups" }
            )

            assertEquals("the prefill is the stored weight", 62.5, requireNotNull(pushups.nextLoadKg), 0.0)
            assertTrue(
                "and the movement was asked about once, not once per re-presentation",
                loadSource.read.count { id -> id == "pushups" } <= 1
            )
        }
    }

    @Test
    fun aMovementNeverWeighedStartsWithAnEmptyField() = runBlocking {
        withSession { controller, _, _ ->
            val pushups = requireNotNull(
                controller.state.value.exercises.firstOrNull { it.exerciseId == "pushups" }
            )
            assertNull("no stored weight is an absence, never zero kilograms", pushups.nextLoadKg)
        }
    }

    @Test
    fun aTypedLoadIsStoredOnTheConfirmedSet() = runBlocking {
        withSession { controller, rig, _ ->
            val occurrence = requireNotNull(controller.state.value.currentExercise)
            assertEquals("pushups is the first occurrence", "pushups", occurrence.exerciseId)

            assertTrue(controller.confirmActualSet("10", "62.5"))

            val stored = storedSets(rig, occurrence.sessionExerciseId).single()
            assertEquals(10, stored.completedReps)
            assertEquals("the typed load is what the row carries", 62.5, requireNotNull(stored.loadKg), 0.0)
        }
    }

    @Test
    fun aLoadOutsideTheBoundIsRefusedAndNothingIsStored() = runBlocking {
        withSession { controller, rig, _ ->
            val occurrence = requireNotNull(controller.state.value.currentExercise)

            for (rejected in listOf("0", "-5", "501", "62.555", "abc")) {
                assertFalse(
                    "load `$rejected` is refused before any write",
                    controller.confirmActualSet("10", rejected)
                )
            }
            assertTrue(
                "and no set was stored by any of them",
                storedSets(rig, occurrence.sessionExerciseId).isEmpty()
            )
        }
    }

    @Test
    fun aRepetitionCountOutsideTheBoundIsRefusedAndNothingIsStored() = runBlocking {
        withSession { controller, rig, _ ->
            val occurrence = requireNotNull(controller.state.value.currentExercise)

            for (rejected in listOf("0", "-3", "501", "999999")) {
                assertFalse(
                    "repetitions `$rejected` are refused before any write",
                    controller.confirmActualSet(rejected, "60")
                )
            }
            assertTrue(
                "and no set was stored by any of them",
                storedSets(rig, occurrence.sessionExerciseId).isEmpty()
            )
        }
    }

    @Test
    fun aBlankLoadConfirmsTheSetWithNoWeightRecorded() = runBlocking {
        withSession { controller, rig, _ ->
            val occurrence = requireNotNull(controller.state.value.currentExercise)

            assertTrue(controller.confirmActualSet("10", ""))

            val stored = storedSets(rig, occurrence.sessionExerciseId).single()
            assertEquals(10, stored.completedReps)
            assertNull("no weight typed is an absence in the row", stored.loadKg)
        }
    }

    @Test
    fun aBodyweightMovementRecordsNoWeight() = runBlocking {
        withSession { controller, rig, _ ->
            val pushups = requireNotNull(controller.state.value.currentExercise)
            repeat(pushups.setCount) { index ->
                assertTrue(
                    "the loaded movement's set ${index + 1} is confirmed with its weight",
                    controller.confirmActualSet("10", "60")
                )
            }

            val plank = requireNotNull(controller.state.value.currentExercise)
            assertEquals("the hold is presented next", "plank", plank.exerciseId)
            assertFalse("which asks for no weight", plank.acceptsLoad)

            assertTrue(controller.confirmActualSet("30", ""))

            val stored = storedSets(rig, plank.sessionExerciseId).single()
            assertEquals(30, stored.durationSeconds)
            assertNull("a bodyweight hold stores no load", stored.loadKg)
            assertTrue(
                "and every set of the loaded movement kept its weight",
                storedSets(rig, pushups.sessionExerciseId).all { set -> set.loadKg == 60.0 }
            )
        }
    }

    @Test
    fun theConfirmedWeightBecomesThePrefillOfTheNextSet() = runBlocking {
        withSession { controller, _, _ ->
            assertTrue(controller.confirmActualSet("10", "70"))
            val next = requireNotNull(controller.state.value.currentExercise)
            assertEquals("the next set of the same movement", "pushups", next.exerciseId)
            assertEquals(
                "starts from the weight just recorded",
                70.0,
                requireNotNull(next.nextLoadKg),
                0.0
            )
        }
    }
}
