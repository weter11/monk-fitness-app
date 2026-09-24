package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.program.ActualResult
import com.monkfitness.app.domain.program.CompletionAssessment
import com.monkfitness.app.domain.program.CompletionCalculator
import com.monkfitness.app.domain.program.ExecutionEvidence
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PerformedWork
import com.monkfitness.app.domain.program.PlannedWork
import com.monkfitness.app.domain.program.ScheduleEditReconciler
import com.monkfitness.app.domain.program.WorkRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Stage 1 executable contract matrix. Room and the current scheduler are deliberately absent. */
class SemanticContractsTest {

    @Test
    fun cadenceTypesRemainSemanticallyDistinct() {
        val everyTwoDays = ScheduleRule.everyNDays("strength", "Strength", START, 2)
        val threePerWeek = ScheduleRule.sessionsPerWeek("mobility", "Mobility", 3)
        val fixed = ScheduleRule.fixedWeekdays("posture", "Posture", setOf(DayOfWeek.MONDAY))
        val daily = ScheduleRule.daily("daily", "Daily")

        assertNotEquals(everyTwoDays.cadence, threePerWeek.cadence)
        assertNotEquals(everyTwoDays.cadence, ScheduleCadence.Daily)
        assertNotEquals(everyTwoDays.cadence, fixed.cadence)
        assertTrue(everyTwoDays.matches(LocalDate.parse("2026-10-03")))
        assertFalse(threePerWeek.matches(LocalDate.parse("2026-10-03")))
        assertTrue(threePerWeek.matches(LocalDate.parse("2026-10-05")))
        assertTrue(fixed.matches(LocalDate.parse("2026-10-05")))
        assertTrue(daily.matches(LocalDate.parse("2026-10-05")))
    }

    @Test
    fun independentRulesCanCoexistAndTheSameRulesCanBeComposedSeparately() {
        val rules = listOf(
            ScheduleRule.everyNDays("strength", "Strength", START, 2),
            ScheduleRule.daily("mobility", "Mobility"),
            ScheduleRule.daily("posture", "Posture")
        )
        val date = LocalDate.parse("2026-10-01")

        val separate = OccurrenceComposer.compose(date, rules, OccurrenceComposition.SEPARATE)
        val combined = OccurrenceComposer.compose(date, rules, OccurrenceComposition.COMBINED)

        assertEquals(3, separate.size)
        assertTrue(separate.all { it.components.size == 1 })
        assertEquals(1, combined.size)
        assertEquals(3, combined.single().components.size)
        assertEquals(listOf("strength", "mobility", "posture"), combined.single().components.map { it.ruleId })
    }

    @Test
    fun derivedScheduleIsComputedFromItsSourceRule() {
        val strength = ScheduleRule.everyNDays("strength", "Strength", START, 3)
        val sourceDates = (0..6).map { START.plusDays(it.toLong()) }
            .filter { strength.matches(it) }
        val mobility = ScheduleRule.derivedExcluding("mobility", "Mobility", "strength")

        val dates = DerivedScheduleResolver.datesThrough(
            mobility,
            START,
            START.plusDays(6),
            sourceDates.toSet()
        )

        assertEquals(listOf(START.plusDays(1), START.plusDays(2), START.plusDays(4), START.plusDays(5)), dates)
    }

    @Test
    fun anUnfinishedRequiredStepIsIncompleteNotCancelledOrCompleted() {
        val assessment = CompletionCalculator.assess(
            planned = listOf(
                PlannedWork("squat", WorkRequirement.REQUIRED, 10),
                PlannedWork("mobility", WorkRequirement.OPTIONAL, 5)
            ),
            actuals = emptyList()
        )

        assertEquals(CompletionAssessment.INCOMPLETE, assessment.state)
        assertEquals(listOf("squat"), assessment.incompleteRequired)
        assertEquals(listOf("mobility"), assessment.incompleteOptional)
    }

