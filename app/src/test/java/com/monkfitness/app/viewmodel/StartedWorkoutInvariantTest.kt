package com.monkfitness.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Task 15: the JVM cannot construct AndroidViewModel; guard its real reactive inputs. */
class StartedWorkoutInvariantTest {
    @Test
    fun removingEquipmentCannotRebuildAnAlreadyStartedWorkout() = kotlinx.coroutines.runBlocking {
        val generator = com.monkfitness.app.domain.usecase.WorkoutGenerator()
        val integration = com.monkfitness.app.domain.usecase.AdaptiveWorkoutIntegration(generator)
        val configuration = com.monkfitness.app.domain.adaptive.ProgramConfiguration.default(
            generator.getExerciseLibrary().map { it.id }.toSet()
        )
        val equipmentAtStart = com.monkfitness.app.data.model.Equipment.entries.toSet()
        // Same frozen snapshot and adaptive plan, exactly the values the VM retains between emissions.
        for (day in 1..56) {
            val active = ActiveWorkoutConfiguration()
            var equipment = equipmentAtStart
            fun context() = WorkoutSessionContext(1, 0, java.time.LocalDate.of(2026, 9, 1),
                WorkoutSessionGeneration(availableEquipment = equipment))
            val snapshot = active.beginSession(WorkoutSessionIdentity(day, false), ::context) { configuration }
            val plan = integration.planSession(com.monkfitness.app.domain.usecase.AdaptiveSessionRequest(
                programDay = day, programCycle = 1, configuration = snapshot,
                availableEquipment = equipmentAtStart
            ))
            fun generated(equipment: Set<com.monkfitness.app.data.model.Equipment>) =
                integration.generateWorkout(com.monkfitness.app.domain.usecase.AdaptiveWorkoutGenerationRequest(
                    programDay = day, availableEquipment = equipment
                ), plan)
            val before = generated(equipmentAtStart)
            equipment = setOf(com.monkfitness.app.data.model.Equipment.NONE)
            active.beginSession(WorkoutSessionIdentity(day, false), ::context) { configuration }
            val after = generated(requireNotNull(active.activeSession.value?.context).generation.availableEquipment)
            org.junit.Assert.assertEquals("day=$day frozen plan must survive live equipment edit; " +
                "before=${before.exercises.map { it.id }} after=${after.exercises.map { it.id }}", before, after)
        }
    }

    @Test
    fun anActiveWorkoutDoesNotSubscribeToLiveGenerationSettings() {
        val source = mainViewModelSource()
        val inputs = source.substringAfter("val workoutSessionUiState = combine(").substringBefore(") {")
        val live = listOf("exerciseDifficultyAdjustments", "flexibilityTrainingType", "flexibilityFocusAreas",
            "availableEquipment", "disabledExerciseFamilies").filter { it in inputs }
        assertTrue("started workout is rebuilt by live settings: $live", live.isEmpty())
    }

    /**
     * The session's own adaptive inputs are frozen facts, not the app's live flows.
     *
     * This is the invariant the Task 14 repair established and the one an innocuous-looking refactor
     * removes first: the plan of a running session is derived from the context captured at its start
     * transition, so a `Start Revised Program`, a cycle rollover or a settings change that lands mid-session
     * cannot re-point the plan of a workout the user is already performing. Reading the same names off the
     * ViewModel's flows is what the code did before the repair, and it is exactly what this scan rejects —
     * including inside the completion path, which must hand the recorder the frozen generation settings
     * rather than the live ones.
     */
    @Test
    fun theSessionPlanAndFinalizationReadTheFrozenContextAndNeverTheLiveFlows() {
        val source = mainViewModelSource()
        val liveFlowInputs = listOf(
            "programRevision.value", "programCycleNumber.value", "_currentWorkoutDay.value",
            "availableEquipment.value", "programStartDate.value", "exerciseDifficultyAdjustments.value",
            "flexibilityTrainingType.value", "flexibilityFocusAreas.value", "disabledExerciseFamilies.value"
        )

        val planBody = bodyOf(source, "private suspend fun readSessionAdaptivePlan(")
        assertEquals(
            "the session's adaptive plan is derived from live state: " +
                liveFlowInputs.filter { it in planBody },
            emptyList<String>(),
            liveFlowInputs.filter { it in planBody }
        )
        assertTrue(
            "the session's plan must be derived from the context the session froze",
            "activeWorkoutConfiguration.activeSession" in planBody
        )

        val finalizationBody = bodyOf(source, "private suspend fun recordAdaptiveDecision(")
        assertEquals(
            "finalization reads live state: " + liveFlowInputs.filter { it in finalizationBody },
            emptyList<String>(),
            liveFlowInputs.filter { it in finalizationBody }
        )
        assertTrue(
            "finalization must hand the recorder the session's frozen generation settings",
            "session.context?.generation?.availableEquipment" in finalizationBody
        )
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun mainViewModelSource(): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/src/main/java/com/monkfitness/app/viewmodel/MainViewModel.kt").readText()
    }

    /** The body of the first function whose declaration contains [signature], by brace matching. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is not declared in MainViewModel", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("$signature is not a function body", open > start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("unbalanced braces in $signature")
    }
}
