package com.monkfitness.app.domain.adaptive.scenario

import com.monkfitness.app.domain.adaptive.ExerciseRange
import com.monkfitness.app.domain.adaptive.TrainingDomain
import com.monkfitness.app.domain.adaptive.BodyRegion
import com.monkfitness.app.domain.adaptive.ExerciseMetadata
import com.monkfitness.app.domain.adaptive.engine.ProgramElementOwnership
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.toExerciseRange
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.product.ProductionProgressionRelationDefinitions
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.generated.GeneratedPlan
import com.monkfitness.app.domain.program.generated.GeneratedPlanner
import com.monkfitness.app.domain.program.generated.GeneratedPlannerRig
import com.monkfitness.app.domain.program.generated.GenerationCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The adaptive load engine, driven end to end over multi-week timelines: a plan from [GeneratedPlanner]
 * and sessions the engine actually decides.
 *
 * Each scenario is one product rule and one timeline, so a failure names the rule it breaks rather than a
 * number that moved. The plan is generated, not written by hand, because "an exercise starts at the lower
 * bound of its range" is the planner's claim and the timeline inherits whatever it prescribes.
 *
 * The family under test is `pushups` — a catalogue family this app declares a ladder for — beside `plank`,
 * which it declares none for, so the two halves of the ladder gap are measured on the same engine.
 */
class AdaptiveLoadScenarioTest {

    // ---- a. steady progress, good results every session -------------------------------------------

    @Test
    fun aSteadyGoodResultsRaiseTheLoad() {
        val rows = steadyTimeline(sessions = 12)

        println(table("a. steady progress, every set completed", rows))

        val first = rows.first()
        val last = rows.last()
        assertTrue(
            "12 sessions completed exactly as asked must raise the load: " +
                "${first.planned} -> ${last.nextPlanned}",
            last.nextPlanned.sum() > first.planned.sum() || last.nextExerciseId != first.exerciseId
        )
        assertTrue(
            "a working session is never answered with a regression",
            rows.none { row -> row.action?.name == "REGRESS" }
        )
    }

    // ---- b. skipped sessions ---------------------------------------------------------------------

    @Test
    fun bSkippingOneAndThenTwoSessionsKeepsProgressButDoesNotAddIt() {
        val trend = pushupTimeline()
        var date = dates.first()
        val rows = mutableListOf<ScenarioRow>()

        // Six ordinary sessions first, so there is progress to protect.
        repeat(6) {
            rows += trend.runSession(date, full(trend.nextPlanned))
            date = next(date)
        }
        val beforeSkips = trend.nextPlanned

        // One skipped workout: the plan asked for it, nothing was performed, no pass ran.
        trend.recordSkip(date)
        date = next(date)
        assertEquals(
            "a missed opportunity neither grows nor lowers the next session",
            beforeSkips,
            trend.nextPlanned
        )
        rows += trend.runSession(date, full(trend.nextPlanned))
        date = next(date)
        val afterOneSkip = trend.nextPlanned
        assertTrue(
            "one missed workout does not reset the progress earned before it: $beforeSkips -> $afterOneSkip",
            afterOneSkip.sum() >= beforeSkips.sum()
        )

        // Two in a row.
        trend.recordSkip(date)
        date = next(date)
        trend.recordSkip(date)
        date = next(date)
        assertEquals(
            "and two missed workouts add nothing either",
            afterOneSkip,
            trend.nextPlanned
        )
        rows += trend.runSession(date, full(trend.nextPlanned))
        date = next(date)
        val afterTwoSkips = trend.nextPlanned
        assertTrue(
            "two missed workouts do not reset it either: $afterOneSkip -> $afterTwoSkips",
            afterTwoSkips.sum() >= afterOneSkip.sum()
        )
        printTable("b. one skip, then two in a row, then resume", rows)

        // And growth resumes on its own curve.
        repeat(4) {
            rows += trend.runSession(date, full(trend.nextPlanned))
            date = next(date)
        }
        printTable("b. resumed", rows)
        assertTrue(
            "growth resumes within three sessions of the return",
            rows.takeLast(4).any { row -> row.rose }
        )
    }

