package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.toConfigurationMetadata
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.ExposureLevel
import com.monkfitness.app.domain.adaptive.ExposureObservation
import com.monkfitness.app.domain.adaptive.ExerciseRange
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.toExerciseRange
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveEvidence
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptivePolicy
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveSignals
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveWindow
import com.monkfitness.app.domain.adaptive.engine.ProgramComparableExposure
import com.monkfitness.app.domain.adaptive.engine.ProgramGapFacts
import com.monkfitness.app.domain.adaptive.integration.AdaptiveWindowRule
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import com.monkfitness.app.domain.workout.EffectiveWorkout
import com.monkfitness.app.domain.workout.SessionExercise
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import com.monkfitness.app.domain.workout.WorkoutSessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * P33's two deliveries, pinned where each one is decided.
 *
 * ```text
 * 1. the production range source   → answers the catalogue's own span, and `null` for what it does not
 *                                    hold; and the composition root wires it instead of `NONE`
 * 2. the two gap facts             → the last confirmed set is found outside the decision window, and
 *                                    the usual interval is absent until there is data for a habit
 * ```
 *
 * Every assertion here is about a **fact**, never about a decision: the last suite in this file states
 * the other half of the brief mechanically — a window carrying the facts decides exactly as one that
 * does not, because no rule reads them yet.
 */
class AdaptiveGapFactsAndRangeWiringTest {

    // ------------------------------------------------------------------ 1. the production range source

    /**
     * The source answers the span the exercise's **own definition** states.
     *
     * The expected value is derived from the catalogue entry the same way the source derives it
     * (`toConfigurationMetadata()` → `toExerciseRange()`), which is the claim worth pinning: the source
     * holds no second copy of any bound, so it cannot disagree with the catalogue it reads. The literal
     * spans are asserted beside it so a source that answered some *other* range consistently would still
     * fail.
     */
    @Test
    fun theProductionRangeSourceAnswersTheCatalogueSpanOfAKnownExercise() {
        val source = CatalogExerciseRangeSource()
        val catalogue = WorkoutGenerator().getExerciseLibrary()

        val repetitionExercise = catalogue.first { exercise -> !exercise.isTimerBased }
        val repetitionRange = source.rangeOf(repetitionExercise.id)
        assertNotNull(
            "a catalogue exercise that progresses in repetitions states a range, so the source must " +
                "answer one: '${repetitionExercise.id}'",
            repetitionRange
        )
        assertEquals(
            "the range is the exercise's whole span — its base minimum to its last phase's maximum — " +
                "and not the first phase's band",
            ExerciseRange(repRange = repetitionExercise.baseMinReps..repetitionExercise.phase4MaxReps),
            repetitionRange
        )

        val timedExercise = catalogue.first { exercise -> exercise.isTimerBased }
        val timedRange = source.rangeOf(timedExercise.id)
        assertNotNull("a timed catalogue exercise states a range too", timedRange)
        assertNull(
            "and it is stated in one dimension: a timed exercise has no repetition range",
            timedRange?.repRange
        )
        assertEquals(
            "its span is the seconds its definition travels",
            ExerciseRange(
                timeRange = timedExercise.baseDurationSeconds..timedExercise.phase4DurationSeconds
            ),
            timedRange
        )
    }

    /** Every catalogue entry, not just the two sampled above: no entry is silently rangeless. */
    @Test
    fun theProductionRangeSourceAgreesWithTheCatalogueForEveryEntry() {
        val source = CatalogExerciseRangeSource()

        val disagreements = WorkoutGenerator().getExerciseLibrary()
            .filter { exercise ->
                source.rangeOf(exercise.id) != exercise.toConfigurationMetadata().toExerciseRange()
            }
            .map { exercise -> exercise.id }

        assertTrue(
            "the source is a projection of the catalogue's own metadata, so no entry may disagree " +
                "with it. Disagreeing: $disagreements",
            disagreements.isEmpty()
        )
    }

