package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.ProgramPauseEntity

/**
 * Persistence for the `program_pause` table — the pause intervals of one Program (§3, §23).
 *
 * Keeping the intervals is what makes "this program was paused" explainable afterwards, so the DAO
 * reads them back as history as well as writing them: [pausesOfProgram] returns every interval of a
 * Program in start order, not only the open one.
 *
 * [closePause] records the end of an interval the caller has already decided to end. It performs no
 * lifecycle transition: it does not check the Program's status, does not open a new interval, does
 * not resume anything, and does not create or touch a revision. Whether a pause should start or end
 * is lifecycle (§3) and is not decided here.
 */
@Dao
interface ProgramPauseDao {

    /** Stores one pause interval. */
    @Insert
    suspend fun insertPause(pause: ProgramPauseEntity)

    /** The interval with [pauseId], or `null` when no such interval is stored. */
    @Query("SELECT * FROM `program_pause` WHERE `pauseId` = :pauseId LIMIT 1")
    suspend fun pauseById(pauseId: String): ProgramPauseEntity?

    /** Every pause interval of one Program, earliest start first. */
    @Query("SELECT * FROM `program_pause` WHERE `programId` = :programId ORDER BY `startedAt` ASC, `pauseId` ASC")
    suspend fun pausesOfProgram(programId: String): List<ProgramPauseEntity>

    /** Records when an interval ended. The interval itself is not rewritten or removed. */
    @Query("UPDATE `program_pause` SET `endedAt` = :endedAt WHERE `pauseId` = :pauseId")
    suspend fun closePause(pauseId: String, endedAt: Long)
}
