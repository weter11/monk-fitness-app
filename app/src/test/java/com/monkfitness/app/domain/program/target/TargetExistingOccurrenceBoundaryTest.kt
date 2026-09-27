package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.ProgramPauseWindow
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.PlannedOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * §30 step 17 behaviour: the target scheduling input, and the state equivalence the boundary
 * refactor had to preserve.
 *
 * Every case here is a case the *previous* target behaviour already had — the reconciler, the
 * planner and the temporal policy are unchanged, and this suite is what makes that a measurement
 * rather than a promise. The one thing that is new is the input's shape: a target occurrence is now
 * the planned payload plus an execution classification, and no scheduling rule needed anything
 * else.
 */
class TargetExistingOccurrenceBoundaryTest {

    // ---- the value itself ----------------------------------------------------------------------------

    @Test
    fun aTargetExistingOccurrenceIsItsPayloadAndItsExecution() {
        val occurrence = planned("strength", DAY_ONE)
        val existing = TargetExistingOccurrence(occurrence, OccurrenceExecution.STARTED)

        assertEquals(occurrence, existing.occurrence)
        assertEquals(OccurrenceExecution.STARTED, existing.execution)
    }

    @Test
    fun theExecutionMayBeBuiltFromThePhaseSixteenDecision() {
        val occurrence = planned("strength", DAY_ONE)
        val decision = TargetOccurrenceExecutionDecision(
            execution = OccurrenceExecution.COMPLETED,
            slotStatus = SlotStatus.MISSED,
            attemptCount = 2
        )

        val existing = TargetExistingOccurrence(occurrence, decision)

        // The opportunity outcome and the attempt count are carried by the decision for other
        // readers; only the execution is a scheduling fact, and only it crosses.
        assertEquals(OccurrenceExecution.COMPLETED, existing.execution)
        assertEquals(OccurrenceExecution.COMPLETED, existing.execution)
    }

    @Test
    fun membershipIdentityIsTheOccurrenceKeyAndNothingElse() {
        val occurrence = planned("strength:2026-10-05", DAY_ONE)

        assertEquals("strength:2026-10-05", TargetExistingOccurrence(occurrence, OccurrenceExecution.PLANNED).occurrenceKey)
        assertEquals(DAY_ONE, TargetExistingOccurrence(occurrence, OccurrenceExecution.PLANNED).plannedFor)
    }

    @Test
    fun onlyThePlannedStateReadsAsPlanned() {
        val occurrence = planned("strength", DAY_ONE)

        assertTrue(TargetExistingOccurrence(occurrence, OccurrenceExecution.PLANNED).isPlanned)
        OccurrenceExecution.entries.filter { it != OccurrenceExecution.PLANNED }.forEach { execution ->
            assertTrue(
                "$execution is not a planned occurrence",
                !TargetExistingOccurrence(occurrence, execution).isPlanned
            )
        }
    }

    @Test
    fun theValueIsADataClassAndComparesByValue() {
        val occurrence = planned("strength", DAY_ONE)

        assertEquals(
            TargetExistingOccurrence(occurrence, OccurrenceExecution.STARTED),
            TargetExistingOccurrence(occurrence, OccurrenceExecution.STARTED)
        )
        assertNotEquals(
            TargetExistingOccurrence(occurrence, OccurrenceExecution.STARTED),
            TargetExistingOccurrence(occurrence, OccurrenceExecution.COMPLETED)
        )
    }

    // ---- every execution state survives reconciliation -----------------------------------------------

    @Test
    fun everyExecutionStateReachesTheSameReconciliationBucketAsBefore() {
        // The bucket a state lands in is unchanged; only the *type* carrying it changed. A state with
        // history is preserved whole, and a PLANNED occurrence is the one state that is not — it is
        // what a replacement plan is allowed to withdraw, which is why it is superseded and not
        // preserved. Both halves are asserted here because the difference is the rule.
        OccurrenceExecution.entries.forEach { execution ->
            val existing = listOf(TargetExistingOccurrence(planned("historical", DAY_ONE), execution))

            val result = TargetOccurrenceReconciler.reconcile(existing, emptyList())

            if (execution == OccurrenceExecution.PLANNED) {
                assertEquals(
                    "a planned occurrence is withdrawable, so it is superseded rather than preserved",
                    existing,
                    result.superseded
                )
                assertEquals(emptyList<TargetExistingOccurrence>(), result.preserved)
            } else {
                assertEquals(
                    "$execution must be preserved unchanged",
                    existing,
                    result.preserved
                )
                assertEquals(
                    "a $execution occurrence is never superseded",
                    emptyList<TargetExistingOccurrence>(),
                    result.superseded
                )
            }
            assertEquals("a $execution occurrence is never added", emptyList<PlannedOccurrence>(), result.added)
        }
    }

