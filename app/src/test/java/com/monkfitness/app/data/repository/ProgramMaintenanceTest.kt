package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgressDao
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.MealEntity
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.ProgramStatisticsSnapshot
import com.monkfitness.app.data.model.SetLog
import com.monkfitness.app.data.model.SetLogRow
import com.monkfitness.app.data.model.ShoppingItemEntity
import com.monkfitness.app.data.model.UserProgress
import com.monkfitness.app.data.model.VolumeHistoryPoint
import com.monkfitness.app.data.model.WorkoutFrequencyPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The C3 destructive operations are ALL-or-NOTHING: the whole point of wrapping them in a Room
 * transaction is that a partial wipe can never be left behind. A "Restart Current Cycle" that
 * cleared `user_progress` and then failed on `posture_session_progress` would leave a cycle whose
 * workout completions are gone but whose posture completions survive — silent, and in the UI
 * indistinguishable from a completed reset. That is the regression this suite pins.
 *
 * `ProgressDao` is a Room interface, so its behaviour is faked here; the transaction semantics are
 * what is under test, supplied by the production caller (`database.withTransaction`) and by fakes
 * that either commit everything or roll the whole block back. No emulator or Robolectric is
 * involved — this is a JVM unit test, like the rest of the project's suite.
 */
class ProgramMaintenanceTest {

    /**
     * In-memory [ProgressDao] that records every call. Open with public mutable state so a test
     * can subclass it (to inject a failing delete) and snapshot/restore its tables (to model a
     * transaction rollback).
     */
    open class FakeProgressDao(
        userProgress: List<UserProgress> = emptyList(),
        postureProgress: List<PostureSessionProgress> = emptyList(),
        programDayStates: List<ProgramDayState> = emptyList(),
        setLogs: List<SetLog> = emptyList(),
        bodyWeight: List<BodyWeightEntry> = emptyList()
    ) : ProgressDao {

        var userProgress = userProgress.toMutableList()
        var postureProgress = postureProgress.toMutableList()
        var programDayStates = programDayStates.toMutableList()
        var setLogs = setLogs.toMutableList()
        var bodyWeight = bodyWeight.toMutableList()

        val callLog = mutableListOf<String>()

        override suspend fun deleteUserProgressForCycle(cycleNumber: Int) {
            callLog += "deleteUserProgressForCycle($cycleNumber)"
            userProgress.removeAll { it.cycleNumber == cycleNumber }
        }

        override suspend fun deletePostureProgressForCycle(cycleNumber: Int) {
            callLog += "deletePostureProgressForCycle($cycleNumber)"
            postureProgress.removeAll { it.cycleNumber == cycleNumber }
        }

        override suspend fun deleteProgramDayStatesForCycle(cycleNumber: Int) {
            callLog += "deleteProgramDayStatesForCycle($cycleNumber)"
            programDayStates.removeAll { it.cycleNumber == cycleNumber }
        }

        override suspend fun clearUserProgress() {
            callLog += "clearUserProgress"
            userProgress.clear()
        }

        override suspend fun clearPostureProgress() {
            callLog += "clearPostureProgress"
            postureProgress.clear()
        }

        override suspend fun clearProgramDayStates() {
            callLog += "clearProgramDayStates"
            programDayStates.clear()
        }

        override suspend fun clearSetLogs() {
            callLog += "clearSetLogs"
            setLogs.clear()
        }

        override suspend fun clearBodyWeightEntries() {
            callLog += "clearBodyWeightEntries"
            bodyWeight.clear()
        }

        // --- Not exercised by the C3 contract; minimal implementations. ---

        override fun getAllProgress(cycleNumber: Int): Flow<List<UserProgress>> = flowOf(userProgress)
        override fun getCompletedDaysCount(cycleNumber: Int): Flow<Int> = flowOf(0)
        override suspend fun getCompletedDays(): List<UserProgress> = userProgress
        override suspend fun getDayProgressSnapshot(): List<UserProgress> = userProgress
        override suspend fun getSessionDates(): List<String> = emptyList()
        override suspend fun getProgramCycles(): List<Int> = emptyList()
        override suspend fun getSetLogsForSessionDate(sessionDate: String): List<SetLogRow> = emptyList()
        override suspend fun getProgressByDay(cycleNumber: Int, day: Int): UserProgress? = null
        override suspend fun updateProgress(progress: UserProgress) {}
        override suspend fun insertSetLog(setLog: SetLog) {}
        override suspend fun insertEntry(entry: BodyWeightEntry) {}
        override fun getEntriesSince(cutoff: String): Flow<List<BodyWeightEntry>> = flowOf(bodyWeight)
        override suspend fun getLatestEntry(): BodyWeightEntry? = null
        override suspend fun deleteLatestSetLogForExerciseOnDate(exerciseId: String, sessionDate: String) {}
        override fun getDailyVolumeHistory(): Flow<List<VolumeHistoryPoint>> = flowOf(emptyList())
        override fun getExerciseVolumeHistory(exerciseId: String): Flow<List<VolumeHistoryPoint>> = flowOf(emptyList())
        override fun getWorkoutFrequencyByWeek(): Flow<List<WorkoutFrequencyPoint>> = flowOf(emptyList())
        override fun getAllPostureProgress(cycleNumber: Int): Flow<List<PostureSessionProgress>> = flowOf(postureProgress)
        override fun getCompletedPostureDaysCount(cycleNumber: Int): Flow<Int> = flowOf(0)
        override suspend fun getPostureProgressByDay(cycleNumber: Int, day: Int): PostureSessionProgress? = null
        override suspend fun updatePostureProgress(progress: PostureSessionProgress) {}
        override fun getProgramDayStates(cycleNumber: Int): Flow<List<ProgramDayState>> = flowOf(programDayStates)
        override suspend fun getProgramDayStatesSnapshot(cycleNumber: Int): List<ProgramDayState> = programDayStates
        override suspend fun getProgramDayState(cycleNumber: Int, day: Int): ProgramDayState? = null
        override suspend fun upsertProgramDayStates(states: List<ProgramDayState>) {}
        override suspend fun upsertProgramDayState(state: ProgramDayState) {}
        override fun getProgramStatistics(cycleNumber: Int): Flow<ProgramStatisticsSnapshot> =
            flowOf(ProgramStatisticsSnapshot(0, 0, 0, 0, 0, 0, 0))
        override suspend fun getMaxProgramDayStateCycle(): Int? = null
        override fun getMealCycles(): Flow<List<MealCycle>> = flowOf(emptyList())
        override suspend fun getMealCyclesSnapshot(): List<MealCycle> = emptyList()
        override suspend fun insertMealCycle(cycle: MealCycle): Long = 0
        override suspend fun insertMeals(meals: List<MealEntity>) {}
        override suspend fun insertShoppingItems(items: List<ShoppingItemEntity>) {}
        override suspend fun deleteMealsForCycle(cycleId: Long) {}
        override suspend fun deleteShoppingItemsForCycle(cycleId: Long) {}
        override fun getMealsForCycle(cycleId: Long): Flow<List<MealEntity>> = flowOf(emptyList())
        override fun getShoppingItemsForCycle(cycleId: Long): Flow<List<ShoppingItemEntity>> = flowOf(emptyList())
        override suspend fun getMealsForCycleSnapshot(cycleId: Long): List<MealEntity> = emptyList()
        override suspend fun getShoppingItemsForCycleSnapshot(cycleId: Long): List<ShoppingItemEntity> = emptyList()
        override suspend fun getMealForCycleAndType(cycleId: Long, programDay: Int, mealTypeKey: String): MealEntity? = null
        override suspend fun upsertMeal(meal: MealEntity) {}
    }

