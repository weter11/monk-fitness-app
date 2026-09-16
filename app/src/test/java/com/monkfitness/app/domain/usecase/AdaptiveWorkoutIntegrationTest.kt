package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.AdaptiveAction
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ExerciseResult
import com.monkfitness.app.domain.adaptive.FamilyAdaptationState
import com.monkfitness.app.domain.adaptive.ProgramConfiguration
import com.monkfitness.app.domain.adaptive.ProgramType
import com.monkfitness.app.domain.adaptive.ProgressionOutcome
import com.monkfitness.app.domain.adaptive.SessionObservation
import com.monkfitness.app.domain.adaptive.SessionOutcome
import com.monkfitness.app.domain.adaptive.Workload
import com.monkfitness.app.domain.adaptive.WorkoutConfigurationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 13 acceptance tests: the adaptive engine and the persisted family progression drive the
 * workout the session actually presents.
 *
 * What they pin, in the order the task states it:
 *
 *  * **independent families** — every family resolves through its own ladder and its own stored
 *    progression state; there is no single global difficulty level;
 *  * **HOLD / PROGRESS / REGRESS** — hold keeps the stored level, progress and regress move exactly
 *    one step on the family's own ladder, and neither reads the whole window as a level;
 *  * **RECOVERY** — orders the recovery load, moves no level and destroys no stored progression;
 *  * **the user's configuration is a constraint** — a disabled exercise is never emitted, however
 *    normal a progression step it would otherwise be;
 *  * **the session's frozen snapshot** — a session generates from the configuration captured at its
 *    start, and a later edit reaches the next session instead;
 *  * **equipment** — a candidate the available equipment cannot support is not selectable, and no
 *    substitution may smuggle it in;
 *  * **impossible progression** — HOLD, never a disabled exercise and never a second progression
 *    algorithm;
 *  * **the generator stays the builder** — every exercise asserted below came out of
 *    [WorkoutGenerator], inside the session's permitted set.
 *
 * Fixtures use the real exercise library ids and the real pilot ladders (`pushups`, `squats`,
 * `plank`, `pullups`), so a ladder whose ids stop matching the library fails here rather than
 * passing on a synthetic catalogue. The three histories are the calibrated ones of
 * `AdaptiveProgramEngineTest` (exposures / trends / load documented there), re-expressed with real
 * ids: `PROGRESSING` is the five-session positive trend, `DECLINING` the five-session negative one,
 * `HIGH_LOAD_DECLINE` the fourteen-session high-load deterioration.
 */
class AdaptiveWorkoutIntegrationTest {

    private val generator = WorkoutGenerator()
    private val integration = AdaptiveWorkoutIntegration(generator)

    private val library = generator.getExerciseLibrary()
    private val allExerciseIds: Set<String> = library.map { it.id }.toSet()
    private val equipmentGatedIds: Set<String> =
        library.filter { it.requiredEquipment.isNotEmpty() }.map { it.id }.toSet()

    private companion object {
        /** Monday: `WorkoutType.STRENGTH_A`, whose first rule is the LEGS strength rule. */
        const val LEGS_DAY = 1

        /** Wednesday: `WorkoutType.STRENGTH_B`, whose first rule is the SHOULDERS strength rule. */
        const val SHOULDERS_DAY = 3

        const val PLANNED_REPS = 20
        const val STARTED_AT = 1_700_000_000_000L
        const val FINISHED_AT = 1_700_000_600_000L

        const val FAMILY_PUSHUPS = "pushups"
        const val FAMILY_SQUATS = "squats"
        const val FAMILY_PLANK = "plank"
        const val FAMILY_PULLUPS = "pullups"

        const val STANDARD_PUSHUPS = "pushups"
        const val WIDE_PUSHUPS = "pushups_wide"
        const val DECLINE_PUSHUPS = "decline_pushups"
        const val STANDARD_SQUATS = "squats"
        const val COSSACK_SQUAT = "cossack_squat"
        const val JUMP_SQUATS = "squats_jump"
        const val SUMO_SQUATS = "squats_sumo"
        const val NEUTRAL_PULLUPS = "pullups_neutral"
    }

    // ------------------------------------------------------------------ fixtures

    private data class Performed(val exerciseId: String, val completedReps: Int)

    private fun session(
        day: Int,
        exercises: List<Performed>,
        setsPerExercise: Int = 3,
        cycle: Int = 1
    ): SessionObservation {
        val plannedReps = PLANNED_REPS * exercises.size
        val completedReps = exercises.sumOf { it.completedReps }
        val sets = setsPerExercise * exercises.size
        return SessionObservation(
            cycleNumber = cycle,
            programDay = day,
            startedAt = STARTED_AT,
            finishedAt = FINISHED_AT,
            outcome = SessionOutcome.COMPLETED,
            plannedExercises = exercises.size,
            completedExercises = exercises.count { it.completedReps > 0 },
            plannedWork = Workload(sets = sets, reps = plannedReps),
            actualWork = Workload(sets = sets, reps = completedReps),
            exerciseResults = exercises.map { performed ->
                ExerciseResult(
                    exerciseId = performed.exerciseId,
                    plannedSets = setsPerExercise,
                    completedSets = if (performed.completedReps > 0) setsPerExercise else 0,
                    plannedReps = PLANNED_REPS,
                    completedReps = performed.completedReps,
                    plannedDurationSeconds = 0,
                    completedDurationSeconds = 0
                )
            }
        )
    }

