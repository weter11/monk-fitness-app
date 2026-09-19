package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.workout.WorkoutSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The Scheduler, driven through the production persistence path — §30 step 7, over the real DAOs.
 *
 * Every claim below is measured on the migrated SQLite engine the device runs, through
 * [ProgramSchedulerRig]'s production repositories, clock and id generator, so "a pass created no
 * Session", "a pass wrote nothing at all" and "the whole pass rolled back" are decided by the engine
 * rather than by this suite's bookkeeping. The dates are written out in full: the fixture's anchor
 * `2026-09-14` is a Monday, and each expectation is a date a reader can check by hand.
 *
 * The suite is organised the way §20's rules are:
 *
 * ```text
 * generation     fixed weekdays, flexible frequency, the horizon, the first window
 * idempotence    the same pass twice, and one date holding one opportunity
 * missed         what a passed opportunity becomes, and what does not move because of it
 * pauses         frozen missed-detection, frozen active program time, and no destroyed opportunity
 * revisions      exactly which future opportunities a revision change supersedes, and which survive
 * boundaries     completed, archived, no anchor, unknown Program, missing revision
 * structure      no Session, no revision write, no lifecycle write, one unit per pass
 * ```
 */
class ProgramSchedulerTest {

    private val rig = ProgramSchedulerRig("s")

    @After
    fun tearDown() = rig.close()

    // ================================================================ generation (§20)

    @Test
    fun theFirstPassPlansTheRevisionOnItsOwnDaysAndNamesItsOwnPlanDays() = runBlocking {
        val graph = rig.create()
        val slotsBefore = rig.tableCounts()

        val outcome = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertEquals(13, outcome.createdCount)
        assertEquals(
            "a Monday/Wednesday/Friday program is planned on Mondays, Wednesdays and Fridays, from " +
                "the day it started to the last day of its thirty-day run",
            listOf(
                "2026-09-14", "2026-09-16", "2026-09-18", "2026-09-21", "2026-09-23", "2026-09-25",
                "2026-09-28", "2026-09-30", "2026-10-02", "2026-10-05", "2026-10-07", "2026-10-09",
                "2026-10-12"
            ),
            outcome.created.map { it.plannedFor.toString() }
        )
        assertEquals(
            "each opportunity presents the revision's own plan day, cycling through the plan in order " +
                "(§9: a repeat is the same plan day, not a new one)",
            listOf(
                "day-s-d1-1", "day-s-d1-2", "day-s-d1-3", "day-s-d1-1", "day-s-d1-2", "day-s-d1-3",
                "day-s-d1-1", "day-s-d1-2", "day-s-d1-3", "day-s-d1-1", "day-s-d1-2", "day-s-d1-3",
                "day-s-d1-1"
            ),
            outcome.created.map { it.programDayId.value }
        )

        val stored = rig.slots(graph.programId)
        assertEquals(
            "and what was stored is what the pass decided — the same rows, read back through a fresh read",
            outcome.created,
            stored
        )
        assertTrue(
            "every stored opportunity is open, has no attempt and no completion stamp",
            stored.all { it.status == SlotStatus.PLANNED && it.attempts.isEmpty() && it.completedAt == null }
        )
        assertTrue(
            "and it names the Program and the revision it was scheduled from",
            stored.all { it.programId == graph.programId && it.revisionId == graph.revision.revisionId }
        )
        assertOnlySlotsChanged(slotsBefore, rig.tableCounts())
    }

    @Test
    fun theOutcomeReportsTheWindowThePassCoveredAndTheDaysItRead() = runBlocking {
        val graph = rig.create()

        val outcome = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertEquals(graph.programId, outcome.programId)
        assertEquals(graph.revision.revisionId, outcome.revisionId)
        assertEquals("the day the plan's first day falls on", ON_ANCHOR, outcome.anchor)
        assertEquals("the day the pass was made on", ON_ANCHOR, outcome.asOf)
        assertEquals(LocalDate.parse("2026-09-14"), outcome.window?.firstDate)
        assertEquals(
            "a fixed revision's window is its own run, thirty days long",
            LocalDate.parse("2026-10-13"),
            outcome.window?.lastDate
        )
        assertEquals(
            "and it reports the revision's own training dates inside that window",
            outcome.created.map { it.plannedFor },
            outcome.scheduledDates
        )
        assertFalse(outcome.isNoOp)
        assertFalse(outcome.hasNoFutureDate)
    }

