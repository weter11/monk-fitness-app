package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraph
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import com.monkfitness.app.domain.workout.EffectiveWorkout
import com.monkfitness.app.domain.workout.SessionExercise
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import com.monkfitness.app.domain.workout.WorkoutSessionSnapshot
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P27's production context **over real storage** — the wiring the composition root performs, measured
 * end to end through the actual SQLite engine.
 *
 * `ProgramGenerationContextTest` states what the source *decides* from a stated history. This suite
 * states the two claims that only storage can decide:
 *
 *  * the read is the **repository's own** `sessionsOfProgram`, so the exercises it reports are the ones
 *    a completed workout actually recorded — reconstructed from `workout_session`, `session_snapshot`,
 *    `session_snapshot_exercise`, `session_exercise` and `program_set_log`, not from a live plan;
 *  * the **Program scope survives the database**: two Programs with genuinely different performed
 *    exercises live in one file, and neither one's history reaches the other's request.
 *
 * The second claim is the one a fixture cannot make. Answering from a map in memory, "another
 * Program's history never enters" is a statement about a lookup; here it is a statement about a
 * `WHERE programId = :programId` over real rows, which is where a scope actually has to hold.
 *
 * Two rigs share one database file, exactly as §30 step 14's own suites do: the second rig does not
 * migrate, so a value that comes back was stored rather than remembered.
 */
class ProgramGenerationContextStorageTest {

    private val rig = ProgramDataAccessRig("alpha")
    private val other = ProgramDataAccessRig("beta", supplied = rig.database)

    /** The production wiring, verbatim: the repository read behind the context source's port. */
    private fun contextOver(repository: com.monkfitness.app.data.repository.WorkoutSessionRepository) =
        ProgramHistoryGenerationContext(
            sessions = GenerationSessionHistory { programId -> repository.sessionsOfProgram(programId) }
        )

    private val alpha = ProgramId(ProgramGraphFixture.programId("alpha"))
    private val beta = ProgramId(ProgramGraphFixture.programId("beta"))

    /** A draft editing [programId], so the read is scoped rather than skipped. */
    private fun draftOf(programId: ProgramId) = ProgramEditorDraft(
        programId = programId,
        baseRevisionId = RevisionId(ProgramGraphFixture.revisionId("alpha")),
        name = "Existing",
        mode = ProgramMode.GENERATED
    )

    // ------------------------------------------------------------------ the real read, end to end

