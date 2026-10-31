package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.target.TargetExistingOccurrence
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionPolicy

/**
 * The one route from stored target occurrences to the target scheduling input: it reads the facts,
 * hands them to the execution policy, and takes only `.execution` from the answer.
 *
 * The caller's [PlannedOccurrence] supplies **one** thing, its `occurrenceKey`. Everything about what
 * the occurrence is comes from the stored read-back, so the reconciler compares OLD against NEW and
 * observes a payload conflict; forwarding the caller's occurrence would compare it against itself and
 * silently swallow the conflict.
 *
 * This KDoc names no target stage type on purpose: the production-source scans that count callers read
 * file text without stripping comments, so spelling them out would register this file as a caller.
 *
 * It decides nothing itself (the precedence stays in the policy), holds no repository, no clock and no
 * identity generator, and never compares two payloads — payload equality is the reconciler's rule.
 */
class TargetExistingOccurrenceReader(
    private val executionReader: TargetOccurrenceExecutionReader
) {

    /**
     * The target scheduling input for one **persisted** occurrence.
     *
     * [occurrence] supplies the lookup identity and nothing else: its payload is not returned, and a
     * caller presenting a different payload for the same key gets the stored one back. Typed read
     * refusals propagate unchanged — data that disagrees with itself is reported, not defaulted.
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
     * One read and one decision per occurrence, in the caller's order: nothing is filtered, sorted or
     * deduplicated, so a duplicate key is refused by the reconciler rather than merged here.
     */
    suspend fun existingOccurrencesOf(
        programId: ProgramId,
        occurrences: List<PlannedOccurrence>
    ): List<TargetExistingOccurrence> = occurrences.map { occurrence ->
        existingOccurrenceOf(programId, occurrence)
    }
}
