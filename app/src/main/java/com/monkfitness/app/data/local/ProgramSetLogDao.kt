package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.SetLogEntity

/**
 * Persistence for the `program_set_log` table — one confirmed set (§19, §23).
 *
 * This is the target set log, not the shipped `set_log`: the legacy row is
 * `(exerciseId, repsCompleted, durationSeconds, timestamp, sessionDate)`, keyed by an autoincrement id
 * and describing the app's old logging shape, while this row is one confirmed set of one session
 * exercise with its own identity. No statement in this DAO reads or writes the legacy table, and the
 * two are not interchangeable (§30 step 15 retires the legacy one).
 *
 * Append and read only. A set row exists because a set was confirmed — a skipped exercise, a missed
 * slot and a cancelled session produce no row at all — so there is no update that could turn a
 * performed set into an unperformed one and no zero row to write in the first place (§12).
 *
 * [setsOfSessions] is the read a session's reconstruction uses: one query for every confirmed set of
 * every occurrence of several sessions, ordered by presentation order and then by set index, so no
 * caller has to sort sets or query per exercise.
 */
@Dao
interface ProgramSetLogDao {

    /** Appends one confirmed set. */
    @Insert
    suspend fun insertSet(setLog: SetLogEntity)

    /** The confirmed sets of one occurrence, in set order. */
    @Query("SELECT * FROM `program_set_log` WHERE `sessionExerciseId` = :sessionExerciseId ORDER BY `setIndex` ASC")
    suspend fun setsOfSessionExercise(sessionExerciseId: String): List<SetLogEntity>

    /** Every confirmed set of several sessions, in presentation order and then set order. */
    @Query("SELECT `program_set_log`.* FROM `program_set_log` INNER JOIN `session_exercise` ON `session_exercise`.`sessionExerciseId` = `program_set_log`.`sessionExerciseId` WHERE `session_exercise`.`sessionId` IN (:sessionIds) ORDER BY `session_exercise`.`sessionId` ASC, `session_exercise`.`position` ASC, `program_set_log`.`setIndex` ASC")
    suspend fun setsOfSessions(sessionIds: List<String>): List<SetLogEntity>

    /**
     * How many sets one Program's sessions have confirmed. A count of stored rows — deliberately not a
     * volume measure: repetitions are not comparable across exercises, and the architecture forbids
     * inventing a scalar for them (§17).
     */
    @Query("SELECT COUNT(*) FROM `program_set_log` INNER JOIN `session_exercise` ON `session_exercise`.`sessionExerciseId` = `program_set_log`.`sessionExerciseId` INNER JOIN `workout_session` ON `workout_session`.`sessionId` = `session_exercise`.`sessionId` WHERE `workout_session`.`programId` = :programId")
    suspend fun countSetsOfProgram(programId: String): Int
}