    /**
     * An id the catalogue does not hold is answered `null`, which is the port's stated answer for *"this
     * caller declares no range"* — never a synthesised span.
     */
    @Test
    fun theProductionRangeSourceAnswersNothingForAnUnknownExercise() {
        val source = CatalogExerciseRangeSource()

        assertNull(
            "an exercise the app does not ship has no catalogue span, and the source invents none",
            source.rangeOf("no_such_exercise_in_the_catalogue")
        )
        assertNull("and the empty id is not a lookup that succeeds by accident", source.rangeOf(""))
    }

    /**
     * The composition root hands the integration **this** source, and no longer leaves the port at its
     * honest-but-empty default.
     *
     * Asserted on the wiring because that is the defect the brief names: the engine already accepted a
     * range, and production supplied none, so the fact never reached it. The wiring is read the same way
     * the neighbouring architecture gates read it — the slice between the integration's node and the next
     * one — so a later edit that moved the argument elsewhere would fail here rather than pass silently.
     */
    @Test
    fun theCompositionRootWiresTheCatalogueRangeSourceIntoTheIntegration() {
        val container = File("src/main/java/com/monkfitness/app/di/AppContainer.kt")
            .let { candidate -> if (candidate.isFile) candidate else File("app/$candidate") }
        assertTrue("expected the composition root at ${container.absolutePath}", container.isFile)

        val wiring = container.readText()
            .substringAfter("val programAdaptiveIntegration")
            .substringBefore("val maintenanceRepository")

        assertTrue(
            "production hands the integration the catalogue's own ranges, so a range reaches the " +
                "engine on every pass",
            Regex("""exerciseRanges\s*=\s*catalogExerciseRangeSource""").containsMatchIn(wiring)
        )
        assertFalse(
            "and the empty source is no longer what production wires: leaving the default in place is " +
                "exactly the state this stage closes",
            Regex("""exerciseRanges\s*=\s*ExerciseRangeSource\.NONE""").containsMatchIn(wiring)
        )
    }

    // ------------------------------------------------------------------ 2. the gap facts

    /**
     * The last confirmed set is found **outside the decision window**.
     *
     * The history holds one session 40 days before the moment of the pass, and the window reaches back
     * only 28 — so the window's own observations contain nothing, while the fact still names the day the
     * user last trained. That difference is the whole point of the fact: a family nobody has touched for
     * six weeks has no exposure *in* the window and still has a date.
     */
    @Test
    fun theLastConfirmedSetIsFoundEvenWhenItIsOlderThanTheWindow() {
        val capturedAt = Instant.parse("2026-10-31T09:00:00Z")
        val lastTrainedAt = capturedAt.minus(Duration.ofDays(40))
        val windowStart = AdaptiveWindowRule.V1.windowStart(capturedAt)

        assertTrue(
            "the fixture is only meaningful if the last set really is outside the window",
            lastTrainedAt.isBefore(windowStart)
        )

        val date = lastConfirmedSetOn(
            sessions = listOf(
                performedSession("older", capturedAt.minus(Duration.ofDays(60)), "pushups"),
                performedSession("last", lastTrainedAt, "pushups")
            ),
            completion = SessionId("session-last"),
            familyOf = { exerciseId -> if (exerciseId == "pushups") "push-family" else null },
            familyId = "push-family",
            zone = ZoneOffset.UTC
        )

        assertEquals(
            "the fact is the calendar day of the most recent confirmed set, read from the stored sets " +
                "and not narrowed to the window",
            LocalDate.ofInstant(lastTrainedAt, ZoneOffset.UTC),
            date
        )
    }

    /** The date is the **set's** own stamp, in the caller's zone — not the session's start or end. */
    @Test
    fun theLastConfirmedSetIsTheLatestConfirmedSetOfThatFamily() {
        val session = performedSession("three", Instant.parse("2026-10-20T07:00:00Z"), "pushups", sets = 3)

        val date = lastConfirmedSetOn(
            sessions = listOf(session),
            completion = session.sessionId,
            familyOf = { exerciseId -> if (exerciseId == "pushups") "push-family" else null },
            familyId = "push-family",
            zone = ZoneOffset.UTC
        )

        val latestSet = session.exercises.single().results.maxOf { result -> result.performedAt }
        assertEquals(LocalDate.ofInstant(latestSet, ZoneOffset.UTC), date)
        assertTrue(
            "the last set of the occurrence is later than the session it ran in started",
            latestSet.isAfter(session.startedAt)
        )
    }

