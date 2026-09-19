package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7's reconciliation, pinned level by level.
 *
 * The precedence is the whole contract — `PINNED > EXPLICIT USER OVERRIDE > COMPATIBLE USER CHANGE >
 * PURE GENERATED CONTENT` — so every level gets its own test, and the tests that matter most are the
 * negative ones: a regeneration that keeps an element it may replace is a *bug*, and a regeneration
 * that replaces an element it may not is the silent overwrite §33 forbids.
 *
 * Identity is asserted throughout, because a draft's identities are what tells a user (and a screen)
 * which elements survived: a preserved element keeps its handle, and a replaced one is recognisably
 * new.
 */
class PlanReconcilerTest {

    // ------------------------------------------------------------------ PINNED

    @Test
    fun aPinnedElementIsNeverAutomaticallyChanged() {
        val plan = plan()
        val draft = draftOf(
            day(
                element("pinned-1", "posture-wall-slide", pinned = true),
                element("generated-1", "push-pushup", origin = ProgramExerciseOrigin.GENERATED)
            )
        )

        val reconciled = PlanReconciler.reconcile(draft, plan, ids())

        assertEquals(
            "the pinned element is in the plan, unchanged, with the identity it had",
            element("pinned-1", "posture-wall-slide", pinned = true),
            reconciled.days.first().exercises.first()
        )
        assertEquals(
            "and it is reported at §7's highest level, with the disagreement visible rather than " +
                "acted on",
            listOf(ProgramExerciseId("pinned-1")),
            reconciled.report.withLevel(PreservationLevel.PINNED).map { it.programExerciseId }
        )
    }

    @Test
    fun aPinnedElementThePlanDisagreesWithIsReportedAsAConflictAndStillKept() {
        val plan = plan()
        val override = element("pinned-1", "mobility-spine-wave", pinned = true)

        val reconciled = PlanReconciler.reconcile(draftOf(day(override)), plan, ids())

        assertEquals(
            "the user's pinned exercise is what the day still holds",
            override,
            reconciled.days.first().exercises.first()
        )
        assertEquals(
            "and the conflict is shown explicitly (§7), because the plan would not have produced it",
            listOf("mobility-spine-wave"),
            reconciled.report.conflicts.map { it.exerciseId }
        )
        assertEquals(PreservationLevel.PINNED, reconciled.report.conflicts.single().level)
    }

    // ------------------------------------------------------------------ EXPLICIT USER OVERRIDE

    @Test
    fun anElementTheUserAuthoredIsNeverSilentlyReplaced() {
        val plan = plan()
        val authored = element("authored-1", "legs-squat", origin = ProgramExerciseOrigin.USER_AUTHORED)
        val draft = draftOf(day(authored, element("generated-1", "push-pushup")))

        val reconciled = PlanReconciler.reconcile(draft, plan, ids())

        assertEquals(
            "the user's own element survives, with its identity and its place",
            authored,
            reconciled.days.first().exercises.first()
        )
        assertEquals(
            "reported as an explicit user override — §7's second level",
            listOf(PreservationLevel.USER_OVERRIDE),
            reconciled.report.withLevel(PreservationLevel.USER_OVERRIDE).map { it.level }
        )
        assertEquals(
            "and the generated element beside it is still the generator's to change: it is dropped, " +
                "and the plan's own elements take the day's remaining places",
            1,
            reconciled.report.ofKind(ChangeKind.DROPPED).size
        )
        assertEquals(
            "so the first day ends up holding the user's element first and the plan's own elements " +
                "after it, in plan order",
            listOf(authored.exerciseId) + plan.slots.first().elements.map { it.exerciseId },
            reconciled.days.first().exercises.map { it.exerciseId }
        )
    }

    // ------------------------------------------------------------------ COMPATIBLE USER CHANGE