    @Test
    fun unfinishedOptionalWorkNeverBecomesRequired() {
        val assessment = CompletionCalculator.assess(
            planned = listOf(
                PlannedWork("squat", WorkRequirement.REQUIRED, 10),
                PlannedWork("mobility", WorkRequirement.OPTIONAL, 5)
            ),
            actuals = listOf(ActualResult.fromPerformed("squat", PerformedWork.reps(10)))
        )

        assertEquals(CompletionAssessment.COMPLETED, assessment.state)
        assertTrue(assessment.incompleteRequired.isEmpty())
        assertEquals(listOf("mobility"), assessment.incompleteOptional)
    }

    @Test
    fun targetAndActualAreDifferentValuesAndActualNeedsPerformedData() {
        val target = 10
        val actual = ActualResult.fromPerformed("squat", PerformedWork.reps(8))

        assertNotEquals(target, actual.work)
        assertEquals(8, actual.work.value)
        assertTrue(actual.isExplicitlyPerformed)
    }

    @Test
    fun partialExecutionIsEvidenceButNeverAnAutomaticRegressDecision() {
        val evidence = ExecutionEvidence.partial("squat", PerformedWork.reps(6))

        assertTrue(evidence.isValidEvidence)
        assertFalse(evidence.impliesAutomaticRegress)
    }

    @Test
    fun catchUpKeepsPlannedAndActualDatesSeparate() {
        val catchUp = CatchUpExecution(
            occurrenceKey = "strength-2026-10-01",
            plannedDate = LocalDate.parse("2026-10-01"),
            actualDate = LocalDate.parse("2026-10-04")
        )
        val policy = CatchUpPolicy(maxAgeDays = 7)

        assertTrue(CatchUpPolicy.isEligible(catchUp, START.plusDays(4), policy))
        assertEquals(LocalDate.parse("2026-10-01"), catchUp.plannedDate)
        assertEquals(LocalDate.parse("2026-10-04"), catchUp.actualDate)
    }

    @Test
    fun pauseIsProgramLevelAndCoversTheWholeDate() {
        val pause = ProgramPauseWindow(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-04"))

        assertEquals(PauseScope.PROGRAM, pause.scope)
        assertTrue(pause.covers(LocalDate.parse("2026-10-02")))
        assertTrue(pause.covers(LocalDate.parse("2026-10-04")))
        assertFalse(pause.covers(LocalDate.parse("2026-10-05")))
    }

    @Test
    fun futureScheduleEditPreservesStartedAndCompletedFacts() {
        val old = PlannedOccurrence("o1", LocalDate.parse("2026-10-01"), listOf(OccurrenceComponent("strength", "Strength")))
        val replacement = old.copy(plannedFor = LocalDate.parse("2026-10-02"))
        val started = ExistingOccurrence(old, OccurrenceExecution.STARTED, emptyList())
        val completed = ExistingOccurrence(
            old.copy(occurrenceKey = "o2"),
            OccurrenceExecution.COMPLETED,
            listOf(ActualResult.fromPerformed("squat", PerformedWork.reps(10)))
        )

        val result = ScheduleEditReconciler.reconcile(listOf(started, completed), replacement)

        assertEquals(listOf(started, completed), result.preserved)
        assertEquals(listOf(replacement), result.future)
        assertEquals(LocalDate.parse("2026-10-01"), started.occurrence.plannedFor)
    }

    @Test
    fun legacyFlexiblePerWeekIsNeverSilentlyRewrittenAsEveryNDays() {
        val mapped = LegacyScheduleMapper.map(LegacySchedule.FlexiblePerWeek(3))

        assertTrue(mapped.cadence is ScheduleCadence.SessionsPerWeek)
        assertFalse(mapped.cadence is ScheduleCadence.EveryNDays)
        assertEquals(3, (mapped.cadence as ScheduleCadence.SessionsPerWeek).sessionsPerWeek)
    }

    private companion object {
        val START: LocalDate = LocalDate.parse("2026-10-01")
    }
}