    /**
     * A family with no confirmed set states **no date**, and another family's sets do not stand in for
     * it: absence is a fact here, not a zero and not a neighbour's reading.
     */
    @Test
    fun theLastConfirmedSetIsAbsentForAFamilyThatWasNeverTrained() {
        val sessions = listOf(
            performedSession("squats", Instant.parse("2026-10-20T07:00:00Z"), "squats")
        )
        val familyOf = { exerciseId: String -> if (exerciseId == "squats") "squat-family" else null }

        assertNull(
            "no confirmed set of this family exists anywhere in the history",
            lastConfirmedSetOn(sessions, SessionId("session-squats"), familyOf, "push-family", ZoneOffset.UTC)
        )
        assertNotNull(
            "while the family that *was* trained states its date — the two are different answers",
            lastConfirmedSetOn(sessions, SessionId("session-squats"), familyOf, "squat-family", ZoneOffset.UTC)
        )
    }

    /**
     * Fewer than three sessions is **no usual interval**: two sessions are one interval, and one interval
     * is not a habit.
     */
    @Test
    fun theUsualIntervalIsAbsentWithFewerThanThreeSessions() {
        val base = Instant.parse("2026-10-01T07:00:00Z")

        assertNull("no exposure, no habit", usualIntervalOf(emptyList()))
        assertNull(
            "one session cannot state an interval at all",
            usualIntervalOf(exposures("a" to base))
        )
        assertNull(
            "two sessions are one interval, which is a coincidence rather than a baseline",
            usualIntervalOf(exposures("a" to base, "b" to base.plus(Duration.ofDays(2))))
        )
    }

    /**
     * From three sessions the interval is stated, as the **median** of the gaps: one long break must not
     * drag the usual interval the way a mean would.
     */
    @Test
    fun theUsualIntervalIsTheMedianOfTheRecentGaps() {
        val base = Instant.parse("2026-10-01T07:00:00Z")

        assertEquals(
            "two gaps of 2 and 4 days state a usual interval of 3",
            Duration.ofDays(3),
            usualIntervalOf(
                exposures(
                    "a" to base,
                    "b" to base.plus(Duration.ofDays(2)),
                    "c" to base.plus(Duration.ofDays(6))
                )
            )
        )
        assertEquals(
            "and three gaps of 2, 4 and 6 state the middle one, 4",
            Duration.ofDays(4),
            usualIntervalOf(
                exposures(
                    "a" to base,
                    "b" to base.plus(Duration.ofDays(2)),
                    "c" to base.plus(Duration.ofDays(6)),
                    "d" to base.plus(Duration.ofDays(12))
                )
            )
        )
    }

    /** Several occurrences of one session are still **one** session for the interval's purposes. */
    @Test
    fun theUsualIntervalCountsSessionsAndNotOccurrences() {
        val base = Instant.parse("2026-10-01T07:00:00Z")
        val oneSession = listOf(
            observation("pushups", "session-a", base),
            observation("pike_pushup", "session-a", base.plusSeconds(300))
        )

        assertNull(
            "two exercises in one session are one training day, so there is still no habit to state",
            usualIntervalOf(oneSession)
        )
    }

    // ------------------------------------------------------------------ 3. nothing reads them yet

    /**
     * The facts reach the policy's own input and **change no decision**: a window carrying both facts
     * answers exactly as the same window with neither.
     *
     * This is the mechanical form of the brief's *"nothing reads them yet"*. It is asserted on the
     * policy's answer rather than on its source, because the claim is about behaviour: the moment a rule
     * consults a gap fact, this suite is where that shows up.
     */
    @Test
    fun theGapFactsChangeNoPolicyDecision() {
        val policy = ProgramAdaptivePolicy.V1
        val withoutFacts = window(ProgramGapFacts.NONE)
        val withFacts = window(
            ProgramGapFacts(
                lastConfirmedSetOn = LocalDate.parse("2026-09-01"),
                usualInterval = Duration.ofDays(3)
            )
        )

        assertEquals(
            "a window that states the gap facts decides as one that does not: no rule reads them",
            policy.evaluate(evidence(withoutFacts)).reason,
            policy.evaluate(evidence(withFacts)).reason
        )
        assertEquals(
            "and the same holds for the state and the action it asks for",
            policy.evaluate(evidence(withoutFacts)).state,
            policy.evaluate(evidence(withFacts)).state
        )
    }

