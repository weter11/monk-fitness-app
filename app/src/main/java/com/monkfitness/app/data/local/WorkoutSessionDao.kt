package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.WorkoutSessionEntity

/**
 * Persistence for the `workout_session` table — one started run of one slot (§19, §23).
 *
 * The session is persisted runtime state: it is created once when it starts and survives the screen
 * closing, the process being recreated and a reboot, which is why [sessionById] exists as a plain read
 * rather than something a caller has to reconstruct from the current plan.
 *
 * Nothing here is unique on `slotId`, and that is deliberate: several attempts at one slot are legal
 * (§19), so [sessionsOfSlot] returns them all in start order and no statement in this DAO could
 * reject the second one. "No more than one session per slot is `IN_PROGRESS`" is a rule about two
 * rows at once and therefore belongs to the transaction that starts a session (§27) — a WHERE clause
 * here could not decide it, and inventing a constraint would forbid attempts the architecture allows.
 *
 * The live plan is not reachable from this DAO: a session's presentation lives in its snapshot rows
 * and is never re-derived from `program_revision`, `program_day` or `program_exercise` (§19).
 */
@Dao
interface WorkoutSessionDao {

    /** Stores one session row. A duplicate identity is a conflict, never a silent overwrite. */
    @Insert
    suspend fun insertSession(session: WorkoutSessionEntity)

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
