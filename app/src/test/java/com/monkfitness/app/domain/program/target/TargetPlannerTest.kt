package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.ActualResult
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PerformedWork
import com.monkfitness.app.domain.program.PlannedOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TargetPlannerTest {
    @Test
    fun emptyPipelineProducesEmptyPlan() {
        assertEquals(
            TargetPlan(emptyList(), TargetOccurrenceReconciliation(emptyList(), emptyList(), emptyList())),
            TargetPlanner.plan(emptyList(), WINDOW)
        )
    }

    @Test
    fun oneScheduleProducesOnePlannedOccurrence() {
        val result = TargetPlanner.plan(listOf(schedule("strength", "Strength")), WINDOW)

        assertEquals(1, result.planned.size)
        assertEquals(listOf("strength"), result.planned.single().components.map { it.ruleId })
        assertEquals(result.planned, result.reconciliation.added)
    }

    @Test
    fun multipleSchedulesReachCompositionAndReconciliation() {
        val result = TargetPlanner.plan(
            listOf(schedule("mobility", "Mobility"), schedule("strength", "Strength")),
            WINDOW,
            selection("mobility", "strength")
        )

        assertEquals(1, result.planned.size)
        assertEquals(listOf("mobility", "strength"), result.planned.single().components.map { it.ruleId })
        assertEquals(result.planned, result.reconciliation.added)
    }

    @Test
    fun compositionSelectionReachesFinalResult() {
        val result = TargetPlanner.plan(
            listOf(schedule("mobility", "Mobility"), schedule("posture", "Posture")),
            WINDOW,
            selection("mobility", "posture")
        )

        assertEquals(1, result.planned.size)
        assertEquals(listOf("mobility", "posture"), result.planned.single().components.map { it.ruleId })
    }

    @Test
    fun noSelectionKeepsOccurrencesSeparate() {
        val result = TargetPlanner.plan(
            listOf(schedule("mobility", "Mobility"), schedule("posture", "Posture")),
            WINDOW
        )

        assertEquals(2, result.planned.size)
        assertTrue(result.planned.all { it.components.size == 1 })
    }

    @Test
    fun existingPlannedIsRetainedAndRemovedPlannedIsSuperseded() {
        val retained = planned("strength:${DAY_ONE}", DAY_ONE, listOf("strength"))
            .copy(components = listOf(OccurrenceComponent("strength", "Strength")))
        val removed = planned("old", DAY_ONE, listOf("old"))
        val result = TargetPlanner.plan(
            listOf(schedule("strength", "Strength")),
            WINDOW,
            existing = listOf(existing(retained), existing(removed))
        )

        assertTrue(result.reconciliation.added.isEmpty())
        assertEquals(listOf(removed), result.reconciliation.superseded.map { it.occurrence })
    }

    @Test
    fun newOccurrenceIsAddedWhenNoExistingValueHasItsIdentity() {
        val result = TargetPlanner.plan(
            listOf(schedule("strength", "Strength")),
            WINDOW,
            existing = listOf(existing(planned("old", DAY_ONE, listOf("old"))))
        )

        assertEquals(result.planned, result.reconciliation.added)
    }

    @Test
    fun historicalExecutionFactsRemainPreserved() {
        val started = planned("started", DAY_ONE, listOf("started"))
        val completed = planned("completed", DAY_ONE, listOf("completed"))
        val cancelled = planned("cancelled", DAY_ONE, listOf("cancelled"))
        val existing = listOf(
            existing(started, OccurrenceExecution.STARTED, actuals()),
            existing(completed, OccurrenceExecution.COMPLETED, actuals()),
            existing(cancelled, OccurrenceExecution.CANCELLED, actuals())
        )

        val result = TargetPlanner.plan(emptyList(), WINDOW, existing = existing)

        assertEquals(
            listOf(existing[2], existing[1], existing[0]),
            result.reconciliation.preserved
        )
        assertTrue(result.reconciliation.superseded.isEmpty())
        assertEquals(actuals(), result.reconciliation.preserved.first().actuals)
    }

    @Test
    fun derivedExclusionReachesFinalResult() {
        val source = schedule("strength", "Strength")
        val derived = TargetSchedule.derivedExcluding("mobility", "Mobility", DAY_ONE, "strength")
        val derivedWindow = TargetScheduleWindow(DAY_ONE, DAY_ONE.plusDays(1))
        val result = TargetPlanner.plan(
            listOf(derived),
            derivedWindow,
            sources = mapOf(
                "strength" to ResolvedScheduleSource(
                    "strength",
                    TargetScheduleResolver.resolve(source, TargetScheduleWindow(DAY_ONE, DAY_ONE))
                )
            )
        )

        assertEquals(listOf(DAY_ONE.plusDays(1)), result.planned.map { it.plannedFor })
        assertTrue(result.planned.all { it.components.single().ruleId == "mobility" })
    }

    @Test
    fun reorderedInputsProduceEqualOutput() {
        val schedules = listOf(schedule("mobility", "Mobility"), schedule("strength", "Strength"))
        val first = TargetPlanner.plan(schedules, WINDOW)
        val second = TargetPlanner.plan(schedules.reversed(), WINDOW)

        assertEquals(first, second)
    }

    @Test
    fun reorderedCompositionSetsProduceEqualOutput() {
        val schedules = listOf(schedule("mobility", "Mobility"), schedule("strength", "Strength"))
        val first = TargetPlanner.plan(schedules, WINDOW, selection("mobility", "strength"))
        val second = TargetPlanner.plan(schedules, WINDOW, CompositionSelection(linkedSetOf("strength", "mobility")))

        assertEquals(first, second)
    }

    @Test
    fun plannerHasExactParityWithDirectStageTwoThreeFourExecution() {
        val schedules = listOf(schedule("mobility", "Mobility"), schedule("strength", "Strength"))
        val selection = selection("mobility", "strength")
        val existing = listOf(existing(planned("old", DAY_ONE, listOf("old"))))
        val expectedResolved = TargetScheduleResolver.resolve(schedules, WINDOW)
        val expectedPlanned = TargetOccurrenceComposer.compose(expectedResolved, selection)
        val expectedReconciliation = TargetOccurrenceReconciler.reconcile(existing, expectedPlanned)
        val expected = TargetPlan(expectedPlanned, expectedReconciliation)

        assertEquals(expected, TargetPlanner.plan(schedules, WINDOW, selection, existing))
    }

    @Test
    fun repeatedExecutionReturnsEqualityIdenticalResults() {
        val schedules = listOf(schedule("mobility", "Mobility"), schedule("strength", "Strength"))
        val selection = selection("mobility", "strength")

        assertEquals(
            TargetPlanner.plan(schedules, WINDOW, selection),
            TargetPlanner.plan(schedules, WINDOW, selection)
        )
    }

    @Test
    fun callerInputsAreNotMutated() {
        val schedules = mutableListOf(schedule("mobility", "Mobility"), schedule("strength", "Strength"))
        val existing = mutableListOf(existing(planned("old", DAY_ONE, listOf("old"))))
        val schedulesBefore = schedules.toList()
        val existingBefore = existing.toList()

        TargetPlanner.plan(schedules, WINDOW, selection("mobility", "strength"), existing)

        assertEquals(schedulesBefore, schedules)
        assertEquals(existingBefore, existing)
    }

    private fun schedule(ruleId: String, workoutId: String) =
        TargetSchedule.daily(ruleId, workoutId, DAY_ONE)

    private fun planned(key: String, date: LocalDate, components: List<String>) = PlannedOccurrence(
        occurrenceKey = key,
        plannedFor = date,
        components = components.map { OccurrenceComponent(it, "Workout $it") }
    )

    private fun existing(
        occurrence: PlannedOccurrence,
        execution: OccurrenceExecution = OccurrenceExecution.PLANNED,
        actuals: List<ActualResult> = emptyList()
    ) = ExistingOccurrence(occurrence, execution, actuals)

    private fun actuals() = listOf(ActualResult.fromPerformed("set-1", PerformedWork.reps(12)))

    private fun selection(vararg ids: String) = CompositionSelection(ids.toSet())

    private companion object {
        val DAY_ONE: LocalDate = LocalDate.parse("2026-10-05")
        val WINDOW = TargetScheduleWindow(DAY_ONE, DAY_ONE)
    }
}
