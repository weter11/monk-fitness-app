package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.toConfigurationMetadata
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ExposureLevel
import com.monkfitness.app.domain.adaptive.ExposureObservation
import com.monkfitness.app.domain.adaptive.ExerciseRange
import com.monkfitness.app.domain.adaptive.PerformedSetAmount
import com.monkfitness.app.domain.adaptive.toExerciseRange
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveElement
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveEngine
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptivePolicy
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveResult
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveWindow
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
 * P33's two deliveries and P34's return rule, pinned where each of them is decided.
 *
 * ```text
 * 1. the production range source   → answers the catalogue's own span, and `null` for what it does not
 *                                    hold; and the composition root wires it instead of `NONE`
 * 2. the gap facts                 → the last confirmed set is found outside the decision window, the
 *                                    stretch before this window is measured to the session before it, and
 *                                    the usual interval comes from the family's own whole history
 * 3. the return after a gap         → a long gap against the user's own rhythm lands lighter, a missed
 *                                    session or two does not, a pending confirmation is reset, and the
 *                                    floor, the fallback and the performed amount each answer as stated
 * ```
 *
 * Sections 1 and 2 are about **facts**; section 3 is about the one rule that reads two of them, and about
 * the three ways it can end (a lighter week, the floor, or nothing at all).
 */
class AdaptiveGapFactsAndRangeWiringTest {

    private companion object {

        /** The family every fixture here is about: the one the classification and the windows name. */
        const val FAMILY = "push-family"
    }

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
     * is not a habit. The policy is what answers an absent habit with its documented fallback.
     */
    @Test
    fun theUsualIntervalIsAbsentWithFewerThanThreeSessions() {
        val base = Instant.parse("2026-10-01T07:00:00Z")

        assertNull("no session, no habit", intervalOf())
        assertNull("one session cannot state an interval at all", intervalOf(base))
        assertNull(
            "two sessions are one interval, which is a coincidence rather than a baseline",
            intervalOf(base, base.plus(Duration.ofDays(2)))
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
            intervalOf(base, base.plus(Duration.ofDays(2)), base.plus(Duration.ofDays(6)))
        )
        assertEquals(
            "and three gaps of 2, 4 and 6 state the middle one, 4",
            Duration.ofDays(4),
            intervalOf(
                base,
                base.plus(Duration.ofDays(2)),
                base.plus(Duration.ofDays(6)),
                base.plus(Duration.ofDays(12))
            )
        )
    }

    /**
     * The usual interval is read from the **whole stored history**, not from the decision window.
     *
     * This is the fact a gap breaks: a family the user has not touched for six weeks has no exposure in a
     * 28-day window at all, so a habit measured over the window would be measured over the absence. The
     * fixture is five sessions ten days apart, and the first of them is outside the window the pass would
     * have used.
     */
    @Test
    fun theUsualIntervalIsReadFromTheWholeHistoryAndNotFromTheWindow() {
        val base = Instant.parse("2026-09-01T07:00:00Z")
        val starts = (0..4).map { index -> base.plus(Duration.ofDays(10L * index)) }

        assertTrue(
            "the fixture is only meaningful if its sessions reach back past the window",
            starts.first().isBefore(AdaptiveWindowRule.V1.windowStart(starts.last()))
        )
        assertEquals(
            "a habit is a fact about the stored history, not about the window's own exposures",
            Duration.ofDays(10),
            intervalOf(*starts.toTypedArray())
        )
    }

    /** ... and only its last N sessions state it, so a month of a new rhythm is not outvoted by a year of the old one. */
    @Test
    fun theUsualIntervalIsReadFromTheLastSessionsThePolicyNames() {
        val base = Instant.parse("2026-10-01T07:00:00Z")
        val starts = arrayOf(
            base,
            base.plus(Duration.ofDays(1)),
            base.plus(Duration.ofDays(2)),
            base.plus(Duration.ofDays(3)),
            base.plus(Duration.ofDays(10))
        )

        assertEquals(
            "five sessions are four gaps of 1, 1, 1 and 7 days: the middle of the sorted two is 1",
            Duration.ofDays(1),
            intervalOf(*starts)
        )
        assertEquals(
            "while the last three are gaps of 1 and 7 days, whose midpoint is 4",
            Duration.ofDays(4),
            intervalOf(*starts, sessionsToConsider = 3)
        )
    }

