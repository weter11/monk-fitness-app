package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.WorkoutSession

/**
 * The raw facts one Progress aggregation is computed from (§12, §19, §21).
 *
 * ### Why this type exists
 *
 * The blueprint's flow for this stage is
 * `Room entity → repository raw facts → pure calculator → use case → UI`, and this is the middle arrow:
 * the facts handed to a calculation that knows nothing about Room, about Program status, about the
 * clock, or about which screen is showing it. They are the **domain values the repositories already
 * produce** — a [WorkoutSlot] and a [WorkoutSession] — and not a parallel projection, because a second
 * shape of the same rows would be a second definition of what a slot and a session are (§25, §24). In
 * particular a session here is the **assembled** session: its captured presentation
 * (`SessionSnapshot` + `SessionSnapshotExercise`) and its confirmed sets (`SetLog`) as the repositories
 * read them, which is exactly what §21 requires performance progression to be derived from and what
 * makes it impossible for this layer to see a prescription other than the one the user was shown (§19).
 *
 * ### The one invariant, and the two scopes
 *
 * A **Program-scoped** fact set is homogeneous: every slot and every session in it belongs to the Program
 * the scope names. That is the mechanical form of §21's default context — a calculation about Program A
 * cannot read Program B's history, because it cannot *hold* it: mixing the two is refused at
 * construction, before any measure is computed. It is deliberately a check on the facts rather than only
 * a hope about the read that produced them, so an isolation defect fails here rather than as a wrong
 * number somewhere downstream.
 *
 * The **aggregate** scope is the opposite case and needs no such rule: it is defined as the facts of every
 * Program, so its members legitimately carry several program ids. Nothing is merged on the way in — each
 * slot and each session keeps the identity it was stored with — and that is what makes the aggregate a
 * sum of Program-scoped facts rather than an entity with facts of its own (§21).
 *
 * @property scope what the facts are about.
 * @property slots the opportunities of the scope, as stored.
 * @property sessions the attempts of the scope, each assembled from its own snapshot and sets.
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

    /** Whether anything at all has been recorded in this scope. */
    val isEmpty: Boolean
        get() = slots.isEmpty() && sessions.isEmpty()

    /** The Programs these facts belong to, in the order they first appear. */
    val programIds: List<ProgramId>
        get() = (slots.map { it.programId } + sessions.map { it.programId }).distinct()

    companion object {

        /**
         * A scope with nothing recorded in it yet.
         *
         * It is a value and not a special case: §21 requires an empty history to produce zeroes and empty
         * lists of the right type, so every calculation below is exercised on it, and "no history" is
         * answered by the same code path that answers "some history" rather than by a guard that returns
         * whatever a screen wanted to see.
         */
        fun empty(scope: ProgressScope): ProgressFacts = ProgressFacts(scope, emptyList(), emptyList())
    }
}