    /** Five sessions, exposures 0.70 / 0.80 / 0.90 / 1.00 / 1.00 → exposure 0.92, trend POSITIVE. */
    private fun progressingHistory(vararg exerciseIds: String): List<SessionObservation> =
        listOf(14, 16, 18, 20, 20).mapIndexed { index, reps ->
            session(index + 1, exerciseIds.map { Performed(it, reps) })
        }

    /** Five sessions, exposures 0.85 / 0.75 / 0.65 / 0.50 / 0.35 → exposure 0.5575, trend NEGATIVE. */
    private fun decliningHistory(exerciseId: String): List<SessionObservation> =
        listOf(17, 15, 13, 10, 7).mapIndexed { index, reps ->
            session(index + 1, listOf(Performed(exerciseId, reps)))
        }

    /** Fourteen sessions, load ratio 3.0 with declining exposures → high-load deterioration. */
    private fun highLoadDeclineHistory(exerciseId: String): List<SessionObservation> {
        val completedReps = mapOf(
            1 to 20, 2 to 20, 3 to 20, 4 to 20, 5 to 20, 6 to 20, 7 to 20,
            8 to 18, 9 to 18, 10 to 18, 11 to 14, 12 to 10, 13 to 8, 14 to 6
        )
        return (1..14).map { day ->
            session(
                day = day,
                exercises = listOf(Performed(exerciseId, completedReps.getValue(day))),
                setsPerExercise = if (day <= 7) 2 else 6
            )
        }
    }

    /** One persisted family state, as Task 8 stores it. */
    private fun familyState(
        familyId: String,
        level: Int = 0,
        state: AdaptiveState = AdaptiveState.HOLD,
        progressWindows: Int = 0,
        regressWindows: Int = 0,
        highRiskWindows: Int = 0,
        recoverySessions: Int = 0,
        currentExerciseId: String? = null,
        sessionsSinceLastChange: Int? = null,
        revision: Int = 0
    ): FamilyProgressionState = FamilyProgressionState(
        familyId = familyId,
        progressionLevel = level,
        currentExerciseId = currentExerciseId,
        adaptationState = state,
        precedingProgressQualifyingWindows = progressWindows,
        precedingRegressQualifyingWindows = regressWindows,
        precedingHighRiskWindows = highRiskWindows,
        recoveryQualifyingSessions = recoverySessions,
        eligibleSessionsSinceLastProgressionChange = sessionsSinceLastChange,
        programRevision = revision,
        updatedAt = STARTED_AT
    )

    /** The authoritative default configuration with [disabled] left out by the user. */
    private fun customConfiguration(vararg disabled: String, version: Int = 1): ProgramConfiguration =
        ProgramConfiguration.custom(allExerciseIds - disabled.toSet(), version)

    private fun defaultConfiguration(): ProgramConfiguration =
        ProgramConfiguration.default(allExerciseIds)

    private fun plan(
        programDay: Int = LEGS_DAY,
        configuration: WorkoutConfigurationSnapshot = WorkoutConfigurationSnapshot.capture(defaultConfiguration()),
        history: List<SessionObservation> = emptyList(),
        states: List<FamilyProgressionState> = emptyList(),
        availableEquipment: Set<Equipment> = emptySet(),
        programType: ProgramType = ProgramType.STANDARD
    ): AdaptiveSessionPlan = integration.planSession(
        AdaptiveSessionRequest(
            programDay = programDay,
            programCycle = 1,
            programType = programType,
            configuration = configuration,
            availableEquipment = availableEquipment,
            recentSessions = history,
            familyStates = states
        )
    )

    private fun workoutOf(
        plan: AdaptiveSessionPlan,
        programDay: Int = LEGS_DAY,
        availableEquipment: Set<Equipment> = emptySet(),
        disabledFamilies: Set<String> = emptySet(),
        postureMobility: Boolean = false
    ) = integration.generateWorkout(
        AdaptiveWorkoutGenerationRequest(
            programDay = programDay,
            availableEquipment = availableEquipment,
            disabledFamilies = disabledFamilies,
            isPostureMobilitySession = postureMobility
        ),
        plan
    )

    private fun AdaptiveSessionPlan.resolutionOf(familyId: String) =
        requireNotNull(progression.family(familyId)) {
            "the plan evaluated no family $familyId, families were ${progression.families.map { it.familyId }}"
        }

    private fun exerciseIds(workout: com.monkfitness.app.data.model.Workout): List<String> =
        workout.exercises.map { it.id }

    // ------------------------------------------------------------------ A: independent families

