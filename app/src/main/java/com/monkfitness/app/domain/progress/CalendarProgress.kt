package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import java.time.LocalDate

/**
 * One planned opportunity as the calendar reads it: a projection of a [WorkoutSlot] that adds nothing
 * to it. It carries no amount of work — no repetition count, no duration, no completion percentage —
 * which is what makes a [SlotStatus.MISSED] entry a fact about an opportunity that passed rather than
 * a workout that scored zero.
 *
 * `programId` is kept per entry so an aggregate calendar can still say which Program each date belongs
 * to, and `attempts` is empty only for an opportunity nobody attempted.
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
 * The scope's calendar of opportunities, chronologically.
 *
 * The four counts are derived from the entries rather than passed in beside them, so this value cannot
 * contradict the history it describes — there is no second place for the number to come from.
 *
 * The statuses are the Scheduler's facts, kept apart as that layer decided them: [missed] is not a
 * zero-performance workout, and a missed opportunity whose attempt recorded partial work keeps that
 * work as exposure in the session's own facts; [upcoming] is the `PLANNED` status, never a date
 * comparison made here — deciding from "its date has passed" would be a second missed-ness rule —
 * though the planned date is on every entry so a presentation can tell the two apart; [superseded] is
 * reported rather than hidden, because a calendar whose parts do not add up is a calendar nobody can
 * trust.
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

    val completed: Int
        get() = count(SlotStatus.COMPLETED)

    /** Passed opportunities. Never read as work performed. */
    val missed: Int
        get() = count(SlotStatus.MISSED)

    val upcoming: Int
        get() = count(SlotStatus.PLANNED)

    val superseded: Int
        get() = count(SlotStatus.SUPERSEDED)

    val opportunityCount: Int
        get() = entries.size

    val programIds: List<ProgramId>
        get() = entries.map { it.programId }.distinct()

    private fun count(status: SlotStatus): Int = entries.count { it.status == status }

    companion object {

        /** The calendar's own order: planned date first, identity as the deterministic tiebreak. */
        val ORDER: Comparator<CalendarSlotEntry> =
            compareBy({ it.plannedFor }, { it.slotId.value })
    }
}
