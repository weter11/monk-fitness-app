package com.monkfitness.app.domain.progress

/**
 * How often the scope was trained inside one [ProgressWindow]. The definition is deliberately narrow,
 * because every widening of it is an invention:
 *
 *  * a workout counts only when a `COMPLETED` session happened on a date inside the window — not an
 *    attempt, not a slot, and never "days the program planned something for";
 *  * the date is that of its actual `startedAt`, read in the calculator's explicit zone, so a session
 *    that began at 23:40 and ended after midnight counts on the date it began — the only reading under
 *    which [trainingDays] means "days I trained";
 *  * [trainingDays] counts distinct dates, which is what separates "trained three times" from "trained
 *    on three days";
 *  * both rates are ratios of those counted facts over the window's own calendar length; neither is a
 *    load, a score or an amount of work.
 */
data class TrainingFrequency(
    val window: ProgressWindow,
    val completedSessions: Int,
    val trainingDays: Int
) {

    init {
        require(completedSessions >= 0) { "a count of workouts is not negative, was $completedSessions" }
        require(trainingDays in 0..completedSessions) {
            "every training day holds at least one completed workout and no day holds one that did not " +
                "happen: days=$trainingDays sessions=$completedSessions"
        }
    }

    val calendarDays: Int
        get() = window.calendarDays

    /** Completed workouts per seven calendar dates — a rate over the window, not a score. */
    val sessionsPerSevenDays: Double
        get() = completedSessions * 7.0 / calendarDays

    val trainingDayRatio: Double
        get() = trainingDays.toDouble() / calendarDays
}
