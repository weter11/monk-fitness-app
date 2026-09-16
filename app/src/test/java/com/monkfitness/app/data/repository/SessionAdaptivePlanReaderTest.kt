package com.monkfitness.app.data.repository

import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ExerciseResult
import com.monkfitness.app.domain.adaptive.ProgramConfiguration
import com.monkfitness.app.domain.adaptive.ProgramType
import com.monkfitness.app.domain.adaptive.ProgressionOutcome
import com.monkfitness.app.domain.adaptive.SessionObservation
import com.monkfitness.app.domain.adaptive.SessionOutcome
import com.monkfitness.app.domain.adaptive.Workload
import com.monkfitness.app.domain.adaptive.WorkoutConfigurationSnapshot
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Task 13's read boundary: the session's plan is the join of the persisted history and the persisted
 * family progression, and a read that fails withholds progress instead of inventing it.
 *
 * The reader takes its two reads as functions — the production wiring is `SessionAdaptivePlanReader.of`
 * — so it can be driven here without a database, while the values it is driven with are the same
 * domain types production reads.
 *
 * What is pinned:
 *
 *  * the plan is built from the *stored* history and the *stored* state of the requested revision,
 *    and nothing else: the same request against a different stored level resolves differently;
 *  * a history read that fails degrades to no evidence — HOLD for every family, the stored level
 *    preserved — and never to a fabricated progression step;
 *  * a family-state read that fails does the same, and the session is still planned;
 *  * the reader passes the session's own configuration through, so the plan it returns cannot be
 *    wider than the session's capture;
 *  * the reader never writes: the state lists it is handed come back unchanged.
 */
class SessionAdaptivePlanReaderTest {

    private val generator = WorkoutGenerator()
    private val allExerciseIds: Set<String> = generator.getExerciseLibrary().map { it.id }.toSet()

    private val programStartDate = LocalDate.of(2026, 1, 5)

    private val pushups = "pushups"
    private val widePushups = "pushups_wide"

    private fun configuration(vararg disabled: String): WorkoutConfigurationSnapshot =
        WorkoutConfigurationSnapshot.capture(
            ProgramConfiguration.custom(allExerciseIds - disabled.toSet(), 7)
        )

    /** Five sessions, exposures 0.70 / 0.80 / 0.90 / 1.00 / 1.00 → exposure 0.92, trend POSITIVE. */
    private fun progressingHistory(): List<SessionObservation> =
        listOf(14, 16, 18, 20, 20).mapIndexed { index, reps ->
            SessionObservation(
                cycleNumber = 1,
                programDay = index + 1,
                startedAt = 1_700_000_000_000L,
                finishedAt = 1_700_000_600_000L,
                outcome = SessionOutcome.COMPLETED,
                plannedExercises = 1,
                completedExercises = 1,
                plannedWork = Workload(sets = 3, reps = 20),
                actualWork = Workload(sets = 3, reps = reps),
                exerciseResults = listOf(
                    ExerciseResult(
                        exerciseId = pushups,
                        plannedSets = 3,
                        completedSets = 3,
                        plannedReps = 20,
                        completedReps = reps,
                        plannedDurationSeconds = 0,
                        completedDurationSeconds = 0
                    )
                )
            )
        }

    private fun familyState(
        level: Int = 0,
        progressWindows: Int = 0,
        revision: Int = 0
    ): FamilyProgressionState = FamilyProgressionState(
        familyId = "pushups",
        progressionLevel = level,
        adaptationState = AdaptiveState.HOLD,
        precedingProgressQualifyingWindows = progressWindows,
        eligibleSessionsSinceLastProgressionChange = null,
        programRevision = revision,
        updatedAt = 1_700_000_000_000L
    )

    private fun inputs(
        configuration: WorkoutConfigurationSnapshot = configuration(),
        programDay: Int = 1,
        programRevision: Int = 0
    ) = SessionAdaptiveInputs(
        programDay = programDay,
        programCycle = 1,
        programType = if (programRevision == 0) ProgramType.STANDARD else ProgramType.REVISED,
        programRevision = programRevision,
        configuration = configuration,
        availableEquipment = emptySet(),
        programStartDate = programStartDate
    )