    @Test
    fun aFlexibleFrequencyIsPlannedFromTheStatedCountAndNotFromTheSlotsItAlreadyHas() = runBlocking {
        (1..7).forEach { count ->
            val other = ProgramSchedulerRig("flex$count")
            try {
                val graph = other.create(
                    key = "flex$count",
                    schedule = ProgramSchedule.FlexiblePerWeek(count),
                    duration = ProgramDuration.Indefinite
                )

                val first = other.plan(graph.programId, ON_ANCHOR).valueOrFail()

                assertEquals(
                    "$count sessions a week is $count weekdays a week, and each full week holds exactly " +
                        "that many opportunities — never an average of what happens to be there",
                    count,
                    other.slots(graph.programId)
                        .filter { it.plannedFor < LocalDate.parse("2026-10-12") }
                        .groupBy { it.plannedFor.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
                        .map { (_, week) -> week.size }
                        .toSet()
                        .let { sizes -> sizes.singleOrNull() ?: error("uneven weeks: $sizes") }
                )
                assertTrue(
                    "and every date it planned is one of the frequency's own weekdays: " +
                        "${flexibleSpread(count)}",
                    first.created.all { it.plannedFor.dayOfWeek in flexibleSpread(count) }
                )

                // The second pass is the one that would expose a frequency derived from the slots that
                // already exist: at this point there are thirteen of them, and a schedule that counted
                // them would spread "thirteen a week" instead of the stated count.
                val second = other.plan(graph.programId, LocalDate.parse("2026-09-21")).valueOrFail()

                assertEquals(
                    "the next pass plans the same rhythm again, so the count comes from the revision " +
                        "and never from the rows the last pass wrote",
                    0,
                    second.created.count { it.plannedFor.dayOfWeek !in flexibleSpread(count) }
                )
            } finally {
                other.close()
            }
        }
    }

    @Test
    fun anIndefiniteHorizonIsExactlyThirtyDaysAndExtendsWhenThePassIsMadeLater() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.Indefinite)

        val first = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertEquals(
            "an indefinite Program keeps exactly thirty days of future planning (§20)",
            LocalDate.parse("2026-10-13"),
            first.window?.lastDate
        )
        assertEquals(13, first.createdCount)

        val second = rig.plan(graph.programId, LocalDate.parse("2026-09-15")).valueOrFail()

