package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus

/**
 * The one place in the Program System that says what a target occurrence's *execution* is.
 *
 * A slot may be attempted repeatedly, so `CANCELLED → IN_PROGRESS` and `CANCELLED → COMPLETED` are both
 * legal stored histories, and no stored column says which attempt decides the occurrence's state. That
 * is a *policy* question, and a policy has to be owned by exactly one named thing or it gets re-invented
 * per caller.
 *
 * ```text
 * no attempts                        -> PLANNED
 * all attempts CANCELLED             -> CANCELLED
 * at least one COMPLETED             -> COMPLETED
 * at least one IN_PROGRESS           -> STARTED
 * ```
 *
 * It is a **monotonic "highest attained execution state"** reading: the occurrence's execution is the
 * furthest state any of its attempts ever reached. A cancellation is not a state an occurrence *falls
 * back* to — once a workout of that slot was completed, a later cancelled attempt does not un-complete
 * the occurrence; it is a second fact about a second attempt. That is why `COMPLETED → later CANCELLED`
 * is still `COMPLETED`, and why the order of the branches below is load-bearing rather than stylistic.
 *
 * `WorkoutSlot.status` is **not** an input to any of it: `SlotStatus` has no member for a started
 * attempt and none for a cancelled one, `MISSED` and `SUPERSEDED` are not `OccurrenceExecution` values at
 * all, and `SlotStatus.COMPLETED` is a different token from `SessionStatus.COMPLETED`. The slot is
 * carried into the decision as its own field so the two facts stay separately readable.
 *
 * Nothing here picks a representative attempt: there is no "latest", no "first" and no "highest-status"
 * attempt, because none of those agree with each other and choosing one would answer a different
 * question. Every stored attempt is inspected, none is ranked, filtered, reordered, deduplicated or
 * dropped, and the answer is a function of the *set* of stored statuses — so stored order is irrelevant
 * to the verdict.
 *
 * It is pure over exactly one input: no repository, no DAO, no clock, no identity generator, no plan day,
 * no current revision, no parsed occurrence key, no ambient state. Equivalent records produce
 * equality-identical decisions, because the decision is a `data class` over two enums and a count.
 *
 * It also does not repair anything: a corrupted attempt is the record's business to refuse, and the
 * record refuses it in its own constructor before this policy is reached. This policy neither filters a
 * status out to make the answer tidier nor reinterprets one it does not recognise, and it never
 * constructs an `ActualResult` or invents a `workId ← exerciseId` mapping.
 *
 * @property execution the occurrence's execution state — declared rather than computed behind an
 *   incidental getter so no caller can read a verdict by accident.
 * @property slotStatus the slot's own stored opportunity status, carried unchanged and under its own
 *   name. It is a separate fact with a separate type, not an input to [execution].
 * @property attemptCount how many stored attempts the decision was read from, so a caller can see that a
 *   `PLANNED` came from zero attempts rather than from a filtered-away one.
 */
data class TargetOccurrenceExecutionDecision(
    val execution: OccurrenceExecution,
    val slotStatus: SlotStatus,
    val attemptCount: Int
)

/**
 * The single owner of the occurrence-execution precedence.
 *
 * The reader reads the facts, this object decides, and no persistence layer, UI or runtime decides
 * either: there is no second precedence, and a caller that wants an occurrence's execution calls
 * [decide].
 */
object TargetOccurrenceExecutionPolicy {

    /**
     * The execution state of one target occurrence, read from its stored attempts and nothing else.
     *
     * Every stored attempt is inspected and none is chosen. The branches are evaluated in the order the
     * precedence table above states them, and that order is the rule: `COMPLETED` is tested before
     * `IN_PROGRESS` because a completed attempt beside a still-open one is a completed occurrence — the
     * open one is a *later* attempt that has not undone the earlier one.
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
