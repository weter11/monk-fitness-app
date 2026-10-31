package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgramSetLogDao
import com.monkfitness.app.data.local.SessionExerciseDao
import com.monkfitness.app.data.local.SessionSnapshotDao
import com.monkfitness.app.data.local.SessionSnapshotExerciseDao
import com.monkfitness.app.data.local.WorkoutSessionDao
import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.model.WorkoutSessionEntity
import com.monkfitness.app.data.mapper.sessionDomain
import com.monkfitness.app.data.mapper.toDomain
import com.monkfitness.app.data.mapper.toEntity
import com.monkfitness.app.data.mapper.toSessionExerciseEntities
import com.monkfitness.app.data.mapper.toSnapshotEntity
import com.monkfitness.app.data.mapper.toSnapshotExerciseEntities
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import com.monkfitness.app.domain.program.WorkoutSlot

/**
 * The session's persistence: starting one, reading it back, confirming sets and finishing it.
 *
 * A session is **always** reconstructed from its own captured presentation — `session_snapshot`,
 * `session_snapshot_exercise` and the session's identity triple on its own row — and never from the live
 * plan: this class reads no `program_revision`, `program_day`, `program_exercise` and no adaptive table,
 * so a later revision, edit or superseding adjustment cannot change the presentation a started (or
 * finished) workout was measured against, and the adjustment ids a session was presented with are the
 * ones stored in its snapshot rather than a query of which adjustments are current.
 *
 * [startSession] is one transaction, so a session can never exist without the presentation it was started
 * under, and an occurrence can never be stored without the snapshot element it presents. [finishSession]
 * writes the session and the slot it attempted in one transaction, because "the workout is complete" and
 * "the opportunity was taken" are one fact. A completion's adaptive half is deliberately **not** written
 * here: [ProgramAdaptiveRepository.persistDecision] owns it, and `SessionRuntime.finishSession` composes
 * the two in one unit of work — this layer must not pre-empt that by deciding a completion always
 * produces an adaptive write.
 *
 * Nothing about the session's lifecycle is decided here: whether a second attempt at a slot is allowed
 * (it is), whether a session may be cancelled, whether a set belongs to the prescription, and whether a
 * workout *should* be completed all belong to the layer that owns the lifecycle. This repository stores
 * the graph it is handed, appends the sets it is told about and reports failures instead of absorbing
 * them.
 */
