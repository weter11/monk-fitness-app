package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.monkfitness.app.data.model.ProgramEntity

/**
 * Persistence for the `program` table — the Program System's aggregate root rows.
 *
 * Nothing here decides anything about a Program: no lifecycle transition, no archive rule, no
 * selection fallback, no revision choice. The queries store a row a caller has already decided on and
 * read rows back in a deterministic order (§24: DAOs are persistence only).
 *
 * Two operations are deliberately present and two are deliberately absent:
 *
 *  * [setCurrentRevision] exists because the "which revision describes this Program" pointer is a
 *    column of this table (§23) and the pointer has to move when a new revision is saved. It writes
 *    the pointer the caller named — it does not choose one.
 *  * [deleteProgram] is the whole deletion: the target schema's ownership edges are `ON DELETE
 *    CASCADE` (§29), so deleting this row destroys every Program-owned row through the database's own
 *    cascade. Manually deleting children here would duplicate the schema's ownership graph and could
 *    disagree with it, so no such statement exists in this layer.
 *
 * A delete of the *selected* Program is refused by the database (`app_state.selectedProgramId` is
 * `ON DELETE NO ACTION`) and the failure propagates to the caller: this DAO does not swallow it and
 * does not clear the selection to make the delete succeed.
 */
@Dao
interface ProgramDao {

    /** Stores one new Program row. A duplicate identity is a conflict, never a silent overwrite. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProgram(program: ProgramEntity)

    /**
     * Writes every mutable column of an existing Program row, matched by `programId`. Renaming,
     * describing, archiving, changing the lifecycle and stamping a start all go through here; none of
     * them is a structural change, which is why the plan rows are untouched by it (§6).
     */
    @Update
    suspend fun updateProgram(program: ProgramEntity)

    /** The Program with [programId], or `null` when no such Program is stored. */
    @Query("SELECT * FROM `program` WHERE `programId` = :programId LIMIT 1")
    suspend fun programById(programId: String): ProgramEntity?

    /** Every stored Program, oldest first, with the identity as the deterministic tiebreak. */
    @Query("SELECT * FROM `program` ORDER BY `createdAt` ASC, `programId` ASC")
    suspend fun programs(): List<ProgramEntity>

    /** How many Programs are stored. */
    @Query("SELECT COUNT(*) FROM `program`")
    suspend fun countPrograms(): Int

    /**
     * Points a Program at the revision that now describes its plan, and stamps the change.
     *
     * The pointer is deliberately not a foreign key (§23), so nothing enforces that the revision
     * exists or belongs to this Program — the persistence transaction that saves a revision writes
     * both in one unit, which is what makes the pointer true rather than merely accepted.
     */
    @Query("UPDATE `program` SET `currentRevisionId` = :revisionId, `updatedAt` = :updatedAt WHERE `programId` = :programId")
    suspend fun setCurrentRevision(programId: String, revisionId: String, updatedAt: Long)

    /** Deletes one Program. Every row it owns goes through the schema's cascade (§29). */
    @Query("DELETE FROM `program` WHERE `programId` = :programId")
    suspend fun deleteProgram(programId: String)
}
