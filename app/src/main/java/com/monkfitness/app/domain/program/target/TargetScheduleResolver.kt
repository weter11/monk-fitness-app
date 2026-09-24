package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.ScheduleCadence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Immutable definition of one target-producing schedule rule. */
data class TargetSchedule(
    val ruleId: String,
    val workoutId: String,
    val cadence: ScheduleCadence,
    val anchorDate: LocalDate
) {
    init {
        require(ruleId.isNotBlank()) { "a target schedule needs a rule identity" }
        require(workoutId.isNotBlank()) { "a target schedule needs a workout identity" }
    }

    companion object {
        fun daily(ruleId: String, workoutId: String, anchorDate: LocalDate) =
            TargetSchedule(ruleId, workoutId, ScheduleCadence.Daily, anchorDate)

        fun everyNDays(ruleId: String, workoutId: String, anchorDate: LocalDate, days: Int) =
            TargetSchedule(
                ruleId,
                workoutId,
                ScheduleCadence.EveryNDays(days),
                anchorDate
            )

        fun fixedWeekdays(
            ruleId: String,
            workoutId: String,
            anchorDate: LocalDate,
            weekdays: Set<DayOfWeek>
        ) = TargetSchedule(
            ruleId,
            workoutId,
            ScheduleCadence.FixedWeekdays(weekdays),
            anchorDate
        )

        fun sessionsPerWeek(
            ruleId: String,
            workoutId: String,
            anchorDate: LocalDate,
            sessionsPerWeek: Int
        ) = TargetSchedule(
            ruleId,
            workoutId,
            ScheduleCadence.SessionsPerWeek(sessionsPerWeek),
            anchorDate
        )

        fun derivedExcluding(
            ruleId: String,
            workoutId: String,
            anchorDate: LocalDate,
            sourceRuleId: String
        ) = TargetSchedule(
            ruleId,
            workoutId,
            ScheduleCadence.DerivedExcluding(sourceRuleId),
            anchorDate
        )
    }
}

/** Inclusive, explicitly bounded interval. It never supplies or infers the current date. */
data class TargetScheduleWindow(val from: LocalDate, val through: LocalDate) {
    init {
        require(!through.isBefore(from)) {
            "target schedule window cannot end before it starts"
        }
    }

    fun dates(): List<LocalDate> =
        (0L..ChronoUnit.DAYS.between(from, through)).map(from::plusDays)

    fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(through)
}

/** One concrete target occurrence. It contains no execution or progress state. */
data class ResolvedScheduleOccurrence(
    val ruleId: String,
    val workoutId: String,
    val plannedDate: LocalDate,
    val cadence: ScheduleCadence
)

/** Explicit, typed source dependency for a derived target. */
data class ResolvedScheduleSource(
    val sourceRuleId: String,
    val occurrences: List<ResolvedScheduleOccurrence>
) {
    init {
        require(sourceRuleId.isNotBlank()) { "a resolved source needs a rule identity" }
    }
}

/** Pure bounded target-date resolution; no ambient state, clock, randomness or persistence. */
object TargetScheduleResolver {
    fun resolve(
        schedules: List<TargetSchedule>,
        window: TargetScheduleWindow,
        sources: Map<String, ResolvedScheduleSource> = emptyMap()
    ): List<ResolvedScheduleOccurrence> = schedules
        .flatMap { schedule -> resolve(schedule, window, sources[sourceRuleId(schedule)]) }
        .sortedWith(compareBy<ResolvedScheduleOccurrence> { it.plannedDate }.thenBy { it.ruleId })

    fun resolve(
        schedule: TargetSchedule,
        window: TargetScheduleWindow,
        source: ResolvedScheduleSource? = null
    ): List<ResolvedScheduleOccurrence> = when (schedule.cadence) {
        ScheduleCadence.Daily -> window.dates()
            .filterNot { it.isBefore(schedule.anchorDate) }
            .map { date -> occurrence(schedule, date) }
        is ScheduleCadence.EveryNDays -> {
            val firstEligible = maxOf(window.from, schedule.anchorDate)
            val elapsed = ChronoUnit.DAYS.between(schedule.anchorDate, firstEligible)
            val interval = schedule.cadence.days.toLong()
            val firstOnPattern = schedule.anchorDate.plusDays(((elapsed + interval - 1L) / interval) * interval)
            generateSequence(firstOnPattern) { it.plusDays(interval) }
                .takeWhile(window::contains)
                .map { date -> occurrence(schedule, date) }
                .toList()
        }
        is ScheduleCadence.SessionsPerWeek -> {
            val weekdays = weeklyWeekdays(schedule.cadence.sessionsPerWeek)
            window.dates()
                .filter { !it.isBefore(schedule.anchorDate) && it.dayOfWeek in weekdays }
                .map { date -> occurrence(schedule, date) }
        }
        is ScheduleCadence.FixedWeekdays -> window.dates()
            .filter { !it.isBefore(schedule.anchorDate) && it.dayOfWeek in schedule.cadence.weekdays }
            .map { date -> occurrence(schedule, date) }
        is ScheduleCadence.DerivedExcluding -> {
            require(source != null) {
                "derived target ${schedule.ruleId} requires its source ${schedule.cadence.sourceRuleId}"
            }
            require(source.sourceRuleId == schedule.cadence.sourceRuleId) {
                "derived target ${schedule.ruleId} expected source ${schedule.cadence.sourceRuleId}, " +
                    "was ${source.sourceRuleId}"
            }
            val excludedDates = source.occurrences.map { it.plannedDate }.toSet()
            window.dates()
                .filter { !it.isBefore(schedule.anchorDate) && it !in excludedDates }
                .map { date -> occurrence(schedule, date) }
        }
    }

    private fun sourceRuleId(schedule: TargetSchedule): String? =
        (schedule.cadence as? ScheduleCadence.DerivedExcluding)?.sourceRuleId

    private fun weeklyWeekdays(sessionsPerWeek: Int): Set<DayOfWeek> = when (sessionsPerWeek) {
        1 -> setOf(DayOfWeek.MONDAY)
        2 -> setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        3 -> setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        4 -> setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.SATURDAY
        )
        5 -> setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY
        )
        6 -> setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SUNDAY
        )
        7 -> DayOfWeek.entries.toSet()
        else -> error("SessionsPerWeek is outside 1..7")
    }

    private fun occurrence(schedule: TargetSchedule, date: LocalDate) =
        ResolvedScheduleOccurrence(
            ruleId = schedule.ruleId,
            workoutId = schedule.workoutId,
            plannedDate = date,
            cadence = schedule.cadence
        )
}
