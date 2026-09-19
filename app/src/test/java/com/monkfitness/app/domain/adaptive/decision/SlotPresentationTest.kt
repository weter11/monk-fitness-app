package com.monkfitness.app.domain.adaptive.decision

import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.EffectiveExercise
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §16's composition, on its own.
 *
 * `standingAdjustments` and `presentedWorkout` are pure functions, so everything §16 claims about a
 * presentation can be measured without a database: which adjustment is in effect, what the presentation
 * is once it is applied, and what the composition refuses rather than guessing. The suite is deliberately
 * free of storage — the end-to-end path (a session capturing this presentation) is `SessionRuntimeTest`'s.
 */
class SlotPresentationTest {

    private val day = ProgramDay(
        programDayId = ProgramDayId("day-1"),
        position = 1,
        type = ProgramDayType.TRAINING,
        name = "Push day",
        exercises = listOf(
            element("pushup", RepPrescription(listOf(12, 10, 8, 6))),
            element("pike_pushup", RepPrescription(listOf(8, 8)), id = "plan-ex-2", pinned = true),
            element("plank", TimePrescription(listOf(30, 30, 45)), id = "plan-ex-3")
        )
    )

    private val slot = WorkoutSlot(
        slotId = SlotId("slot-1"),
        programId = ProgramId("program-1"),
        revisionId = RevisionId("revision-1"),
        programDayId = day.programDayId,
        plannedFor = LocalDate.parse("2026-09-21"),
        status = SlotStatus.PLANNED
    )

    private val computedAt: Instant = Instant.parse("2026-09-21T07:30:00Z")

    private fun element(
        exerciseId: String,
        prescription: com.monkfitness.app.domain.prescription.Prescription,
        id: String = "plan-ex-1",
        pinned: Boolean = false
    ) = ProgramExercise(
        programExerciseId = ProgramExerciseId(id),
        exerciseId = exerciseId,
        prescription = prescription,
        origin = ProgramExerciseOrigin.USER_AUTHORED,
        isPinned = pinned
    )

    private fun before(element: ProgramExercise = day.exercises.first()) = EffectiveExercise(
        element.programExerciseId,
        element.exerciseId,
        element.prescription
    )

    private fun adjustment(
        id: String,
        after: EffectiveExercise,
        supersedes: String? = null,
        before: EffectiveExercise = before()
    ) = AdaptiveAdjustment(
        adjustmentId = AdjustmentId("adjustment-$id"),
        decisionId = DecisionId("decision-$id"),
        slotId = slot.slotId,
        before = before,
        after = after,
        createdAt = computedAt,
        supersedesAdjustmentId = supersedes?.let { AdjustmentId("adjustment-$it") }
    )

    // ------------------------------------------------------------------ which adjustment is in effect

    @Test
    fun theStandingAdjustmentOfAChainIsTheOneNothingSupersedes() {
        val first = adjustment("a1", before().copy(exerciseId = "knee_pushup"))
        val second = adjustment(
            "a2",
            before().copy(exerciseId = "diamond_pushup"),
            supersedes = "a1",
            before = first.after
        )

        assertEquals(
            "a superseded adjustment is history: the row is not rewritten, it simply stops being the " +
                "one in effect (§16)",
            listOf(second.adjustmentId),
            standingAdjustments(listOf(first, second)).map { it.adjustmentId }
        )
    }

    @Test
    fun adjustmentsThatChangeDifferentElementsAllStand() {
        val first = adjustment("a1", before().copy(exerciseId = "knee_pushup"))
        val second = adjustment(
            "a2",
            before(day.exercises[1]).copy(exerciseId = "band_pull_apart"),
            before = before(day.exercises[1])
        )

        assertEquals(
            "one standing adjustment per element is not a conflict: they change different elements",
            listOf(first.adjustmentId, second.adjustmentId),
            standingAdjustments(listOf(first, second)).map { it.adjustmentId }
        )
    }

    @Test
    fun aSupersededAdjustmentIsRecognisedHoweverLongTheChain() {
        val third = adjustment("a3", before().copy(exerciseId = "wide_pushup"), supersedes = "a2")
        val first = adjustment("a1", before().copy(exerciseId = "knee_pushup"))
        val second = adjustment("a2", before().copy(exerciseId = "diamond_pushup"), supersedes = "a1")

        assertEquals(
            "the chain is read from the links, in the order the rows were made",
            listOf(third.adjustmentId),
            standingAdjustments(listOf(first, second, third)).map { it.adjustmentId }
        )
    }

    @Test
    fun anIncoherentChainIsRefusedRatherThanResolved() {
        val repeated = adjustment("a1", before().copy(exerciseId = "knee_pushup"))

        val failure = try {
            standingAdjustments(listOf(repeated, repeated))
            null
        } catch (thrown: IllegalArgumentException) {
            thrown
        }

        assertTrue(
            "the same adjustment twice is not a chain to interpret: ${failure?.message}",
            failure != null && failure.message!!.contains("appears once")
        )
    }

    // ------------------------------------------------------------------ what the slot presents