    @Test
    fun aPlannedExistingOccurrenceIsRetainedNotPreservedWhenTheTargetStillPresentsIt() {
        val occurrence = planned("strength", DAY_ONE)
        val existing = listOf(TargetExistingOccurrence(occurrence, OccurrenceExecution.PLANNED))

        val result = TargetOccurrenceReconciler.reconcile(existing, listOf(occurrence))

        assertEquals(emptyList<TargetExistingOccurrence>(), result.preserved)
        assertEquals(emptyList<TargetExistingOccurrence>(), result.superseded)
        assertEquals(emptyList<PlannedOccurrence>(), result.added)
    }

    @Test
    fun aPlannedExistingOccurrenceIsSupersededOnceTheTargetWithdrawsIt() {
        val existing = listOf(TargetExistingOccurrence(planned("withdrawn", DAY_ONE), OccurrenceExecution.PLANNED))

        val result = TargetOccurrenceReconciler.reconcile(existing, emptyList())

        assertEquals(existing, result.superseded)
        assertEquals(emptyList<TargetExistingOccurrence>(), result.preserved)
    }

    // ---- the whole pipeline, on the new input --------------------------------------------------------

    @Test
    fun thePlannerCarriesTheNewInputThroughReconciliationUnchanged() {
        val started = planned("started", DAY_ONE, listOf("started"))
        val completed = planned("completed", DAY_ONE, listOf("completed"))
        val cancelled = planned("cancelled", DAY_ONE, listOf("cancelled"))
        val existing = listOf(
            TargetExistingOccurrence(started, OccurrenceExecution.STARTED),
            TargetExistingOccurrence(completed, OccurrenceExecution.COMPLETED),
            TargetExistingOccurrence(cancelled, OccurrenceExecution.CANCELLED)
        )

        val plan = TargetPlanner.plan(emptyList(), WINDOW, existing = existing)

        // Canonical order is (plannedFor, occurrenceKey): all three fall on the same date, so the key
        // orders them — cancelled, completed, started.
        assertEquals(listOf(cancelled, completed, started), plan.reconciliation.preserved.map { it.occurrence })
        assertEquals(
            listOf(OccurrenceExecution.CANCELLED, OccurrenceExecution.COMPLETED, OccurrenceExecution.STARTED),
            plan.reconciliation.preserved.map { it.execution }
        )
        assertTrue(plan.reconciliation.superseded.isEmpty())
    }

    @Test
    fun thePolicyClassifiesTemporallyOnTheSameFactsAsBefore() {
        val asOf = LocalDate.parse("2026-10-05")
        val past = planned("past", asOf.minusDays(1))
        val today = planned("today", asOf)
        val future = planned("future", asOf.plusDays(1))
        val existing = listOf(
            TargetExistingOccurrence(past, OccurrenceExecution.PLANNED),
            TargetExistingOccurrence(today, OccurrenceExecution.PLANNED),
            TargetExistingOccurrence(future, OccurrenceExecution.PLANNED),
            TargetExistingOccurrence(planned("done", asOf), OccurrenceExecution.COMPLETED)
        )
        val targetPlan = TargetPlan(
            planned = listOf(today, future),
            reconciliation = TargetOccurrenceReconciliation(
                preserved = listOf(TargetExistingOccurrence(planned("done", asOf), OccurrenceExecution.COMPLETED)),
                superseded = emptyList(),
                added = emptyList()
            )
        )

        val decision = TargetSchedulePolicy.decide(targetPlan, existing, asOf, emptyList())

        assertEquals(listOf(past), decision.missed.map { it.occurrence })
        assertEquals(listOf(today, future), decision.retained.map { it.occurrence })
        assertEquals(listOf(planned("done", asOf)), decision.preserved.map { it.occurrence })
        assertTrue(decision.created.isEmpty())
        assertTrue(decision.superseded.isEmpty())
    }

