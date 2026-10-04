package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.prescription.Prescription
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P29's **focus-keyed generation signals**, read from the historical record that P29 made readable.
 *
 * P27 left `recentExposureByFocus` and `recentLoadByFocus` neutral and recorded why. This suite is the
 * other side of that record: the two signals are filled, from a fact the session's own snapshot froze at
 * start, and every claim about *how* they are filled is stated as a test that would fail for a specific
 * wrong answer.
 *
 * ### The four ways to be wrong, and the test that closes each
 *
 * | wrong answer | test |
 * | --- | --- |
 * | count **workouts** instead of focus assignments | `exposureCountsAssignmentsAndNotWorkouts` |
 * | count **repetitions** instead of confirmed sets | `loadCountsConfirmedSetsAndNeverRepetitions` |
 * | count **seconds** as though they were sets | `timedWorkContributesItsConfirmedSetsAndNotItsSeconds` |
 * | turn an **absence** into a `0` | `anUnrecordedFocusIsAbsentAndNeverZero`, `aSkippedOccurrenceIsNotAZeroExposure` |
 *
 * ### And the three it is forbidden to do at all
 *
 * | fabrication | test |
 * | --- | --- |
 * | read the **current revision** for a past workout's focus | `aCurrentRevisionCannotRelabelAnOlderSession` |
 * | **classify** an exercise through the catalogue | `focusIsNeverReconstructedFromTheExerciseCatalogue` |
 * | promote `currentExerciseId` to an adaptive preference | `adaptivePreferenceRemainsNeutralAndIsNeverReadFromFamilyState` |
 *
 * The last one is worth stating plainly: P29 gave two of §8's six signals an owner, and the temptation
 * a stage faces after filling two is to go looking for the remaining four. That is exactly the wrong move
 * — a filled signal proves the *pipeline* works, not that the unfilled ones have an owner.
 */
class AdaptiveHistoryContextTest {

    // ------------------------------------------------------------------ exposure: assignments, not workouts

