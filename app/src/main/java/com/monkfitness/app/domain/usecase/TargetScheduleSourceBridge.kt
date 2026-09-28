package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.TargetScheduleSourceRepository
import com.monkfitness.app.domain.common.RevisionId

/**
 * The smallest explicit bridge from a revision's persisted target source to the two lists a target
 * scheduling input needs.
 *
 * ```text
 * persisted, revision-owned target source
 *         ↓  TargetScheduleSourceBridge
 * scheduleDefinitions + programDayBindings
 *         ↓  TargetScheduleInput (the caller's other eight values)
 * TargetScheduleInputAdapter
 *         ↓
 * TargetScheduleOrchestrationRequest
 * ```
 *
 * ### What it is for
 *
 * Until this existed there was no honest way for a production caller to obtain
 * `TargetScheduleInput.scheduleDefinitions`: every way to get them would have had to reconstruct a
 * rule identity, a workout identity, an anchor date and a derived rule's source from Program data that
 * does not state them. This class reads the source that *does* state them, and hands the caller the
 * caller's own values unchanged.
 *
 * ### What it deliberately does not do
 *
 *  * it does **not** read `ProgramSchedule` and translate it. A legacy schedule states when slots
 *    fall; a target rule states a rule identity, a workout identity, a cadence and an anchor, and a
 *    mapping between the two would have to invent the three the legacy vocabulary does not carry;
 *  * it does **not** construct a target identity from a `ProgramDay`, a position, a name, a date or an
 *    id's own text — it constructs no identity at all, it forwards stored ones;
 *  * it does **not** decide a date, a window, a composition, an `asOf`, a pause or a source's resolved
 *    occurrences. Those are the other eight values of [TargetScheduleInput] and they stay the caller's;
 *  * it does **not** call the planner, the policy, the presenter, the persister or the orchestrator,
 *    and it does not run a pass;
 *  * it holds no clock, no identity generator and no window of its own, and it inspects no execution,
 *    session or performance state.
 *
 * ### A missing source is refused, never filled in
 *
 * When the revision states no source, [definitionsAndBindingsOf] returns
 * [TargetScheduleSourceRead.Missing] unchanged rather than an empty pair of lists. A future production
 * caller therefore cannot accidentally schedule an empty plan against a revision that simply never had
 * target semantics stated — and it cannot fall back to the legacy schedule either, because there is no
 * fallback here to take.
 */
class TargetScheduleSourceBridge(
    private val sourceRepository: TargetScheduleSourceRepository
) {

    /**
     * The stated rules and bindings of one revision, or the typed reason there are none.
     *
     * The two lists are the source's own lists, in the source's own order: nothing is sorted, merged,
     * deduplicated or renamed on the way out, so an equivalent source always produces an
     * equality-identical pair. A [TargetScheduleSourceRead.Malformed] outcome propagates as it is —
     * stored rows that disagree with the vocabulary are reported, not defaulted.
     */
    suspend fun definitionsAndBindingsOf(revisionId: RevisionId): TargetScheduleSourceRead =
        sourceRepository.sourceOf(revisionId)
}
