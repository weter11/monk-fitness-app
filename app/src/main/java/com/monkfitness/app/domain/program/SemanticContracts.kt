package com.monkfitness.app.domain.program

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Stage 1's pure schedule vocabulary. It deliberately describes values, not persistence or runtime
 * state. A schedule rule owns WHEN; an occurrence owns the calendar fact; execution owns EXECUTION.
 */
sealed interface ScheduleCadence {
    data object Daily : ScheduleCadence
    data class EveryNDays(val days: Int) : ScheduleCadence {
        init { require(days > 0) { "EveryNDays must have a positive interval, was $days" } }
    }
    /** Distinct target semantic; never normalized to `EveryNDays`. */
    data class SessionsPerWeek(val sessionsPerWeek: Int) : ScheduleCadence {
        init { require(sessionsPerWeek in 1..7) { "SessionsPerWeek must be within 1..7" } }
    }
    data class FixedWeekdays(val weekdays: Set<DayOfWeek>) : ScheduleCadence {
        init { require(weekdays.isNotEmpty()) { "FixedWeekdays must name a weekday" } }
    }
    data class DerivedExcluding(val sourceRuleId: String) : ScheduleCadence {
        init { require(sourceRuleId.isNotBlank()) { "Derived cadence must name its source rule" } }
    }
}

data class ScheduleRule(
    val ruleId: String,
    val workoutId: String,
    val cadence: ScheduleCadence,
    val anchorDate: LocalDate
) {
    init {
        require(ruleId.isNotBlank()) { "a schedule rule needs an identity" }
        require(workoutId.isNotBlank()) { "a schedule rule needs a workout identity" }
    }

    fun matches(date: LocalDate): Boolean = when (cadence) {
        ScheduleCadence.Daily -> true
        is ScheduleCadence.EveryNDays -> {
            val distance = ChronoUnit.DAYS.between(anchorDate, date)
            distance >= 0 && distance % cadence.days == 0L
        }
        is ScheduleCadence.SessionsPerWeek -> date.dayOfWeek in flexibleSpread(cadence.sessionsPerWeek)
        is ScheduleCadence.FixedWeekdays -> date.dayOfWeek in cadence.weekdays
        is ScheduleCadence.DerivedExcluding -> error("derived rules require a source date set")
    }

    companion object {
        fun daily(ruleId: String, workoutId: String, anchorDate: LocalDate = LocalDate.MIN) =
            ScheduleRule(ruleId, workoutId, ScheduleCadence.Daily, anchorDate)

        fun everyNDays(ruleId: String, workoutId: String, anchorDate: LocalDate, days: Int) =
            ScheduleRule(ruleId, workoutId, ScheduleCadence.EveryNDays(days), anchorDate)

        fun sessionsPerWeek(ruleId: String, workoutId: String, sessionsPerWeek: Int) =
            ScheduleRule(
                ruleId,
                workoutId,
                ScheduleCadence.SessionsPerWeek(sessionsPerWeek),
                LocalDate.MIN
            )

        fun fixedWeekdays(ruleId: String, workoutId: String, weekdays: Set<DayOfWeek>) =
            ScheduleRule(ruleId, workoutId, ScheduleCadence.FixedWeekdays(weekdays), LocalDate.MIN)

        fun derivedExcluding(ruleId: String, workoutId: String, sourceRuleId: String) =
            ScheduleRule(
                ruleId,
                workoutId,
                ScheduleCadence.DerivedExcluding(sourceRuleId),
                LocalDate.MIN
            )
    }
}

enum class OccurrenceComposition { SEPARATE, COMBINED }

data class OccurrenceComponent(val ruleId: String, val workoutId: String)

data class PlannedOccurrence(
    val occurrenceKey: String,
    val plannedFor: LocalDate,
    val components: List<OccurrenceComponent>
) {
    init { require(components.isNotEmpty()) { "an occurrence must contain work" } }
}

