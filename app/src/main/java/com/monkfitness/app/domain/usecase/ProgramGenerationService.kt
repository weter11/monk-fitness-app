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
 * The **production Generate flow** — §30 step 24: the one application operation behind the Generated
 * editor's `Generate` and `Regenerate`.
 *
 * ```text
 * working draft
 *      ↓  its own focus / schedule / duration
 * ProgramGenerationService.generate(draft, availableEquipment)
 *      ↓  the app's shipped catalogue, through P23's boundary
 * GenerationRequest<Equipment>          ← the pure input, unchanged
 *      ↓  GeneratedPlanner.plan
 * GeneratedPlan
 *      ↓  PlanReconciler.reconcile, with a DraftIdSource from the composition root
 * GeneratedDraftEdit
 *      ↓
 * the next working draft                ← nothing else is written
 * ```
 *
 * ### What this class is for
 *
 * Every step of that chain already exists and belongs to a different owner: the catalogue conversion
 * and the request assembly are [ProductionGenerationBoundary]'s (P23), the allocation, selection and
 * prescriptions are the generated domain's (P10), and the preservation of pinned and user-authored
 * content is [ProgramGeneratedEditor]'s together with `PlanReconciler` (§7). What was missing is the
 * one node that says **in what order** they run and **which facts each is given** — and without it, a
 * caller that wanted a plan had to know all of that, which is exactly the knowledge a screen and a
 * controller must not hold. So the orchestration lives here, once, and every caller reaches the
 * planner the same way.
 *
 * ### The configuration is the draft's, never a default laid over it
 *
 * `focus`, `schedule` and `duration` are read from [draft] and forwarded unchanged. §7's *Goals &
 * Focus* configuration, the weekly rhythm and the Program's length are the user's own statements
 * about this Program, and a pass that substituted `FocusPlan.DEFAULT` or a default cadence would
 * plan a Program the user did not ask for while the draft beside it stored the configuration they
 * did — the exact disagreement §6's *conflicts with user choices are shown explicitly* is about.
 * There is no second configuration object here and no field that could fall back.
 *
 * `mode` is not read: generation *states* it. [ProgramGeneratedEditor] writes `GENERATED` and the
 * focus the plan was built for into the next draft, because a draft whose stored mode disagreed with
 * the plan beside it would be saving a false fact as revision content (§6).
 *
 * ### Equipment is a stated fact, forwarded verbatim
 *
 * [availableEquipment] goes into the request exactly as the caller states it — no normalisation, no
 * widening, no catalogue filtering. The whole catalogue reaches the planner and
 * `GenerationRequest.isUsable` decides usability through
 * `availableEquipment.containsAll(candidate.requiredEquipment)`. **An empty set means the user
 * declared no equipment**, not *constrain nothing*: that is P24's documented reading of
 * `SettingsManager.availableEquipmentFlow`'s empty default, and it is deliberately **not** the
 * legacy `isAccessibleWith` rule, which would hand a user who owns nothing a plan full of bar and
 * band exercises.
 *
 * ### The context is read once, by one collaborator, and forwarded unchanged
 *
 * P27 replaced this class's stated neutrality with a **real read**: [context] answers
 * `GenerationPreferences.preferencesFor(draft)` and the answer goes into the already-existing
 * `GenerationRequest.preferences` untouched. Nothing here inspects, filters, re-orders or completes
 * that value — it is asked **once per pass**, in [edit], and the same answer serves `Generate`,
 * `Regenerate` and `Preview` because all three hand to the same private pass. A preview is therefore
 * never planned against one set of facts and applied against another (§33's *"no silent
 * substitution"*, applied to context rather than to focus).
 *
 * The value itself says which of §8's signals production can state today. **`recentExerciseIds` is
 * filled** from this Program's performed sessions; the other five stay at their neutral values because
 * no production-owned source states them — not `recentExposureByFocus` from a session count, not
 * `recentLoadByFocus` from repetitions, not `recovery` from elapsed hours, not an adaptive preference
 * out of a family's current exercise. `docs/PROGRAM_GENERATION_CONTEXT.md` gives the per-signal table.
 * [GenerationPreferences.NONE] remains the answer for a draft with no Program and for a Program with no
 * history, and that is an **explicit neutral absence**, never a fabricated zero.
 *
 * [policy] is unchanged and is likewise the generated domain's own [GenerationPolicy.DEFAULT]: the
 * blueprint's open numbers are that type's declared owner decisions, so naming the default is not this
 * class inventing one.
 *
 * ### What it never does
 *
 * No repository, no DAO, no Room, no revision, no Program, no slot, no date, no clock, no Compose and
 * no UI type. `Generate` and `Regenerate` **only ever alter a draft** (§7): the only route from here
 * to storage remains `ProgramEditorService.save`, which is also the only place a revision identity is
 * minted. Reading a Program's history through [context] changes none of that — the read belongs to
 * the context source, and this class still holds nothing that could write. The draft handed in is not
 * mutated — every operation returns the next immutable value, so a caller that ignores the result has
 * changed nothing.
 *
 * @param catalogue where the production catalogue is read. A port rather than a direct call so a
 *   test can state a fixture catalogue, and so the legacy `WorkoutGenerator` stays named by
 *   [ProductionGenerationBoundary] alone rather than by every caller of it (P23's closed reader list).
 * @param focusSource where an exercise's focus membership is stated —
 *   [ProductionFocusClassification] in production.
 * @param ids where the identities of added draft days and elements come from (§26), wired to the
 *   composition root's own id generator.
 * @param context where the plain signals come from — P27's [GenerationContextSource],
 *   [ProgramHistoryGenerationContext] in production. Required rather than defaulted, because a
 *   defaulted value here is exactly the quiet neutrality P27 replaced: every construction site has to
 *   say where its facts come from.
 * @param policy the generated domain's own explicit numbers, defaulted to
 *   [GenerationPolicy.DEFAULT].
 */
class ProgramGenerationService(
    private val catalogue: GenerationCatalogue,
    private val focusSource: GenerationFocusSource,
    private val ids: DraftIdSource,
    private val context: GenerationContextSource,
    private val policy: GenerationPolicy = GenerationPolicy.DEFAULT
) {

    /**
     * §7's **Generate**: plans [draft] and reconciles the plan into it.
     *
     * @param availableEquipment the equipment the user has, forwarded into the request unchanged. An
     *   empty set means the user declared no equipment (§4 of this stage's brief).
     */
    suspend fun generate(
        draft: ProgramEditorDraft,
        availableEquipment: Set<Equipment>
    ): ProgramGenerationResult = edit(draft, availableEquipment)

    /**
     * §7's **Regenerate**: the same reconciliation, asked for a second time.
     *
     * Deliberately the same operation as [generate], which is the generated domain's own recorded
     * decision ([ProgramGeneratedEditor.regenerate]): a regenerate that discarded pinned content, or
     * a generate that kept content a regenerate would have replaced, would make the meaning of a pin
     * depend on which button the user reached for.
     */
    suspend fun regenerate(
        draft: ProgramEditorDraft,
        availableEquipment: Set<Equipment>
    ): ProgramGenerationResult = edit(draft, availableEquipment)

    /**
     * §7's **Preview**: the same pass, offered for reading rather than for adoption.
     *
     * ```text
     * preview(draft, availableEquipment)  →  the very same edit(…) → the very same GeneratedDraftEdit
     * ```
     *
     * It is deliberately **not** a second pipeline. There is no `previewPlan`, no branch in the
     * request assembly and no alternative caller of the planner: [preview] hands straight to the same
     * private [edit] that [generate] and [regenerate] hand to, so a preview cannot be built from a
     * different configuration, a different catalogue or a different policy than the Generate the user
     * is being shown a preview of. A *different* planner path would be a second set of generation
     * semantics that could disagree with the one the button applies, which is precisely what §33's
     * *"no silent substitution"* is about.
     *
     * ### What the caller does with the result is the caller's business
     *
     * This class cannot and does not decide whether a preview is applied. It returns a
     * [ProgramGenerationResult.Generated] holding a prospective [GeneratedDraftEdit] — a value, like
     * every other operation here — and whether that value becomes the working draft is the caller's
     * single explicit decision. Nothing here mutates the draft it was given: every operation returns
     * the next immutable value, so a caller that ignores a preview has changed nothing at all.
     *
     * @param availableEquipment the equipment the user has, forwarded into the request unchanged,
     *   exactly as [generate] forwards it.
     */
    suspend fun preview(
        draft: ProgramEditorDraft,
        availableEquipment: Set<Equipment>
    ): ProgramGenerationResult = edit(draft, availableEquipment)

    /**
     * The one pass all three buttons run: read the configuration, read the catalogue, read the
     * context, assemble the request, and hand it to the generated editor.
     *
     * The two refusals are typed values rather than thrown exceptions (§28: an expected state is a
     * result), and each is decided **before** the value that would otherwise throw it, so the caller
     * is told what the gap is instead of being handed a failure whose message happens to explain it:
     *
     *  * a catalogue that classified nothing is [ProgramGenerationRefusal.NoExerciseStatesItsFocus];
     *  * a configuration nothing usable can serve is [ProgramGenerationRefusal.NothingPlannable]. The
     *    plan would be a plan of nothing, which §33 forbids presenting as a success — and the draft
     *    the user already has is kept, because losing it is the worse answer.
     *
     * ### The context is read exactly here, exactly once
     *
     * [context] is asked once, inside the `try`, and the value is passed down as an argument rather
     * than re-read by [planned]. That is the whole of the snapshot-consistency rule: there is no second
     * read, no second request-assembly path, and nothing downstream of here can observe a different set
     * of facts than the request was built from. It is read *after* the catalogue check, so a pass that
     * can plan nothing does not go looking for history it will not use.
     *
     * Anything else that throws is [ProgramGenerationResult.Failed], surfaced with its cause rather
     * than absorbed into an empty draft (§33).
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

    /** The assembled request, the editor's pass, and the two answers that request can produce. */
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
 * Where the exercises a generation pass may choose from are read, given the focus source that states
 * what each one trains.
 *
 * A port rather than a direct call to [ProductionGenerationBoundary.catalogueOfShippedExercises], for
 * two reasons that pull the same way: a test states a fixture catalogue without a device, and the
 * legacy `WorkoutGenerator` stays named by the boundary alone — P23's gate pins a closed list of
 * readers of the catalogue, and a second production caller would be a second owner of it.
 */
fun interface GenerationCatalogue {

    /** The exercises this source offers, as the boundary's classified view of them. */
    fun catalogueOf(focusSource: GenerationFocusSource): ProductionGenerationCatalogue
}

/**
 * The production [GenerationCatalogue]: the app's own shipped exercises, read by the boundary.
 *
 * A value rather than a class so the composition root wires a graph node it can pass straight on, and
 * a test wires a lambda. It is the only place in production that says *"the catalogue for generation
 * is the shipped one"*.
 */
val SHIPPED_EXERCISE_CATALOGUE: GenerationCatalogue = GenerationCatalogue { focusSource ->
    ProductionGenerationBoundary.catalogueOfShippedExercises(focusSource)
}

/**
 * What one generation pass did, in the three classes §28 requires a caller to be able to tell apart.
 *
 * [Failed] is the §28 `SYSTEM_FAILURE` class: something threw while the pass ran, surfaced rather
 * than absorbed — §33 forbids turning a failure into an empty draft, and a draft that silently lost
 * the user's plan would be exactly that. [Refused] is the expected class: the app states no usable
 * exercise for this configuration, and the caller's draft is untouched.
 */
sealed interface ProgramGenerationResult {

    /**
     * The pass produced a plan and reconciled it into the draft.
     *
     * @property edit the next draft, the plan that was built, and what reconciliation did — all three
     *   are kept because none is derivable from the others (§7's *conflicts with user choices are
     *   shown explicitly* needs the third; a Preview screen needs the second).
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
 * Both are *absences of stated facts* rather than failures, and neither is ever answered by inventing
 * a focus membership, widening the equipment or generating an empty plan: the honest answer to *"the
 * app states nothing usable here"* is a typed refusal a UI can present.
 */
sealed interface ProgramGenerationRefusal {

    /** The user-facing sentence. */
    val message: String

    /**
     * No exercise in the catalogue states which focuses it trains, so there is nothing to generate
     * from — the gap P23 recorded and this stage closes, still reported if it ever reopens.
     *
     * @property unclassifiedExercises how many catalogue entries stated nothing.
     */
    data class NoExerciseStatesItsFocus(val unclassifiedExercises: Int) : ProgramGenerationRefusal {
        override val message: String =
            "no exercise in the catalogue states which focuses it trains, so there is nothing to " +
                "generate from: $unclassifiedExercises exercise(s) are unclassified"
    }

    /**
     * The user's configuration cannot be served by anything they can actually perform — every
     * candidate for a stated focus needs equipment they declared none of, or is prescribed in a
     * dimension the domain does not implement.
     *
     * @property focus the configuration the pass was asked for.
     * @property limitations the planner's own reasons, one per focus it could not plan. The
     *   generated domain's own values are carried rather than a copy of them, so the wording lives
     *   where the rule lives.
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
