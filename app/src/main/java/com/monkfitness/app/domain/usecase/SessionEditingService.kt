package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.workout.SessionExercise
import com.monkfitness.app.domain.workout.SessionRefusal
import com.monkfitness.app.domain.workout.SessionRuntimeResult
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession

/**
 * Correcting a running attempt: taking back the last confirmed set, and skipping an occurrence.
 *
 * These are edits to what was already recorded rather than steps of an attempt's life, which is why they
 * live here instead of in [SessionRuntime]: that layer's surface stays the five operations of one session.
 *
 * The rules are the domain's own and are enforced here, before anything is written: a skipped occurrence
 * observed nothing, so it cannot hold sets, and undo removes exactly one set — the one performed last.
 */
class SessionEditingService(private val sessions: WorkoutSessionRepository) {

    /**
     * Removes the set that was performed last, across every occurrence of [sessionId].
     *
     * The choice is per occurrence first and by set index second, so the row removed is always the last of
     * its own occurrence: the remaining sets stay numbered `1..n` with no gap.
     */
    suspend fun undoLastSet(sessionId: SessionId): SessionRuntimeResult<WorkoutSession> = editingOutcome {
        val session = sessions.sessionById(sessionId)
            ?: return@editingOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        if (!session.isInProgress) {
            return@editingOutcome refused(SessionRefusal.SessionIsNotInProgress(sessionId, session.status))
        }
        val last = lastPerformedSet(session)
            ?: return@editingOutcome refused(SessionRefusal.NothingToUndo(sessionId))

        sessions.removeSet(last.setLogId)
        success(requireStored(sessionId))
    }

    /**
     * Records the user's "not this one" for one occurrence of [sessionId].
     *
     * Skipping an occurrence that is already skipped writes nothing: the flag is already the answer, and a
     * second write would only rewrite a row to the value it holds.
     */
    suspend fun skipOccurrence(
        sessionId: SessionId,
        sessionExerciseId: SessionExerciseId
    ): SessionRuntimeResult<WorkoutSession> = editingOutcome {
        val session = sessions.sessionById(sessionId)
            ?: return@editingOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        if (!session.isInProgress) {
            return@editingOutcome refused(SessionRefusal.SessionIsNotInProgress(sessionId, session.status))
        }
        val occurrence = session.exercises.firstOrNull { it.sessionExerciseId == sessionExerciseId }
            ?: return@editingOutcome refused(
                SessionRefusal.OccurrenceIsNotOfThisSession(sessionId, sessionExerciseId)
            )
        if (occurrence.skipped) return@editingOutcome success(session)
        if (occurrence.results.isNotEmpty()) {
            return@editingOutcome refused(
                SessionRefusal.SkippingWouldEraseConfirmedSets(
                    sessionId = sessionId,
                    sessionExerciseId = sessionExerciseId,
                    confirmedSets = occurrence.results.size
                )
            )
        }

        sessions.markSkipped(sessionExerciseId)
        success(requireStored(sessionId))
    }

    /**
     * The set performed last: the occurrence whose own last set is the latest of all, then that
     * occurrence's highest set index.
     */
    private fun lastPerformedSet(session: WorkoutSession): SetResult? =
        session.exercises
            .filter { occurrence -> occurrence.results.isNotEmpty() }
            .maxByOrNull { occurrence -> occurrence.results.maxOf { set -> set.performedAt } }
            ?.let { occurrence -> occurrence.results.maxBy { set -> set.setIndex } }

    private suspend fun requireStored(sessionId: SessionId): WorkoutSession =
        sessions.sessionById(sessionId)
            ?: error("the session '${sessionId.value}' was written and is not readable")
}

private suspend fun <T> editingOutcome(
    block: suspend () -> SessionRuntimeResult<T>
): SessionRuntimeResult<T> = try {
    block()
} catch (failure: Throwable) {
    SessionRuntimeResult.Failure(failure)
}

private fun refused(reason: SessionRefusal): SessionRuntimeResult<Nothing> =
    SessionRuntimeResult.Refused(reason)

private fun <T> success(value: T): SessionRuntimeResult<T> = SessionRuntimeResult.Success(value)