    @Test
    fun aUsersCompatibleChoiceIsPreservedRatherThanReplacedBecauseWeRegenerated() {
        val plan = plan()
        // The user authored exactly what the plan produces for this day: same exercise, same
        // prescription. Regenerating must keep it — and must keep *it*, not an equal copy with a
        // fresh identity, which is the difference between preserving a choice and replacing it.
        val compatible = element(
            "authored-1",
            plan.slots.first().elements.first().exerciseId,
            prescription = plan.slots.first().elements.first().prescription,
            origin = ProgramExerciseOrigin.USER_AUTHORED
        )

        val reconciled = PlanReconciler.reconcile(draftOf(day(compatible)), plan, ids())

        assertEquals(
            "the compatible choice is reported as compatible and not as a conflict",
            listOf(PreservationLevel.COMPATIBLE),
            reconciled.report.changes.filter { it.kind == ChangeKind.PRESERVED }
                .map { it.level }
                .filter { it != PreservationLevel.GENERATED }
        )
        assertEquals(
            "nothing is reported as a conflict, because the user and the plan agree",
            emptyList<ReconciliationChange>(),
            reconciled.report.conflicts
        )
        assertEquals(
            "and the element keeps the identity the user's edit gave it",
            ProgramExerciseId("authored-1"),
            reconciled.days.first().exercises.first().programExerciseId
        )
        assertEquals(
            "the user's element stands in for the plan's element it agrees with, so the day holds " +
                "the plan's own element count and no duplicate exercise appears beside it",
            plan.slots.first().elements.size,
            reconciled.days.first().exercises.size
        )
    }

    @Test
    fun aUserChangeThatOnlyReorderedTheElementsIsKeptWhereTheUserPutIt() {
        val plan = plan()
        val first = plan.slots.first().elements.first()
        val second = plan.slots.first().elements[1]
        // The user swapped the two elements the plan produces for the first day.
        val draft = draftOf(
            day(
                element("authored-1", second.exerciseId, second.prescription, ProgramExerciseOrigin.USER_AUTHORED),
                element("authored-2", first.exerciseId, first.prescription, ProgramExerciseOrigin.USER_AUTHORED)
            )
        )

        val reconciled = PlanReconciler.reconcile(draft, plan, ids())

        assertEquals(
            "§8's 'establishes pinned/manual content first' is read literally: the user's own " +
                "elements hold their order, and the generator's content is arranged around them",
            listOf("authored-1", "authored-2"),
            reconciled.days.first().exercises.take(2).map { it.programExerciseId.value }
        )
        assertEquals(
            "and their exercise order is the user's, not the plan's",
            listOf(second.exerciseId, first.exerciseId),
            reconciled.days.first().exercises.take(2).map { it.exerciseId }
        )
    }

    // ------------------------------------------------------------------ PURE GENERATED CONTENT

    @Test
    fun anUnchangedGeneratedPlanComesBackUnchangedWithTheSameIdentities() {
        val plan = plan()
        val materialized = ProgramGeneratedEditor(draftOf(), ids("gen")).regenerate(
            GeneratedPlannerRig.request()
        )
        // The plan the editor produced is not the fixture plan, so regenerate the same request
        // again: nothing about it changed, and nothing about it may be churned.
        val again = ProgramGeneratedEditor(materialized.draft, ids("second")).regenerate(
            GeneratedPlannerRig.request()
        )

        assertTrue(
            "the same request over the plan it produced adds nothing, drops nothing and removes no " +
                "day — §7's unchanged generated plan stays unchanged",
            again.reconciliation.changedNothing
        )
        assertEquals(
            "and every element of the plan is reported as preserved rather than only the changed ones",
            materialized.plan.elementCount,
            again.reconciliation.preservedCount
        )
        assertEquals(
            "so the draft is untouched, identities included",
            materialized.draft.days,
            again.draft.days
        )
    }

