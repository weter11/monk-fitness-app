package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.workout.EffectiveWorkout
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.presentedWorkout
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.workout.EffectiveExercise
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P29's **focus attribution**: the one place where the semantic link between a generated plan and a
 * performed exercise is created.
 *
 * ### The gap this stage found, which was not the gap P27 recorded
 *
 * P27 left `recentExposureByFocus` and `recentLoadByFocus` neutral and wrote down why: *"no object on
 * the way from a plan to a performed set carries a focus"*. That statement was **true and incomplete**.
 * The focus did exist — `GeneratedElement.focus`, assigned by the Focus Planner, asserted by
 * `GeneratedSlot` to be exactly the assignment's focuses in order — and `PlanReconciler` dropped it in
 * the very next step, when it materialised the element into a `ProgramExercise`.
 *
 * So the defect was a **discarded fact**, not a missing one, and the difference decides the fix:
 *
 * ```text
 * missing fact   → the only way forward is to invent a proxy (P27 correctly refused)
 * discarded fact → the only way forward is to stop discarding it (this suite)
 * ```
 *
 * A proxy would have been `ProductionFocusClassification`: classify a performed exercise by what the
 * catalogue says it trains. That is forbidden here structurally, not merely by convention —
 * `theContextSourceStatesNoRecoveryAndNoFocusDerivedSignal` bans the token in the context source, and
 * `theOnlyPlaceThatCopiesAGeneratedFocusIsTheReconciler` (architecture suite) bans it everywhere else.
 *
 * ### What this suite asserts, claim by claim
 *
 * | claim | test |
 * | --- | --- |
 * | the plan element's focus survives reconciliation | `theGeneratedElementsFocusSurvivesMaterialisation` |
 * | the **primary** focus is what is recorded | `thePrimaryFocusOfAnAssignmentIsTheRecordedFocus` |
 * | a **secondary** focus is recorded on its own element, not folded into the primary | `eachSecondaryFocusIsRecordedOnItsOwnElement` |
 * | **assignment order** is preserved end to end | `theAssignmentOrderSurvivesIntoThePresentation` |
 * | no default focus is invented for an unassigned element | `anElementWithNoAssignmentRecordsNoFocus` |
 * | an **adjusted** element keeps the focus of its assignment | `anAdjustmentChangesWhatIsShownAndNeverWhichFocusItServes` |
 * | an adjusted element is never left focus-less | `aStandingAdjustmentNeverErasesTheRecordedFocus` |
 */
class AdaptiveFocusAttributionTest {

    // ------------------------------------------------------------------ the discarded fact is now kept

    @Test
    fun theGeneratedElementsFocusSurvivesMaterialisation() {
        // The whole stage in one assertion: what the planner assigned is what the draft element says.
        val plan = generatedPlan(
            assignment = FocusAssignment(primary = Focus.PUSH, secondary = listOf(Focus.CORE))
        )

        val reconciled = PlanReconciler.reconcile(draftOf(), plan, ids())

        assertEquals(
            "every generated element carries the focus the plan assigned it — before P29 this was " +
                "silently dropped and no downstream object could honestly state a historical focus",
            listOf(Focus.PUSH, Focus.CORE),
            reconciled.days.first().exercises.map { element -> element.focus }
        )
    }

    @Test
    fun thePrimaryFocusOfAnAssignmentIsTheRecordedFocus() {
        // A single-focus slot is the ordinary case, and the one where a "primary" and a "first element"
        // could be confused. They are the same value only because §8's contract says the first element
        // serves the primary — which is asserted here rather than assumed.
        val plan = generatedPlan(assignment = FocusAssignment(primary = Focus.LEGS))

        val elements = PlanReconciler.reconcile(draftOf(), plan, ids()).days.first().exercises

        assertEquals(
            "the one element of a primary-only slot records the primary focus itself",
            listOf(Focus.LEGS),
            elements.map { it.focus }
        )
        assertEquals(
            "and it is exactly the assignment's primary, not merely its first element",
            plan.slots.first().assignment.primary,
            elements.single().focus
        )
    }

    @Test
    fun eachSecondaryFocusIsRecordedOnItsOwnElement() {
        // The claim that a secondary is NOT lost, and — just as load-bearing — that it is not recorded
        // as the primary. A slot with `PUSH + PULL + CORE` plans three elements; each names its own focus,
        // so "which focus was this exercise for" has one answer per exercise rather than a set.
        val plan = generatedPlan(
            assignment = FocusAssignment(primary = Focus.PUSH, secondary = listOf(Focus.PULL, Focus.CORE))
        )

        val elements = PlanReconciler.reconcile(draftOf(), plan, ids()).days.first().exercises

        assertEquals(
            "three assigned focuses, three elements, each with its own recorded focus",
            3,
            elements.size
        )
        assertEquals(
            "and the recorded focuses are the assignment's, in the assignment's own order",
            listOf(Focus.PUSH, Focus.PULL, Focus.CORE),
            elements.map { it.focus }
        )
        assertEquals(
            "no element claims the primary on behalf of a secondary: each focus appears exactly once, so " +
                "an exposure counted per element can never double-count one assignment",
            listOf(Focus.PUSH, Focus.PULL, Focus.CORE).distinct().size,
            elements.mapNotNull { it.focus }.distinct().size
        )
    }

