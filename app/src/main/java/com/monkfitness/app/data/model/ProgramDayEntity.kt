package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One day of a revision's plan.
 *
 * Identity and order are kept apart, which is the whole reason this type has two columns for what
 * looks like one thing: [programDayId] **identifies** the day, and [position] says where it sits in
 * the revision. A day number is not an identity — the Scheduler decides which calendar date a
 * position eventually lands on, and nothing about that decision is stored here.
 *
 * The day's plan elements are their own rows ([ProgramExerciseEntity]), so a day with no elements is a
 * legal row: a rest day prescribes nothing. A day belongs to exactly one revision and cascades with it,
 * taking its occurrences along.
 *
 * @property position 1-based place of this day in the revision; unique within that revision.
 * @property name an optional user-facing label; `null` means the day is presented by its position and
 *   type.
 */
@Entity(
    tableName = "program_day",
    foreignKeys = [
        ForeignKey(
            entity = ProgramRevisionEntity::class,
            parentColumns = ["revisionId"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["revisionId", "position"], unique = true)]
)
data class ProgramDayEntity(
    @PrimaryKey val programDayId: String,
    val revisionId: String,
    val position: Int,
    val type: String,
    val name: String? = null
)