    @Test
    fun oneChangedGeneratedElementIsReplacedAndEverythingElseIsPreserved() {
        val plan = plan()
        val materialized = ProgramGeneratedEditor(draftOf(), ids("gen")).regenerate(
            GeneratedPlannerRig.request()
        )
        // One element of the second day is edited by hand — the same plan, one occurrence changed.
        val days = materialized.draft.days.map { day ->
            if (day.position != 2) {
                day
            } else {
                day.copy(exercises = day.exercises.mapIndexed { index, element ->
                    if (index == 0) {
                        element.copy(prescription = RepPrescription(listOf(3, 3)))
                    } else {
                        element
                    }
                })
            }
        }
        val edited = materialized.draft.copy(days = days)

        val regenerated = ProgramGeneratedEditor(edited, ids("second")).regenerate(
            GeneratedPlannerRig.request()
        )

        assertEquals(
            "only that element is replaced: one added, one dropped, and the whole rest of the plan " +
                "preserved with its identities",
            1,
            regenerated.reconciliation.addedCount
        )
        assertEquals(1, regenerated.reconciliation.droppedCount)
        assertEquals(
            "no conflict is reported: the element was the generator's own, and §7 lets it change",
            emptyList<ReconciliationChange>(),
            regenerated.reconciliation.conflicts
        )
        val before = materialized.draft.days.flatMap { it.exercises }.map { it.programExerciseId }
        val after = regenerated.draft.days.flatMap { it.exercises }.map { it.programExerciseId }
        val dropped = regenerated.reconciliation.ofKind(ChangeKind.DROPPED)
            .mapNotNull { it.programExerciseId }
        val added = regenerated.reconciliation.ofKind(ChangeKind.ADDED)
            .mapNotNull { it.programExerciseId }

        assertEquals(
            "the plan as a whole is *not* replaced: every identity that was not the one edited is " +
                "still there",
            before.toSet() - dropped.toSet(),
            after.toSet() - added.toSet()
        )
        assertTrue(
            "and the replacement is a genuinely new occurrence: none of the added identities existed " +
                "before",
            added.none { it in before.toSet() }
        )
        assertEquals(
            "the draft holds the same number of elements as before — one replaced, not one added",
            before.size,
            after.size
        )
    }

    @Test
    fun aReplacedElementReceivesAFreshIdentityAndAPreservedOneKeepsItsOwn() {
        val plan = plan()
        val kept = plan.slots.first().elements.first()
        val draft = draftOf(
            day(
                element("kept-1", kept.exerciseId, kept.prescription, ProgramExerciseOrigin.GENERATED),
                element("dropped-1", "mobility-spine-wave", RepPrescription(listOf(1)), ProgramExerciseOrigin.GENERATED)
            )
        )

        val reconciled = PlanReconciler.reconcile(draft, plan, ids("new"))

        val identities = reconciled.days.first().exercises.map { it.programExerciseId.value }
        assertTrue(
            "the element the plan still produces keeps its handle: $identities",
            "kept-1" in identities
        )
        assertTrue(
            "the element the plan no longer produces is gone, and what took its place is a new " +
                "occurrence with an identity of its own: $identities",
            "dropped-1" !in identities && identities.all { it == "kept-1" || it.startsWith("new-") }
        )
    }

    @Test
    fun aRegenerationNeverRemovesAnElementTheUserOwns() {
        val plan = plan()
        val authored = element("authored-1", "legs-squat", origin = ProgramExerciseOrigin.USER_AUTHORED)
        val pinned = element("pinned-1", "mobility-hip-opener", pinned = true)
        // A plan about a single focus, so the user's two elements are certainly not what it produces.
        val narrow = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(focus = FocusPlan.focused(setOf(Focus.POSTURE)))
        )

        val reconciled = PlanReconciler.reconcile(draftOf(day(authored, pinned)), narrow, ids())