    // ---- c. long break ---------------------------------------------------------------------------

    @Test
    fun cALongBreakComesBackLighterAndAShortOneDoesNot() {
        val longBreak = timelineWithSixSessions()
        val beforeBreak = longBreak.trend.nextPlanned
        val backAfterFifteen = longBreak.trend.runSession(
            longBreak.lastDate.plusDays(15),
            full(longBreak.trend.nextPlanned)
        )

        assertTrue(
            "the first session after a 15-day illness is lighter than the load carried before it: " +
                "$beforeBreak -> ${backAfterFifteen.nextPlanned}",
            backAfterFifteen.nextPlanned.sum() < beforeBreak.sum()
        )
        printTable("c1. 15-day gap", listOf(backAfterFifteen))

        // Ten days is two missed sessions at this rhythm, under the return floor: the absence is answered
        // by the ordinary rules and the load is not lightened for it.
        val tenDays = timelineWithSixSessions()
        val beforeTen = tenDays.trend.nextPlanned
        val backAfterTen = tenDays.trend.runSession(
            tenDays.lastDate.plusDays(10),
            full(tenDays.trend.nextPlanned)
        )

        assertNotEquals(
            "ten days is below the return floor, so the return rule does not answer it: " +
                "$beforeTen -> ${backAfterTen.nextPlanned}",
            "RETURN_AFTER_GAP",
            backAfterTen.reason
        )
        assertTrue(
            "and the next session is not lightened: $beforeTen -> ${backAfterTen.nextPlanned}",
            backAfterTen.nextPlanned.sum() >= beforeTen.sum()
        )
        printTable("c2. 10-day gap", listOf(backAfterTen))

        val shortBreak = timelineWithSixSessions()
        val beforeShort = shortBreak.trend.nextPlanned
        val backAfterFour = shortBreak.trend.runSession(
            shortBreak.lastDate.plusDays(4),
            full(shortBreak.trend.nextPlanned)
        )
        assertTrue(
            "a three-to-four day gap is an ordinary rhythm and is not a return: " +
                "$beforeShort -> ${backAfterFour.nextPlanned}",
            backAfterFour.nextPlanned.sum() >= beforeShort.sum()
        )
        printTable("c3. 4-day gap", listOf(backAfterFour))
    }

    // ---- d. novice vs veteran ---------------------------------------------------------------------

    @Test
    fun dTheSameResultsProgressDifferentlyWithADifferentHistory() {
        val novice = pushupTimeline()
        val veteran = pushupTimeline()
        var date = dates.first()

        // The veteran arrives with the same twelve sessions behind them, at the same prescription.
        val veteranDates = AdaptiveLoadTimeline.scheduleFrom(date.minusWeeks(4), sessions = 12)
        for (past in veteranDates) veteran.seedSession(past, full(veteran.nextPlanned))

        val noviceRows = mutableListOf<ScenarioRow>()
        val veteranRows = mutableListOf<ScenarioRow>()
        repeat(12) {
            noviceRows += novice.runSession(date, full(novice.nextPlanned))
            veteranRows += veteran.runSession(date, full(veteran.nextPlanned))
            date = next(date)
        }

        printTable("d1. novice, 12 sessions from zero history", noviceRows)
        printTable("d2. veteran, the same 12 sessions with 4 weeks behind them", veteranRows)

        val noviceGrowth = noviceRows.count { it.rose }
        val veteranGrowth = veteranRows.count { it.rose }
        assertNotEquals(
            "the same performance over different histories must not produce the same curve: " +
                "novice grew $noviceGrowth times, veteran $veteranGrowth",
            noviceGrowth,
            veteranGrowth
        )
        assertTrue(
            "the veteran, with the settled history, is the one who moves first",
            veteranGrowth > noviceGrowth
        )
    }

    // ---- e. poor results -------------------------------------------------------------------------

