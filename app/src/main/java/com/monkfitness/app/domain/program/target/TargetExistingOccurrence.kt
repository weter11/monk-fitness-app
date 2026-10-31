package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PlannedOccurrence

/**
 * The **only** occurrence state target scheduling consumes: the planned payload, plus the one execution
 * classification the scheduling rules actually read.
 *
 * Membership in a reconciliation, and a temporal classification at an as-of date, are decided from two
 * facts: *which* occurrence this is (its key and its payload) and *what happened to it* (its execution
 * state). Actual performance is not read by any rule in the target contour, so it is not a field here.
 *
 * It is not a narrower `ExistingOccurrence` and it is not an alias of one. It holds **no**
 * `ActualResult`, no performed work, no execution evidence, no `WorkoutSession`, no `WorkoutSlot`, no
 * repository, no persistence state and no adaptive state. Each of those belongs to the layer that owns
 * it: performed work lives in the session graph; `ActualResult` aggregation has no owner yet, because no
 * documented contract relates a session's `exerciseId` to a target occurrence's `workId`, so mapping one
 * onto the other remains explicitly deferred; and the opportunity outcome (`SlotStatus`) travels inside
 * [TargetOccurrenceExecutionDecision], not here.
 *
 * The execution value comes only from the policy, through [fromDecision]. There is no
 * constructor-adjacent derivation, no convenience overload that decides for itself and no ambient
 * default: an occurrence's execution is a *decided* fact, and a second route to that value would be a
 * second precedence.
 *
 * It is a pure value: no clock, no randomness, no identity generator, no repository, no ambient state,
 * no collaborator of any kind. Two equal pairs describe an equal scheduling input.
 *
 * @property occurrence the planned payload — identity, planned date and components — passed through
 *   unchanged, never re-derived.
 * @property execution the occurrence's execution classification, carried under its own name.
 */
data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution
) {

    /**
     * Builds a target-existing occurrence from the execution **decision**.
     *
     * The decision is taken as a value, not recomputed: this constructor converts two facts into a
     * scheduling input, and the precedence that produced the execution stays in
     * [TargetOccurrenceExecutionPolicy] where it is the only copy of it.
     *
     * @param decision the verdict, whose [TargetOccurrenceExecutionDecision.execution] is the only fact
     *   taken from it. `slotStatus` and `attemptCount` are opportunity and count facts, and neither is an
     *   input to scheduling.
     */
    constructor(occurrence: PlannedOccurrence, decision: TargetOccurrenceExecutionDecision) :
        this(occurrence, decision.execution)

    /**
     * The occurrence's own key — read, never parsed.
     *
     * Occurrence membership identity is `occurrenceKey` and this type adds no second identity: no date,
     * no weekday, no program-day position and no parsed key fragment stands in for it.
     */
    val occurrenceKey: String get() = occurrence.occurrenceKey

    /**
     * The planned date, forwarded from the payload.
     *
     * The temporal policy reads it; this type does not interpret it, and nothing here compares it with a
     * clock or with an as-of date.
     */
    val plannedFor get() = occurrence.plannedFor

    /** Whether this occurrence is still awaiting the user, as the policy classified it. */
    val isPlanned: Boolean get() = execution == OccurrenceExecution.PLANNED
}
