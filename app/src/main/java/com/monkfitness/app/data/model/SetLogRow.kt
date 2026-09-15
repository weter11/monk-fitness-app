package com.monkfitness.app.data.model

/**
 * The raw per-set columns a session-date query reads out of `set_log`.
 *
 * A [SetLog] row carries a calendar date but no cycle, program-day or session identity, so this is
 * deliberately only what persistence can prove: which exercise was confirmed, when, and the observed
 * amounts. The cycle and program day a row is attributed to are resolved by the caller from the
 * session date, never read off the row.
 *
 * `repsCompleted` and `durationSeconds` are exactly what the session observed — one row is one
 * confirmed set; a repetition set carries its prescribed target reps and no seconds, and a timed set
 * carries the elapsed hold seconds and no reps.
 */
data class SetLogRow(
    val exerciseId: String,
    val sessionDate: String,
    val timestamp: Long,
    val repsCompleted: Int,
    val durationSeconds: Int
)