    @Test
    fun e2ASetBelowItsTargetSettlesTheNextOneAndGrowthResumes() {
        val trend = pushupTimeline()
        var date = dates.first()
        val rows = mutableListOf<ScenarioRow>()

        repeat(6) {
            rows += trend.runSession(date, full(trend.nextPlanned))
            date = next(date)
        }
        val asked = trend.nextPlanned

        // The last set comes in two repetitions short of its target; the others are met.
        val underTarget = asked.dropLast(1) + (asked.last() - 2)
        rows += trend.runSession(date, underTarget)
        date = next(date)
        val corrected = trend.nextPlanned
        printTable("e2. one set below its target", rows)

        assertEquals(
            "the next session is asked for what was actually performed, set by set: " +
                "$asked -> $corrected",
            underTarget,
            corrected
        )
        assertEquals(
            "and the correction says what it is rather than being reported as a trend",
            "BELOW_TARGET_CORRECTION",
            rows.last().reason
        )

        val resumed = mutableListOf<ScenarioRow>()
        repeat(3) {
            resumed += trend.runSession(date, full(trend.nextPlanned))
            date = next(date)
        }
        printTable("e2. growth after the correction", resumed)

        assertTrue(
            "the correction does not restart the progression cooldown: the first good session after it is " +
                "the one the confirmation rule was waiting for, so the load is raised no later than the " +
                "second: $resumed",
            resumed.take(2).any { row -> row.rose }
        )
    }

    @Test
    fun eMissedRepsNeverRaiseTheLoad() {
        val trend = pushupTimeline()
        var date = dates.first()
        val rows = mutableListOf<ScenarioRow>()

        repeat(6) {
            rows += trend.runSession(date, full(trend.nextPlanned))
            date = next(date)
        }
        val earned = trend.nextPlanned

        // Four sessions in a row where only the first two of three sets happen.
        repeat(4) {
            val planned = trend.nextPlanned
            rows += trend.runSession(date, planned.dropLast(1))
            assertFalse(
                "a session that missed a set never raises the next one: $planned -> ${rows.last().nextPlanned}",
                rows.last().nextPlanned.sum() > planned.sum()
            )
            date = next(date)
        }
        printTable("e. four sessions that each missed their last set", rows)

        assertTrue(
            "a family that stops completing its work must not still be where its good weeks left it: " +
                "$earned -> ${trend.nextPlanned}",
            trend.nextPlanned.sum() < earned.sum()
        )
    }

    // ---- f. top of the range ---------------------------------------------------------------------

    @Test
    fun fTheTopOfTheRangeStepsTheVariantWhenThePlanPresentsIt() {
        val relation = pushupLadder()
        val trend = pushupTimeline(
            availableExerciseIds = relation.variants.map { variant -> variant.exerciseId }.toSet()
        )
        var date = dates.first()
        val rows = mutableListOf<ScenarioRow>()

        repeat(40) {
            rows += trend.runSession(date, full(trend.nextPlanned))
            date = next(date)
        }
        printTable("f1. push-ups to the top of the range, the whole ladder presented", rows)

        val climb = rows.take(20).map { row -> row.nextPlanned.sum() }
        assertTrue(
            "the load climbs inside the exercise's own range before anything else: $climb",
            climb.last() > climb.first()
        )
        assertTrue(
            "at the top of the range the family steps to a harder variant",
            rows.any { row -> row.nextExerciseId != PUSHUP }
        )
    }

    @Test
    fun f2TheTopOfTheRangeIntroducesTheHarderVariantThePlanDoesNotPresent() {
        val trend = pushupTimeline()
        var date = dates.first()
        val rows = mutableListOf<ScenarioRow>()

        repeat(56) {
            rows += trend.runSession(date, full(trend.nextPlanned))
            date = next(date)
        }
        printTable("f2. push-ups to the top of the range, only the presented variant in the plan", rows)

        val climbed = rows.takeWhile { row -> row.nextExerciseId == PUSHUP }
        assertTrue(
            "the load climbs inside the exercise's own range before the exercise changes: " +
                "${climbed.map { row -> row.nextPlanned.sum() }}",
            climbed.size > 1 && climbed.last().nextPlanned.sum() > climbed.first().nextPlanned.sum()
        )
        assertTrue(
            "and only once the range is used does the family step to a variant the plan does not present",
            rows.any { row -> row.nextExerciseId == "pushups_wide" }
        )
        assertEquals(
            "the rung the plan never presented is still one the family declares",
            ProgramAdaptiveReason.CEILING_REACHED.name,
            rows.last().reason
        )
        assertEquals(
            "and at the top of the whole ladder the engine holds where it is",
            AdaptiveAction.HOLD.name,
            rows.last().action?.name
        )
    }