class WorkoutSessionRepository(
    private val sessionDao: WorkoutSessionDao,
    private val snapshotDao: SessionSnapshotDao,
    private val snapshotExerciseDao: SessionSnapshotExerciseDao,
    private val sessionExerciseDao: SessionExerciseDao,
    private val setLogDao: ProgramSetLogDao,
    private val slotDao: ProgramWorkoutSlotDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * Persists a started session and its complete captured presentation as one unit.
     *
     * The session's own rows carry the whole graph: the session row, the snapshot row, the snapshot's
     * elements and the occurrences it runs. Nothing is derived from the plan — the snapshot the caller
     * captured *is* the presentation, and the live plan is not consulted to complete it.
     *
     * The row is written by the DAO's **conditional** insert, so "no more than one session per slot is
     * `IN_PROGRESS`" is decided by the write and not by a read before it: the statement stores the session
     * only when the slot holds no other attempt in `IN_PROGRESS`, and reports `0` when it does not. A
     * refused start writes **nothing at all** — session, snapshot and occurrence rows all disappear with
     * the transaction — which is what makes a second start atomic rather than half-taken.
     *
     * The status the guard counts is `IN_PROGRESS`, the rule's own status, and **not** the status of the
     * row being written: the rule is about *unfinished* attempts, so storing a finished attempt (a
     * restored or imported one, a fixture's) neither obeys nor disturbs it. What the write refuses is a
     * second attempt while one is running.
     *
     * @throws IllegalArgumentException when the session violates a domain invariant (a snapshot of
     *   another session, occurrences the snapshot did not present, a status that disagrees with its
     *   finish stamp).
     * @throws SessionAlreadyInProgress when the slot already holds an `IN_PROGRESS` attempt. The refusal
     *   is atomic: nothing of this session is left behind.
     * @throws Exception whatever the DAOs throw — a duplicate identity, a constraint failure — with the
     *   transaction rolled back, so no half-started session survives.
     */
    suspend fun startSession(session: WorkoutSession) {
        val row = session.toEntity()
        inTransaction {
            sessionDao.insertSessionIfSlotIsNotOccupied(
                sessionId = row.sessionId,
                slotId = row.slotId,
                programId = row.programId,
                revisionId = row.revisionId,
                status = row.status,
                startedAt = row.startedAt,
                finishedAt = row.finishedAt,
                // The status that occupies a slot is the one the rule names, read from the entity's own
                // token so the statement and the rule cannot drift apart.
                occupiedSlotId = row.slotId,
                occupiedStatus = WorkoutSessionEntity.IN_PROGRESS
            )
            // The occupancy rule is the statement's own predicate, so whether this attempt was stored
            // is the count the engine reports for that statement — read here, before anything else is
            // written on this connection, and inside the same unit.
            if (sessionDao.changedRowCount() == 0) throw SessionAlreadyInProgress(session.slotId)
            snapshotDao.insertSnapshot(session.toSnapshotEntity())
            val elements = session.toSnapshotExerciseEntities()
            if (elements.isNotEmpty()) snapshotExerciseDao.insertSnapshotExercises(elements)
            val occurrences = session.toSessionExerciseEntities()
            if (occurrences.isNotEmpty()) sessionExerciseDao.insertSessionExercises(occurrences)
        }
    }

    /** The session with [sessionId] fully assembled from its own stored rows, or `null` when none is. */
    suspend fun sessionById(sessionId: SessionId): WorkoutSession? {
        val session = sessionDao.sessionById(sessionId.value) ?: return null
        return assemble(listOf(session)).single()
    }

    /**
     * Every attempt at one slot, in start order, each fully assembled.
     *
     * Several attempts are legal and none is special-cased here: the read returns exactly the sessions
     * stored for the slot. "At most one of them is `IN_PROGRESS`" is a rule for the transaction that
     * starts a session, not for a read, and this method neither filters nor repairs a database that
     * violates it.
     */
    suspend fun sessionsOfSlot(slotId: SlotId): List<WorkoutSession> =
        assemble(sessionDao.sessionsOfSlot(slotId.value))

    /** Every session of one Program, in start order, each fully assembled. */
    suspend fun sessionsOfProgram(programId: ProgramId): List<WorkoutSession> =
        assemble(sessionDao.sessionsOfProgram(programId.value))

    /**
     * Appends one confirmed set to one occurrence.
     *
     * The occurrence is checked to exist first, so a set cannot be filed under an occurrence that was
     * never stored; the set's own row is the record that work happened, and no zero-valued row is written
     * for a set that was not performed.
     *
     * @throws IllegalArgumentException when no occurrence with [sessionExerciseId] is stored.
     */
    suspend fun appendSet(sessionExerciseId: SessionExerciseId, set: SetResult) {
        val occurrence = sessionExerciseDao.sessionExerciseById(sessionExerciseId.value)
            ?: throw IllegalArgumentException(
                "a confirmed set belongs to a stored occurrence: session_exercise " +
                    "'${sessionExerciseId.value}' does not exist"
            )
        setLogDao.insertSet(set.toEntity(SessionExerciseId(occurrence.sessionExerciseId)))
    }

    /** The confirmed sets of one occurrence, in set order. */
    suspend fun setsOf(sessionExerciseId: SessionExerciseId): List<SetResult> =
        setLogDao.setsOfSessionExercise(sessionExerciseId.value).map { it.toDomain() }

    /**
     * Records how one session ended, and nothing else.
     *
     * The session row's outcome is `IN_PROGRESS → COMPLETED | CANCELLED`, written on its own here because
     * a cancellation ends the attempt **without** taking the opportunity: `Back` does not cancel either,
     * and a cancelled attempt must not complete a slot. A completion is two facts (the workout ended, the
     * opportunity was taken), and [finishSession] is what writes both.
     *
     * @throws Exception whatever the DAO throws, uncaught.
     */
    suspend fun recordSessionOutcome(session: WorkoutSession) {
        sessionDao.updateOutcome(
            sessionId = session.sessionId.value,
            status = session.status.name,
            finishedAt = session.finishedAt?.toEpochMilli()
        )
    }

    /**
     * Records a finished session and the slot it attempted, in one transaction.
     *
     * Both rows are written because this is one fact: the workout ended and the opportunity was taken. The
     * statuses and the finish stamp are the caller's decision — this method derives nothing from the
     * dates, does not decide whether a slot was missed and does not touch the adaptive rows.
     *
     * @throws IllegalArgumentException when the session does not attempt [slot].
     */
    suspend fun finishSession(session: WorkoutSession, slot: WorkoutSlot) {
        require(session.slotId == slot.slotId) {
            "a finished session records its own slot: session '${session.sessionId.value}' attempts " +
                "'${session.slotId.value}' but slot '${slot.slotId.value}' was handed in"
        }
        inTransaction {
            recordSessionOutcome(session)
            slotDao.updateOutcome(
                slotId = slot.slotId.value,
                status = slot.status.name,
                completedAt = slot.completedAt?.toEpochMilli()
            )
        }
    }

    /**
     * Assembles every session's graph from its own rows in a fixed number of queries: the session rows,
     * their snapshots, their captured elements, their occurrences and their confirmed sets. A session
     * whose snapshot is missing is invalid persisted data and fails loudly inside the mapper rather than
     * being loaded without the presentation it was started under.
     */
    private suspend fun assemble(sessions: List<WorkoutSessionEntity>): List<WorkoutSession> {
        if (sessions.isEmpty()) return emptyList()
        val ids = sessions.map { it.sessionId }
        val snapshots = snapshotDao.snapshotsOf(ids).associateBy { it.sessionId }
        val snapshotElements = snapshotExerciseDao.snapshotExercisesOfSessions(ids).groupBy { it.sessionId }
        val occurrences = sessionExerciseDao.sessionExercisesOfSessions(ids).groupBy { it.sessionId }
        val sets = setLogDao.setsOfSessions(ids).groupBy { it.sessionExerciseId }

        return sessions.map { session ->
            val ownOccurrences = occurrences[session.sessionId].orEmpty()
            sessionDomain(
                session = session,
                snapshot = snapshots[session.sessionId],
                snapshotExercises = snapshotElements[session.sessionId].orEmpty(),
                sessionExercises = ownOccurrences,
                setLogs = ownOccurrences.flatMap { sets[it.sessionExerciseId].orEmpty() }
            )
        }
    }
}

/**
 * A start was refused because the slot was already being worked out.
 *
 * Thrown by [WorkoutSessionRepository.startSession] when the conditional insert stored no row, and thrown
 * *inside* the transaction, so the refusal leaves nothing behind: not the session, not its snapshot, not
 * an occurrence. It is a distinct type rather than an `IllegalArgumentException` because it is not a
 * defect in the caller's value — it is the rule, and the layer above turns it into the typed refusal a
 * caller branches on: an expected state, not a failure.
 */
class SessionAlreadyInProgress(val slotId: SlotId) : RuntimeException(
    "the slot '${slotId.value}' is already being worked out: no more than one session per slot is " +
        "IN_PROGRESS (§19)"
)