    @Test
    fun exposureCountsAssignmentsAndNotWorkouts() = runBlocking {
        // One workout that trained **two PUSH assignments and one PULL**, and a second that trained one
        // CORE. That shape is what makes the distinction measurable rather than asserted:
        //
        // ```text
        // counting assignments   {PUSH: 2, PULL: 1, CORE: 1}   ← 4 assignments over 2 workouts
        // counting workouts      {PUSH: 1, PULL: 1, CORE: 1}   ← one workout, one count per focus
        // ```
        //
        // The earlier form of this fixture (one PUSH per workout) produced the *same* map either way, so
        // it could not have failed a workout-counting implementation at all — a test that cannot fail the
        // bug it is written for is decoration. A focus trained twice inside one session is the smallest
        // case where the two readings diverge.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            performed("pushups", sets = 3, focus = Focus.PUSH),
                            performed("pushups_wide", sets = 2, focus = Focus.PUSH),
                            performed("pullups", sets = 3, focus = Focus.PULL)
                        )
                    ),
                    session(
                        "s2",
                        minutes = 30,
                        exercises = listOf(performed("plank", sets = 2, focus = Focus.CORE))
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "each performed occurrence contributes one to ITS OWN recorded focus, so two workouts " +
                "training four focuses in total state four assignments",
            mapOf(
                Focus.PUSH to 2,
                Focus.PULL to 1,
                Focus.CORE to 1
            ),
            preferences.recentExposureByFocus
        )
        assertFalse(
            "and this is emphatically not the workout count: one workout that trained PUSH twice is ONE " +
                "workout, so counting workouts would answer {PUSH: 1, PULL: 1, CORE: 1}. §8 allocates " +
                "exposure per focus assignment precisely so this number is not a workout tally",
            preferences.recentExposureByFocus == mapOf(Focus.PUSH to 1, Focus.PULL to 1, Focus.CORE to 1)
        )
    }

    @Test
    fun onePerformedOccurrenceContributesExactlyOneExposureHoweverManySetsItRan() = runBlocking {
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(performed("back-squat", sets = 5, focus = Focus.LEGS))
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "five confirmed sets are still ONE assignment — exposure counts focus assignments that were " +
                "executed, and it is the load map that counts sets",
            mapOf(Focus.LEGS to 1),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "while the load map counts the sets: 5, not 1 and not 5×10",
            mapOf(Focus.LEGS to 5),
            preferences.recentLoadByFocus
        )
    }

    @Test
    fun partialExecutionContributesOneExposureAndOnlyItsConfirmedSets() = runBlocking {
        // A prescribed-and-presented occurrence the user abandoned after two of three sets. The
        // occurrence IS exposure — it happened — and its load is exactly what was confirmed.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            performed("pull-up", sets = 2, prescribedSets = 4, focus = Focus.PULL)
                        )
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "a partially executed occurrence is exposure exactly once",
            mapOf(Focus.PULL to 1),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "and contributes only the two sets that were confirmed — never the three that were prescribed",
            mapOf(Focus.PULL to 2),
            preferences.recentLoadByFocus
        )
    }

    // ------------------------------------------------------------------ load: sets, in one dimension only

    @Test
    fun loadCountsConfirmedSetsAndNeverRepetitions() = runBlocking {
        // 4 sets of 12 repetitions. The load unit is a set (§10's `perSetTargets`, §19's `SetResult`
        // rows), so the answer is 4 — not 48, which is the same work counted in a different dimension.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            performed("barbell-row", sets = 4, repsPerSet = 12, focus = Focus.PULL)
                        )
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "load is a count of confirmed SETS. Summing repetitions would answer 48, which is a " +
                "cross-dimension conversion rather than a measurement",
            mapOf(Focus.PULL to 4),
            preferences.recentLoadByFocus
        )
    }

    @Test
    fun timedWorkContributesItsConfirmedSetsAndNotItsSeconds() = runBlocking {
        // A time-based prescription: 2 confirmed sets of 45 and 60 seconds. Seconds are never summed and
        // never converted — the unit is the set, and §10's dimensions are never compared with each other.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            timed("plank", secondsPerSet = listOf(45, 60), focus = Focus.CORE)
                        )
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "two confirmed timed sets contribute 2, not 105 seconds and not a load score",
            mapOf(Focus.CORE to 2),
            preferences.recentLoadByFocus
        )
        assertEquals(
            "and one assignment, because one occurrence is one assignment",
            mapOf(Focus.CORE to 1),
            preferences.recentExposureByFocus
        )
    }

    @Test
    fun loadAndExposureAreKeyedByEachOccurrencesOwnRecordedFocus() = runBlocking {
        // Two occurrences of the SAME exercise in one day, presented under two different focuses (§9
        // allows the repetition, and each occurrence is its own element with its own assignment). A
        // source that keyed by exercise id — the obvious shortcut — would collapse them.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            performed("grip-hold", sets = 2, focus = Focus.PUSH),
                            performed("grip-hold", sets = 3, focus = Focus.CORE, tag = "second")
                        )
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "each occurrence attributes its own sets to its own recorded focus",
            mapOf(Focus.PUSH to 2, Focus.CORE to 3),
            preferences.recentLoadByFocus
        )
        assertEquals(
            "and each is one assignment",
            mapOf(Focus.PUSH to 1, Focus.CORE to 1),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "while `recentExerciseIds` states the exercise once — recency is an order, not a count, and " +
                "§9's recency axis reads it by index",
            listOf("grip-hold"),
            preferences.recentExerciseIds
        )
    }

    // ------------------------------------------------------------------ what is NOT exposure

    @Test
    fun aSkippedOccurrenceIsNotAZeroExposure() = runBlocking {
        // `skipped` is the user's explicit "not this one" and is exactly equivalent to having no
        // results (§12). The snapshot *does* record a focus for this element — that is the trap: a
        // source that counted elements rather than performed occurrences would state an exposure.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            skipped("hip-thrust", focus = Focus.LEGS),
                            performed("leg-press", sets = 2, focus = Focus.LEGS)
                        )
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "only the performed occurrence counts: the skipped one has a recorded focus and no work",
            mapOf(Focus.LEGS to 1),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "and only its confirmed sets are load",
            mapOf(Focus.LEGS to 2),
            preferences.recentLoadByFocus
        )
    }

    @Test
    fun anOccurrenceWithNoConfirmedSetContributesNothingAtAll() = runBlocking {
        // Presented, never worked. This is the case that separates "no history" from "history that
        // observed no work", and it is exactly where a fabricated zero appears.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            presented("bench-press", focus = Focus.PUSH),
                            presented("cable-fly", focus = Focus.PULL)
                        )
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "two focuses were presented and neither was trained, so neither appears — not even as a 0",
            emptyMap<Focus, Int>(),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "and no load, for the same reason",
            emptyMap<Focus, Int>(),
            preferences.recentLoadByFocus
        )
        Focus.entries.forEach { focus ->
            assertFalse(
                "'$focus' appears in neither map, because absence produces no observation rather than " +
                    "a zero one",
                focus in preferences.recentExposureByFocus || focus in preferences.recentLoadByFocus
            )
        }
    }

    @Test
    fun aCancelledSessionWithConfirmedWorkStillContributesItsSets() = runBlocking {
        // §19 preserves the partial work a cancelled attempt recorded, so dropping it would invent a rule
        // about which attempts count that no domain statement owns. The session's own `status` is
        // deliberately not a filter.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        status = SessionStatus.CANCELLED,
                        exercises = listOf(performed("front-squat", sets = 2, focus = Focus.LEGS))
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "a cancelled attempt that confirmed real work is real exposure — §19 keeps the partial work",
            mapOf(Focus.LEGS to 1),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "and its confirmed sets are its load",
            mapOf(Focus.LEGS to 2),
            preferences.recentLoadByFocus
        )
    }

    @Test
    fun anInProgressSessionWithConfirmedWorkContributesToo() = runBlocking {
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        status = SessionStatus.IN_PROGRESS,
                        exercises = listOf(performed("lat-pulldown", sets = 1, focus = Focus.PULL))
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "a workout still running has confirmed a set, and that set is real",
            mapOf(Focus.PULL to 1),
            preferences.recentLoadByFocus
        )
    }

    // ------------------------------------------------------------------ absence is not a zero, and not a guess

    @Test
    fun anUnrecordedFocusIsAbsentAndNeverZero() = runBlocking {
        // The case this whole stage exists to keep honest: a performed occurrence whose snapshot recorded
        // NO focus — a manual program, a user-authored element, or a workout from before this column
        // existed. It is real performed work, and its focus is genuinely unknown.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            performed("barbell-curl", sets = 2, focus = null),
                            performed("dumbbell-curl", sets = 3, focus = Focus.PULL)
                        )
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "only the occurrence with a RECORDED focus contributes; the other is absent, not zero and " +
                "not reconstructed",
            mapOf(Focus.PULL to 1),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "and its sets alone are load",
            mapOf(Focus.PULL to 3),
            preferences.recentLoadByFocus
        )
        assertEquals(
            "while BOTH exercises still appear in the recency list — that signal needs no focus, and " +
                "dropping real performed work because a parallel field was empty would lose information " +
                "the session graph actually recorded",
            listOf("barbell-curl", "dumbbell-curl"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun aProgramWhoseHistoryRecordedNoFocusStatesBothMapsEmpty() = runBlocking {
        // A Program whose every occurrence predates the column. The whole vocabulary is absent — and the
        // answer is two empty maps, not seven zeros.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            performed("squats", sets = 3, focus = null),
                            performed("lunges", sets = 3, focus = null)
                        )
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "no focus was ever recorded, so exposure is empty — a per-focus 0 would claim the Program " +
                "trained each focus as often as nothing",
            emptyMap<Focus, Int>(),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "and load is empty for the same reason, despite six real confirmed sets having been performed",
            emptyMap<Focus, Int>(),
            preferences.recentLoadByFocus
        )
        assertEquals(
            "which is not the same fact as having performed nothing at all",
            listOf("squats", "lunges"),
            preferences.recentExerciseIds
        )
    }

    @Test
    fun focusIsNeverReconstructedFromTheExerciseCatalogue() = runBlocking {
        // `ProductionFocusClassification` knows what every shipped exercise trains, and it would produce a
        // confident answer for every occurrence below — including the ones whose focus was genuinely
        // never recorded. That is the reconstruction this stage forbids: it answers a different question
        // ("what does this exercise train?") with the name of a field that asks "which focus was this
        // occurrence assigned?", and an exercise that trains two focuses is not two assignments.
        //
        // The claim is behavioural, and it is the strongest available form: the catalogue *does*
        // classify these exercises, and the source still states absence. The exercises are chosen to make
        // the trap sharp — `pullups` is classified `PULL`, and `burpees` is classified
        // `PUSH + LEGS + CONDITIONING`, which is precisely the case where a reconstruction would either
        // drop two focuses or invent three assignments.
        assertEquals(
            "the shipped catalogue really does classify these exercises, so this is not a fixture where " +
                "reconstruction would coincidentally be impossible",
            setOf(Focus.PULL),
            ProductionFocusClassification.focusesOf("pullups")
        )
        assertEquals(
            "and it classifies a multi-focus exercise as more than one focus — the exact shape that " +
                "cannot be turned back into 'the focus this occurrence was assigned to'",
            setOf(Focus.PUSH, Focus.LEGS, Focus.CONDITIONING),
            ProductionFocusClassification.focusesOf("burpees")
        )
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            performed("pullups", sets = 2, focus = null),
                            performed("burpees", sets = 3, focus = null)
                        )
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "and the source still states absence for occurrences with no recorded focus — the " +
                "catalogue's answer is not this field's answer, and `burpees` especially must not become " +
                "three assignments",
            emptyMap<Focus, Int>(),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "while the real work is still reported by the signal that needs no focus",
            listOf("pullups", "burpees"),
            preferences.recentExerciseIds
        )
    }

    // ------------------------------------------------------------------ history is immutable

    @Test
    fun aCurrentRevisionCannotRelabelAnOlderSession() = runBlocking {
        // The critical historical invariant, stated as one read: an old session's focus is whatever its
        // OWN snapshot froze. Regenerating the program into a different FocusPlan, and re-scheduling the
        // same slots, cannot change what that workout was presented for — and the context source never
        // consults a revision, so there is no path by which it could.
        val old = session(
            "old",
            exercises = listOf(performed("bench-press", sets = 3, focus = Focus.PUSH))
        )
        // "A regenerated program" is expressed the only way it can be here: it is a *different* stored
        // session with the same slot, under the current revision, presenting the same exercise under
        // another focus. The two coexist, and the older one is unchanged.
        val regenerated = session(
            "regenerated",
            minutes = 60,
            revisionId = RevisionId("revision-2"),
            exercises = listOf(performed("bench-press", sets = 3, focus = Focus.LEGS))
        )
        val read = FocusRecordingHistory(mapOf(PROGRAM to listOf(old, regenerated)))

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "both sessions' recorded focuses stand as recorded: the old workout was PUSH and the newer " +
                "one was LEGS, and neither is relabelled by the other",
            mapOf(Focus.PUSH to 1, Focus.LEGS to 1),
            preferences.recentExposureByFocus
        )
        assertEquals(
            "and the load agrees, key by key",
            mapOf(Focus.PUSH to 3, Focus.LEGS to 3),
            preferences.recentLoadByFocus
        )
    }

    @Test
    fun equalStoredHistoryProducesEqualFocusFacts() = runBlocking {
        // The snapshot-consistency claim at the source's own level: same stored history, two separate
        // reads, one answer. There is no caching that could make this pass for the wrong reason — each
        // call goes to the read again.
        val history = listOf(
            session(
                "s1",
                exercises = listOf(
                    performed("bench-press", sets = 3, focus = Focus.PUSH),
                    performed("back-squat", sets = 4, focus = Focus.LEGS)
                )
            )
        )
        val read = FocusRecordingHistory(mapOf(PROGRAM to history))
        val context = ProgramHistoryGenerationContext(read)

        val first = context.preferencesFor(draftOf(PROGRAM))
        val second = context.preferencesFor(draftOf(PROGRAM))

        assertEquals("the same stored history states the same focus facts", first, second)
        assertEquals("and the read really happened twice, so the equality is not a cache", 2, read.calls)
    }

    // ------------------------------------------------------------------ scope: Program and revision

    @Test
    fun twoProgramsNeverSeeEachOthersFocusHistory() = runBlocking {
        // Both Programs performed real work under *different* focuses, so a leak would produce a
        // plausible wrong map rather than an obviously wrong one.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session("a1", exercises = listOf(performed("bench-press", sets = 2, focus = Focus.PUSH)))
                ),
                OTHER to listOf(
                    session("b1", exercises = listOf(performed("back-squat", sets = 5, focus = Focus.LEGS))),
                    session("b2", minutes = 30, exercises = listOf(performed("deadlift", sets = 3, focus = Focus.PULL)))
                )
            )
        )
        val context = ProgramHistoryGenerationContext(read)

        val mine = context.preferencesFor(draftOf(PROGRAM))
        val theirs = context.preferencesFor(draftOf(OTHER))

        assertEquals(
            "this Program's own history, and only its own",
            mapOf(Focus.PUSH to 2),
            mine.recentLoadByFocus
        )
        assertEquals(
            "and the neighbour's is a different map entirely",
            mapOf(Focus.LEGS to 5, Focus.PULL to 3),
            theirs.recentLoadByFocus
        )
        listOf(Focus.LEGS, Focus.PULL).forEach { focus ->
            assertFalse(
                "'$focus' was trained by another Program and must never enter this one's context",
                focus in mine.recentExposureByFocus || focus in mine.recentLoadByFocus
            )
        }
        assertEquals(
            "and each Program was read exactly once, scoped by its own id",
            listOf(PROGRAM, OTHER),
            read.requested
        )
    }

    // ------------------------------------------------------------------ the signals that stay neutral

    @Test
    fun adaptivePreferenceRemainsNeutralAndIsNeverReadFromFamilyState() = runBlocking {
        // P29 filled two of §8's six signals. The temptation a stage faces after filling two is to go
        // looking for the remaining four, and `FamilyProgressionState.currentExerciseId` is sitting right
        // there — persisted, revision-scoped, and plausible. It means *"the exercise this family is
        // currently on"*, which is family progression state and not an exercise-selection preference
        // for generation. Promoting it would fabricate the field's meaning.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(
                            performed("bench-press", sets = 3, focus = Focus.PUSH),
                            performed("back-squat", sets = 4, focus = Focus.LEGS)
                        )
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "a rich, non-empty history changes nothing about the adaptive preference: there is no " +
                "contract that makes a family ladder position a generation preference",
            emptyList<String>(),
            preferences.adaptivePreferredExerciseIds
        )
        assertTrue(
            "and the history above is genuinely rich, so this is not an empty-history artefact",
            preferences.recentExposureByFocus.isNotEmpty() && preferences.recentLoadByFocus.isNotEmpty()
        )
    }

    @Test
    fun recoveryRemainsUnknownEvenWithACompleteHistory() = runBlocking {
        // The same temptation, the other remaining field. `RecoveryContext` is produced by the adaptive
        // stage's own judgement rule for **one decision window of one family**, and that rule itself
        // receives `UNKNOWN` as its documented absence. There is no generation-scoped recovery contract
        // to read, and elapsed time, time since the last workout or `currentExerciseId` would each be a
        // substitute measurement rather than the fact.
        val read = FocusRecordingHistory(
            mapOf(
                PROGRAM to listOf(
                    session(
                        "s1",
                        exercises = listOf(performed("bench-press", sets = 4, focus = Focus.PUSH)),
                        status = SessionStatus.COMPLETED
                    )
                )
            )
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "§14's documented absence, unchanged: no production-owned generation-scoped recovery context " +
                "exists",
            RecoveryContext.UNKNOWN,
            preferences.recovery
        )
    }

    @Test
    fun aNewProgramStillStatesTheNeutralContextAndIsNeverRead() = runBlocking {
        // P27's invariant, re-pinned: a draft that will CREATE a Program has no Program to scope a read
        // to, so the read is not issued at all — including for the two signals P29 filled.
        val read = FocusRecordingHistory(
            mapOf(PROGRAM to listOf(session("s1", exercises = listOf(performed("squats", focus = Focus.LEGS)))))
        )

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(
            ProgramEditorDraft(name = "New", mode = ProgramMode.GENERATED)
        )

        assertEquals(
            "no history is read for a Program that does not exist",
            emptyList<ProgramId>(),
            read.requested
        )
        assertEquals(
            "so every signal is neutral — including the two that now have owners, which have nothing to " +
                "read from",
            emptyMap<Focus, Int>(),
            preferences.recentExposureByFocus
        )
        assertEquals(emptyMap<Focus, Int>(), preferences.recentLoadByFocus)
        assertEquals(emptyList<String>(), preferences.recentExerciseIds)
    }

    @Test
    fun aProgramWithNoHistoryStillStatesAbsenceRatherThanZero() = runBlocking {
        val read = FocusRecordingHistory(mapOf(PROGRAM to emptyList()))

        val preferences = ProgramHistoryGenerationContext(read).preferencesFor(draftOf(PROGRAM))

        assertEquals(
            "the Program was read — an existing Program with no sessions is not the same fact as one " +
                "that does not exist",
            listOf(PROGRAM),
            read.requested
        )
        Focus.entries.forEach { focus ->
            assertFalse(
                "'$focus' has no stated exposure; a 0 entry would be a fabricated measurement",
                focus in preferences.recentExposureByFocus
            )
            assertFalse(
                "'$focus' has no stated load; a 0 entry would be a fabricated measurement",
                focus in preferences.recentLoadByFocus
            )
        }
    }

    @Test
    fun theSnapshotElementIsTheOnlySourceOfTheHistoricalFocus() = runBlocking {
        // Stated as a value claim rather than only a behavioural one: the focus the context reads is the
        // `EffectiveExercise.focus` of the session's own snapshot, which is what §19 freezes. A session
        // whose snapshot states nothing therefore states nothing here, whatever its occurrences contain.
        val snapshotOnly = session(
            "s1",
            exercises = listOf(performed("bench-press", sets = 2, focus = Focus.PUSH))
        )
        assertEquals(
            "the fixture really does put the focus on the snapshot element, so the tests above measure " +
                "the snapshot and not a coincidence",
            listOf(Focus.PUSH),
            snapshotOnly.snapshot.workout.exercises.map { it.focus }
        )
        // The occurrence carries no focus field at all, so the context cannot have read one from there.
        // Asserted as a shape claim on the *declared fields* rather than a fixture statement, which is
        // stronger: it says no future fixture could put a focus on an occurrence and have the tests
        // above quietly mean something else. `completedSetCount` is absent because it is a computed
        // property, not a stored field — which is the point: it is derived from `results`, and so is a
        // set count.
        assertEquals(
            "a `SessionExercise` stores no focus: the focus is a property of what was PRESENTED, frozen " +
                "at start, and not of what was performed. Its own fields are the occurrence's identity, " +
                "the presentation it came from, and the results the user confirmed",
            listOf("exerciseId", "prescription", "programExerciseId", "results", "sessionExerciseId",
                "skipped"),
            SessionExercise::class.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .sorted()
        )
    }

    // ------------------------------------------------------------------ fixtures

    private companion object {

        val PROGRAM = ProgramId("program-under-test")
        val OTHER = ProgramId("program-somebody-else")

        /** The reference moment every fixture fact is stamped with. */
        val BASE: Instant = Instant.parse("2026-09-21T07:00:00Z")

        fun draftOf(programId: ProgramId) = ProgramEditorDraft(
            programId = programId,
            baseRevisionId = RevisionId("revision-1"),
            name = "Existing",
            mode = ProgramMode.GENERATED
        )

        /**
         * A complete, legal stored session.
         *
         * The snapshot element carries [focus] and the occurrence deliberately does **not** — which is
         * the production shape and the point of the stage: the focus is a property of what was
         * *presented*, frozen at start, and the occurrence records only what was performed.
         */
        fun session(
            key: String,
            minutes: Int = 10,
            status: SessionStatus = SessionStatus.COMPLETED,
            revisionId: RevisionId = RevisionId("revision-1"),
            exercises: List<SessionExercise>
        ): WorkoutSession {
            val startedAt = BASE.plusSeconds(minutes * 60L)
            val sessionId = SessionId("session-$key")
            val slotId = SlotId("slot-$key")
            val programId = PROGRAM
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
                            EffectiveExercise(
                                programExerciseId = occurrence.programExerciseId,
                                exerciseId = occurrence.exerciseId,
                                prescription = occurrence.prescription,
                                focus = FOCUSES.getValue(occurrence.programExerciseId.value)
                            )
                        }
                    )
                ),
                status = status,
                startedAt = startedAt,
                finishedAt = if (status == SessionStatus.IN_PROGRESS) null else finishedAt,
                exercises = exercises
            )
        }

        /**
         * The focus each snapshot element states, keyed by plan element identity.
         *
         * A fixture-wide map rather than a field on the occurrence, because that is exactly the production
         * shape: the focus lives on the presentation, and the occurrence knows nothing about it. It also
         * means a fixture cannot accidentally put a focus where the domain forbids one.
         */
        val FOCUSES: MutableMap<String, Focus?> = mutableMapOf()

        /** One performed occurrence of [sets] sets of [repsPerSet] repetitions, presented under [focus]. */
        fun performed(
            exerciseId: String,
            sets: Int = 2,
            prescribedSets: Int = sets,
            repsPerSet: Int = 10,
            focus: Focus?,
            tag: String = "first"
        ): SessionExercise {
            val programExerciseId = ProgramExerciseId("pe-$exerciseId-$tag")
            FOCUSES[programExerciseId.value] = focus
            return SessionExercise(
                sessionExerciseId = SessionExerciseId("se-$exerciseId-$tag"),
                programExerciseId = programExerciseId,
                exerciseId = exerciseId,
                prescription = RepPrescription(List(prescribedSets) { repsPerSet }),
                results = (1..sets).map { index ->
                    SetResult(
                        setLogId = SetLogId("set-$exerciseId-$tag-$index"),
                        setIndex = index,
                        completedReps = repsPerSet,
                        durationSeconds = 0,
                        performedAt = BASE
                    )
                }
            )
        }

        /** One timed occurrence: [secondsPerSet] seconds confirmed, one set each. */
        fun timed(
            exerciseId: String,
            secondsPerSet: List<Int>,
            focus: Focus?,
            tag: String = "first"
        ): SessionExercise {
            val programExerciseId = ProgramExerciseId("pe-$exerciseId-$tag")
            FOCUSES[programExerciseId.value] = focus
            return SessionExercise(
                sessionExerciseId = SessionExerciseId("se-$exerciseId-$tag"),
                programExerciseId = programExerciseId,
                exerciseId = exerciseId,
                prescription = TimePrescription(secondsPerSet),
                results = secondsPerSet.mapIndexed { index, seconds ->
                    SetResult(
                        setLogId = SetLogId("set-$exerciseId-$tag-$index"),
                        setIndex = index + 1,
                        completedReps = 0,
                        durationSeconds = seconds,
                        performedAt = BASE
                    )
                }
            )
        }

        /** Presented and explicitly skipped: a recorded focus, and no work at all. */
        fun skipped(exerciseId: String, focus: Focus?, tag: String = "first"): SessionExercise {
            val programExerciseId = ProgramExerciseId("pe-$exerciseId-$tag")
            FOCUSES[programExerciseId.value] = focus
            return SessionExercise(
                sessionExerciseId = SessionExerciseId("se-$exerciseId-$tag"),
                programExerciseId = programExerciseId,
                exerciseId = exerciseId,
                prescription = RepPrescription(listOf(10)) as Prescription,
                skipped = true
            )
        }

        /** Presented, never worked. */
        fun presented(exerciseId: String, focus: Focus?, tag: String = "first"): SessionExercise {
            val programExerciseId = ProgramExerciseId("pe-$exerciseId-$tag")
            FOCUSES[programExerciseId.value] = focus
            return SessionExercise(
                sessionExerciseId = SessionExerciseId("se-$exerciseId-$tag"),
                programExerciseId = programExerciseId,
                exerciseId = exerciseId,
                prescription = RepPrescription(listOf(10))
            )
        }
    }
}

/**
 * A [GenerationSessionHistory] that answers from a stated map and records what it was asked for.
 *
 * P29's own copy rather than a shared one, because P27's `ProgramGenerationContextTest` declares a
 * private class of the same name in the same package — two `private` top-level declarations collide in
 * Kotlin, and a shared fixture would also couple two suites that measure different things: P27's
 * measures scoping and recency, this one's measures focus attribution.
 */
private class FocusRecordingHistory(
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