    /** A snapshot of every table, so a rolled-back transaction can restore the pre-state. */
    private data class Snapshot(
        val user: List<UserProgress>,
        val posture: List<PostureSessionProgress>,
        val states: List<ProgramDayState>,
        val sets: List<SetLog>,
        val weight: List<BodyWeightEntry>
    )

    private fun FakeProgressDao.snapshot() = Snapshot(userProgress, postureProgress, programDayStates, setLogs, bodyWeight)

    private fun FakeProgressDao.restore(s: Snapshot) {
        userProgress.apply { clear(); addAll(s.user) }
        postureProgress.apply { clear(); addAll(s.posture) }
        programDayStates.apply { clear(); addAll(s.states) }
        setLogs.apply { clear(); addAll(s.sets) }
        bodyWeight.apply { clear(); addAll(s.weight) }
    }

    /** A transaction that commits the whole block, as `database.withTransaction` does. */
    private suspend fun committingTransaction(block: suspend () -> Unit) {
        block()
    }

    /**
     * A transaction that rolls the block back on any failure: it snapshots the tables, runs the
     * block, and restores the snapshot if the block throws — the observable outcome of a Room
     * rollback, which is what makes a mid-transaction failure leave nothing behind.
     */
    private fun rollingBackTransaction(
        dao: FakeProgressDao
    ): suspend (suspend () -> Unit) -> Unit = { block ->
        val before = dao.snapshot()
        try {
            block()
        } catch (e: Exception) {
            dao.restore(before)
            throw e
        }
    }

    // ---- Restart Current Cycle ---------------------------------------------------------------

