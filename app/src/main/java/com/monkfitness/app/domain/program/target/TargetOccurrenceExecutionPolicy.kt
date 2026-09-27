package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus

/**
 * The one place in the Program System that says what a target occurrence's *execution* is.
 *
 * Phase 15 stopped at the facts — a [TargetOccurrenceExecutionRecord] holding an occurrence, a slot
 * and every stored attempt — and recorded why it stopped: a slot may be attempted repeatedly, so
 * `CANCELLED → IN_PROGRESS` and `CANCELLED → COMPLETED` are both legal stored histories, and no
 * stored column says which attempt decides the occurrence's state. That is a *policy* question,
 * and a policy has to be owned by exactly one named thing or it gets re-invented per caller. This
 * file is that thing, and it is the first phase allowed to answer.
 *
 * ### The precedence, stated once
 *
 * ```text
 * no attempts                        -> PLANNED
 * all attempts CANCELLED             -> CANCELLED
 * at least one COMPLETED             -> COMPLETED
 * at least one IN_PROGRESS           -> STARTED
 * ```
 *
 * It is a **monotonic "highest attained execution state"** reading: the occurrence's execution is
 * the furthest state any of its attempts ever reached. A cancellation is not a state an occurrence
 * *falls back* to — once a workout of that slot was completed, a later cancelled attempt does not
 * un-complete the occurrence; it is a second fact about a second attempt. That is why
 * `COMPLETED → later CANCELLED` is still `COMPLETED`, and why the order of the branches below is
 * load-bearing rather than stylistic.
 *
 * `WorkoutSlot.status` is **not** an input to any of it, and that is the point: `SlotStatus` has no
 * member for a started attempt and none for a cancelled one, `MISSED` and `SUPERSEDED` are not
 * `OccurrenceExecution` values at all, and `SlotStatus.COMPLETED` is a different token from
 * `SessionStatus.COMPLETED`. The slot is carried into the [TargetOccurrenceExecutionDecision] as
 * its own field so the two facts stay separately readable — a caller that wants to know how the
 * *opportunity* went asks for one, and a caller that wants to know how the *execution* went asks
 * for the other.
 *
 * ### No representative attempt
 *
 * Nothing here picks an attempt. There is no "the latest attempt", no "the first attempt" and no
 * "the highest-status attempt" — because none of those agree with each other, and choosing one
 * would answer a different question than "what was the highest state this occurrence reached".
 * Every stored attempt is inspected, none is ranked, filtered, reordered, deduplicated or dropped,
 * and the answer is a function of the *set* of stored statuses. The stored order is therefore
 * irrelevant to the verdict, which is a test rather than an accident.
 *
 * ### Pure, and pure over exactly one input
 *
 * The policy takes a [TargetOccurrenceExecutionRecord] and nothing storage-specific: no repository,
 * no DAO, no clock, no identity generator, no plan day, no current revision, no parsed occurrence
 * key, no ambient state. Equivalent records produce equality-identical decisions, because the
 * decision is a `data class` over two enums and a count.
 *
 * It also does not repair anything. A corrupted attempt is the Phase 15 record's business to refuse,
 * and the record refuses it in its own constructor before this policy is reached; this policy sees
 * a record that already holds together, and it neither filters a status out to make the answer
 * tidier nor reinterprets one it does not recognise.
 *
 * ### What this is not
 *
 * This is an **explicit derived policy**, not a stored fact, and it is not an `ExistingOccurrence`.
 * Nothing here constructs an `ActualResult`, invents a `workId ← exerciseId` mapping, or sums a
 * session's sets into one result: no documented contract relates a session's `exerciseId` to a
 * target occurrence's `workId`, so mapping performed work onto planned work remains an explicitly
 * deferred phase with its own owner.
 *
 * @property execution the occurrence's execution state — the whole point of this type, declared
 *   rather than computed behind an incidental getter so no caller can read a verdict by accident.
 * @property slotStatus the slot's own stored opportunity status, carried unchanged and under its
 *   own name. It is a separate fact with a separate type, not an input to [execution].
 * @property attemptCount how many stored attempts the decision was read from, so a caller can see
 *   that a `PLANNED` came from zero attempts rather than from a filtered-away one.
 */
data class TargetOccurrenceExecutionDecision(
    val execution: OccurrenceExecution,
    val slotStatus: SlotStatus,
    val attemptCount: Int
)

/**
 * §30 step 16: the single owner of the occurrence-execution precedence.
 *
 * ```text
 * TargetOccurrenceExecutionReader   reads the facts
 * TargetOccurrenceExecutionRecord   the facts themselves
 * TargetOccurrenceExecutionPolicy   decides, here and nowhere else
 * TargetOccurrenceExecutionDecision the one resulting value
 * ```
 *
 * Reader reads, policy decides, and no persistence layer, UI or runtime decides either. There is no
 * second precedence: a caller that wants an occurrence's execution calls [decide].
 */
object TargetOccurrenceExecutionPolicy {

    /**
     * The execution state of one target occurrence, read from its stored attempts and nothing else.
     *
     * Every stored attempt is inspected and none is chosen. The branches are evaluated in the order
     * the precedence table above states them, and that order is the rule: `COMPLETED` is tested
     * before `IN_PROGRESS` because a completed attempt beside a still-open one is a completed
     * occurrence — the open one is a *later* attempt that has not undone the earlier one.
     *
     * @param record the Phase 15 read-back, whose own invariants have already refused any corrupted
     *   or mismatched stored data.
     * @return the decision: one explicit [OccurrenceExecution], the slot's own status under its own
     *   name, and the number of stored attempts the answer was read from.
     */
    fun decide(record: TargetOccurrenceExecutionRecord): TargetOccurrenceExecutionDecision {
        val statuses = record.attemptStatuses
        val execution = when {
            // No attempt at all: the occurrence is still ahead of the user. This is the normal
            // state of a planned workout, not an absence.
            statuses.isEmpty() -> OccurrenceExecution.PLANNED
            // Every attempt was cancelled: nothing was ever started to a finish and nothing was
            // left open, so the occurrence ended as cancelled.
            statuses.all { it == SessionStatus.CANCELLED } -> OccurrenceExecution.CANCELLED
            // A completed attempt is the highest state an occurrence can reach. It outranks an
            // in-progress one, which is a *different* attempt of the same slot.
            statuses.any { it == SessionStatus.COMPLETED } -> OccurrenceExecution.COMPLETED
            // Started and not completed: a workout of this occurrence is open right now.
            statuses.any { it == SessionStatus.IN_PROGRESS } -> OccurrenceExecution.STARTED
            // Unreachable while `SessionStatus` holds exactly these three values: a non-empty list
            // that is not all-cancelled, and holds neither COMPLETED nor IN_PROGRESS, cannot be
            // built. The branch exists because the `when` is a value, and it deliberately restates
            // the cancelled reading rather than inventing a fifth execution state.
            else -> OccurrenceExecution.CANCELLED
        }
        return TargetOccurrenceExecutionDecision(
            execution = execution,
            slotStatus = record.slotStatus,
            attemptCount = statuses.size
        )
    }
}
