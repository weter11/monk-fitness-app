package com.monkfitness.app.data.repository

import com.monkfitness.app.data.model.AdaptiveDecisionRecord
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.AdaptivePolicy
import com.monkfitness.app.domain.adaptive.AdaptiveReasonCode
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ProgressionOutcome
import com.monkfitness.app.domain.adaptive.SessionObservation
import com.monkfitness.app.domain.adaptive.WorkoutConfigurationSnapshot
import com.monkfitness.app.domain.usecase.AdaptiveSessionRequest
import com.monkfitness.app.domain.usecase.AdaptiveWorkoutGenerationRequest
import com.monkfitness.app.domain.usecase.AdaptiveWorkoutIntegration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 15: the state machine under adversarial sequences, driven through the whole pipeline.
 *
 * The policy's own suite asserts every transition against hand-set
 * [com.monkfitness.app.domain.adaptive.AdaptiveEvidence], and the engine's suite asserts the same against
 * hand-built windows. Both can be right while the path the app actually runs is wrong, because the app
 * never hands the policy evidence: it hands it a stored history, which the real
 * [com.monkfitness.app.domain.adaptive.AdaptiveSignalCalculator] turns into exposure, trend, adherence and
 * load. This suite closes that gap — every case below runs
 * `stored history → signals → policy → ladder → persisted state and record` through the production
 * components, and asks whether a shortcut in the wiring could turn a conservative window into a move.
 *
 * Only the push-up family is enabled in these fixtures, so one window writes exactly one family's state
 * and record and the assertions can name what they read.
 *
 * What it is guarding, in the order it appears:
 *
 *  * a single poor session of any shape is not a regression, and a single good window is not a
 *    progression — the confirmation rule, not the fixture's strength, is what holds the family;
 *  * a HIGH recent load blocks ordinary progression even when every other progress condition is
 *    satisfied, and the same load with deterioration enters RECOVERY instead of a confirmed REGRESS;
 *  * whatever a window decides, the program day and cycle it decided are the ones the session was
 *    started on: no decision moves the calendar, and no decision writes or deletes a calendar row.
 */
class AdaptiveStateMachineInvariantTest {

    private val policy = AdaptivePolicy.V1

    /** One decided window: the family's persisted state and the immutable record of the decision. */
    private class Decision(val state: FamilyProgressionState, val record: AdaptiveDecisionRecord) {
        val level: Int get() = state.progressionLevel
        val exerciseId: String? get() = state.currentExerciseId
        val adaptationState: AdaptiveState get() = state.adaptationState
        val reason: AdaptiveReasonCode get() = record.reasonCode
    }

    /** Level `1` is `pushups_wide`, so a HOLD that erased the ladder would be visible in the assertions. */
    private fun seeded(
        progressWindows: Int = 0,
        regressWindows: Int = 0
    ) = persistedFamilyState(
        familyId = "pushups",
        progressionLevel = 1,
        currentExerciseId = "pushups_wide",
        precedingProgressQualifyingWindows = progressWindows,
        precedingRegressQualifyingWindows = regressWindows
    )

    // ---- one window is never enough ---------------------------------------------------------------

    @Test
    fun onePoorSessionOfEveryShapeHoldsAndKeepsTheLadder() = runBlocking {
        val cases = listOf(
            // One session, barely performed: below the eligibility floor, whatever else it says.
            "one poor completion" to poorCompletion(),
            // One declining window: the regress conditions are met, but the eligible-session floor is not.
            "one negative trend" to negativeTrendWindow(),
            // One strongly performed window with no qualifying window before it.
            "one good window" to goodWindow()
        )

        for ((name, fixture) in cases) {
            val decision = decide(fixture)

            assertEquals("$name: a single window must not move the family", AdaptiveState.HOLD, decision.adaptationState)
            assertEquals("$name: the accumulated level survives the window", 1, decision.level)
            assertEquals("$name: and so does the variation it is on", "pushups_wide", decision.exerciseId)
            assertEquals("$name: held for want of evidence", AdaptiveReasonCode.INSUFFICIENT_EVIDENCE, decision.reason)
            assertTrue(
                "$name: nothing in the trail claims a move",
                fixture.rig.historyDao.rows.none {
                    it.newState == AdaptiveState.PROGRESS || it.newState == AdaptiveState.REGRESS
                }
            )
        }
    }

