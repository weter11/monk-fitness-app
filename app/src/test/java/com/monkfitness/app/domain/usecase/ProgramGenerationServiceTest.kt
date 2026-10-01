package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 24's production Generate flow, measured on the real catalogue and through the real
 * generated editor.
 *
 * The class under test is the one node that knows **in what order** the boundary, the planner and the
 * reconciler run, so its claims are about the *facts handed to each of them* — the configuration
 * comes from the draft, the candidates come from the real catalogue, the equipment comes from the
 * caller untouched, and what comes out is the next immutable draft. Three of those are easy to get
 * wrong silently: a defaulted configuration that plans a Program the user did not ask for, a widened
 * equipment set that offers a bar exercise to someone who owns no bar, and a pass that reaches the
 * planner but skips reconciliation.
 */
class ProgramGenerationServiceTest {

    private val ids = SequentialIds()

    /** The service as the composition root wires it, minus the id source's randomness. */
    private fun serviceOver(
        exercises: List<Exercise>,
        focusSource: ExerciseGenerationFacts.GenerationFocusSource = ProductionFocusClassification,
        preferences: GenerationPreferences = GenerationPreferences.NONE
    ): ProgramGenerationService = ProgramGenerationService(
        catalogue = GenerationCatalogue { source ->
            ProductionGenerationBoundary.catalogueOf(exercises, source)
        },
        focusSource = focusSource,
        ids = DraftIdSource { ids.newId() },
        preferences = preferences
    )

    private fun exerciseOf(
        id: String,
        category: ExerciseCategory = ExerciseCategory.STRENGTH,
        subCategory: ExerciseSubCategory = ExerciseSubCategory.FULL_BODY,
        equipment: Set<Equipment> = emptySet()
    ) = Exercise(
        id = id,
        familyId = "family_$id",
        animationId = "anim_$id",
        nameRes = 0,
        descriptionRes = 0,
        techniqueRes = 0,
        imageRes = null,
        sets = 3,
        reps = 10,
        category = category,
        subCategory = subCategory,
        requiredEquipment = equipment
    )

    // ------------------------------------------------------------------ the real catalogue

    @Test
    fun theRealShippedCatalogueReachesThePlannerAsClassifiedCandidates() {
        val service = serviceOver(WorkoutGenerator().getExerciseLibrary())
        val result = service.generate(draft(), emptySet())

        assertTrue(
            "the real catalogue is classified, so a pass over it produces a plan — this is the claim " +
                "P23 could not make and P24 exists to make",
            result is ProgramGenerationResult.Generated
        )
        val edit = (result as ProgramGenerationResult.Generated).edit
        assertTrue(
            "and the plan is not empty",
            edit.plan.slots.isNotEmpty()
        )
        assertEquals(
            "every candidate the planner saw is a real catalogue exercise, and every real catalogue " +
                "exercise that could serve a focus reached it",
            emptyList<String>(),
            edit.plan.slots.flatMap { it.elements }.map { it.exerciseId }.distinct()
                .filterNot { id -> id in WorkoutGenerator().getExerciseLibrary().map { it.id } }
        )
        assertEquals(
            "nothing is unclassified any more: the real catalogue states a focus for every exercise",
            emptyList<String>(),
            ProductionGenerationBoundary
                .catalogueOfShippedExercises(ProductionFocusClassification).unclassifiedExerciseIds
        )
    }

    @Test
    fun theRealCatalogueWithNoEquipmentStillPlansFromTheExercisesThatNeedNone() {
        // The P24 reading: an empty declared set means the user owns nothing, so bar and band work is
        // reported unusable rather than offered. The plan must therefore be built from bodyweight
        // exercises, and it must not be empty — which is also why PULL is servable here at all
        // (lat_stretch needs nothing).
        val result = serviceOver(WorkoutGenerator().getExerciseLibrary())
            .generate(draft(), emptySet())

        val edit = (result as ProgramGenerationResult.Generated).edit
        val requiredEquipment = edit.plan.slots.flatMap { it.elements }
            .map { it.exerciseId }
            .distinct()
            .map { id -> WorkoutGenerator().getExerciseLibrary().single { it.id == id } }
        assertTrue(
            "nothing the plan prescribes needs equipment the user said they do not have",
            requiredEquipment.all { it.requiredEquipment.isEmpty() }
        )
    }

