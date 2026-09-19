package com.monkfitness.app.domain.progress

/**
 * §21's **frequency**: how often the scope was trained inside one [ProgressWindow].
 *
 * The definition is deliberately narrow, because every widening of it is an invention:
 *
 *  * a workout counts when a **`COMPLETED` session** happened on a date inside the window. A cancelled
 *    attempt is not a workout (§12, §19) and neither is an opportunity that passed — so the count is a
 *    count of finished workouts, not of attempts, not of slots, and never of "days the program planned
 *    something for";
 *  * the date a workout counts on is the calendar date of its actual `startedAt`, read in the
 *    calculator's explicit zone: a session that began at 23:40 and ended after midnight happened on the
 *    date it began. That is the only reading under which `trainingDays` means "days I trained";
 *  * [trainingDays] counts **distinct** such dates, which is what separates *"trained three times"* from
 *    *"trained on three days"* — two workouts on one evening are neither more consistent nor a longer
 *    streak than one;
 *  * and the two rates are ratios of those counted facts over the window's own calendar length. Neither
 *    is a load, a score or an amount of work: no repetition, second or exercise enters them (§17).
 *
 * @property window the inclusive range of calendar dates this frequency is about.
 * @property completedSessions how many completed workouts fall inside it.
 * @property trainingDays how many distinct calendar dates hold at least one of them.
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

    /** How many calendar dates the window spans, both ends included. */
    val calendarDays: Int
        get() = window.calendarDays

    /** Completed workouts per seven calendar dates — a rate over the window, not a score. */
    val sessionsPerSevenDays: Double
        get() = completedSessions * 7.0 / calendarDays

    /** The fraction of the window's calendar dates that hold at least one completed workout. */
    val trainingDayRatio: Double
        get() = trainingDays.toDouble() / calendarDays
}
