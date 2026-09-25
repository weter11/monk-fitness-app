package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.ActualResult
import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PerformedWork
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.ScheduleEditReconciler
import com.monkfitness.app.domain.program.ScheduleReconciliation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TargetOccurrenceReconcilerTest {
    @Test
    fun emptyExistingAndReplacementProduceEmptyDelta() {
        assertEquals(
            TargetOccurrenceReconciliation(emptyList(), emptyList(), emptyList()),
            TargetOccurrenceReconciler.reconcile(emptyList(), emptyList())
        )
    }

    @Test
    fun existingPlannedWithSameIdentityIsRetainedWithoutDuplication() {
        val existingOccurrence = planned("strength", DAY_ONE)
        val existing = listOf(existing(existingOccurrence))

        val result = TargetOccurrenceReconciler.reconcile(existing, listOf(existingOccurrence))

        assertEquals(emptyList<ExistingOccurrence>(), result.preserved)
        assertEquals(emptyList<ExistingOccurrence>(), result.superseded)
        assertEquals(emptyList<PlannedOccurrence>(), result.added)
    }

    @Test
    fun existingPlannedRemovedFromReplacementIsSuperseded() {
        val existingOccurrence = planned("posture", DAY_ONE)

        val result = TargetOccurrenceReconciler.reconcile(
            listOf(existing(existingOccurrence)),
            emptyList()
        )

        assertEquals(listOf(existingOccurrence), result.superseded.map { it.occurrence })
        assertEquals(emptyList<ExistingOccurrence>(), result.preserved)
        assertEquals(emptyList<PlannedOccurrence>(), result.added)
    }

    @Test
    fun newReplacementIdentityIsAdded() {
        val replacement = planned("new", DAY_ONE)

        val result = TargetOccurrenceReconciler.reconcile(emptyList(), listOf(replacement))

        assertEquals(listOf(replacement), result.added)
    }

    @Test
    fun startedExistingOccurrenceSurvivesRemoval() {
        val occurrence = planned("strength", DAY_ONE)
        val existing = existing(occurrence, OccurrenceExecution.STARTED, actuals())

        val result = TargetOccurrenceReconciler.reconcile(listOf(existing), emptyList())

        assertEquals(listOf(existing), result.preserved)
        assertEquals(emptyList<ExistingOccurrence>(), result.superseded)
        assertEquals(emptyList<PlannedOccurrence>(), result.added)
    }

    @Test
    fun completedExistingOccurrenceSurvivesRemoval() {
        val occurrence = planned("mobility", DAY_ONE)
        val existing = existing(occurrence, OccurrenceExecution.COMPLETED, actuals())

        val result = TargetOccurrenceReconciler.reconcile(listOf(existing), emptyList())

        assertEquals(listOf(existing), result.preserved)
        assertEquals(emptyList<ExistingOccurrence>(), result.superseded)
    }

    @Test
    fun cancelledExistingOccurrenceSurvivesRemoval() {
        val occurrence = planned("posture", DAY_ONE)
        val existing = existing(occurrence, OccurrenceExecution.CANCELLED, actuals())

        val result = TargetOccurrenceReconciler.reconcile(listOf(existing), emptyList())

        assertEquals(listOf(existing), result.preserved)
        assertEquals(emptyList<ExistingOccurrence>(), result.superseded)
    }

    @Test
    fun factualExistingOccurrenceWithSameIdentityPreventsReplacementDuplication() {
        val occurrence = planned("strength", DAY_ONE)
        val existing = existing(occurrence, OccurrenceExecution.COMPLETED, actuals())

        val result = TargetOccurrenceReconciler.reconcile(listOf(existing), listOf(occurrence))

        assertEquals(listOf(existing), result.preserved)
        assertEquals(emptyList<PlannedOccurrence>(), result.added)
    }

    @Test
    fun changedCompositionMembershipSupersedesOldIdentityAndAddsNewIdentity() {
        val old = planned("combined:2026-10-05:1:a|1:b", DAY_ONE, listOf("a", "b"))
        val replacement = planned("combined:2026-10-05:1:a|1:c", DAY_ONE, listOf("a", "c"))
        val existing = existing(old)

        val result = TargetOccurrenceReconciler.reconcile(listOf(existing), listOf(replacement))

        assertEquals(listOf(old), result.superseded.map { it.occurrence })
        assertEquals(listOf(replacement), result.added)
        assertEquals(emptyList<ExistingOccurrence>(), result.preserved)
    }

    @Test
    fun sameDateWithDifferentOccurrenceKeysRemainDistinct() {
        val first = planned("first", DAY_ONE)
        val second = planned("second", DAY_ONE)
        val existing = listOf(existing(first), existing(second))

        val result = TargetOccurrenceReconciler.reconcile(existing, listOf(second, first))

        assertTrue(result.superseded.isEmpty())
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun differentDatesNeverCollapse() {
        val first = planned("strength:${DAY_ONE}", DAY_ONE)
        val second = planned("strength:${DAY_TWO}", DAY_TWO)
        val result = TargetOccurrenceReconciler.reconcile(
            listOf(existing(first), existing(second)),
            listOf(first, second)
        )

        assertTrue(result.superseded.isEmpty())
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun reversedExistingInputProducesIdenticalResult() {
        val first = planned("z", DAY_TWO)
        val second = planned("a", DAY_ONE)
        val replacement = listOf(planned("new", DAY_ONE))
        val forward = TargetOccurrenceReconciler.reconcile(
            listOf(existing(first), existing(second)),
            replacement
        )
        val reversed = TargetOccurrenceReconciler.reconcile(
            listOf(existing(second), existing(first)),
            replacement
        )

        assertEquals(forward, reversed)
        assertEquals(listOf("a", "z"), forward.superseded.map { it.occurrence.occurrenceKey })
        assertEquals(listOf(DAY_ONE, DAY_TWO), forward.superseded.map { it.occurrence.plannedFor })
    }

    @Test
    fun reversedReplacementInputProducesIdenticalResult() {
        val first = planned("first", DAY_TWO)
        val second = planned("second", DAY_ONE)
        val forward = TargetOccurrenceReconciler.reconcile(
            emptyList(),
            listOf(first, second)
        )
        val reversed = TargetOccurrenceReconciler.reconcile(
            emptyList(),
            listOf(second, first)
        )

        assertEquals(forward, reversed)
        assertEquals(listOf(DAY_ONE, DAY_TWO), forward.added.map { it.plannedFor })
    }

    @Test
    fun duplicateExistingOccurrenceKeyIsRejected() {
        val occurrence = planned("strength", DAY_ONE)

        assertThrows(IllegalArgumentException::class.java) {
            TargetOccurrenceReconciler.reconcile(
                listOf(existing(occurrence), existing(occurrence)),
                emptyList()
            )
        }
    }

    @Test
    fun duplicateReplacementOccurrenceKeyIsRejected() {
        val occurrence = planned("strength", DAY_ONE)

        assertThrows(IllegalArgumentException::class.java) {
            TargetOccurrenceReconciler.reconcile(emptyList(), listOf(occurrence, occurrence))
        }
    }

    @Test
    fun sameKeyWithConflictingDatePayloadIsRejected() {
        val existingOccurrence = planned("strength", DAY_ONE)
        val conflictingReplacement = planned("strength", DAY_TWO)

        assertThrows(IllegalArgumentException::class.java) {
            TargetOccurrenceReconciler.reconcile(
                listOf(existing(existingOccurrence)),
                listOf(conflictingReplacement)
            )
        }
    }

    @Test
    fun sameKeyWithConflictingComponentsPayloadIsRejected() {
        val existingOccurrence = planned("combined", DAY_ONE, listOf("a", "b"))
        val conflictingReplacement = planned("combined", DAY_ONE, listOf("a", "c"))

        assertThrows(IllegalArgumentException::class.java) {
            TargetOccurrenceReconciler.reconcile(
                listOf(existing(existingOccurrence)),
                listOf(conflictingReplacement)
            )
        }
    }

    @Test
    fun historicalActualResultsRemainEqualityIdentical() {
        val actuals = listOf(
            ActualResult.fromPerformed("set-1", PerformedWork.reps(12))
        )
        val existing = existing(
            planned("strength", DAY_ONE),
            OccurrenceExecution.COMPLETED,
            actuals
        )

        val result = TargetOccurrenceReconciler.reconcile(listOf(existing), emptyList())

        assertEquals(existing, result.preserved.single())
        assertEquals(actuals, result.preserved.single().actuals)
        assertSame(actuals, result.preserved.single().actuals)
    }

    @Test
    fun inputCollectionsAreNotMutated() {
        val existing = listOf(existing(planned("old", DAY_ONE)))
        val replacement = listOf(planned("new", DAY_ONE))
        val existingSnapshot = existing.toList()
        val replacementSnapshot = replacement.toList()

        TargetOccurrenceReconciler.reconcile(existing, replacement)

        assertEquals(existingSnapshot, existing)
        assertEquals(replacementSnapshot, replacement)
    }

    @Test
    fun repeatedExecutionIsEqualityIdentical() {
        val existing = listOf(existing(planned("old", DAY_ONE)))
        val replacement = listOf(planned("new", DAY_ONE))

        assertEquals(
            TargetOccurrenceReconciler.reconcile(existing, replacement),
            TargetOccurrenceReconciler.reconcile(existing, replacement)
        )
    }

    @Test
    fun applyingSameReplacementAgainIsAnIdempotentNoOpForFutureMaterial() {
        val old = planned("old", DAY_ONE)
        val replacement = planned("new", DAY_ONE)
        val first = TargetOccurrenceReconciler.reconcile(listOf(existing(old)), listOf(replacement))
        val current = first.preserved + first.added.map(::existing)

        val second = TargetOccurrenceReconciler.reconcile(current, listOf(replacement))

        assertTrue(second.superseded.isEmpty())
        assertTrue(second.added.isEmpty())
    }

    @Test
    fun stageOneParityPreservesStartedAndCompletedAndKeepsReplacementAsFutureMaterial() {
        val startedOccurrence = planned("strength", DAY_ONE)
        val completedOccurrence = planned("mobility", DAY_TWO)
        val replacement = planned("posture", DAY_ONE)
        val existing = listOf(
            existing(startedOccurrence, OccurrenceExecution.STARTED, actuals()),
            existing(completedOccurrence, OccurrenceExecution.COMPLETED, actuals())
        )

        val result = ScheduleEditReconciler.reconcile(existing, replacement)

        assertEquals(listOf(existing[0], existing[1]), result.preserved)
        assertEquals(listOf(replacement), result.future)
        assertEquals(ScheduleReconciliation(listOf(existing[0], existing[1]), listOf(replacement)), result)
    }

    @Test
    fun strongSemanticExamplePlacesEveryItemInExactlyOneIntentionalBucket() {
        val strength = planned("strength", DAY_ONE)
        val mobility = planned("mobility", DAY_ONE)
        val posture = planned("posture", DAY_ONE)
        val oldCombined = planned("old-combined", DAY_TWO)
        val newCombined = planned("new-combined", DAY_TWO)
        val existing = listOf(
            existing(strength, OccurrenceExecution.STARTED, actuals()),
            existing(mobility, OccurrenceExecution.COMPLETED, actuals()),
            existing(posture),
            existing(oldCombined)
        )
        val replacement = listOf(strength, mobility, newCombined)

        val result = TargetOccurrenceReconciler.reconcile(existing, replacement)

        assertEquals(listOf(existing[1], existing[0]), result.preserved)
        assertEquals(listOf(posture, oldCombined), result.superseded.map { it.occurrence })
        assertEquals(listOf(newCombined), result.added)
    }

    private fun planned(
        key: String,
        date: LocalDate,
        componentIds: List<String> = listOf("workout")
    ) = PlannedOccurrence(
        occurrenceKey = key,
        plannedFor = date,
        components = componentIds.map { OccurrenceComponent(it, "Workout $it") }
    )

    private fun existing(
        occurrence: PlannedOccurrence,
        execution: OccurrenceExecution = OccurrenceExecution.PLANNED,
        actuals: List<ActualResult> = emptyList()
    ) = ExistingOccurrence(occurrence, execution, actuals)

    private fun actuals() = listOf(ActualResult.fromPerformed("set-1", PerformedWork.reps(12)))

    private companion object {
        val DAY_ONE: LocalDate = LocalDate.parse("2026-10-05")
        val DAY_TWO: LocalDate = LocalDate.parse("2026-10-06")
    }
}
