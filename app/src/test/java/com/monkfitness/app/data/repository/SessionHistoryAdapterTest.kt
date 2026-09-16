package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgressDao
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
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
import com.monkfitness.app.domain.adaptive.SessionObservation
import com.monkfitness.app.domain.adaptive.SessionOutcome
import com.monkfitness.app.domain.adaptive.SessionSetLog
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Regression suite for the adapter that turns persisted workout history into pure
 * [SessionObservation] values.
 *
 * The adapter is the ONLY place that knows what a stored session *means*: everything downstream
 * (signal calculation, policy, progression) consumes the normalized observations and never touches
 * Room, DataStore or the generator. What is pinned here is the source-of-truth mapping itself —
 * which persisted row is trusted for which number — because silently swapping the source silently
 * rewrites history:
 *
 *  * **`UserProgress.isCompleted` is the day-level completion source.** `COMPLETED` means that flag
 *    is set — never that every prescribed set has a [SetLog] row.
 *  * **`SetLog` is the raw per-set source.** One row is one confirmed set. Its `repsCompleted` /
 *    `durationSeconds` are observed work. A set with no row was not performed, so planned work is
 *    never promoted to actual work on the strength of a completed flag.
 *  * **The planned workout is the generator's deterministic output for that historical day**, not
 *    the plan the generator would produce today — see the historical-identity tests.
 *  * **Reps and seconds stay in their own channels.** A repetition exercise contributes no seconds
 *    and a timer exercise contributes the placeholder-reps `0`, matching what the session records.
 *
 * The adapter is JVM-testable because it works off a [ProgressDao] snapshot; no Robolectric or
 * emulator is involved, like the rest of the project's suite.
 */
class SessionHistoryAdapterTest {

