package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import java.time.LocalDate

/**
 * One planned opportunity as the calendar reads it (§20, §21).
 *
 * It is a projection of a [WorkoutSlot] and adds nothing to it: the date it was planned for, its
 * identity, the Program and plan day it presents, what became of it, and the attempts it holds. In
 * particular it carries **no amount of work** — there is no repetition count, no duration and no
 * completion percentage — which is what makes a [SlotStatus.MISSED] entry a fact about an opportunity
 * that passed rather than a workout that scored zero (§12).
 *
 * @property plannedFor the calendar date the opportunity was planned for.
 * @property slotId identity of the opportunity.
 * @property programId the Program whose plan it presents — kept per entry so an aggregate calendar can
 *   still say which Program each date belongs to.
 * @property programDayId the plan day it presents.
 * @property status what happened to it: still open, taken, passed, or replaced by a later plan.
 * @property attempts the sessions started for it, in start order — empty for an opportunity nobody
 *   attempted, and non-empty for one that was attempted and cancelled.
 */
data class CalendarSlotEntry(
    val plannedFor: LocalDate,
    val slotId: SlotId,
    val programId: ProgramId,
    val programDayId: ProgramDayId,
    val status: SlotStatus,
    val attempts: List<SessionId>
)

/**
 * §21's **Calendar Progress**: what the scope's calendar of opportunities looks like, chronologically.
 *
 * The four counts are derived from the entries rather than passed in beside them, so this value cannot
 * contradict the history it describes — a caller cannot hold a calendar of three completed days and a
 * count that says four, because there is no second place for the number to come from.
 *
 * The statuses are the §20 Scheduler's facts, and they are kept apart exactly as that layer decided
 * them:
 *
 *  * [completed] — a session for the opportunity was completed;
 *  * [missed] — the opportunity passed. This is **not** a zero-performance workout: no amount of work is
 *    read, invented or implied here (§12). A missed opportunity whose attempt recorded partial work keeps
 *    that work as exposure, where it belongs — in the session's own facts, not in the calendar;
 *  * [upcoming] — the opportunity is still **open**. It is the `PLANNED` status §20 owns, never a date
 *    comparison made here: deciding from *"its date has passed"* would be a second missed-ness rule, and
 *    §33 forbids guessing from a date. The planned date is on every entry, so a presentation can tell an
 *    opportunity whose date has gone by from one still ahead without Progress re-classifying anything;
 *  * [superseded] — a later plan replaced it before it was trained (§20: superseded is not missed, since
 *    the user was not expected to train it). It is reported rather than hidden, because a calendar whose
 *    parts do not add up to the whole is a calendar nobody can trust.
 *
 * @property scope what this calendar is about (§21's default context, or the All Programs aggregate).
 * @property entries the opportunities, chronologically — planned date first, identity as the
 *   deterministic tiebreak, so two runs over the same facts produce the same sequence.
 */
data class CalendarProgress(
    val scope: ProgressScope,
    val entries: List<CalendarSlotEntry>
) {

    init {
        require(entries == entries.sortedWith(ORDER)) {
            "the calendar is chronological: planned date first, then identity. Got " +
                "${entries.map { "${it.plannedFor}/${it.slotId.value}" }}"
        }
        require(entries.map { it.slotId }.toSet().size == entries.size) {
            "an opportunity appears once in the calendar"
        }
    }

    /** The opportunities a session completed. */
    val completed: Int
        get() = count(SlotStatus.COMPLETED)

    /** The opportunities that passed without one. Never read as work performed (§12). */
    val missed: Int
        get() = count(SlotStatus.MISSED)

    /** The opportunities still ahead of the user — the open ones. */
    val upcoming: Int
        get() = count(SlotStatus.PLANNED)

    /** The opportunities a later plan replaced before they were trained. */
    val superseded: Int
        get() = count(SlotStatus.SUPERSEDED)

    /** How many opportunities this calendar holds, whatever became of them. */
    val opportunityCount: Int
        get() = entries.size

    /** The Programs the entries belong to, in the calendar's own order — one for a Program scope. */
    val programIds: List<ProgramId>
        get() = entries.map { it.programId }.distinct()

    private fun count(status: SlotStatus): Int = entries.count { it.status == status }

    companion object {

        /** The calendar's own order: planned date first, identity as the deterministic tiebreak. */
        val ORDER: Comparator<CalendarSlotEntry> =
            compareBy({ it.plannedFor }, { it.slotId.value })
    }
}