    @Test
    fun theContextReportsTheExercisesACompletedWorkoutActuallyRecorded() = runBlocking {
        rig.createGraph()
        rig.startSessionWithSets(sessionOf("alpha", rig.graph.slotFor(1), ALPHA_WORK.first()))

        // Read through a FRESH repository, so nothing can be answered from a cache in this process.
        val preferences = contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))

        assertEquals(
            "the performed exercise comes back out of `session_exercise` joined with its confirmed " +
                "`program_set_log` rows — the repository's own assembly, not a plan read",
            listOf("squats", "rows"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun twoProgramsInOneDatabaseNeverSeeEachOthersHistory() = runBlocking {
        rig.createGraph()
        other.createGraph()
        rig.startSessionWithSets(sessionOf("alpha", rig.graph.slotFor(1), ALPHA_WORK.first()))
        other.startSessionWithSets(sessionOf("beta", other.graph.slotFor(1), BETA_WORK))

        // Both read through their own repository instance over the SAME file.
        val alphaPreferences = contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))
        val betaPreferences = contextOver(other.freshSessionRepository()).preferencesFor(draftOf(beta))

        assertEquals(
            "this Program's own performed exercise",
            listOf("squats", "rows"),
            alphaPreferences.recentExerciseIds
        )
        assertEquals(
            "and the other Program's own, which is a different exercise",
            listOf("deadlifts"),
            betaPreferences.recentExerciseIds
        )
        assertTrue(
            "the neighbour's exercise is nowhere in this Program's answer",
            "deadlifts" !in alphaPreferences.recentExerciseIds
        )
        listOf("deadlifts").forEach { foreign ->
            assertTrue(
                "'$foreign' belongs to the other Program and must never reach this one's request",
                foreign !in alphaPreferences.recentExerciseIds
            )
        }
        assertEquals(
            "and the database really holds both sessions, so the scoping above is not an empty-table " +
                "artefact",
            1,
            rig.freshSessionRepository().sessionsOfProgram(alpha).size
        )
        assertEquals(
            "— for either of them",
            1,
            other.freshSessionRepository().sessionsOfProgram(beta).size
        )
    }

    @Test
    fun theOrderingSurvivesTheRoundTripThroughStorage() = runBlocking {
        rig.createGraph()
        // Three sessions on three different slots, stored and read back in start order — the order the
        // repository's own `ORDER BY startedAt ASC, sessionId ASC` guarantees.
        ALPHA_WORK.forEachIndexed { index, work ->
            rig.startSessionWithSets(
                sessionOf(
                    key = "alpha$index",
                    slot = rig.graph.slotFor(index + 1),
                    work = work,
                    startedAt = BASE.plusSeconds(index * 3600L)
                )
            )
        }

        val preferences = contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))

        assertEquals(
            "most recent first, as the domain states it — and read back from storage rather than from " +
                "the order the test happened to build them in",
            listOf("lunges", "plank", "pushups", "squats", "rows"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun aSkippedOccurrenceStoredAsSkippedIsNotReported() = runBlocking {
        rig.createGraph()
        // `skipped` is a stored column, so this is the real shape: the user pressed "skip", and the row
        // says so. Nothing was confirmed, so nothing is exposure.
        rig.startSessionWithSets(
            SessionExerciseFixture.sessionOf(
                key = "alpha",
                slot = rig.graph.slotFor(1),
                work = listOf(
                    SessionExercise(
                        sessionExerciseId = SessionExerciseId("se-skip"),
                        programExerciseId = ProgramExerciseId("pe-skip"),
                        exerciseId = "burpees",
                        prescription = RepPrescription(listOf(10)),
                        skipped = true
                    ),
                    SessionExercise(
                        sessionExerciseId = SessionExerciseId("se-did"),
                        programExerciseId = ProgramExerciseId("pe-did"),
                        exerciseId = "squats",
                        prescription = RepPrescription(listOf(10, 10)),
                        results = listOf(
                            SetResult(SetLogId("set-did-1"), 1, 10, 0, BASE),
                            SetResult(SetLogId("set-did-2"), 2, 10, 0, BASE)
                        )
                    )
                )
            )
        )

        val preferences = contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))

        assertEquals(
            "the skipped row is stored and is still not exposure — §12 states that a skipped exercise " +
                "observed nothing",
            listOf("squats"),
            preferences.recentExerciseIds
        )
    }

    // ------------------------------------------------------------------ the neutral answers, over storage

    @Test
    fun aProgramWithStoredRowsButNoPerformedSetGetsTheNeutralContext() = runBlocking {
        rig.createGraph()
        // A session that was started and presented two exercises, and confirmed nothing. This is the
        // case that separates "no history" from "history that observed no work", and it is exactly where
        // a fabricated zero would appear.
        rig.startSessionWithSets(
            SessionExerciseFixture.sessionOf(
                key = "alpha",
                slot = rig.graph.slotFor(1),
                work = listOf(
                    SessionExercise(
                        sessionExerciseId = SessionExerciseId("se-a"),
                        programExerciseId = ProgramExerciseId("pe-a"),
                        exerciseId = "squats",
                        prescription = RepPrescription(listOf(10))
                    ),
                    SessionExercise(
                        sessionExerciseId = SessionExerciseId("se-b"),
                        programExerciseId = ProgramExerciseId("pe-b"),
                        exerciseId = "rows",
                        prescription = RepPrescription(listOf(10))
                    )
                )
            )
        )
        assertEquals(
            "the session really is stored, with its occurrences",
            2,
            rig.freshSessionRepository().sessionsOfProgram(alpha).single().exercises.size
        )

        val preferences = contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))

        assertEquals(
            "presented-but-unperformed is absence, not a zero and not a placeholder exercise",
            GenerationPreferences.NONE,
            preferences
        )
    }

    @Test
    fun theFiveUnfillableSignalsStayNeutralOverRealStorageToo() = runBlocking {
        rig.createGraph()
        rig.startSessionWithSets(sessionOf("alpha", rig.graph.slotFor(1), ALPHA_WORK.first()))

        val preferences = contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))

        assertEquals("no user preference source exists", emptyList<String>(), preferences.userPreferredExerciseIds)
        assertEquals("no adaptive preference is read", emptyList<String>(), preferences.adaptivePreferredExerciseIds)
        assertEquals(
            "exposure stays empty even though sessions were performed and read: the unit is focus " +
                "assignments, and no stored row states which focus an occurrence was assigned to",
            emptyMap<Focus, Int>(),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "load stays empty for the same reason — sets exist, but there is no focus to attribute them to",
            emptyMap<Focus, Int>(),
            preferences.recentLoadByFocus
        )
        assertEquals(
            "and recovery stays §14's UNKNOWN: no production-owned recovery context exists to read",
            RecoveryContext.UNKNOWN,
            preferences.recovery
        )
    }

    @Test
    fun theContextSourceWritesNothing() = runBlocking {
        rig.createGraph()
        rig.startSessionWithSets(sessionOf("alpha", rig.graph.slotFor(1), ALPHA_WORK.first()))
        val before = tableCounts(rig)

        contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))

        assertEquals(
            "every table is exactly as it was: P27 adds no persistence, and a context read that wrote " +
                "a row would be a generation pass mutating storage (§33)",
            before,
            tableCounts(rig)
        )
    }

    @Test
    fun generateAndPreviewOverTheSameStoredStateSeeTheSameFacts() = runBlocking {
        rig.createGraph()
        rig.startSessionWithSets(sessionOf("alpha", rig.graph.slotFor(1), ALPHA_WORK.first()))
        val repository = rig.freshSessionRepository()

        val generated = serviceOver(repository).generate(draftOf(alpha), emptySet())
        val previewed = serviceOver(repository).preview(draftOf(alpha), emptySet())

        assertEquals(
            "the plan a user is shown a preview of is the plan Generate produces",
            (generated as ProgramGenerationResult.Generated).edit.plan,
            (previewed as ProgramGenerationResult.Generated).edit.plan
        )
    }

    // ------------------------------------------------------------------ helpers

    /** The service as the composition root wires it, over the real repository read. */
    private fun serviceOver(repository: com.monkfitness.app.data.repository.WorkoutSessionRepository) =
        ProgramGenerationService(
            catalogue = SHIPPED_EXERCISE_CATALOGUE,
            focusSource = ProductionFocusClassification,
            ids = com.monkfitness.app.domain.program.DraftIdSource { "id-${nextId()}" },
            context = contextOver(repository)
        )

    private var idCounter = 0

    private fun nextId(): String = "p27-${idCounter++}"

    /**
     * Every table's row count, as a map.
     *
     * Read through the database rather than through a repository, so a write that bypassed the
     * repositories would still be visible — and the sweep is over the **whole** schema rather than the
     * handful the session owns, because "writes nothing" means nothing.
     */
    private fun tableCounts(
        target: ProgramDataAccessRig
    ): Map<String, Int> = target.database.tableNames().sorted().associateWith { table ->
        target.database.count(table)
    }

    private companion object {

        val BASE: Instant = Instant.parse("2026-09-21T07:00:00Z")

        /** The three sessions' performed work, oldest first. */
        val ALPHA_WORK: List<List<SessionExercise>> = listOf(
            listOf(reps("squats", sets = 2, scope = "a1"), reps("rows", sets = 2, scope = "a1")),
            listOf(reps("pushups", sets = 3, scope = "a2")),
            listOf(reps("lunges", sets = 2, scope = "a3"), timed("plank", seconds = 45, scope = "a3"))
        )

        /**
         * The neighbour's Program performs a *different* exercise from this one's. Sharing an id would
         * make the cross-Program assertion vacuous: a source that read the wrong Program would return
         * the same list and pass.
         */
        val BETA_WORK: List<SessionExercise> = listOf(reps("deadlifts", sets = 2, scope = "b1"))

        /**
         * Occurrence identities are prefixed by [scope] because `session_exercise` is keyed by
         * `sessionExerciseId` alone: a fixture that reused one identity across two sessions would
         * collide on the primary key instead of measuring anything.
         */
        fun reps(exerciseId: String, sets: Int, scope: String, at: Instant = BASE): SessionExercise =
            SessionExercise(
                sessionExerciseId = SessionExerciseId("se-$scope-$exerciseId"),
                programExerciseId = ProgramExerciseId("pe-$scope-$exerciseId"),
                exerciseId = exerciseId,
                prescription = RepPrescription(List(sets) { 10 }),
                results = (1..sets).map { index ->
                    SetResult(SetLogId("set-$scope-$exerciseId-$index"), index, 10, 0, at)
                }
            )

        fun timed(exerciseId: String, seconds: Int, scope: String, at: Instant = BASE): SessionExercise =
            SessionExercise(
                sessionExerciseId = SessionExerciseId("se-$scope-$exerciseId"),
                programExerciseId = ProgramExerciseId("pe-$scope-$exerciseId"),
                exerciseId = exerciseId,
                prescription = TimePrescription(listOf(seconds)),
                results = listOf(SetResult(SetLogId("set-$scope-$exerciseId-1"), 1, 0, seconds, at))
            )

        /** A complete, legal session for [slot] presenting [work]. */
        fun sessionOf(
            key: String,
            slot: com.monkfitness.app.domain.program.WorkoutSlot,
            work: List<SessionExercise>,
            startedAt: Instant = BASE
        ): WorkoutSession = SessionExerciseFixture.sessionOf(key, slot, work, startedAt)
    }
}