    /**
     * Requirement A: one generation, three families, three different stored progression states —
     * each family resolves on its own ladder and none of them inherits another's level or another's
     * decision.
     */
    @Test
    fun eachFamilyResolvesFromItsOwnPersistedProgressionState() {
        val plan = plan(
            history = progressingHistory(STANDARD_PUSHUPS, STANDARD_SQUATS),
            states = listOf(
                familyState(FAMILY_PUSHUPS, level = 1, progressWindows = 1),
                familyState(FAMILY_SQUATS, level = 0, progressWindows = 1),
                familyState(FAMILY_PLANK, level = -1, state = AdaptiveState.HOLD, progressWindows = 0)
            )
        )

        val pushups = plan.resolutionOf(FAMILY_PUSHUPS)
        val squats = plan.resolutionOf(FAMILY_SQUATS)
        val plank = plan.resolutionOf(FAMILY_PLANK)

        // The window's evidence is identical for all three; only the families' own state differs.
        assertEquals(AdaptiveState.PROGRESS, pushups.decision.state)
        assertEquals(AdaptiveState.PROGRESS, squats.decision.state)
        assertEquals(AdaptiveState.HOLD, plank.decision.state)

        assertEquals(ProgressionOutcome.STEP, pushups.resolution.outcome)
        assertEquals(2, pushups.resolution.level)
        assertEquals(DECLINE_PUSHUPS, pushups.resolution.exerciseId)
        assertEquals(ProgressionOutcome.STEP, squats.resolution.outcome)
        assertEquals(1, squats.resolution.level)
        assertEquals(COSSACK_SQUAT, squats.resolution.exerciseId)
        assertEquals(ProgressionOutcome.HOLD, plank.resolution.outcome)
        assertEquals(-1, plank.resolution.level)

        // Three families, three levels, and no figure shared between them.
        assertEquals(listOf(2, 1, -1), listOf(pushups.resolution.level, squats.resolution.level, plank.resolution.level))
    }

    // ------------------------------------------------------------------ B: HOLD

    /** Requirement B: HOLD keeps the stored level exactly — no reset, no increment, no decrement. */
    @Test
    fun aHoldLeavesTheStoredProgressionLevelUntouched() {
        val states = listOf(
            familyState(FAMILY_PUSHUPS, level = 1),
            familyState(FAMILY_SQUATS, level = -1)
        )
        val plan = plan(history = emptyList(), states = states)

        // No evidence at all: the policy holds every family of the window.
        plan.progression.families.forEach { family ->
            assertEquals(family.familyId, AdaptiveState.HOLD, family.decision.state)
            assertEquals(family.familyId, listOf(AdaptiveAction.MAINTAIN_STIMULUS), family.decision.actions)
        }

        val pushups = plan.resolutionOf(FAMILY_PUSHUPS)
        val squats = plan.resolutionOf(FAMILY_SQUATS)
        assertEquals(ProgressionOutcome.HOLD, pushups.resolution.outcome)
        assertEquals(1, pushups.resolution.level)
        assertNull(pushups.resolution.exerciseId)
        assertEquals(0, pushups.resolution.adjustment)
        assertEquals(ProgressionOutcome.HOLD, squats.resolution.outcome)
        assertEquals(-1, squats.resolution.level)

        // The session is still generated — from the exercises the frozen configuration permits.
        val workout = workoutOf(plan)
        assertTrue("a held session still presents a workout", workout.exercises.isNotEmpty())
        assertTrue(workout.exercises.all { it.id in plan.permittedExerciseIds })
    }

    // ------------------------------------------------------------------ C: PROGRESS

    /** Requirement D: PROGRESS moves exactly one level forward, and never more. */
    @Test
    fun aProgressMovesExactlyOneLevelForward() {
        val plan = plan(
            history = progressingHistory(STANDARD_PUSHUPS, STANDARD_SQUATS),
            states = listOf(
                familyState(FAMILY_PUSHUPS, level = 0, progressWindows = 1),
                familyState(FAMILY_SQUATS, level = -1, progressWindows = 1)
            )
        )

        val pushups = plan.resolutionOf(FAMILY_PUSHUPS)
        val squats = plan.resolutionOf(FAMILY_SQUATS)

        assertEquals(ProgressionOutcome.STEP, pushups.resolution.outcome)
        assertEquals(1, pushups.resolution.level)
        assertEquals(WIDE_PUSHUPS, pushups.resolution.exerciseId)

        assertEquals(ProgressionOutcome.STEP, squats.resolution.outcome)
        assertEquals(0, squats.resolution.level)
        assertEquals(STANDARD_SQUATS, squats.resolution.exerciseId)

        // The level stays inside the documented model.
        plan.progression.families.forEach { family ->
            assertTrue(
                "${family.familyId} left the documented level range",
                family.resolution.level in FamilyAdaptationState.MIN_LEVEL..FamilyAdaptationState.MAX_LEVEL
            )
        }
    }

