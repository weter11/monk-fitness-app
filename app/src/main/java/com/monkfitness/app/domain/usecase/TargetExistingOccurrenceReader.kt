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
 * ### The payload comes from storage, and the caller's occurrence is only a key
 *
 * The caller's [PlannedOccurrence] is used for **one** thing: its `occurrenceKey`, which is what
 * locates the stored occurrence. Everything the returned value carries about *what* the occurrence
 * is comes from Phase 15's read-back — `record.occurrence` — because the existing occurrence is the
 * one **already stored**, not the one the caller happens to be planning.
 *
 * This is what keeps the reconciler's payload-equality rule exact. It compares the stored existing
 * payload against the replacement payload, and it can only do that if the *stored* payload is what
 * reaches it. Substituting the caller's occurrence here would compare the caller against itself:
 *
 * ```text
 * persisted   key=strength  components=OLD
 * caller      key=strength  components=NEW
 *
 * forwarding the caller   reconciler compares NEW == NEW   conflict lost
 * carrying record.occurrence  reconciler compares OLD == NEW   conflict observed
 * ```
 *
 * A payload conflict is a real target fact, and hiding it is the one outcome this boundary must not
 * produce. Forwarding the caller's occurrence is therefore not a "harmless default" — it is a
 * silently swallowed defect.
 *
 * As in the input adapter, the KDoc of this file names no target stage type on purpose. The
 * production-source scans that count callers read file text without stripping comments, so a KDoc
 * that spelled those types out would register this file as a caller and invert a guard that is
 * meant to stay closed. The stage document names them; the source does not.
 *
 * Note what this class deliberately does **not** do about that conflict: it does not compare the two
 * payloads, refuse on a mismatch, or reconcile anything. Payload equality is the reconciler's rule
 * and it stays there, unchanged; this class only refuses to destroy the input that rule needs.
 */
class TargetExistingOccurrenceReader(
    private val executionReader: TargetOccurrenceExecutionReader
) {

    /**
     * The target scheduling input for one **persisted** occurrence.
     *
     * @param occurrence supplies the lookup identity and nothing else. Its `occurrenceKey` locates
     *   the stored occurrence; its payload is **not** returned, and a caller presenting a different
     *   payload for the same key gets the *stored* one back — which is exactly what lets the
     *   reconciler observe the payload conflict instead of comparing the caller against itself.
     *
     * The identity used for the lookup is the target occurrence key and nothing else — no `slotId`,
     * revision, plan day, date or weekday is consulted, and the key is passed to the reader as
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
        return TargetExistingOccurrence(record.occurrence.occurrence, decision)
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