    @Test
    fun restartCurrentCycleDeletesOnlyTheActiveCyclesRows() {
        val dao = FakeProgressDao(
            userProgress = listOf(
                UserProgress(cycleNumber = 2, day = 1, isCompleted = true, completionDate = 1L),
                UserProgress(cycleNumber = 2, day = 2, isCompleted = true, completionDate = 2L),
                UserProgress(cycleNumber = 1, day = 1, isCompleted = true, completionDate = 3L)
            ),
            postureProgress = listOf(
                PostureSessionProgress(cycleNumber = 2, day = 3, isCompleted = true, completionDate = 4L),
                PostureSessionProgress(cycleNumber = 1, day = 3, isCompleted = true, completionDate = 5L)
            ),
            programDayStates = listOf(
                ProgramDayState(cycleNumber = 2, programDay = 1, isCompleted = true, completedAt = 6L),
                ProgramDayState(cycleNumber = 1, programDay = 1, isCompleted = false)
            )
        )

        runBlocking {
            ProgramMaintenance.deleteProgressForCycle(2, dao, ::committingTransaction)
        }

        assertEquals(
            "the active cycle's completions are gone from all three tables",
            listOf(UserProgress(cycleNumber = 1, day = 1, isCompleted = true, completionDate = 3L)),
            dao.userProgress
        )
        assertEquals(
            "posture completions of the active cycle are gone too",
            listOf(PostureSessionProgress(cycleNumber = 1, day = 3, isCompleted = true, completionDate = 5L)),
            dao.postureProgress
        )
        assertEquals(
            "the active cycle's grid rows are gone, so the sync tick re-seeds it fresh",
            listOf(ProgramDayState(cycleNumber = 1, programDay = 1, isCompleted = false)),
            dao.programDayStates
        )
    }

    @Test
    fun restartCurrentCycleDeletesAllThreeTablesInTheSameTransaction() {
        val dao = FakeProgressDao(
            userProgress = listOf(UserProgress(cycleNumber = 3, day = 1)),
            postureProgress = listOf(PostureSessionProgress(cycleNumber = 3, day = 1)),
            programDayStates = listOf(ProgramDayState(cycleNumber = 3, programDay = 1))
        )

        runBlocking {
            ProgramMaintenance.deleteProgressForCycle(3, dao, ::committingTransaction)
        }

        assertEquals(
            "the three deletes run in one block, so no table can be missed",
            listOf(
                "deleteUserProgressForCycle(3)",
                "deletePostureProgressForCycle(3)",
                "deleteProgramDayStatesForCycle(3)"
            ),
            dao.callLog
        )
    }