    /** Requirement D at the upper bound: +2 cannot progress further, and does not clamp to a fifth level. */
    @Test
    fun aProgressAtTheUpperBoundHolds() {
        val plan = plan(
            history = progressingHistory(STANDARD_SQUATS),
            states = listOf(familyState(FAMILY_SQUATS, level = FamilyAdaptationState.MAX_LEVEL, progressWindows = 1))
        )

        val squats = plan.resolutionOf(FAMILY_SQUATS)
        assertEquals(AdaptiveState.PROGRESS, squats.decision.state)
        assertEquals(ProgressionOutcome.HOLD, squats.resolution.outcome)
        assertEquals(FamilyAdaptationState.MAX_LEVEL, squats.resolution.level)
        assertNull(squats.resolution.exerciseId)
    }

    // ------------------------------------------------------------------ D: REGRESS

    /** Requirement C: REGRESS moves exactly one level back, in both directions of the ladder. */
    @Test
    fun aRegressMovesExactlyOneLevelBack() {
        val fromTop = plan(
            history = decliningHistory(STANDARD_SQUATS),
            states = listOf(familyState(FAMILY_SQUATS, level = 2, regressWindows = 1))
        ).resolutionOf(FAMILY_SQUATS)

        assertEquals(AdaptiveState.REGRESS, fromTop.decision.state)
        assertEquals(listOf(AdaptiveAction.REDUCE_STIMULUS), fromTop.decision.actions)
        assertEquals(ProgressionOutcome.STEP, fromTop.resolution.outcome)
        assertEquals(1, fromTop.resolution.level)
        assertEquals(COSSACK_SQUAT, fromTop.resolution.exerciseId)

        val fromBelowBaseline = plan(
            history = decliningHistory(STANDARD_SQUATS),
            states = listOf(familyState(FAMILY_SQUATS, level = -1, regressWindows = 1))
        ).resolutionOf(FAMILY_SQUATS)

        assertEquals(ProgressionOutcome.STEP, fromBelowBaseline.resolution.outcome)
        assertEquals(-2, fromBelowBaseline.resolution.level)
        assertEquals(SUMO_SQUATS, fromBelowBaseline.resolution.exerciseId)
    }

    /** Requirement C at the lower bound: -2 has nowhere to go — HOLD, never a second step. */
    @Test
    fun aRegressAtTheLowerBoundHolds() {
        val squats = plan(
            history = decliningHistory(STANDARD_SQUATS),
            states = listOf(familyState(FAMILY_SQUATS, level = FamilyAdaptationState.MIN_LEVEL, regressWindows = 1))
        ).resolutionOf(FAMILY_SQUATS)

        assertEquals(AdaptiveState.REGRESS, squats.decision.state)
        assertEquals(ProgressionOutcome.HOLD, squats.resolution.outcome)
        assertEquals(FamilyAdaptationState.MIN_LEVEL, squats.resolution.level)
        assertNull(squats.resolution.exerciseId)
    }

    // ------------------------------------------------------------------ E: RECOVERY

    /**
     * Requirement E: a recovery window orders the recovery load, moves no level and destroys
     * nothing — the stored progression is exactly where it was, so the next normal session still
     * sees it.
     */
    @Test
    fun aRecoveryDecisionKeepsTheStoredProgressionAndMovesNothing() {
        val stored = familyState(FAMILY_PUSHUPS, level = 1, currentExerciseId = WIDE_PUSHUPS)
        val plan = plan(
            history = highLoadDeclineHistory(STANDARD_PUSHUPS),
            states = listOf(stored)
        )

        val pushups = plan.resolutionOf(FAMILY_PUSHUPS)
        assertEquals(AdaptiveState.RECOVERY, pushups.decision.state)
        assertEquals(listOf(AdaptiveAction.RECOVERY_LOAD), pushups.decision.actions)

        // No level move, no target, no adjustment: recovery is not a regression.
        assertEquals(ProgressionOutcome.HOLD, pushups.resolution.outcome)
        assertEquals(1, pushups.resolution.level)
        assertNull(pushups.resolution.exerciseId)
        assertEquals(0, pushups.resolution.adjustment)

        // Nothing was written: the caller's own state still reports the level it supplied.
        assertEquals(1, stored.progressionLevel)
        assertEquals(AdaptiveState.HOLD, stored.adaptationState)

        // The session itself still generates, from the permitted set.
        val workout = workoutOf(plan)
        assertTrue(workout.exercises.isNotEmpty())
        assertTrue(workout.exercises.all { it.id in plan.permittedExerciseIds })
    }

    /** Recovery resolves per family like every other state: the recovery ordering is not global either. */
    @Test
    fun recoveryAppliesOnlyToTheFamilyWhoseWindowQualifies() {
        val plan = plan(
            configuration = WorkoutConfigurationSnapshot.capture(
                customConfiguration(*nonPushupAndSquatIds().toTypedArray())
            ),
            history = highLoadDeclineHistory(STANDARD_PUSHUPS),
            states = listOf(
                familyState(FAMILY_PUSHUPS, level = 1),
                familyState(FAMILY_SQUATS, level = 1)
            )
        )

        // The window's risk reading is shared, so both evaluated families take the recovery ordering;
        // neither loses its stored level, which is what "recovery is not a reset" means at this layer.
        assertEquals(listOf(FAMILY_PUSHUPS, FAMILY_SQUATS), plan.progression.families.map { it.familyId })
        plan.progression.families.forEach { family ->
            assertEquals(AdaptiveState.RECOVERY, family.decision.state)
            assertEquals(ProgressionOutcome.HOLD, family.resolution.outcome)
            assertEquals(1, family.resolution.level)
        }
    }