    /** And the facts are carried, not folded into the window's counts: the counts are untouched. */
    @Test
    fun theGapFactsAreCarriedBesideTheWindowsCounts() {
        val facts = ProgramGapFacts(
            lastConfirmedSetOn = LocalDate.parse("2026-09-01"),
            usualInterval = Duration.ofDays(3)
        )
        val stated = window(facts)

        assertEquals(facts, stated.gap)
        assertEquals(
            "the counts the policy already read are exactly what they were",
            ProgramAdaptiveWindow(familyId = "push-family", level = 1),
            stated.copy(gap = ProgramGapFacts.NONE)
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun window(gap: ProgramGapFacts): ProgramAdaptiveWindow =
        ProgramAdaptiveWindow(familyId = "push-family", level = 1, gap = gap)

    private fun evidence(window: ProgramAdaptiveWindow): ProgramAdaptiveEvidence =
        ProgramAdaptiveEvidence(
            window = window,
            signals = ProgramAdaptiveSignals(
                familyId = window.familyId,
                exposure = ProgramComparableExposure(),
                trend = null,
                olderHalf = null,
                newerHalf = null,
                consistency = null,
                recentContext = null
            ),
            evidence = EvidenceLevel.INSUFFICIENT,
            confidence = ConfidenceLevel.LOW,
            recovery = RecoveryContext.UNKNOWN
        )

    /** One observation per named session, at the instant given: the interval fixture's own shape. */
    private fun exposures(vararg sessionAndStart: Pair<String, Instant>): List<ExposureObservation> =
        sessionAndStart.map { (session, startedAt) -> observation("pushups", session, startedAt) }

    private fun observation(exerciseId: String, session: String, startedAt: Instant): ExposureObservation =
        ExposureObservation(
            exerciseId = exerciseId,
            sessionId = SessionId("session-$session"),
            sessionExerciseId = SessionExerciseId("se-$session-$exerciseId"),
            level = ExposureLevel.FULL,
            completedSets = 2,
            prescribedSets = 2,
            startedAt = startedAt,
            finishedAt = startedAt.plusSeconds(1800)
        )

    /**
     * A complete, legal stored session that confirmed [sets] sets of eight repetitions of [exerciseId].
     *
     * The occurrence is presented by its own snapshot (a session may only run what it presented), and each
     * set carries its own `performedAt`, which is what makes *"the date of the last confirmed set"* a
     * statement about a set rather than about a session.
     */
    private fun performedSession(
        key: String,
        startedAt: Instant,
        exerciseId: String,
        sets: Int = 1
    ): WorkoutSession {
        val sessionId = SessionId("session-$key")
        val slotId = SlotId("slot-$key")
        val programId = ProgramId("program-gap-facts")
        val revisionId = RevisionId("revision-gap-facts")
        val programExerciseId = ProgramExerciseId("pe-$key")
        val occurrence = SessionExercise(
            sessionExerciseId = SessionExerciseId("se-$key"),
            programExerciseId = programExerciseId,
            exerciseId = exerciseId,
            prescription = RepPrescription(List(sets) { 8 }),
            results = (1..sets).map { index ->
                SetResult(
                    setLogId = SetLogId("set-$key-$index"),
                    setIndex = index,
                    completedReps = 8,
                    durationSeconds = 0,
                    performedAt = startedAt.plusSeconds(600L + index * 60L)
                )
            }
        )

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
                    plannedFor = LocalDate.ofInstant(startedAt, ZoneOffset.UTC),
                    computedAt = startedAt,
                    exercises = listOf(
                        EffectiveExercise(
                            programExerciseId = programExerciseId,
                            exerciseId = exerciseId,
                            prescription = occurrence.prescription
                        )
                    )
                )
            ),
            status = SessionStatus.COMPLETED,
            startedAt = startedAt,
            finishedAt = startedAt.plusSeconds(1800),
            exercises = listOf(occurrence)
        )
    }
}
