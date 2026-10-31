package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.TargetScheduleOccurrenceRepository
import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionReadException
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionRecord

/**
 * Reads a target occurrence's **stored execution facts**: `(programId, occurrenceKey)` locates the
 * semantic occurrence, the slot by the same pair, then every attempt at that slot.
 *
 * The slot is located by the `(programId, occurrenceKey)` pair, **not** by `slotId`, a revision, a plan
 * day or a weekday; `slotId` enters only afterwards, to follow the established slot -> session link.
 * Attempts are kept in the session repository's own start order, unfiltered and unreordered.
 *
 * It decides nothing: a slot may hold several attempts and which one settles the execution state is a
 * policy no stored fact supplies, so producing an answer here would invent a precedence rule. It also
 * holds no clock and no identity generator — an execution is read back, never computed from when the
 * read happened — and opens no transaction.
 */
class TargetOccurrenceExecutionReader(
    private val occurrenceRepository: TargetScheduleOccurrenceRepository,
    private val scheduleRepository: ProgramScheduleRepository,
    private val sessionRepository: WorkoutSessionRepository
) {

    /**
     * The stored execution facts of one target occurrence, or a typed refusal.
     *
     * No session attempts is a normal result, not a failure: the workout has not been started, and the
     * record says so with an empty list. What is refused is stored data that disagrees with itself —
     * a missing occurrence or slot, or a slot/attempt of another Program, revision or slot.
     */
    suspend fun executionRecordOf(
        programId: ProgramId,
        occurrenceKey: String
    ): TargetOccurrenceExecutionRecord {
        val occurrence = occurrenceRepository.occurrenceOf(programId, occurrenceKey)
            ?: throw TargetOccurrenceExecutionReadException.MissingTargetOccurrence(programId, occurrenceKey)

        val slot = scheduleRepository.slotByTargetOccurrenceKey(programId, occurrenceKey)
            ?: throw TargetOccurrenceExecutionReadException.MissingTargetSlot(programId, occurrenceKey)

        // The lookup was keyed on the pair, so these are properties the query implies. Asserted anyway:
        // a repository that widened its query would otherwise yield a record whose invariants fail with
        // a message naming neither the Program nor the key.
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

        // Only now does `slotId` enter, after the target identity has located the slot. The order is
        // the session repository's own; this reader imposes none of its own.
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

        // The malformed-graph case is deliberately not caught here: `sessionsOfSlot` validates the
        // graph itself, and returning fewer attempts would silently drop stored execution facts.
        return TargetOccurrenceExecutionRecord(occurrence = occurrence, slot = slot, attempts = attempts)
    }
}
