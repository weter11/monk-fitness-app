package com.monkfitness.app.data.repository

import com.monkfitness.app.data.model.AdaptiveDecisionRecord
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.data.model.SetLog
import com.monkfitness.app.data.model.UserProgress
import com.monkfitness.app.domain.adaptive.AdaptiveAction
import com.monkfitness.app.domain.adaptive.AdaptiveReasonCode
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.SessionOutcome
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the finalized-session integration: which sessions persist an adaptive decision,
 * what is written, and what a repeated finalization does.
 *
 * The recorder is the one place where a workout becomes an adaptive decision, so the rules pinned here
 * are the ones a plausible-looking implementation could get wrong while the rest of the suite stayed
 * green:
 *
 *  * **Only a finalized session records anything.** A session that never started, one still in
 *    progress, and one abandoned without a day-level completion all leave the two adaptive tables
 *    untouched — a decision taken from a half-finished observation would move a family on evidence the
 *    user has not produced yet.
 *  * **The window is the finalized observation.** The decision is taken from the stored history — the
 *    day-level completion source plus the confirmed-set rows — and never from a generated preview, the
 *    live session state or the exercise's configured targets.
 *  * **A repeated finalization records nothing twice.** The same programme window cannot hold two
 *    records for one family, whichever way the completion path is invoked again.
 *  * **Both tables or neither.** The new current state and its immutable record land in one
 *    transaction, so an audit entry can never describe a transition whose state write was lost.
 *  * **The domain decides, the persistence layer records.** Every value written is the one the
 *    decision and its resolution produced; the persisted level is the ladder's own step and the stored
 *    adaptation state is the state the policy ordered.
 *  * **Recovery is not a level move.** Entering RECOVERY and leaving it to HOLD both preserve the
 *    accumulated progression level, and a HOLD never erases one.
 *
 * The DAOs are faked ([AdaptiveLifecycleRig]) because this project has no Robolectric, no
 * `androidx.test` and no in-memory-Room harness; the history rows are real, so Task 3's adapter, the
 * signal layer, the policy and the progression resolver all run exactly as production runs them.
 */
class AdaptiveSessionDecisionRecorderTest {

    // ---- a finalized session records its decision ------------------------------------------------

