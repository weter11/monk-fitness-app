package com.monkfitness.app.data.repository

import com.monkfitness.app.data.model.*
import com.monkfitness.app.domain.adaptive.*
import com.monkfitness.app.ui.customprogram.CustomProgramApplyResult
import com.monkfitness.app.ui.customprogram.CustomProgramEditorRig
import com.monkfitness.app.viewmodel.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real editor, validator, file-backed DataStore, session holder and recorder; only DAOs are fake. */
class AdaptiveConfigurationInvariantTest {
    @Test fun cancelAndBothHardRejectionsLeaveBytesVersionAdaptiveStateAndNextSessionUnchanged() = runBlocking {
        val editorRig = CustomProgramEditorRig()
        try {
            val repository = editorRig.repository
            repository.apply(editorRig.libraryIds - "decline_pushups")
            val before = repository.load()
            val digest = editorRig.storedDigest()
            val adaptive = seededAdaptive()
            val states = adaptive.stateDao.rows.toList()
            val records = adaptive.historyDao.rows.toList()
            editorRig.editor.open()
            editorRig.editor.toggleExercise("pushups")
            editorRig.editor.discardDraft()
            assertEquals(before, repository.load())
            assertEquals(digest, editorRig.storedDigest())
            // Two independent hard errors, each re-opening from the persisted authority.
            for (equipmentError in listOf(false, true)) {
                editorRig.equipment = emptySet()
                editorRig.editor.open()
                if (equipmentError) editorRig.equipment = setOf(Equipment.NONE)
                else editorRig.idsInDomain(TrainingDomain.FLEXIBILITY).forEach(editorRig.editor::toggleExercise)
                assertTrue(editorRig.editor.apply() is CustomProgramApplyResult.Rejected)
                assertEquals(before, repository.load())
                assertEquals(digest, editorRig.storedDigest())
            }
            val active = ActiveWorkoutConfiguration()
            val snapshot = active.beginSession(WorkoutSessionIdentity(3, false), {
                WorkoutSessionContext(1, 0, AdaptiveLifecycleRig.PROGRAM_START)
            }) { repository.load() }
            assertEquals(before.enabledExerciseIds, snapshot.enabledExerciseIds)
            assertEquals(before.configurationVersion, snapshot.configurationVersion)
            assertEquals(states, adaptive.stateDao.rows)
            assertEquals(records, adaptive.historyDao.rows)
            assertTrue(adaptive.stateDao.callLog.none { it.startsWith("clear") })
        } finally { editorRig.close() }
    }

    @Test fun warningApplyAndDefaultResetAffectFutureSessionsWithoutErasingAdaptiveHistory() = runBlocking {
        val editorRig = CustomProgramEditorRig()
        try {
            val adaptive = seededAdaptive()
            val states = adaptive.stateDao.rows.toList()
            val records = adaptive.historyDao.rows.toList()
            val selection = setOf("pushups", "cat_cow")
            assertTrue(editorRig.libraryIds.containsAll(selection))
            editorRig.editor.open()
            (editorRig.libraryIds - selection).forEach(editorRig.editor::toggleExercise)
            assertTrue(editorRig.state().errors.isEmpty())
            assertTrue("warning witness", editorRig.state().warnings.isNotEmpty())
            val beforeApply = editorRig.repository.load()
            assertTrue(editorRig.editor.apply() is CustomProgramApplyResult.Applied)
            val applied = editorRig.repository.load()
            assertEquals(selection, applied.enabledExerciseIds)
            assertEquals(beforeApply.configurationVersion + 1, applied.configurationVersion)
            val active = ActiveWorkoutConfiguration()
            val context = { WorkoutSessionContext(1, 0, AdaptiveLifecycleRig.PROGRAM_START) }
            val started = active.beginSession(WorkoutSessionIdentity(3, false), context) { editorRig.repository.load() }
            editorRig.editor.requestResetToDefault()
            editorRig.editor.confirmResetToDefault()
            val reset = editorRig.repository.load()
            assertEquals(ProgramConfigurationSource.DEFAULT, reset.source)
            assertEquals(editorRig.libraryIds, reset.enabledExerciseIds)
            assertEquals(applied.configurationVersion + 1, reset.configurationVersion)
            assertSame(started, active.beginSession(WorkoutSessionIdentity(3, false), context) { editorRig.repository.load() })
            val future = active.beginSession(WorkoutSessionIdentity(4, false), context) { editorRig.repository.load() }
            assertEquals(reset.enabledExerciseIds, future.enabledExerciseIds)
            assertEquals(states, adaptive.stateDao.rows)
            assertEquals(records, adaptive.historyDao.rows)
        } finally { editorRig.close() }
    }

