package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.ProgramRevisionEntity

/**
 * Persistence for the `program_revision` table — the saved, immutable plans of a Program.
 *
 * Insert-and-read only, and that is the contract rather than an omission: a revision is immutable
 * history, so there is no update and no delete to offer. A structural change saves a new revision with
 * a new identity, and a revision that is no longer current still describes exactly what it described,
 * which is what lets a session started under it stay explained. Rows disappear only through the
 * ownership cascade when their Program is deleted.
 */
@Dao
interface ProgramRevisionDao {

    /** The `(programId, revisionNumber)` index makes a duplicate a conflict. */
    @Insert
    suspend fun insertRevision(revision: ProgramRevisionEntity)

    /** The revision, or `null` when none is stored. */
    @Query("SELECT * FROM `program_revision` WHERE `revisionId` = :revisionId LIMIT 1")
    suspend fun revisionById(revisionId: String): ProgramRevisionEntity?

    @Query("SELECT * FROM `program_revision` WHERE `programId` = :programId ORDER BY `revisionNumber` ASC")
    suspend fun revisionsOfProgram(programId: String): List<ProgramRevisionEntity>

    @Query("SELECT COUNT(*) FROM `program_revision` WHERE `programId` = :programId")
    suspend fun countRevisionsOf(programId: String): Int
}