    /**
     * In-memory [ProgressDao]. Only the read paths the adapter uses are implemented meaningfully;
     * the rest are the same minimal stubs the existing C3 fake uses.
     */
    private class FakeProgressDao(
        userProgress: List<UserProgress> = emptyList(),
        programDayStates: List<ProgramDayState> = emptyList(),
        setLogs: List<SetLog> = emptyList()
    ) : ProgressDao {

        var userProgress = userProgress.toMutableList()
        var programDayStates = programDayStates.toMutableList()
        var setLogs = setLogs.toMutableList()

        override suspend fun getCompletedDays(): List<UserProgress> = userProgress.filter { it.isCompleted }

        override suspend fun getDayProgressSnapshot(): List<UserProgress> = userProgress

        override suspend fun getSessionDates(): List<String> = setLogs.map { it.sessionDate }.distinct().sorted()

        override suspend fun getProgramCycles(): List<Int> = programDayStates.map { it.cycleNumber }.distinct().sorted()

        override suspend fun getSetLogsForSessionDate(sessionDate: String): List<SetLogRow> = setLogs
            .filter { it.sessionDate == sessionDate }
            .sortedBy { it.timestamp }
            .map {
                SetLogRow(
                    exerciseId = it.exerciseId,
                    sessionDate = it.sessionDate,
                    timestamp = it.timestamp,
                    repsCompleted = it.repsCompleted,
                    durationSeconds = it.durationSeconds
                )
            }

        override suspend fun getProgressByDay(cycleNumber: Int, day: Int): UserProgress? =
            userProgress.firstOrNull { it.cycleNumber == cycleNumber && it.day == day }

        override suspend fun getProgramDayStatesSnapshot(cycleNumber: Int): List<ProgramDayState> =
            programDayStates.filter { it.cycleNumber == cycleNumber }

        override suspend fun getProgramDayState(cycleNumber: Int, day: Int): ProgramDayState? =
            programDayStates.firstOrNull { it.cycleNumber == cycleNumber && it.programDay == day }

        override fun getAllProgress(cycleNumber: Int): Flow<List<UserProgress>> = flowOf(userProgress)
        override fun getCompletedDaysCount(cycleNumber: Int): Flow<Int> = flowOf(0)
        override suspend fun updateProgress(progress: UserProgress) {}
        override suspend fun insertSetLog(setLog: SetLog) {}
        override suspend fun insertEntry(entry: BodyWeightEntry) {}
        override fun getEntriesSince(cutoff: String): Flow<List<BodyWeightEntry>> = flowOf(emptyList())
        override suspend fun getLatestEntry(): BodyWeightEntry? = null
        override suspend fun deleteLatestSetLogForExerciseOnDate(exerciseId: String, sessionDate: String) {}
        override fun getDailyVolumeHistory(): Flow<List<VolumeHistoryPoint>> = flowOf(emptyList())
        override fun getExerciseVolumeHistory(exerciseId: String): Flow<List<VolumeHistoryPoint>> = flowOf(emptyList())
        override fun getWorkoutFrequencyByWeek(): Flow<List<WorkoutFrequencyPoint>> = flowOf(emptyList())
        override fun getAllPostureProgress(cycleNumber: Int): Flow<List<PostureSessionProgress>> = flowOf(emptyList())
        override fun getCompletedPostureDaysCount(cycleNumber: Int): Flow<Int> = flowOf(0)
        override suspend fun getPostureProgressByDay(cycleNumber: Int, day: Int): PostureSessionProgress? = null
        override suspend fun updatePostureProgress(progress: PostureSessionProgress) {}
        override fun getProgramDayStates(cycleNumber: Int): Flow<List<ProgramDayState>> = flowOf(programDayStates)
        override suspend fun getMaxProgramDayStateCycle(): Int? = null
        override suspend fun deleteUserProgressForCycle(cycleNumber: Int) {}
        override suspend fun deletePostureProgressForCycle(cycleNumber: Int) {}
        override suspend fun deleteProgramDayStatesForCycle(cycleNumber: Int) {}
        override suspend fun clearUserProgress() {}
        override suspend fun clearPostureProgress() {}
        override suspend fun clearProgramDayStates() {}
        override suspend fun clearSetLogs() {}
        override suspend fun clearBodyWeightEntries() {}
        override suspend fun upsertProgramDayStates(states: List<ProgramDayState>) {}
        override suspend fun upsertProgramDayState(state: ProgramDayState) {}
        override fun getProgramStatistics(cycleNumber: Int): Flow<ProgramStatisticsSnapshot> =
            flowOf(ProgramStatisticsSnapshot(0, 0, 0, 0, 0, 0, 0))
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

    private val generator = WorkoutGenerator()

    /**
     * The program started 2026-08-31, so the session dates the tests use resolve to known program
     * days through the same calendar the live app uses.
     */
    private val programStartDate: LocalDate = LocalDate.of(2026, 8, 31)

    /** A timestamp on 2026-09-01 — the session date the set rows carry. */
    private val startedAt = 1_788_211_200_000L // 2026-08-31T00:00Z — dateFor(1)
    private val finishedAt = startedAt + 30 * 60 * 1000L

    /** The exact planned exercises the generator prescribes for a historical day, in order. */
    private fun plannedExercises(day: Int): List<Exercise> = generator.generateWorkout(day).exercises

    /**
     * The calendar date a historical program day fell on — the inverse of the program calendar the
     * adapter resolves session dates through, so a test's `day` and its set-row dates always agree.
     */
    private fun dateFor(day: Int): String = programStartDate.plusDays((day - 1).toLong()).toString()

    private fun adapter(dao: FakeProgressDao) = SessionHistoryAdapter(dao, generator, programStartDate)

    private fun completedDay(
        cycle: Int,
        day: Int,
        finishedAt: Long = this.finishedAt
    ) = UserProgress(
        cycleNumber = cycle,
        day = day,
        isCompleted = true,
        completionDate = finishedAt,
        workoutType = generator.getWorkoutType(day).name
    )

    /** One confirmed set of a repetition exercise: the prescribed target reps, no seconds. */
    private fun repSet(
        exerciseId: String,
        reps: Int,
        sessionDate: String,
        timestamp: Long
    ) = SetLog(
        exerciseId = exerciseId,
        repsCompleted = reps,
        durationSeconds = 0,
        timestamp = timestamp,
        sessionDate = sessionDate
    )

    /** One confirmed set of a timer exercise: the elapsed hold seconds, no reps. */
    private fun timerSet(
        exerciseId: String,
        elapsedSeconds: Int,
        sessionDate: String,
        timestamp: Long
    ) = SetLog(
        exerciseId = exerciseId,
        repsCompleted = 0,
        durationSeconds = elapsedSeconds,
        timestamp = timestamp,
        sessionDate = sessionDate
    )

    @Test
    fun emptyHistoryProducesNoObservations() = runBlocking {
        val observations = adapter(FakeProgressDao()).observations()

        assertTrue("no persisted history means no sessions to observe", observations.isEmpty())
    }

    // ---------------------------------------------------------------- completed day

    @Test
    fun completedDayProducesACompletedObservation() = runBlocking {
        val day = 1 // STRENGTH_A
        val planned = plannedExercises(day)
        val setLogs = planned.flatMap { exercise ->
            (1..exercise.sets).map { index ->
                repSet(exercise.id, exercise.reps, dateFor(day), startedAt + index * 60_000L)
            }
        }
        val dao = FakeProgressDao(
            userProgress = listOf(completedDay(1, day)),
            setLogs = setLogs
        )

        val observations = adapter(dao).observations()

        assertEquals("one completed day is one observation", 1, observations.size)
        val observation = observations.single()
        assertEquals(SessionOutcome.COMPLETED, observation.outcome)
        assertEquals(1, observation.cycleNumber)
        assertEquals(day, observation.programDay)
        assertEquals(planned.size, observation.plannedExercises)
        assertEquals("every planned exercise was performed", planned.size, observation.completedExercises)
    }

    /**
     * A completed day whose confirmed-set rows are gone — every set rolled back, or history written by a
     * build that did not log sets — is still a session the user finished. Persistence keeps two
     * independent facts (the day-level completion and the set rows), and the completion stamp is the only
     * instant it establishes for such a session. The reading must not be "not started": that would make a
     * finished session vanish from the history, and because the whole history is read as one list it
     * would take every other session's observation down with it (`COMPLETED requires the moment the
     * session started`).
     */
    @Test
    fun aCompletedDayWithoutConfirmedSetRowsIsStillItsCompletedObservation() = runBlocking {
        val day = 1 // STRENGTH_A
        val dao = FakeProgressDao(userProgress = listOf(completedDay(1, day)))

        val observation = adapter(dao).observations().single()

        assertEquals(SessionOutcome.COMPLETED, observation.outcome)
        assertEquals(
            "the completion stamp is the whole extent persistence proves for it",
            finishedAt,
            observation.startedAt
        )
        assertEquals(finishedAt, observation.finishedAt)
        assertTrue("no set row means no observed work", observation.actualWork.isZero)
        assertEquals(0, observation.completedExercises)
        assertEquals(
            "the plan still describes what the session prescribed",
            plannedExercises(day).size,
            observation.plannedExercises
        )
    }

    @Test
    fun completedDayCarriesTheCompletionStampAsFinishedAt() = runBlocking {
        val day = 1
        val planned = plannedExercises(day)
        val dao = FakeProgressDao(
            userProgress = listOf(completedDay(1, day, finishedAt = finishedAt)),
            setLogs = planned.map { repSet(it.id, it.reps, dateFor(day), startedAt) }
        )

        val observation = adapter(dao).observations().single()

        assertEquals(
            "UserProgress.completionDate is the finished stamp the day-level source records",
            finishedAt,
            observation.finishedAt
        )
        assertEquals(
            "the first confirmed set is the earliest moment persistence establishes",
            startedAt,
            observation.startedAt
        )
    }

    /**
     * The contract this pins: `completedExercises` counts planned exercises with OBSERVED work, so
     * a day the completion source marks done still reports exactly the exercises actually logged.
     */
    @Test
    fun completedDayWithUnloggedExercisesReportsOnlyLoggedExercisesAsCompleted() = runBlocking {
        val day = 1
        val planned = plannedExercises(day)
        val performed = planned.take(2) // two of the planned exercises performed...
        val setLogs = performed.map { repSet(it.id, it.reps, dateFor(day), startedAt) }
        val dao = FakeProgressDao(
            userProgress = listOf(completedDay(1, day)),
            setLogs = setLogs
        )

        val observation = adapter(dao).observations().single()

        assertEquals(planned.size, observation.plannedExercises)
        assertEquals(
            "a completed day is not proof every exercise was performed",
            performed.size,
            observation.completedExercises
        )
        assertEquals(
            "the completion flag is trusted for the outcome, not for the per-exercise count",
            SessionOutcome.COMPLETED,
            observation.outcome
        )
        assertEquals(performed.size, observation.exerciseResults.count { it.completedSets > 0 })
    }

    /**
     * The adaptive contract depends on this: a workout can be COMPLETED with exposure well below
     * `1.0`, because completion and full performance are different facts from different sources.
     */
    @Test
    fun completedDayStillReportsActualWorkBelowThePlannedAmount() = runBlocking {
        val day = 1
        val planned = plannedExercises(day)
        // Every exercise logged once instead of the prescribed three sets.
        val dao = FakeProgressDao(
            userProgress = listOf(completedDay(1, day)),
            setLogs = planned.map { repSet(it.id, it.reps, dateFor(day), startedAt) }
        )

        val observation = adapter(dao).observations().single()

        assertEquals(SessionOutcome.COMPLETED, observation.outcome)
        assertTrue(
            "a completed day may carry less actual than planned work",
            observation.actualWork.reps < observation.plannedWork.reps
        )
        assertEquals(
            "only the logged sets count",
            planned.size,
            observation.actualWork.sets
        )
        planned.forEach { exercise ->
            val result = observation.exerciseResults.single { it.exerciseId == exercise.id }
            assertEquals(exercise.sets, result.plannedSets)
            assertEquals(1, result.completedSets)
            if (exercise.isTimerBased) {
                // A confirmed hold that elapsed nothing records zero seconds, so the exercise is
                // below full exposure even on a completed day — the same unit-channel rule.
                assertEquals(
                    "a timer exercise's exposure is the elapsed fraction of the plan",
                    0.0,
                    result.exposure,
                    1e-9
                )
            } else {
                assertEquals(
                    "a repetition exercise's exposure is the completed fraction of the plan",
                    1.0 / exercise.sets,
                    result.exposure,
                    1e-9
                )
            }
        }
    }

    // ---------------------------------------------------------------- partial day

    @Test
    fun partialDayWithSomeSetsIsPartialWithObservedWork() = runBlocking {
        val day = 1
        val planned = plannedExercises(day)
        val setLogs = planned.take(2).map { repSet(it.id, it.reps, dateFor(day), startedAt) }
        val dao = FakeProgressDao(setLogs = setLogs)

        val observation = adapter(dao).observations().single()

        assertEquals(
            "no day-level completion row means the completion condition is not established",
            SessionOutcome.PARTIAL,
            observation.outcome
        )
        assertEquals(startedAt, observation.startedAt)
        assertNull(
            "persistence has no abandonment timestamp to report, so none is invented",
            observation.finishedAt
        )
        assertTrue("PARTIAL carries observed work", observation.actualWork.reps > 0)
        assertEquals(2, observation.completedExercises)
    }

    @Test
    fun partialDayKeepsPlannedAndActualWorkInSeparateUnitChannels() = runBlocking {
        val day = 1
        val planned = plannedExercises(day)
        val performed = planned.take(2)
        val dao = FakeProgressDao(
            setLogs = performed.map { repSet(it.id, it.reps, dateFor(day), startedAt) }
        )

        val observation = adapter(dao).observations().single()

        // A partial session of repetition exercises reports no observed timed work, and its planned
        // timed channel holds only the workout's timer exercises (day 1 prescribes a timed hang).
        assertEquals(0, observation.actualWork.durationSeconds)
        assertTrue(observation.actualWork.reps < observation.plannedWork.reps)
        assertTrue(observation.plannedWork.reps > 0)
    }

    // ---------------------------------------------------------------- not-started day

    @Test
    fun plannedDayWithNoActivityIsNotStarted() = runBlocking {
        val day = 1
        val dao = FakeProgressDao(
            programDayStates = listOf(
                // The calendar sync's mark of a past workout day the user did not complete.
                ProgramDayState(cycleNumber = 1, programDay = day, isWorkoutDay = true, isMissed = true)
            )
        )

        val observation = adapter(dao).observations().single()

        assertEquals(SessionOutcome.NOT_STARTED, observation.outcome)
        assertNull(observation.startedAt)
        assertNull(observation.finishedAt)
        assertEquals(0, observation.completedExercises)
        assertTrue("NOT_STARTED reports zero actual work", observation.actualWork.isZero)
        assertEquals(
            "the plan still describes what the session prescribed",
            plannedExercises(day).size,
            observation.plannedExercises
        )
        // A session that never ran has a plan with no performed sets.
        observation.exerciseResults.forEach { result ->
            assertEquals(0, result.completedSets)
            assertEquals(0.0, result.exposure, 1e-9)
        }
    }

    /**
     * A rest day is not a workout opportunity: it prescribes no exercises and the adaptive
     * pipeline has nothing to observe from it.
     */
    @Test
    fun restDayIsNotAWorkoutOpportunity() = runBlocking {
        val day = 4 // REST
        val dao = FakeProgressDao(
            programDayStates = listOf(
                ProgramDayState(cycleNumber = 1, programDay = day, isWorkoutDay = false, isCompleted = true)
            )
        )

        val observations = adapter(dao).observations()

        assertTrue("a rest day yields no session observation", observations.isEmpty())
    }

    // ---------------------------------------------------------------- repetition vs timer channels

    @Test
    fun repetitionExerciseActualRepsComeFromSetLog() = runBlocking {
        val day = 1
        val planned = plannedExercises(day)
        val repExercise = planned.first { !it.isTimerBased }
        // The user performed 3 reps of a set prescribed at 12 — the observed amount is 3.
        val dao = FakeProgressDao(
            setLogs = listOf(repSet(repExercise.id, reps = 3, sessionDate = dateFor(day), timestamp = startedAt))
        )

        val result = adapter(dao).observations().single().exerciseResults.single { it.exerciseId == repExercise.id }

        assertEquals(repExercise.sets, result.plannedSets)
        assertEquals(1, result.completedSets)
        assertEquals(
            "the observed reps are what the SetLog recorded, not the prescribed target",
            3,
            result.completedReps
        )
        assertEquals(
            "planned reps are the prescribed target across all planned sets",
            repExercise.reps * repExercise.sets,
            result.plannedReps
        )
        assertEquals(0, result.completedDurationSeconds)
        assertEquals(0, result.plannedDurationSeconds)
    }

    @Test
    fun timerExerciseActualDurationComesFromSetLog() = runBlocking {
        val day = 2 // MOBILITY — timer exercises present
        val planned = plannedExercises(day)
        val timerExercise = planned.firstOrNull { it.isTimerBased } ?: return@runBlocking
        // A 30 s hold ended after 10 s: the session records 10 s of elapsed work.
        val dao = FakeProgressDao(
            setLogs = listOf(timerSet(timerExercise.id, elapsedSeconds = 10, sessionDate = dateFor(day), timestamp = startedAt))
        )

        val result = adapter(dao).observations().single().exerciseResults.single { it.exerciseId == timerExercise.id }

        assertEquals(timerExercise.sets, result.plannedSets)
        assertEquals(1, result.completedSets)
        assertEquals(
            "the observed duration is the elapsed seconds the SetLog recorded, not the plan",
            10,
            result.completedDurationSeconds
        )
        assertEquals(
            "planned duration is the prescribed hold across all planned sets",
            timerExercise.durationSeconds * timerExercise.sets,
            result.plannedDurationSeconds
        )
        assertEquals(
            "a timed hold carries no repetition work",
            0,
            result.completedReps
        )
        assertEquals(0, result.plannedReps)
    }

    @Test
    fun multipleSetsOfOneExerciseAggregateToSessionTotals() = runBlocking {
        val day = 1
        val planned = plannedExercises(day)
        val exercise = planned.first { !it.isTimerBased }
        // Three confirmed sets at the prescribed target — the full exercise performed.
        val dao = FakeProgressDao(
            setLogs = (1..3).map { index ->
                repSet(exercise.id, exercise.reps, dateFor(day), startedAt + index * 60_000L)
            }
        )

        val result = adapter(dao).observations().single().exerciseResults.single { it.exerciseId == exercise.id }

        assertEquals(3, result.completedSets)
        assertEquals(exercise.reps * 3, result.completedReps)
        assertEquals(exercise.reps * exercise.sets, result.plannedReps)
        assertEquals(1.0, result.exposure, 1e-9)
    }

    @Test
    fun missingSetsAreNeverInventedAsCompletedWork() = runBlocking {
        val day = 1
        val planned = plannedExercises(day)
        val exercise = planned.first { !it.isTimerBased }
        // One confirmed set of three prescribed: nothing may infer the other two.
        val dao = FakeProgressDao(
            setLogs = listOf(repSet(exercise.id, exercise.reps, dateFor(day), startedAt))
        )

        val result = adapter(dao).observations().single().exerciseResults.single { it.exerciseId == exercise.id }

        assertEquals(
            "a set with no SetLog row was not performed",
            1,
            result.completedSets
        )
        assertEquals(exercise.reps, result.completedReps)
        assertEquals(exercise.reps * exercise.sets, result.plannedReps)
        assertEquals(
            "the two unlogged sets keep the exercise below full exposure",
            1.0 / exercise.sets,
            result.exposure,
            1e-9
        )
    }

    // ---------------------------------------------------------------- ordering / determinism

    @Test
    fun observationsAreOrderedCycleThenDayChronologically() = runBlocking {
        val laterDay = 3
        val earlierDay = 1
        val dao = FakeProgressDao(
            setLogs = listOf(
                repSet(plannedExercises(laterDay).first().id, 5, dateFor(laterDay), startedAt),
                repSet(plannedExercises(earlierDay).first().id, 5, dateFor(earlierDay), startedAt)
            )
        )

        val observations = adapter(dao).observations()

        assertEquals(2, observations.size)
        assertEquals(
            "cycle/day ascending is the stable order, regardless of storage order",
            listOf(earlierDay, laterDay),
            observations.map { it.programDay }
        )
    }

    @Test
    fun observationsAreDeterministicForIdenticalPersistedInput() = runBlocking {
        val day = 1
        val setLogs = plannedExercises(day).map { repSet(it.id, it.reps, dateFor(day), startedAt) }
        val dao = FakeProgressDao(setLogs = setLogs)

        val first = adapter(dao).observations()
        val second = adapter(dao).observations()

        assertEquals(first, second)
    }

    @Test
    fun separateSessionsOnDifferentDatesAreNotDeduplicated() = runBlocking {
        val firstDay = 1
        val secondDay = 3 // a different workout day, so a distinct planned opportunity
        val firstExercise = plannedExercises(firstDay).first()
        val secondExercise = plannedExercises(secondDay).first()
        val dao = FakeProgressDao(
            setLogs = listOf(
                repSet(firstExercise.id, firstExercise.reps, dateFor(firstDay), startedAt),
                repSet(secondExercise.id, secondExercise.reps, dateFor(secondDay), startedAt + 2 * 86_400_000L)
            )
        )

        val observations = adapter(dao).observations()

        assertEquals(
            "two distinct session dates are two distinct sessions, even for one exercise each",
            2,
            observations.size
        )
    }

    // ---------------------------------------------------------------- historical identity

    /**
     * The planned workout is read for the historical day, so a later library/generator change that
     * alters the same day's plan does not silently rewrite what an old session prescribed.
     */
    @Test
    fun plannedWorkoutIsResolvedForTheHistoricalDay() = runBlocking {
        val day = 1
        val planned = plannedExercises(day)
        val dao = FakeProgressDao(
            setLogs = planned.map { repSet(it.id, it.reps, dateFor(day), startedAt) }
        )

        val observation = adapter(dao).observations().single()

        assertEquals(
            planned.map { it.id },
            observation.exerciseResults.map { it.exerciseId }
        )
        planned.forEach { exercise ->
            val result = observation.exerciseResults.single { it.exerciseId == exercise.id }
            assertEquals(exercise.sets, result.plannedSets)
            assertEquals(
                if (exercise.isTimerBased) 0 else exercise.reps * exercise.sets,
                result.plannedReps
            )
            assertEquals(
                if (exercise.isTimerBased) exercise.durationSeconds * exercise.sets else 0,
                result.plannedDurationSeconds
            )
        }
    }

    /**
     * The only cross-session identifier persistence exposes is the calendar position, so the
     * observation's session identity is (cycle, day) and the date is derived, not stored as a
     * session id. A session whose day cannot be established from persistence is not emitted —
     * see the adapter's documented limitation.
     */
    @Test
    fun setLogForAnExerciseThePlanDoesNotContainContributesNoWork() = runBlocking {
        val day = 1
        val planned = plannedExercises(day)
        val performed = planned.first()
        val dao = FakeProgressDao(
            userProgress = listOf(completedDay(1, day)),
            setLogs = listOf(
                repSet(performed.id, performed.reps, dateFor(day), startedAt),
                // A row for an exercise the planned workout does not contain.
                repSet("unknown_exercise", 5, dateFor(day), startedAt + 60_000L)
            )
        )

        val observation = adapter(dao).observations().single()

        // The session is real — its completion source and its planned exercises are all provable —
        // but the stray row prescribes nothing, so it inflates neither work nor exercise counts.
        assertEquals(SessionOutcome.COMPLETED, observation.outcome)
        assertEquals(planned.size, observation.plannedExercises)
        assertEquals(
            "only the planned exercise counts as performed",
            1,
            observation.completedExercises
        )
        assertEquals(planned.size, observation.exerciseResults.size)
        assertTrue(
            "the stray row adds no observed work",
            observation.actualWork.reps <= performed.reps
        )
    }
}