    /**
     * The session the pass is about is **not** one of the sessions the habit is measured over.
     *
     * The stretch that led to it is the gap itself: counting it as one of two intervals would let the
     * absence define the rhythm it interrupted, and a user who trained every three days and then missed
     * two weeks would come back to an app that believes they train every nine.
     */
    @Test
    fun theUsualIntervalSkipsTheSessionThePassIsAbout() {
        val base = Instant.parse("2026-10-01T07:00:00Z")
        val history = listOf(
            performedSession("a", base, "pushups"),
            performedSession("b", base.plus(Duration.ofDays(3)), "pushups"),
            performedSession("c", base.plus(Duration.ofDays(6)), "pushups"),
            performedSession("today", base.plus(Duration.ofDays(21)), "pushups")
        )

        assertEquals(
            "three days apart before the gap, and three days apart as a stated habit",
            Duration.ofDays(3),
            usualIntervalOf(history, SessionId("session-today"), ::familyOf, FAMILY, 5)
        )
    }

    /**
     * And the stretch itself is stated as its own fact: [`daysAway`] is measured to the last confirmed set
     * **before** the completing session, because that session's sets are already facts when the pass runs.
     */
    @Test
    fun theGapFactsStateTheStretchTheUserIsReturningFrom() {
        val today = Instant.parse("2026-10-31T07:00:00Z")
        val lastTrainedAt = today.minus(Duration.ofDays(15))
        val facts = programGapFactsOf(
            sessions = listOf(
                performedSession("older", lastTrainedAt.minus(Duration.ofDays(4)), "pushups"),
                performedSession("last", lastTrainedAt, "pushups"),
                performedSession("today", today, "pushups")
            ),
            completion = SessionId("session-today"),
            familyOf = ::familyOf,
            familyId = FAMILY,
            zone = ZoneOffset.UTC,
            statedOn = LocalDate.ofInstant(today, ZoneOffset.UTC),
            policy = ProgramAdaptivePolicy.V1
        )

        assertEquals(
            "fifteen days, not zero: the completing session's own sets are excluded from the stretch",
            15L,
            facts.daysAway
        )
        assertEquals(
            "and the last confirmed set still says where the user was, today included",
            LocalDate.ofInstant(today, ZoneOffset.UTC),
            facts.lastConfirmedSetOn
        )
        assertNull(
            "two earlier sessions are one interval, so the habit is absent rather than guessed",
            facts.usualInterval
        )
    }

    // ------------------------------------------------------------------ 3. the return after a gap

    /**
     * The same 15-day gap is a **different week** for a user who trains twice a week and one who trains
     * four times a week, because the gap is measured against each of their own rhythms and the amount
     * taken away grows with it.
     *
     * ```text
     * twice a week     usual 3.5 d -> 4 d   15 d is 3 of their intervals   range 6..15, 12 performed -> 10
     * four times a week usual 1.75 d -> 2 d  15 d is 7 of theirs (capped 6)                        ->  8
     * ```
     */
    @Test
    fun theSameGapLandsLowerForTheUserWhoseOwnRhythmItIsLongerAgainst() {
        val twiceAWeek = decideAfterGap(usualInterval = Duration.ofHours(84))
        val fourTimesAWeek = decideAfterGap(usualInterval = Duration.ofHours(42))

        assertEquals(ProgramAdaptiveReason.RETURN_AFTER_GAP, twiceAWeek.reason)
        assertEquals(ProgramAdaptiveReason.RETURN_AFTER_GAP, fourTimesAWeek.reason)
        assertEquals(AdaptiveAction.REGRESS, twiceAWeek.decision.action)
        assertEquals(listOf(10, 10, 10), twiceAWeek.adjustment!!.after.prescription.perSetTargets)
        assertEquals(
            "fifteen days is seven of the frequent trainer's intervals and three of the other's, so the " +
                "longer gap against their own rhythm comes back lower",
            listOf(8, 8, 8),
            fourTimesAWeek.adjustment!!.after.prescription.perSetTargets
        )
    }

