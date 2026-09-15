package com.monkfitness.app.domain.adaptive

/**
 * A confirmed-set row attributed to the session it belongs to.
 *
 * A persisted set row records the exercise id, the observed amounts and a calendar session date, but
 * carries no cycle, program-day or workout identity of its own — the session a set belongs to is
 * established by the calendar position that date resolves to, not by the set row. This projection
 * adds the ([cycleNumber], [programDay]) that attribution resolves, so the mapper reads one
 * already-grouped value instead of re-deriving session identity.
 *
 * `repsCompleted` and `durationSeconds` are exactly what the session observed: one row is one
 * confirmed set; a repetition set carries its prescribed target reps and no seconds, and a timed set
 * carries the elapsed hold seconds and no reps. Repackaging the row here never reinterprets either.
 */
data class SessionSetLog(
    val cycleNumber: Int,
    val programDay: Int,
    val exerciseId: String,
    val sessionDate: String,
    val timestamp: Long,
    val repsCompleted: Int,
    val durationSeconds: Int
)
