package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.ExercisePreference
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.SessionType
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.program.generated.GeneratedDraftEdit
import com.monkfitness.app.domain.program.generated.GenerationLimitation
import com.monkfitness.app.domain.program.generated.GenerationPolicy
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import com.monkfitness.app.domain.program.generated.GeneratedPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **session-type selection**, measured through the production Generate flow on the real catalogue.
 *
 * The claims are the ones a filter can get wrong silently: a selection that lets a non-member through, a
 * union that quietly collapses to one of its parts, an empty selection that plans something different from
 * the pipeline before the selection existed, and the two facts the preview has to say out loud — a focus the
 * pool cannot serve and a preferred exercise the selection puts out of reach.
 */
class ProgramGenerationSessionTypeTest {

    private val catalogue = WorkoutGenerator().getExerciseLibrary()

    private val ids = SequentialIds()

    private fun service() = ProgramGenerationService(
        catalogue = GenerationCatalogue { source ->
            ProductionGenerationBoundary.catalogueOf(catalogue, source)
        },
        focusSource = ProductionFocusClassification,
        ids = DraftIdSource { ids.newId() },
        context = GenerationContextSource { GenerationPreferences.NONE }
    )

    private fun draft(
        sessionTypes: Set<SessionType>,
        focus: FocusPlan = FocusPlan.Balanced,
        preferredExercises: ExercisePreference = ExercisePreference.NONE
    ) = ProgramEditorDraft(
        name = "Generated",
        mode = ProgramMode.GENERATED,
        focus = focus,
        preferredExercises = preferredExercises,
        sessionTypes = sessionTypes
    )

    private fun editOf(draft: ProgramEditorDraft): GeneratedDraftEdit {
        val result = runBlocking { service().generate(draft, emptySet()) }
        assertTrue(
            "the pass planned something: ${(result as? ProgramGenerationResult.Refused)?.reason?.message}",
            result is ProgramGenerationResult.Generated
        )
        return (result as ProgramGenerationResult.Generated).edit
    }

    private fun membersOf(types: Set<SessionType>): Set<String> =
        catalogue.map { it.id }
            .filter { id ->
                ProductionSessionTypeClassification.sessionTypesOf(id).orEmpty().any { it in types }
            }
            .toSet()

    private fun exerciseIdsOf(edit: GeneratedDraftEdit): List<String> =
        edit.plan.slots.flatMap { slot -> slot.elements.map { element -> element.exerciseId } }

    @Test
    fun aSingleSelectionGeneratesOnlyItsOwnExercises() {
        val edit = editOf(draft(setOf(SessionType.STRENGTH)))
        val allowed = membersOf(setOf(SessionType.STRENGTH))

        assertTrue("the plan has elements", exerciseIdsOf(edit).isNotEmpty())
        assertEquals(
            "a plan drawn from strength alone may use nothing outside the strength membership",
            emptyList<String>(),
            exerciseIdsOf(edit).filterNot { it in allowed }
        )
    }

    @Test
    fun severalSelectionsCombineAsAUnion() {
        val selection = setOf(SessionType.STRENGTH, SessionType.MOBILITY)
        val edit = editOf(draft(selection))
        val used = exerciseIdsOf(edit)

        assertEquals(
            "the union, and nothing wider",
            emptyList<String>(),
            used.filterNot { it in membersOf(selection) }
        )
        assertTrue(
            "both halves of the union are actually drawn on, so the selection did not collapse to one type",
            used.any { id -> ProductionSessionTypeClassification.sessionTypesOf(id).orEmpty().contains(SessionType.STRENGTH) } &&
                used.any { id -> ProductionSessionTypeClassification.sessionTypesOf(id).orEmpty().contains(SessionType.MOBILITY) }
        )
    }

    @Test
    fun noSelectionPlansExactlyWhatTheUnfilteredPipelinePlans() {
        // The selection is a filter, so *no* selection must reduce to the pipeline exactly as it ran before
        // the filter existed — compared against that pipeline called directly, not against the service
        // called twice.
        val draft = draft(emptySet())
        val unrestricted = ProductionGenerationBoundary.catalogueOf(catalogue, ProductionFocusClassification)
        val request = ProductionGenerationBoundary.generationRequest(
            catalogue = unrestricted,
            focus = draft.focus,
            schedule = draft.schedule,
            duration = draft.duration,
            availableEquipment = emptySet()
        )

        assertEquals(
            "Mixed is the whole catalogue, so the plan is the one the unfiltered request produces",
            GeneratedPlanner.plan(request),
            editOf(draft).plan
        )
    }

    @Test
    fun aMixedSelectionCostsNothing() {
        val edit = editOf(draft(emptySet()))
        val selectionCosts = edit.plan.limitations.filter { limitation ->
            limitation is GenerationLimitation.ThinSelection ||
                limitation is GenerationLimitation.UnusedPreference
        }

        assertEquals(
            "with no selection there is no selection to pay for, and the draft stores the absence",
            emptyList<GenerationLimitation>(),
            selectionCosts
        )
    }

    @Test
    fun aPreferredExerciseOutsideTheSelectionIsReportedRatherThanDropped() {
        val edit = editOf(
            draft(
                sessionTypes = setOf(SessionType.STRENGTH),
                preferredExercises = ExercisePreference(listOf("cat_cow"))
            )
        )

        assertFalse(
            "an exercise outside the pool is not planned, however preferred",
            "cat_cow" in exerciseIdsOf(edit)
        )
        assertTrue(
            "and the plan says so instead of silently ignoring the preference: ${edit.plan.limitations}",
            GenerationLimitation.UnusedPreference("cat_cow") in edit.plan.limitations
        )
    }

    @Test
    fun aFocusTheSelectionCannotServeIsReportedAndTheRestIsStillPlanned() {
        // Balanced asks for all seven focuses; the hyperlordosis set trains legs, core and mobility and
        // nothing that presses, pulls or conditions — so the planner skips those three and says why.
        val edit = editOf(draft(setOf(SessionType.HYPERLORDOSIS)))

        assertTrue(
            "the plan is not refused: the focuses it can serve are planned",
            edit.plan.slots.isNotEmpty()
        )
        assertEquals(
            "and the three it cannot serve are reported, one by one",
            listOf(Focus.PUSH, Focus.PULL, Focus.CONDITIONING),
            edit.plan.limitations.filterIsInstance<GenerationLimitation.UnusableFocus>()
                .map { limitation -> limitation.focus }
        )
    }

    @Test
    fun aSelectionThinnerThanThePlanIsReportedAgainstItsDayCount() {
        val edit = editOf(draft(setOf(SessionType.HYPERLORDOSIS)))
        val days = 3 * GenerationPolicy.DEFAULT.indefiniteCycleWeeks

        assertTrue(
            "the six hyperlordosis exercises cannot fill a $days-day plan without repeating",
            GenerationLimitation.ThinSelection(SessionType.HYPERLORDOSIS, 6, days) in edit.plan.limitations
        )
    }

    @Test
    fun aSelectionWideEnoughForThePlanIsNotReportedAsThin() {
        val edit = editOf(draft(setOf(SessionType.STRENGTH)))

        assertFalse(
            "30 strength exercises fill a plan of ${3 * GenerationPolicy.DEFAULT.indefiniteCycleWeeks} " +
                "days without a guaranteed repeat, so nothing is claimed",
            edit.plan.limitations.any { it is GenerationLimitation.ThinSelection }
        )
    }
}
