package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.generated.GeneratedDraftEdit
import com.monkfitness.app.domain.program.generated.GenerationLimitation
import com.monkfitness.app.domain.program.generated.GenerationPolicy
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import com.monkfitness.app.domain.program.generated.ProgramGeneratedEditor
import com.monkfitness.app.domain.usecase.ExerciseGenerationFacts.GenerationFocusSource
import com.monkfitness.app.domain.usecase.ProductionGenerationBoundary.ProductionGenerationCatalogue

/**
 * The **production Generate flow**: the one application operation behind the Generated editor's `Generate`,
 * `Regenerate` and `Preview`.
 *
 * Every step of the chain already exists and belongs to a different owner — catalogue conversion and request
 * assembly to [ProductionGenerationBoundary], allocation and prescriptions to the generated domain,
 * preservation of pinned and user-authored content to [ProgramGeneratedEditor] with `PlanReconciler`. What
 * was missing is the node that says **in what order** they run and **which facts each is given**; without it
 * every caller had to know all of that, which is knowledge a screen and a controller must not hold.
 *
 * `focus`, `schedule` and `duration` are read from the draft and forwarded unchanged: they are the user's own
 * statements about this Program, and a pass that substituted a default would plan a Program the user did not
 * ask for while the draft stored the configuration they did. `mode` is not read — generation *states* it.
 *
 * `availableEquipment` goes into the request exactly as stated, with no filtering: the whole catalogue reaches
 * the planner and `GenerationRequest.isUsable` decides usability. **An empty set means the user declared no
 * equipment**, not *constrain nothing* — deliberately not the legacy `isAccessibleWith` rule, which would
 * hand a user who owns nothing a plan full of bar and band exercises.
 *
 * [context] is asked **once per pass** and its answer is forwarded untouched into
 * `GenerationRequest.preferences`, so a preview is never planned against one set of facts and applied against
 * another. [GenerationPreferences.NONE] is an **explicit neutral absence** for a draft with no Program or a
 * Program with no history, never a fabricated zero.
 *
 * It holds no repository, no DAO, no revision, no clock and no UI type: `Generate` and `Regenerate` only ever
 * alter a draft, and the only route to storage remains `ProgramEditorService.save`. The draft handed in is not
 * mutated — a caller that ignores the result has changed nothing.
 *
 * @param catalogue where the production catalogue is read. A port so a test can state a fixture catalogue,
 *   and so the legacy `WorkoutGenerator` stays named by [ProductionGenerationBoundary] alone.
 * @param focusSource where an exercise's focus membership is stated — [ProductionFocusClassification] in
 *   production.
 * @param context where the plain signals come from. Required rather than defaulted: a default here is exactly
 *   the quiet neutrality the read replaced, so every construction site must say where its facts come from.
 * @param policy the generated domain's own numbers, defaulted to [GenerationPolicy.DEFAULT].
 */
class ProgramGenerationService(
    private val catalogue: GenerationCatalogue,
    private val focusSource: GenerationFocusSource,
    private val ids: DraftIdSource,
    private val context: GenerationContextSource,
    private val policy: GenerationPolicy = GenerationPolicy.DEFAULT
) {

    /**
     * **Generate**: plans [draft] and reconciles the plan into it.
     *
     * @param availableEquipment the equipment the user has, forwarded into the request unchanged. An empty
     *   set means the user declared no equipment.
     */
    suspend fun generate(
        draft: ProgramEditorDraft,
        availableEquipment: Set<Equipment>
    ): ProgramGenerationResult = edit(draft, availableEquipment)

    /**
     * **Regenerate**: the same reconciliation, asked for a second time — deliberately the same operation as
     * [generate], because a regenerate that discarded pinned content (or a generate that kept content a
     * regenerate would replace) would make the meaning of a pin depend on which button the user pressed.
     */
    suspend fun regenerate(
        draft: ProgramEditorDraft,
        availableEquipment: Set<Equipment>
    ): ProgramGenerationResult = edit(draft, availableEquipment)

    /**
     * **Preview**: the same pass, offered for reading rather than adoption.
     *
     * Deliberately **not** a second pipeline — no `previewPlan`, no branch in the request assembly — so a
     * preview cannot be built from a different configuration, catalogue or policy than the Generate it
     * previews. What becomes of the returned value is the caller's single explicit decision; nothing here
     * mutates the draft.
     *
     * @param availableEquipment forwarded unchanged, exactly as [generate] forwards it.
     */
    suspend fun preview(
        draft: ProgramEditorDraft,
        availableEquipment: Set<Equipment>
    ): ProgramGenerationResult = edit(draft, availableEquipment)

    /**
     * The one pass all three buttons run: read the configuration, read the catalogue, read the context,
     * assemble the request, hand it to the generated editor.
     *
     * The two refusals are typed values, each decided **before** the value that would otherwise throw it, so
     * the caller is told what the gap is rather than handed a failure whose message explains it: a catalogue
     * that classified nothing is `NoExerciseStatesItsFocus`, and a configuration nothing usable can serve is
     * `NothingPlannable` — a plan of nothing must not read as a success, and keeping the draft the user has is
     * the better answer.
     *
     * [context] is asked once, inside the `try`, and passed down as an argument rather than re-read: no second
     * read, no second request-assembly path, so nothing downstream can observe different facts than the
     * request was built from. It is read *after* the catalogue check, so a pass that can plan nothing does not
     * go looking for history it will not use.
     */
    private suspend fun edit(
        draft: ProgramEditorDraft,
        availableEquipment: Set<Equipment>
    ): ProgramGenerationResult = try {
        val classified = catalogue.catalogueOf(focusSource)
        if (classified.candidates.isEmpty()) {
            ProgramGenerationResult.Refused(
                ProgramGenerationRefusal.NoExerciseStatesItsFocus(
                    unclassifiedExercises = classified.unclassifiedExerciseIds.size
                )
            )
        } else {
            planned(draft, availableEquipment, classified, context.preferencesFor(draft))
        }
    } catch (failure: Throwable) {
        ProgramGenerationResult.Failed(failure)
    }

    private fun planned(
        draft: ProgramEditorDraft,
        availableEquipment: Set<Equipment>,
        classified: ProductionGenerationCatalogue,
        preferences: GenerationPreferences
    ): ProgramGenerationResult {
        val request = ProductionGenerationBoundary.generationRequest(
            catalogue = classified,
            focus = draft.focus,
            schedule = draft.schedule,
            duration = draft.duration,
            availableEquipment = availableEquipment,
            preferences = preferences,
            policy = policy
        )
        val edit = ProgramGeneratedEditor(draft = draft, ids = ids).generate(request)
        return if (edit.plan.slots.isEmpty()) {
            ProgramGenerationResult.Refused(
                ProgramGenerationRefusal.NothingPlannable(
                    focus = draft.focus,
                    limitations = edit.plan.limitations
                )
            )
        } else {
            ProgramGenerationResult.Generated(edit)
        }
    }
}

