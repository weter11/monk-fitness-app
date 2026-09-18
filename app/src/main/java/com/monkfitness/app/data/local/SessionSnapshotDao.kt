package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.SessionSnapshotEntity

/**
 * Persistence for the `session_snapshot` table — the frozen record of what a session was presented
 * when it started (§19).
 *
 * One row per session, keyed by the session's own identity, written once at start. The DAO offers no
 * update and no delete, because the record it holds is the thing §19 exists to protect: an edit to the
 * program, a new revision or a superseding adjustment must not be able to change what a workout
 * already recorded, and an update statement here would be exactly that capability.
 *
 * It reads only from the snapshot's own rows. There is no query in this DAO that joins a snapshot to
 * `program_revision`, `program_day`, `program_exercise` or `adaptive_adjustment` — reconstructing a
 * presentation from the live plan is the failure the snapshot exists to prevent.
 *
 * [snapshotsOf] reads the captures of several sessions at once, so reconstructing a list of sessions
 * costs one query rather than one per session.
 */
@Dao
interface SessionSnapshotDao {

    /** Stores the captured presentation of one session. */
    @Insert
    suspend fun insertSnapshot(snapshot: SessionSnapshotEntity)

    /** The captured presentations of several sessions, in identity order. */
    @Query("SELECT * FROM `session_snapshot` WHERE `sessionId` IN (:sessionIds) ORDER BY `sessionId` ASC")
    suspend fun snapshotsOf(sessionIds: List<String>): List<SessionSnapshotEntity>
}