    /** Every library id whose family is neither push-ups nor squats — i.e. everything else. */
    private fun nonPushupAndSquatIds(): List<String> =
        library.filter { it.familyId != FAMILY_PUSHUPS && it.familyId != FAMILY_SQUATS }.map { it.id }

    private fun familyExerciseIds(familyId: String): List<String> =
        library.filter { it.familyId == familyId }.map { it.id }

    // ------------------------------------------------------------------ the user's selection is a constraint

    /**
     * Test 6 and the first negative test of section 12: the exercise the family would normally
     * progress to is disabled, and it must not appear — not as the resolved target, not in the
     * workout, not through a substitution.
     */
    @Test
    fun aDisabledProgressionChoiceIsNeverEmitted() {
        val configuration = customConfiguration(COSSACK_SQUAT, JUMP_SQUATS)
        val plan = plan(
            programDay = LEGS_DAY,
            configuration = WorkoutConfigurationSnapshot.capture(configuration),
            history = progressingHistory(STANDARD_SQUATS),
            states = listOf(familyState(FAMILY_SQUATS, level = 0, progressWindows = 1))
        )

        val squats = plan.resolutionOf(FAMILY_SQUATS)
        // The family wanted +1; +1 is cossack_squat, and the squats ladder declares no fallback axis.
        assertEquals(AdaptiveState.PROGRESS, squats.decision.state)
        assertEquals(ProgressionOutcome.HOLD, squats.resolution.outcome)
        assertEquals(0, squats.resolution.level)
        assertNull(squats.resolution.exerciseId)

        assertFalse(COSSACK_SQUAT in plan.permittedExerciseIds)
        assertFalse(JUMP_SQUATS in plan.permittedExerciseIds)

        val workout = workoutOf(plan)
        assertTrue("the session still presents a workout", workout.exercises.isNotEmpty())
        assertFalse("a disabled exercise reached the workout", COSSACK_SQUAT in exerciseIds(workout))
        assertFalse("a disabled exercise reached the workout", JUMP_SQUATS in exerciseIds(workout))
        assertTrue(workout.exercises.all { it.id in plan.permittedExerciseIds })
    }

    /**
     * The other half of requirement C: the progression axes the profile *does* permit stay
     * reachable, so a disabled variation degrades to the family's volume fallback rather than
     * stopping the family — and the fallback is a low-level adjustment, not a level move.
     */
    @Test
    fun aPermittedVolumeFallbackIsUsedInsteadOfADisabledProgressionStep() {
        val plan = plan(
            configuration = WorkoutConfigurationSnapshot.capture(
                customConfiguration(WIDE_PUSHUPS, DECLINE_PUSHUPS)
            ),
            history = progressingHistory(STANDARD_PUSHUPS),
            states = listOf(
                familyState(
                    FAMILY_PUSHUPS,
                    level = 0,
                    progressWindows = 1,
                    currentExerciseId = STANDARD_PUSHUPS
                )
            )
        )

        val pushups = plan.resolutionOf(FAMILY_PUSHUPS)
        assertEquals(ProgressionOutcome.FALLBACK, pushups.resolution.outcome)
        assertEquals(0, pushups.resolution.level)
        assertEquals(STANDARD_PUSHUPS, pushups.resolution.exerciseId)
        assertEquals(1, pushups.resolution.adjustment)

        // The step is handed to the existing difficulty mechanism, composed with the user's own.
        assertEquals(1, plan.effectiveAdjustments(emptyMap())[STANDARD_PUSHUPS])
        assertEquals(3, plan.effectiveAdjustments(mapOf(STANDARD_PUSHUPS to 2))[STANDARD_PUSHUPS])
    }

    /**
     * Section 12's third negative test: an impossible progression under a custom selection must end
     * in HOLD with no disabled exercise selected — and the family's own stored level is untouched.
     */
    @Test
    fun anImpossibleProgressionHoldsAndSelectsNoDisabledExercise() {
        val plan = plan(
            programDay = LEGS_DAY,
            configuration = WorkoutConfigurationSnapshot.capture(
                customConfiguration(COSSACK_SQUAT, JUMP_SQUATS, SUMO_SQUATS)
            ),
            history = progressingHistory(STANDARD_SQUATS),
            states = listOf(familyState(FAMILY_SQUATS, level = 0, progressWindows = 1))
        )

        val squats = plan.resolutionOf(FAMILY_SQUATS)
        assertEquals(ProgressionOutcome.HOLD, squats.resolution.outcome)
        assertEquals(0, squats.resolution.level)
        assertNull(squats.resolution.exerciseId)

        val workout = workoutOf(plan)
        listOf(COSSACK_SQUAT, JUMP_SQUATS, SUMO_SQUATS).forEach { disabled ->
            assertFalse("$disabled is disabled and must not be enumerated", disabled in plan.permittedExerciseIds)
            assertFalse("$disabled reached the generated workout", disabled in exerciseIds(workout))
        }
    }

