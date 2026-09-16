package com.monkfitness.app.data.repository

import com.monkfitness.app.data.model.AdaptiveDecisionRecord
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.data.model.UserProgress
import com.monkfitness.app.domain.adaptive.AdaptiveAction
import com.monkfitness.app.domain.adaptive.AdaptiveReasonCode
import com.monkfitness.app.domain.adaptive.AdaptiveState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The adaptive half of the C3 lifecycle contract, pinned against the same authorities the app runs.
 *
 * Two of the four lifecycle actions are *preservation* rules and one is a *boundary* rule, and each can
 * be broken by an integration that looks reasonable:
 *
 *  * **Normal cycle rollover** (cycle 56 → the next cycle's day 1) neither resets progression nor
 *    triggers a decision of its own. Adaptive state is not cycle-scoped — a family's level, its state
 *    and its audit trail carry across the boundary — and the rollover is a calendar operation that
 *    records nothing.
 *  * **Restart Current Cycle** wipes the active cycle's calendar rows through the existing C3 authority
 *    and leaves family progression and decision history exactly where they were: repeating a cycle does
 *    not mean losing physical capability.
 *  * **Start Revised Program** starts a NEW revision from the ladder's baseline while the previous
 *    revision's states and history stay readable — current progression truth is revision-scoped, so a
 *    new revision may not inherit the old one's levels and the old one's records may not be deleted.
 *  * **Full Reset** returns the program to its true first-launch state: the adaptive tables go with the
 *    rest of the program history, in the same transaction, while the tables C3 preserves stay untouched.
 *  * **A disabled family keeps its state**, and re-enabling it continues from the state it already had
 *    rather than from a baseline.
 *
 * The DAOs are faked ([AdaptiveLifecycleRig]) — no Robolectric harness exists in this project — and the
 * lifecycle operations under test are the production ones (`ProgramMaintenance`, the recorder), so what
 * is asserted here is the behaviour of the app's own code, not of a re-implementation of it.
 */
class AdaptiveLifecycleTest {

    // ---- normal cycle rollover ------------------------------------------------------------------

    @Test
    fun cycleRolloverPreservesProgressionAndRecordsNoDecisionOfItsOwn() = runBlocking {
        // Cycle 1 ends with the family on a level, and the next cycle's first session continues that
        // same family: adaptive state is not cycle-scoped, and the boundary itself is a calendar event
        // that records nothing.
        val rig = AdaptiveLifecycleRig(
            familyStates = listOf(
                persistedFamilyState(
                    progressionLevel = 1,
                    currentExerciseId = "pushups_wide",
                    adaptationState = AdaptiveState.HOLD,
                    precedingProgressQualifyingWindows = 1
                )
            )
        )
        val history = (35..40).map { day -> rig.repSession(programDay = day, cycleNumber = 1) } +
            rig.repSession(programDay = 1, cycleNumber = 2)
        val recorder = rig.recorder(history)
        val permitted = setOf("pushups", "pushups_wide", "decline_pushups")

        // The last session of cycle 1.
        recorder.recordFinalizedSession(
            rig.request(programCycle = 1, programDay = 40, enabledExerciseIds = permitted)
        )
        assertEquals(
            "the family's own ladder moved it one level",
            2,
            rig.stateDao.rows.single().progressionLevel
        )

        // The rollover: cycle 2 begins and its day 1 is trained. Nothing between the two sessions is an
        // adaptive event, and the family is not restarted for the new cycle.
        recorder.recordFinalizedSession(
            rig.request(programCycle = 2, programDay = 1, enabledExerciseIds = permitted)
        )

        assertEquals(
            "the family is one row, not one per cycle: adaptive state is not cycle-scoped",
            1,
            rig.stateDao.rows.size
        )
        assertEquals(
            "the level the previous cycle earned is not reset by the boundary",
            2,
            rig.stateDao.rows.single().progressionLevel
        )
        assertEquals(
            "and the variation the level resolved to is carried across the boundary",
            "decline_pushups",
            rig.stateDao.rows.single().currentExerciseId
        )
        assertEquals(
            "the trail records the two windows in the order they ran, with no boundary entry",
            listOf(1 to 40, 2 to 1),
            rig.historyDao.rows.map { it.cycleNumber to it.programDay }
        )
        assertEquals("a rollover adds no decision of its own", 2, rig.historyDao.rows.size)
    }

    // ---- Restart Current Cycle ------------------------------------------------------------------

    @Test
    fun restartCurrentCycleLeavesAdaptiveProgressionAndHistoryIntact() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(
                UserProgress(cycleNumber = 1, day = 40, isCompleted = true, completionDate = 1L),
                UserProgress(cycleNumber = 2, day = 3, isCompleted = true, completionDate = 2L)
            ),
            familyStates = listOf(
                persistedFamilyState(
                    progressionLevel = 2,
                    currentExerciseId = "decline_pushups",
                    adaptationState = AdaptiveState.HOLD,
                    eligibleSessionsSinceLastProgressionChange = 1
                )
            ),
            records = listOf(decisionRecord(cycleNumber = 1, programDay = 40))
        )

        ProgramMaintenance.deleteProgressForCycle(1, rig.progressDao, rig.committing)

        assertEquals(
            "the restart clears the cycle's calendar rows",
            listOf(UserProgress(cycleNumber = 2, day = 3, isCompleted = true, completionDate = 2L)),
            rig.progressDao.userProgress
        )
        assertEquals("the restart touches no adaptive table", emptyList<String>(), rig.stateDao.callLog)
        assertEquals(emptyList<String>(), rig.historyDao.callLog)
        assertEquals(
            "the family's accumulated level survives the restart",
            2,
            rig.stateDao.rows.single().progressionLevel
        )
        assertEquals(
            "the family's state row is the very same row, including its cooldown position",
            1,
            rig.stateDao.rows.single().eligibleSessionsSinceLastProgressionChange
        )
        assertEquals(
            "decision history survives too: the restart deletes no audit entry",
            listOf(1 to 40),
            rig.historyDao.rows.map { it.cycleNumber to it.programDay }
        )
    }

    @Test
    fun theRestartOperationNamesOnlyTheCalendarsOwnTables() = runBlocking {
        // The structural half of the rule above: the restart's own transaction is the three progress
        // tables and nothing else, so there is no path by which it could clear adaptive data even if a
        // future table appeared next to them.
        val rig = AdaptiveLifecycleRig()

        ProgramMaintenance.deleteProgressForCycle(3, rig.progressDao, rig.committing)

        assertEquals(
            listOf(
                "deleteUserProgressForCycle(3)",
                "deletePostureProgressForCycle(3)",
                "deleteProgramDayStatesForCycle(3)"
            ),
            rig.progressDao.callLog
        )
        assertEquals(
            "the restart's transaction is one block of three deletes",
            1,
            rig.transactions
        )
    }

    // ---- Start Revised Program ------------------------------------------------------------------

    @Test
    fun startRevisedProgramResetsProgressionForTheNewRevisionAndKeepsTheOldHistory() = runBlocking {
        // The database as it stands after a revised start: the C3 action bumps the program revision and
        // restarts the calendar, and it deletes nothing — the earlier revision's state rows and records
        // are still there, and the new revision's first session runs against them.
        val beforeTheRevision = AdaptiveLifecycleRig(
            familyStates = listOf(
                persistedFamilyState(progressionLevel = 2, currentExerciseId = "decline_pushups", programRevision = 0)
            ),
            records = listOf(decisionRecord(cycleNumber = 1, programDay = 40, programRevision = 0))
        )

        val revised = AdaptiveLifecycleRig(
            familyStates = beforeTheRevision.stateDao.rows.toList(),
            records = beforeTheRevision.historyDao.rows.toList()
        )

        val outcome = revised.recorder(listOf(revised.repSession(programDay = 1)))
            .recordFinalizedSession(revised.request(programDay = 1, programRevision = 1))

        assertEquals(listOf("pushups"), outcome.recordedFamilies)
        assertEquals(
            "the new revision's family starts from the ladder's baseline",
            0,
            revised.repository().familyState(1, "pushups")!!.progressionLevel
        )
        assertEquals(
            "the previous revision's current state is neither reused nor deleted",
            2,
            revised.repository().familyState(0, "pushups")!!.progressionLevel
        )
        assertEquals(
            "the old revision's history stays queryable on its own",
            listOf(1 to 40),
            revised.repository().decisionHistory(0).map { it.cycleNumber to it.programDay }
        )
        assertEquals(
            "and the new revision's history is separate from it",
            listOf(1 to 1),
            revised.repository().decisionHistory(1).map { it.cycleNumber to it.programDay }
        )
        assertEquals(
            "a family's trail spans revisions, which is what keeps the old results analysable",
            listOf(0 to 40, 1 to 1),
            revised.repository().decisionHistoryForFamily("pushups").map { it.programRevision to it.programDay }
        )
    }

    // ---- Full Reset -----------------------------------------------------------------------------

    @Test
    fun fullResetClearsTheAdaptiveTablesInTheSameTransactionAsTheRestOfTheProgram() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(UserProgress(cycleNumber = 1, day = 1, isCompleted = true)),
            familyStates = listOf(persistedFamilyState(progressionLevel = 2), persistedFamilyState(familyId = "squats", progressionLevel = 1)),
            records = listOf(decisionRecord(cycleNumber = 1, programDay = 1))
        )

        ProgramMaintenance.clearAllProgressData(
            progressDao = rig.progressDao,
            familyStateDao = rig.stateDao,
            decisionHistoryDao = rig.historyDao,
            inTransaction = rig.committing
        )

        assertEquals("adaptive current state is cleared", emptyList<FamilyProgressionState>(), rig.stateDao.rows)
        assertEquals("adaptive decision history is cleared", emptyList<AdaptiveDecisionRecord>(), rig.historyDao.rows)
        assertEquals("the program's progress tables are cleared as before", emptyList<UserProgress>(), rig.progressDao.userProgress)
        assertEquals(
            "one transaction for the whole reset",
            1,
            rig.transactions
        )
        assertEquals(
            "the adaptive clears run inside the same block as the progress clears",
            listOf(
                "clearUserProgress",
                "clearPostureProgress",
                "clearProgramDayStates",
                "clearSetLogs",
                "clearBodyWeightEntries"
            ),
            rig.progressDao.callLog
        )
        assertEquals(listOf("clearFamilyStates"), rig.stateDao.callLog)
        assertEquals(listOf("clearDecisionHistory"), rig.historyDao.callLog)
    }

    @Test
    fun fullResetRollsTheAdaptiveTablesBackWhenAClearFails() = runBlocking {
        val rig = AdaptiveLifecycleRig(
            userProgress = listOf(UserProgress(cycleNumber = 1, day = 1, isCompleted = true)),
            familyStates = listOf(persistedFamilyState(progressionLevel = 2)),
            records = listOf(decisionRecord(cycleNumber = 1, programDay = 1))
        )
        rig.historyDao.failure = "adaptive_decision_record: disk full"

        val thrown = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ProgramMaintenance.clearAllProgressData(
                    progressDao = rig.progressDao,
                    familyStateDao = rig.stateDao,
                    decisionHistoryDao = rig.historyDao,
                    inTransaction = rig.rollingBack()
                )
            }
        }

        assertEquals("adaptive_decision_record: disk full", thrown.message)
        assertEquals(
            "a failed reset leaves the family's state exactly as it was",
            listOf(persistedFamilyState(progressionLevel = 2)),
            rig.stateDao.rows
        )
        assertEquals("and its audit trail too", 1, rig.historyDao.rows.size)
    }

    @Test
    fun fullResetNamesEveryProgramTableButKeepsNutrition() {
        assertTrue(
            "the adaptive tables are part of the program's own record and are cleared with it",
            ProgramMaintenance.clearedTables.containsAll(
                listOf("family_progression_state", "adaptive_decision_record")
            )
        )
        assertEquals(
            "nutrition plans are keyed by their own calendar and stay preserved",
            listOf("meal_cycles", "meals", "shopping_items"),
            ProgramMaintenance.preservedTables
        )
    }

    @Test
    fun fullResetClassifiesEveryTableTheDatabaseDeclares() {
        // The reset's own specification: "everything the program records, except nutrition plans". A
        // table added to the database without being classified here is a table a full reset silently
        // forgets, which is exactly the failure mode the C3 suite exists to catch.
        assertEquals(
            "every entity the database declares is either cleared by a full reset or deliberately preserved",
            declaredTableNames().orEmpty().sorted(),
            (ProgramMaintenance.clearedTables + ProgramMaintenance.preservedTables).sorted()
        )
        assertEquals(
            "7 program-scoped tables are cleared, 3 nutrition tables are preserved",
            7 to 3,
            ProgramMaintenance.clearedTables.size to ProgramMaintenance.preservedTables.size
        )
    }

    // ---- a disabled family keeps its progression ------------------------------------------------

    @Test
    fun aDisabledFamilyKeepsItsStateAndReEnablingItContinuesFromIt() = runBlocking {
        val storedBefore = persistedFamilyState(familyId = "squats", progressionLevel = 1, currentExerciseId = "cossack_squat")
        val rig = AdaptiveLifecycleRig(
            familyStates = listOf(storedBefore)
        )

        // A session that runs without the family enabled: the configuration does not permit squats, so
        // the family is not evaluated and nothing about it is written.
        rig.recorder(rig.fullExposureSessions(count = 7))
            .recordFinalizedSession(rig.request(programDay = 6, enabledExerciseIds = setOf("pushups")))

        assertEquals(
            "disabling a family does not erase its progression state",
            storedBefore,
            rig.repository().familyState(0, "squats")
        )
        assertEquals(
            "and no second row is created for it by a window that never evaluated it",
            1,
            rig.stateDao.rows.count { it.familyId == "squats" }
        )
        assertEquals(listOf("pushups"), rig.historyDao.rows.map { it.familyId })

        // Re-enabling it: the family is evaluated again, from the state it already had — not from a
        // baseline — and the audit entry says which state it came from.
        rig.recorder(rig.fullExposureSessions(count = 7))
            .recordFinalizedSession(rig.request(programDay = 7, enabledExerciseIds = setOf("squats")))

        val squats = rig.repository().familyState(0, "squats")
        assertNotNull(squats)
        assertEquals(
            "re-enabling a family does not silently reset its level",
            1,
            squats!!.progressionLevel
        )
        assertEquals("cossack_squat", squats.currentExerciseId)
        assertEquals(
            "and its first window after being re-enabled holds, as the conservative default the engine documents",
            AdaptiveState.HOLD,
            squats.adaptationState
        )
        assertEquals(
            "the audit entry names the state it came from",
            AdaptiveState.HOLD,
            rig.historyDao.rows.first { it.familyId == "squats" }.previousState
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun decisionRecord(
        cycleNumber: Int,
        programDay: Int,
        programRevision: Int = 0,
        familyId: String = "pushups"
    ) = AdaptiveDecisionRecord(
        id = 0,
        familyId = familyId,
        programRevision = programRevision,
        cycleNumber = cycleNumber,
        programDay = programDay,
        timestamp = 1_800_000_000_000L,
        previousState = AdaptiveState.HOLD,
        newState = AdaptiveState.PROGRESS,
        actions = listOf(AdaptiveAction.INCREASE_STIMULUS),
        reasonCode = AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
        policyVersion = 1
    )

    /**
     * Every table name the app's own entity declarations carry, read from the sources rather than
     * restated here, so a new entity cannot be added to the database without appearing in this census.
     */
    private fun declaredTableNames(): List<String>? {
        val modelDir = File("src/main/java/com/monkfitness/app/data/model").let { dir ->
            if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app/data/model")
        }
        val tableName = Regex("""@Entity\(\s*(?:tableName\s*=\s*)?"([a-z_0-9]+)"""")

        return modelDir.listFiles { file -> file.isFile && file.extension == "kt" }
            ?.flatMap { file -> tableName.findAll(file.readText()).map { it.groupValues[1] } }
            ?.toList()
    }
}