    @Test
    fun restartCurrentCycleRejectsAnInvalidCycleNumber() {
        val dao = FakeProgressDao()

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ProgramMaintenance.deleteProgressForCycle(0, dao, ::committingTransaction)
            }
        }

        assertTrue(
            "the failure names the invalid argument rather than a generic message",
            thrown.message!!.contains("cycleNumber")
        )
        assertEquals("nothing was deleted", emptyList<String>(), dao.callLog)
    }

    @Test
    fun restartCurrentCycleLeavesNothingBehindWhenATableFailsMidTransaction() {
        // The second delete always fails, simulating a mid-transaction error.
        val dao = object : FakeProgressDao(
            userProgress = listOf(UserProgress(cycleNumber = 2, day = 1, isCompleted = true)),
            postureProgress = listOf(PostureSessionProgress(cycleNumber = 2, day = 1, isCompleted = true)),
            programDayStates = listOf(ProgramDayState(cycleNumber = 2, programDay = 1, isCompleted = true))
        ) {
            override suspend fun deletePostureProgressForCycle(cycleNumber: Int) {
                throw IllegalStateException("posture_session_progress: disk full")
            }
        }
        val before = dao.snapshot()

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ProgramMaintenance.deleteProgressForCycle(2, dao, rollingBackTransaction(dao))
            }
        }

        assertEquals(
            "the failure is not suppressed — it reaches the caller",
            "posture_session_progress: disk full",
            thrown.message
        )
        assertEquals(
            "the already-applied delete is rolled back: no partial wipe is left behind",
            before.user,
            dao.userProgress
        )
        assertEquals(
            "the untouched tables are intact too",
            before.states,
            dao.programDayStates
        )
    }

    // ---- Full Reset --------------------------------------------------------------------------

    @Test
    fun fullResetClearsEveryProgramTable() {
        val dao = FakeProgressDao(
            userProgress = listOf(UserProgress(cycleNumber = 5, day = 40, isCompleted = true)),
            postureProgress = listOf(PostureSessionProgress(cycleNumber = 5, day = 40, isCompleted = true)),
            programDayStates = listOf(ProgramDayState(cycleNumber = 5, programDay = 40, isCompleted = true)),
            setLogs = listOf(SetLog(exerciseId = "squat", repsCompleted = 10, durationSeconds = 0, timestamp = 1L, sessionDate = "2026-09-01")),
            bodyWeight = listOf(BodyWeightEntry(weightKg = 80.0f, date = "2026-09-01"))
        )
        val adaptiveStates = FakeAdaptiveStateDao(
            listOf(
                FamilyProgressionState(
                    familyId = "pushups",
                    progressionLevel = 1,
                    eligibleSessionsSinceLastProgressionChange = null,
                    updatedAt = 1L
                )
            )
        )
        val decisionHistory = FakeAdaptiveHistoryDao()

        runBlocking {
            ProgramMaintenance.clearAllProgressData(dao, adaptiveStates, decisionHistory, ::committingTransaction)
        }

        assertEquals("user_progress cleared", emptyList<UserProgress>(), dao.userProgress)
        assertEquals("posture_session_progress cleared", emptyList<PostureSessionProgress>(), dao.postureProgress)
        assertEquals("program_day_state cleared", emptyList<ProgramDayState>(), dao.programDayStates)
        assertEquals("set_log cleared", emptyList<SetLog>(), dao.setLogs)
        assertEquals("body_weight_log cleared", emptyList<BodyWeightEntry>(), dao.bodyWeight)
        assertEquals("family_progression_state cleared", emptyList<FamilyProgressionState>(), adaptiveStates.rows)
        assertEquals(
            "all seven clears run in one block, so a reset cannot leave adaptive state behind",
            listOf(
                "clearUserProgress",
                "clearPostureProgress",
                "clearProgramDayStates",
                "clearSetLogs",
                "clearBodyWeightEntries"
            ),
            dao.callLog
        )
        assertEquals(listOf("clearFamilyStates"), adaptiveStates.callLog)
        assertEquals(listOf("clearDecisionHistory"), decisionHistory.callLog)
    }

    @Test
    fun fullResetRollsBackAndReportsWhenAClearFails() {
        // The fourth clear always fails, simulating a mid-transaction error.
        val dao = object : FakeProgressDao(
            userProgress = listOf(UserProgress(cycleNumber = 5, day = 40, isCompleted = true)),
            postureProgress = listOf(PostureSessionProgress(cycleNumber = 5, day = 40, isCompleted = true)),
            programDayStates = listOf(ProgramDayState(cycleNumber = 5, programDay = 40, isCompleted = true))
        ) {
            override suspend fun clearSetLogs() {
                throw IllegalStateException("set_log: disk full")
            }
        }
        val before = dao.snapshot()

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ProgramMaintenance.clearAllProgressData(
                    dao,
                    FakeAdaptiveStateDao(),
                    FakeAdaptiveHistoryDao(),
                    rollingBackTransaction(dao)
                )
            }
        }

        assertEquals(
            "the failure is not suppressed — it reaches the caller",
            "set_log: disk full",
            thrown.message
        )
        assertEquals(
            "the earlier clears are rolled back: the database is fully intact, not half empty",
            before.user,
            dao.userProgress
        )
        assertEquals(
            "posture progress is intact too",
            before.posture,
            dao.postureProgress
        )
        assertEquals(
            "program_day_state is intact too",
            before.states,
            dao.programDayStates
        )
    }

    // ---- the reset's own specification -------------------------------------------------------

    @Test
    fun fullResetClearsEveryProgramTableAndDeliberatelyPreservesNutritionTables() {
        // The database has 10 entities; the reset clears the 7 program-scoped ones — the 5 calendar and
        // logging tables plus the 2 adaptive ones — and keeps the 3 nutrition ones, whose plans are keyed
        // by their own meal-cycle calendar. A future table that joins the @Database entities list must
        // join clearedTables too, or this test fails — the reset's own spec is "everything the program
        // records, except nutrition plans" (the census is cross-checked against the entity declarations
        // by `AdaptiveLifecycleTest.fullResetClassifiesEveryTableTheDatabaseDeclares`).
        assertEquals(
            "7 program-scoped tables cleared",
            listOf(
                "user_progress",
                "posture_session_progress",
                "program_day_state",
                "set_log",
                "body_weight_log",
                "family_progression_state",
                "adaptive_decision_record"
            ),
            ProgramMaintenance.clearedTables
        )
        assertEquals(
            "nutrition tables preserved: their plans are keyed by their own meal-cycle calendar, " +
                "independent of the program calendar",
            listOf("meal_cycles", "meals", "shopping_items"),
            ProgramMaintenance.preservedTables
        )
    }
}