    @Test fun oldSessionFinalizesThroughRealHistoryAfterRevisionCalendarAndConfigurationAllChange() = runBlocking {
        val editorRig = CustomProgramEditorRig()
        try {
            val calendarA = AdaptiveLifecycleRig.PROGRAM_START
            val stamp = calendarA.plusDays(9).toEpochDay() * AdaptiveLifecycleRig.MILLIS_PER_DAY
            val adaptive = AdaptiveLifecycleRig(userProgress = listOf(UserProgress(cycleNumber = 1,
                day = 10, isCompleted = true, completionDate = stamp)),
                familyStates = listOf(persistedFamilyState(progressionLevel = 1, currentExerciseId = "pushups_wide")))
            editorRig.repository.apply(setOf("pushups", "cat_cow"))
            var live = WorkoutSessionContext(1, 0, calendarA)
            var reads = 0
            val holder = ActiveWorkoutConfiguration()
            suspend fun enter() = holder.beginSession(WorkoutSessionIdentity(10, false), { reads++; live }) {
                editorRig.repository.load()
            }
            val snapshotA = enter()
            live = WorkoutSessionContext(4, 1, calendarA.plusDays(60))
            editorRig.repository.apply(setOf("squats", "cat_cow"))
            repeat(20) { assertSame(snapshotA, enter()) }
            assertEquals(1, reads)
            val request = requireNotNull(sessionFinalizationRequest(holder.activeSession.value, emptySet()))
            assertEquals(calendarA, request.programStartDate)
            assertEquals(0, request.programRevision)
            assertEquals(1, request.programCycle)
            assertSame(snapshotA, request.configuration)
            val recorder = adaptive.recorderOverPersistedHistory()
            assertTrue(recorder.recordFinalizedSession(request).isFinalized)
            val states = adaptive.stateDao.rows.toList()
            val history = adaptive.historyDao.rows.toList()
            repeat(100) { adaptive.now++; recorder.recordFinalizedSession(request) }
            assertEquals(states, adaptive.stateDao.rows)
            assertEquals(history, adaptive.historyDao.rows)
            val familyOf = adaptive.generator.getExerciseLibrary().associate { it.id to it.familyId }
            assertEquals(setOf(familyOf["pushups"], familyOf["cat_cow"]), history.map { it.familyId }.toSet())
            assertTrue(history.all { it.programRevision == 0 && it.cycleNumber == 1 && it.programDay == 10 })
            assertNull(adaptive.repository().familyState(1, "pushups"))
        } finally { editorRig.close() }
    }

