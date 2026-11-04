package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.SetLogEntity

/**
 * Persistence for the `program_set_log` table — one confirmed set.
 *
 * A set row exists because a set was confirmed — a skipped exercise, a missed slot and a cancelled
 * session produce no row at all — so there is no update that could turn a performed set into an
 * unperformed one and no zero row to write in the first place. The one removal is [removeSet], which
 * takes back a confirmation the user made by mistake; the choice of which row that is stays with the
 * layer that knows the order the sets were performed in.
 *
 * [setsOfSessions] is the read a session's reconstruction uses: one query for every confirmed set of
 * every occurrence of several sessions, ordered by presentation order and then by set index, so no
 * caller has to sort sets or query per exercise.
 */
@Dao
interface ProgramSetLogDao {

    @Insert
    suspend fun insertSet(setLog: SetLogEntity)

    @Query("DELETE FROM `program_set_log` WHERE `setLogId` = :setLogId")
    suspend fun removeSet(setLogId: String)

    @Query("SELECT * FROM `program_set_log` WHERE `sessionExerciseId` = :sessionExerciseId ORDER BY `setIndex` ASC")
    suspend fun setsOfSessionExercise(sessionExerciseId: String): List<SetLogEntity>

    @Query("SELECT `program_set_log`.* FROM `program_set_log` INNER JOIN `session_exercise` ON `session_exercise`.`sessionExerciseId` = `program_set_log`.`sessionExerciseId` WHERE `session_exercise`.`sessionId` IN (:sessionIds) ORDER BY `session_exercise`.`sessionId` ASC, `session_exercise`.`position` ASC, `program_set_log`.`setIndex` ASC")
    suspend fun setsOfSessions(sessionIds: List<String>): List<SetLogEntity>

    /**
     * A count of stored rows — deliberately not a volume measure: repetitions are not comparable across
     * exercises, and the architecture forbids inventing a scalar for them.
     */
    @Query("SELECT COUNT(*) FROM `program_set_log` INNER JOIN `session_exercise` ON `session_exercise`.`sessionExerciseId` = `program_set_log`.`sessionExerciseId` INNER JOIN `workout_session` ON `workout_session`.`sessionId` = `session_exercise`.`sessionId` WHERE `workout_session`.`programId` = :programId")
    suspend fun countSetsOfProgram(programId: String): Int
}