object OccurrenceComposer {
    fun compose(
        date: LocalDate,
        rules: List<ScheduleRule>,
        composition: OccurrenceComposition
    ): List<PlannedOccurrence> {
        val matching = rules.filter { it.matches(date) }
        return when (composition) {
            OccurrenceComposition.SEPARATE -> matching.map { rule ->
                PlannedOccurrence(
                    occurrenceKey = "${rule.ruleId}:$date",
                    plannedFor = date,
                    components = listOf(OccurrenceComponent(rule.ruleId, rule.workoutId))
                )
            }
            OccurrenceComposition.COMBINED -> if (matching.isEmpty()) emptyList() else listOf(
                PlannedOccurrence(
                    occurrenceKey = "combined:$date",
                    plannedFor = date,
                    components = matching.map { OccurrenceComponent(it.ruleId, it.workoutId) }
                )
            )
        }
    }
}

/** Resolves a derived rule from explicit source dates; it does not invent a second cadence. */
object DerivedScheduleResolver {
    fun datesThrough(
        rule: ScheduleRule,
        from: LocalDate,
        through: LocalDate,
        sourceDates: Set<LocalDate>
    ): List<LocalDate> {
        val sourceRuleId = (rule.cadence as ScheduleCadence.DerivedExcluding).sourceRuleId
        require(sourceRuleId.isNotBlank()) { "a derived rule must name its source" }
        return (0L..ChronoUnit.DAYS.between(from, through)).map { from.plusDays(it) }
            .filter { it !in sourceDates }
    }
}

enum class PauseScope { PROGRAM }

data class ProgramPauseWindow(
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val scope: PauseScope = PauseScope.PROGRAM
) {
    init { require(!lastDate.isBefore(firstDate)) { "pause cannot end before it starts" } }
    fun covers(date: LocalDate): Boolean = !date.isBefore(firstDate) && !date.isAfter(lastDate)
}

data class CatchUpExecution(
    val occurrenceKey: String,
    val plannedDate: LocalDate,
    val actualDate: LocalDate
) {
    init { require(!actualDate.isBefore(plannedDate)) { "catch-up cannot precede the plan" } }
}

data class CatchUpPolicy(val maxAgeDays: Int?) {
    init { require(maxAgeDays == null || maxAgeDays >= 0) { "catch-up age cannot be negative" } }

    companion object {
        fun isEligible(execution: CatchUpExecution, asOf: LocalDate, policy: CatchUpPolicy): Boolean {
            if (asOf.isBefore(execution.plannedDate)) return false
            val age = ChronoUnit.DAYS.between(execution.plannedDate, asOf)
            return policy.maxAgeDays == null || age <= policy.maxAgeDays
        }
    }
}

data class ExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val actuals: List<ActualResult>
) {
    init {
        require(execution != OccurrenceExecution.PLANNED || actuals.isEmpty()) {
            "an unstarted occurrence has no actual result"
        }
    }
}

data class ScheduleReconciliation(
    val preserved: List<ExistingOccurrence>,
    val future: List<PlannedOccurrence>
)

object ScheduleEditReconciler {
    fun reconcile(
        existing: List<ExistingOccurrence>,
        replacement: PlannedOccurrence
    ): ScheduleReconciliation {
        val preserved = existing.filter {
            it.execution == OccurrenceExecution.STARTED || it.execution == OccurrenceExecution.COMPLETED
        }
        return ScheduleReconciliation(preserved, listOf(replacement))
    }
}

sealed interface LegacySchedule {
    data class FixedWeekdays(val weekdays: Set<DayOfWeek>) : LegacySchedule
    data class FlexiblePerWeek(val sessionsPerWeek: Int) : LegacySchedule
}

data class LegacyScheduleMapping(val rule: ScheduleRule) {
    val cadence: ScheduleCadence get() = rule.cadence
}

object LegacyScheduleMapper {
    fun map(schedule: LegacySchedule): LegacyScheduleMapping = when (schedule) {
        is LegacySchedule.FixedWeekdays -> LegacyScheduleMapping(
            ScheduleRule.fixedWeekdays("legacy", "legacy-workout", schedule.weekdays)
        )
        // This is a legacy compatibility mapping, not an EveryNDays conversion.
        is LegacySchedule.FlexiblePerWeek -> LegacyScheduleMapping(
            ScheduleRule.sessionsPerWeek("legacy", "legacy-workout", schedule.sessionsPerWeek)
        )
    }
}
