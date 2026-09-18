package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import java.time.Instant

/**
 * One run of one slot: a session the user started, with what they were presented and what they
 * confirmed (§19).
 *
 * A session is created **once**, when it starts, and from that moment it is persisted runtime state —
 * it survives the screen closing, the process being recreated, an app restart and a reboot. Going
 * back does not cancel it; only an explicit cancel ends it that way, and the history stays either
 * way. A slot may be attempted more than once, but no more than one session per slot is
 * `IN_PROGRESS`; that rule spans sessions and belongs to the storage transaction that starts one
 * (§19, §27), not to a single value here.
 *
 * The session is bound to identity and to its snapshot, and the binding is enforced at construction:
 *
 *  * the [snapshot] must be the snapshot of *this* session, for *this* slot, program and revision —
 *    so a session can never be assembled from a plan that was never presented to it;
 *  * every [SessionExercise] must be an element the snapshot presented, with the same exercise, so a
 *    session cannot record work the user was never shown;
 *  * the status and the finish stamp agree: a session still `IN_PROGRESS` has not finished, and a
 *    completed or cancelled one has.
 *
 * Planned and actual time are different facts and are kept apart: the planned date lives in the
 * snapshot's workout, while [startedAt] and [finishedAt] are what actually happened — a session
 * started on one date and completed the next preserves both (§19).
 *
 * @property sessionId identity of this session.
 * @property slotId the opportunity it attempts.
 * @property programId the Program it belongs to.
 * @property revisionId the revision it started under.
 * @property snapshot the frozen record of what was presented.
 * @property status in progress, completed or cancelled.
 * @property startedAt when the user started it.
 * @property finishedAt when it was completed or cancelled, or `null` while it is in progress.
 * @property exercises the occurrences it ran, in presentation order.
 */
data class WorkoutSession(
    val sessionId: SessionId,
    val slotId: SlotId,
    val programId: ProgramId,
    val revisionId: RevisionId,
    val snapshot: WorkoutSessionSnapshot,
    val status: SessionStatus,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val exercises: List<SessionExercise> = emptyList()
) {

    init {
        require(snapshot.sessionId == sessionId) {
            "a session is bound to its own snapshot, got ${snapshot.sessionId}"
        }
        require(
            snapshot.workout.slotId == slotId &&
                snapshot.workout.programId == programId &&
                snapshot.workout.revisionId == revisionId
        ) {
            "a session is bound to the slot, program and revision it started under: " +
                "session=($slotId, $programId, $revisionId) " +
                "snapshot=(${snapshot.workout.slotId}, ${snapshot.workout.programId}, " +
                "${snapshot.workout.revisionId})"
        }
        require((status == SessionStatus.IN_PROGRESS) == (finishedAt == null)) {
            "an IN_PROGRESS session has not finished and a finished session is not IN_PROGRESS: " +
                "status=$status finishedAt=$finishedAt"
        }
        require(finishedAt == null || finishedAt >= startedAt) {
            "a session cannot finish before it started: started=$startedAt finished=$finishedAt"
        }
        require(exercises.map { it.sessionExerciseId }.toSet().size == exercises.size) {
            "an exercise occurrence is run at most once in a session"
        }
        require(exercises.all { it.isPresentedBy(snapshot) }) {
            "a session may only run what its snapshot presented (§19); got " +
                "${exercises.filterNot { it.isPresentedBy(snapshot) }.map { it.exerciseId }}"
        }
    }

    /** Whether the session ended as a finished workout. */
    val isCompleted: Boolean
        get() = status.isCompleted

    /** Whether the session is still running. */
    val isInProgress: Boolean
        get() = status == SessionStatus.IN_PROGRESS

    private fun SessionExercise.isPresentedBy(snapshot: WorkoutSessionSnapshot): Boolean =
        snapshot.workout.exercises.any {
            it.programExerciseId == programExerciseId && it.exerciseId == exerciseId
        }
}
