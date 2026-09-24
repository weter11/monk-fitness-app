package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.ScheduleCadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class TargetScheduleResolverTest {
    @Test
    fun dailyResolvesEveryEligibleDateInTheInclusiveWindow() {
        val schedule = TargetSchedule(
            ruleId = "daily",
            workoutId = "Daily workout",
            cadence = ScheduleCadence.Daily,
            anchorDate = LocalDate.parse("2026-10-03")
        )
        val window = TargetScheduleWindow(
            from = LocalDate.parse("2026-10-01"),
            through = LocalDate.parse("2026-10-05")
        )

        assertEquals(
            listOf(
                LocalDate.parse("2026-10-03"),
                LocalDate.parse("2026-10-04"),
                LocalDate.parse("2026-10-05")
            ),
            TargetScheduleResolver.resolve(schedule, window).map { it.plannedDate }
        )
    }

    @Test
    fun everyNDaysResolvesAnchorRelativeArithmeticProgression() {
        val schedule = TargetSchedule.everyNDays(
            ruleId = "strength",
            workoutId = "Strength",
            anchorDate = LocalDate.parse("2026-10-01"),
            days = 2
        )
        val window = TargetScheduleWindow(
            from = LocalDate.parse("2026-09-30"),
            through = LocalDate.parse("2026-10-08")
        )

        assertEquals(
            listOf(
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-03"),
                LocalDate.parse("2026-10-05"),
                LocalDate.parse("2026-10-07")
            ),
            TargetScheduleResolver.resolve(schedule, window).map { it.plannedDate }
        )
    }

    @Test
    fun everyNDaysSupportsDailyAndLargerIntervalsWithoutOffPatternDates() {
        val everyNDays = TargetSchedule.everyNDays(
            ruleId = "strength",
            workoutId = "Strength",
            anchorDate = LocalDate.parse("2026-10-01"),
            days = 2
        )
        val daily = TargetSchedule.everyNDays("daily-step", "Step", LocalDate.parse("2026-10-01"), 1)
        val weekly = TargetSchedule.everyNDays("weekly", "Weekly", LocalDate.parse("2026-10-01"), 7)
        val window = TargetScheduleWindow(
            LocalDate.parse("2026-10-01"),
            LocalDate.parse("2026-10-21")
        )

        assertEquals(
            listOf(
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-08"),
                LocalDate.parse("2026-10-15")
            ),
            TargetScheduleResolver.resolve(weekly, window).map { it.plannedDate }
        )
        assertEquals(window.dates(), TargetScheduleResolver.resolve(daily, window).map { it.plannedDate })
        assertEquals(
            listOf(
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-03"),
                LocalDate.parse("2026-10-05"),
                LocalDate.parse("2026-10-07"),
                LocalDate.parse("2026-10-09"),
                LocalDate.parse("2026-10-11"),
                LocalDate.parse("2026-10-13"),
                LocalDate.parse("2026-10-15"),
                LocalDate.parse("2026-10-17"),
                LocalDate.parse("2026-10-19"),
                LocalDate.parse("2026-10-21")
            ),
            TargetScheduleResolver.resolve(everyNDays, window).map { it.plannedDate }
        )
    }

    @Test
    fun derivedExcludingUsesTheExplicitSourceDatesAndPreservesTheDependency() {
        val source = TargetSchedule.everyNDays(
            ruleId = "strength",
            workoutId = "Strength",
            anchorDate = LocalDate.parse("2026-10-01"),
            days = 2
        )
        val derived = TargetSchedule.derivedExcluding(
            ruleId = "mobility",
            workoutId = "Mobility",
            anchorDate = LocalDate.parse("2026-10-01"),
            sourceRuleId = "strength"
        )
        val window = TargetScheduleWindow(
            LocalDate.parse("2026-10-01"),
            LocalDate.parse("2026-10-05")
        )

        val result = TargetScheduleResolver.resolve(
            schedule = derived,
            window = window,
            source = ResolvedScheduleSource(
                sourceRuleId = "strength",
                occurrences = TargetScheduleResolver.resolve(source, window)
            )
        )

        assertEquals(
            listOf(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-04")),
            result.map { it.plannedDate }
        )
        assertTrue(result.all { it.cadence == ScheduleCadence.DerivedExcluding("strength") })
    }

    @Test
    fun modelAndWindowInvariantsRejectInvalidValues() {
        assertIllegalArgument {
            TargetSchedule.everyNDays("rule", "Workout", START, 0)
        }
        assertIllegalArgument {
            TargetSchedule.everyNDays("rule", "Workout", START, -1)
        }
        assertIllegalArgument {
            TargetSchedule.sessionsPerWeek("rule", "Workout", START, 0)
        }
        assertIllegalArgument {
            TargetSchedule.sessionsPerWeek("rule", "Workout", START, 8)
        }
        assertIllegalArgument {
            TargetSchedule.fixedWeekdays("rule", "Workout", START, emptySet())
        }
        assertIllegalArgument {
            TargetScheduleWindow(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-01"))
        }
        assertIllegalArgument { TargetSchedule(" ", "Workout", ScheduleCadence.Daily, START) }
        assertIllegalArgument { TargetSchedule("rule", " ", ScheduleCadence.Daily, START) }
        assertIllegalArgument {
            TargetSchedule.derivedExcluding("rule", "Workout", START, " ")
        }
    }

    @Test
    fun windowIsInclusiveAndSupportsAOneDayWindow() {
        val first = TargetScheduleWindow(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"))
        val full = TargetScheduleWindow(
            LocalDate.parse("2026-10-01"),
            LocalDate.parse("2026-10-03")
        )
        val daily = TargetSchedule.daily("daily", "Daily", LocalDate.parse("2026-10-01"))

        assertEquals(listOf(LocalDate.parse("2026-10-01")), first.dates())
        assertEquals(
            listOf(LocalDate.parse("2026-10-01")),
            TargetScheduleResolver.resolve(
                TargetSchedule.daily("daily-one", "Daily", LocalDate.parse("2026-10-01")),
                TargetScheduleWindow(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"))
            ).map { it.plannedDate }
        )
        assertEquals(3, TargetScheduleResolver.resolve(daily, full).size)
    }

    @Test
    fun multiRuleResolutionOrdersByDateThenRuleIdentityRegardlessOfInputOrder() {
        val monday = TargetSchedule.daily("z-rule", "Z", START)
        val tuesday = TargetSchedule.daily("a-rule", "A", START)
        val window = TargetScheduleWindow(START, START)
        val forward = TargetScheduleResolver.resolve(listOf(monday, tuesday), window)
        val reverse = TargetScheduleResolver.resolve(listOf(tuesday, monday), window)

        assertEquals(listOf("a-rule", "z-rule"), forward.map { it.ruleId })
        assertEquals(forward, reverse)
    }

    @Test
    fun sessionsPerWeekPinsEveryFrequencyAndTheMondayFirstSpread() {
        val expected = mapOf(
            1 to listOf(DayOfWeek.MONDAY),
            2 to listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            3 to listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            4 to listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY),
            5 to listOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY,
                DayOfWeek.SATURDAY
            ),
            6 to listOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
                DayOfWeek.SUNDAY
            ),
            7 to DayOfWeek.entries.toList()
        )
        val window = TargetScheduleWindow(
            from = LocalDate.parse("2026-10-05"),
            through = LocalDate.parse("2026-10-11")
        )

        expected.forEach { (frequency, weekdays) ->
            val schedule = TargetSchedule.sessionsPerWeek(
                ruleId = "weekly-$frequency",
                workoutId = "Weekly $frequency",
                anchorDate = LocalDate.parse("2026-10-05"),
                sessionsPerWeek = frequency
            )
            val expectedDates = weekdays.map { window.from.with(it) }
            val first = TargetScheduleResolver.resolve(schedule, window)
            val second = TargetScheduleResolver.resolve(schedule, window)

            assertEquals("frequency=$frequency", expectedDates, first.map { it.plannedDate })
            assertEquals("frequency=$frequency", first, second)
        }
    }

    @Test
    fun sessionsPerWeekRemainsWeeklyNotEveryNDaysAndIgnoresUnrelatedHistory() {
        val weekly = TargetSchedule.sessionsPerWeek("weekly", "Weekly", START, 3)
        val interval = TargetSchedule.everyNDays("interval", "Interval", START, 2)
        val window = TargetScheduleWindow(START, LocalDate.parse("2026-10-10"))
        val unrelatedHistory = mutableListOf("2026-10-01", "2026-10-02")

        val before = TargetScheduleResolver.resolve(weekly, window)
        val unrelatedSource = ResolvedScheduleSource(
            sourceRuleId = "unrelated",
            occurrences = listOf(
                ResolvedScheduleOccurrence(
                    ruleId = "unrelated",
                    workoutId = "Old slot",
                    plannedDate = START,
                    cadence = ScheduleCadence.Daily
                )
            )
        )
        unrelatedHistory += listOf("2026-10-05", "2026-10-06", "2026-10-07")
        val after = TargetScheduleResolver.resolve(weekly, window, unrelatedSource)

        assertEquals(
            listOf(START, LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-09")),
            before.map { it.plannedDate }
        )
        assertEquals(before, after)
        assertFalse(before == TargetScheduleResolver.resolve(interval, window))
    }

    @Test
    fun fixedWeekdaysUsesLiteralMembershipAndIgnoresInputSetOrder() {
        val first = TargetSchedule.fixedWeekdays(
            "fixed-a",
            "Fixed",
            anchorDate = LocalDate.parse("2026-10-02"),
            weekdays = linkedSetOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        )
        val second = TargetSchedule.fixedWeekdays(
            "fixed-a",
            "Fixed",
            anchorDate = LocalDate.parse("2026-10-02"),
            weekdays = linkedSetOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        )
        val window = TargetScheduleWindow(
            from = LocalDate.parse("2026-10-01"),
            through = LocalDate.parse("2026-10-07")
        )

        val expected = listOf(
            LocalDate.parse("2026-10-02"),
            LocalDate.parse("2026-10-05"),
            LocalDate.parse("2026-10-07")
        )
        assertEquals(expected, TargetScheduleResolver.resolve(first, window).map { it.plannedDate })
        assertEquals(TargetScheduleResolver.resolve(first, window), TargetScheduleResolver.resolve(second, window))
    }

    @Test
    fun fixedWeekdaysSupportsOneSeveralAndAllWeekdaysChronologically() {
        val window = TargetScheduleWindow(START, LocalDate.parse("2026-10-07"))
        val monday = TargetSchedule.fixedWeekdays("one", "One", START, setOf(DayOfWeek.MONDAY))
        val three = TargetSchedule.fixedWeekdays(
            "three",
            "Three",
            START,
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )
        val all = TargetSchedule.fixedWeekdays("all", "All", START, DayOfWeek.entries.toSet())

        assertEquals(listOf(START), TargetScheduleResolver.resolve(monday, window).map { it.plannedDate })
        assertEquals(
            listOf(START, LocalDate.parse("2026-10-07")),
            TargetScheduleResolver.resolve(three, window).map { it.plannedDate }
        )
        assertEquals(window.dates(), TargetScheduleResolver.resolve(all, window).map { it.plannedDate })
    }

    @Test
    fun windowIteratesBothBoundariesDeterministically() {
        val window = TargetScheduleWindow(
            from = LocalDate.parse("2026-10-01"),
            through = LocalDate.parse("2026-10-03")
        )

        assertEquals(
            listOf(
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-02"),
                LocalDate.parse("2026-10-03")
            ),
            window.dates()
        )
        assertEquals(window.dates(), window.dates())
        assertTrue(window.contains(LocalDate.parse("2026-10-01")))
        assertTrue(window.contains(LocalDate.parse("2026-10-03")))
    }

    @Test
    fun derivedRuleWithoutAValidSourceRefusesToResolve() {
        val derived = TargetSchedule.derivedExcluding("mobility", "Mobility", START, "missing")
        val wrongSource = TargetSchedule.daily("strength", "Strength", START)

        assertIllegalArgument {
            TargetScheduleResolver.resolve(derived, TargetScheduleWindow(START, START))
        }
        assertIllegalArgument {
            TargetScheduleResolver.resolve(
                derived,
                TargetScheduleWindow(START, START),
                ResolvedScheduleSource(
                    sourceRuleId = "other",
                    occurrences = TargetScheduleResolver.resolve(wrongSource, TargetScheduleWindow(START, START))
                )
            )
        }
    }

    @Test
    fun resolverDoesNotMutateInputAndCompleteResultsAreEqualAcrossCalls() {
        val weekdays = linkedSetOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)
        val schedule = TargetSchedule.fixedWeekdays("fixed", "Fixed", START, weekdays)
        val window = TargetScheduleWindow(START, LocalDate.parse("2026-10-11"))
        val beforeSchedule = schedule
        val beforeWeekdays = weekdays.toSet()
        val beforeWindow = window

        val first = TargetScheduleResolver.resolve(schedule, window)
        val second = TargetScheduleResolver.resolve(schedule, window)

        assertEquals(first, second)
        assertEquals(beforeSchedule, schedule)
        assertEquals(beforeWeekdays, weekdays)
        assertEquals(beforeWindow, window)
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    private companion object {
        val START: LocalDate = LocalDate.parse("2026-10-05")
    }
}
