package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.WorkoutSession

/**
 * The raw facts one Progress aggregation is computed from — the domain values the repositories already
 * produce, not a parallel projection, because a second shape of the same rows would be a second
 * definition of what a slot and a session are. A session here is the assembled one (captured snapshot
 * plus confirmed sets), so this layer can never see a prescription other than the one the user was
 * shown.
 *
 * A Program-scoped fact set is homogeneous: every slot and session belongs to the Program the scope
 * names, and mixing is refused at construction, before any measure is computed — an isolation defect
 * fails here rather than as a wrong number downstream. The aggregate scope is the opposite case and
 * needs no such rule: its members legitimately carry several program ids, each keeping the identity it
 * was stored with.
 */
data class ProgressFacts(
    val scope: ProgressScope,
    val slots: List<WorkoutSlot>,
    val sessions: List<WorkoutSession>
) {

    init {
        require(slots.map { it.slotId }.toSet().size == slots.size) {
            "an opportunity is read at most once into an aggregation"
        }
        require(sessions.map { it.sessionId }.toSet().size == sessions.size) {
            "an attempt is read at most once into an aggregation"
        }
        val owning = scope
        if (owning is ProgressScope.OfProgram) {
            val foreign = slots.filter { it.programId != owning.programId } +
                sessions.filter { it.programId != owning.programId }
            require(foreign.isEmpty()) {
                "a Program-scoped aggregation reads that Program's facts alone (§21): " +
                    "'${owning.programId.value}' was given ${foreign.size} row(s) belonging to " +
                    "${(slots.map { it.programId } + sessions.map { it.programId }).distinct().map { it.value }}"
            }
        }
    }

    val isEmpty: Boolean
        get() = slots.isEmpty() && sessions.isEmpty()

    val programIds: List<ProgramId>
        get() = (slots.map { it.programId } + sessions.map { it.programId }).distinct()

    companion object {

        /**
         * A scope with nothing recorded yet, as a value rather than a special case: every calculation is
         * exercised on it, so "no history" is answered by the same code path as "some history".
         */
        fun empty(scope: ProgressScope): ProgressFacts = ProgressFacts(scope, emptyList(), emptyList())
    }
}