    @Test fun recoverySequenceKeepsTheLadderAndExitsOnlyToHoldWithExplicitPolicyEvidence() = runBlocking {
        val policy = AdaptivePolicy.V1
        val rig = AdaptiveLifecycleRig(familyStates = listOf(persistedFamilyState(
            progressionLevel = 1, currentExerciseId = "pushups_wide",
            precedingProgressQualifyingWindows = policy.progressConfirmingWindows - 1)))
        rig.recorder(rig.highLoadDeteriorationSessions()).recordFinalizedSession(rig.request(programDay = 14))
        assertEquals(AdaptiveState.RECOVERY, rig.stateDao.rows.single().adaptationState)
        assertEquals(1, rig.stateDao.rows.single().progressionLevel)
        // Qualification ownership is deliberately unresolved. Supply evidence explicitly, never compute it here.
        for (qualifying in 0..policy.recoveryExitQualifyingSessions) {
            rig.repository().saveFamilyState(rig.stateDao.rows.single().copy(recoveryQualifyingSessions = qualifying))
            val day = 20 + qualifying
            val history = (day - 5..day).map { rig.repSession(programDay = it) }
            rig.recorder(history).recordFinalizedSession(rig.request(programDay = day))
            val state = rig.stateDao.rows.single()
            assertEquals(if (qualifying < policy.recoveryExitQualifyingSessions) AdaptiveState.RECOVERY
                else AdaptiveState.HOLD, state.adaptationState)
            assertEquals("recovery count=$qualifying", 1, state.progressionLevel)
            assertEquals("pushups_wide", state.currentExerciseId)
        }
        assertTrue(rig.historyDao.rows.none { it.newState == AdaptiveState.PROGRESS })
    }

    @Test fun confirmedCooldownIsDirectionIndependentAndNullDoesNotBlockTheFirstChange() = runBlocking {
        val policy = AdaptivePolicy.V1
        for (regress in listOf(false, true)) {
            for (cooldown in listOf<Int?>(null) + (0..policy.progressionCooldownEligibleSessions).toList()) {
                // The family sits on an earned level, so a cooldown HOLD that erased it would be visible.
                val rig = AdaptiveLifecycleRig(familyStates = listOf(persistedFamilyState(
                    progressionLevel = 1,
                    currentExerciseId = "pushups_wide",
                    precedingProgressQualifyingWindows = policy.progressConfirmingWindows - 1,
                    precedingRegressQualifyingWindows = policy.regressConfirmingWindows - 1,
                    eligibleSessionsSinceLastProgressionChange = cooldown)))
                val history = if (regress) (1..5).map { day -> rig.repSession(programDay = day,
                    plannedRepsPerSet = 100, completedRepsPerSet = 90 - day * 10) }
                    else rig.fullExposureSessions(count = 5)
                rig.recorder(history).recordFinalizedSession(rig.request(programDay = 5,
                    enabledExerciseIds = rig.generator.getExerciseLibrary().map { it.id }.toSet()))
                val blocked = cooldown != null && cooldown < policy.progressionCooldownEligibleSessions
                val state = requireNotNull(rig.repository().familyState(0, "pushups"))
                assertEquals("regress=$regress cooldown=$cooldown",
                    if (blocked) AdaptiveState.HOLD else if (regress) AdaptiveState.REGRESS else AdaptiveState.PROGRESS,
                    state.adaptationState)
                assertEquals("regress=$regress cooldown=$cooldown", if (blocked) 1 else if (regress) 0 else 2,
                    state.progressionLevel)
                assertEquals("regress=$regress cooldown=$cooldown",
                    when {
                        blocked -> "pushups_wide"
                        regress -> "pushups"
                        else -> "decline_pushups"
                    },
                    state.currentExerciseId)
                if (blocked) {
                    assertEquals(AdaptiveReasonCode.PROGRESSION_COOLDOWN,
                        rig.historyDao.rows.single { it.familyId == "pushups" }.reasonCode)
                }
            }
        }
    }

    private suspend fun seededAdaptive(): AdaptiveLifecycleRig {
        val rig = AdaptiveLifecycleRig(familyStates = listOf(persistedFamilyState(
            progressionLevel = 1, currentExerciseId = "pushups_wide")))
        rig.recorder(rig.fullExposureSessions()).recordFinalizedSession(rig.request(programDay = 6))
        assertEquals(1, rig.stateDao.rows.single().progressionLevel)
        assertEquals(1, rig.historyDao.rows.size)
        return rig
    }
}
