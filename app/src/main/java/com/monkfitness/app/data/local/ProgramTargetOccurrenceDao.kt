package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.ProgramTargetOccurrenceComponentEntity
import com.monkfitness.app.data.model.ProgramTargetOccurrenceEntity

/**
 * Persistence for the two target-occurrence tables (§30 step 14).
 *
 * ### What this DAO can and cannot do
 *
 * It has exactly three kinds of statement: two inserts and two reads. There is deliberately **no**
 * `UPDATE` and **no** `DELETE`:
 *
 *  * a stored payload is never rewritten in place. A repeated write of the same identity either
 *    matches what is stored — in which case the caller writes nothing — or is refused by
 *    [com.monkfitness.app.domain.program.target.TargetOccurrencePersistenceException] before this DAO
 *    is reached. A DAO that could update would make "never silently overwritten" a convention
 *    rather than a structural fact;
 *  * a stored payload is never dropped, because deleting one half of a target occurrence and leaving
 *    the other is exactly the half-written state this stage exists to prevent.
 *
 * ### The lookups are the identity, exactly
 *
 * [occurrenceOf] and [componentsOf] are both keyed on `(programId, occurrenceKey)` and nothing else.
 * Neither falls back to a date, a weekday, a plan day, a plan day's position or name, a revision, a
 * slot id or a list index: those are facts *about* an occurrence, never substitutes for its identity.
 * `occurrenceKey` is bound as a parameter and compared as text; no statement splits it, trims it or
 * matches it partially.
 *
 * [componentsOf] orders by `position`, which is the order the components were presented in, so a read
 * returns the caller's order rather than whatever order the engine happens to produce.
 */
@Dao
interface ProgramTargetOccurrenceDao {

    /** Stores the occurrences a caller decided on, each under its own `(programId, occurrenceKey)`. */
    @Insert
    suspend fun insertOccurrences(occurrences: List<ProgramTargetOccurrenceEntity>)

    /**
     * Stores the ordered components of those occurrences.
     *
     * The `(programId, occurrenceKey, position)` primary key is what makes the order a stored fact
     * and not a convention: a repeated position is refused by the engine rather than overwriting the
     * component that already held it.
     */
    @Insert
    suspend fun insertComponents(components: List<ProgramTargetOccurrenceComponentEntity>)

    /** The stored occurrence with this exact identity within this Program, or `null`. */
    @Query("SELECT * FROM `program_target_occurrence` WHERE `programId` = :programId AND `occurrenceKey` = :occurrenceKey LIMIT 1")
    suspend fun occurrenceOf(programId: String, occurrenceKey: String): ProgramTargetOccurrenceEntity?

    /** The occurrence's components in the order they were presented, identity as nothing else. */
    @Query("SELECT * FROM `program_target_occurrence_component` WHERE `programId` = :programId AND `occurrenceKey` = :occurrenceKey ORDER BY `position` ASC")
    suspend fun componentsOf(
        programId: String,
        occurrenceKey: String
    ): List<ProgramTargetOccurrenceComponentEntity>

    /** Every stored target occurrence of one Program, earliest planned date first. */
    @Query("SELECT * FROM `program_target_occurrence` WHERE `programId` = :programId ORDER BY `plannedFor` ASC, `occurrenceKey` ASC")
    suspend fun occurrencesOfProgram(programId: String): List<ProgramTargetOccurrenceEntity>
}
