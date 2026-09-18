package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.ProgramExerciseEntity

/**
 * Persistence for the `program_exercise` table — the plan elements of one revision's days (§9, §23).
 *
 * [exercisesOfRevision] is the one query that spans two tables, and it exists because reading a plan is
 * reading days and their elements together: the join carries the revision's ordering (`day position`,
 * then `element position`) so the repository can assemble a `ProgramRevision` without a query per day
 * and without re-sorting rows it was handed in an arbitrary order. The stored prescription is returned
 * per set, exactly as stored — `12 / 10 / 8 / 6` is four numbers, not a uniform target, and no query
 * here aggregates or collapses them (§10).
 *
 * The exercise's library key is opaque to this layer: it is stored and returned, never resolved, and
 * no exercise metadata is written, read or copied here (§10). There is no update and no delete: a
 * revision's plan is immutable, and elements disappear with their day through the schema's cascade
 * (§6, §29).
 */
@Dao
interface ProgramExerciseDao {

    /** Stores the plan elements of one revision's days as new rows. */
    @Insert
    suspend fun insertExercises(exercises: List<ProgramExerciseEntity>)

    /**
     * Every plan element of one revision, ordered by its day's position and then by its own position.
     */
    @Query("SELECT `program_exercise`.* FROM `program_exercise` INNER JOIN `program_day` ON `program_day`.`programDayId` = `program_exercise`.`programDayId` WHERE `program_day`.`revisionId` = :revisionId ORDER BY `program_day`.`position` ASC, `program_exercise`.`position` ASC")
    suspend fun exercisesOfRevision(revisionId: String): List<ProgramExerciseEntity>
}