    @Test
    fun theSameGoodWindowProgressesOnlyOnceTheConfirmationWindowIsSatisfied() = runBlocking {
        // The same evidence twice: with no qualifying window before it and with one. The only difference
        // between the two runs is the confirmation count, so the HOLD cannot be an artefact of a weak
        // fixture — and the PROGRESS cannot be an artefact of a strong one.
        val unconfirmed = decide(fullWindow(seeded(progressWindows = 0)))
        assertEquals(AdaptiveState.HOLD, unconfirmed.adaptationState)
        assertEquals(1, unconfirmed.level)
        assertEquals("pushups_wide", unconfirmed.exerciseId)

        val confirmed = decide(fullWindow(seeded(progressWindows = policy.progressConfirmingWindows - 1)))
        assertEquals(AdaptiveState.PROGRESS, confirmed.adaptationState)
        assertEquals(AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE, confirmed.reason)
        assertEquals("the confirmed window is what moves the family", 2, confirmed.level)
        assertEquals("decline_pushups", confirmed.exerciseId)
    }

    // ---- recovery safety is not an ordinary progression -------------------------------------------

    @Test
    fun aHighLoadWindowNeverProgressesAndItsDeteriorationRecoversInsteadOfRegressing() = runBlocking {
        // (a) Every progress condition but the load one is satisfied: a fully performed window whose
        // performed sets doubled against the week before. The load bucket is HIGH, so the family stays
        // where it is — the safety rule wins over an otherwise confirmed progression.
        val loaded = highLoadFullyPerformed(seeded(progressWindows = policy.progressConfirmingWindows - 1))
        val held = decide(loaded)
        assertEquals("a HIGH load is not a progression", AdaptiveState.HOLD, held.adaptationState)
        assertEquals(AdaptiveReasonCode.INSUFFICIENT_EVIDENCE, held.reason)
        assertEquals(1, held.level)
        assertEquals("pushups_wide", held.exerciseId)

        // The same window as a session's generation sees it: the family's ladder is asked for no step.
        val sessionPlan = plan(loaded)
        assertEquals(ProgressionOutcome.HOLD, sessionPlan.progression.family("pushups")!!.resolution.outcome)
        assertTrue("a held family prefers nothing", sessionPlan.resolvedExerciseIds.isEmpty())

        // (b) The same load with a completion that falls every day: this window satisfies the confirmed
        // REGRESS rule and the RECOVERY entry rule at once, because it is both. RECOVERY is the state
        // that lands — one decision, one state, and the earned level untouched.
        val deteriorating = highLoadDeclining(seeded(regressWindows = policy.regressConfirmingWindows - 1))
        val recovered = decide(deteriorating)
        assertEquals(AdaptiveState.RECOVERY, recovered.adaptationState)
        assertEquals(AdaptiveReasonCode.HIGH_LOAD_DETERIORATION, recovered.reason)
        assertEquals("recovery does not destroy what the family earned", 1, recovered.level)
        assertEquals("pushups_wide", recovered.exerciseId)
        assertTrue(
            "the confirmed regression was consumed by the safety path, not recorded beside it",
            deteriorating.rig.historyDao.rows.none { it.newState == AdaptiveState.REGRESS }
        )
    }

    // ---- the calendar is not the adaptive layer's to move -----------------------------------------