    @Test
    fun f3AFamilyWithOneRungClimbsItsOwnRangeAndHoldsAtTheTop() {
        val rows = plankTimeline(sessions = PLANK_SESSIONS, relation = plankSingleRung())
        printTable("f3. a plank hold declared as one rung", rows)

        assertTrue(
            "a family declared as one rung still advances within its own range: " +
                "${rows.map { row -> row.nextPlanned }}",
            rows.any { row -> row.rose }
        )
        assertTrue(
            "and never leaves it",
            rows.all { row -> row.nextPlanned.all { amount -> amount in PLANK_RANGE } }
        )
        val atTheTop = rows.last()
        assertEquals(
            "at the top of the range it holds, and says so with a reason the UI can show",
            ProgramAdaptiveReason.CEILING_REACHED.name,
            atTheTop.reason
        )
        assertEquals(AdaptiveAction.HOLD.name, atTheTop.action?.name)
    }

    @Test
    fun f4AFamilyWithNoDeclaredRelationIsReportedNotAdapted() {
        val rows = plankTimeline(sessions = PLANK_SESSIONS, relation = null)
        printTable("f4. a plank hold, a family with no declared relation at all", rows)

        assertTrue(
            "a family the catalogue declares nothing for is reported, not adapted and not crashed on",
            rows.all { row -> row.reason == AdaptiveLoadTimeline.NO_DECLARED_LADDER }
        )
        assertTrue(
            "and the catalogue's own start is what it keeps presenting",
            rows.all { row -> row.nextPlanned == row.planned }
        )
    }

    // ---- g. semi-automatic -----------------------------------------------------------------------

    @Test
    fun gAUserAuthoredElementIsRespectedAndTheRestStillAdapts() {
        val authored = steadyTimeline(sessions = 8, ownership = ProgramElementOwnership.USER_AUTHORED)
        printTable("g1. a user-authored element in a generated Program", authored)

        assertTrue(
            "the element the user built is never changed by the adaptive layer",
            authored.all { row -> row.nextPlanned == row.planned && row.nextExerciseId == row.exerciseId }
        )
        assertTrue(
            "and the hold says whose element it is",
            authored.all { row -> row.reason == "USER_AUTHORED_ELEMENT" }
        )

        // The same plan and the same performances, with the element left automatic: ownership is the only
        // difference between the two runs.
        val automatic = steadyTimeline(sessions = 8)
        printTable("g2. the same element left automatic", automatic)
        assertTrue(
            "an automatic element of the same Program still receives the adaptation",
            automatic.any { row -> row.rose }
        )
    }

    // ---- h. a rotation: each family appears on its own days --------------------------------------

    /**
     * Rule 2 on a rotation. The family is trained once every sixth day, so the window holds four
     * comparable exposures only from its fifth appearance: growth is measured in the family's own
     * occurrences, not in the plan's workouts.
     */
    @Test
    fun h1AGrowthStepOnARotationArrivesOnTheFifthAppearance() {
        val trend = pushupTimeline()
        val rows = rotationDates(RECURRENCE_SIX, 6).map { date -> trend.runSession(date, full(trend.nextPlanned)) }
        printTable("h1. a family the plan presents every sixth day, every set completed", rows)

        assertTrue(
            "four comparable exposures are in the window only from the fifth appearance on, so the " +
                "first four cannot raise anything: ${rows.take(4).map { row -> row.reason }}",
            rows.take(4).none { row -> row.rose }
        )
        assertTrue(
            "and the fifth does: ${rows.map { row -> row.nextPlanned }}",
            rows[4].rose
        )
        assertEquals(
            "twenty-four calendar days after the first appearance, not the two sessions a three-a-week " +
                "rhythm needs",
            24L,
            ChronoUnit.DAYS.between(rows.first().date, rows[4].date)
        )
    }

