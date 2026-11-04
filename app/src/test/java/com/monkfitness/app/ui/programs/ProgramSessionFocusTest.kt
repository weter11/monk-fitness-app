package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.adaptive.integration.NoDeclaredProgression
import com.monkfitness.app.domain.adaptive.integration.NoExerciseFamilyClassification
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.usecase.ProgramAdaptiveIntegration
import com.monkfitness.app.domain.usecase.SessionEditingService
import com.monkfitness.app.domain.usecase.SessionRuntime
import com.monkfitness.app.domain.workout.SessionRefusal
import com.monkfitness.app.domain.workout.SessionRuntimeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session screen's behaviour, driven through the production controller over the production runtime
 * and a real SQLite engine: a confirmed set advances the attempt, the rest interval follows a set and not
 * the workout, the timed set stores what it measured, undo takes back one set, a skipped occurrence is not
 * work, and reopening restores what was stored.
 */
class ProgramSessionFocusTest {

    @Test
    fun aConfirmedSetAdvancesTheOccurrence() = runBlocking {
        withSession { harness ->
            val controller = harness.controller
            val first = requireNotNull(controller.state.value.currentExercise)
            assertEquals("the first occurrence is the one shown", "pushups", first.exerciseId)
            assertEquals(1, first.setNumber)

            assertTrue(controller.confirmActualSet("10"))

            val after = requireNotNull(controller.state.value.currentExercise)
            assertEquals(first.sessionExerciseId, after.sessionExerciseId)
            assertEquals(1, after.completedSets)
            assertEquals(2, after.setNumber)
            assertEquals(listOf(10), after.performed)
            assertEquals(
                "the stored set is what the user entered, not the prescription",
                listOf(10),
                harness.storedSets(first.sessionExerciseId).map { set -> set.completedReps }
            )
        }
    }

    @Test
    fun restFollowsASetAndNotTheLastSetOfWorkout() = runBlocking {
        withSession(storedRest = 60) { harness ->
            val controller = harness.controller
            val pushups = requireNotNull(controller.state.value.currentExercise)

            assertTrue(controller.confirmActualSet("10"))
            assertTrue("a set starts the rest", controller.state.value.isResting)
            assertEquals(60, controller.state.value.restSecondsLeft)
            assertEquals(
                "the rest names what comes next",
                pushups.sessionExerciseId,
                controller.state.value.restNext?.sessionExerciseId
            )
            assertEquals(2, controller.state.value.restNext?.setNumber)

            controller.skipRest()
            assertFalse("skipping ends it", controller.state.value.isResting)

            assertTrue(controller.confirmActualSet("10"))
            controller.skipRest()
            assertTrue(controller.confirmActualSet("10"))
            assertTrue(
                "the rest after the last set of an occurrence names the next exercise",
                controller.state.value.isResting
            )
            assertEquals("plank", controller.state.value.restNext?.exerciseId)
            controller.skipRest()

            val hang = requireNotNull(controller.state.value.currentExercise)
            assertEquals("plank", hang.exerciseId)
            assertEquals(PrescriptionDimension.TIME_BASED, hang.dimension)

            assertTrue(controller.confirmActualSet("30"))
            controller.skipRest()
            assertTrue(controller.confirmActualSet("30"))
            controller.skipRest()
            assertTrue(controller.confirmActualSet("30"))
            assertFalse(
                "the last set of the workout starts no rest",
                controller.state.value.isResting
            )
            assertTrue(controller.state.value.everythingConfirmed)
        }
    }

    @Test
    fun aClearedOrInvalidRestInputStoresNothingAndNoRestStoresZero() = runBlocking {
        withSession { harness ->
            val controller = harness.controller
            assertEquals(60, controller.state.value.restSeconds)

            assertFalse("a cleared field stores nothing", controller.setRestSecondsFromInput(""))
            assertFalse("a non-integral entry stores nothing", controller.setRestSecondsFromInput("abc"))
            assertFalse("zero is not what a text field may write", controller.setRestSecondsFromInput("0"))
            assertEquals("nothing was written", emptyList<Int>(), harness.preference.written)
            assertEquals(60, controller.state.value.restSeconds)

            assertTrue(controller.setRestSecondsFromInput("90"))
            assertEquals(listOf(90), harness.preference.written)
            assertEquals(90, controller.state.value.restSeconds)

            controller.chooseNoRest()
            assertEquals("the explicit choice is the only way zero is stored", listOf(90, 0), harness.preference.written)
            assertEquals(0, controller.state.value.restSeconds)
        }
    }

