package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.ProgramRevisionEntity

/**
 * Persistence for the `program_revision` table — the saved, immutable plans of a Program (§6).
 *
 * The DAO is insert-and-read only, and that is the contract rather than an omission: a revision is
 * immutable history, so there is no update of a revision and no delete of a revision to offer. A
 * structural change saves a new revision with a new identity (§6), and a revision that is no longer
 * current still describes exactly what it described, which is what lets a session started under it
 * stay explained (§19).
 *
 * Rows disappear only through the ownership cascade when their Program is deleted (§29), which is why
 * the only writer here is an insert.
 */
@Dao
interface ProgramRevisionDao {

    /** Stores one revision row. The `(programId, revisionNumber)` index makes a duplicate a conflict. */
    @Insert
    suspend fun insertRevision(revision: ProgramRevisionEntity)

    /** The revision with [revisionId], or `null` when no such revision is stored. */
    @Query("SELECT * FROM `program_revision` WHERE `revisionId` = :revisionId LIMIT 1")
    suspend fun revisionById(revisionId: String): ProgramRevisionEntity?

    /** Every revision of one Program, in revision-number order. */
    @Query("SELECT * FROM `program_revision` WHERE `programId` = :programId ORDER BY `revisionNumber` ASC")
    suspend fun revisionsOfProgram(programId: String): List<ProgramRevisionEntity>

    /** How many revisions one Program has saved. */
    @Query("SELECT COUNT(*) FROM `program_revision` WHERE `programId` = :programId")
    suspend fun countRevisionsOf(programId: String): Int
}
