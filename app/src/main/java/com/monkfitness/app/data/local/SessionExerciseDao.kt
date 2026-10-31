package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.SessionExerciseEntity

/**
 * Persistence for the `session_exercise` table — one exercise occurrence as it ran in one session.
 *
 * The row links back to the plan by `programExerciseId`, and that link is a plain reference, not a
 * foreign key: a plan that changes afterwards must not be able to rewrite, orphan or delete the record
 * of what was performed. Nothing in this DAO resolves that reference either — no join to
 * `program_exercise` for the prescription, because the prescription stored here is the one that was
 * presented.
 *
 * A skipped occurrence is a stored `skipped` flag with no set rows; the DAO does not write a
 * placeholder set for it, and reading it back does not invent one.
 */
@Dao
interface SessionExerciseDao {

    @Insert
    suspend fun insertSessionExercises(exercises: List<SessionExerciseEntity>)

    @Query("SELECT * FROM `session_exercise` WHERE `sessionId` IN (:sessionIds) ORDER BY `sessionId` ASC, `position` ASC")
    suspend fun sessionExercisesOfSessions(sessionIds: List<String>): List<SessionExerciseEntity>

    /** The occurrence, or `null` when none is stored. */
    @Query("SELECT * FROM `session_exercise` WHERE `sessionExerciseId` = :sessionExerciseId LIMIT 1")
    suspend fun sessionExerciseById(sessionExerciseId: String): SessionExerciseEntity?
}
