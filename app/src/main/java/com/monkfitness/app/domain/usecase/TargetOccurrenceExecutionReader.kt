package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.TargetScheduleOccurrenceRepository
import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionReadException
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionRecord

/**
 * Reads a target occurrence's **stored execution facts** (§30 step 15).
 *
 * The lookup path is the identity and nothing else:
 *
 * ```text
 * (programId, occurrenceKey)
 *         ↓  TargetScheduleOccurrenceRepository   the semantic occurrence
 *         ↓  ProgramScheduleRepository            the slot, by the same pair
 *         ↓  WorkoutSessionRepository             every attempt at that slot
 * ```
 *
 * Each step is one question asked of the repository that owns the answer. Nothing is reconstructed
 * anywhere along it, and no step is skipped because its answer would be convenient:
 *
 *  * the semantic occurrence is Phase 14's own read-back, unchanged — the reader adds no field to it
 *    and derives nothing from it;
 *  * the slot is located by the same `(programId, occurrenceKey)` pair, **not** by `slotId`, a
 *    revision, a plan day, a date or a weekday. `slotId` enters only afterwards, to follow the
 *    established slot → session link once the identity has already located the slot;
 *  * the attempts are every stored session for that slot, in the session repository's own start
 *    order, and the reader neither filters nor reorders them.
 *
 * ### What it deliberately does not decide
 *
 * It does not construct an `OccurrenceExecution` and does not build an
 * [com.monkfitness.app.domain.program.ExistingOccurrence]. A slot may hold several attempts, and
 * which of them decides the occurrence's execution state is a policy no stored fact supplies, so
 * producing one here would be inventing a precedence rule. It also does not turn performed work into
 * target `ActualResult`s: no documented contract relates a session's `exerciseId` to a target
 * occurrence's `workId`, and summing a session's sets into a single result is a rule rather than a
 * read.
 *
 * It holds no clock, no identity generator, no planner, resolver, composer, policy, presenter,
 * reconciler, materializer or persister, and it opens no transaction: it reads, and a read that
 * needs a consistent snapshot of a database it does not own is the storage layer's contract, not
 * something this class may invent.
 *
 * @param occurrenceRepository owns the semantic occurrence — Phase 14's contract, used as it stands.
 * @param scheduleRepository owns slot and opportunity facts.
 * @param sessionRepository owns the persisted session aggregate with its snapshot, `SessionExercise`
 *   and `SetLog` graph, including the validation of that graph.
 */
class TargetOccurrenceExecutionReader(
    private val occurrenceRepository: TargetScheduleOccurrenceRepository,
    private val scheduleRepository: ProgramScheduleRepository,
    private val sessionRepository: WorkoutSessionRepository
) {

    /**
     * The stored execution facts of one target occurrence, or a typed refusal.
     *
     * An occurrence with **no** session attempts is a normal result, not a failure: the workout has
     * not been started yet, and the record says so with an empty attempt list. What is refused is
     * stored data that disagrees with itself.
     *
     * @throws TargetOccurrenceExecutionReadException.MissingTargetOccurrence when no semantic record
     *   is stored for the pair.
     * @throws TargetOccurrenceExecutionReadException.MissingTargetSlot when the semantic record
     *   exists but no slot presents it.
     * @throws TargetOccurrenceExecutionReadException.SlotBelongsToAnotherProgram when the slot found
     *   for the pair is of another Program.
     * @throws TargetOccurrenceExecutionReadException.SlotTargetKeyMismatch when the slot found stores
     *   a different target key than the requested one.
     * @throws TargetOccurrenceExecutionReadException.SessionBelongsToAnotherProgram when an attempt
     *   found for the slot is of another Program.
     * @throws TargetOccurrenceExecutionReadException.SessionBelongsToAnotherRevision when an attempt
     *   found for the slot was started under another revision than the slot's.
     * @throws TargetOccurrenceExecutionReadException.SessionReferencesAnotherSlot when an attempt
     *   found for the slot attempts a different slot.
     * @throws IllegalArgumentException whatever [WorkoutSessionRepository] throws for a malformed
     *   stored session graph — the graph is validated where it is assembled, and this reader
     *   propagates that refusal instead of repairing it.
     */
    suspend fun executionRecordOf(
        programId: ProgramId,
        occurrenceKey: String
    ): TargetOccurrenceExecutionRecord {
        val occurrence = occurrenceRepository.occurrenceOf(programId, occurrenceKey)
            ?: throw TargetOccurrenceExecutionReadException.MissingTargetOccurrence(programId, occurrenceKey)

        val slot = scheduleRepository.slotByTargetOccurrenceKey(programId, occurrenceKey)
            ?: throw TargetOccurrenceExecutionReadException.MissingTargetSlot(programId, occurrenceKey)

        // The lookup was keyed on the pair, so these two are properties the query already implies.
        // They are asserted anyway: a repository that ever widened its query would otherwise produce
        // a record whose slot answers for a different identity, and the record's own invariants would
        // fail with a message that names neither the Program nor the key.
        if (slot.programId != programId) {
            throw TargetOccurrenceExecutionReadException.SlotBelongsToAnotherProgram(
                requestedProgramId = programId,
                slotProgramId = slot.programId,
                occurrenceKey = occurrenceKey
            )
        }
        if (slot.targetOccurrenceKey != occurrenceKey) {
            throw TargetOccurrenceExecutionReadException.SlotTargetKeyMismatch(
                requestedOccurrenceKey = occurrenceKey,
                storedOccurrenceKey = slot.targetOccurrenceKey,
                slotIdValue = slot.slotId.value
            )
        }

        // Only now does `slotId` enter: it is the established slot → session link (§23) and it is
        // used after the target identity has already located the slot. The order is the session
        // repository's own (`startedAt ASC, sessionId ASC`); this reader imposes no order of its own.
        val attempts = sessionRepository.sessionsOfSlot(slot.slotId)
        for (attempt in attempts) {
            if (attempt.programId != programId) {
                throw TargetOccurrenceExecutionReadException.SessionBelongsToAnotherProgram(
                    requestedProgramId = programId,
                    sessionProgramId = attempt.programId,
                    sessionIdValue = attempt.sessionId.value
                )
            }
            if (attempt.slotId != slot.slotId) {
                throw TargetOccurrenceExecutionReadException.SessionReferencesAnotherSlot(
                    slotIdValue = slot.slotId.value,
                    sessionIdValue = attempt.sessionId.value,
                    sessionSlotIdValue = attempt.slotId.value
                )
            }
            if (attempt.revisionId != slot.revisionId) {
                throw TargetOccurrenceExecutionReadException.SessionBelongsToAnotherRevision(
                    slotIdValue = slot.slotId.value,
                    sessionIdValue = attempt.sessionId.value,
                    slotRevisionIdValue = slot.revisionId.value,
                    sessionRevisionIdValue = attempt.revisionId.value
                )
            }
        }

        // The malformed-graph case is deliberately *not* caught here. `sessionsOfSlot` assembles
        // every session through the session repository's own mapper and snapshot validation, and a
        // graph that does not hold together fails there; catching it here and returning fewer
        // attempts would silently drop stored execution facts, which is the one outcome this phase
        // exists to prevent.
        return TargetOccurrenceExecutionRecord(occurrence = occurrence, slot = slot, attempts = attempts)
    }
}
