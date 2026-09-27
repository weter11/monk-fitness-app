package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.target.TargetExistingOccurrence
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionPolicy

/**
 * §30 step 17: the one route from stored target occurrences to the target scheduling input.
 *
 * ```text
 * TargetOccurrenceExecutionReader     reads the facts          (§30 step 15, unchanged)
 * TargetOccurrenceExecutionRecord     the facts themselves     (§30 step 15, unchanged)
 * TargetOccurrenceExecutionPolicy     decides                  (§30 step 16, unchanged)
 * TargetExistingOccurrence            the scheduling input     (this phase)
 * ```
 *
 * Every one of the four steps was already owned; this class only sequences them. It reads the
 * stored facts through the Phase 15 reader, hands them to the Phase 16 policy, and takes **only**
 * `TargetOccurrenceExecutionDecision.execution` from the answer.
 *
 * ### What it deliberately does not do
 *
 *  * it does **not** decide anything itself — the one precedence in the Program System stays in
 *    [TargetOccurrenceExecutionPolicy], and this class holds no `when` over attempt statuses, no
 *    representative attempt and no second reading of `slot.status`;
 *  * it does **not** construct an `ActualResult`, map a session's `exerciseId` to a target
 *    occurrence's `workId`, or sum a session's sets. No documented contract relates the two, so
 *    performed work is left exactly where Phase 15 read it: inside the session graph;
 *  * it does **not** hold a repository. Storage is the Phase 15 reader's contract, and a second
 *    component opening the same rows would be a second read path rather than a boundary;
 *  * it holds no clock and no identity generator: an occurrence's execution is a stored fact read
 *    back, never a fact computed from when the read happened.
 *
 * The **payload** side is caller-stated rather than read. A persisted `WorkoutSlot` does not carry a
 * full occurrence payload, so no honest read-back of the components exists yet; the caller states
 * the [PlannedOccurrence] it planned, and this class answers the only question storage can answer —
 * what happened to that occurrence. That is also what keeps the reconciler's payload-equality rule
 * exact: it compares planned payloads, and this class never rewrites one.
 */
class TargetExistingOccurrenceReader(
    private val executionReader: TargetOccurrenceExecutionReader
) {

    /**
     * The target scheduling input for one persisted occurrence.
     *
     * The identity is the target occurrence key and nothing else — no `slotId`, revision, plan day,
     * date or weekday is consulted to find the record, and the key is passed to the reader as
     * written. The execution is [TargetOccurrenceExecutionPolicy]'s verdict, taken whole.
     *
     * Typed read refusals from the Phase 15 reader propagate unchanged: a stored graph that
     * disagrees with itself is reported, not defaulted to `PLANNED`.
     */
    suspend fun existingOccurrenceOf(
        programId: ProgramId,
        occurrence: PlannedOccurrence
    ): TargetExistingOccurrence {
        val record = executionReader.executionRecordOf(programId, occurrence.occurrenceKey)
        val decision = TargetOccurrenceExecutionPolicy.decide(record)
        return TargetExistingOccurrence(occurrence, decision)
    }

    /**
     * The target scheduling input for every persisted occurrence the caller planned.
     *
     * One read and one decision per occurrence, in the caller's order: nothing is filtered, sorted,
     * deduplicated or collapsed, so a duplicate key reaches the reconciler and is refused there
     * rather than being silently merged on the way in.
     */
    suspend fun existingOccurrencesOf(
        programId: ProgramId,
        occurrences: List<PlannedOccurrence>
    ): List<TargetExistingOccurrence> = occurrences.map { occurrence ->
        existingOccurrenceOf(programId, occurrence)
    }
}
