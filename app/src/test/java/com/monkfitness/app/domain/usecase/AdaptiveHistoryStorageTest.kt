package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.data.repository.WorkoutSessionRepository
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P29's historical focus **over real storage** — the two columns, on a real SQLite engine, through the
 * composition root's own wiring.
 *
 * `AdaptiveHistoryContextTest` states what the context source decides from a stated history. This suite
 * states the three claims that only storage can decide, and each is a claim a fixture cannot make:
 *
 *  * **the focus survives the round trip, exactly.** §11's determinism rule says one representation per
 *    value: a `Focus` stored by name and read back must be the same enum value, not a lookalike, and not
 *    reordered or defaulted on the way back.
 *  * **absence survives the round trip as absence.** A row written before the column existed — or a
 *    user-authored element — must read back as `null`, because that is the statement *"no focus was ever
 *    recorded"* and generation then reads it as absence rather than as a zero. This is the claim a
 *    `focus ?: Focus.PUSH` in a mapper would quietly destroy, and it cannot be observed anywhere except
 *    here.
 *  * **history survives a later revision.** A workout's stored focus is frozen; a *new* revision that
 *    presents the same exercise under a different focus must not re-label the old snapshot. This is the
 *    §19 invariant that the second column exists for, and it is only meaningful against real rows,
 *    because the whole failure mode is a stale value surviving in a table.
 *
 * Two rigs share one database file, exactly as §30 step 14's own suites do: the second does not migrate,
 * so a value that comes back was stored rather than remembered.
 */
class AdaptiveHistoryStorageTest {

    private val rig = ProgramDataAccessRig("p29")
    private val other = ProgramDataAccessRig("p29-other", supplied = rig.database)

    private val alpha = ProgramId(ProgramGraphFixture.programId("p29"))
    private val beta = ProgramId(ProgramGraphFixture.programId("p29-other"))

    @After
    fun close() {
        rig.close()
        other.close()
    }

    /** The production wiring, verbatim: the repository read behind the context source's port. */
    private fun contextOver(repository: WorkoutSessionRepository) = ProgramHistoryGenerationContext(
        sessions = GenerationSessionHistory { programId -> repository.sessionsOfProgram(programId) }
    )

    private fun draftOf(programId: ProgramId) = ProgramEditorDraft(
        programId = programId,
        baseRevisionId = RevisionId(ProgramGraphFixture.revisionId("p29")),
        name = "Existing",
        mode = ProgramMode.GENERATED
    )

    // ------------------------------------------------------------------ the round trip