    @Test
    fun theAssignmentOrderSurvivesIntoThePresentation() {
        // End to end within the domain: plan → draft element → what a session would be shown. If the
        // order is lost here, then "which element was the secondary" is unanswerable at the snapshot
        // boundary, and §19 would freeze an ambiguous history.
        val plan = generatedPlan(
            assignment = FocusAssignment(primary = Focus.PULL, secondary = listOf(Focus.LEGS))
        )

        val elements = PlanReconciler.reconcile(draftOf(), plan, ids()).days.first().exercises
        val presented = presentedWorkout(dayOf(elements), slot(), standing = emptyList(), computedAt = BASE)

        assertEquals(
            "the presentation keeps the order, and with it each element's own focus",
            listOf(Focus.PULL, Focus.LEGS),
            presented.exercises.map { it.focus }
        )
    }

    // ------------------------------------------------------------------ absence is never a default

    @Test
    fun anElementWithNoAssignmentRecordsNoFocus() {
        // A manual program, or a user-authored element inside a generated one: the generator never said
        // anything about its focus, so nothing is recorded. This is the row that must read back as
        // absence all the way to generation context — never as "trains PUSH", and never as a zero.
        val authored = ProgramExercise(
            programExerciseId = ProgramExerciseId("authored-1"),
            exerciseId = "legs-squat",
            prescription = RepPrescription(listOf(10)),
            origin = ProgramExerciseOrigin.USER_AUTHORED
        )
        assertNull(
            "a user-authored element states no focus by construction, and that is the honest default-free " +
                "reading — `null` is absence, not a claim",
            authored.focus
        )

        // And it survives reconciliation as absence: the plan's own elements get focuses, the user's does
        // not, and nothing invents one for it.
        val plan = generatedPlan(assignment = FocusAssignment(primary = Focus.PUSH))
        val reconciled = PlanReconciler.reconcile(
            draftOf(dayOf(listOf(authored))),
            plan,
            ids()
        )

        assertEquals(
            "the preserved user element still states no focus after a regeneration — reconciliation " +
                "preserves what the user owns, and a focus the generator never assigned is not theirs to " +
                "take",
            null,
            reconciled.days.first().exercises.first { it.programExerciseId == authored.programExerciseId }.focus
        )
        assertNotNull(
            "while the plan's own added element does carry one, so the absence is the user's and not a " +
                "dropped value on every element",
            reconciled.days.first().exercises.first { it.focus != null }.focus
        )
    }

    // ------------------------------------------------------------------ an adjustment is not a reassignment

    @Test
    fun anAdjustmentChangesWhatIsShownAndNeverWhichFocusItServes() {
        // §16's rule: an adjustment supersedes the *presentation* of an element. It does not re-decide the
        // slot's assignment, so an adjusted occurrence still trains the focus its element was planned for.
        val plan = generatedPlan(assignment = FocusAssignment(primary = Focus.CORE))
        val elements = PlanReconciler.reconcile(draftOf(), plan, ids()).days.first().exercises
        val target = elements.single()

        val presented = presentedWorkout(
            dayOf(elements),
            slot(),
            standing = listOf(
                adjustmentOf(
                    target.programExerciseId,
                    before = EffectiveExercise(
                        target.programExerciseId,
                        target.exerciseId,
                        target.prescription
                    ),
                    after = EffectiveExercise(
                        target.programExerciseId,
                        "core-plank",
                        TimePrescription(listOf(45))
                    )
                )
            ),
            computedAt = BASE
        )

        assertEquals(
            "the adjusted exercise is the one the user is shown",
            "core-plank",
            presented.exercises.single().exerciseId
        )
        assertEquals(
            "and the focus it is presented under is still the assignment's own — an adjustment changes " +
                "what a slot presents, never what the slot is for (§16)",
            Focus.CORE,
            presented.exercises.single().focus
        )
    }

    @Test
    fun aStandingAdjustmentNeverErasesTheRecordedFocus() {
        // The negative form of the rule above, and the one that actually protects history: the stored
        // adjustment carries no focus of its own, so reading the presented element from the adjustment
        // would produce a focus-less element and §19 would freeze that absence into the snapshot forever.
        val plan = generatedPlan(assignment = FocusAssignment(primary = Focus.LEGS))
        val elements = PlanReconciler.reconcile(draftOf(), plan, ids()).days.first().exercises
        val target = elements.single()

        val presented = presentedWorkout(
            dayOf(elements),
            slot(),
            standing = listOf(
                adjustmentOf(
                    target.programExerciseId,
                    before = EffectiveExercise(
                        target.programExerciseId,
                        target.exerciseId,
                        target.prescription
                    ),
                    after = EffectiveExercise(
                        target.programExerciseId,
                        "legs-lunge",
                        RepPrescription(listOf(12))
                    )
                )
            ),
            computedAt = BASE
        )

        assertEquals(
            "an adjusted element keeps the focus of the element it adjusts; the adjustment's own `after` " +
                "value is a presentation, not an assignment",
            listOf(Focus.LEGS),
            presented.exercises.mapNotNull { it.focus }
        )
    }

