package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PlannedOccurrence

/**
 * The **only** occurrence state target scheduling consumes: the planned payload, plus the one
 * execution classification the scheduling rules actually read.
 *
 * ```text
 * TargetExistingOccurrence  occurrence: PlannedOccurrence, execution: OccurrenceExecution
 * ```
 *
 * ### Why this type exists
 *
 * Target planning and reconciliation used to take the general-purpose
 * `com.monkfitness.app.domain.program.ExistingOccurrence`, and inherited its third field — a list of
 * `ActualResult`s — as a scheduling input. That dependency was never about scheduling. Membership in
 * a reconciliation, and a temporal classification at an as-of date, are decided from two facts:
 * *which* occurrence this is (its key and its payload) and *what happened to it* (its execution
 * state). Actual performance is not read by any rule in the target contour; it was a passenger on a
 * type that predates the target architecture, and the target contour paid for it by depending on the
 * execution payload shape of a generation it is not part of.
 *
 * This value removes that passenger and with it the dependency. The orchestration chain is now
 * stated in the target contour's own vocabulary:
 *
 * ```text
 * TargetExistingOccurrence[]
 *         ↓
 * TargetPlanner
 *         ↓
 * TargetSchedulePolicy
 *         ↓
 * TargetScheduleApplicationService
 * ```
 *
 * ### What it deliberately does not carry
 *
 * It is not a narrower `ExistingOccurrence` and it is not an alias of one. It holds **no**
 * `ActualResult`, no performed work, no execution evidence, no `WorkoutSession`, no `WorkoutSlot`,
 * no repository, no persistence state and no adaptive state. Each of those belongs to the layer that
 * owns it:
 *
 *  * performed work lives in the session graph the Phase 15 read-back returns verbatim;
 *  * `ActualResult` aggregation is not a read and has no owner yet — no documented contract relates
 *    a session's `exerciseId` to a target occurrence's `workId`, so mapping one onto the other, or
 *    summing a session's sets into a single result, remains an explicitly deferred phase;
 *  * the opportunity outcome (`SlotStatus`) is a separate concept with a separate type and travels
 *    inside [TargetOccurrenceExecutionDecision], not here.
 *
 * ### Where the execution value comes from
 *
 * Only from the Phase 16 policy, through [fromDecision]. There is no constructor-adjacent derivation,
 * no convenience overload that takes a `TargetOccurrenceExecutionRecord` and decides for itself, and
 * no ambient default: an occurrence's execution is a *decided* fact, and the one thing that decides
 * it is `TargetOccurrenceExecutionPolicy.decide`. A second route to that value would be a second
 * precedence, which is exactly what Phase 16 exists to prevent.
 *
 * It is a pure value: no clock, no randomness, no identity generator, no repository, no ambient state, and
 * no collaborator of any kind. Two equal pairs describe an equal scheduling input.
 *
 * @property occurrence the planned payload — identity, planned date and components — as the planner
 *   composes and the reconciler compares it. Passed through unchanged, never re-derived.
 * @property execution the occurrence's execution classification, read from
 *   [TargetOccurrenceExecutionPolicy]'s single decision and carried under its own name.
 */
data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution
) {

    /**
     * Builds a target-existing occurrence from the Phase 16 execution **decision**.
     *
     * The decision is taken as a value, not recomputed: this constructor is a conversion of two
     * facts into a scheduling input, and the precedence that produced the execution stays in
     * [TargetOccurrenceExecutionPolicy] where it is the only copy of it.
     *
     * @param occurrence the planned payload, forwarded unchanged.
     * @param decision the Phase 16 verdict, whose [TargetOccurrenceExecutionDecision.execution] is
     *   the only fact taken from it. `slotStatus` and `attemptCount` are opportunity and count
     *   facts, and neither is an input to scheduling.
     */
    constructor(occurrence: PlannedOccurrence, decision: TargetOccurrenceExecutionDecision) :
        this(occurrence, decision.execution)

    /**
     * The occurrence's own key — read, never parsed.
     *
     * Occurrence membership identity is `occurrenceKey` and this type adds no second identity: no
     * date, no weekday, no program-day position and no parsed key fragment stands in for it.
     */
    val occurrenceKey: String get() = occurrence.occurrenceKey

    /**
     * The planned date, forwarded from the payload.
     *
     * The temporal policy reads it; this type does not interpret it, and nothing here compares it
     * with a clock or with an as-of date.
     */
    val plannedFor get() = occurrence.plannedFor

    /** Whether this occurrence is still awaiting the user, as the Phase 16 policy classified it. */
    val isPlanned: Boolean get() = execution == OccurrenceExecution.PLANNED
}
