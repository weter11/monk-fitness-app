package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * The scheduling decision, decided without a database, a clock or a device.
 *
 * [SlotPlanner] is a pure function — a [ScheduleRequest] in, a [SlotPlan] out — so everything §20 rules
 * on can be stated as arithmetic over dates: which dates a revision trains on, which of them still need
 * an opportunity, what happens to an opportunity whose date passed, and what a revision change does to
 * the ones still ahead. The dates below are written out in full rather than computed from the
 * production code: a suite that re-derives its expectations would agree with the Scheduler about its own
 * algorithm, and the point is to agree about the **calendar** — the fixture's anchor `2026-09-14` is a
 * Monday, and every expectation below is a date a reader can check by hand.
 */
class SlotPlannerTest {

    private val programId = ProgramId("program-pure")

    private val anchor = LocalDate.parse("2026-09-14")

    /** The plan's identity source. A counter, so a created slot's id is readable in a failure message. */
    private class TestSlotIds(private val tag: String = "slot") : SlotIdSource {
        private var minted = 0
        override fun newId(): SlotId {
            minted += 1
            return SlotId("$tag-${minted.toString().padStart(3, '0')}")
        }
    }

    // ------------------------------------------------------------------ generation (§20)

    @Test
    fun aFixedWeeklyRevisionIsPlannedOnTheDaysTheUserNamedAndOnNoOthers() {
        val plan = SlotPlanner.plan(
            request(revision(), asOf = anchor)
        ).asScheduled()

        assertEquals(
            "the user named Monday, Wednesday and Friday, so those are the dates — thirteen of them " +
                "between the anchor and the end of a thirty-day run",
            listOf(
                "2026-09-14", "2026-09-16", "2026-09-18", "2026-09-21", "2026-09-23", "2026-09-25",
                "2026-09-28", "2026-09-30", "2026-10-02", "2026-10-05", "2026-10-07", "2026-10-09",
                "2026-10-12"
            ),
            plan.createdDates.map { it.toString() }
        )
        assertEquals(
            "and the plan cycles through its days in order, three days at a time (§9: a repeated " +
                "occurrence is the revision's own plan day, never a new one)",
            listOf(
                "day-1", "day-2", "day-3", "day-1", "day-2", "day-3", "day-1", "day-2", "day-3",
                "day-1", "day-2", "day-3", "day-1"
            ),
            plan.create.map { it.programDayId.value }
        )
        assertTrue(
            "every opportunity is open, and none of them carries a session or a result",
            plan.create.all {
                it.status == SlotStatus.PLANNED && it.attempts.isEmpty() && it.completedAt == null
            }
        )
        assertTrue(
            "and the rest day in the plan is an opportunity like any other — a slot without a " +
                "session, which is not a workout that scored zero (§20)",
            plan.create.any { it.programDayId.value == "day-2" }
        )
    }

