package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Query
import com.monkfitness.app.data.model.WorkoutSessionEntity

/**
 * Persistence for the `workout_session` table — one started run of one slot (§19, §23).
 *
 * The session is persisted runtime state: it is created once when it starts and survives the screen
 * closing, the process being recreated and a reboot, which is why [sessionById] exists as a plain read
 * rather than something a caller has to reconstruct from the current plan.
 *
 * No statement here is unique on `slotId`, and that is deliberate: several attempts at one slot are
 * legal (§19), so [sessionsOfSlot] returns them all in start order. What *is* enforced is the one rule
 * that spans two rows — *"no more than one session per slot is `IN_PROGRESS`"* — and it is enforced by
 * [insertSessionIfSlotIsNotOccupied]'s own condition rather than by a declared constraint, because a
 * uniqueness rule on `slotId` would forbid the finished attempts the architecture allows and a rule on
 * the status alone would forbid two slots being worked at once. It is a rule about the *pair*, so it is
 * decided by the statement that writes the second row: the engine evaluates the predicate inside the
 * insert, and the row count is the answer.
 *
 * The live plan is not reachable from this DAO: a session's presentation lives in its snapshot rows
 * and is never re-derived from `program_revision`, `program_day` or `program_exercise` (§19).
 */
@Dao
interface WorkoutSessionDao {

    /**
     * Stores one session row **only when the slot it attempts is not already being worked out**, and
     * reports how many rows it inserted: `1` when the session was stored, `0` when the slot already
     * held a session in [status].
     *
     * This is the DAO's **only** way to create a session row, and that is the point: §19's *"no more
     * than one session per slot is `IN_PROGRESS`"* is carried by the write itself rather than by a read
     * before it — a read-then-write is a window two starts can both pass through — and there is no
     * second, unguarded insert beside it for a caller to reach for.
     *
     * It is a conditional statement rather than a uniqueness constraint on purpose. A constraint on
     * `slotId` would forbid the repeated attempts §19 allows; what is refused here is a second
     * *unfinished* session for one slot, which is a rule about two rows at once and therefore cannot be
     * expressed as a declared index that Room would generate and validate. The condition is evaluated by
     * the engine inside the statement, so the count it returns is the decision, not an inference from a
     * read that happened earlier.
     *
     * [status] is the status the session is stored with, and `occupiedStatus` the status an existing
     * session must hold for the write to be refused. They are two parameters rather than one repeated
     * name — the statement says what each occurrence means — and they are not always the same value: the
     * rule §19 states is about `IN_PROGRESS` attempts, so a caller storing a finished attempt passes its
     * own status first and the rule's status second. The tokens are parameters, as every status in this
     * layer is: the vocabulary stays in the domain.
     *
     * A duplicate `sessionId` is still a conflict (the primary key refuses it), never a silent
     * overwrite.
     *
     * The write stores **nothing at all** when the slot is occupied, so the caller needs to know
     * whether it did: [changedRowCount] reports it, and the two are read together inside one
     * transaction on one connection.
     */
    @Query("INSERT INTO `workout_session` (`sessionId`, `slotId`, `programId`, `revisionId`, `status`, `startedAt`, `finishedAt`) SELECT :sessionId, :slotId, :programId, :revisionId, :status, :startedAt, :finishedAt WHERE NOT EXISTS (SELECT 1 FROM `workout_session` WHERE `slotId` = :occupiedSlotId AND `status` = :occupiedStatus)")
    suspend fun insertSessionIfSlotIsNotOccupied(sessionId: String, slotId: String, programId: String, revisionId: String, status: String, startedAt: Long, finishedAt: Long?, occupiedSlotId: String, occupiedStatus: String)

    /**
     * How many rows the most recent `INSERT`, `UPDATE` or `DELETE` on this connection changed — SQLite's
     * own `changes()`.
     *
     * It exists for exactly one caller, and the reason is stated where it is used: a statement of the
     * form `INSERT ... SELECT ... WHERE NOT EXISTS (...)` stores either its row or nothing, and Room
     * gives an insert no return value that could say which happened (`INSERT` methods may return
     * `void` or a rowid, and a rowid is the *last* one on the connection, not this statement's). The
     * count is the engine's answer about the statement that was just executed on the connection the
     * transaction is open on, which is why it must be read immediately after it and inside that
     * transaction.
     */
    @Query("SELECT changes()")
    suspend fun changedRowCount(): Int

    /** The session with [sessionId], or `null` when no such session is stored. */
    @Query("SELECT * FROM `workout_session` WHERE `sessionId` = :sessionId LIMIT 1")
    suspend fun sessionById(sessionId: String): WorkoutSessionEntity?

    /** Every attempt at one slot, in start order — one row per attempt, never fewer. */
    @Query("SELECT * FROM `workout_session` WHERE `slotId` = :slotId ORDER BY `startedAt` ASC, `sessionId` ASC")
    suspend fun sessionsOfSlot(slotId: String): List<WorkoutSessionEntity>

    /** Every session of one Program, in start order. */
    @Query("SELECT * FROM `workout_session` WHERE `programId` = :programId ORDER BY `startedAt` ASC, `sessionId` ASC")
    suspend fun sessionsOfProgram(programId: String): List<WorkoutSessionEntity>

    /**
     * The sessions of one Program reduced to what a slot's `attempts` needs: which slot each session
     * belongs to and its identity, in start order. Kept as one query so assembling a Program's slots
     * does not need a query per slot, and ordered so the attempt list of every slot is deterministic.
     */
    @Query("SELECT `slotId`, `sessionId`, `startedAt` FROM `workout_session` WHERE `programId` = :programId ORDER BY `startedAt` ASC, `sessionId` ASC")
    suspend fun attemptsOfProgram(programId: String): List<SessionAttemptRow>

    /** The same projection for one revision's slots. */
    @Query("SELECT `slotId`, `sessionId`, `startedAt` FROM `workout_session` WHERE `revisionId` = :revisionId ORDER BY `startedAt` ASC, `sessionId` ASC")
    suspend fun attemptsOfRevision(revisionId: String): List<SessionAttemptRow>

    /** Records how one session ended: its status and, when it ended, when. */
    @Query("UPDATE `workout_session` SET `status` = :status, `finishedAt` = :finishedAt WHERE `sessionId` = :sessionId")
    suspend fun updateOutcome(sessionId: String, status: String, finishedAt: Long?)

    /** How many sessions of one Program hold [status]. A row count, not a score. */
    @Query("SELECT COUNT(*) FROM `workout_session` WHERE `programId` = :programId AND `status` = :status")
    suspend fun countByStatus(programId: String, status: String): Int
}

/**
 * One session's slot binding, as the slot-assembly read returns it.
 *
 * It exists so the `attempts` of a slot can be read for a whole Program in one query instead of one
 * query per slot. It is a persistence projection, not a domain type: the repository turns these rows
 * into the typed session ids a `WorkoutSlot` carries.
 */
data class SessionAttemptRow(
    val slotId: String,
    val sessionId: String,
    val startedAt: Long
)