    private fun reader(
        history: suspend (LocalDate) -> List<SessionObservation>,
        states: suspend (Int) -> List<FamilyProgressionState>
    ) = SessionAdaptivePlanReader(history, states, generator)

    @Test
    fun thePlanIsTheJoinOfTheStoredHistoryAndTheStoredProgressionState() {
        val stored = familyState(level = 0, progressWindows = 1)
        val plan = runBlocking {
            reader(history = { progressingHistory() }, states = { listOf(stored) })
                .read(inputs())
        }

        assertEquals(7, plan.configurationVersion)
        val pushups = plan.progression.family(this.pushups)!!
        assertEquals(AdaptiveState.PROGRESS, pushups.decision.state)
        assertEquals(ProgressionOutcome.STEP, pushups.resolution.outcome)
        assertEquals(1, pushups.resolution.level)
        assertEquals(widePushups, pushups.resolution.exerciseId)
        assertTrue(widePushups in plan.permittedExerciseIds)
    }

    @Test
    fun theStoredProgressionLevelIsWhatThePlanResolvesFrom() {
        // Same history, same decision, different stored level: the resolution follows the stored
        // level, which is what makes progression independent per family and persistent across days.
        val atBaseline = runBlocking {
            reader(history = { progressingHistory() }, states = { listOf(familyState(level = 0, progressWindows = 1)) })
                .read(inputs())
        }
        val furtherAlong = runBlocking {
            reader(history = { progressingHistory() }, states = { listOf(familyState(level = 1, progressWindows = 1)) })
                .read(inputs())
        }

        assertEquals(1, atBaseline.progression.family(pushups)!!.resolution.level)
        assertEquals(2, furtherAlong.progression.family(pushups)!!.resolution.level)
    }

    @Test
    fun theRequestedProgramRevisionIsTheStateThatIsRead() {
        var requestedRevision: Int? = null
        val plan = runBlocking {
            reader(
                history = { emptyList() },
                states = { revision ->
                    requestedRevision = revision
                    listOf(familyState(level = 2, revision = revision))
                }
            ).read(inputs(programRevision = 1))
        }

        assertEquals(1, requestedRevision)
        assertEquals(ProgramType.REVISED, plan.progression.programType)
        assertEquals(2, plan.progression.family(pushups)!!.resolution.level)
    }

    @Test
    fun aFailedHistoryReadWithholdsProgressInsteadOfInventingIt() {
        val stored = familyState(level = 1, progressWindows = 1)
        val plan = runBlocking {
            reader(
                history = { throw IllegalStateException("the history read failed") },
                states = { listOf(stored) }
            ).read(inputs())
        }

        val pushups = plan.progression.family(this.pushups)!!
        assertEquals(AdaptiveState.HOLD, pushups.decision.state)
        assertEquals(ProgressionOutcome.HOLD, pushups.resolution.outcome)
        assertEquals(1, pushups.resolution.level)
        // Nothing was written: the row the caller handed in is unchanged, level included.
        assertEquals(1, stored.progressionLevel)
    }

    @Test
    fun aFailedStateReadWithholdsProgressAndStillPlansTheSession() {
        val plan = runBlocking {
            reader(
                history = { progressingHistory() },
                states = { throw IllegalStateException("the state read failed") }
            ).read(inputs())
        }

        // The family is still evaluated — from its documented untracked state, which cannot progress.
        val pushups = plan.progression.family(this.pushups)!!
        assertEquals(AdaptiveState.HOLD, pushups.decision.state)
        assertEquals(0, pushups.resolution.level)
        assertTrue(plan.permittedExerciseIds.isNotEmpty())
    }

    @Test
    fun thePlanCannotBeWiderThanTheSessionsCapturedConfiguration() {
        val captured = configuration(widePushups)
        val plan = runBlocking {
            reader(history = { progressingHistory() }, states = { listOf(familyState(progressWindows = 1)) })
                .read(inputs(configuration = captured))
        }

        assertFalse(widePushups in plan.permittedExerciseIds)
        assertTrue(plan.permittedExerciseIds.all { it in captured.enabledExerciseIds })
        // The resolved step is the family's own, but it never reaches generation when the session's
        // configuration does not permit it.
        assertTrue(
            plan.resolvedExerciseIds.none { it !in captured.enabledExerciseIds }
        )
    }
}