/**
 * Where the exercises a generation pass may choose from are read, given the focus source that states what each
 * one trains.
 *
 * A port rather than a direct call to the boundary: a test states a fixture catalogue without a device, and
 * the legacy `WorkoutGenerator` stays named by the boundary alone — a second production reader would be a
 * second owner of the catalogue.
 */
fun interface GenerationCatalogue {

    /** The exercises this source offers, as the boundary's classified view of them. */
    fun catalogueOf(focusSource: GenerationFocusSource): ProductionGenerationCatalogue
}

/**
 * The production [GenerationCatalogue]: the app's own shipped exercises, read by the boundary.
 *
 * A value so the composition root passes a graph node straight on and a test wires a lambda; this is the only
 * place in production that says *the catalogue for generation is the shipped one*.
 */
val SHIPPED_EXERCISE_CATALOGUE: GenerationCatalogue = GenerationCatalogue { focusSource ->
    ProductionGenerationBoundary.catalogueOfShippedExercises(focusSource)
}

/**
 * What one generation pass did, in the three classes a caller must be able to tell apart.
 *
 * [Failed] is the system-failure class: something threw, surfaced rather than absorbed, because a draft that
 * silently lost the user's plan is the worst answer. [Refused] is the expected class: the app states no usable
 * exercise for this configuration, and the caller's draft is untouched.
 */
sealed interface ProgramGenerationResult {

    /**
     * The next draft, the plan that was built, and what reconciliation did — all three kept, because none is
     * derivable from the others: the conflict report needs the third, a preview screen the second.
     */
    data class Generated(val edit: GeneratedDraftEdit) : ProgramGenerationResult

    /** The pass was refused. Nothing was written and the caller's draft is unchanged. */
    data class Refused(val reason: ProgramGenerationRefusal) : ProgramGenerationResult

    /** The pass failed. Nothing was written, and the cause is surfaced rather than swallowed. */
    data class Failed(val cause: Throwable) : ProgramGenerationResult
}

/**
 * The rules that can refuse a generation pass, each with the sentence that explains it.
 *
 * Both are *absences of stated facts* rather than failures, and neither is ever answered by inventing a focus
 * membership, widening the equipment or generating an empty plan: "the app states nothing usable here" is a
 * typed refusal a UI can present.
 */
sealed interface ProgramGenerationRefusal {

    val message: String

    /**
     * No exercise in the catalogue states which focuses it trains, so there is nothing to generate from —
     * still reported as a refusal if the gap ever reopens.
     *
     * @property unclassifiedExercises how many catalogue entries stated nothing.
     */
    data class NoExerciseStatesItsFocus(val unclassifiedExercises: Int) : ProgramGenerationRefusal {
        override val message: String =
            "no exercise in the catalogue states which focuses it trains, so there is nothing to " +
                "generate from: $unclassifiedExercises exercise(s) are unclassified"
    }

    /**
     * The user's configuration cannot be served by anything they can actually perform — every candidate for a
     * stated focus needs equipment they declared none of, or is prescribed in a dimension the domain does not
     * implement.
     *
     * @property focus the configuration the pass was asked for.
     * @property limitations the planner's own reasons, carried as the generated domain's own values so the
     *   wording lives where the rule lives.
     */
    data class NothingPlannable(
        val focus: FocusPlan,
        val limitations: List<GenerationLimitation>
    ) : ProgramGenerationRefusal {
        override val message: String =
            "nothing in the exercise library can serve this plan with the equipment available " +
                "($focus): " +
                (limitations.joinToString("; ") { it.message }.ifEmpty { "no focus could be planned" })
    }
}