    @Test
    fun aFinalizedSessionPersistsTheDecidedStateAndItsRecord() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(
                UserProgress(
                    cycleNumber = 1,
                    day = 3,
                    isCompleted = true,
                    completionDate = rigCompletionMillis(3)
                )
            ),
            setLogs = completedSetLogsForDay(3)
        )
        rig.now = 1_800_000_500_000L

        val outcome = rig.recorderOverPersistedHistory().recordFinalizedSession(rig.request(programDay = 3))

        assertTrue("the session's observation is the finalized one", outcome.isFinalized)
        assertEquals(SessionOutcome.COMPLETED, outcome.observationOutcome)
        assertEquals("every family of the window is recorded", listOf("pushups"), outcome.recordedFamilies)
        assertEquals(emptyList<String>(), outcome.alreadyRecordedFamilies)

        val record = rig.historyDao.rows.single()
        assertEquals("pushups", record.familyId)
        assertEquals("the record carries the session's programme revision", 0, record.programRevision)
        assertEquals(1, record.cycleNumber)
        assertEquals(3, record.programDay)
        assertEquals("the record is stamped by the persistence adapter", 1_800_000_500_000L, record.timestamp)
        assertEquals(AdaptiveState.HOLD, record.previousState)
        assertEquals(AdaptiveState.HOLD, record.newState)
        assertEquals(listOf(AdaptiveAction.MAINTAIN_STIMULUS), record.actions)
        assertEquals(AdaptiveReasonCode.INSUFFICIENT_EVIDENCE, record.reasonCode)
        assertEquals(1, record.policyVersion)

        val state = rig.stateDao.rows.single()
        assertEquals("pushups", state.familyId)
        assertEquals(0, state.programRevision)
        assertEquals(AdaptiveState.HOLD, state.adaptationState)
        assertEquals("a family with no prior state is persisted at the ladder's baseline", 0, state.progressionLevel)
        assertEquals(1_800_000_500_000L, state.updatedAt)
        assertEquals(1, state.policyVersion)
    }

    @Test
    fun aSessionStillInProgressPersistsNoDecision() = runBlocking {
        // Confirmed sets, no day-level completion: the app's own way of representing work that has not
        // been finished (and, once abandoned, still no completion row).
        val rig = AdaptiveLifecycleRig(setLogs = completedSetLogsForDay(3))

        val outcome = rig.recorderOverPersistedHistory().recordFinalizedSession(rig.request(programDay = 3))

        assertTrue("a partial session is not finalized", !outcome.isFinalized)
        assertEquals(SessionOutcome.PARTIAL, outcome.observationOutcome)
        assertEquals(emptyList<String>(), outcome.recordedFamilies)
        assertEquals("no record is written for an unfinished session", emptyList<AdaptiveDecisionRecord>(), rig.historyDao.rows)
        assertEquals("no state is written for an unfinished session", emptyList<FamilyProgressionState>(), rig.stateDao.rows)
        assertEquals("nothing opened a transaction", 0, rig.transactions)
    }

    @Test
    fun aSessionWithNoObservationAtAllPersistsNoDecision() = runBlocking {
        val rig = AdaptiveLifecycleRig()

        val outcome = rig.recorderOverPersistedHistory().recordFinalizedSession(rig.request(programDay = 3))

        assertNull("persistence establishes no session there", outcome.observationOutcome)
        assertTrue(!outcome.isFinalized)
        assertEquals(emptyList<AdaptiveDecisionRecord>(), rig.historyDao.rows)
        assertEquals(emptyList<FamilyProgressionState>(), rig.stateDao.rows)
    }

    @Test
    fun aFinalizedSessionWhoseConfirmedSetsWereAllRolledBackStillRecordsItsDecision() = runBlocking {
        // Persistence keeps the day-level completion and the confirmed-set rows as separate facts, and the
        // app can hold a finished day with no set rows left (every set rolled back). That session was
        // finalized, so it records a decision like any other — and, since the whole history is read as one
        // list, one undatable finished session must not take the window's read down with it.
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(
                UserProgress(
                    cycleNumber = 1,
                    day = 3,
                    isCompleted = true,
                    completionDate = rigCompletionMillis(3)
                )
            )
        )

        val outcome = rig.recorderOverPersistedHistory().recordFinalizedSession(rig.request(programDay = 3))

        assertTrue("the completion is what finalizes the session", outcome.isFinalized)
        assertEquals(SessionOutcome.COMPLETED, outcome.observationOutcome)
        assertEquals(listOf("pushups"), outcome.recordedFamilies)
        assertEquals(1, rig.historyDao.rows.size)
    }

    @Test
    fun aCompletedRestDayPersistsNoDecision() = runBlocking {
        // Day 56 is the program's rest day: it prescribes nothing, so it is not training history and no
        // session exists to decide about — even though the day itself was marked complete.
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(
                UserProgress(
                    cycleNumber = 1,
                    day = 56,
                    isCompleted = true,
                    completionDate = rigCompletionMillis(56)
                )
            )
        )

        val outcome = rig.recorderOverPersistedHistory().recordFinalizedSession(rig.request(programDay = 56))

        assertNull(outcome.observationOutcome)
        assertTrue(!outcome.isFinalized)
        assertEquals(emptyList<AdaptiveDecisionRecord>(), rig.historyDao.rows)
        assertEquals(emptyList<FamilyProgressionState>(), rig.stateDao.rows)
    }

    // ---- idempotency ----------------------------------------------------------------------------

    @Test
    fun aRepeatedFinalizationOfTheSameSessionAddsNoSecondRecordAndRewritesNoState() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(
                UserProgress(
                    cycleNumber = 1,
                    day = 3,
                    isCompleted = true,
                    completionDate = rigCompletionMillis(3)
                )
            ),
            setLogs = completedSetLogsForDay(3)
        )
        val recorder = rig.recorderOverPersistedHistory()

        val first = recorder.recordFinalizedSession(rig.request(programDay = 3))
        rig.now += 60_000L // a second callback arrives a moment later
        val second = recorder.recordFinalizedSession(rig.request(programDay = 3))

        assertEquals(listOf("pushups"), first.recordedFamilies)
        assertEquals("the repeated finalization records nothing", emptyList<String>(), second.recordedFamilies)
        assertEquals(listOf("pushups"), second.alreadyRecordedFamilies)
        assertEquals("one session, one audit entry", 1, rig.historyDao.rows.size)
        assertEquals("one session, one current state", 1, rig.stateDao.rows.size)
        assertEquals(
            "the state was not rewritten either, so its stamp is still the first one",
            1_800_000_000_000L,
            rig.stateDao.rows.single().updatedAt
        )
    }

    @Test
    fun tenRepeatedFinalizationsStillProduceOneRecord() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(
                UserProgress(
                    cycleNumber = 1,
                    day = 3,
                    isCompleted = true,
                    completionDate = rigCompletionMillis(3)
                )
            ),
            setLogs = completedSetLogsForDay(3)
        )
        val recorder = rig.recorderOverPersistedHistory()

        repeat(10) { recorder.recordFinalizedSession(rig.request(programDay = 3)) }

        assertEquals("recomposition, navigation and re-entry cannot multiply the audit trail", 1, rig.historyDao.rows.size)
        assertEquals(1, rig.stateDao.rows.size)
    }

    @Test
    fun theSameWindowOfADifferentProgramRevisionIsItsOwnSession() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(
                UserProgress(
                    cycleNumber = 1,
                    day = 3,
                    isCompleted = true,
                    completionDate = rigCompletionMillis(3)
                )
            ),
            setLogs = completedSetLogsForDay(3),
            familyStates = listOf(persistedFamilyState(progressionLevel = 1, adaptationState = AdaptiveState.PROGRESS))
        )
        val recorder = rig.recorderOverPersistedHistory()

        val standard = recorder.recordFinalizedSession(rig.request(programDay = 3, programRevision = 0))
        val revised = recorder.recordFinalizedSession(rig.request(programDay = 3, programRevision = 1))

        assertEquals(listOf("pushups"), standard.recordedFamilies)
        assertEquals(
            "a revised program's identical window is a different session, not a duplicate",
            listOf("pushups"),
            revised.recordedFamilies
        )
        assertEquals(2, rig.historyDao.rows.size)
        assertEquals(listOf(0, 1), rig.historyDao.rows.map { it.programRevision }.sorted())
        assertEquals(
            "the revision that carried a level keeps it",
            1,
            rig.stateDao.getFamilyState(0, "pushups")!!.progressionLevel
        )
        assertEquals(
            "the new revision starts from the ladder's baseline, not from the old revision's state",
            0,
            rig.stateDao.getFamilyState(1, "pushups")!!.progressionLevel
        )
    }

    // ---- atomicity ------------------------------------------------------------------------------

    @Test
    fun theStateAndItsRecordLandTogetherOrNotAtAll() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(
                UserProgress(
                    cycleNumber = 1,
                    day = 3,
                    isCompleted = true,
                    completionDate = rigCompletionMillis(3)
                )
            ),
            setLogs = completedSetLogsForDay(3)
        )
        rig.historyDao.failure = "adaptive_decision_record: disk full"

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                rig.recorderOverPersistedHistory(inTransaction = rig.rollingBack())
                    .recordFinalizedSession(rig.request(programDay = 3))
            }
        }

        assertEquals("the failure is not suppressed", "adaptive_decision_record: disk full", thrown.message)
        assertEquals("the state write was rolled back with the record", emptyList<FamilyProgressionState>(), rig.stateDao.rows)
        assertEquals(emptyList<AdaptiveDecisionRecord>(), rig.historyDao.rows)
    }

    @Test
    fun aFailedHistoryReadIsNotRecordedAsADecision() = runBlocking {
        // A read that fails yields NO evidence. Recording that as a decision would persist a state the
        // user's own history never justified, so the failure is reported instead of degraded.
        val rig = AdaptiveLifecycleRig()
        val recorder = AdaptiveSessionDecisionRecorder(
            repository = rig.repository(),
            readHistory = { throw IllegalStateException("set_log: disk full") },
            generator = rig.generator,
            now = { rig.now }
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { recorder.recordFinalizedSession(rig.request(programDay = 3)) }
        }

        assertEquals("set_log: disk full", thrown.message)
        assertEquals(emptyList<FamilyProgressionState>(), rig.stateDao.rows)
        assertEquals(emptyList<AdaptiveDecisionRecord>(), rig.historyDao.rows)
    }

    @Test
    fun oneSessionIsPersistedInOneTransaction() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(
                UserProgress(
                    cycleNumber = 1,
                    day = 3,
                    isCompleted = true,
                    completionDate = rigCompletionMillis(3)
                )
            ),
            setLogs = completedSetLogsForDay(3)
        )

        rig.recorderOverPersistedHistory().recordFinalizedSession(
            rig.request(programDay = 3, enabledExerciseIds = setOf("pushups", AdaptiveLifecycleRig.PLANK_EXERCISE_ID))
        )

        assertEquals("both families of the window", listOf("plank", "pushups"), rig.historyDao.rows.map { it.familyId })
        assertEquals("one finalized session, one transaction", 1, rig.transactions)
        assertEquals(2, rig.stateDao.rows.size)
    }

    // ---- the domain decides; the levels it resolves are what lands -------------------------------

    @Test
    fun aConfirmedProgressWindowMovesTheFamilyOntoItsNextStep() = runBlocking {
        // The confirmation counter one window short of the policy's two: this window is the second
        // qualifying one, so the decision is PROGRESS and the family's ladder resolves one level up.
        val rig = AdaptiveLifecycleRig(familyStates = listOf(persistedFamilyState(precedingProgressQualifyingWindows = 1)))
        val recorder = rig.recorder(rig.fullExposureSessions())

        val outcome = recorder.recordFinalizedSession(
            rig.request(programDay = 6, enabledExerciseIds = setOf("pushups", "pushups_wide"))
        )

        assertEquals(listOf("pushups"), outcome.recordedFamilies)

        val record = rig.historyDao.rows.single()
        assertEquals(AdaptiveState.PROGRESS, record.newState)
        assertEquals(AdaptiveState.HOLD, record.previousState)
        assertEquals(listOf(AdaptiveAction.INCREASE_STIMULUS), record.actions)
        assertEquals(AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE, record.reasonCode)

        val state = rig.stateDao.rows.single()
        assertEquals("the resolver's own step for the next level", 1, state.progressionLevel)
        assertEquals("pushups_wide", state.currentExerciseId)
        assertEquals(AdaptiveState.PROGRESS, state.adaptationState)
    }

    @Test
    fun aDisabledTargetHoldsTheFamilyInsteadOfBypassingTheConfiguration() = runBlocking {
        // The same confirmed window, but the user's configuration enables only the variation the family
        // already sits on: the step the decision ordered is not permitted, so the resolver's declared
        // fallback applies and the LEVEL DOES NOT MOVE — the persistence layer may not reach past the
        // user's selection to find a target.
        val rig = AdaptiveLifecycleRig(
            familyStates = listOf(
                persistedFamilyState(
                    progressionLevel = 0,
                    currentExerciseId = "pushups",
                    precedingProgressQualifyingWindows = 1
                )
            )
        )

        val outcome = rig.recorder(rig.fullExposureSessions())
            .recordFinalizedSession(rig.request(programDay = 6, enabledExerciseIds = setOf("pushups")))

        assertEquals(listOf("pushups"), outcome.recordedFamilies)
        assertEquals(
            "the decision is still the domain's own",
            AdaptiveState.PROGRESS,
            rig.historyDao.rows.single().newState
        )

        val state = rig.stateDao.rows.single()
        assertEquals("a fallback never moves the level", 0, state.progressionLevel)
        assertEquals("pushups", state.currentExerciseId)
    }

    @Test
    fun aFamilyOutsideTheSessionsConfigurationIsNeitherDecidedNorStored() = runBlocking {
        val rig = AdaptiveLifecycleRig(familyStates = listOf(persistedFamilyState(familyId = "squats", progressionLevel = 1)))

        rig.recorder(rig.fullExposureSessions())
            .recordFinalizedSession(rig.request(programDay = 6, enabledExerciseIds = setOf("pushups")))

        assertEquals(listOf("pushups"), rig.historyDao.rows.map { it.familyId })
        assertEquals(
            "the disabled family's state row is left exactly as it was",
            persistedFamilyState(familyId = "squats", progressionLevel = 1),
            rig.repository().familyState(0, "squats")
        )
        assertEquals(
            "and no second row is created for it by a window that never evaluated it",
            1,
            rig.stateDao.rows.count { it.familyId == "squats" }
        )
    }

    // ---- recovery and HOLD ----------------------------------------------------------------------

    @Test
    fun enteringRecoveryPreservesTheAccumulatedProgressionLevel() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            familyStates = listOf(
                persistedFamilyState(
                    progressionLevel = 1,
                    currentExerciseId = "pushups_wide",
                    adaptationState = AdaptiveState.HOLD
                )
            )
        )

        rig.recorder(rig.highLoadDeteriorationSessions()).recordFinalizedSession(rig.request(programDay = 14))

        val record = rig.historyDao.rows.single()
        assertEquals(AdaptiveState.RECOVERY, record.newState)
        assertEquals(listOf(AdaptiveAction.RECOVERY_LOAD), record.actions)
        assertEquals(AdaptiveReasonCode.HIGH_LOAD_DETERIORATION, record.reasonCode)

        val state = rig.stateDao.rows.single()
        assertEquals("recovery is not a level move", 1, state.progressionLevel)
        assertEquals(AdaptiveState.RECOVERY, state.adaptationState)
        assertEquals("the family keeps the variation it earned", "pushups_wide", state.currentExerciseId)
    }

    @Test
    fun leavingRecoveryBecomesHoldAndKeepsTheLevel() = runBlocking {
        // The documented recovery exit: two qualifying sessions accumulated while in RECOVERY, and a
        // window that is not high risk. The policy leaves to HOLD — never straight to PROGRESS — and the
        // accumulated level is untouched.
        val rig = AdaptiveLifecycleRig(
            familyStates = listOf(
                persistedFamilyState(
                    progressionLevel = 1,
                    currentExerciseId = "pushups_wide",
                    adaptationState = AdaptiveState.RECOVERY,
                    recoveryQualifyingSessions = 2
                )
            )
        )

        rig.recorder(rig.fullExposureSessions()).recordFinalizedSession(rig.request(programDay = 6))

        val record = rig.historyDao.rows.single()
        assertEquals(AdaptiveState.HOLD, record.newState)
        assertEquals("the state it came from is part of the audit entry", AdaptiveState.RECOVERY, record.previousState)
        assertEquals(AdaptiveReasonCode.RECOVERY, record.reasonCode)

        val state = rig.stateDao.rows.single()
        assertEquals("recovery exit leaves the level where it was", 1, state.progressionLevel)
        assertEquals(AdaptiveState.HOLD, state.adaptationState)
    }

    @Test
    fun aHoldDoesNotEraseTheAccumulatedLevel() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            familyStates = listOf(persistedFamilyState(progressionLevel = 2, currentExerciseId = "decline_pushups"))
        )

        // One single session: far too little evidence for any transition.
        rig.recorder(listOf(rig.repSession(programDay = 1)))
            .recordFinalizedSession(rig.request(programDay = 1))

        val state = rig.stateDao.rows.single()
        assertEquals(AdaptiveState.HOLD, state.adaptationState)
        assertEquals("HOLD is the normal outcome and never a reset", 2, state.progressionLevel)
        assertEquals("decline_pushups", state.currentExerciseId)
        assertEquals(
            AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
            rig.historyDao.rows.single().reasonCode
        )
    }

    @Test
    fun theStoredStateCarriesTheHysteresisCountersItWasGiven() = runBlocking {
        // The domain's own contract: the engine READS the confirmation counts, the recovery session
        // count and the cooldown position from the caller and never updates them, because only "the
        // layer that stores and updates them" knows them. This layer stores what it is handed, so no
        // count is reset, invented or advanced here — advancing them requires a qualification outcome
        // the policy does not publish (see the PR's open items).
        val prior = persistedFamilyState(
            progressionLevel = 1,
            currentExerciseId = "pushups_wide",
            adaptationState = AdaptiveState.HOLD,
            precedingProgressQualifyingWindows = 2,
            precedingRegressQualifyingWindows = 3,
            precedingHighRiskWindows = 4,
            recoveryQualifyingSessions = 5,
            eligibleSessionsSinceLastProgressionChange = 7
        )
        val rig = AdaptiveLifecycleRig(familyStates = listOf(prior))

        rig.recorder(listOf(rig.repSession(programDay = 1)))
            .recordFinalizedSession(rig.request(programDay = 1))

        val state = rig.stateDao.rows.single()
        assertEquals(2, state.precedingProgressQualifyingWindows)
        assertEquals(3, state.precedingRegressQualifyingWindows)
        assertEquals(4, state.precedingHighRiskWindows)
        assertEquals(5, state.recoveryQualifyingSessions)
        assertEquals(7, state.eligibleSessionsSinceLastProgressionChange)
    }

    @Test
    fun theLevelThatLandsIsTheOneTheDomainResolvedFromTheStateItWasGiven() = runBlocking {
        // Two families, the same confirmed window and the same permitted set. What differs is the state
        // each was stored at: one has a window of confirmation behind it and moves a level, the other
        // already sits at the top of its ladder and therefore HOLDS. Neither outcome is computed here —
        // the level in the state row is a consequence of the domain's own decision and resolution.
        val permitted = setOf("pushups", "pushups_wide", "decline_pushups")

        val moving = AdaptiveLifecycleRig(
            familyStates = listOf(persistedFamilyState(progressionLevel = 1, precedingProgressQualifyingWindows = 1))
        )
        moving.recorder(moving.fullExposureSessions())
            .recordFinalizedSession(moving.request(programDay = 6, enabledExerciseIds = permitted))

        val atTheTop = AdaptiveLifecycleRig(
            familyStates = listOf(
                persistedFamilyState(
                    progressionLevel = 2,
                    currentExerciseId = "decline_pushups",
                    precedingProgressQualifyingWindows = 1
                )
            )
        )
        atTheTop.recorder(atTheTop.fullExposureSessions())
            .recordFinalizedSession(atTheTop.request(programDay = 6, enabledExerciseIds = permitted))

        assertEquals(AdaptiveState.PROGRESS, moving.historyDao.rows.single().newState)
        assertEquals(
            "one level up, onto the ladder's own next step",
            2,
            moving.stateDao.rows.single().progressionLevel
        )
        assertEquals("decline_pushups", moving.stateDao.rows.single().currentExerciseId)

        // At the top of the ladder the policy still confirms PROGRESS — it judges the evidence, not the
        // ladder — while the family's own resolution is a HOLD: there is no level above the boundary, and
        // the resolver documents that as a HOLD rather than as a clamped level. The audit trail therefore
        // records the decision, and the family keeps the level and the variation it already had.
        assertEquals(
            AdaptiveState.PROGRESS,
            atTheTop.historyDao.rows.single().newState
        )
        assertEquals(
            "there is no level above the top, so the state does not move",
            2,
            atTheTop.stateDao.rows.single().progressionLevel
        )
        assertEquals(
            "and a HOLD leaves the family on the variation it was on",
            "decline_pushups",
            atTheTop.stateDao.rows.single().currentExerciseId
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * The confirmed sets a real session of that program day leaves behind: one row per prescribed set of
     * the day's own generated workout, so Task 3's adapter counts them as that plan's observed work.
     */
    private fun completedSetLogsForDay(programDay: Int): List<SetLog> {
        val exercise = WorkoutGenerator().generateWorkout(programDay).exercises.first()
        val sessionDate = AdaptiveLifecycleRig.PROGRAM_START
            .plusDays((programDay - 1).toLong())
            .toString()

        return (1..exercise.sets).map { set ->
            SetLog(
                exerciseId = exercise.id,
                repsCompleted = if (exercise.isTimerBased) 0 else exercise.reps,
                durationSeconds = if (exercise.isTimerBased) exercise.durationSeconds else 0,
                timestamp = 1_800_000_000_000L + set,
                sessionDate = sessionDate
            )
        }
    }

    private fun rigCompletionMillis(programDay: Int): Long = AdaptiveLifecycleRig.PROGRAM_START
        .plusDays((programDay - 1).toLong())
        .toEpochDay() * AdaptiveLifecycleRig.MILLIS_PER_DAY
}