    @Test
    fun aPausedPastDateIsSupersededRatherThanMissed() {
        val asOf = LocalDate.parse("2026-10-05")
        val pausedPast = planned("paused", asOf.minusDays(1))
        val pauses = listOf(ProgramPauseWindow(asOf.minusDays(2), asOf.plusDays(2)))
        val existing = listOf(TargetExistingOccurrence(pausedPast, OccurrenceExecution.PLANNED))
        val targetPlan = TargetPlan(emptyList(), TargetOccurrenceReconciliation(emptyList(), emptyList(), emptyList()))

        val decision = TargetSchedulePolicy.decide(targetPlan, existing, asOf, pauses)

        assertTrue(decision.missed.isEmpty())
        assertEquals(
            listOf(pausedPast),
            decision.superseded.map { it.occurrence.occurrence }
        )
        assertEquals(TargetSupersessionReason.PASSED_WHILE_PAUSED, decision.superseded.single().reason)
    }

    @Test
    fun sameDateCompositionIsUnaffectedByTheInputShape() {
        val composed = TargetPlanner.plan(
            schedules = listOf(
                TargetSchedule.daily("strength", "Strength", DAY_ONE),
                TargetSchedule.daily("mobility", "Mobility", DAY_ONE)
            ),
            window = WINDOW,
            selection = CompositionSelection(setOf("strength", "mobility"))
        )

        val existing = TargetExistingOccurrence(
            composed.planned.single(),
            OccurrenceExecution.PLANNED
        )
        val reconciles = TargetOccurrenceReconciler.reconcile(listOf(existing), composed.planned)

        assertEquals(emptyList<TargetExistingOccurrence>(), reconciles.preserved)
        assertEquals(emptyList<PlannedOccurrence>(), reconciles.added)
        assertSame(composed.planned.single(), existing.occurrence)
    }

    @Test
    fun aPayloadConflictIsStillRefusedByTheReconciler() {
        val stored = TargetExistingOccurrence(planned("strength", DAY_ONE, listOf("a")), OccurrenceExecution.PLANNED)
        val conflicting = planned("strength", DAY_ONE, listOf("b"))

        val failure = runCatching {
            TargetOccurrenceReconciler.reconcile(listOf(stored), listOf(conflicting))
        }.exceptionOrNull()

        assertTrue("a same-key payload conflict must be refused: $failure", failure is IllegalArgumentException)
    }

    @Test
    fun aDuplicateKeyIsStillRefusedByTheReconciler() {
        val occurrence = planned("strength", DAY_ONE)
        val existing = listOf(
            TargetExistingOccurrence(occurrence, OccurrenceExecution.PLANNED),
            TargetExistingOccurrence(occurrence, OccurrenceExecution.STARTED)
        )

        val failure = runCatching {
            TargetOccurrenceReconciler.reconcile(existing, emptyList())
        }.exceptionOrNull()

        assertTrue("a duplicate existing key must be refused: $failure", failure is IllegalArgumentException)
    }

    @Test
    fun theValueNeedsNoPerformanceDataToReachEverySchedulingVerdict() {
        // Every verdict the target contour can produce is reachable from a two-field input. This is
        // the operational statement of the boundary: no scheduling rule ever wanted performance.
        val asOf = LocalDate.parse("2026-10-05")
        val occurrence = planned("strength", asOf)
        val verdicts = OccurrenceExecution.entries.associateWith { execution ->
            val existing = TargetExistingOccurrence(occurrence, execution)
            val targetPlan = TargetPlan(
                planned = listOf(occurrence),
                reconciliation = TargetOccurrenceReconciliation(
                    preserved = if (execution == OccurrenceExecution.PLANNED) emptyList() else listOf(existing),
                    superseded = emptyList(),
                    added = emptyList()
                )
            )
            TargetSchedulePolicy.decide(targetPlan, listOf(existing), asOf, emptyList())
        }

        assertEquals(OccurrenceExecution.entries.toSet(), verdicts.keys)
        OccurrenceExecution.entries.filter { it != OccurrenceExecution.PLANNED }.forEach { execution ->
            assertEquals("a $execution occurrence is preserved", 1, verdicts.getValue(execution).preserved.size)
        }
        assertEquals(
            "a planned occurrence on the as-of date is retained, not missed",
            1,
            verdicts.getValue(OccurrenceExecution.PLANNED).retained.size
        )
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    private fun planned(
        key: String,
        date: LocalDate,
        componentIds: List<String> = listOf("workout")
    ) = PlannedOccurrence(
        occurrenceKey = key,
        plannedFor = date,
        components = componentIds.map { OccurrenceComponent(it, "Workout $it") }
    )

    private companion object {
        val DAY_ONE: LocalDate = LocalDate.parse("2026-10-05")
        val WINDOW = TargetScheduleWindow(DAY_ONE, DAY_ONE)
    }
}