    @Test
    fun anOpportunityWithNoAdjustmentPresentsItsRevisionsOwnPlan() {
        val presentation = presentedWorkout(day, slot, emptyList(), computedAt)

        assertEquals(
            "the elements are the plan day's, in the plan's own order",
            listOf("pushup", "pike_pushup", "plank"),
            presentation.exercises.map { it.exerciseId }
        )
        assertEquals(
            listOf("plan-ex-1", "plan-ex-2", "plan-ex-3"),
            presentation.exercises.map { it.programExerciseId.value }
        )
        assertEquals(
            "with each element's prescription, per set, as the revision wrote it",
            listOf(listOf(12, 10, 8, 6), listOf(8, 8), listOf(30, 30, 45)),
            presentation.exercises.map { it.prescription.perSetTargets }
        )
        assertEquals(emptyList<AdjustmentId>(), presentation.appliedAdjustmentIds)
        assertFalse(presentation.isAdjusted)
    }

    @Test
    fun anAdjustmentReplacesItsOwnElementInPlaceAndIsCapturedById() {
        val adjusted = adjustment("a1", before().copy(exerciseId = "knee_pushup", prescription = RepPrescription(listOf(8, 8))))

        val presentation = presentedWorkout(day, slot, listOf(adjusted), computedAt)

        assertEquals(
            "the changed element is presented as the adjustment says — an adjustment changes one " +
                "presented element, not a different one (§16)",
            listOf("knee_pushup", "pike_pushup", "plank"),
            presentation.exercises.map { it.exerciseId }
        )
        assertEquals(
            "and it keeps its place: presentation order is the plan's, not the adjustment's",
            listOf("plan-ex-1", "plan-ex-2", "plan-ex-3"),
            presentation.exercises.map { it.programExerciseId.value }
        )
        assertEquals(
            listOf(listOf(8, 8), listOf(8, 8), listOf(30, 30, 45)),
            presentation.exercises.map { it.prescription.perSetTargets }
        )
        assertEquals(listOf(adjusted.adjustmentId), presentation.appliedAdjustmentIds)
        assertTrue(presentation.isAdjusted)
    }

    @Test
    fun theCaptureHoldsEveryAdjustmentThatWasAppliedInPresentationOrder() {
        val first = adjustment("a1", before().copy(exerciseId = "knee_pushup"))
        val third = adjustment(
            "a3",
            before(day.exercises[2]).copy(prescription = TimePrescription(listOf(20, 20, 20))),
            before = before(day.exercises[2])
        )

        val presentation = presentedWorkout(day, slot, listOf(first, third), computedAt)

        assertEquals(
            "the ids are captured in the order of the presentation they change, so a captured snapshot " +
                "reads in the order the user saw",
            listOf(first.adjustmentId, third.adjustmentId),
            presentation.appliedAdjustmentIds
        )
        assertEquals(listOf("knee_pushup", "pike_pushup", "plank"), presentation.exercises.map { it.exerciseId })
        assertEquals(listOf(20, 20, 20), presentation.exercises.last().prescription.perSetTargets)
    }

    @Test
    fun thePresentationCarriesTheOpportunityItIsFor() {
        val presentation = presentedWorkout(day, slot, emptyList(), computedAt)

        assertEquals(slot.slotId, presentation.slotId)
        assertEquals(slot.programId, presentation.programId)
        assertEquals(
            "the revision the opportunity was scheduled from, which is what an adjustment is expressed " +
                "against",
            slot.revisionId,
            presentation.revisionId
        )
        assertEquals(
            "the planned date is the opportunity's, and the computation moment is the caller's",
            slot.plannedFor,
            presentation.plannedFor
        )
        assertEquals(computedAt, presentation.computedAt)
    }

    @Test
    fun twoAdjustmentsChangingOneElementAreRefusedRatherThanResolved() {
        val first = adjustment("a1", before().copy(exerciseId = "knee_pushup"))
        val second = adjustment("a2", before().copy(exerciseId = "diamond_pushup"))

        val failure = try {
            presentedWorkout(day, slot, listOf(first, second), computedAt)
            null
        } catch (thrown: IllegalArgumentException) {
            thrown
        }

        assertTrue(
            "two standing changes to one element are two answers to 'what is presented': " +
                "${failure?.message}",
            failure != null && failure.message!!.contains("presented once")
        )
    }

    @Test
    fun anAdjustmentThatChangesAnElementThisPlanDayDoesNotPresentIsRefused() {
        val nowhere = EffectiveExercise(
            ProgramExerciseId("plan-ex-nowhere"),
            "pushup",
            RepPrescription(listOf(12, 10, 8, 6))
        )
        val elsewhere = adjustment("a9", nowhere.copy(exerciseId = "knee_pushup"), before = nowhere)

        val failure = try {
            presentedWorkout(day, slot, listOf(elsewhere), computedAt)
            null
        } catch (thrown: IllegalArgumentException) {
            thrown
        }

        assertTrue(
            "a change the plan day cannot present is not silently skipped: ${failure?.message}",
            failure != null && failure.message!!.contains("must change an element its revision presents")
        )
    }

    @Test
    fun theCompositionIsAFunctionOfItsInputsAndNothingElse() {
        val adjusted = adjustment("a1", before().copy(exerciseId = "knee_pushup"))
        val original = day

        val first = presentedWorkout(day, slot, listOf(adjusted), computedAt)
        val second = presentedWorkout(day, slot, listOf(adjusted), computedAt)

        assertEquals(
            "the same opportunity and the same chain present the same workout — no clock, no random " +
                "source and no state is read",
            first,
            second
        )
        assertEquals("and nothing was mutated by composing it", original, day)
        assertTrue(
            "the result is a value with the presentation's own field set: what was presented, for which " +
                "opportunity, and which adjustments were applied",
            first.exercises.isNotEmpty() && first.appliedAdjustmentIds == listOf(adjusted.adjustmentId)
        )
    }
}