    @Test
    fun aTimedSetStoresTheTargetWhenItRunsOutAndTheElapsedWhenItStopsEarly() = runBlocking {
        withSession { harness ->
            val controller = harness.controller
            controller.focus(hangId(controller))
            val hang = requireNotNull(controller.state.value.currentExercise)

            assertTrue("the countdown ran to zero and confirmed the target", controller.confirmActualSet("30"))
            assertTrue("an early confirmation records the time it measured", controller.confirmActualSet("12"))

            assertEquals(
                "seconds, in the order they were measured",
                listOf(30, 12),
                requireNotNull(controller.state.value.currentExercise).performed
            )
            assertEquals(
                listOf(30, 12),
                harness.storedSets(hang.sessionExerciseId).map { set -> set.durationSeconds }
            )
        }
    }

    @Test
    fun undoRemovesExactlyOneSetAndRestoresThePreviousState() = runBlocking {
        withSession { harness ->
            val controller = harness.controller
            val pushups = requireNotNull(controller.state.value.currentExercise)

            assertFalse("there is nothing to take back yet", controller.undoLastSet())

            assertTrue(controller.confirmActualSet("10"))
            assertTrue(controller.confirmActualSet("9"))
            assertEquals(2, controller.state.value.confirmedSetCount)

            assertTrue(controller.undoLastSet())
            assertEquals(1, controller.state.value.confirmedSetCount)
            assertEquals(
                listOf(10),
                requireNotNull(controller.state.value.currentExercise).performed
            )
            assertEquals(
                "exactly one row is gone",
                listOf(10),
                harness.storedSets(pushups.sessionExerciseId).map { set -> set.completedReps }
            )
            assertEquals(
                "and the next set is confirmed at the position the undone one held",
                listOf(1),
                harness.storedSets(pushups.sessionExerciseId).map { set -> set.setIndex }
            )

            assertTrue(controller.confirmActualSet("7"))
            assertEquals(
                listOf(10, 7),
                harness.storedSets(pushups.sessionExerciseId).map { set -> set.completedReps }
            )
        }
    }

    @Test
    fun aSkippedExerciseIsNotCompletedWorkAndTheRuntimeRefusesItsSets() = runBlocking {
        withSession { harness ->
            val controller = harness.controller
            val hang = requireNotNull(controller.state.value.exercises.firstOrNull { it.exerciseId == "plank" })
            val pushupSets = requireNotNull(controller.state.value.exercises.first()).setCount

            controller.focus(hang.sessionExerciseId)
            assertTrue(controller.skipCurrentExercise())

            val skipped = requireNotNull(
                controller.state.value.exercises.firstOrNull { it.exerciseId == "plank" }
            )
            assertTrue("the flag is stored", skipped.skipped)
            assertFalse(skipped.isPerformable)
            assertEquals("a skip confirms nothing", 0, skipped.completedSets)
            repeat(pushupSets) {
                assertTrue(controller.confirmActualSet("10"))
                controller.skipRest()
            }
            assertTrue(
                "every occurrence is resolved — performed or skipped",
                controller.state.value.exercises.all { exercise -> exercise.skipped || exercise.isFinished }
            )
            assertFalse(
                "and a skipped occurrence still leaves the workout incomplete",
                controller.state.value.everythingConfirmed
            )

            val refused = harness.runtime.confirmSet(
                sessionId = SessionId(requireNotNull(controller.state.value.sessionId)),
                sessionExerciseId = SessionExerciseId(skipped.sessionExerciseId),
                completedReps = 10
            )
            assertTrue(
                "the runtime refuses a set on a skipped occurrence",
                refused is SessionRuntimeResult.Refused && refused.reason is SessionRefusal.OccurrenceWasSkipped
            )
        }
    }