    // ------------------------------------------------------------------ the snapshot boundary

    @Test
    fun theHistoricalSnapshotStatesTheFocusThePresentationCarried() {
        // The fact §19 freezes: an `EffectiveWorkout` presented from a revision that has focuses states
        // them, and that is what a snapshot element copies. Nothing here reads a revision, a catalogue or
        // an adaptive state — the value is already on the presentation.
        val plan = generatedPlan(
            assignment = FocusAssignment(primary = Focus.PUSH, secondary = listOf(Focus.CORE))
        )
        val elements = PlanReconciler.reconcile(draftOf(), plan, ids()).days.first().exercises

        val workout: EffectiveWorkout = presentedWorkout(dayOf(elements), slot(), emptyList(), BASE)

        assertEquals(
            "every presented element states the focus it was presented under, which is precisely what " +
                "`session_snapshot_exercise.focus` copies at session start",
            listOf(Focus.PUSH, Focus.CORE),
            workout.exercises.map { it.focus }
        )
    }

    @Test
    fun theGeneratedPackageGainedNoFocusVocabularyOfItsOwn() {
        // The recorded focus is §8's `Focus`, by identity — not a new token type, not a string, and not a
        // second vocabulary that could drift from the Focus Planner's.
        val reconciled = PlanReconciler.reconcile(
            draftOf(),
            generatedPlan(assignment = FocusAssignment(primary = Focus.PULL)),
            ids()
        )

        val recorded = reconciled.days.first().exercises.single().focus
        assertEquals(
            "the value is the Focus Planner's own enum value, stored exactly",
            Focus.PULL,
            recorded
        )
        assertTrue(
            "and it compares equal to the vocabulary itself, which is what makes it the same fact rather " +
                "than a lookalike: a second token type would still hold a `Focus.PULL` by name",
            Focus.entries.contains(recorded)
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun assertTrue(message: String, condition: Boolean) =
        org.junit.Assert.assertTrue(message, condition)

    private companion object {

        val BASE: Instant = Instant.parse("2026-09-21T07:00:00Z")

        /**
         * A plan with exactly one slot carrying [assignment] and exactly one element per assigned focus.
         *
         * Built through the real [FocusAssignment] and [GeneratedElement] types rather than by mocking
         * them, so the fixture cannot drift into a shape the planner would refuse: `GeneratedSlot`
         * asserts that a slot's elements are exactly its assignment's focuses, in order.
         */
        fun generatedPlan(assignment: FocusAssignment): GeneratedPlan = GeneratedPlan(
            focus = FocusPlan.Focused(listOf(assignment.primary) + assignment.secondary),
            slots = listOf(
                GeneratedSlot(
                    position = 1,
                    assignment = assignment,
                    elements = assignment.focuses.map { focus ->
                        GeneratedElement(
                            focus = focus,
                            exerciseId = "exercise-$focus",
                            familyId = "family-$focus",
                            prescription = RepPrescription(listOf(10)) as Prescription
                        )
                    }
                )
            )
        )

        fun draftOf(vararg days: ProgramDay): ProgramEditorDraft = ProgramEditorDraft(
            name = "Generated program",
            mode = ProgramMode.GENERATED,
            days = days.toList()
        )

        fun dayOf(elements: List<ProgramExercise>): ProgramDay = ProgramDay(
            programDayId = ProgramDayId("day-1"),
            position = 1,
            type = ProgramDayType.TRAINING,
            exercises = elements
        )

        fun slot() = com.monkfitness.app.domain.program.WorkoutSlot(
            slotId = SlotId("slot-1"),
            programId = ProgramId("program-1"),
            revisionId = RevisionId("revision-1"),
            programDayId = ProgramDayId("day-1"),
            plannedFor = LocalDate.parse("2026-09-21"),
            status = com.monkfitness.app.domain.program.SlotStatus.PLANNED
        )

        fun adjustmentOf(
            programExerciseId: ProgramExerciseId,
            before: EffectiveExercise,
            after: EffectiveExercise
        ): AdaptiveAdjustment = AdaptiveAdjustment(
            adjustmentId = AdjustmentId("adjustment-1"),
            decisionId = DecisionId("decision-1"),
            slotId = SlotId("slot-1"),
            before = before,
            after = after,
            createdAt = BASE
        )

        fun ids(prefix: String = "p29"): DraftIdSource {
            val counter = intArrayOf(0)
            return DraftIdSource { counter[0] += 1; "$prefix-${counter[0]}" }
        }
    }
}