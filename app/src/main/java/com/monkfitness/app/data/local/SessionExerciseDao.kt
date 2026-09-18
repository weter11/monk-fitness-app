package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.SessionExerciseEntity

/**
 * Persistence for the `session_exercise` table — one exercise occurrence as it ran in one session
 * (§19).
 *
 * The row links back to the plan by `programExerciseId`, and that link is a plain reference, not a
 * foreign key: a plan that changes afterwards must not be able to rewrite, orphan or delete the record
 * of what was performed. Nothing in this DAO resolves that reference either — no join to
 * `program_exercise` for the prescription, because the prescription stored here is the one that was
 * presented.
 *
 * A skipped occurrence is a stored `skipped` flag with no set rows; the DAO does not write a
 * placeholder set for it, and reading it back does not invent one (§12).
 */
@Dao
interface SessionExerciseDao {

    /** Stores several occurrences as one batch — the presented plan at session start (§27). */
    @Insert
    suspend fun insertSessionExercises(exercises: List<SessionExerciseEntity>)

    /** Every occurrence of several sessions, in session identity and then presentation order. */
    @Query("SELECT * FROM `session_exercise` WHERE `sessionId` IN (:sessionIds) ORDER BY `sessionId` ASC, `position` ASC")
    suspend fun sessionExercisesOfSessions(sessionIds: List<String>): List<SessionExerciseEntity>

    /** The occurrence with [sessionExerciseId], or `null` when no such occurrence is stored. */
    @Query("SELECT * FROM `session_exercise` WHERE `sessionExerciseId` = :sessionExerciseId LIMIT 1")
    suspend fun sessionExerciseById(sessionExerciseId: String): SessionExerciseEntity?
}
