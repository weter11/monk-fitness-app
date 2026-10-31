package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.TargetScheduleSourceRepository
import com.monkfitness.app.domain.common.RevisionId

/**
 * The smallest explicit bridge from a revision's persisted target source to the two lists a target
 * scheduling input needs: `scheduleDefinitions + programDayBindings`.
 *
 * Every other way to obtain them would have had to reconstruct a rule identity, a workout identity, an
 * anchor date and a derived rule's source from Program data that does not state them. This reads the
 * source that *does* state them and hands the caller's own values back unchanged.
 *
 * It does not read `ProgramSchedule` and translate it (a legacy schedule says when slots fall, and a
 * mapper would invent the three fields it lacks), constructs no identity, decides no date, window,
 * composition, `asOf`, pause or resolved source — those are the caller's other eight values — and it
 * calls no planner, policy, presenter, persister or orchestrator.
 *
 * A revision that states no source yields [TargetScheduleSourceRead.Missing] unchanged rather than an
 * empty pair, so a caller cannot schedule an empty plan against a revision that never had target
 * semantics stated, and there is no legacy fallback here to take.
 */
class TargetScheduleSourceBridge(
    private val sourceRepository: TargetScheduleSourceRepository
) {

    /**
     * The stated rules and bindings of one revision, or the typed reason there are none.
     *
     * The two lists are the source's own, in its own order: nothing is sorted, merged, deduplicated or
     * renamed, so an equivalent source always produces an equality-identical pair. A
     * [TargetScheduleSourceRead.Malformed] outcome propagates — disagreeing rows are reported, not
     * defaulted.
     */
    suspend fun definitionsAndBindingsOf(revisionId: RevisionId): TargetScheduleSourceRead =
        sourceRepository.sourceOf(revisionId)
}
