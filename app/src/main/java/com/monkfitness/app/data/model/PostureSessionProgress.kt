package com.monkfitness.app.data.model

import androidx.room.Entity

/**
 * One day of the optional **posture / mobility** track: whether that track day's session was done.
 *
 * This is a **retained global feature**, not Program state (§4). It belongs to the 56-day daily-track
 * calendar ([com.monkfitness.app.domain.track.TrackCalendar]) and not to any Program, Program
 * revision, ProgramWorkoutSlot or session: completing a mobility session says nothing about a
 * Program, and no Program reads this table.
 *
 * The columns were called `cycleNumber` / `day` while the shipped 56-day program existed. §30 step
 * 15 retired that program; the row identity was renamed to `trackCycle` / `trackDay` in the same
 * migration (`MIGRATION_11_12`, which renames the columns and copies nothing) so that the surviving
 * track stops borrowing the retired program's vocabulary — a reviewer reading `trackCycle` cannot
 * mistake it for the Program's cycle, which no longer exists. The **rows themselves are preserved**:
 * the migration renames columns in place.
 *
 * @property trackCycle which 56-day track cycle this day belongs to, 1-based.
 * @property trackDay which day of that cycle, 1-based.
 * @property isCompleted whether the day's mobility session was completed.
 * @property completionDate when it was completed, epoch milliseconds, or 0 before it was.
 * @property focusArea the flexibility focus areas the session was run for, as the track stored them.
 */
@Entity(
    tableName = "posture_session_progress",
    primaryKeys = ["trackCycle", "trackDay"],
)
data class PostureSessionProgress(
    val trackCycle: Int = 1,
    val trackDay: Int,
    val isCompleted: Boolean = false,
    val completionDate: Long = 0L,
    val focusArea: String = ""
)
