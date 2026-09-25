package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.ActualResult
import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PerformedWork
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.ProgramPauseWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TargetSchedulePolicyTest {
    @Test
    fun emptyInputProducesEmptyDecision() {
        assertEquals(
            TargetScheduleDecision(TargetPlan(emptyList(), emptyReconciliation()), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            decide(emptyPlan(), emptyList(), emptyList())
        )
    }

    @Test
    fun startedCompletedAndCancelledHistoricalFactsArePreservedExactly() {
        val existing = listOf(
            existing(planned("started", AS_OF), OccurrenceExecution.STARTED, actuals()),
            existing(planned("completed", AS_OF), OccurrenceExecution.COMPLETED, actuals()),
            existing(planned("cancelled", AS_OF), OccurrenceExecution.CANCELLED, actuals())
        )

        val result = decide(plan(emptyList(), existing), existing, listOf(PAUSE))

        assertEquals(existing.sortedBy { it.occurrence.occurrenceKey }, result.preserved)
        result.preserved.forEach { assertSame(it.actuals, result.preserved.first { found -> found.occurrence == it.occurrence }.actuals) }
        assertTrue(result.retained.isEmpty())
        assertTrue(result.superseded.isEmpty())
        assertTrue(result.missed.isEmpty())
    }

    @Test
    fun futureSameIdentityIsRetained() {
        val occurrence = planned("future", AS_OF.plusDays(1))
        val current = existing(occurrence)
        val result = decide(plan(listOf(occurrence), listOf(current)), listOf(current), emptyList())

        assertEquals(listOf(current), result.retained)
        assertTrue(result.created.isEmpty())
    }

    @Test
    fun todaySameIdentityIsRetained() {
        val occurrence = planned("today", AS_OF)
        val current = existing(occurrence)
        val result = decide(plan(listOf(occurrence), listOf(current)), listOf(current), emptyList())

        assertEquals(listOf(current), result.retained)
        assertTrue(result.missed.isEmpty())
    }

    @Test
    fun todayInsidePauseIsStillRetained() {
        val occurrence = planned("today-paused", AS_OF)
        val current = existing(occurrence)
        val result = decide(plan(listOf(occurrence), listOf(current)), listOf(current), listOf(PAUSE))

        assertEquals(listOf(current), result.retained)
        assertTrue(result.superseded.isEmpty())
    }

    @Test
    fun futureRemovedIdentityIsSupersededAsTargetRemoval() {
        val occurrence = planned("removed", AS_OF.plusDays(1))
        val result = decide(emptyPlan(), listOf(existing(occurrence)), emptyList())

        assertEquals(
            listOf(TargetSupersededOccurrence(existing(occurrence), TargetSupersessionReason.TARGET_NO_LONGER_PRESENTS_OCCURRENCE)),
            result.superseded
        )
        assertTrue(result.missed.isEmpty())
    }

    @Test
    fun pastUnpausedPlannedOccurrenceIsMissed() {
        val occurrence = planned("past", AS_OF.minusDays(1))
        val current = existing(occurrence)
        val result = decide(plan(listOf(occurrence), listOf(current)), listOf(current), emptyList())

        assertEquals(listOf(current), result.missed)
        assertTrue(result.superseded.isEmpty())
    }

    @Test
    fun pastPausedPlannedOccurrenceIsSupersededAsPassedWhilePaused() {
        val occurrence = planned("past-paused", AS_OF.minusDays(1))
        val current = existing(occurrence)
        val result = decide(plan(listOf(occurrence), listOf(current)), listOf(current), listOf(PAUSE))

        assertEquals(
            listOf(TargetSupersededOccurrence(current, TargetSupersessionReason.PASSED_WHILE_PAUSED)),
            result.superseded
        )
        assertTrue(result.missed.isEmpty())
    }

    @Test
    fun futurePlannedOccurrenceInsidePauseIsRetained() {
        val occurrence = planned("future-paused", AS_OF.plusDays(1))
        val current = existing(occurrence)
        val result = decide(plan(listOf(occurrence), listOf(current)), listOf(current), listOf(PAUSE))

        assertEquals(listOf(current), result.retained)
        assertTrue(result.superseded.isEmpty())
    }

    @Test
    fun addedFuturePausedOccurrenceIsNotCreated() {
        val occurrence = planned("new-paused", AS_OF.plusDays(1))
        val result = decide(plan(occurrence), emptyList(), listOf(PAUSE))

        assertTrue(result.created.isEmpty())
        assertEquals(listOf(occurrence), result.targetPlan.planned)
    }

    @Test
    fun addedFutureUnpausedOccurrenceIsCreated() {
        val occurrence = planned("new", AS_OF.plusDays(1))
        val result = decide(plan(occurrence), emptyList(), emptyList())

        assertEquals(listOf(occurrence), result.created)
    }

    @Test
    fun addedPastOccurrenceIsNotCreated() {
        val occurrence = planned("new-past", AS_OF.minusDays(1))
        val result = decide(plan(occurrence), emptyList(), emptyList())

        assertTrue(result.created.isEmpty())
        assertEquals(listOf(occurrence), result.targetPlan.planned)
    }

    @Test
    fun pastRemovedByRevisionRemainsMissedWhenUnpaused() {
        val old = existing(planned("old-past", AS_OF.minusDays(1)))
        val replacement = planned("replacement", AS_OF.plusDays(1))
        val targetPlan = TargetPlan(
            listOf(replacement),
            TargetOccurrenceReconciliation(emptyList(), listOf(old), listOf(replacement))
        )

        val result = TargetSchedulePolicy.decide(targetPlan, listOf(old), AS_OF, emptyList())

        assertEquals(listOf(old), result.missed)
        assertTrue(result.superseded.isEmpty())
    }

    @Test
    fun pastRemovedByRevisionBecomesPauseSupersessionWhenPaused() {
        val old = existing(planned("old-paused", AS_OF.minusDays(1)))
        val replacement = planned("replacement", AS_OF.plusDays(1))
        val targetPlan = TargetPlan(
            listOf(replacement),
            TargetOccurrenceReconciliation(emptyList(), listOf(old), listOf(replacement))
        )

        val result = TargetSchedulePolicy.decide(targetPlan, listOf(old), AS_OF, listOf(PAUSE))

        assertEquals(
            listOf(TargetSupersededOccurrence(old, TargetSupersessionReason.PASSED_WHILE_PAUSED)),
            result.superseded
        )
        assertTrue(result.missed.isEmpty())
    }

    @Test
    fun overlappingPausesStillMatchAnOccurrence() {
        val occurrence = planned("overlap", AS_OF.minusDays(1))
        val pauses = listOf(
            ProgramPauseWindow(AS_OF.minusDays(3), AS_OF.minusDays(2)),
            ProgramPauseWindow(AS_OF.minusDays(1), AS_OF)
        )

        val result = decide(plan(occurrence), listOf(existing(occurrence)), pauses)

        assertTrue(result.missed.isEmpty())
        assertEquals(
            listOf(TargetSupersededOccurrence(existing(occurrence), TargetSupersessionReason.PASSED_WHILE_PAUSED)),
            result.superseded
        )
    }

    @Test
    fun reorderedExistingInputProducesEqualDecision() {
        val first = existing(planned("a", AS_OF.plusDays(1)))
        val second = existing(planned("b", AS_OF.minusDays(1)))
        val replacement = listOf(first.occurrence, second.occurrence)

        assertEquals(
            decide(plan(replacement), listOf(first, second), emptyList()),
            decide(plan(replacement), listOf(second, first), emptyList())
        )
    }

    @Test
    fun everyBucketUsesCanonicalOrdering() {
        val futureB = existing(planned("b", AS_OF.plusDays(2)))
        val futureA = existing(planned("a", AS_OF.plusDays(1)))
        val pastB = existing(planned("d", AS_OF.minusDays(2)))
        val pastA = existing(planned("c", AS_OF.minusDays(1)))
        val targetPlan = plan(listOf(futureB.occurrence, futureA.occurrence, pastB.occurrence, pastA.occurrence))

        val result = decide(targetPlan, listOf(pastB, futureB, pastA, futureA), emptyList())

        assertEquals(listOf("d", "c"), result.missed.map { it.occurrence.occurrenceKey })
        assertEquals(listOf("a", "b"), result.retained.map { it.occurrence.occurrenceKey })
    }

    @Test
    fun inputCollectionsRemainUnchanged() {
        val existing = mutableListOf(existing(planned("existing", AS_OF)))
        val pauses = mutableListOf(PAUSE)
        val targetPlan = plan(listOf(planned("new", AS_OF.plusDays(1))))
        val existingBefore = existing.toList()
        val pausesBefore = pauses.toList()
        val planBefore = targetPlan

        decide(targetPlan, existing, pauses)

        assertEquals(existingBefore, existing)
        assertEquals(pausesBefore, pauses)
        assertEquals(planBefore, targetPlan)
    }

    @Test
    fun repeatedCallsReturnEqualityIdenticalDecisions() {
        val targetPlan = plan(listOf(planned("new", AS_OF.plusDays(1))))
        val existing = listOf(existing(planned("old", AS_OF.minusDays(1))))

        assertEquals(decide(targetPlan, existing, listOf(PAUSE)), decide(targetPlan, existing, listOf(PAUSE)))
    }

    @Test
    fun directSemanticMatrixMatchesTheDocumentedStates() {
        val future = planned("future", AS_OF.plusDays(1))
        val today = planned("today", AS_OF)
        val past = planned("past", AS_OF.minusDays(1))
        val pausedFuture = planned("paused-future", AS_OF.plusDays(1))
        val pausedPast = planned("paused-past", AS_OF.minusDays(1))

        assertEquals(listOf(existing(future)), decide(plan(future), listOf(existing(future)), emptyList()).retained)
        assertEquals(listOf(existing(today)), decide(plan(today), listOf(existing(today)), emptyList()).retained)
        assertEquals(listOf(existing(past)), decide(plan(past), listOf(existing(past)), emptyList()).missed)
        assertEquals(
            TargetSupersessionReason.PASSED_WHILE_PAUSED,
            decide(plan(pausedPast), listOf(existing(pausedPast)), listOf(PAUSE)).superseded.single().reason
        )
        assertEquals(listOf(existing(pausedFuture)), decide(plan(pausedFuture), listOf(existing(pausedFuture)), listOf(PAUSE)).retained)
        assertTrue(decide(plan(pausedFuture), emptyList(), listOf(PAUSE)).created.isEmpty())
    }

    @Test
    fun historicalActualResultsRemainExactlyPreserved() {
        val actuals = listOf(ActualResult.fromPerformed("set-1", PerformedWork.reps(12)))
        val historical = existing(planned("historical", AS_OF), OccurrenceExecution.COMPLETED, actuals)

        val result = decide(plan(emptyList(), listOf(historical)), listOf(historical), listOf(PAUSE))

        assertEquals(historical, result.preserved.single())
        assertSame(actuals, result.preserved.single().actuals)
    }

    @Test
    fun stageFourPastSupersessionIsNotLeakedUnchanged() {
        val past = existing(planned("past", AS_OF.minusDays(1)))
        val targetPlan = TargetPlan(
            emptyList(),
            TargetOccurrenceReconciliation(emptyList(), listOf(past), emptyList())
        )

        val result = decide(targetPlan, listOf(past), emptyList())

        assertEquals(listOf(past), result.missed)
        assertTrue(result.superseded.isEmpty())
    }

    private fun decide(
        targetPlan: TargetPlan,
        existing: List<ExistingOccurrence>,
        pauses: List<ProgramPauseWindow>
    ) = TargetSchedulePolicy.decide(targetPlan, existing, AS_OF, pauses)

    private fun plan(vararg occurrences: PlannedOccurrence) = plan(occurrences.toList())

    private fun plan(occurrences: List<PlannedOccurrence>, existing: List<ExistingOccurrence> = emptyList()): TargetPlan {
        val existingKeys = existing.map { it.occurrence.occurrenceKey }.toSet()
        val replacement = occurrences.sortedWith(compareBy<PlannedOccurrence> { it.plannedFor }.thenBy { it.occurrenceKey })
        return TargetPlan(
            replacement,
            TargetOccurrenceReconciliation(
                preserved = existing.filter { it.execution != OccurrenceExecution.PLANNED }
                    .sortedWith(compareBy<ExistingOccurrence> { it.occurrence.plannedFor }.thenBy { it.occurrence.occurrenceKey }),
                superseded = existing.filter { it.execution == OccurrenceExecution.PLANNED && it.occurrence.occurrenceKey !in replacement.map { p -> p.occurrenceKey }.toSet() },
                added = replacement.filter { it.occurrenceKey !in existingKeys }
            )
        )
    }

    private fun emptyPlan() = plan(emptyList())

    private fun emptyReconciliation() = TargetOccurrenceReconciliation(emptyList(), emptyList(), emptyList())

    private fun planned(key: String, date: LocalDate) = PlannedOccurrence(
        key,
        date,
        listOf(OccurrenceComponent("rule-$key", "Workout $key"))
    )

    private fun existing(
        occurrence: PlannedOccurrence,
        execution: OccurrenceExecution = OccurrenceExecution.PLANNED,
        actuals: List<ActualResult> = emptyList()
    ) = ExistingOccurrence(occurrence, execution, actuals)

    private fun actuals() = listOf(ActualResult.fromPerformed("set-1", PerformedWork.reps(12)))

    private companion object {
        val AS_OF: LocalDate = LocalDate.parse("2026-10-05")
        val PAUSE = ProgramPauseWindow(AS_OF.minusDays(2), AS_OF.plusDays(2))
    }
}
