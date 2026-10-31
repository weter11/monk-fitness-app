package com.monkfitness.app.data.model

import androidx.room.Entity

/**
 * One day of the optional **posture / mobility** track: whether that track day's session was done.
 *
 * A **retained global feature**, not Program state: it belongs to the 56-day daily-track calendar
 * ([com.monkfitness.app.domain.track.TrackCalendar]) and not to any Program, revision, slot or
 * session — completing a mobility session says nothing about a Program, and no Program reads this
 * table. `trackCycle`/`trackDay` are the track's own identity, not the retired program's vocabulary.
 *
 * @property trackCycle which 56-day track cycle this day belongs to, 1-based.
 * @property trackDay which day of that cycle, 1-based.
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