    @Test
    fun reopeningTheSessionRestoresWhatWasStored() = runBlocking {
        val rig = ProgramsRig("focus-restore")
        try {
            val first = present(rig, RecordingRest(stored = 45))
            val controller = first.controller
            val pushups = requireNotNull(controller.state.value.currentExercise)
            assertTrue(controller.confirmActualSet("11"))
            val hang = requireNotNull(controller.state.value.exercises.firstOrNull { it.exerciseId == "plank" })
            controller.focus(hang.sessionExerciseId)
            controller.skipCurrentExercise()

            val reopened = controllerFor(rig, RecordingRest(stored = 45), first.slotId)
            assertEquals(SessionStage.PRESENTING, reopened.state.value.stage)
            assertEquals(controller.state.value.sessionId, reopened.state.value.sessionId)
            assertEquals(1, reopened.state.value.confirmedSetCount)
            assertEquals(
                listOf(11),
                requireNotNull(
                    reopened.state.value.exercises.firstOrNull {
                        it.sessionExerciseId == pushups.sessionExerciseId
                    }
                ).performed
            )
            assertTrue(
                "the skip is restored too",
                requireNotNull(reopened.state.value.exercises.firstOrNull { it.exerciseId == "plank" }).skipped
            )
            assertEquals(
                "the rest the user chose is remembered",
                45,
                reopened.state.value.restSeconds
            )
            assertEquals(
                "and the elapsed time is anchored to the stored start",
                first.startedAtMillis,
                reopened.state.value.startedAtMillis
            )
        } finally {
            rig.close()
        }
    }

    private suspend fun hangId(controller: ProgramSessionController): String =
        requireNotNull(controller.state.value.exercises.firstOrNull { it.exerciseId == "plank" })
            .sessionExerciseId

    private suspend fun withSession(
        storedRest: Int? = null,
        body: suspend (Harness) -> Unit
    ) {
        val rig = ProgramsRig("focus")
        try {
            body(present(rig, RecordingRest(stored = storedRest)))
        } finally {
            rig.close()
        }
    }

    private class Harness(
        val rig: ProgramsRig,
        val runtime: SessionRuntime,
        val controller: ProgramSessionController,
        val slotId: String,
        val preference: RecordingRest
    ) {

        val startedAtMillis: Long?
            get() = controller.state.value.startedAtMillis

        suspend fun storedSets(sessionExerciseId: String) =
            rig.transfer.sessionRepository.setsOf(SessionExerciseId(sessionExerciseId))
    }

    private suspend fun present(rig: ProgramsRig, preference: RecordingRest): Harness {
        // The rig's catalogue records every exercise as repetition-based; a timed one is what the countdown
        // and the "records the elapsed seconds" path need.
        rig.catalogueOptions.replaceAll { option ->
            if (option.exerciseId == "plank") option.copy(isTimerBased = true) else option
        }
        rig.controller.openCreateDraft(ProgramMode.MANUAL)
        rig.controller.setDraftName("Focus Program")
        rig.controller.addDraftDay(ProgramDayType.TRAINING)
        val dayId = requireNotNull(rig.state.draft?.days?.firstOrNull()?.programDayId)
        rig.controller.addDraftElement(dayId, "pushups")
        rig.controller.addDraftElement(dayId, "plank")
        assertTrue("the plan was saved", rig.controller.saveDraft())

        val createdId = requireNotNull(
            rig.state.rows.firstOrNull { row -> row.name == "Focus Program" }?.programId
        )
        val id = ProgramId(createdId)
        rig.controller.select(createdId)
        assertTrue(
            "the plan scheduled its opportunities",
            rig.transfer.scheduler.schedule(id) is ProgramSchedulingResult.Success
        )
        val next = (rig.transfer.scheduler.nextOpportunity(id) as ProgramSchedulingResult.Success).value
        val slotId = requireNotNull(next).slotId.value

        val controller = controllerFor(rig, preference, slotId)
        assertEquals(SessionStage.PRESENTING, controller.state.value.stage)
        return Harness(
            rig = rig,
            runtime = runtimeOf(rig),
            controller = controller,
            slotId = slotId,
            preference = preference
        )
    }

    private suspend fun controllerFor(
        rig: ProgramsRig,
        preference: RecordingRest,
        slotId: String
    ): ProgramSessionController {
        val controller = ProgramSessionController(
            runtime = runtimeOf(rig),
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
            restPreference = preference
        )
        controller.open(slotId)
        return controller
    }

    private fun runtimeOf(rig: ProgramsRig): SessionRuntime = SessionRuntime(
        planRepository = rig.transfer.planRepository,
        scheduleRepository = rig.transfer.scheduleRepository,
        sessionRepository = rig.transfer.sessionRepository,
        adaptiveRepository = rig.transfer.adaptiveRepository,
        clock = rig.clock,
        idGenerator = rig.ids,
        zone = rig.zone,
        inTransaction = { block -> rig.database.transaction { block() } }
    )

    private class RecordingRest(private val stored: Int?) : SessionRestPreference {

        val written = mutableListOf<Int>()

        override suspend fun read(): Int? = stored

        override suspend fun remember(seconds: Int) {
            written += seconds
        }
    }
}