        assertEquals(
            "a pass made a day later extends the window by a day and adds the opportunity that fits",
            LocalDate.parse("2026-10-14"),
            second.window?.lastDate
        )
        assertEquals(listOf("2026-10-14"), second.created.map { it.plannedFor.toString() })
        assertEquals(
            "and the opportunity that has passed is missed, not slid away",
            listOf("2026-09-14"),
            second.missed.map { slotId -> storedSlot(graph.programId, slotId).plannedFor.toString() }
        )
        assertEquals(
            "the opportunities it already had are untouched: same identity, same date, same plan day",
            first.created.drop(1),
            rig.slots(graph.programId).filter { it.slotId in first.created.drop(1).map { slot -> slot.slotId } }
        )
        assertEquals(14, rig.slots(graph.programId).size)
    }

    @Test
    fun twoProgramsAreScheduledIndependently() = runBlocking {
        val monday = rig.create(key = "monday")
        val tuesday = rig.create(key = "tuesday", schedule = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.TUESDAY)))

        rig.plan(monday.programId, ON_ANCHOR)
        rig.plan(tuesday.programId, ON_ANCHOR)

        assertEquals(
            "one Program's rhythm is its own: the pass that planned the Tuesday Program wrote no " +
                "Monday, Wednesday or Friday",
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            rig.slots(monday.programId).map { it.plannedFor.dayOfWeek }.toSet()
        )
        assertEquals(
            setOf(DayOfWeek.TUESDAY),
            rig.slots(tuesday.programId).map { it.plannedFor.dayOfWeek }.toSet()
        )
        assertTrue(
            "and each opportunity names the Program it belongs to",
            rig.slots(tuesday.programId).all { it.programId == tuesday.programId }
        )
    }

    // ================================================================ idempotence (§20)

    @Test
    fun theSamePassAppliedTwiceAddsNothingAndRewritesNothing() = runBlocking {
        val graph = rig.create()
        val first = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()
        val census = rig.tableCounts()
        val storedBefore = rig.slots(graph.programId)

        val second = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertTrue("the second pass decides nothing at all", second.isNoOp)
        assertEquals(0, second.createdCount)
        assertEquals(
            "and writes nothing: no new opportunity, no status change, no row anywhere",
            census,
            rig.tableCounts()
        )
        assertEquals(
            "the opportunities the first pass stored are the same rows, down to their identities",
            first.created,
            storedBefore
        )
        assertEquals(storedBefore, rig.slots(graph.programId))
    }

    @Test
    fun aDateThatAlreadyHoldsAnOpportunityIsNeverPlannedAgainEvenAfterItWasSuperseded() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)
        rig.saveRevision(graph, schedule = TUE_THU, revisionNumber = 2)
        rig.plan(graph.programId, ON_ANCHOR)
        val afterTheChange = rig.slots(graph.programId)
        assertEquals(22, afterTheChange.size)
        assertEquals(13, afterTheChange.count { it.status == SlotStatus.SUPERSEDED })

        rig.saveRevision(graph, schedule = SchedulerFixture.weekly(), revisionNumber = 3)
        val backAgain = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertEquals(
            "a date holds one opportunity, ever: the Mondays, Wednesdays and Fridays the revision " +
                "trains on again are the dates whose opportunities were superseded, and a supersession " +
                "is history rather than a gap to be refilled silently",
            0,
            backAgain.createdCount
        )
        assertEquals(
            "what the revision used to present and does not any more is superseded in its turn",
            9,
            backAgain.supersededCount
        )
        assertEquals(
            "so a Program whose schedule was changed and changed back has nothing open: every date it " +
                "trains on again was superseded on the way out and is not re-planned, because a " +
                "supersession is a durable record rather than a gap",
            0,
            rig.slots(graph.programId).count { it.status == SlotStatus.PLANNED }
        )
        assertEquals(22, rig.slots(graph.programId).count { it.status == SlotStatus.SUPERSEDED })
        assertEquals("and no row was ever deleted", 22, rig.slots(graph.programId).size)
    }

    // ================================================================ missed opportunities (§12, §20)

    @Test
    fun anOpportunityThatPassedIsMissedAndNoWorkIsInventedForIt() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)
        val census = rig.tableCounts()

        val outcome = rig.plan(graph.programId, LocalDate.parse("2026-09-25")).valueOrFail()

        assertEquals(
            "the five opportunities that passed untrained are missed; the one on the day of the pass " +
                "is still ahead, because a date is not missed at midnight",
            listOf("2026-09-14", "2026-09-16", "2026-09-18", "2026-09-21", "2026-09-23"),
            outcome.missed.map { slotId -> storedSlot(graph.programId, slotId).plannedFor.toString() }
        )
        val missed = rig.slots(graph.programId).filter { it.status == SlotStatus.MISSED }
        assertTrue(
            "a missed opportunity is not a workout that scored zero: it has no completion stamp, no " +
                "session and nothing to measure (§12)",
            missed.all { it.completedAt == null && it.attempts.isEmpty() }
        )
        assertEquals(
            "and it keeps its own date — nothing about it was rewritten, only its status",
            listOf("2026-09-14", "2026-09-16", "2026-09-18", "2026-09-21", "2026-09-23"),
            missed.map { it.plannedFor.toString() }
        )
        assertEquals(
            "the status is recorded on that slot and nowhere else: the other eight opportunities are " +
                "still open, on their own dates",
            8,
            rig.slots(graph.programId).count { it.status == SlotStatus.PLANNED }
        )
        assertEquals(
            "and no Session, snapshot, occurrence or set was created to represent the miss",
            census.filterKeys { it != "program_workout_slot" },
            rig.tableCounts().filterKeys { it != "program_workout_slot" }
        )
        assertEquals(0, rig.tableCounts()["workout_session"])
    }

    @Test
    fun aPassedOpportunityThatWasAttemptedIsStillMissedAndItsSessionIsUntouched() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)
        val slot = rig.slots(graph.programId).first { it.plannedFor.toString() == "2026-09-16" }
        rig.sessionRepository.startSession(ProgramGraphFixture.session("s", slot))
        val sessionBefore = sessionRow(slot.slotId)

        val outcome = rig.plan(graph.programId, LocalDate.parse("2026-09-25")).valueOrFail()

        val after = storedSlot(graph.programId, slot.slotId)
        assertEquals(
            "an attempt is not a completion: the opportunity passed without a finished workout, so it " +
                "is missed",
            SlotStatus.MISSED,
            after.status
        )
        assertTrue("and it is the pass that missed it", slot.slotId in outcome.missed)
        assertEquals(
            "the slot still names the session, because the attempt is a fact about the opportunity and " +
                "a scheduling pass never rewrites it",
            listOf(SessionId("session-s")),
            after.attempts
        )
        assertEquals(
            "the session itself is not touched by a scheduling pass (§19: a started session never " +
                "changes, not even the status of the slot it was started for)",
            sessionBefore,
            sessionRow(slot.slotId)
        )
    }

    @Test
    fun aMissedOpportunityDoesNotSlideTheRestOfTheSchedule() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.Indefinite)
        rig.plan(graph.programId, ON_ANCHOR)
        val before = rig.slots(graph.programId)
        val missedDates = listOf("2026-09-14", "2026-09-16", "2026-09-18", "2026-09-21", "2026-09-23")

        rig.plan(graph.programId, LocalDate.parse("2026-09-25"))

        val after = rig.slots(graph.programId)
        assertEquals(
            "no date moved: every opportunity that is not newly created is on the date it was planned " +
                "for, with the identity and the plan day it had — a miss rewrites nothing and slides " +
                "nothing (§20)",
            before.map { slot -> Triple(slot.slotId, slot.plannedFor, slot.programDayId) },
            after.filter { it.slotId in before.map { slot -> slot.slotId } }
                .map { slot -> Triple(slot.slotId, slot.plannedFor, slot.programDayId) }
        )
        assertEquals(
            "the only rows whose status changed are the five that passed",
            missedDates,
            after.filter { it.status == SlotStatus.MISSED }.map { it.plannedFor.toString() }
        )
        assertEquals(
            "and the opportunities the pass added continue the plan's cycle where it had reached: the " +
                "cycle counts scheduled dates from the anchor, never completions and never misses",
            listOf("day-s-d1-2", "day-s-d1-3", "day-s-d1-1", "day-s-d1-2", "day-s-d1-3"),
            after.filter { it.plannedFor > LocalDate.parse("2026-10-12") }.map { it.programDayId.value }
        )
    }

    // ================================================================ pauses (§3, §20)

    @Test
    fun aPausedDateIsNotAPlanningDateAndThePlanResumesWhereItFroze() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.Indefinite)
        rig.pause(graph.programId, from = LocalDate.parse("2026-09-16"), until = LocalDate.parse("2026-09-25"))

        val outcome = rig.plan(graph.programId, LocalDate.parse("2026-09-28")).valueOrFail()

        assertEquals(
            "nothing is planned inside the pause, and the horizon runs from the day of the pass",
            listOf(
                "2026-09-28", "2026-09-30", "2026-10-02", "2026-10-05", "2026-10-07", "2026-10-09",
                "2026-10-12", "2026-10-14", "2026-10-16", "2026-10-19", "2026-10-21", "2026-10-23",
                "2026-10-26"
            ),
            outcome.created.map { it.plannedFor.toString() }
        )
        assertEquals(
            "and the first opportunity after the pause presents the plan day that came after the " +
                "previous one: pausing freezes active program time, so the days that would have fallen " +
                "inside the pause fall after it instead (§3)",
            "day-s-d1-2",
            outcome.created.first().programDayId.value
        )
        assertEquals(0, outcome.missedCount)
        assertEquals(0, outcome.supersededCount)
    }

    @Test
    fun anOpportunityThatPassedInsideAPauseIsSupersededRatherThanMissed() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)
        rig.pause(graph.programId, from = LocalDate.parse("2026-09-16"), until = LocalDate.parse("2026-09-25"))

        val outcome = rig.plan(graph.programId, LocalDate.parse("2026-09-28")).valueOrFail()

        assertEquals(
            "the opportunity that passed while the program was not paused was expected, so it is missed",
            listOf("2026-09-14"),
            outcome.missed.map { slotId -> storedSlot(graph.programId, slotId).plannedFor.toString() }
        )
        assertEquals(
            "the five that passed inside the pause were not expected and are superseded instead — " +
                "superseded is not missed (§20)",
            listOf("2026-09-16", "2026-09-18", "2026-09-21", "2026-09-23", "2026-09-25"),
            outcome.superseded.map { superseded -> storedSlot(graph.programId, superseded.slotId).plannedFor.toString() }
        )
        assertTrue(
            "and every one of them records *why* it was superseded, so the change is auditable",
            outcome.superseded.all {
                it.reason == SupersessionReason.THE_OPPORTUNITY_PASSED_WHILE_PAUSED
            }
        )
        assertEquals(
            "nothing was destroyed: the seven opportunities after the pause keep their dates and their " +
                "plan days and are still open",
            listOf("2026-09-28", "2026-09-30", "2026-10-02", "2026-10-05", "2026-10-07", "2026-10-09", "2026-10-12"),
            rig.slots(graph.programId).filter { it.status == SlotStatus.PLANNED }.map { it.plannedFor.toString() }
        )
        assertEquals("no row was deleted and none was created", 13, rig.slots(graph.programId).size)
        assertEquals(0, outcome.createdCount)
    }

    @Test
    fun aPauseDoesNotDestroyAnOpportunityThatIsStillAhead() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.Indefinite)
        rig.plan(graph.programId, ON_ANCHOR)
        rig.pause(graph.programId, from = LocalDate.parse("2026-09-16"), until = LocalDate.parse("2026-09-25"))

        val outcome = rig.plan(graph.programId, LocalDate.parse("2026-09-16")).valueOrFail()

        assertEquals("the horizon extends past the pause by a day", 1, outcome.createdCount)
        assertEquals(listOf("2026-10-14"), outcome.created.map { it.plannedFor.toString() })
        assertEquals(
            "nothing is superseded by a pause that is still ahead of the user",
            0,
            outcome.supersededCount
        )
        assertEquals(
            "the only opportunity that passed is the one from before the pause, and it is missed",
            listOf("2026-09-14"),
            outcome.missed.map { slotId -> storedSlot(graph.programId, slotId).plannedFor.toString() }
        )
        assertTrue(
            "a pause is a fact about now, not a cancellation of what is coming: the five opportunities " +
                "inside the paused interval are still open",
            rig.slots(graph.programId).filter {
                it.plannedFor in listOf("2026-09-16", "2026-09-18", "2026-09-21", "2026-09-23", "2026-09-25")
                    .map { date -> LocalDate.parse(date) }
            }.all { it.status == SlotStatus.PLANNED }
        )
    }

    @Test
    fun anOpenPauseFreezesBothPlanningAndMissedDetection() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)
        rig.pause(graph.programId, from = LocalDate.parse("2026-09-16"))

        val outcome = rig.plan(graph.programId, LocalDate.parse("2026-09-21")).valueOrFail()

        assertEquals(
            "the program is paused, so the Scheduler plans nothing: it has no way to know which dates " +
                "belong to the program after the pause",
            0,
            outcome.createdCount
        )
        assertEquals(
            "the opportunity that passed before the pause was expected, so it is missed",
            listOf("2026-09-14"),
            outcome.missed.map { slotId -> storedSlot(graph.programId, slotId).plannedFor.toString() }
        )
        assertEquals(
            "the two that passed inside the pause were not — missed-opportunity logic is frozen there (§3)",
            listOf("2026-09-16", "2026-09-18"),
            outcome.superseded.map { superseded -> storedSlot(graph.programId, superseded.slotId).plannedFor.toString() }
        )
        assertTrue(
            "and every opportunity still ahead of the user stays open, exactly as it was: the pause " +
                "closes nothing and moves nothing",
            rig.slots(graph.programId)
                .filter { it.plannedFor >= LocalDate.parse("2026-09-21") }
                .all { it.status == SlotStatus.PLANNED }
        )
        assertEquals(
            "there are ten of them",
            10,
            rig.slots(graph.programId)
                .count { it.plannedFor >= LocalDate.parse("2026-09-21") && it.status == SlotStatus.PLANNED }
        )
        assertEquals(
            "nothing was deleted by the pause",
            13,
            rig.slots(graph.programId).size
        )
    }

    // ================================================================ revision changes (§20, §27)

    @Test
    fun aRevisionThatChangesOnlyThePlansContentSupersedesNothingAndCreatesNothing() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)
        val before = rig.slots(graph.programId)

        rig.saveRevision(graph, days = SchedulerFixture.days("s-d2"), revisionNumber = 2)
        val outcome = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertTrue(
            "the opportunity is the date, not the workout: every Wednesday is still a Wednesday, so " +
                "the revision presents all thirteen of them and there is nothing to supersede or add",
            outcome.isNoOp
        )
        assertEquals(
            "every opportunity survives the edit exactly as it was, on its own date and plan day",
            before,
            rig.slots(graph.programId)
        )
    }

    @Test
    fun aRevisionThatMovesTheScheduleSupersedesExactlyTheDatesItNoLongerPresents() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)
        val mondaySlots = rig.slots(graph.programId)

        rig.saveRevision(graph, schedule = TUE_THU, revisionNumber = 2)
        val outcome = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertEquals(
            "the revision trains on Tuesday and Thursday now, so none of the thirteen M/W/F dates is " +
                "one it presents and all thirteen are superseded",
            mondaySlots.map { it.slotId },
            outcome.superseded.map { it.slotId }
        )
        assertTrue(
            "each with the rule that superseded it, so a caller can explain it",
            outcome.superseded.all {
                it.reason == SupersessionReason.THE_REVISION_NO_LONGER_PRESENTS_THE_DATE
            }
        )
        assertEquals(
            "the dates the revision does train on are planned from scratch",
            listOf(
                "2026-09-15", "2026-09-17", "2026-09-22", "2026-09-24", "2026-09-29", "2026-10-01",
                "2026-10-06", "2026-10-08", "2026-10-13"
            ),
            outcome.created.map { it.plannedFor.toString() }
        )
        val stored = rig.slots(graph.programId)
        assertEquals("nothing is deleted: the superseded rows are still there", 22, stored.size)
        assertEquals(
            "and a superseded row keeps its date and its plan day exactly as they were: only its status " +
                "changed, which is what makes the supersession auditable history rather than a rewrite",
            mondaySlots.map { slot -> Triple(slot.slotId, slot.plannedFor, slot.programDayId) },
            stored.filter { it.slotId in mondaySlots.map { slot -> slot.slotId } }
                .map { slot -> Triple(slot.slotId, slot.plannedFor, slot.programDayId) }
        )
        assertTrue(
            "no opportunity was missed simply because a weekday left the schedule",
            outcome.missedCount == 0 && stored.none { it.status == SlotStatus.MISSED }
        )
    }

    @Test
    fun aRevisionThatOnlyAddsAWeekdayKeepsEveryOpportunityItStillPresents() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)

        rig.saveRevision(
            graph,
            schedule = ProgramSchedule.FixedWeekdays(
                setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
            ),
            revisionNumber = 2
        )
        val outcome = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertTrue(
            "a revision that widens the schedule takes nothing away: nothing is superseded and nothing " +
                "is missed",
            outcome.supersededCount == 0 && outcome.missedCount == 0
        )
        assertEquals(
            "and the new weekday gains its own opportunities, each still presenting the plan day the " +
                "cycle had reached",
            listOf("2026-09-19", "2026-09-26", "2026-10-03", "2026-10-10"),
            outcome.created.map { it.plannedFor.toString() }
        )
        assertEquals(
            "and each presents the plan day the cycle had reached, of the revision the pass scheduled",
            listOf(
                "day-program-s-d2-1", "day-program-s-d2-2", "day-program-s-d2-3", "day-program-s-d2-1"
            ),
            outcome.created.map { it.programDayId.value }
        )
        assertEquals(17, rig.slots(graph.programId).size)
    }

    @Test
    fun shrinkingARevisionSupersedesOnlyTheDatesBeyondItsNewEnd() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)

        rig.saveRevision(graph, duration = ProgramDuration.FixedDays(10), revisionNumber = 2)
        val outcome = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertEquals(
            "the revision now runs to 2026-09-23, so the eight dates beyond its end are the ones it " +
                "can no longer present",
            listOf(
                "2026-09-25", "2026-09-28", "2026-09-30", "2026-10-02", "2026-10-05", "2026-10-07",
                "2026-10-09", "2026-10-12"
            ),
            outcome.superseded.map { superseded -> storedSlot(graph.programId, superseded.slotId).plannedFor.toString() }
        )
        assertEquals(
            "and the five inside the new run are untouched: this is one rule applied to each date, not " +
                "a regeneration of the schedule",
            listOf("2026-09-14", "2026-09-16", "2026-09-18", "2026-09-21", "2026-09-23"),
            rig.slots(graph.programId).filter { it.status == SlotStatus.PLANNED }.map { it.plannedFor.toString() }
        )
        assertEquals(0, outcome.createdCount)
        assertEquals(
            "the revision that was superseded still describes exactly what it described (§6)",
            graph.revision,
            rig.revision(graph.revision.revisionId)
        )
        assertEquals(2, rig.revisionCount(graph.programId))
    }

    @Test
    fun aRevisionThatReachesFurtherThanTheHorizonSupersedesNothingPastIt() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.FixedDays(40))
        rig.plan(graph.programId, ON_ANCHOR)

        rig.saveRevision(graph, duration = ProgramDuration.Indefinite, revisionNumber = 2)
        val outcome = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertTrue(
            "an indefinite revision has no end, so the dates past today's thirty days are still dates " +
                "it presents: the horizon is how far ahead the plan is written down, not how far it reaches",
            outcome.supersededCount == 0 && outcome.createdCount == 0
        )
    }

    @Test
    fun aRevisionThatShortensThePlanKeepsEveryDateAndCyclesTheNewPlanFromThenOn() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.Indefinite)
        rig.plan(graph.programId, ON_ANCHOR)

        rig.saveRevision(graph, days = SchedulerFixture.shortDays("s-d2"), revisionNumber = 2)
        val sameDay = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertTrue(
            "the schedule is untouched: the plan is shorter, and an opportunity is a date, so every " +
                "date is still presented and nothing is superseded",
            sameDay.isNoOp
        )
        assertEquals(0, sameDay.supersededCount)

        val later = rig.plan(graph.programId, LocalDate.parse("2026-10-14")).valueOrFail()

        assertEquals("the new horizon is planned", 13, later.createdCount)
        assertEquals(
            "and the cycle it follows is the **new** plan's: the pass reads the revision it is given, " +
                "never the one the existing slots came from",
            listOf("day-program-s-d2-2", "day-program-s-d2-1", "day-program-s-d2-2", "day-program-s-d2-1"),
            later.created.take(4).map { it.programDayId.value }
        )
        assertEquals(
            "the opportunities behind the pass are missed on the way",
            13,
            later.missedCount
        )
    }

    @Test
    fun aRevisionThatReachesBackBeforeThePlanSupersedesTheDatesItNoLongerCovers() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.Indefinite)
        rig.plan(graph.programId, ON_ANCHOR)

        rig.saveRevision(graph, duration = ProgramDuration.FixedDays(6), revisionNumber = 2)
        val outcome = rig.plan(graph.programId, ON_ANCHOR).valueOrFail()

        assertEquals(
            "a run of six days from the anchor ends on 2026-09-19, so everything after it goes",
            listOf(
                "2026-09-21", "2026-09-23", "2026-09-25", "2026-09-28", "2026-09-30", "2026-10-02",
                "2026-10-05", "2026-10-07", "2026-10-09", "2026-10-12"
            ),
            outcome.superseded.map { superseded -> storedSlot(graph.programId, superseded.slotId).plannedFor.toString() }
        )
        assertEquals(
            "and the three dates inside it are still open",
            listOf("2026-09-14", "2026-09-16", "2026-09-18"),
            rig.slots(graph.programId).filter { it.status == SlotStatus.PLANNED }.map { it.plannedFor.toString() }
        )
    }

    @Test
    fun anElapsedRevisionCreatesNothingAndStillReconcilesWhatItFinds() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.FixedDays(10))
        rig.plan(graph.programId, ON_ANCHOR)

        val outcome = rig.plan(graph.programId, LocalDate.parse("2026-09-30")).valueOrFail()

        assertNull("a run that ended before the pass has no window at all", outcome.window)
        assertTrue(outcome.hasNoFutureDate)
        assertEquals("so it creates nothing", 0, outcome.createdCount)
        assertEquals(
            "and it still reconciles the opportunities it finds: they passed, so they are missed",
            listOf("2026-09-14", "2026-09-16", "2026-09-18", "2026-09-21", "2026-09-23"),
            outcome.missed.map { slotId -> storedSlot(graph.programId, slotId).plannedFor.toString() }
        )
        assertEquals(5, rig.slots(graph.programId).size)
    }

    // ================================================================ boundaries (§3, §20, §29)

    @Test
    fun aCompletedProgramIsRefusedAndNothingIsWrittenAtAll() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)
        rig.store(
            rig.stored(graph.programId).copy(lifecycleStatus = LifecycleStatus.COMPLETED)
        )
        val census = rig.tableCounts()

        val result = rig.scheduler.schedule(graph.programId)

        assertEquals(
            ProgramSchedulingRefusal.ProgramCompleted(graph.programId),
            result.refusalOrFail()
        )
        assertEquals(
            "a pass on a Program that is over writes nothing at all — not one new opportunity, and not " +
                "even a status change on the ones it has",
            census,
            rig.tableCounts()
        )
        assertEquals(
            "the opportunities it had are left exactly as they were",
            13,
            rig.slots(graph.programId).size
        )
        assertTrue(rig.slots(graph.programId).all { it.status == SlotStatus.PLANNED })
    }

    @Test
    fun anArchivedProgramIsRefusedAndItsOpportunitiesAreKept() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.Indefinite)
        rig.plan(graph.programId, ON_ANCHOR)
        rig.store(rig.stored(graph.programId).copy(archivedAt = SchedulerFixture.at(ON_ANCHOR)))
        val census = rig.tableCounts()

        val result = rig.scheduler.schedule(graph.programId)

        assertEquals(ProgramSchedulingRefusal.ProgramArchived(graph.programId), result.refusalOrFail())
        assertEquals(
            "archiving stops future planning (§29), and this stage stops it at the door: no slot is " +
                "added and none is rewritten",
            census,
            rig.tableCounts()
        )
        val archived = rig.stored(graph.programId)
        assertTrue("and the archive stamp is not touched by a scheduling pass", archived.isArchived)
        assertEquals(RUNNING, archived.lifecycleStatus)

        rig.store(archived.copy(archivedAt = null))
        val afterUnarchive = rig.plan(graph.programId, LocalDate.parse("2026-09-15")).valueOrFail()

        assertEquals(
            "unarchiving makes the Program plannable again, on the same terms",
            listOf("2026-10-14"),
            afterUnarchive.created.map { it.plannedFor.toString() }
        )
    }

    @Test
    fun aProgramThatHasNeitherStartedNorBeenPlannedHasNoDateToPlanFrom() = runBlocking {
        val graph = rig.create(
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            plannedStartDate = null
        )
        val census = rig.tableCounts()

        val result = rig.scheduler.schedule(graph.programId)

        assertEquals(ProgramSchedulingRefusal.NoSchedulingAnchor(graph.programId), result.refusalOrFail())
        assertEquals(
            "the Scheduler does not invent a start date: picking today would be it deciding when the " +
                "user's program begins",
            census,
            rig.tableCounts()
        )
    }

    @Test
    fun planningFromAPlannedStartDatePlansOpportunitiesAndStartsNothing() = runBlocking {
        val plannedStart = LocalDate.parse("2026-10-01")
        val graph = rig.create(
            duration = ProgramDuration.FixedDays(14),
            anchor = plannedStart,
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            plannedStartDate = plannedStart
        )
        val before = rig.stored(graph.programId)

        val outcome = rig.plan(graph.programId, LocalDate.parse("2026-09-20")).valueOrFail()

        assertEquals(
            "the plan begins at its own first day, and the first date it trains on is the first such " +
                "date on or after the anchor",
            listOf("2026-10-02", "2026-10-05", "2026-10-07", "2026-10-09", "2026-10-12", "2026-10-14"),
            outcome.created.map { it.plannedFor.toString() }
        )
        assertEquals(
            "the plan now has two days, so the cycle the pass reads is the new plan's: a later pass " +
                "reaches for the revision it is given, never for the one the slots came from",
            listOf("day-s-d1-1", "day-s-d1-2", "day-s-d1-3", "day-s-d1-1", "day-s-d1-2", "day-s-d1-3"),
            outcome.created.map { it.programDayId.value }
        )
        val after = rig.stored(graph.programId)
        assertEquals(
            "and a planned start date does not start a Program: the lifecycle did not move, the " +
                "factual start is still absent and the planned date is unchanged (§3)",
            before,
            after
        )
        assertEquals(LifecycleStatus.NOT_STARTED, after.lifecycleStatus)
        assertNull(after.actualStartDate)
        assertEquals(plannedStart, after.plannedStartDate)
    }

    @Test
    fun anUnknownProgramIsRefusedAndNothingIsWritten() = runBlocking {
        val census = rig.tableCounts()
        val unknown = ProgramId("program-nowhere")

        val result = rig.scheduler.schedule(unknown)

        assertEquals(ProgramSchedulingRefusal.ProgramNotFound(unknown), result.refusalOrFail())
        assertEquals(census, rig.tableCounts())
    }

    @Test
    fun aProgramWhoseRevisionIsNotStoredIsRefused() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)
        rig.database.exec(
            "DELETE FROM `program_revision` WHERE `revisionId` = ?",
            graph.revision.revisionId.value
        )

        val result = rig.scheduler.schedule(graph.programId)

        assertEquals(
            "a Program always points at a revision, so a missing one is invalid data (§28) — not an " +
                "empty plan and not a silent success",
            ProgramSchedulingRefusal.RevisionMissing(graph.programId, graph.revision.revisionId),
            result.refusalOrFail()
        )
    }

    // ================================================================ structure (§6, §12, §19, §26, §27)

    @Test
    fun aPassCreatesNoSessionSnapshotOccurrenceOrSet() = runBlocking {
        val graph = rig.create()
        rig.plan(graph.programId, ON_ANCHOR)
        rig.plan(graph.programId, LocalDate.parse("2026-09-28"))

        listOf(
            "workout_session", "session_snapshot", "session_snapshot_exercise", "session_exercise",
            "program_set_log", "program_adaptive_decision_record", "adaptive_adjustment",
            "program_family_progression_state", "program_pause", "app_state"
        ).forEach { table ->
            assertEquals(
                "the Scheduler schedules slots and nothing else: `$table` is not its business (§33)",
                0,
                rig.database.count(table)
            )
        }
    }

    @Test
    fun aPassWritesNothingButSlotsAndNeverTouchesThePlan() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.Indefinite)
        val before = rig.tableCounts()
        val programBefore = rig.stored(graph.programId)

        rig.plan(graph.programId, ON_ANCHOR)
        rig.plan(graph.programId, LocalDate.parse("2026-09-28"))

        val after = rig.tableCounts()
        assertOnlySlotsChanged(before, after)
        assertEquals(
            "the Program row is not written by a pass: no lifecycle, no pointer, no stamp (§6, §20)",
            programBefore,
            rig.stored(graph.programId)
        )
        assertEquals(
            "and the revision it scheduled is exactly the revision it read: a pass never rewrites an " +
                "immutable revision (§6)",
            graph.revision,
            rig.currentRevision(graph.programId)
        )
        assertEquals(1, rig.revisionCount(graph.programId))
        assertEquals(3, rig.database.count("program_day"))
        assertEquals(2, rig.database.count("program_exercise"))
    }

    @Test
    fun aPreviewDecidesExactlyWhatSchedulingWouldDoAndWritesNothing() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.Indefinite)
        rig.plan(graph.programId, ON_ANCHOR)
        val census = rig.tableCounts()
        val storedBefore = rig.slots(graph.programId)

        val preview = rig.preview(graph.programId, LocalDate.parse("2026-09-25")).valueOrFail()

        assertEquals(
            "the preview reports the opportunities scheduling would add",
            listOf("2026-10-14", "2026-10-16", "2026-10-19", "2026-10-21", "2026-10-23"),
            preview.created.map { it.plannedFor.toString() }
        )
        assertEquals(
            "it reports the misses and supersessions as well",
            5,
            preview.missedCount
        )
        assertEquals(
            "and it writes nothing at all: not a slot, not a status, not a row anywhere (§26)",
            census,
            rig.tableCounts()
        )
        assertEquals(storedBefore, rig.slots(graph.programId))

        rig.moveTo(LocalDate.parse("2026-09-25"))
        val scheduled = rig.scheduler.schedule(graph.programId).valueOrFail()

        assertEquals(
            "what it decided is what scheduling then does, down to the dates and the plan days",
            preview.created.map { it.plannedFor to it.programDayId },
            scheduled.created.map { it.plannedFor to it.programDayId }
        )
    }

    @Test
    fun onePassIsOneUnitSoAFailureLeavesTheScheduleExactlyAsItWas() = runBlocking {
        val graph = rig.create(duration = ProgramDuration.Indefinite)
        rig.plan(graph.programId, ON_ANCHOR)
        rig.saveRevision(graph, schedule = TUE_THU, revisionNumber = 2)
        val census = rig.tableCounts()
        val storedBefore = rig.slots(graph.programId)

        rig.faults.failSlotInsert = true
        rig.moveTo(LocalDate.parse("2026-09-25"))
        val failed = rig.scheduler.schedule(graph.programId)

        val failure = failed.failureOrFail()
        assertTrue(
            "the storage failure is propagated, never absorbed into an empty result (§28, §33)",
            failure is IllegalStateException && failure.message!!.contains("planted fault: slot insert")
        )
        assertEquals(
            "and the whole pass rolled back with it: the opportunities it had already decided to " +
                "supersede and to miss are untouched, because nothing landed until everything could",
            census,
            rig.tableCounts()
        )
        assertEquals(storedBefore, rig.slots(graph.programId))

        rig.faults.failSlotInsert = false
        val recovered = rig.scheduler.schedule(graph.programId).valueOrFail()

        assertEquals(
            "with the fault gone the same pass lands whole — the eight opportunities the new revision " +
                "trains on, the eight it no longer presents and the five that passed",
            8,
            recovered.createdCount
        )
        assertEquals(8, recovered.supersededCount)
        assertEquals(5, recovered.missedCount)
        assertEquals(21, rig.slots(graph.programId).size)
        assertEquals(8, rig.slots(graph.programId).count { it.status == SlotStatus.PLANNED })
    }

    // ================================================================ helpers

    /** The stored `workout_session` row of [slotId], read through SQL so nothing is a mapper's view. */
    private fun sessionRow(slotId: SlotId): Map<String, String?> =
        rig.database.rows("SELECT * FROM `workout_session` WHERE `slotId` = ?", slotId.value).single()

    /** One stored opportunity, read fresh — never a value a pass returned. */
    private suspend fun storedSlot(programId: ProgramId, slotId: SlotId): WorkoutSlot =
        rig.slots(programId).single { it.slotId == slotId }

    /** Asserts that a pass changed the slot table and nothing else in the database. */
    private fun assertOnlySlotsChanged(before: Map<String, Int>, after: Map<String, Int>) {
        assertEquals(
            "the slots are the only rows a scheduling pass may write",
            before.filterKeys { it != "program_workout_slot" },
            after.filterKeys { it != "program_workout_slot" }
        )
        assertTrue(
            "and it really did write them",
            after.getValue("program_workout_slot") > before.getValue("program_workout_slot")
        )
    }

    private fun <T> ProgramSchedulingResult<T>.valueOrFail(): T = when (this) {
        is ProgramSchedulingResult.Success -> value
        is ProgramSchedulingResult.Refused -> error("refused: ${reason.message}")
        is ProgramSchedulingResult.Failure -> throw cause
    }

    private fun <T> ProgramSchedulingResult<T>.refusalOrFail(): ProgramSchedulingRefusal = when (this) {
        is ProgramSchedulingResult.Success -> error("expected a refusal, got $value")
        is ProgramSchedulingResult.Refused -> reason
        is ProgramSchedulingResult.Failure -> throw cause
    }

    private fun <T> ProgramSchedulingResult<T>.failureOrFail(): Throwable = when (this) {
        is ProgramSchedulingResult.Success -> error("expected a failure, got $value")
        is ProgramSchedulingResult.Refused -> error("expected a failure, got ${reason.message}")
        is ProgramSchedulingResult.Failure -> cause
    }

    private companion object {

        val ON_ANCHOR: LocalDate = SchedulerFixture.ANCHOR

        val RUNNING: LifecycleStatus = LifecycleStatus.RUNNING

        val TUE_THU: ProgramSchedule = ProgramSchedule.FixedWeekdays(
            setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        )
    }
}