/** One stored session, built to satisfy every `WorkoutSession` invariant so the fixture cannot drift. */
internal object SessionExerciseFixture {

    fun sessionOf(
        key: String,
        slot: com.monkfitness.app.domain.program.WorkoutSlot,
        work: List<SessionExercise>,
        startedAt: Instant = Instant.parse("2026-09-21T07:00:00Z")
    ): WorkoutSession {
        val sessionId = SessionId("session-$key")
        return WorkoutSession(
            sessionId = sessionId,
            slotId = slot.slotId,
            programId = slot.programId,
            revisionId = slot.revisionId,
            snapshot = WorkoutSessionSnapshot(
                sessionId = sessionId,
                capturedAt = startedAt,
                workout = EffectiveWorkout(
                    slotId = slot.slotId,
                    programId = slot.programId,
                    revisionId = slot.revisionId,
                    plannedFor = LocalDate.parse("2026-09-21"),
                    computedAt = startedAt,
                    exercises = work.map { occurrence ->
                        EffectiveExercise(occurrence.programExerciseId, occurrence.exerciseId, occurrence.prescription)
                    }
                )
            ),
            status = SessionStatus.COMPLETED,
            startedAt = startedAt,
            finishedAt = startedAt.plusSeconds(2700),
            exercises = work
        )
    }
}