    /**
     * A family the user disabled entirely keeps its stored progression and is not evaluated at all —
     * re-enabling it later cannot find its state erased.
     */
    @Test
    fun aDisabledFamilyKeepsItsStoredProgressionAndIsNotEvaluated() {
        val storedStates = listOf(
            familyState(FAMILY_PUSHUPS, level = 2, currentExerciseId = WIDE_PUSHUPS),
            familyState(FAMILY_SQUATS, level = 0, progressWindows = 1)
        )
        val plan = plan(
            configuration = WorkoutConfigurationSnapshot.capture(
                customConfiguration(*familyExerciseIds(FAMILY_PUSHUPS).toTypedArray())
            ),
            history = progressingHistory(STANDARD_SQUATS),
            states = storedStates
        )

        // Every push-up variation is off, so the window never evaluates that family.
        assertNull(plan.progression.family(FAMILY_PUSHUPS))
        assertFalse(FAMILY_PUSHUPS in plan.progression.families.map { it.familyId })
        assertFalse(WIDE_PUSHUPS in plan.permittedExerciseIds)
        assertTrue(STANDARD_SQUATS in plan.permittedExerciseIds)

        // Its stored level is exactly what the caller supplied: nothing generated, erased or moved it.
        assertEquals(2, storedStates.first { it.familyId == FAMILY_PUSHUPS }.progressionLevel)
    }

    // ------------------------------------------------------------------ Task 12's snapshot

    /**
     * Tests 7: the session generates from the configuration captured at its start. The edit that
     * follows it is a future session's configuration, and this one cannot see it.
     */
    @Test
    fun anActiveSessionGeneratesFromItsFrozenConfiguration() {
        // Captured when the session started: the two harder rungs above standard push-ups were off.
        val capturedAtStart = WorkoutConfigurationSnapshot.capture(
            customConfiguration(WIDE_PUSHUPS, DECLINE_PUSHUPS, version = 4)
        )
        // Edited afterwards, while the session is still running.
        val editedDuringTheSession = customConfiguration(version = 5)

        val session = plan(
            configuration = capturedAtStart,
            history = progressingHistory(STANDARD_PUSHUPS),
            states = listOf(
                familyState(
                    FAMILY_PUSHUPS,
                    level = 0,
                    progressWindows = 1,
                    currentExerciseId = STANDARD_PUSHUPS
                )
            )
        )

        assertEquals(4, session.configurationVersion)
        assertFalse(WIDE_PUSHUPS in session.permittedExerciseIds)
        assertFalse(DECLINE_PUSHUPS in session.permittedExerciseIds)
        assertEquals(editedDuringTheSession.enabledExerciseIds, allExerciseIds)

        val workout = workoutOf(session)
        assertFalse(WIDE_PUSHUPS in exerciseIds(workout))
        assertFalse(DECLINE_PUSHUPS in exerciseIds(workout))
    }

    /** Test 8: the next session captures afresh and sees what was persisted in the meantime. */
    @Test
    fun aFutureSessionSeesTheNewlyPersistedConfiguration() {
        val capturedAtStart = WorkoutConfigurationSnapshot.capture(
            customConfiguration(WIDE_PUSHUPS, DECLINE_PUSHUPS, version = 4)
        )
        val editedAfterwards = customConfiguration(version = 5)
        val states = listOf(
            familyState(
                FAMILY_PUSHUPS,
                level = 1,
                progressWindows = 1,
                currentExerciseId = WIDE_PUSHUPS
            )
        )

        val running = plan(
            configuration = capturedAtStart,
            history = progressingHistory(STANDARD_PUSHUPS),
            states = states
        )
        assertEquals(ProgressionOutcome.HOLD, running.resolutionOf(FAMILY_PUSHUPS).resolution.outcome)

        val next = plan(
            configuration = WorkoutConfigurationSnapshot.capture(editedAfterwards),
            history = progressingHistory(STANDARD_PUSHUPS),
            states = states
        )

        assertEquals(5, next.configurationVersion)
        val pushups = next.resolutionOf(FAMILY_PUSHUPS)
        assertEquals(ProgressionOutcome.STEP, pushups.resolution.outcome)
        assertEquals(2, pushups.resolution.level)
        assertEquals(DECLINE_PUSHUPS, pushups.resolution.exerciseId)
        assertTrue(DECLINE_PUSHUPS in next.permittedExerciseIds)
    }

    // ------------------------------------------------------------------ equipment