    @Test
    fun theRecordedFocusSurvivesTheRoundTripThroughStorage() = runBlocking {
        rig.createGraph()
        rig.startSessionWithSets(
            sessionOf(
            "rt",
            rig.graph.slotFor(1),
            listOf(reps("pushups", sets = 3)),
            listOf(Focus.PUSH)
        )
        )

        // Read through a FRESH repository, so nothing can be answered from a cache in this process.
        val stored = rig.freshSessionRepository().sessionsOfProgram(alpha).single()

        assertEquals(
            "the focus the snapshot froze is the focus that comes back out of " +
                "`session_snapshot_exercise.focus` — stored by name, read back as the same enum value",
            listOf(Focus.PUSH),
            stored.snapshot.workout.exercises.map { it.focus }
        )
        assertEquals(
            "and the context built over that read states it",
            mapOf(Focus.PUSH to 3),
            contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha)).recentLoadByFocus
        )
        // The raw column, so the claim is about storage and not about a mapper that might invent it.
        assertEquals(
            "and the column itself holds the vocabulary's name — by name, never by ordinal, so a renamed " +
                "enum value is detected rather than silently re-pointing stored history",
            "PUSH",
            rig.database.scalar(
                "SELECT `focus` FROM `session_snapshot_exercise` WHERE `sessionId` = ?",
                "session-rt"
            )
        )
    }

    @Test
    fun everyFocusInTheVocabularySurvivesTheRoundTrip() = runBlocking {
        // One session presenting the whole vocabulary, one element each. §8's seven values is a small
        // enough set to state exhaustively, which matters here: a mapper that collapsed two values, or
        // truncated one, would pass a single-focus fixture and fail this one.
        val work = Focus.entries.map { focus -> reps("exercise-$focus", sets = 1, tag = focus.name) }
        rig.createGraph()
        rig.startSessionWithSets(sessionOf("all", rig.graph.slotFor(1), work, Focus.entries.toList()))

        val stored = rig.freshSessionRepository().sessionsOfProgram(alpha).single()

        assertEquals(
            "all seven focuses, in the order they were presented, each stored and read back exactly",
            Focus.entries.toList(),
            stored.snapshot.workout.exercises.map { it.focus }
        )
        assertEquals(
            "and each contributes exactly its own one confirmed set",
            Focus.entries.associateWith { 1 },
            contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha)).recentLoadByFocus
        )
    }

    @Test
    fun aGeneratedElementsFocusSurvivesTheSaveIntoThePlan() = runBlocking {
        // The **plan** column, over real storage — the link P29's row 3 mutation removes.
        //
        // Without this, dropping `focus` from `ProgramExercise.toEntity` is invisible: nothing downstream
        // of a *saved* revision re-reads that column, because the snapshot copy is taken from the session
        // boundary's own composition. So the column could be written as NULL forever and every snapshot
        // test would still pass. The claim is that the assignment is persisted where it is owned, and read
        // back as the same enum value by the plan repository.
        val assigned = listOf(
            Focus.PUSH, Focus.PULL, Focus.LEGS, Focus.CORE, Focus.MOBILITY
        )
        val revision = rig.graph.revision.copy(
            days = rig.graph.revision.days.map { day ->
                day.copy(
                    exercises = day.exercises.mapIndexed { index, element ->
                        element.copy(focus = assigned[index % assigned.size])
                    }
                )
            }
        )
        rig.programRepository.createProgram(rig.graph.program, revision, rig.graph.slots)

        val stored = rig.freshPlanRepository().revisionById(RevisionId(ProgramGraphFixture.revisionId("p29")))
        assertEquals(
            "every element's recorded focus is stored on the element that owns the assignment and read " +
                "back exactly — the column is the reason a snapshot can be composed later at all",
            revision.days.flatMap { day -> day.exercises.map { it.focus } },
            stored?.days?.flatMap { day -> day.exercises.map { it.focus } }
        )
        // And the raw row, so the claim is about the column rather than about a mapper that might invent it.
        assertEquals(
            "and the column itself holds the vocabulary's name",
            Focus.PUSH.name,
            rig.database.scalar(
                "SELECT `focus` FROM `program_exercise` WHERE `programExerciseId` = ? " +
                    "ORDER BY `programExerciseId` LIMIT 1",
                rig.graph.revision.days.first().exercises.first().programExerciseId.value
            )
        )
    }

    @Test
    fun anAbsentFocusIsStoredAsNullAndReadsBackAsAbsence() = runBlocking {
        rig.createGraph()
        rig.startSessionWithSets(
            sessionOf(
                "absent",
                rig.graph.slotFor(1),
                listOf(reps("pushups", sets = 2), reps("pullups", sets = 2)),
                listOf(null, Focus.PULL)
            )
        )

        assertEquals(
            "the element the generator never assigned stores NULL, not an empty string and not a token",
            null,
            rig.database.scalar(
                "SELECT `focus` FROM `session_snapshot_exercise` WHERE `sessionId` = ? AND " +
                    "`programExerciseId` = ?",
                "session-absent",
                "pe-absent-pushups"
            )
        )
        val stored = rig.freshSessionRepository().sessionsOfProgram(alpha).single()
        assertNull(
            "and it reads back as absence, which is a different statement from 'trains PUSH'",
            stored.snapshot.workout.exercises.first().focus
        )
        val preferences = contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))
        assertEquals(
            "so the context attributes only the occurrence that has a recorded focus",
            mapOf(Focus.PULL to 2),
            preferences.recentLoadByFocus
        )
        assertTrue(
            "and PUSH is absent from the map rather than present with a 0 — §12's rule, measured on the " +
                "row that actually stores the absence",
            Focus.PUSH !in preferences.recentLoadByFocus && Focus.PUSH !in preferences.recentExposureByFocus
        )
    }

    @Test
    fun aRowThatPredatesTheColumnReadsAsAbsenceRatherThanADefault() = runBlocking {
        // The upgrade case, produced by writing rows the way a pre-P29 device would have: no `focus` in
        // the INSERT at all. This is exactly what `MIGRATION_16_17` leaves behind for an existing row,
        // and the claim is that the column's own no-default means it stays absent — never that a
        // migration backfilled a training claim onto history nobody recorded.
        //
        // The rows are written directly rather than through the repository, because no repository can
        // express "an INSERT that omits a column" — the whole point is the *absence* of the column, not
        // a `null` value passed to it. Foreign keys are enforced by this engine, so the parent rows are
        // written first and under ids this Program owns.
        rig.createGraph()
        rig.database.exec(
            "INSERT INTO `workout_session` (" +
                "`sessionId`, `slotId`, `programId`, `revisionId`, `status`, `startedAt`) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            "legacy-session",
            ProgramGraphFixture.slotId("p29", 1),
            ProgramGraphFixture.programId("p29"),
            ProgramGraphFixture.revisionId("p29"),
            "COMPLETED",
            1_700_000_000_000L
        )
        rig.database.exec(
            "INSERT INTO `session_snapshot` (" +
                "`sessionId`, `capturedAt`, `plannedFor`, `computedAt`, `appliedAdjustmentIds`) " +
                "VALUES (?, ?, ?, ?, ?)",
            "legacy-session", 1_700_000_000_000L, "2026-09-21", 1_700_000_000_000L, ""
        )
        rig.database.exec(
            "INSERT INTO `session_snapshot_exercise` (" +
                "`sessionId`, `programExerciseId`, `position`, `exerciseId`, " +
                "`prescriptionDimension`, `perSetTargets`) VALUES (?, ?, ?, ?, ?, ?)",
            "legacy-session", "pe-legacy", 1, "squats", "REP_BASED", "10,10"
        )

        assertEquals(
            "the snapshot row was written with no focus column, so it stores NULL — a default would have " +
                "stamped a focus onto a workout that never recorded one",
            null,
            rig.database.scalar(
                "SELECT `focus` FROM `session_snapshot_exercise` WHERE `sessionId` = ?",
                "legacy-session"
            )
        )
        // And the plan side, which is where the value is read before it is ever presented. A
        // `USER_AUTHORED` element is the everyday case of a row with no assignment: a manual program's
        // element and a user's own edit inside a generated one both mean exactly this.
        // Position 4 continues the graph's own 1..3 numbering: a revision numbers its days 1..n with no
        // gaps, so a fixture that jumps to 99 would be refused as invalid persisted data — correctly.
        rig.database.exec(
            "INSERT INTO `program_day` (`programDayId`, `revisionId`, `position`, `type`) " +
                "VALUES (?, ?, ?, ?)",
            "legacy-day", ProgramGraphFixture.revisionId("p29"), 4, "TRAINING"
        )
        rig.database.exec(
            "INSERT INTO `program_exercise` (" +
                "`programExerciseId`, `programDayId`, `position`, `exerciseId`, " +
                "`prescriptionDimension`, `perSetTargets`, `origin`, `isPinned`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            "pe-legacy-plan", "legacy-day", 1, "squats", "REP_BASED", "10", "USER_AUTHORED", 0
        )
        assertEquals(
            "and a plan element written without the column also stores NULL, which is what a manual " +
                "program's element and a user-authored one both mean",
            null,
            rig.database.scalar(
                "SELECT `focus` FROM `program_exercise` WHERE `programExerciseId` = ?",
                "pe-legacy-plan"
            )
        )
        // And the read-back is absence, all the way into the domain — which is the claim a
        // `focus ?: Focus.PUSH` in a mapper would quietly destroy, and which is only observable here.
        assertNull(
            "the stored plan element reads back as `focus = null`, not as a default",
            rig.freshPlanRepository()
                .revisionById(RevisionId(ProgramGraphFixture.revisionId("p29")))
                ?.days?.first { it.programDayId.value == "legacy-day" }
                ?.exercises?.single()?.focus
        )
    }

    // ------------------------------------------------------------------ history is not relabelled

    @Test
    fun aNewerRevisionCannotRelabelAnOlderSession() = runBlocking {
        // The §19 invariant, on real rows, which is the only place it can be falsified: an old session's
        // stored focus stays whatever the snapshot froze, while a *new* session on the newer revision
        // records its own. Both live in one file, so a join-to-current-revision implementation would
        // report the newer focus for both — and this test would fail with a real, plausible wrong map.
        // Two different start instants, because "the newer revision's session" is only meaningful if the
        // engine really does order them: two fixtures sharing one instant would make the ordering an
        // accident of the session id, and this test would prove nothing about which came later.
        rig.createGraph()
        rig.startSessionWithSets(
            sessionOf(
                "old",
                rig.graph.slotFor(1),
                listOf(reps("pushups", sets = 3, tag = "old")),
                listOf(Focus.PUSH),
                startedAt = BASE
            )
        )
        rig.startSessionWithSets(
            sessionOf(
                "new",
                rig.graph.slotFor(2),
                listOf(reps("pushups", sets = 3, tag = "later")),
                listOf(Focus.LEGS),
                startedAt = BASE.plusSeconds(86_400)
            )
        )

        val stored = rig.freshSessionRepository().sessionsOfProgram(alpha)
        assertEquals(
            "both sessions are really stored",
            2,
            stored.size
        )
        assertEquals(
            "and each keeps the focus its own snapshot froze — asserted per session rather than as one " +
                "ordered list, because the repository returns sessions in start order and which one is " +
                "'first' is not the claim under test",
            mapOf(
                "session-new" to Focus.LEGS,
                "session-old" to Focus.PUSH
            ),
            stored.associate { session ->
                session.sessionId.value to session.snapshot.workout.exercises.single().focus
            }
        )
        assertEquals(
            "and the older workout really is older, so the assertion above is not reading the same row twice",
            listOf("session-old", "session-new"),
            stored.sortedBy { session -> session.startedAt }.map { session -> session.sessionId.value }
        )
        assertEquals(
            "so the context reports both focuses separately, one assignment each",
            mapOf(Focus.PUSH to 1, Focus.LEGS to 1),
            contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha)).recentExposureByFocus
        )
    }

    // ------------------------------------------------------------------ scope survives the database

    @Test
    fun twoProgramsInOneDatabaseNeverSeeEachOthersFocusHistory() = runBlocking {
        // Both Programs performed real work under different focuses, so a leak produces a plausible wrong
        // map rather than an obviously wrong one. This is a `WHERE programId = :programId` claim, and a
        // `WHERE` is where a scope actually has to hold.
        rig.createGraph()
        other.createGraph()
        rig.startSessionWithSets(
            sessionOf("alpha", rig.graph.slotFor(1), listOf(reps("pushups", sets = 2, tag = "a")), listOf(Focus.PUSH))
        )
        other.startSessionWithSets(
            sessionOf("beta", other.graph.slotFor(1), listOf(reps("squats", sets = 5, tag = "b")), listOf(Focus.LEGS))
        )

        val mine = contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))
        val theirs = contextOver(other.freshSessionRepository()).preferencesFor(draftOf(beta))

        assertEquals("this Program's own focus facts", mapOf(Focus.PUSH to 2), mine.recentLoadByFocus)
        assertEquals("and the neighbour's, which is a different focus entirely", mapOf(Focus.LEGS to 5), theirs.recentLoadByFocus)
        assertTrue(
            "the neighbour's focus is nowhere in this Program's answer",
            Focus.LEGS !in mine.recentLoadByFocus && Focus.LEGS !in mine.recentExposureByFocus
        )
        assertEquals(
            "and the database really holds both sessions, so the scoping above is not an empty-table " +
                "artefact",
            1,
            rig.freshSessionRepository().sessionsOfProgram(alpha).size
        )
    }

    // ------------------------------------------------------------------ the neutral answers, over storage

    @Test
    fun theUnfilledSignalsStayNeutralOverRealStorageToo() = runBlocking {
        rig.createGraph()
        rig.startSessionWithSets(
            sessionOf("neutral", rig.graph.slotFor(1), listOf(reps("pushups", sets = 3)), listOf(Focus.PUSH))
        )

        val preferences = contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))

        assertEquals("no adaptive preference is read", emptyList<String>(), preferences.adaptivePreferredExerciseIds)
        assertEquals(
            "recovery stays §14's UNKNOWN: no production-owned generation-scoped recovery context exists " +
                "to read, and elapsed time would be a substitute rather than the fact",
            RecoveryContext.UNKNOWN,
            preferences.recovery
        )
    }

    @Test
    fun theContextSourceStillWritesNothing() = runBlocking {
        rig.createGraph()
        rig.startSessionWithSets(
            sessionOf("nowrite", rig.graph.slotFor(1), listOf(reps("pushups", sets = 2)), listOf(Focus.PUSH))
        )
        val before = tableCounts()

        contextOver(rig.freshSessionRepository()).preferencesFor(draftOf(alpha))

        assertEquals(
            "every table is exactly as it was: a context read that wrote a row would be a generation pass " +
                "mutating storage (§33), and a *repair* write would be worse — it would invent the focus " +
                "for exactly the rows that never had one",
            before,
            tableCounts()
        )
    }

    @Test
    fun generateAndPreviewOverTheSameStoredStateSeeTheSameFocusFacts() = runBlocking {
        rig.createGraph()
        rig.startSessionWithSets(
            sessionOf("same", rig.graph.slotFor(1), listOf(reps("pushups", sets = 3)), listOf(Focus.PUSH))
        )
        val repository = rig.freshSessionRepository()

        val generated = serviceOver(repository).generate(draftOf(alpha), emptySet())
        val previewed = serviceOver(repository).preview(draftOf(alpha), emptySet())

        assertEquals(
            "the plan a user is shown a preview of is the plan Generate produces — one snapshot per " +
                "operation, and equal stored state means equal context",
            (generated as ProgramGenerationResult.Generated).edit.plan,
            (previewed as ProgramGenerationResult.Generated).edit.plan
        )
    }

    // ------------------------------------------------------------------ helpers

    /** The service as the composition root wires it, over the real repository read. */
    private fun serviceOver(repository: WorkoutSessionRepository) = ProgramGenerationService(
        catalogue = SHIPPED_EXERCISE_CATALOGUE,
        focusSource = ProductionFocusClassification,
        ids = com.monkfitness.app.domain.program.DraftIdSource { "id-${nextId()}" },
        context = contextOver(repository)
    )

    private var idCounter = 0

    private fun nextId(): String = "p29-${idCounter++}"

    /** Every table's row count, as a map, over the whole schema. */
    private fun tableCounts(): Map<String, Int> = rig.database.tableNames().sorted()
        .associateWith { table -> rig.database.count(table) }

    private companion object {

        val BASE: Instant = Instant.parse("2026-09-21T07:00:00Z")

        /**
         * One performed occurrence of [sets] sets, presented under [focus].
         *
         * `tag` distinguishes two occurrences of the same exercise, because §9 allows the repetition and
         * each occurrence is its own plan element with its own recorded focus.
         */
        fun reps(
            exerciseId: String,
            sets: Int,
            tag: String = "one",
            at: Instant = BASE
        ): SessionExercise {
            val programExerciseId = ProgramExerciseId("pe-$tag-$exerciseId")
            return SessionExercise(
                sessionExerciseId = SessionExerciseId("se-$tag-$exerciseId"),
                programExerciseId = programExerciseId,
                exerciseId = exerciseId,
                prescription = RepPrescription(List(sets) { 10 }),
                results = (1..sets).map { index ->
                    SetResult(SetLogId("set-$tag-$exerciseId-$index"), index, 10, 0, at)
                }
            )
        }

        /** One timed occurrence — used to prove seconds are never summed into the set count. */
        fun timed(exerciseId: String, tag: String = "one"): SessionExercise {
            val programExerciseId = ProgramExerciseId("pe-$tag-$exerciseId")
            return SessionExercise(
                sessionExerciseId = SessionExerciseId("se-$tag-$exerciseId"),
                programExerciseId = programExerciseId,
                exerciseId = exerciseId,
                prescription = TimePrescription(listOf(45, 60)),
                results = listOf(
                    SetResult(SetLogId("set-$tag-$exerciseId-1"), 1, 0, 45, BASE),
                    SetResult(SetLogId("set-$tag-$exerciseId-2"), 2, 0, 60, BASE)
                )
            )
        }

        /**
         * A complete, legal stored session presenting [work], with the focus each occurrence was
         * presented under.
         *
         * The focus is applied to the **snapshot element**, never to the occurrence — which is the
         * production shape and the reason the column exists on two tables rather than one.
         */
        fun sessionOf(
            key: String,
            slot: com.monkfitness.app.domain.program.WorkoutSlot,
            work: List<SessionExercise>,
            /** The focus each occurrence was presented under, in [work] order; `null` means none recorded. */
            focuses: List<Focus?>,
            startedAt: Instant = BASE
        ): WorkoutSession {
            require(focuses.size == work.size) {
                "every presented occurrence states the focus it was presented under, or that none was"
            }
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
                        exercises = work.mapIndexed { index, occurrence ->
                            EffectiveExercise(
                                programExerciseId = occurrence.programExerciseId,
                                exerciseId = occurrence.exerciseId,
                                prescription = occurrence.prescription,
                                focus = focuses[index]
                            )
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
}