    /**
     * The window measures **occurrences**, and a four-week lookback holds three of a family's
     * twelve-day appearances — one fewer than the evidence gate requires. On this rotation growth is
     * therefore not late, it is unreachable, and the family is told it is plateaued.
     */
    @Test
    fun h2ATwelveDayRotationNeverConfirmsGrowth() {
        val trend = pushupTimeline()
        val rows = rotationDates(RECURRENCE_TWELVE, 12).map { date -> trend.runSession(date, full(trend.nextPlanned)) }
        printTable("h2. a family the plan presents every twelfth day, every set completed", rows)

        assertEquals(
            "twelve appearances over 132 days and not one rise: the window holds at most three of them " +
                "and four are required",
            0,
            rows.count { row -> row.rose }
        )
        assertEquals(
            "and the answer names a plateau rather than the appearances the plan does not schedule",
            ProgramAdaptiveReason.STABLE_PLATEAU.name,
            rows.last().reason
        )
    }

    /** Rule 3 on a rotation: one missed appearance is one missed appearance, not a break. */
    @Test
    fun h3AMissedAppearanceOnARotationIsNotAReturn() {
        val trend = pushupTimeline()
        val dates = rotationDates(RECURRENCE_TWELVE, 6)
        val rows = dates.take(5).map { date -> trend.runSession(date, full(trend.nextPlanned)) }
        val beforeSkip = trend.nextPlanned

        trend.recordSkip(dates[5])
        val back = trend.runSession(dates[5].plusDays(RECURRENCE_TWELVE), full(trend.nextPlanned))
        printTable("h3. a skipped appearance at a twelve-day rhythm", rows + back)

        assertEquals(
            "a missed appearance neither grows nor lowers the next one: $beforeSkip -> ${back.nextPlanned}",
            beforeSkip,
            back.nextPlanned
        )
        assertNotEquals(
            "and two of its own intervals away is not a return: ${back.reason}",
            ProgramAdaptiveReason.RETURN_AFTER_GAP.name,
            back.reason
        )
    }

    /**
     * Rule 4 on a rotation: the return floor is stated in the family's own intervals, so the same
     * absence reads differently on a twelve-day rhythm than on a three-a-week one.
     */
    @Test
    fun h4AReturnOnARotationIsMeasuredInItsOwnIntervals() {
        // The plan presents more than the range floor: at the floor a return has nothing to take away
        // and the engine answers FLOOR_REACHED instead of a lighter prescription.
        val aboveTheFloor = listOf(8, 8, 8)
        val shortBreak = rotationTimelineAfterThreeAppearances(aboveTheFloor)
        val beforeShort = shortBreak.trend.nextPlanned
        val afterThirty = shortBreak.trend.runSession(
            shortBreak.lastDate.plusDays(30),
            full(shortBreak.trend.nextPlanned)
        )
        assertNotEquals(
            "thirty days is two and a half of this family's own intervals, under the multiple the " +
                "return rule needs: ${afterThirty.reason}",
            ProgramAdaptiveReason.RETURN_AFTER_GAP.name,
            afterThirty.reason
        )
        assertTrue(
            "so the next appearance is not lightened: $beforeShort -> ${afterThirty.nextPlanned}",
            afterThirty.nextPlanned.sum() >= beforeShort.sum()
        )
        printTable("h4. thirty days away", listOf(afterThirty))

        val longBreak = rotationTimelineAfterThreeAppearances(aboveTheFloor)
        val beforeLong = longBreak.trend.nextPlanned
        val afterSixty = longBreak.trend.runSession(
            longBreak.lastDate.plusDays(60),
            full(longBreak.trend.nextPlanned)
        )
        assertEquals(
            "while sixty days — five of them — is a return",
            ProgramAdaptiveReason.RETURN_AFTER_GAP.name,
            afterSixty.reason
        )
        assertTrue(
            "and it comes back lighter: $beforeLong -> ${afterSixty.nextPlanned}",
            afterSixty.nextPlanned.sum() < beforeLong.sum()
        )
        printTable("h4. sixty days away", listOf(afterSixty))
    }

