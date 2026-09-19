package com.monkfitness.app.domain.program

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * The date arithmetic of §20's two schedule forms, and the pause/date conversion the schedule needs.
 *
 * Everything here is a *reading* of the schedule vocabulary the domain already has
 * ([ProgramSchedule], [ProgramPause]) into the dates a scheduling pass works with. Nothing in this
 * file decides anything: which dates exist, which of them a pass covers and what happens to a date
 * that passes are [SlotPlanner]'s decisions, and the two are kept apart so the arithmetic can be read
 * (and tested) on its own.
 *
 * ### Fixed weekdays and flexible frequency are the same question
 *
 * The blueprint supports two ways of saying *when a Program is trained*, and it is tempting to treat
 * them as different kinds of thing — one a list, one a count. They are not: a schedule answers exactly
 * one question, *is this date a training date?*, and both forms answer it deterministically.
 *
 *  * [ProgramSchedule.FixedWeekdays] answers it by membership: the weekday the user named is a
 *    training day, and the days they did not name are not.
 *  * [ProgramSchedule.FlexiblePerWeek] answers it by an even spread over the calendar week — see
 *    [flexibleSpread] — because "three sessions a week, you choose" is still deterministic: the user
 *    delegated the choice to the app, not to chance. §9's "*no random `Random()` in key generation
 *    decisions*" and §33's prohibition on nondeterministic generation are the same rule read here.
 *
 * The frequency is authoritative as a **count the user stated**, never as a description of the slots
 * that happen to exist: [ProgramSchedule.FlexiblePerWeek.sessionsPerWeek] is read directly, and no
 * function in this file counts rows. A schedule derived from the current slots would make yesterday's
 * miss change next week's rhythm, which is exactly the whole-schedule sliding §20 forbids.
 *
 * ### The week a spread is distributed over
 *
 * The calendar week, Monday first (`java.time`'s `IsoFields` week, read as a weekday number `1..7`).
 * Anchoring it to the program's own start date instead would make the same schedule mean different
 * things for two Programs, and would let a pause move the rhythm.
 */

/**
 * The weekdays [this] schedule trains on — the one question every schedule form answers.
 *
 * `FixedWeekdays` returns the days the user named; `FlexiblePerWeek` returns [flexibleSpread] of its
 * count. Both are sets, so no caller has to know which form it was handed.
 */
fun ProgramSchedule.scheduledWeekdays(): Set<DayOfWeek> = when (this) {
    is ProgramSchedule.FixedWeekdays -> weekdays
    is ProgramSchedule.FlexiblePerWeek -> flexibleSpread(sessionsPerWeek)
}

/** Whether [date] falls on a day [this] schedule trains on. */
fun ProgramSchedule.schedules(date: LocalDate): Boolean = date.dayOfWeek in scheduledWeekdays()

/**
 * The deterministic even spread of [sessionsPerWeek] sessions over a Monday-first week.
 *
 * The rule is one line and has no state: the `i`-th session lands on the weekday at
 * `floor(i * 7 / sessionsPerWeek)`, counted from Monday. Every supported count, in full — this table
 * is the spec, and the suite pins it:
 *
 * ```text
 *  1  MON
 *  2  MON  THU
 *  3  MON  WED  FRI
 *  4  MON  TUE  THU  SAT
 *  5  MON  TUE  WED  FRI  SAT
 *  6  MON  TUE  WED  THU  FRI  SAT
 *  7  MON  TUE  WED  THU  FRI  SAT  SUN
 * ```
 *
 * Why this rule rather than a "nicer" one: it is a *function of the count alone*, so the same
 * `sessionsPerWeek` produces the same days in every process, every month and every Program — which is
 * what "deterministic tie-breaks; no random scheduling" requires — and it never depends on how many
 * slots exist, on the current date or on the plan's content. A count of 3 lands on Monday, Wednesday
 * and Friday, the rhythm a user asking for three sessions a week expects; the remaining counts are
 * spread as evenly as whole weekdays allow, and a test pins all seven so the table cannot drift
 * silently.
 *
 * @throws IllegalArgumentException when [sessionsPerWeek] is outside `1..7` — the range
 *   [ProgramSchedule.FlexiblePerWeek] itself enforces.
 */
fun flexibleSpread(sessionsPerWeek: Int): Set<DayOfWeek> {
    require(sessionsPerWeek in 1..7) {
        "a flexible schedule trains between 1 and 7 days a week, was $sessionsPerWeek"
    }
    return (0 until sessionsPerWeek)
        .map { position -> DayOfWeek.of(position * 7 / sessionsPerWeek + 1) }
        .toSet()
}

/**
 * A pause interval expressed in **dates**, which is the form the schedule works in.
 *
 * A [ProgramPause] is stored as instants (a fact about when the user paused, §3) while a slot is
 * planned for a `LocalDate`, so the two have to meet somewhere. They meet here, once, at the boundary:
 * [ProgramPause.pausedInterval] is the only conversion, and after it the scheduling core compares
 * dates only and needs no clock, no zone and no instant at all.
 *
 * A pause covers **whole days**: the date its interval starts on and the date it ends on are both
 * covered, even when the user paused at 21:00 or resumed at 09:00. That is a deliberate, stated
 * simplification — a half-covered day would have to answer "was 14:00 still an opportunity?", which
 * is a question about the user's intention rather than about the plan, and a date-based answer is
 * deterministic and explainable.
 *
 * An **open** interval ([lastDate] `null`) covers every date from its start onward, which is what
 * "the pause is still in effect" means for a plan: the Scheduler has no way to know which dates
 * belong to the program after it, so it plans none of them.
 *
 * @property firstDate the date the pause began, inclusive.
 * @property lastDate the date it ended, inclusive, or `null` while it is still in effect.
 */
data class PausedInterval(
    val firstDate: LocalDate,
    val lastDate: LocalDate? = null
) {

    /** Whether this pause is still in effect: an interval with no end covers everything after it. */
    val isOpen: Boolean
        get() = lastDate == null

    /** Whether [date] falls inside this pause. */
    fun covers(date: LocalDate): Boolean =
        !date.isBefore(firstDate) && (lastDate == null || !date.isAfter(lastDate))
}

/**
 * The dates one pause interval covers, read in [zone].
 *
 * The zone is the caller's for the same reason the clock is (§26): a date is a calendar fact about a
 * place, and the layer that owns the clock is the layer that owns the calendar the user is reading.
 * Nothing in the domain assumes one, so the conversion happens where it is supplied rather than
 * inside the scheduling decision.
 */
fun ProgramPause.pausedInterval(zone: ZoneId): PausedInterval =
    PausedInterval(
        firstDate = startedAt.atZone(zone).toLocalDate(),
        lastDate = endedAt?.atZone(zone)?.toLocalDate()
    )