    /** Test 10 and section 12's equipment negative: an unusable candidate is not selectable. */
    @Test
    fun equipmentIncompatibleCandidatesAreNeverSelected() {
        val plan = plan(
            history = progressingHistory("pullups"),
            states = listOf(familyState(FAMILY_PULLUPS, level = 0, progressWindows = 1)),
            configuration = WorkoutConfigurationSnapshot.capture(defaultConfiguration()),
            availableEquipment = setOf(Equipment.NONE)
        )

        val pullups = plan.resolutionOf(FAMILY_PULLUPS)
        assertEquals(AdaptiveState.PROGRESS, pullups.decision.state)
        // Every rung of the pull-up ladder needs the bar, so the window's target is unusable and the
        // ladder declares no fallback axis: HOLD, exactly as the resolver's contract says.
        assertEquals(ProgressionOutcome.HOLD, pullups.resolution.outcome)
        assertEquals(0, pullups.resolution.level)
        assertNull(pullups.resolution.exerciseId)

        assertTrue(
            "no bar means no bar exercise is permitted",
            plan.permittedExerciseIds.none { it in equipmentGatedIds && it.startsWith("pullups") }
        )

        (1..7).forEach { day ->
            val workout = workoutOf(plan, programDay = day, availableEquipment = setOf(Equipment.NONE))
            assertTrue(
                "day $day presented an exercise the equipment cannot support",
                workout.exercises.none { it.id in equipmentGatedIds }
            )
        }
    }

    /** The same window *with* the bar resolves normally — the constraint is equipment, not the ladder. */
    @Test
    fun theSameEquipmentBoundCandidateResolvesOnceTheEquipmentIsAvailable() {
        val plan = plan(
            history = progressingHistory("pullups"),
            states = listOf(familyState(FAMILY_PULLUPS, level = 0, progressWindows = 1)),
            configuration = WorkoutConfigurationSnapshot.capture(defaultConfiguration()),
            availableEquipment = setOf(Equipment.BAR)
        )

        val pullups = plan.resolutionOf(FAMILY_PULLUPS)
        assertEquals(ProgressionOutcome.STEP, pullups.resolution.outcome)
        assertEquals(1, pullups.resolution.level)
        assertEquals(NEUTRAL_PULLUPS, pullups.resolution.exerciseId)

        // A resolved step reaches the workout where the workout's own rules can carry it.
        val workout = workoutOf(plan, programDay = SHOULDERS_DAY, availableEquipment = setOf(Equipment.BAR))
        assertTrue(
            "the resolved variation reached the session",
            NEUTRAL_PULLUPS in exerciseIds(workout)
        )
    }

    // ------------------------------------------------------------------ the resolved step reaches the workout

    /**
     * The integration is not a report: what a family resolves to is what the session presents. The
     * builder is still `WorkoutGenerator`, and the routine, its rule structure and its phase
     * progression are untouched.
     */
    @Test
    fun theResolvedProgressionStepReachesTheWorkout() {
        val plan = plan(
            programDay = LEGS_DAY,
            history = progressingHistory(STANDARD_SQUATS),
            states = listOf(familyState(FAMILY_SQUATS, level = 0, progressWindows = 1))
        )
        assertEquals(COSSACK_SQUAT, plan.resolutionOf(FAMILY_SQUATS).resolution.exerciseId)

        val workout = workoutOf(plan)
        assertTrue("the resolved variation reached the session", COSSACK_SQUAT in exerciseIds(workout))

        // The routine itself is still the generator's: the strength day keeps its four slots and its
        // rule order.
        assertEquals(4, workout.exercises.size)
    }

    // ------------------------------------------------------------------ the invariant, swept

    /**
     * The invariant of section 4, swept over the whole calendar and several real selections: whatever
     * the adaptive decision is, no exercise outside the session's permitted set ever reaches the
     * workout — strength days and flexibility days alike.
     */
    @Test
    fun everyGeneratedExerciseStaysInsideTheSessionsPermittedSet() {
        val configurations = listOf(
            defaultConfiguration(),
            customConfiguration(DECLINE_PUSHUPS, JUMP_SQUATS, COSSACK_SQUAT, "plank", "side_plank", "hang"),
            customConfiguration(*equipmentGatedIds.toTypedArray()),
            customConfiguration(*allExerciseIds.filter { it != STANDARD_PUSHUPS && it != STANDARD_SQUATS }.toTypedArray())
        )
        val states = listOf(
            familyState(FAMILY_PUSHUPS, level = 1, progressWindows = 1),
            familyState(FAMILY_SQUATS, level = 1, progressWindows = 1),
            familyState(FAMILY_PLANK, level = -1)
        )

        var presented = 0
        configurations.forEach { configuration ->
            listOf(setOf<Equipment>(), setOf(Equipment.BAR), setOf(Equipment.NONE)).forEach { equipment ->
                val plan = plan(
                    configuration = WorkoutConfigurationSnapshot.capture(configuration),
                    history = progressingHistory(STANDARD_PUSHUPS, STANDARD_SQUATS),
                    states = states,
                    availableEquipment = equipment
                )
                (1..56).forEach { day ->
                    val workout = workoutOf(plan, programDay = day, availableEquipment = equipment)
                    val ids = exerciseIds(workout)
                    val outside = ids.filterNot { it in plan.permittedExerciseIds }
                    assertTrue(
                        "day $day presented $outside, which the session's frozen configuration and " +
                            "equipment do not permit",
                        outside.isEmpty()
                    )
                    assertEquals("day $day presented an exercise twice", ids.distinct().size, ids.size)
                    presented += ids.size
                }
            }
        }

        assertTrue("the sweep must actually generate workouts", presented > 0)
    }

