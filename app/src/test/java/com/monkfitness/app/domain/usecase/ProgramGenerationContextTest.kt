package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.ExercisePreference
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
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
 * The production generation context — what a generation pass is planned against, and just as importantly
 * what it is *not* planned against.
 *
 * [ProgramHistoryGenerationContext] is the one production owner of the plain signals, and every test here
 * fails for a **specific fabrication**, because the failure modes are all silent: a context that reads
 * plausible and produces a slightly different plan for the same user, with nothing crashing and every
 * planner assertion still green. The fabrications closed: exposure or load counted from sessions, slots or
 * repetitions; absence turned into `0`; `currentExerciseId` promoted to an adaptive preference; recovery
 * derived from elapsed time.
 *
 * The suite asserts its refusals as first-class facts, because the signals cannot all be filled from
 * production: a neutral field is the documented answer, not a missing test, and a later stage filling one
 * will have to *revise* these tests rather than find them silently passing.
 */
class ProgramGenerationContextTest {

    @Test
    fun theContextIsScopedToTheDraftsOwnProgram() = runBlocking {
        val read = RecordingHistory(
            mapOf(
                PROGRAM to listOf(session("alpha", minutes = 10, exercises = listOf(performed("squats")))),
                OTHER to listOf(session("beta", minutes = 20, exercises = listOf(performed("deadlifts"))))
            )
        )
        val context = ProgramHistoryGenerationContext(read)

        val preferences = context.preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "the history read is the draft's own Program and nothing else — ProgramId is the scope of " +
                "the facts, not a filter applied afterwards",
            listOf(PROGRAM),
            read.requested
        )
        assertEquals(
            "and the answer is that Program's performed exercise",
            listOf("squats"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun anotherProgramsHistoryNeverEntersTheRequest() = runBlocking {
        // The other Program's history is *present and non-empty*, so this is not an absence test: a source
        // reading the wrong Program would produce a real, plausible, wrong list.
        val read = RecordingHistory(
            mapOf(
                PROGRAM to listOf(session("alpha", minutes = 10, exercises = listOf(performed("squats")))),
                OTHER to listOf(
                    session("beta", minutes = 20, exercises = listOf(performed("deadlifts"), performed("rows"))),
                    session("beta2", minutes = 30, exercises = listOf(performed("pullups")))
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        listOf("deadlifts", "rows", "pullups").forEach { foreign ->
            assertTrue(
                "'$foreign' belongs to another Program and must never reach this Program's request",
                foreign !in preferences.recentExerciseIds
            )
        }
        assertEquals(
            "and the list is exactly this Program's own",
            listOf("squats"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun anAllProgramsAggregationIsNeverAssembled() = runBlocking {
        // The failure closed here is not "another Program leaks in" but "everything is summed": a source
        // with no scope at all would pass the *previous* test if this Program's own list were a prefix, so
        // the fixture makes the foreign list strictly larger and asserts the total count too.
        val read = RecordingHistory(
            mapOf(
                PROGRAM to listOf(session("alpha", minutes = 10, exercises = listOf(performed("squats")))),
                OTHER to (1..5).map { position ->
                    session("beta$position", minutes = 40 + position, exercises = listOf(performed("burpee$position")))
                }
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "one Program's performed exercises, and not every Program's",
            1,
            preferences.recentExerciseIds.size
        )
    }

    @Test
    fun aNewProgramGetsTheNeutralContextAndIsNeverReadAtAll() = runBlocking {
        val read = RecordingHistory(mapOf(PROGRAM to listOf(session("alpha", exercises = listOf(performed("squats"))))))
        val context = ProgramHistoryGenerationContext(read)

        // A draft with no ProgramId will CREATE a Program: nothing to scope a read to, so the answer is
        // neutral and no read is issued at all — which is what makes this more than a filtered result.
        val preferences = context.preferencesFor(ProgramEditorDraft(name = "New", mode = com.monkfitness.app.domain.program.ProgramMode.GENERATED))

        assertEquals(
            "a Program that does not exist has no history, so no history is read",
            emptyList<ProgramId>(),
            read.requested
        )
        assertEquals(
            "and the context is exactly the neutral representation — empty lists, empty maps, UNKNOWN. " +
                "Not one synthetic zero, because a zero would be a claim that the Program was loaded " +
                "with nothing when in fact nothing is known about it",
            GenerationPreferences.NONE,
            preferences
        )
        assertEquals(
            "recovery in particular stays UNKNOWN, which §14's own vocabulary says is a fact rather " +
                "than a missing value",
            RecoveryContext.UNKNOWN,
            preferences.recovery
        )
    }

    @Test
    fun aProgramWithNoHistoryStatesAbsenceRatherThanZero() = runBlocking {
        // Distinct from the new-Program case: here the Program EXISTS and was read, and the answer is
        // still neutral. `recentExposureByFocus` is the field this is about — an entry per focus reading
        // `0` would claim the Program trained each focus as often as nothing.
        val read = RecordingHistory(mapOf(PROGRAM to emptyList()))
        val context = ProgramHistoryGenerationContext(read)

        val preferences = context.preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "the Program was read — an existing Program with no sessions is not the same fact as a " +
                "Program that does not exist",
            listOf(PROGRAM),
            read.requested
        )
        assertEquals(
            "absence is stated as absence",
            GenerationPreferences.NONE,
            preferences
        )
        Focus.entries.forEach { focus ->
            assertTrue(
                "'$focus' has no stated exposure; a 0 entry would be a fabricated measurement",
                focus !in preferences.recentExposureByFocus
            )
            assertTrue(
                "'$focus' has no stated load; a 0 entry would be a fabricated measurement",
                focus !in preferences.recentLoadByFocus
            )
        }
    }

    @Test
    fun recentExercisesAreOrderedMostRecentFirst() = runBlocking {
        val history = listOf(
            session("s1", minutes = 10, exercises = listOf(performed("squats"), performed("rows"))),
            session("s2", minutes = 20, exercises = listOf(performed("pushups"))),
            session("s3", minutes = 30, exercises = listOf(performed("lunges"), performed("plank")))
        )

        val preferences = ProgramHistoryGenerationContext(RecordingHistory(mapOf(PROGRAM to history)))
            .preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "§9's recency axis is read by index, so 'most recent first' is the whole claim: the " +
                "newest session's exercises first, in the order the user met them, then older ones",
            listOf("lunges", "plank", "pushups", "squats", "rows"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun aRepeatedExerciseKeepsItsMostRecentPositionAndIsStatedOnce() = runBlocking {
        // `GenerationPreferences` refuses a duplicate in `recentExerciseIds` ("most recent first is an
        // order, so an exercise appears at most once"), so the source de-duplicates — keeping the *latest*
        // sighting, not the first it walked past.
        val history = listOf(
            session("s1", minutes = 10, exercises = listOf(performed("squats"), performed("rows"))),
            session("s2", minutes = 20, exercises = listOf(performed("squats")))
        )

        val preferences = ProgramHistoryGenerationContext(RecordingHistory(mapOf(PROGRAM to history)))
            .preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "the most recent use of an exercise is the one §9's recency axis reads, stated once",
            listOf("squats", "rows"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun skippedAndUnperformedOccurrencesDoNotBecomeExposure() = runBlocking {
        // Three shapes of "not performed": an explicitly skipped exercise, an occurrence presented but
        // confirming nothing, and content planned but never started (absent from the session graph, so it
        // cannot appear at all).
        val history = listOf(
            session(
                "s1",
                minutes = 10,
                exercises = listOf(
                    performed("squats"),
                    SessionExercise(
                        sessionExerciseId = SessionExerciseId("se-skipped"),
                        programExerciseId = ProgramExerciseId("pe-skipped"),
                        exerciseId = "burpees",
                        prescription = RepPrescription(listOf(10)),
                        skipped = true
                    ),
                    SessionExercise(
                        sessionExerciseId = SessionExerciseId("se-nothing"),
                        programExerciseId = ProgramExerciseId("pe-nothing"),
                        exerciseId = "mountain_climbers",
                        prescription = RepPrescription(listOf(10))
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(RecordingHistory(mapOf(PROGRAM to history)))
            .preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "only the occurrence with a confirmed set is exposure: §12 and ExposureObservation's own " +
                "invariant both say a skipped exercise or a missed slot produces no observation " +
                "rather than a zero one",
            listOf("squats"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun aSkippedOccurrenceIsNotAZeroExposure() = runBlocking {
        val history = listOf(
            session(
                "s1",
                exercises = listOf(
                    SessionExercise(
                        sessionExerciseId = SessionExerciseId("se-skipped"),
                        programExerciseId = ProgramExerciseId("pe-skipped"),
                        exerciseId = "burpees",
                        prescription = RepPrescription(listOf(10)),
                        skipped = true
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(RecordingHistory(mapOf(PROGRAM to history)))
            .preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "a session of nothing but skips states nothing at all — not an empty-string entry, not a " +
                "zero, not a placeholder exercise",
            GenerationPreferences.NONE,
            preferences
        )
    }

    @Test
    fun aCancelledAttemptStillCountsWhatWasActuallyPerformed() = runBlocking {
        // A cancelled attempt's partial work is preserved in the session, and only real performed
        // exposures count. Filtering on the session's status would invent a rule no domain statement owns;
        // the per-occurrence rule is the one that exists.
        val cancelled = session(
            "s1",
            minutes = 10,
            status = SessionStatus.CANCELLED,
            exercises = listOf(performed("squats"))
        )

        val preferences = ProgramHistoryGenerationContext(RecordingHistory(mapOf(PROGRAM to listOf(cancelled))))
            .preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "the sets were confirmed, so they happened",
            listOf("squats"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun exposureIsNotDerivedFromTheSessionCount() = runBlocking {
        // The forbidden derivation: one workout training two focuses must contribute to two entries, so a
        // session count can never be the answer — and a source counting sessions would produce a
        // NON-empty map here, which is what this asserts against.
        val history = (1..4).map { position ->
            session("s$position", minutes = 10 * position, exercises = listOf(performed("squats")))
        }

        val preferences = ProgramHistoryGenerationContext(RecordingHistory(mapOf(PROGRAM to history)))
            .preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "four sessions were performed and the exposure map is still EMPTY: the unit is focus " +
                "assignments, and nothing in the session graph states which focus any performed " +
                "occurrence was assigned to",
            emptyMap<Focus, Int>(),
            preferences.recentExposureByFocus
        )
    }

    @Test
    fun loadIsNotDerivedFromRepetitions() = runBlocking {
        // 24 confirmed sets across the history, and the load map is still empty. Every forbidden move is
        // visible in that number: summing repetitions, converting seconds, collapsing a LoadProfile to a
        // scalar, or attributing sets to a focus.
        val history = (1..3).map { position ->
            session(
                "s$position",
                minutes = 10 * position,
                exercises = listOf(performed("squats", sets = position + 1))
            )
        }

        val preferences = ProgramHistoryGenerationContext(RecordingHistory(mapOf(PROGRAM to history)))
            .preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "a count of performed sets exists here, and it is still not stated: §17's recent load is " +
                "sets BY FOCUS, and there is no focus to attribute a set to",
            emptyMap<Focus, Int>(),
            preferences.recentLoadByFocus
        )
    }

    @Test
    fun noCrossDimensionScalarConversionReachesTheRequest() = runBlocking {
        // One session holding BOTH a repetition and a time prescription: the two are incomparable
        // measurements, so any single figure over them is meaningless. The fixture makes the trap concrete
        // rather than asserting a general principle.
        val mixed = session(
            "s1",
            exercises = listOf(
                performed("squats", sets = 3),
                SessionExercise(
                    sessionExerciseId = SessionExerciseId("se-timed"),
                    programExerciseId = ProgramExerciseId("pe-timed"),
                    exerciseId = "plank",
                    prescription = com.monkfitness.app.domain.prescription.TimePrescription(listOf(45)),
                    results = listOf(
                        SetResult(SetLogId("set-timed-1"), 1, completedReps = 0, durationSeconds = 45, performedAt = BASE)
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(RecordingHistory(mapOf(PROGRAM to listOf(mixed))))
            .preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "repetitions and seconds are reported side by side and never added together, so nothing " +
                "here can become one load figure",
            listOf("squats", "plank"),
            preferences.recentExerciseIds
        )
        assertEquals(
            "and neither dimension reached a focus-keyed map",
            emptyMap<Focus, Int>(),
            preferences.recentLoadByFocus
        )
    }

    @Test
    fun aStoredFamiliesCurrentExerciseIsNeverReadAsAGenerationPreference() = runBlocking {
        // `FamilyProgressionState.currentExerciseId` is stored and means "the exercise the family is
        // currently on" — family- and revision-scoped. Turning it into a generation preference would need a
        // contract that does not exist, so the outcome is asserted: the source never reads it.
        //
        // The structural form of the claim lives in `ProgramGenerationContextArchitectureTest`; what
        // matters here is that a history rich enough to suggest a preference still yields an empty list.
        val history = (1..6).map { position ->
            session("s$position", minutes = 10 * position, exercises = listOf(performed("bench_press")))
        }

        val preferences = ProgramHistoryGenerationContext(RecordingHistory(mapOf(PROGRAM to history)))
            .preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "§9's adaptive-preference level stays empty: no stored adaptive state is read as a " +
                "selection preference, so a family's current exercise cannot leak into generation",
            emptyList<String>(),
            preferences.adaptivePreferredExerciseIds
        )
        assertEquals(
            "recency is the only level filled, and it is the honest one",
            listOf("bench_press"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun recoveryIsNeverDerivedFromElapsedTime() = runBlocking {
        // A gap of two hours between the last session and now, and recovery is still UNKNOWN. There is no
        // clock in the source at all, which makes this structural rather than merely asserted — but the
        // behavioural claim is stated here too, because a "helpful" cooldown rule is exactly the change
        // that arrives with a test attached.
        val history = listOf(session("s1", minutes = 10, exercises = listOf(performed("squats"))))

        val preferences = ProgramHistoryGenerationContext(RecordingHistory(mapOf(PROGRAM to history)))
            .preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "there is no production-owned recovery context for a generation request, so the honest " +
                "value is §14's own UNKNOWN — never FAVORABLE and never CAUTIOUS derived from a gap",
            RecoveryContext.UNKNOWN,
            preferences.recovery
        )
    }

    /**
     * A draft full of user-authored, pinned elements still states what the user **built**, not what they
     * would prefer a generator to pick: reading plan content as a wish list is a different meaning the
     * codebase does not state.
     *
     * The test plants such a draft AND an explicitly stated preference, then asserts the request holds
     * **exactly** the stated preference — the plan's content appears nowhere in it. Reading plan content
     * adds ids and fails; dropping the stated preference fails the other half.
     */
    @Test
    fun noUserPreferenceIsInventedFromTheDraftsOwnPlanContent() = runBlocking {
        val draft = draftOf(PROGRAM).copy(
            name = "Edited",
            days = listOf(
                ProgramDay(
                    programDayId = ProgramDayId("day-1"),
                    position = 1,
                    type = ProgramDayType.TRAINING,
                    name = null,
                    exercises = listOf(
                        ProgramExercise(
                            programExerciseId = ProgramExerciseId("element-1"),
                            exerciseId = "squats",
                            prescription = RepPrescription(listOf(10, 10, 10)),
                            origin = ProgramExerciseOrigin.USER_AUTHORED,
                            isPinned = true
                        )
                    )
                )
            ),
            preferredExercises = ExercisePreference.of("pullups")
        )

        val preferences = ProgramHistoryGenerationContext(
            RecordingHistory(mapOf(PROGRAM to listOf(session("s1", exercises = listOf(performed("dips"))))))
        ).preferencesFor(draft)

        assertEquals(
            "§9's top level is the preference the user stated and nothing else: the draft's pinned and " +
                "user-authored element is plan content reconciliation preserves (§7), never a rank",
            listOf("pullups"),
            preferences.userPreferredExerciseIds
        )
        assertTrue(
            "and the plan's own exercise is absent from the preference, not merely ranked lower",
            "squats" !in preferences.userPreferredExerciseIds
        )
    }

    @Test
    fun theContextIsReadFromTheOneRepositoryReadAndNeverFromADao() = runBlocking {
        // Two facts measured together because they are one decision: the read is the repository's own
        // `sessionsOfProgram`, and it is asked exactly once per call.
        val read = RecordingHistory(mapOf(PROGRAM to listOf(session("s1", exercises = listOf(performed("squats"))))))
        val context = ProgramHistoryGenerationContext(read)

        context.preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "one read per context, so Generate and Preview over unchanged state see the same facts",
            1,
            read.calls
        )
    }

    @Test
    fun equalInputStateProducesEqualPreferences() = runBlocking {
        // Same Program, same stored history, two separate reads, one answer — and no cache that could make
        // this pass for the wrong reason, since each call goes to the read again.
        val history = listOf(
            session("s1", minutes = 10, exercises = listOf(performed("squats"))),
            session("s2", minutes = 20, exercises = listOf(performed("rows")))
        )
        val read = RecordingHistory(mapOf(PROGRAM to history))
        val context = ProgramHistoryGenerationContext(read)

        val first = context.preferencesFor(draftOf(PROGRAM))
        val second = context.preferencesFor(draftOf(PROGRAM))

        assertEquals("the same stored state yields the same stated facts", first, second)
        assertEquals("and the read really happened twice, so the equality is not a cache", 2, read.calls)
    }

    private companion object {

        val PROGRAM = ProgramId("program-under-test")
        val OTHER = ProgramId("program-somebody-else")

        /** The reference moment every fixture fact is stamped with. */
        val BASE: Instant = Instant.parse("2026-09-21T07:00:00Z")

        /** A draft editing [programId] — an existing Program, so the history read is scoped. */
        fun draftOf(programId: ProgramId) = ProgramEditorDraft(
            programId = programId,
            baseRevisionId = RevisionId("revision-1"),
            name = "Existing",
            mode = com.monkfitness.app.domain.program.ProgramMode.GENERATED
        )

        /**
         * A stored session: complete, snapshot-bound and legal, so the fixture cannot drift into a shape the
         * repository would refuse.
         */
        fun session(
            key: String,
            minutes: Int = 10,
            status: SessionStatus = SessionStatus.COMPLETED,
            exercises: List<SessionExercise>
        ): WorkoutSession {
            val startedAt = BASE.plusSeconds(minutes * 60L)
            val sessionId = SessionId("session-$key")
            val slotId = SlotId("slot-$key")
            val programId = PROGRAM
            val revisionId = RevisionId("revision-1")
            val finishedAt = startedAt.plusSeconds(45 * 60L)
            return WorkoutSession(
                sessionId = sessionId,
                slotId = slotId,
                programId = programId,
                revisionId = revisionId,
                snapshot = WorkoutSessionSnapshot(
                    sessionId = sessionId,
                    capturedAt = startedAt,
                    workout = EffectiveWorkout(
                        slotId = slotId,
                        programId = programId,
                        revisionId = revisionId,
                        plannedFor = LocalDate.parse("2026-09-21"),
                        computedAt = startedAt,
                        exercises = exercises.map { occurrence ->
                            EffectiveExercise(occurrence.programExerciseId, occurrence.exerciseId, occurrence.prescription)
                        }
                    )
                ),
                status = status,
                startedAt = startedAt,
                finishedAt = if (status == SessionStatus.IN_PROGRESS) null else finishedAt,
                exercises = exercises
            )
        }

        /** An occurrence the user actually worked: [sets] confirmed sets of repetitions. */
        fun performed(exerciseId: String, sets: Int = 2): SessionExercise = SessionExercise(
            sessionExerciseId = SessionExerciseId("se-$exerciseId-$sets"),
            programExerciseId = ProgramExerciseId("pe-$exerciseId-$sets"),
            exerciseId = exerciseId,
            prescription = RepPrescription(List(sets) { 10 }),
            results = (1..sets).map { index ->
                SetResult(
                    setLogId = SetLogId("set-$exerciseId-$sets-$index"),
                    setIndex = index,
                    completedReps = 10,
                    durationSeconds = 0,
                    performedAt = BASE
                )
            }
        )
    }
}

/**
 * A [GenerationSessionHistory] that answers from a stated map and **records what it was asked for**.
 *
 * The recording is the point: "scoped to one Program" is only measurable if the read can be observed, and
 * asserting only the returned list could not tell a correctly scoped read from one that read everything
 * and happened to return the right answer.
 */
private class RecordingHistory(
    private val byProgram: Map<ProgramId, List<WorkoutSession>>
) : GenerationSessionHistory {

    /** Every Program this history was asked about, in call order. */
    val requested = mutableListOf<ProgramId>()

    /** How many times the read was performed. */
    var calls: Int = 0
        private set

    override suspend fun sessionsOfProgram(programId: ProgramId): List<WorkoutSession> {
        requested += programId
        calls += 1
        return byProgram[programId].orEmpty()
    }
}