    @Test
    fun everyDecidedWindowKeepsTheProgramDayAndCycleItWasStartedOn() = runBlocking {
        val integration = AdaptiveWorkoutIntegration()

        for ((cycle, day) in listOf(1 to 6, 1 to 14, 1 to 28, 1 to 56, 3 to 1, 9 to 56)) {
            val scenarios = listOf(
                "sustaining" to sustainingWindow(cycle, day),
                "deteriorating" to deterioratingWindow(cycle, day)
            )
            for ((name, fixture) in scenarios) {
                val rig = fixture.rig
                val label = "$name cycle=$cycle day=$day"
                val calendarBefore = rig.progressDao.userProgress.toList()
                val dayStatesBefore = rig.progressDao.programDayStates.toList()

                val sessionPlan = integration.planSession(
                    AdaptiveSessionRequest(
                        programDay = day,
                        programCycle = cycle,
                        configuration = WorkoutConfigurationSnapshot(1, pushupFamilyIds(rig)),
                        recentSessions = fixture.history,
                        familyStates = rig.repository().familyStates(0)
                    )
                )
                assertEquals("$label: the decided window is the requested one", day, sessionPlan.progression.programDay)
                assertEquals("$label: the cycle is the supplied one", cycle, sessionPlan.progression.programCycle)

                val workout = integration.generateWorkout(AdaptiveWorkoutGenerationRequest(day), sessionPlan)
                assertEquals("$label: the generated day is the requested day", day, workout.id)
                assertTrue(
                    "$label: the requested position is the one that was decided",
                    sessionPlan.progression.families.isNotEmpty() &&
                        sessionPlan.progression.programType ==
                        com.monkfitness.app.domain.adaptive.ProgramType.STANDARD
                )
                // The decision of every evaluated family is the position's own window, and the
                // record of the session is filed there too — the day and cycle never follow a decision.
                assertEquals(
                    "$label: the window is recorded on the requested position",
                    sessionPlan.progression.families.size,
                    sessionPlan.progression.families.count { it.decision.familyId != null }
                )

                val decision = record(fixture)
                assertEquals(label, cycle, decision.record.cycleNumber)
                assertEquals(label, day, decision.record.programDay)
                assertEquals("$label: the revision the session ran as", 0, decision.record.programRevision)

                assertEquals("$label: no calendar row was written", calendarBefore, rig.progressDao.userProgress)
                assertEquals("$label: no day-state row was written", dayStatesBefore, rig.progressDao.programDayStates)
                assertEquals("$label: the adaptive path deleted nothing", emptyList<String>(), rig.progressDao.callLog)
            }
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** One fixture: the rig its decisions are stored in, and the window the recorder is handed. */
    private class Window(
        val rig: AdaptiveLifecycleRig,
        val history: List<SessionObservation>
    ) {
        /** The newest position of the window: the one the session being finalized was started on. */
        val anchorDay: Int get() = history.last().programDay
        val anchorCycle: Int get() = history.last().cycleNumber
    }

    /** One session performed to a tenth of its plan, with the regress rule otherwise confirmed. */
    private fun poorCompletion(): Window {
        val rig = AdaptiveLifecycleRig(familyStates = listOf(seeded(regressWindows = policy.regressConfirmingWindows - 1)))
        return Window(rig, listOf(rig.repSession(programDay = 1, completedRepsPerSet = 1)))
    }

    /** Five sessions whose performed fraction falls every day: the regress conditions, unconfirmed. */
    private fun negativeTrendWindow(): Window {
        val rig = AdaptiveLifecycleRig(familyStates = listOf(seeded(regressWindows = 0)))
        return Window(
            rig,
            (1..5).map { day ->
                rig.repSession(programDay = day, plannedRepsPerSet = 100, completedRepsPerSet = 90 - day * 10)
            }
        )
    }

    /** A fully performed six-session window with no qualifying window before it. */
    private fun goodWindow(): Window {
        val rig = AdaptiveLifecycleRig(familyStates = listOf(seeded(progressWindows = 0)))
        return Window(rig, rig.fullExposureSessions())
    }

    /** The same fully performed window on a rig whose family state the caller seeds. */
    private fun fullWindow(state: FamilyProgressionState): Window {
        val rig = AdaptiveLifecycleRig(familyStates = listOf(state))
        return Window(rig, rig.fullExposureSessions())
    }

    /** Seven days of one performed set, then seven days of two, all performed in full. */
    private fun highLoadFullyPerformed(state: FamilyProgressionState): Window {
        val rig = AdaptiveLifecycleRig(familyStates = listOf(state))
        return Window(
            rig,
            (1..14).map { day ->
                val sets = if (day <= 7) 1 else 2
                rig.repSession(
                    programDay = day,
                    plannedSets = sets,
                    plannedRepsPerSet = 20,
                    completedSets = sets,
                    completedRepsPerSet = 20
                )
            }
        )
    }

    /**
     * The adversarial recovery window: seven days of one performed set, then seven days of two whose
     * completion falls every day. It qualifies for RECOVERY (HIGH load with a declining trend) and for a
     * confirmed REGRESS at the same time, which is exactly the pair the safety rule has to arbitrate.
     */
    private fun highLoadDeclining(state: FamilyProgressionState): Window {
        val rig = AdaptiveLifecycleRig(familyStates = listOf(state))
        val completion = listOf(20, 16, 12, 8, 4, 2, 1)
        return Window(
            rig,
            (1..14).map { day ->
                val loaded = day > 7
                rig.repSession(
                    programDay = day,
                    plannedSets = if (loaded) 2 else 1,
                    plannedRepsPerSet = 20,
                    completedSets = if (loaded) 2 else 1,
                    completedRepsPerSet = if (loaded) completion[day - 8] else 20
                )
            }
        )
    }

    /** A sustaining window anchored on one calendar position, for the calendar matrix. */
    private fun sustainingWindow(cycle: Int, day: Int): Window {
        val rig = AdaptiveLifecycleRig(familyStates = listOf(seeded(progressWindows = policy.progressConfirmingWindows - 1)))
        return Window(rig, positionsEndingAt(cycle, day, 6).map { (c, d) -> rig.repSession(programDay = d, cycleNumber = c) })
    }

    /** A deteriorating window anchored on one calendar position, for the calendar matrix. */
    private fun deterioratingWindow(cycle: Int, day: Int): Window {
        val rig = AdaptiveLifecycleRig(familyStates = listOf(seeded(regressWindows = policy.regressConfirmingWindows - 1)))
        val positions = positionsEndingAt(cycle, day, 14)
        val history = positions.mapIndexed { index, (c, d) ->
            val loaded = index >= positions.size / 2
            rig.repSession(
                programDay = d,
                cycleNumber = c,
                plannedSets = if (loaded) 2 else 1,
                plannedRepsPerSet = 20,
                completedSets = if (loaded) 2 else 1,
                completedRepsPerSet = if (loaded) 3 else 20
            )
        }
        return Window(rig, history)
    }

    /**
     * The [count] program positions ending at `(cycle, day)`, oldest first — the calendar as the app's own
     * linear day index sees it, so a window that reaches back past day 1 continues in the previous cycle,
     * and a window anchored before the program had [count] days simply starts at its first one.
     */
    private fun positionsEndingAt(cycle: Int, day: Int, count: Int): List<Pair<Int, Int>> {
        val total = AdaptiveLifecycleRig.TOTAL_PROGRAM_DAYS
        val last = (cycle - 1) * total + (day - 1)
        val available = minOf(count, last + 1)
        return (available - 1 downTo 0).map { back ->
            val linear = last - back
            (linear / total) + 1 to (linear % total) + 1
        }
    }

    // ---- the pipeline -----------------------------------------------------------------------------

    /** Finalizes the fixture's window through the production recorder and reads what it stored. */
    private suspend fun decide(window: Window): Decision = record(window)

    private suspend fun record(window: Window): Decision {
        window.rig.recorder(window.history).recordFinalizedSession(
            window.rig.request(
                programDay = window.anchorDay,
                programCycle = window.anchorCycle,
                enabledExerciseIds = pushupFamilyIds(window.rig)
            )
        )
        return Decision(
            state = requireNotNull(window.rig.repository().familyState(0, "pushups")),
            record = window.rig.historyDao.rows.single()
        )
    }

    /** The same window as a session's generation reads it: the plan the ladder is resolved inside. */
    private suspend fun plan(window: Window) = AdaptiveWorkoutIntegration().planSession(
        AdaptiveSessionRequest(
            programDay = window.anchorDay,
            programCycle = window.anchorCycle,
            configuration = WorkoutConfigurationSnapshot(1, pushupFamilyIds(window.rig)),
            recentSessions = window.history,
            familyStates = window.rig.repository().familyStates(0)
        )
    )

    /**
     * The push-up family's own library ids, read from the app's exercise catalogue: the configuration
     * these fixtures run on, so one window writes exactly one family's state and record.
     */
    private fun pushupFamilyIds(rig: AdaptiveLifecycleRig): Set<String> =
        rig.generator.getExerciseLibrary().filter { it.familyId == "pushups" }.map { it.id }.toSet()
}
