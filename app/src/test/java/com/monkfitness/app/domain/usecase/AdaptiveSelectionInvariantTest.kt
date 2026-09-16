package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.repository.AdaptiveLifecycleRig
import com.monkfitness.app.data.repository.persistedFamilyState
import com.monkfitness.app.domain.adaptive.*
import org.junit.Assert.*
import org.junit.Test

/** Task 15: hostile preferences and calendar-wide candidate constraints, not another policy. */
class AdaptiveSelectionInvariantTest {
    private val generator = WorkoutGenerator()
    private val integration = AdaptiveWorkoutIntegration(generator)
    private val ids = generator.getExerciseLibrary().map { it.id }.toSet()

    @Test fun forgedUnknownTargetCannotManufactureAnExercise() {
        val plan = integration.planSession(AdaptiveSessionRequest(1, 7,
            configuration = WorkoutConfigurationSnapshot(4, ids)))
        val forged = plan.copy(progression = plan.progression.copy(families = listOf(
            AdaptiveFamilyResolution("pushups", AdaptiveDecision(
                state = AdaptiveState.PROGRESS, previousState = AdaptiveState.HOLD,
                actions = listOf(AdaptiveAction.INCREASE_STIMULUS),
                reasonCode = AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE, policyVersion = 1,
                familyId = "pushups"), ProgressionResolution("pushups", ProgressionOutcome.STEP,
                1, "not_in_the_library", 0))
        )), permittedExerciseIds = ids + "not_in_the_library")
        assertEquals(setOf("not_in_the_library"), forged.resolvedExerciseIds)
        for (day in 1..TOTAL_PROGRAM_DAYS) {
            val workout = integration.generateWorkout(AdaptiveWorkoutGenerationRequest(day), forged)
            assertTrue("day=$day invented ${workout.exercises.map { it.id } - ids}",
                workout.exercises.all { it.id in ids })
        }
    }

    @Test fun confirmedDisabledStepUsesOnlyDeclaredFallbackAcrossEveryCalendarDayAndCycle() {
        val rig = AdaptiveLifecycleRig()
        val enabled = ids - setOf("pushups_wide", "decline_pushups")
        val state = persistedFamilyState(currentExerciseId = "pushups",
            precedingProgressQualifyingWindows = AdaptivePolicy.V1.progressConfirmingWindows - 1)
        for (cycle in listOf(1, 2, 9)) for (day in 1..TOTAL_PROGRAM_DAYS) {
            val request = AdaptiveSessionRequest(day, cycle,
                configuration = WorkoutConfigurationSnapshot(8, enabled),
                familyStates = listOf(state), recentSessions = rig.fullExposureSessions())
            val plan = integration.planSession(request)
            val family = requireNotNull(plan.progression.family("pushups"))
            assertEquals(AdaptiveState.PROGRESS, family.decision.state)
            assertEquals(ProgressionOutcome.FALLBACK, family.resolution.outcome)
            assertEquals("pushups", family.resolution.exerciseId)
            assertEquals(PilotProgressionProfiles.forFamily("pushups")!!.fallbackStep,
                family.resolution.adjustment)
            assertEquals(0, family.resolution.level)
            val workout = integration.generateWorkout(AdaptiveWorkoutGenerationRequest(day), plan)
            assertEquals(day, workout.id)
            assertEquals(day, plan.progression.programDay)
            assertEquals(cycle, plan.progression.programCycle)
            assertTrue("cycle=$cycle day=$day disabled exercise: ${workout.exercises.map { it.id }}",
                workout.exercises.all { it.id in enabled })
        }
        assertEquals(0, state.progressionLevel)
    }

    @Test fun equipmentSafetySurvivesHostilePreferencesInNormalAndAdaptiveGeneration() {
        val rig = AdaptiveLifecycleRig()
        val equipmentCases = listOf(setOf(Equipment.NONE), setOf(Equipment.BAR),
            setOf(Equipment.BANDS), setOf(Equipment.BACKPACK), Equipment.entries.toSet())
        for (equipment in equipmentCases) {
            val permitted = generator.getExerciseLibrary(equipment).map { it.id }.toSet()
            val plan = integration.planSession(AdaptiveSessionRequest(5, 1,
                configuration = WorkoutConfigurationSnapshot(1, ids), availableEquipment = equipment,
                recentSessions = rig.fullExposureSessions(exerciseId = "pullups"),
                familyStates = listOf(persistedFamilyState(familyId = "pullups", currentExerciseId = "pullups",
                    precedingProgressQualifyingWindows = AdaptivePolicy.V1.progressConfirmingWindows - 1))))
            assertEquals(permitted, plan.permittedExerciseIds)
            assertTrue(plan.resolvedExerciseIds.all { it in permitted })
            for (day in 1..TOTAL_PROGRAM_DAYS) {
                val normal = generator.generateWorkout(day, availableEquipment = equipment,
                    preferredExerciseIds = ids)
                val adaptive = integration.generateWorkout(AdaptiveWorkoutGenerationRequest(day,
                    availableEquipment = equipment), plan)
                for (workout in listOf(normal, adaptive)) assertTrue(
                    "day=$day equipment=$equipment selected=${workout.exercises.map { it.id }}",
                    workout.exercises.all { it.id in permitted })
            }
        }
        val none = generator.getExerciseLibrary(setOf(Equipment.NONE)).map { it.id }.toSet()
        assertFalse("non-vacuous required-equipment witness", "pullups" in none)
        assertTrue("unrecorded equipment retains existing permissive semantics",
            "pullups" in generator.getExerciseLibrary(emptySet()).map { it.id })
    }
}