    /**
     * One or two missed sessions are **not** a return: they are what the user's own rhythm already
     * contains, and lightening the week of somebody who missed a session would make the rule noise.
     *
     * ```text
     * twice a week      one missed = 7 d, two = 10-11 d   threshold 12 d (3 · 4)
     * four times a week one missed = 4 d, two = 6 d      threshold  6 d (3 · 2), and the 10-day floor
     * ```
     */
    @Test
    fun oneOrTwoMissedSessionsAreNotAReturn() {
        val policy = ProgramAdaptivePolicy.V1

        assertFalse(
            "one missed session at twice a week",
            policy.returnAfterGapHolds(gap(daysAway = 7, usualInterval = Duration.ofHours(84)))
        )
        assertFalse(
            "two missed sessions at twice a week",
            policy.returnAfterGapHolds(gap(daysAway = 10, usualInterval = Duration.ofHours(84)))
        )
        assertFalse(
            "one missed session at four times a week",
            policy.returnAfterGapHolds(gap(daysAway = 4, usualInterval = Duration.ofHours(42)))
        )
        assertFalse(
            "two missed sessions at four times a week",
            policy.returnAfterGapHolds(gap(daysAway = 6, usualInterval = Duration.ofHours(42)))
        )
        assertTrue(
            "while a third week away is a return ...",
            policy.returnAfterGapHolds(gap(daysAway = 13, usualInterval = Duration.ofHours(84)))
        )

        val missedTwo = decideAfterGap(usualInterval = Duration.ofHours(84), daysAway = 10)
        assertFalse(
            "and a window that is not a return decides exactly as it did before the rule existed",
            missedTwo.reason == ProgramAdaptiveReason.RETURN_AFTER_GAP
        )
        assertNull("no lighter week for two missed sessions", missedTwo.adjustment)
    }

    /**
     * A pending pre-gap progression confirmation **does not fire on return**, and the counters that would
     * have confirmed it are reset rather than advanced.
     *
     * The control run is the point: the same window without the gap is exactly the confirmation a family
     * one window into a two-window gate was waiting for. With the gap the policy answers `REGRESS` and
     * reports **no direction qualified**, which is what the caller advances its counters from (§15) — so
     * pre-gap evidence cannot confirm anything on the way back in.
     */
    @Test
    fun aReturnResetsThePendingProgressionConfirmationInsteadOfFiringIt() {
        val policy = ProgramAdaptivePolicy.V1
        val pending = ProgramAdaptiveRig.window(precedingProgressQualifyingWindows = 1)

        assertEquals(
            "without the gap this window is the confirmation the family was waiting for",
            ProgramAdaptiveReason.SUSTAINED_POSITIVE,
            policy.evaluate(ProgramAdaptiveRig.evidence(window = pending)).reason
        )

        val returning = policy.evaluate(
            ProgramAdaptiveRig.evidence(
                window = pending.copy(
                    gap = gap(daysAway = 15, usualInterval = Duration.ofHours(84))
                )
            )
        )

        assertEquals(ProgramAdaptiveReason.RETURN_AFTER_GAP, returning.reason)
        assertEquals(AdaptiveState.REGRESS, returning.state)
        assertTrue(
            "the return reports no direction at all, so every preceding* counter is reset to zero: " +
                "verdict=${returning.verdict}",
            returning.verdict.isIdle
        )
    }

    /** At the range's floor there is nothing to lower: no adjustment, and the floor has its own reason. */
    @Test
    fun atTheRangesFloorAReturnMakesNoAdjustment() {
        val result = decideAfterGap(
            usualInterval = Duration.ofHours(84),
            element = ProgramAdaptiveRig.element(prescription = RepPrescription.uniform(3, 6)),
            performed = 6
        )

        assertNull("a presentation already at the bottom of its range is not lowered again", result.adjustment)
        assertEquals(ProgramAdaptiveReason.FLOOR_REACHED, result.reason)
        assertEquals(AdaptiveAction.HOLD, result.decision.action)
    }

    /**
     * A history too short to state a habit is answered with the policy's **fallback interval**, not with a
     * rule that never fires: a user who has trained twice and then missed a fortnight is exactly the case
     * this must not drop.
     */
    @Test
    fun aHistoryTooShortToStateAHabitFallsBackToThePolicysUsualInterval() {
        val policy = ProgramAdaptivePolicy.V1
        val noHabit = gap(daysAway = 15, usualInterval = null)

        assertEquals(
            "no measurable habit is answered with the named fallback, not with a zero",
            policy.usualIntervalFallbackDays.toLong(),
            policy.usualIntervalDaysOf(noHabit)
        )
        assertTrue("fifteen days away is a return even without a habit", policy.returnAfterGapHolds(noHabit))
        assertFalse("and nine days is not", policy.returnAfterGapHolds(noHabit.copy(daysAway = 9)))

        val result = decideAfterGap(usualInterval = null)
        assertEquals(ProgramAdaptiveReason.RETURN_AFTER_GAP, result.reason)
        assertEquals(
            "the fallback feeds the same arithmetic as a measured habit: 15 days is five of them, and " +
                "five twelfths of a 6..15 range is 3",
            listOf(9, 9, 9),
            result.adjustment!!.after.prescription.perSetTargets
        )
    }

