package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import java.time.Instant
import java.time.LocalDate

/**
 * One planned opportunity to train, produced by the Scheduler from a revision's plan (§20).
 *
 * A slot is the *opportunity*, not the training: it names the program day it presents and the date it
 * was planned for, and it records which sessions were attempted for it. **It carries no amount of
 * work at all** — there is no repetitions field, no duration field and no performance score, and
 * that absence is deliberate. A [SlotStatus.MISSED] slot therefore cannot be read as a workout that
 * scored zero, because there is nothing here to be zero: the missed opportunity is the fact, and no
 * zero-work record is invented for it (§12). The same holds for a cancelled attempt — the session's
 * partial work is preserved in the session, not converted into a slot result.
 *
 * Two invariants follow from that shape:
 *
 *  * a slot is only [SlotStatus.COMPLETED] with a [completedAt] stamp, and only a completed slot has
 *    one — the other three statuses are never described as having happened;
 *  * [attempts] holds the identity of every session started for this slot, in start order. Several
 *    attempts are allowed; that **at most one of them is `IN_PROGRESS`** is a rule the start-session
 *    transaction enforces (§19, §27), because it spans more than one session and cannot be asserted
 *    from a single immutable value.
 *
 * @property slotId identity of this opportunity.
 * @property programId the Program whose plan this slot presents.
 * @property revisionId the revision it was scheduled from. A later revision supersedes the slot; it
 *   never rewrites it.
 * @property programDayId the plan day it presents.
 * @property plannedFor the calendar date it was planned for.
 * @property status what happened to the opportunity.
 * @property attempts the sessions started for it, in start order.
 * @property completedAt when a session completed it, or `null`.
 */
data class WorkoutSlot(
    val slotId: SlotId,
    val programId: ProgramId,
    val revisionId: RevisionId,
    val programDayId: ProgramDayId,
    val plannedFor: LocalDate,
    val status: SlotStatus,
    val attempts: List<SessionId> = emptyList(),
    val completedAt: Instant? = null
) {

    init {
        require(attempts.toSet().size == attempts.size) {
            "a session belongs to a slot at most once"
        }
        require((status == SlotStatus.COMPLETED) == (completedAt != null)) {
            "only a COMPLETED slot happened, and a completed slot says when: status=$status " +
                "completedAt=$completedAt"
        }
        require(status != SlotStatus.COMPLETED || attempts.isNotEmpty()) {
            "a slot is completed by a session, not by itself"
        }
    }

    /** Whether the opportunity is still ahead of the user. */
    val isOpen: Boolean
        get() = status == SlotStatus.PLANNED

    /** Whether any session was started for this slot, whatever became of it. */
    val hasBeenAttempted: Boolean
        get() = attempts.isNotEmpty()
}