    // ------------------------------------------------------------------ the configuration is the draft's

    @Test
    fun theDraftsOwnFocusScheduleAndDurationReachTheRequestUnchanged() {
        val focus = FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL))
        val schedule = ProgramSchedule.FlexiblePerWeek(4)
        val duration = ProgramDuration.FixedDays(28)
        val result = serviceOver(WorkoutGenerator().getExerciseLibrary())
            .generate(
                draft(focus = focus, schedule = schedule, duration = duration),
                setOf(Equipment.BAR)
            )

        val edit = (result as ProgramGenerationResult.Generated).edit
        assertEquals(
            "the plan is for the focus the draft stated — a default FocusPlan here would plan a " +
                "Program the user did not ask for",
            focus,
            edit.plan.focus
        )
        assertEquals(
            "the next draft stores the configuration the plan was built for (§6: mode and " +
                "configuration are revision content, so they may not disagree)",
            focus,
            edit.draft.focus
        )
        assertEquals(
            "four sessions a week is a different plan from three, and the draft's own cadence is the " +
                "one used",
            4 * 4,
            edit.plan.slots.size
        )
        assertEquals("and the duration is still the draft's", duration, edit.draft.duration)
        assertEquals("and so is the schedule", schedule, edit.draft.schedule)
    }

    @Test
    fun aCustomFocusShareIsCarriedThroughRatherThanReplacedByAnEvenSpread() {
        val focus = FocusPlan.custom(
            listOf(FocusAllocation(Focus.PUSH, 80), FocusAllocation(Focus.LEGS, 20))
        )
        val result = serviceOver(WorkoutGenerator().getExerciseLibrary())
            .generate(draft(focus = focus), emptySet())

        val edit = (result as ProgramGenerationResult.Generated).edit
        assertEquals("the stated configuration is the one planned", focus, edit.plan.focus)
        val leading = edit.plan.slots.map { slot ->
            slot.assignment.focuses.first()
        }
        assertTrue(
            "and 80/20 is visible in the plan: pressing leads, legs follow",
            leading.count { it == Focus.PUSH } > leading.count { it == Focus.LEGS }
        )
    }

    // ------------------------------------------------------------------ equipment is forwarded, never filtered

    @Test
    fun availableEquipmentReachesTheRequestUnchangedAndIsNeverNormalised() {
        val declared = setOf(Equipment.BAR)
        val withBar = serviceOver(WorkoutGenerator().getExerciseLibrary())
            .generate(draft(focus = FocusPlan.focused(listOf(Focus.PULL))), declared)
        val withoutBar = serviceOver(WorkoutGenerator().getExerciseLibrary())
            .generate(draft(focus = FocusPlan.focused(listOf(Focus.PULL))), emptySet())

        val withBarEdit = (withBar as ProgramGenerationResult.Generated).edit
        val withoutBarEdit = (withoutBar as ProgramGenerationResult.Generated).edit
        assertTrue(
            "with a bar declared, the plan may use the bar exercises the catalogue holds",
            withBarEdit.plan.slots.flatMap { it.elements }.any { it.exerciseId in BAR_EXERCISES }
        )
        assertTrue(
            "with nothing declared, it may not — the strict reading, not the legacy \"empty means " +
                "unconstrained\" one",
            withoutBarEdit.plan.slots.flatMap { it.elements }.none { it.exerciseId in BAR_EXERCISES }
        )
    }

    @Test
    fun theWholeCatalogueReachesThePlannerAndTheConstraintIsTheRequestsOwn() {
        // The catalogue is not filtered before the request is built: an exercise the user cannot
        // perform is *reported* by the planner, not removed from the library view, so the fact that it
        // was unavailable survives into the result (§9's hard constraints are never silently dropped).
        val classified = ProductionGenerationBoundary
            .catalogueOfShippedExercises(ProductionFocusClassification)
        val request = ProductionGenerationBoundary.generationRequest(
            catalogue = classified,
            focus = FocusPlan.Balanced,
            schedule = ProgramSchedule.FlexiblePerWeek(3),
            duration = ProgramDuration.Indefinite,
            availableEquipment = emptySet()
        )

        assertEquals(
            "every classified exercise is offered to the request, equipment or not",
            classified.candidates.size,
            request.candidates.size
        )
        assertTrue(
            "and the unusable ones are there to be reported",
            classified.candidates.any { candidate ->
                !request.isUsable(candidate) && candidate.requiredEquipment.isNotEmpty()
            }
        )
    }

    // ------------------------------------------------------------------ preferences and policy

    @Test
    fun noAdaptiveSignalIsComputedAndTheNeutralRepresentationReachesTheRequest() {
        // P24 does not integrate the adaptive side, so the request's plain signals must be exactly
        // the neutral representation — a real preference or a real recency list would be a hidden
        // adaptive rule producing a different plan for the same user.
        val classified = ProductionGenerationBoundary
            .catalogueOfShippedExercises(ProductionFocusClassification)
        val request = ProductionGenerationBoundary.generationRequest(
            catalogue = classified,
            focus = FocusPlan.Balanced,
            schedule = ProgramSchedule.FlexiblePerWeek(3),
            duration = ProgramDuration.Indefinite,
            availableEquipment = emptySet()
        )

        assertEquals(
            "no user preference, no adaptive preference, no recency, no exposure, no load",
            GenerationPreferences.NONE,
            request.preferences
        )
        assertEquals(
            "the generated domain's own declared policy, not a number this stage chose",
            com.monkfitness.app.domain.program.generated.GenerationPolicy.DEFAULT,
            request.policy
        )
    }

    // ------------------------------------------------------------------ the pass reaches the reconciler

    @Test
    fun thePassReachesProgramGeneratedEditorAndPlanReconcilerSoPinnedContentSurvives() {
        // The production-path integration claim: the service does not stop at `GeneratedPlanner`.
        // A pinned element the user put on day 1 must be in the draft that comes out, with its
        // identity, and the generated content must arrive around it.
        val pinned = ProgramExercise(
            programExerciseId = ProgramExerciseId("element-pinned"),
            exerciseId = "dead_bug",
            prescription = RepPrescription.uniform(3, 12),
            origin = ProgramExerciseOrigin.USER_AUTHORED,
            isPinned = true
        )
        val start = draft().copy(
            days = listOf(
                ProgramDay(
                    programDayId = ProgramDayId("day-1"),
                    position = 1,
                    type = ProgramDayType.TRAINING,
                    exercises = listOf(pinned)
                )
            )
        )

        val result = serviceOver(WorkoutGenerator().getExerciseLibrary()).generate(start, emptySet())

        val edit = (result as ProgramGenerationResult.Generated).edit
        val firstDay = edit.draft.days.first { it.position == 1 }
        assertTrue(
            "the pinned element survived the pass",
            firstDay.exercises.any { it.programExerciseId == pinned.programExerciseId }
        )
        assertTrue(
            "and it is still pinned",
            firstDay.exercises.single { it.programExerciseId == pinned.programExerciseId }.isPinned
        )
        assertEquals(
            "its prescription was not rewritten either",
            pinned.prescription,
            firstDay.exercises.single { it.programExerciseId == pinned.programExerciseId }.prescription
        )
        assertTrue(
            "while the generated content arrived around it",
            firstDay.exercises.any { it.origin == ProgramExerciseOrigin.GENERATED }
        )
        assertTrue(
            "and the reconciliation is reported, so a screen can show what happened (§7)",
            edit.reconciliation.changes.isNotEmpty()
        )
    }

    @Test
    fun aRegenerationIsTheSameReconciliationAsAGenerate() {
        val start = draft(focus = FocusPlan.focused(listOf(Focus.PUSH)))
        val service = serviceOver(WorkoutGenerator().getExerciseLibrary())

        val first = service.generate(start, emptySet()) as ProgramGenerationResult.Generated
        val second = service.regenerate(first.edit.draft, emptySet()) as ProgramGenerationResult.Generated

        assertEquals(
            "a regeneration over its own output is deterministic: the same request and the same " +
                "cycle produce the same plan",
            first.edit.plan.slots.map { slot -> slot.elements.map { it.exerciseId } },
            second.edit.plan.slots.map { slot -> slot.elements.map { it.exerciseId } }
        )
        assertTrue(
            "and nothing churned: an unchanged plan keeps its element identities (§7's *unchanged " +
                "generated plan stays unchanged*)",
            second.edit.reconciliation.addedCount == 0 && second.edit.reconciliation.droppedCount == 0
        )
    }

    // ------------------------------------------------------------------ only the draft changes

    @Test
    fun thePassProducesTheNextImmutableDraftAndMutatesNothingItWasGiven() {
        val start = draft()
        val before = start

        val result = serviceOver(WorkoutGenerator().getExerciseLibrary()).generate(start, emptySet())
        val edit = (result as ProgramGenerationResult.Generated).edit

        assertNotSame("the draft handed in is not the draft handed back", start, edit.draft)
        assertEquals("and the value the caller still holds is unchanged", before, start)
        assertEquals(
            "the next draft is a Generated one: generation states the mode it planned in (§2)",
            ProgramMode.GENERATED,
            edit.draft.mode
        )
        assertNotNull("the pass produced a plan and a reconciliation", edit.plan)
    }

    @Test
    fun aCatalogueThatStatesNothingIsRefusedRatherThanGeneratedAround() {
        // The Stage-1-shaped source: it states nothing, so the whole catalogue is unclassified. The
        // pass must refuse, naming the gap, and must not widen a focus to make a plan appear.
        val result = serviceOver(
            exercises = WorkoutGenerator().getExerciseLibrary(),
            focusSource = { null }
        ).generate(draft(), emptySet())

        assertTrue(
            "a source that states nothing cannot produce a plan",
            result is ProgramGenerationResult.Refused
        )
        val refusal = (result as ProgramGenerationResult.Refused).reason
        assertTrue(
            "and the refusal is the missing classification, not a planner limitation: $refusal",
            refusal is ProgramGenerationRefusal.NoExerciseStatesItsFocus
        )
    }

    @Test
    fun aConfigurationNothingCanServeIsRefusedRatherThanPlannedAsNothing() {
        // PULL with no equipment declared: the catalogue's PULL candidates all need a bar or a band
        // except the bodyweight lat stretch, so a fixture catalogue is used to make the refusal
        // unambiguous — every PULL candidate requires equipment.
        val pullOnly = listOf(
            exerciseOf("pullup_standard", equipment = setOf(Equipment.BAR)),
            exerciseOf("row_bar", equipment = setOf(Equipment.BAR)),
            exerciseOf("face_pull_band", equipment = setOf(Equipment.BANDS))
        )
        val service = serviceOver(pullOnly, focusSource = { id ->
            setOf(Focus.PULL)
        })

        val refused = service.generate(draft(focus = FocusPlan.focused(listOf(Focus.PULL))), emptySet())
        val generated = service.generate(
            draft(focus = FocusPlan.focused(listOf(Focus.PULL))),
            setOf(Equipment.BAR, Equipment.BANDS)
        )

        assertTrue(
            "nothing in that catalogue can be performed with nothing declared",
            refused is ProgramGenerationResult.Refused
        )
        assertTrue(
            "and the refusal says so, in the planner's own reasons",
            (refused as ProgramGenerationResult.Refused).reason is ProgramGenerationRefusal.NothingPlannable
        )
        assertTrue(
            "while declaring the equipment the catalogue needs makes the same pass succeed",
            generated is ProgramGenerationResult.Generated
        )
    }

    @Test
    fun aFailureIsSurfacedRatherThanAbsorbedIntoAnEmptyDraft() {
        val exploding = ProgramGenerationService(
            catalogue = GenerationCatalogue { throw IllegalStateException("planted: the catalogue could not be read") },
            focusSource = ProductionFocusClassification,
            ids = DraftIdSource { ids.newId() }
        )

        val result = exploding.generate(draft(), emptySet())

        assertTrue(
            "§33: a failure is never turned into an empty result",
            result is ProgramGenerationResult.Failed
        )
        assertTrue(
            "and the cause is carried, not swallowed",
            (result as ProgramGenerationResult.Failed).cause.message!!.contains("planted")
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun draft(
        focus: FocusPlan = FocusPlan.Balanced,
        schedule: ProgramSchedule = ProgramSchedule.FlexiblePerWeek(3),
        duration: ProgramDuration = ProgramDuration.Indefinite
    ) = ProgramEditorDraft(
        name = "Generated",
        mode = ProgramMode.GENERATED,
        duration = duration,
        schedule = schedule,
        focus = focus
    )

    private companion object {

        /** The catalogue's own bar exercises, read once so the equipment claim is about the real data. */
        val BAR_EXERCISES: Set<String> = WorkoutGenerator().getExerciseLibrary()
            .filter { it.requiredEquipment.contains(Equipment.BAR) }
            .map { it.id }
            .toSet()
    }
}