    /**
     * Rule 5 on a rotation: the correction needs one occurrence, not a confirmed direction, and it is
     * written onto the family's **next** appearance — which on this rotation is twelve days away.
     */
    @Test
    fun h5ABelowTargetSetOnARotationSettlesTheNextAppearance() {
        // A correction can only be expressed above the range floor: six is this exercise's floor, so a
        // plan presenting six has nothing to take away.
        val trend = pushupTimeline(amounts = listOf(8, 8, 8))
        val first = trend.runSession(rotationDates(RECURRENCE_TWELVE, 1).single(), full(trend.nextPlanned))
        val asked = trend.nextPlanned
        val underTarget = asked.dropLast(1) + (asked.last() - 2)

        val corrective = trend.runSession(first.date.plusDays(RECURRENCE_TWELVE), underTarget)
        printTable("h5. one set below its target on a twelve-day rotation", listOf(first, corrective))

        assertEquals(
            "the family's next appearance is asked for what was actually performed, set by set: " +
                "$asked -> ${corrective.nextPlanned}",
            underTarget,
            corrective.nextPlanned
        )
        assertEquals(
            "one occurrence is enough: the correction is not a direction the confirmation rule reads",
            ProgramAdaptiveReason.BELOW_TARGET_CORRECTION.name,
            corrective.reason
        )
        assertEquals(
            "and it lands on that appearance, twelve days after the short one",
            RECURRENCE_TWELVE,
            ChronoUnit.DAYS.between(first.date, corrective.date)
        )
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private val dates = AdaptiveLoadTimeline.SESSION_DATES

    /** [count] appearances of one family, the plan presenting it once every [days] days. */
    private fun rotationDates(days: Long, count: Int): List<LocalDate> =
        (0 until count).map { index -> ROTATION_START.plusDays(index * days) }

    private fun rotationTimelineAfterThreeAppearances(
        amounts: List<Int> = repPrescriptionOf(PUSHUP)
    ): BreakPoint {
        val trend = pushupTimeline(amounts = amounts)
        val appearances = rotationDates(RECURRENCE_TWELVE, 3)
        appearances.forEach { date -> trend.runSession(date, full(trend.nextPlanned)) }
        return BreakPoint(trend, appearances.last())
    }

    private fun next(date: LocalDate): LocalDate =
        requireNotNull(dates.firstOrNull { it.isAfter(date) }) { "the scenario's calendar ran out" }

    private fun full(amounts: List<Int>): List<Int> = amounts

    private fun pushupLadder(): ProgramProgressionRelation =
        ProductionProgressionRelationDefinitions.pushups

    private fun pushupTimeline(
        availableExerciseIds: Set<String> = setOf(PUSHUP),
        ownership: ProgramElementOwnership = ProgramElementOwnership.AUTOMATIC,
        amounts: List<Int> = repPrescriptionOf(PUSHUP)
    ): AdaptiveLoadTimeline = AdaptiveLoadTimeline(
        familyId = PUSHUP_FAMILY,
        dimension = PrescriptionDimension.REP_BASED,
        rangeOf = { exerciseId -> pushupRanges[exerciseId] },
        relation = pushupLadder(),
        availableExerciseIds = availableExerciseIds,
        subjectExerciseId = PUSHUP,
        subjectAmounts = amounts,
        ownership = ownership
    )

    private fun steadyTimeline(
        sessions: Int,
        ownership: ProgramElementOwnership = ProgramElementOwnership.AUTOMATIC
    ): List<ScenarioRow> {
        val trend = pushupTimeline(ownership = ownership)
        var date = dates.first()
        val rows = mutableListOf<ScenarioRow>()
        repeat(sessions) {
            rows += trend.runSession(date, full(trend.nextPlanned))
            date = next(date)
        }
        return rows
    }

    private fun timelineWithSixSessions(): BreakPoint {
        val trend = pushupTimeline()
        var date = dates.first()
        repeat(6) {
            trend.runSession(date, full(trend.nextPlanned))
            date = next(date)
        }
        return BreakPoint(trend, dates.last { it < date })
    }

    private fun plankTimeline(
        sessions: Int,
        relation: ProgramProgressionRelation? = null
    ): List<ScenarioRow> {
        val trend = AdaptiveLoadTimeline(
            familyId = PLANK_FAMILY,
            dimension = PrescriptionDimension.TIME_BASED,
            rangeOf = { ExerciseRange(timeRange = PLANK_RANGE) },
            relation = relation,
            availableExerciseIds = setOf(PLANK),
            subjectExerciseId = PLANK,
            subjectAmounts = listOf(20, 20, 20)
        )
        var date = dates.first()
        return (1..sessions).map {
            trend.runSession(date, trend.nextPlanned).also { date = next(date) }
        }
    }

    /**
     * A ladder-less family's one rung: the exercise the plan already presents, at the position it starts
     * on. Nothing is authored here — no second exercise, no level above it.
     */
    private fun plankSingleRung(): ProgramProgressionRelation = ProgramProgressionRelation(
        familyId = PLANK_FAMILY,
        variants = listOf(
            ProgramProgressionVariant(
                level = 0,
                exerciseId = PLANK,
                prescription = TimePrescription(listOf(20, 20, 20))
            )
        )
    )

    private data class BreakPoint(val trend: AdaptiveLoadTimeline, val lastDate: LocalDate)

    /**
     * What the *planner* prescribes for the family: the plan an ordinary generated Program holds, read
     * once so the timeline starts where the planner starts it.
     */
    private fun repPrescriptionOf(exerciseId: String): List<Int> {
        val plan = generatedPlan()
        val amounts = plan.slots
            .flatMap { slot -> slot.elements }
            .filter { element -> element.exerciseId == exerciseId }
            .map { element -> element.prescription.perSetTargets }
            .firstOrNull()
            ?: error("the generated plan does not present '$exerciseId'")
        return amounts
    }

    private fun generatedPlan(): GeneratedPlan = GeneratedPlanner.plan(
        GeneratedPlannerRig.request(
            schedule = ProgramSchedule.FlexiblePerWeek(3),
            duration = ProgramDuration.FixedDays(28),
            candidates = listOf(
                GeneratedPlannerRig.candidate(
                    id = PUSHUP,
                    familyId = PUSHUP_FAMILY,
                    focuses = setOf(Focus.PUSH),
                    repRange = PUSHUP_RANGE,
                    sets = 3
                )
            )
        )
    )

    // ---- the table -------------------------------------------------------------------------------

    private fun table(title: String, rows: List<ScenarioRow>): String = buildString {
        appendLine()
        appendLine(title)
        appendLine(
            listOf("session", "exercise", "planned", "actual", "next", "rule")
                .joinToString(" | ")
        )
        rows.forEach { row ->
            appendLine(
                listOf(
                    row.session.toString(),
                    row.exerciseId,
                    row.planned.joinToString("/"),
                    if (row.actual.isEmpty()) "skipped" else row.actual.joinToString("/"),
                    row.next,
                    row.reason
                ).joinToString(" | ")
            )
        }
    }

    private fun printTable(title: String, rows: List<ScenarioRow>) = println(table(title, rows))

    private companion object {

        const val PUSHUP = "pushups"
        const val PUSHUP_FAMILY = "pushups"
        const val PLANK = "plank"
        const val PLANK_FAMILY = "plank"

        /** The catalogue's own span for the exercise, as the fixture states it. */
        val PUSHUP_RANGE = 6..15
        val PLANK_RANGE = 20..60

        /** Each ladder rung's own span — a different exercise states a different one. */
        val pushupRanges: Map<String, ExerciseRange> = mapOf(
            "pushups_knee" to ExerciseRange(repRange = 6..20),
            "pushups" to ExerciseRange(repRange = PUSHUP_RANGE),
            "pushups_wide" to ExerciseRange(repRange = 5..12),
            "decline_pushups" to ExerciseRange(repRange = 5..12)
        )

        /** Twelve sessions: four weeks of a three-day rhythm. */
        const val TOTAL = 12

        const val PLANK_SESSIONS = 20

        /** The first appearance of a family a generated rotation presents on its own days. */
        val ROTATION_START: LocalDate = LocalDate.parse("2026-01-05")

        /** A family the plan presents every sixth day, and one it presents every twelfth. */
        const val RECURRENCE_SIX: Long = 6L
        const val RECURRENCE_TWELVE: Long = 12L
    }
}
