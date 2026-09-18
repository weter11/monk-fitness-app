package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.SessionSnapshotExerciseEntity

/**
 * Persistence for the `session_snapshot_exercise` table — the elements of a captured presentation
 * (§19).
 *
 * Each row is what the user was shown for one plan element, with the prescription **copied** at capture
 * time. The DAO therefore writes at session start and only ever reads: there is no update that could
 * re-point a captured element at a newer plan, and no query that joins these rows to `program_exercise`
 * — the captured presentation is a value, not a pointer into a plan that may later be superseded.
 *
 * Rows come back in presentation order within their session, and grouped per session for a batch read,
 * so the repository never re-sorts or guesses which element came first.
 */
@Dao
interface SessionSnapshotExerciseDao {

    /** Stores the elements of one captured presentation. */
    @Insert
    suspend fun insertSnapshotExercises(exercises: List<SessionSnapshotExerciseEntity>)

    /** The captured elements of several sessions, in session identity and then presentation order. */
    @Query("SELECT * FROM `session_snapshot_exercise` WHERE `sessionId` IN (:sessionIds) ORDER BY `sessionId` ASC, `position` ASC")
    suspend fun snapshotExercisesOfSessions(sessionIds: List<String>): List<SessionSnapshotExerciseEntity>
}
