package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.ProgramDayEntity

/**
 * Persistence for the `program_day` table — the plan days of one revision (§23).
 *
 * A day's identity and its position are separate columns, and both reads below order by the position:
 * the plan's order is the user's, so it is data and not an artifact of storage. No query here
 * interprets what a day *is* — the type token is stored and returned, never acted on, and the
 * Scheduler's question (which calendar date a position lands on) is not a question this layer can
 * answer (§1, §20).
 *
 * There is no update and no delete: a revision's plan is immutable, and days disappear with their
 * revision through the schema's cascade (§6, §29).
 */
@Dao
interface ProgramDayDao {

    /** Stores the days of one revision as new rows. */
    @Insert
    suspend fun insertDays(days: List<ProgramDayEntity>)

    /** The days of one revision, in position order. */
    @Query("SELECT * FROM `program_day` WHERE `revisionId` = :revisionId ORDER BY `position` ASC")
    suspend fun daysOfRevision(revisionId: String): List<ProgramDayEntity>
}