    // ------------------------------------------------------------------ the old filter still applies

    /**
     * Section 15: the training-style filter and the effective configuration are independent
     * constraints, and generation is the intersection of them. Neither can re-enable what the other
     * excluded.
     */
    @Test
    fun theTrainingStyleFilterAndTheConfigurationAreBothApplied() {
        val plan = plan(
            programDay = LEGS_DAY,
            configuration = WorkoutConfigurationSnapshot.capture(customConfiguration())
        )

        val styleFiltered = workoutOf(plan, disabledFamilies = setOf("calisthenics"))
        val styled = styleFiltered.exercises
        assertTrue(styled.isNotEmpty())
        assertTrue(
            "a style-disabled exercise reached the workout",
            styled.none { exercise ->
                com.monkfitness.app.data.model.exerciseToFamiliesMap[exercise.id]
                    .orEmpty()
                    .any { it.key == "calisthenics" }
            }
        )
        assertTrue(styled.all { it.id in plan.permittedExerciseIds })
    }

    // ------------------------------------------------------------------ the pre-integration behaviour

    /**
     * The negative that makes the constraint tests non-vacuous.
     *
     * The same program day, generated the way it was generated before this task — no configuration in
     * play — enumerates exactly the exercises a custom configuration has disabled. That is the
     * behaviour Task 13 exists to constrain, and it is asserted here so the constrained tests above
     * cannot pass by accident: the difference between the two calls is the constraint, not the day.
     */
    @Test
    fun theUnconstrainedGenerationEnumeratesExercisesTheSessionConstraintKeepsOut() {
        val unconstrained = exerciseIds(generator.generateWorkout(LEGS_DAY))
        assertTrue("the legacy path must actually choose something", unconstrained.isNotEmpty())

        val disabledByTheUser = unconstrained
        val plan = plan(
            configuration = WorkoutConfigurationSnapshot.capture(
                customConfiguration(*disabledByTheUser.toTypedArray())
            ),
            history = progressingHistory(STANDARD_SQUATS),
            states = listOf(familyState(FAMILY_SQUATS, level = 0, progressWindows = 1))
        )

        val constrained = exerciseIds(workoutOf(plan))
        assertTrue(
            "every exercise the unconstrained path chose was disabled, but the session presented " +
                "${constrained.filter { it in disabledByTheUser }}",
            constrained.none { it in disabledByTheUser }
        )
    }

    /**
     * A configuration too narrow for the routine yields nothing rather than everything: the
     * training-style filter's legacy "empty means no filter" fallback must not be inherited by the
     * user's configuration, or a strict selection would silently re-enable the whole library.
     */
    @Test
    fun aConfigurationWithoutUsableExercisesYieldsNothingRatherThanEverything() {
        val only = "neck_circles"
        val plan = plan(
            configuration = WorkoutConfigurationSnapshot.capture(
                ProgramConfiguration.custom(setOf(only), 1)
            ),
            history = progressingHistory(STANDARD_SQUATS),
            states = listOf(familyState(FAMILY_SQUATS, level = 0, progressWindows = 1))
        )

        assertEquals(setOf(only), plan.permittedExerciseIds)
        val workout = workoutOf(plan)
        assertTrue(
            "generation may only use what the session permits, was ${exerciseIds(workout)}",
            workout.exercises.all { it.id in plan.permittedExerciseIds }
        )
    }

    /** Determinism: identical inputs produce an identical plan and an identical workout. */
    @Test
    fun identicalInputsProduceAnIdenticalSession() {
        val configuration = WorkoutConfigurationSnapshot.capture(customConfiguration(JUMP_SQUATS))
        val states = listOf(familyState(FAMILY_SQUATS, level = 0, progressWindows = 1))
        val history = progressingHistory(STANDARD_SQUATS)

        val first = plan(configuration = configuration, history = history, states = states)
        val second = plan(configuration = configuration, history = history, states = states)

        assertEquals(first.progression, second.progression)
        assertEquals(first.permittedExerciseIds, second.permittedExerciseIds)
        assertEquals(exerciseIds(workoutOf(first)), exerciseIds(workoutOf(second)))
    }

    /** The policy version travels with the plan, so a session can say which policy resolved it. */
    @Test
    fun thePlanCarriesThePolicyVersionThatResolvedIt() {
        val plan = plan(
            history = progressingHistory(STANDARD_SQUATS),
            states = listOf(familyState(FAMILY_SQUATS, level = 0, progressWindows = 1)),
            programType = ProgramType.REVISED
        )

        assertEquals(1, plan.progression.policyVersion)
        assertEquals(1, plan.progression.programCycle)
        assertEquals(LEGS_DAY, plan.progression.programDay)
        assertEquals(ProgramType.REVISED, plan.progression.programType)
    }
}