        assertEquals(
            "nothing the user owns is ever dropped — there is no code path here that could drop one",
            0,
            reconciled.report.ofKind(ChangeKind.DROPPED).size
        )
        assertEquals(
            "both of the user's elements are in the day, in their own order",
            listOf("authored-1", "pinned-1"),
            reconciled.days.first().exercises.take(2).map { it.programExerciseId.value }
        )
        assertEquals(
            "and the plan's own element joined them rather than displacing either",
            "posture-wall-slide",
            reconciled.days.first().exercises.last().exerciseId
        )
        assertTrue(
            "the plan is a plan of 12 slots, so the rest of the days are the plan's own",
            reconciled.days.size == 12
        )
    }

    @Test
    fun repeatedOccurrencesStayDistinctAndThePlanDecidesHowManySurvive() {
        val plan = plan()
        val first = plan.slots.first().elements.first()
        // Two identical generated occurrences where the plan wants one: an occurrence is its own
        // element (§9), and the plan's own count is what tells a surplus occurrence from a wanted one.
        val draft = draftOf(
            day(
                element("occurrence-1", first.exerciseId, first.prescription, ProgramExerciseOrigin.GENERATED),
                element("occurrence-2", first.exerciseId, first.prescription, ProgramExerciseOrigin.GENERATED)
            )
        )

        val reconciled = PlanReconciler.reconcile(draft, plan, ids("new"))

        val identities = reconciled.days.first().exercises.map { it.programExerciseId.value }
        assertEquals(
            "the two occurrences were distinct elements, which is why one of them can be the plan's " +
                "and the other the surplus",
            setOf("occurrence-1", "occurrence-2"),
            reconciled.report.ofKind(ChangeKind.DROPPED).mapNotNull { it.programExerciseId?.value }
                .plus("occurrence-1")
                .toSet()
        )
        assertEquals(
            "the plan's first element is satisfied by the first occurrence, which therefore keeps its " +
                "identity",
            "occurrence-1",
            identities.first()
        )
        assertEquals(
            "the surplus occurrence is dropped and the plan's other elements are added fresh",
            1,
            reconciled.report.ofKind(ChangeKind.DROPPED).size
        )
        assertEquals(
            "and every element of the day is its own occurrence",
            identities.size,
            identities.distinct().size
        )
    }

    // ------------------------------------------------------------------ days, not just elements

    @Test
    fun aDayThePlanNoLongerFillsIsRemovedAndOneItAddsIsAdded() {
        // A one-slot plan over a three-day draft: the first day is the plan's, the others are the
        // draft's own generated content and the plan no longer produces them.
        val narrow = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                focus = FocusPlan.focused(setOf(Focus.POSTURE)),
                candidates = listOf(
                    GeneratedPlannerRig.candidate("posture-wall-slide", "posture", setOf(Focus.POSTURE))
                ),
                schedule = com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek(1),
                duration = com.monkfitness.app.domain.program.ProgramDuration.FixedDays(7)
            )
        )
        val draft = draftOf(
            day(element("element-1", "push-pushup"), position = 1),
            day(element("element-2", "push-pushup"), position = 2),
            day(element("element-3", "push-pushup"), position = 3)
        )

        val reconciled = PlanReconciler.reconcile(draft, narrow, ids("new"))

        assertEquals("the plan holds one slot, so the draft holds one day", 1, reconciled.days.size)
        assertEquals(
            "and the days that left the plan are reported as days, once each",
            2,
            reconciled.report.removedDayCount
        )
        assertEquals(
            "with the day's own identity named, so a screen can say which one went",
            listOf(ProgramDayId("day-2"), ProgramDayId("day-3")),
            reconciled.report.ofKind(ChangeKind.DAY_REMOVED).map { it.programDayId }
        )
        assertEquals(
            "the day that stayed keeps the user's own day identity and name",
            ProgramDayId("day-1"),
            reconciled.days.single().programDayId
        )
    }

    @Test
    fun aGeneratedDayIsATrainingDayAndIsUnnamed() {
        val plan = plan()

        val reconciled = PlanReconciler.reconcile(draftOf(), plan, ids("new"))

        assertEquals(
            "§8's focus dimension is not a day type: a generated day is loaded work",
            List(plan.slotCount) { ProgramDayType.TRAINING },
            reconciled.days.map { it.type }
        )
        assertEquals(
            "and the domain authors no user-facing text (§25), so a generated day is presented by " +
                "its position and its type",
            List(plan.slotCount) { null },
            reconciled.days.map { it.name }
        )
        assertEquals(
            "the days are numbered 1..n, as a revision must hold them",
            (1..plan.slotCount).toList(),
            reconciled.days.map { it.position }
        )
    }

    @Test
    fun aRestDayThePlanFillsWithWorkBecomesAWorkDay() {
        val plan = plan()
        val rest = ProgramDay(
            programDayId = ProgramDayId("rest-1"),
            position = 1,
            type = ProgramDayType.REST
        )

        val reconciled = PlanReconciler.reconcile(draftOf(rest), plan, ids("new"))

        assertEquals(
            "a rest day holds no elements (§20), so a plan that puts work on it makes it a work day " +
                "rather than dropping the plan's elements to preserve the type",
            ProgramDayType.TRAINING,
            reconciled.days.first().type
        )
        assertEquals(
            "and the user's day identity survives — the day was retyped, not replaced",
            ProgramDayId("rest-1"),
            reconciled.days.first().programDayId
        )
    }

    @Test
    fun aReconciliationLeavesADifferentPlanAloneOnlyWhereItMay() {
        // The negative half of §7's precedence, asserted as counts: the pure generated content of a
        // plan that changed wholesale is replaced, and everything the user owns is untouched.
        val plan = plan()
        val draft = draftOf(
            day(
                element("authored-1", "legs-squat", origin = ProgramExerciseOrigin.USER_AUTHORED),
                element("generated-1", "mobility-spine-wave", RepPrescription(listOf(1))),
                element("generated-2", "mobility-hip-opener", RepPrescription(listOf(2)))
            )
        )

        val reconciled = PlanReconciler.reconcile(draft, plan, ids("new"))

        assertEquals(
            "the two generated elements are dropped and the plan's own are added",
            2,
            reconciled.report.ofKind(ChangeKind.DROPPED).size
        )
        assertTrue(
            "the added elements are at least the day's plan content",
            reconciled.report.ofKind(ChangeKind.ADDED).size >= 2
        )
        assertEquals(
            "and exactly one element is reported as preserved from the user",
            1,
            reconciled.report.changes.count {
                it.kind == ChangeKind.PRESERVED && it.programExerciseId == ProgramExerciseId("authored-1")
            }
        )
        assertEquals(
            "the user's element is still first in the day",
            "authored-1",
            reconciled.days.first().exercises.first().programExerciseId.value
        )
    }

    @Test
    fun theReportAccountsForEveryElementOfTheDraftExactlyOnce() {
        val plan = plan()
        val draft = draftOf(
            day(element("a", "push-pushup"), element("b", "legs-squat", pinned = true)),
            day(element("c", "mobility-spine-wave"))
        )

        val reconciled = PlanReconciler.reconcile(draft, plan, ids("new"))

        val accountedFor = reconciled.report.changes
            .mapNotNull { it.programExerciseId }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
        assertEquals(
            "no element of the draft is accounted for twice, so a report cannot say two contradictory " +
                "things about one element",
            emptyMap<ProgramExerciseId, Int>(),
            accountedFor
        )
        assertEquals(
            "and every element of the draft appears somewhere in the report",
            setOf("a", "b", "c"),
            reconciled.report.changes.mapNotNull { it.programExerciseId?.value }
                .filter { it.length == 1 }
                .toSet()
        )
    }

    // ------------------------------------------------------------------ fixtures

    /** The plan the generated planner produces for the rig's balanced request. */
    private fun plan(): GeneratedPlan = GeneratedPlanner.plan(GeneratedPlannerRig.request())

    private fun draftOf(vararg days: ProgramDay): ProgramEditorDraft = ProgramEditorDraft(
        name = "Generated program",
        mode = com.monkfitness.app.domain.program.ProgramMode.GENERATED,
        days = days.toList().mapIndexed { index, day -> day.copy(position = index + 1) }
    )

    /** One draft day: the elements are re-identified into it, so a fixture is one deliberate thing. */
    private fun day(vararg elements: ProgramExercise, position: Int = 1): ProgramDay = ProgramDay(
        programDayId = ProgramDayId("day-$position"),
        position = position,
        type = ProgramDayType.TRAINING,
        exercises = elements.toList()
    )

    private fun element(
        id: String,
        exerciseId: String,
        prescription: com.monkfitness.app.domain.prescription.Prescription =
            RepPrescription(listOf(10)),
        origin: ProgramExerciseOrigin = ProgramExerciseOrigin.GENERATED,
        pinned: Boolean = false
    ): ProgramExercise = ProgramExercise(
        programExerciseId = ProgramExerciseId(id),
        exerciseId = exerciseId,
        prescription = prescription,
        origin = origin,
        isPinned = pinned
    )

    /** Identity minted by a test, so a failure message names the identity the reconciler produced. */
    private fun ids(prefix: String = "id"): DraftIdSource {
        val counter = intArrayOf(0)
        return DraftIdSource { counter[0] += 1; "$prefix-${counter[0]}" }
    }
}