    @Test
    fun theFlexibleSpreadIsPinnedForEverySupportedFrequency() {
        assertEquals(setOf(DayOfWeek.MONDAY), flexibleSpread(1))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), flexibleSpread(2))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), flexibleSpread(3))
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY),
            flexibleSpread(4)
        )
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
            flexibleSpread(5)
        )
        assertEquals(
            setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
            ),
            flexibleSpread(6)
        )
        assertEquals(DayOfWeek.entries.toSet(), flexibleSpread(7))
        assertEquals(
            "every count produces exactly as many weekdays as it names",
            (1..7).map { count -> flexibleSpread(count).size },
            (1..7).toList()
        )
    }

    @Test
    fun aFlexibleFrequencyIsSpreadDeterministicallyAndNotFromItsOwnHistory() {
        val twoPerWeek = SlotPlanner.plan(
            request(revision(schedule = ProgramSchedule.FlexiblePerWeek(2)), asOf = anchor)
        ).asScheduled()

        assertEquals(
            "two sessions a week land on Monday and Thursday — a function of the stated frequency " +
                "alone, so the same count gives the same days in every process and every month",
            listOf(
                "2026-09-14", "2026-09-17", "2026-09-21", "2026-09-24", "2026-09-28", "2026-10-01",
                "2026-10-05", "2026-10-08", "2026-10-12"
            ),
            twoPerWeek.createdDates.map { it.toString() }
        )

        val onePerWeek = SlotPlanner.plan(
            request(revision(schedule = ProgramSchedule.FlexiblePerWeek(1)), asOf = anchor)
        ).asScheduled()

        assertEquals(
            "and one a week is one date a week, never an average of what happens to be there",
            listOf("2026-09-14", "2026-09-21", "2026-09-28", "2026-10-05", "2026-10-12"),
            onePerWeek.createdDates.map { it.toString() }
        )

        val fivePerWeek = SlotPlanner.plan(
            request(revision(schedule = ProgramSchedule.FlexiblePerWeek(5)), asOf = anchor)
        ).asScheduled()

        assertEquals(
            "five a week is five weekdays of every seven, including the two the numbering reaches last",
            listOf(
                "2026-09-14", "2026-09-15", "2026-09-16", "2026-09-18", "2026-09-19", "2026-09-21",
                "2026-09-22", "2026-09-23", "2026-09-25", "2026-09-26", "2026-09-28", "2026-09-29",
                "2026-09-30", "2026-10-02", "2026-10-03", "2026-10-05", "2026-10-06", "2026-10-07",
                "2026-10-09", "2026-10-10", "2026-10-12", "2026-10-13"
            ),
            fivePerWeek.createdDates.map { it.toString() }
        )
    }

    @Test
    fun anIndefiniteRevisionKeepsExactlyThirtyDaysOfPlanning() {
        val plan = SlotPlanner.plan(
            request(revision(duration = ProgramDuration.Indefinite), asOf = anchor)
        ).asScheduled()

        assertEquals("the window starts at the first date that can still be trained", anchor, plan.onlyWindow.firstDate)
        assertEquals(
            "and it reaches exactly thirty days, which is what an indefinite Program keeps (§20)",
            LocalDate.parse("2026-10-13"),
            plan.onlyWindow.lastDate
        )
        assertEquals(30, plan.onlyWindow.dayCount)
        assertTrue(
            "the horizon is a window of dates, never a total the program is a fraction of (§21)",
            plan.createdDates.last() <= plan.onlyWindow.lastDate
        )
    }

    @Test
    fun aFixedRevisionIsPlannedToItsOwnEndAndNotToAHorizon() {
        val plan = SlotPlanner.plan(
            request(revision(duration = ProgramDuration.FixedDays(40)), asOf = anchor)
        ).asScheduled()

        assertEquals(
            "a revision whose end is a fact is planned to that end: forty days is a window of forty " +
                "days, not of thirty",
            LocalDate.parse("2026-10-23"),
            plan.onlyWindow.lastDate
        )
        assertEquals(40, plan.onlyWindow.dayCount)
        assertEquals(
            "and every Monday, Wednesday and Friday of it is an opportunity",
            LocalDate.parse("2026-10-23"),
            plan.createdDates.last()
        )
    }

    @Test
    fun thePlanStartsAtTheFirstScheduledDateOnOrAfterTheAnchor() {
        val thursday = LocalDate.parse("2026-10-01")

        val plan = SlotPlanner.plan(
            request(revision(duration = ProgramDuration.FixedDays(14)), anchor = thursday, asOf = thursday)
        ).asScheduled()

        assertEquals(
            "the anchor is a Thursday and the plan trains on Mondays, Wednesdays and Fridays, so it " +
                "begins on the first such date — Friday — and not on the anchor itself",
            listOf("2026-10-02", "2026-10-05", "2026-10-07", "2026-10-09", "2026-10-12", "2026-10-14"),
            plan.createdDates.map { it.toString() }
        )
        assertEquals(
            "and the cycle starts there: the anchor's own weekday never consumes a plan day",
            listOf("day-1", "day-2", "day-3", "day-1", "day-2", "day-3"),
            plan.create.map { it.programDayId.value }
        )
    }

    @Test
    fun anAnchorInTheFutureIsNotPlannedBackwards() {
        val planned = LocalDate.parse("2026-10-01")

        val plan = SlotPlanner.plan(
            request(
                revision(duration = ProgramDuration.FixedDays(14)),
                anchor = planned,
                asOf = LocalDate.parse("2026-09-20")
            )
        ).asScheduled()

        assertEquals(
            "a pass made before the program's first day plans nothing before it",
            planned,
            plan.onlyWindow.firstDate
        )
        assertEquals("2026-10-02", plan.createdDates.first().toString())
    }

    @Test
    fun aDateThatAlreadyHoldsAnOpportunityIsNeverPlannedAgain() {
        val first = SlotPlanner.plan(request(revision(), asOf = anchor)).asScheduled()

        val second = SlotPlanner.plan(
            request(revision(), asOf = anchor, slots = first.create)
        ).asScheduled()

        assertTrue(
            "one date holds one opportunity, so applying the same pass to what it produced decides " +
                "nothing at all",
            second.isEmpty
        )
        assertEquals(
            "and the dates it saw are still the revision's own",
            first.createdDates,
            second.scheduledDates
        )
    }

    @Test
    fun theSameRequestAlwaysProducesTheSameDatesAndTheSamePlanDays() {
        val once = SlotPlanner.plan(request(revision(), asOf = anchor)).asScheduled()
        val twice = SlotPlanner.plan(request(revision(), asOf = anchor)).asScheduled()

        assertEquals(
            "no random source, no clock and no state: the same inputs give the same opportunities, " +
                "in the same order, with the same ids (§26, §33)",
            once.create,
            twice.create
        )
        assertEquals(once.create, twice.create)
    }

    // ------------------------------------------------------------------ missed opportunities (§12, §20)

    @Test
    fun anOpportunityBehindTheUserIsMissedAndTodaysIsNot() {
        val plan = SlotPlanner.plan(
            request(
                revision(),
                asOf = anchor,
                slots = listOf(
                    slot("yesterday", "2026-09-13"),
                    slot("today", "2026-09-14"),
                    slot("tomorrow", "2026-09-16"),
                    slot("attempted-yesterday", "2026-09-13", attempts = listOf(SessionId("session-1")))
                )
            )
        ).asScheduled()

        assertEquals(
            "the opportunities that passed are missed; today's is still ahead and tomorrow's is ahead " +
                "of that — a date is not missed at midnight",
            listOf("yesterday", "attempted-yesterday"),
            plan.miss.map { it.value }
        )
        assertTrue(
            "neither open one is touched, and neither is superseded: an opportunity still ahead of the " +
                "user is left exactly as it is",
            plan.supersede.isEmpty() &&
                plan.miss.none { it.value in listOf("today", "tomorrow") }
        )
        assertTrue(
            "and neither of their dates is planned again, because a date holds one opportunity",
            plan.createdDates.none { it.toString() in listOf("2026-09-14", "2026-09-16") }
        )
    }

    @Test
    fun aMissedOpportunityMovesNoOtherDateAndNoOtherPlanDay() {
        val clean = SlotPlanner.plan(request(revision(), asOf = anchor)).asScheduled()

        val occupied = listOf("2026-09-16", "2026-09-18")
        val withAMiss = SlotPlanner.plan(
            request(
                revision(),
                asOf = anchor,
                slots = listOf(
                    slot("missed", "2026-09-16", status = SlotStatus.MISSED),
                    slot("attempted", "2026-09-18", attempts = listOf(SessionId("session-1")))
                )
            )
        ).asScheduled()

        assertEquals(
            "a miss does not slide the schedule: the dates a pass would add are the same dates, in the " +
                "same order, and the only ones missing are the two that already hold an opportunity " +
                "(§20: no automatic whole-schedule sliding after a missed workout)",
            clean.createdDates.map { it.toString() }.filter { it !in occupied },
            withAMiss.createdDates.map { it.toString() }
        )
        assertEquals(
            "and the plan day a date presents is the same too: the cycle counts scheduled dates from " +
                "the anchor, never completions and never misses",
            clean.create.filter { it.plannedFor.toString() !in occupied }.map { it.programDayId.value },
            withAMiss.create.map { it.programDayId.value }
        )
        assertTrue(
            "nor is a missed or attempted opportunity destroyed by the pass",
            withAMiss.supersede.isEmpty() && withAMiss.miss.isEmpty()
        )
    }

    @Test
    fun aCompletedOpportunityIsNeverTouchedAgain() {
        val completed = slot("done", "2026-09-16").copy(
            status = SlotStatus.COMPLETED,
            attempts = listOf(SessionId("session-1")),
            completedAt = Instant.parse("2026-09-16T08:00:00Z")
        )

        val plan = SlotPlanner.plan(
            request(revision(), asOf = LocalDate.parse("2026-09-28"), slots = listOf(completed))
        ).asScheduled()

        assertTrue(
            "a workout that happened is history: it is not missed, not superseded, and its date is not " +
                "planned again (§19, §20)",
            plan.miss.isEmpty() && plan.supersede.isEmpty() &&
                plan.createdDates.none { it.toString() == "2026-09-16" }
        )
    }

    // ------------------------------------------------------------------ pauses (§3, §20)

    @Test
    fun anOpportunityThatPassedInsideAPauseIsSupersededAndNotMissed() {
        val plan = SlotPlanner.plan(
            request(
                revision(),
                asOf = LocalDate.parse("2026-09-28"),
                slots = listOf(
                    slot("before-the-pause", "2026-09-14"),
                    slot("inside-the-pause", "2026-09-18")
                ),
                pauses = listOf(PausedInterval(LocalDate.parse("2026-09-16"), LocalDate.parse("2026-09-25")))
            )
        ).asScheduled()

        assertEquals(
            "the opportunity that passed while the program was not paused was expected, so it is missed",
            listOf("before-the-pause"),
            plan.miss.map { it.value }
        )
        assertEquals(
            "the one that passed inside the pause was not expected, so it is superseded instead " +
                "(§20: 'superseded is not missed: the user was not expected to train it')",
            listOf("inside-the-pause"),
            plan.supersede.map { it.slotId.value }
        )
        assertEquals(
            SupersessionReason.THE_OPPORTUNITY_PASSED_WHILE_PAUSED,
            plan.supersede.single().reason
        )
        assertTrue(
            "and no opportunity is in both lists: the two are different facts about one date",
            plan.miss.map { it.value }.none { it in plan.supersede.map { slot -> slot.slotId.value } }
        )
    }

    @Test
    fun anOpportunityAheadOfTheUserInsideAPauseIsLeftOpen() {
        val plan = SlotPlanner.plan(
            request(
                revision(),
                asOf = LocalDate.parse("2026-09-21"),
                slots = listOf(slot("ahead", "2026-09-23")),
                pauses = listOf(PausedInterval(LocalDate.parse("2026-09-16")))
            )
        ).asScheduled()

        assertTrue(
            "a pause is a fact about now, not a cancellation of what is coming: an opportunity still " +
                "ahead of the user is left exactly as it is",
            plan.supersede.isEmpty() && plan.miss.isEmpty()
        )
    }

    @Test
    fun aPausedDateIsNotAPlanningDateAndThePlanKeepsItsPlaceOnTheCalendar() {
        val plan = SlotPlanner.plan(
            request(
                revision(duration = ProgramDuration.Indefinite),
                asOf = LocalDate.parse("2026-09-28"),
                pauses = listOf(PausedInterval(LocalDate.parse("2026-09-16"), LocalDate.parse("2026-09-25")))
            )
        ).asScheduled()

        assertEquals(
            "nothing is planned inside the pause, and the horizon runs from the pass date",
            listOf(
                "2026-09-28", "2026-09-30", "2026-10-02", "2026-10-05", "2026-10-07", "2026-10-09",
                "2026-10-12", "2026-10-14", "2026-10-16", "2026-10-19", "2026-10-21", "2026-10-23",
                "2026-10-26"
            ),
            plan.createdDates.map { it.toString() }
        )
        assertEquals(
            "and the paused interval renumbers nothing: the first opportunity after it presents the " +
                "plan day the calendar gives that date — the paused dates keep their place in the " +
                "cycle, so a date's plan day never depends on when the pass was made (§3 freezes " +
                "active program *time* and missed-opportunity logic, not the plan's dates)",
            "day-1",
            plan.create.first().programDayId.value
        )
    }

    @Test
    fun anOpenPauseFreezesPlanningEntirely() {
        val plan = SlotPlanner.plan(
            request(
                revision(duration = ProgramDuration.Indefinite),
                asOf = LocalDate.parse("2026-09-21"),
                pauses = listOf(PausedInterval(LocalDate.parse("2026-09-16")))
            )
        ).asScheduled()

        assertTrue(
            "an open pause covers every date from its start onward, so there is no date to plan: the " +
                "Scheduler has no way to know which dates belong to the program after it",
            plan.create.isEmpty() && plan.scheduledDates.isEmpty()
        )
        assertTrue(
            "the window itself is still reported — the horizon is where the plan *would* reach",
            plan.onlyWindow.dayCount == PLANNING_HORIZON_DAYS
        )
    }

    // ------------------------------------------------------------------ revision changes (§20, §27)

    @Test
    fun aDayTheRevisionNoLongerTrainsOnSupersedesTheOpportunityStillAhead() {
        val planned = SlotPlanner.plan(request(revision(), asOf = anchor)).asScheduled()

        val moved = SlotPlanner.plan(
            request(
                revision(schedule = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))),
                asOf = anchor,
                slots = planned.create
            )
        ).asScheduled()

        assertEquals(
            "no Monday, Wednesday or Friday is a date the revision trains on any more, so every one of " +
                "the thirteen future opportunities is superseded, each with the rule that did it",
            planned.create.map { it.slotId.value },
            moved.supersede.map { it.slotId.value }
        )
        assertTrue(
            "and the reason is the revision, not a pause",
            moved.supersede.all {
                it.reason == SupersessionReason.THE_REVISION_NO_LONGER_PRESENTS_THE_DATE
            }
        )
        assertEquals(
            "the dates the new revision does train on are planned from scratch",
            listOf(
                "2026-09-15", "2026-09-17", "2026-09-22", "2026-09-24", "2026-09-29", "2026-10-01",
                "2026-10-06", "2026-10-08", "2026-10-13"
            ),
            moved.createdDates.map { it.toString() }
        )
        assertTrue(
            "nothing is missed simply because a weekday left the schedule",
            moved.miss.isEmpty()
        )
    }

    @Test
    fun shrinkingARevisionSupersedesOnlyTheDatesBeyondItsNewEnd() {
        val planned = SlotPlanner.plan(request(revision(), asOf = anchor)).asScheduled()

        val shortened = SlotPlanner.plan(
            request(
                revision(duration = ProgramDuration.FixedDays(10)),
                asOf = anchor,
                slots = planned.create
            )
        ).asScheduled()

        assertEquals(
            "the revision now runs to 2026-09-23, so the eight opportunities beyond it are the ones it " +
                "can no longer present",
            listOf(
                "2026-09-25", "2026-09-28", "2026-09-30", "2026-10-02", "2026-10-05", "2026-10-07",
                "2026-10-09", "2026-10-12"
            ),
            shortened.supersede.map { superseded ->
                planned.create.single { it.slotId == superseded.slotId }.plannedFor.toString()
            }
        )
        assertEquals(
            "and the five inside the new run are untouched — this is not a regeneration of the " +
                "schedule, it is one rule applied to each date",
            listOf("2026-09-14", "2026-09-16", "2026-09-18", "2026-09-21", "2026-09-23"),
            planned.create
                .filter { it.plannedFor <= LocalDate.parse("2026-09-23") }
                .map { it.plannedFor.toString() }
        )
        assertTrue("nothing new is created for dates that already hold an opportunity", shortened.create.isEmpty())
    }

    @Test
    fun wideningTheSchedulePreservesEveryOpportunityItStillPresents() {
        val planned = SlotPlanner.plan(request(revision(), asOf = anchor)).asScheduled()

        val widened = SlotPlanner.plan(
            request(
                revision(
                    schedule = ProgramSchedule.FixedWeekdays(
                        setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
                    )
                ),
                asOf = anchor,
                slots = planned.create
            )
        ).asScheduled()

        assertTrue(
            "a revision that adds a weekday takes nothing away: every existing opportunity keeps its " +
                "date, its plan day and its status",
            widened.supersede.isEmpty() && widened.miss.isEmpty()
        )
        assertEquals(
            "and the new weekday gains opportunities, from the date the four-day rhythm starts",
            listOf("2026-09-19", "2026-09-26", "2026-10-03", "2026-10-10"),
            widened.createdDates.map { it.toString() }
        )
    }

    @Test
    fun aChangeToThePlansContentSupersedesNothingAndCreatesNothing() {
        val planned = SlotPlanner.plan(request(revision(), asOf = anchor)).asScheduled()

        val reAuthored = SlotPlanner.plan(
            request(
                revision(days = rearrangedPlan()),
                asOf = anchor,
                slots = planned.create
            )
        ).asScheduled()

        assertTrue(
            "the opportunity is the date, not the workout: changing what a Wednesday contains leaves " +
                "every Wednesday exactly where it was, and the revision still presents all thirteen " +
                "dates, so there is nothing to create either",
            reAuthored.isEmpty
        )
    }

    @Test
    fun aRevisionThatReachesFurtherThanTheHorizonSupersedesNothingPastIt() {
        val planned = SlotPlanner.plan(
            request(revision(duration = ProgramDuration.FixedDays(40)), asOf = anchor)
        ).asScheduled()

        val indefinite = SlotPlanner.plan(
            request(
                revision(duration = ProgramDuration.Indefinite),
                asOf = anchor,
                slots = planned.create
            )
        ).asScheduled()

        assertTrue(
            "an indefinite revision has no end, so it still presents the dates past today's thirty-day " +
                "horizon: the horizon is how far ahead the plan is written down, not how far it reaches",
            indefinite.supersede.isEmpty()
        )
        assertTrue(
            "and every one of those dates already holds an opportunity",
            indefinite.create.isEmpty()
        )
    }

    @Test
    fun theRevisionOnlySupersedesWhatIsStillAheadOfTheUser() {
        val past = slot("past", "2026-09-14")
        val ahead = slot("ahead", "2026-09-21")

        val plan = SlotPlanner.plan(
            request(
                revision(schedule = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.TUESDAY))),
                asOf = LocalDate.parse("2026-09-20"),
                slots = listOf(past, ahead)
            )
        ).asScheduled()

        assertEquals(
            "§20 says *future* incompatible slots become superseded: an opportunity that already passed " +
                "was an expectation under the plan that was live then, and a later edit does not " +
                "retroactively cancel it",
            listOf("ahead"),
            plan.supersede.map { it.slotId.value }
        )
        assertEquals(
            "it stays a fact about the past: it was missed, and it is not also superseded",
            listOf("past"),
            plan.miss.map { it.value }
        )
    }

    @Test
    fun aRevisionThatNoLongerPresentsAnyFutureDateStillReconcilesThePast() {
        val plan = SlotPlanner.plan(
            request(
                revision(duration = ProgramDuration.FixedDays(10)),
                asOf = LocalDate.parse("2026-09-30"),
                slots = listOf(slot("long-gone", "2026-09-16"), slot("also-gone", "2026-09-18"))
            )
        )

        assertNull(
            "a run that ended before the pass has no window at all, and the pass says so rather than " +
                "inventing a degenerate one",
            plan.window
        )
        assertTrue("so it creates nothing", plan.create.isEmpty() && plan.scheduledDates.isEmpty())
        assertEquals(
            "and it still reconciles what it finds: the opportunities that passed are missed",
            listOf("long-gone", "also-gone"),
            plan.miss.map { it.value }
        )
        assertTrue(plan.hasNoFutureDate)
    }

    @Test
    fun anOpportunityCarriesNoAmountOfWork() {
        val fields = WorkoutSlot::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }

        assertEquals(
            "a slot is an opportunity and nothing else: the fields are identity, ownership, the " +
                "planned date, what happened and when — there is no repetitions field, no duration " +
                "field and no score for a missed opportunity to be a zero of (§12, §20)",
            listOf(
                "slotId", "programId", "revisionId", "programDayId", "plannedFor", "status", "attempts",
                "completedAt", "targetOccurrenceKey"
            ),
            fields
        )
        assertFalse(
            "so no part of the schedule can be summed into a workout that did not happen",
            fields.any { name ->
                listOf("rep", "set", "duration", "score", "performance", "volume").any { token ->
                    name.contains(token, ignoreCase = true)
                }
            }
        )
    }

    // ------------------------------------------------------------------ a pause renumbers nothing (audit)

    @Test
    fun aPauseDoesNotRenumberThePlanForTheDatesThatFollowIt() {
        val planned = SlotPlanner.plan(
            request(revision(duration = ProgramDuration.Indefinite), asOf = anchor)
        ).asScheduled()
        val lastBeforeThePause = planned.create.last()

        val afterThePause = SlotPlanner.plan(
            request(
                revision(duration = ProgramDuration.Indefinite),
                asOf = LocalDate.parse("2026-09-16"),
                slots = planned.create,
                pauses = listOf(
                    PausedInterval(LocalDate.parse("2026-09-16"), LocalDate.parse("2026-09-25"))
                )
            )
        ).asScheduled()

        assertEquals(
            "the opportunity that follows the paused interval is the next date the revision trains on",
            listOf("2026-10-14"),
            afterThePause.createdDates.map { it.toString() }
        )
        assertEquals("2026-10-12", lastBeforeThePause.plannedFor.toString())
        assertEquals("day-1", lastBeforeThePause.programDayId.value)
        assertEquals(
            "and it presents the plan day that follows the last opportunity before it: a pause stops " +
                "planning inside itself and renumbers nothing, because the plan day a date presents is " +
                "a property of the calendar (§3 freezes active program *time*, not the plan's dates)",
            "day-2",
            afterThePause.create.single().programDayId.value
        )
    }

    @Test
    fun aSinglePausedDateDoesNotCollideTwoConsecutiveDatesOntoTheSamePlanDay() {
        val planned = SlotPlanner.plan(
            request(revision(duration = ProgramDuration.Indefinite), asOf = anchor)
        ).asScheduled()

        val afterThePause = SlotPlanner.plan(
            request(
                revision(duration = ProgramDuration.Indefinite),
                asOf = LocalDate.parse("2026-09-21"),
                slots = planned.create,
                pauses = listOf(
                    PausedInterval(LocalDate.parse("2026-09-16"), LocalDate.parse("2026-09-16"))
                )
            )
        ).asScheduled()

        assertEquals(
            listOf("2026-10-14", "2026-10-16", "2026-10-19"),
            afterThePause.createdDates.map { it.toString() }
        )
        assertEquals(
            "the plan advances one day per planned date across the pause too: the persisted " +
                "2026-10-12 presents day-1, so the dates after it present day-2, day-3, day-1 — a " +
                "shifted walk would put day-1 on 2026-10-14 as well and lose two days of the cycle",
            listOf("day-2", "day-3", "day-1"),
            afterThePause.create.map { it.programDayId.value }
        )
    }

    @Test
    fun thePlanDayOfADateDoesNotDependOnWhetherTheProgramWasPaused() {
        val pause = PausedInterval(LocalDate.parse("2026-09-16"), LocalDate.parse("2026-09-25"))

        val neverPaused = SlotPlanner.plan(
            request(revision(duration = ProgramDuration.Indefinite), asOf = anchor)
        ).asScheduled()
        val paused = SlotPlanner.plan(
            request(revision(duration = ProgramDuration.Indefinite), asOf = anchor, pauses = listOf(pause))
        ).asScheduled()

        val daysNeverPaused = neverPaused.create.associate { slot -> slot.plannedFor to slot.programDayId }
        val daysPaused = paused.create.associate { slot -> slot.plannedFor to slot.programDayId }

        assertTrue(
            "the paused program plans fewer dates — a paused date is not a date a pass plans on",
            daysPaused.size < daysNeverPaused.size
        )
        assertEquals(
            "but every date it does plan presents the plan day the same date presents in a program that " +
                "was never paused: a pause removes dates from the plan, it does not renumber it",
            daysNeverPaused.filterKeys { date -> date in daysPaused.keys },
            daysPaused
        )
    }

    @Test
    fun aFixedRunEndsOnItsCalendarEndHoweverLongTheProgramWasPaused() {
        val plan = SlotPlanner.plan(
            request(
                revision(duration = ProgramDuration.FixedDays(30)),
                asOf = anchor,
                pauses = listOf(
                    PausedInterval(LocalDate.parse("2026-09-16"), LocalDate.parse("2026-09-25"))
                )
            )
        ).asScheduled()

        assertEquals(
            "a FixedDays run is a number of **calendar** days from its anchor — " +
                "ProgramDuration.FixedDays is documented as 'a program that runs for a known number of " +
                "calendar days' — so a pause does not move its end: §3's freeze is about active program " +
                "time and missed-opportunity logic, not about the plan's dates (§20)",
            LocalDate.parse("2026-10-13"),
            plan.onlyWindow.lastDate
        )
        assertEquals(
            "and the run still ends on the date the thirty-day calendar says, not on one extended by " +
                "the paused days",
            LocalDate.parse("2026-10-13"),
            anchor.plusDays(29)
        )
    }

    // ------------------------------------------------------------------ settled semantics (locked)

    /**
     * **Settled semantics #1 — a superseded date is never re-planned.**
     *
     * One date holds one opportunity, ever: the row that records *what happened* to an opportunity is
     * the durable fact, so a date the revision stopped presenting keeps its superseded slot and the
     * Scheduler adds nothing for it, however many passes run afterwards. The alternative (re-planning a
     * superseded date) is the rejected reading recorded in `docs/PROGRAM_SCHEDULER.md` §5.
     */
    @Test
    fun aSupersededDateIsNeverReplanned() {
        val plan = SlotPlanner.plan(
            request(
                revision(duration = ProgramDuration.Indefinite),
                asOf = anchor,
                slots = listOf(slot("superseded", "2026-09-16", status = SlotStatus.SUPERSEDED))
            )
        ).asScheduled()

        assertTrue(
            "the pass adds nothing for the date whose opportunity was superseded",
            plan.createdDates.none { it.toString() == "2026-09-16" }
        )
        assertEquals(
            "and it plans every other date of the revision, so the rule is not hiding a gap",
            listOf(
                "2026-09-14", "2026-09-18", "2026-09-21", "2026-09-23", "2026-09-25", "2026-09-28",
                "2026-09-30", "2026-10-02", "2026-10-05", "2026-10-07", "2026-10-09", "2026-10-12"
            ),
            plan.createdDates.map { it.toString() }
        )
        assertTrue(
            "and the superseded opportunity is not deleted, re-statused or superseded again",
            plan.supersede.isEmpty() && plan.miss.isEmpty()
        )
    }

    /**
     * **Settled semantics #2 — a pause preserves the opportunities inside it and renumbers nothing.**
     *
     * Both halves in one place, on a pause that has not started yet: the opportunities planned inside
     * the interval before it was added are left exactly as they are (a pause is a fact about now, not a
     * cancellation of what is coming), and everything a pass does plan for a date outside the interval
     * continues the same cycle those preserved opportunities are on — so a pause can never split the
     * plan into two phases.
     */
    @Test
    fun aPausePreservesFutureOpportunitiesAndRenumbersNothing() {
        val pause = PausedInterval(LocalDate.parse("2026-09-16"), LocalDate.parse("2026-09-25"))
        val insideThePause = listOf(
            slot("16", "2026-09-16", dayPosition = 2),
            slot("18", "2026-09-18", dayPosition = 3),
            slot("21", "2026-09-21", dayPosition = 1),
            slot("23", "2026-09-23", dayPosition = 2),
            slot("25", "2026-09-25", dayPosition = 3)
        )

        val plan = SlotPlanner.plan(
            request(revision(), asOf = anchor, slots = insideThePause, pauses = listOf(pause))
        ).asScheduled()

        assertTrue(
            "a pause preserves the opportunities inside it: nothing is superseded and nothing is missed",
            plan.supersede.isEmpty() && plan.miss.isEmpty()
        )
        assertEquals(
            "and it renumbers nothing: the dates outside the interval that have no opportunity yet are " +
                "planned in cycle order",
            listOf(
                "2026-09-14", "2026-09-28", "2026-09-30", "2026-10-02", "2026-10-05", "2026-10-07",
                "2026-10-09", "2026-10-12"
            ),
            plan.createdDates.map { it.toString() }
        )
        assertEquals(
            "with the cycle continuing across the pause: 2026-09-25 (preserved) presents day-3, so " +
                "2026-09-28 presents day-1 and the days either side of the interval belong to one plan",
            listOf("day-1", "day-1", "day-2", "day-3", "day-1", "day-2", "day-3", "day-1"),
            plan.create.map { it.programDayId.value }
        )
        assertEquals(
            "and the walk the days are counted along is the calendar's: 2026-09-14 is the plan's first " +
                "date and the paused dates keep their own places in it",
            "day-1",
            plan.create.first().programDayId.value
        )
    }

    // ------------------------------------------------------------------ helpers

    /** The plan of a request that produced one, failing loudly when the pass had no window. */
    private fun SlotPlan.asScheduled(): SlotPlan {
        require(!hasNoFutureDate) { "expected a pass with a window, got $this" }
        requireNotNull(onlyWindow)
        return this
    }

    /** The window of a pass that has one: the null case is [SlotPlan.hasNoFutureDate]'s. */
    private val SlotPlan.onlyWindow: ScheduleWindow
        get() = requireNotNull(window) { "expected a pass with a window, got $this" }

    private fun request(
        revision: ProgramRevision,
        anchor: LocalDate = this.anchor,
        asOf: LocalDate = anchor,
        slots: List<WorkoutSlot> = emptyList(),
        pauses: List<PausedInterval> = emptyList()
    ): ScheduleRequest = ScheduleRequest(
        revision = revision,
        anchor = anchor,
        asOf = asOf,
        slots = slots,
        pauses = pauses,
        slotIds = TestSlotIds()
    )

    private fun slot(
        id: String,
        date: String,
        dayPosition: Int = 1,
        status: SlotStatus = SlotStatus.PLANNED,
        attempts: List<SessionId> = emptyList()
    ): WorkoutSlot = WorkoutSlot(
        slotId = SlotId(id),
        programId = programId,
        revisionId = RevisionId("revision-pure"),
        programDayId = ProgramDayId("day-$dayPosition"),
        plannedFor = LocalDate.parse(date),
        status = status,
        attempts = attempts
    )

    /** The fixture's three-day plan: training, rest, mobility — the days a cycle runs through. */
    private fun plan(tag: String = "d1"): List<ProgramDay> = listOf(
        ProgramDay(
            programDayId = ProgramDayId("day-1"),
            position = 1,
            type = ProgramDayType.TRAINING,
            name = "Day one",
            exercises = listOf(element("plan-ex-$tag-1-1", "pushup", listOf(12, 10, 8)))
        ),
        ProgramDay(
            programDayId = ProgramDayId("day-2"),
            position = 2,
            type = ProgramDayType.REST
        ),
        ProgramDay(
            programDayId = ProgramDayId("day-3"),
            position = 3,
            type = ProgramDayType.MOBILITY,
            name = "Day three",
            exercises = listOf(element("plan-ex-$tag-3-1", "plank", listOf(30, 30)))
        )
    )

    /** The same three days with different content: the plan an edit produced, unchanged in shape. */
    private fun rearrangedPlan(): List<ProgramDay> = plan("d2").map { day ->
        day.copy(
            name = day.name?.let { "Revised $it" },
            exercises = day.exercises.map { element -> element.copy(prescription = RepPrescription(listOf(5))) }
        )
    }

    private fun element(id: String, exerciseId: String, targets: List<Int>) = ProgramExercise(
        programExerciseId = ProgramExerciseId(id),
        exerciseId = exerciseId,
        prescription = RepPrescription(targets),
        origin = ProgramExerciseOrigin.USER_AUTHORED
    )

    private fun revision(
        schedule: ProgramSchedule = ProgramSchedule.FixedWeekdays(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        ),
        duration: ProgramDuration = ProgramDuration.FixedDays(30),
        days: List<ProgramDay> = plan(),
        revisionNumber: Int = 1
    ): ProgramRevision = ProgramRevision(
        revisionId = RevisionId("revision-pure"),
        programId = programId,
        revisionNumber = revisionNumber,
        mode = ProgramMode.MANUAL,
        duration = duration,
        schedule = schedule,
        days = days,
        createdAt = Instant.parse("2026-09-01T08:00:00Z")
    )
}
