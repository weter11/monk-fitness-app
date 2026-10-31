package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity

/**
 * Persistence for the `program_workout_slot` table — the planned opportunities to train.
 *
 * A slot row holds no amount of work, and nothing here computes one: the reads return identity,
 * ownership, the planned date, the status and the completion stamp, and [countByStatus] counts rows
 * with a status rather than measuring anything about them. A `MISSED` slot therefore cannot be summed
 * into a zero-valued workout by this layer, because there is no value to sum.
 *
 * [slotsFrom] filters by a date the *caller* supplies, and the status it matches is bound as a
 * parameter rather than spelled into the SQL: which status counts as open is domain vocabulary, and a
 * DAO that hard-coded it would be holding a rule. Deciding *which* dates to generate, whether a slot
 * is missed and how a horizon is extended remains the Scheduler's work and is not expressible here.
 *
 * [updateOutcome] is the one mutation a slot needs — the record of what happened to the opportunity,
 * written by the transaction that owns that fact. It writes the status and stamp the caller decided on;
 * it never infers them from the sessions of the slot.
 */
@Dao
interface ProgramWorkoutSlotDao {

    @Insert
    suspend fun insertSlots(slots: List<ProgramWorkoutSlotEntity>)

    /** The slot, or `null` when none is stored. */
    @Query("SELECT * FROM `program_workout_slot` WHERE `slotId` = :slotId LIMIT 1")
    suspend fun slotById(slotId: String): ProgramWorkoutSlotEntity?

    /**
     * The lookup is exactly the stored identity pair. It never falls back to a date, plan day, revision
     * or position: those are slot facts, not substitutes for the occurrence key.
     */
    @Query("SELECT * FROM `program_workout_slot` WHERE `programId` = :programId AND `targetOccurrenceKey` = :targetOccurrenceKey LIMIT 1")
    suspend fun slotByTargetOccurrenceKey(
        programId: String,
        targetOccurrenceKey: String
    ): ProgramWorkoutSlotEntity?

    @Query("SELECT * FROM `program_workout_slot` WHERE `programId` = :programId ORDER BY `plannedFor` ASC, `slotId` ASC")
    suspend fun slotsOfProgram(programId: String): List<ProgramWorkoutSlotEntity>

    @Query("SELECT * FROM `program_workout_slot` WHERE `revisionId` = :revisionId ORDER BY `plannedFor` ASC, `slotId` ASC")
    suspend fun slotsOfRevision(revisionId: String): List<ProgramWorkoutSlotEntity>

    @Query("SELECT * FROM `program_workout_slot` WHERE `programId` = :programId AND `plannedFor` >= :fromDate AND `status` = :status ORDER BY `plannedFor` ASC, `slotId` ASC")
    suspend fun slotsFrom(programId: String, fromDate: String, status: String): List<ProgramWorkoutSlotEntity>

    @Query("UPDATE `program_workout_slot` SET `status` = :status, `completedAt` = :completedAt WHERE `slotId` = :slotId")
    suspend fun updateOutcome(slotId: String, status: String, completedAt: Long?)

    /** How many slots of one Program hold [status]. A row count, not a performance measure. */
    @Query("SELECT COUNT(*) FROM `program_workout_slot` WHERE `programId` = :programId AND `status` = :status")
    suspend fun countByStatus(programId: String, status: String): Int
}