    /**
     * The amount comes from what the user **performed**, not from what the plan prescribed: the plan asked
     * for 14, the user performed 9, and the lighter week is nine less the gap's share — not fourteen less
     * it, and not the range's floor either.
     */
    @Test
    fun theReturningAmountComesFromWhatWasPerformedAndNotFromWhatWasPrescribed() {
        val result = decideAfterGap(
            usualInterval = Duration.ofHours(84),
            element = ProgramAdaptiveRig.element(prescription = RepPrescription.uniform(3, 14)),
            performed = 9
        )

        assertEquals(listOf(14, 14, 14), result.adjustment!!.before.prescription.perSetTargets)
        assertEquals(
            "nine performed, less the two the policy's share of the range takes away",
            listOf(7, 7, 7),
            result.adjustment.after.prescription.perSetTargets
        )
    }

    /** And the facts are carried, not folded into the window's counts: the counts are untouched. */
    @Test
    fun theGapFactsAreCarriedBesideTheWindowsCounts() {
        val facts = ProgramGapFacts(
            lastConfirmedSetOn = LocalDate.parse("2026-09-01"),
            daysAway = 15,
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

    /** A gap the return rule is measured on: how long the user was away, and how often they usually train. */
    private fun gap(daysAway: Long?, usualInterval: Duration?): ProgramGapFacts =
        ProgramGapFacts(daysAway = daysAway, usualInterval = usualInterval)

    /**
     * The engine's answer for a returning family: one performed exposure of three sets at [performed], an
     * element presenting [element], and the gap facts above. The range is the catalogue-like span 6..15.
     */
    private fun decideAfterGap(
        usualInterval: Duration?,
        daysAway: Long = 15,
        element: ProgramAdaptiveElement = ProgramAdaptiveRig.element(
            prescription = RepPrescription.uniform(3, 14)
        ),
        performed: Int = 12
    ): ProgramAdaptiveResult = ProgramAdaptiveEngine.decide(
        ProgramAdaptiveRig.request(
            snapshot = ProgramAdaptiveRig.snapshot(exposures = listOf(performedExposure(performed))),
            element = element,
            window = ProgramAdaptiveRig.window().copy(gap = gap(daysAway, usualInterval))
        ).copy(range = ExerciseRange(repRange = 6..15))
    )

    /** One full exposure of three sets, each performed at [reps] repetitions. */
    private fun performedExposure(reps: Int): ExposureObservation = ExposureObservation(
        exerciseId = ProgramAdaptiveRig.PRESENTED,
        sessionId = SessionId("session-return"),
        sessionExerciseId = SessionExerciseId("se-return"),
        level = ExposureLevel.FULL,
        completedSets = 3,
        prescribedSets = 3,
        performedAmounts = List(3) { PerformedSetAmount(completedReps = reps, durationSeconds = 0) },
        startedAt = ProgramAdaptiveRig.WINDOW_START.plusSeconds(3_600),
        finishedAt = ProgramAdaptiveRig.WINDOW_START.plusSeconds(4_200)
    )

    /** The interval the family's own history states, for sessions starting at [starts]. */
    private fun intervalOf(vararg starts: Instant, sessionsToConsider: Int = 5): Duration? {
        val history = starts.mapIndexed { index, startedAt ->
            performedSession("interval-$index", startedAt, "pushups")
        }
        // The pass is about a session of its own, one day after the last of them: it is not one of the
        // sessions the habit is measured over.
        val completing = performedSession(
            "interval-completing",
            (starts.lastOrNull() ?: Instant.parse("2026-10-01T07:00:00Z")).plus(Duration.ofDays(1)),
            "pushups"
        )
        return usualIntervalOf(history + completing, completing.sessionId, ::familyOf, FAMILY, sessionsToConsider)
    }

    /** The caller's exercise→family classification, as production states it. */
    private fun familyOf(exerciseId: String): String? =
        if (exerciseId == "pushups" || exerciseId == "pike_pushup") FAMILY else null

    private fun window(gap: ProgramGapFacts): ProgramAdaptiveWindow =
        ProgramAdaptiveWindow(familyId = FAMILY, level = 1, gap = gap)

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
