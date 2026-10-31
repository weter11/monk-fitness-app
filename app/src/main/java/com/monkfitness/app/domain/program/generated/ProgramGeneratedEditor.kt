package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramMode

/**
 * What one `Generate` or `Regenerate` produced: the next draft, the plan it was built from, and what
 * reconciliation did to the draft it started from.
 *
 * Three values rather than one, because none of them can be derived from the others: the **draft** is
 * what the user goes on editing and what `Save` persists, the **plan** is what the planner decided (each
 * slot's focuses, what could not be planned), and the **reconciliation** is what happened to the content
 * that was already there. Showing conflicts needs the third; a Preview screen needs the second.
 */
data class GeneratedDraftEdit(
    val draft: ProgramEditorDraft,
    val plan: GeneratedPlan,
    val reconciliation: ReconciliationReport
)

/**
 * The Generated Program Editor: `Generate`, `Preview`, `Edit`, `Regenerate`, `Pin`, override and local
 * regeneration, in the shape the rest of this domain already uses.
 *
 * **Generate and Regenerate only ever alter a draft.** There is no repository in this class, no service,
 * no transaction, no `Program` and no `ProgramRevision` — not as a convention but as the type's own
 * shape. The only way from here to storage remains
 * [com.monkfitness.app.domain.usecase.ProgramEditorService.save], which is also the only place a
 * revision identity is minted. Nothing here duplicates Save: a no-op save creates no revision, a
 * structural change creates exactly one, a stale draft saves relative to what is stored.
 *
 * The class mirrors [com.monkfitness.app.domain.program.ProgramDraftEditor] deliberately — draft in,
 * next draft out, identity from an injected [DraftIdSource], no mutation of the value it was given — so
 * the two editors compose without either one having to know about the other. Manual editing is the same
 * `ProgramDraftEditor` as before: an element the user edits inside a Generated Program becomes
 * `USER_AUTHORED` (the manual editor's own default origin), and that is exactly what reconciliation
 * reads to decide it may not be replaced.
 *
 * Generate and Regenerate are one operation: **the plan is reconciled into the draft, whatever the draft
 * holds.** The difference a UI makes — *"build me a plan"* versus *"build me another one"* — is a
 * difference of intent, and intent must not decide whose content survives: a `Generate` that discarded
 * pinned elements would be the silent overwrite this stage forbids, and it would make the pin's meaning
 * depend on which button the user reached for.
 *
 * The draft that comes out is a **Generated** draft whose Goals & Focus configuration is the one the plan
 * was built for — generation states the mode it planned in and the configuration it planned from, rather
 * than leaving a draft whose stored goal disagrees with the plan beside it. Mode and configuration are
 * revision content, so a save after a generation is a structural change like any other.
 */
class ProgramGeneratedEditor(
    val draft: ProgramEditorDraft,
    private val ids: DraftIdSource
) {

    /** Plans [request] and reconciles it into this draft — `Generate`. */
    fun <E> generate(request: GenerationRequest<E>): GeneratedDraftEdit = edit(request)

    /**
     * Plans [request] again and reconciles it into this draft — `Regenerate`.
     *
     * Same operation as [generate], deliberately: the precedence has no exception for a user who
     * pressed the other button.
     */
    fun <E> regenerate(request: GenerationRequest<E>): GeneratedDraftEdit = edit(request)

    /**
     * The plan of [request], reconciled into this draft, as the next draft.
     *
     * The two steps are kept visible rather than folded together because they have different guarantees:
     * [GeneratedPlanner.plan] is a pure function of the request, and [PlanReconciler.reconcile] is a
     * pure function of the draft and that plan. Either can be used alone — a Preview screen wants the
     * first without the second.
     */
    private fun <E> edit(request: GenerationRequest<E>): GeneratedDraftEdit {
        val plan = GeneratedPlanner.plan(request)
        val reconciled = PlanReconciler.reconcile(previous = draft, plan = plan, ids = ids)
        val next = draft.copy(
            mode = ProgramMode.GENERATED,
            focus = request.focus,
            days = reconciled.days
        )
        return GeneratedDraftEdit(
            draft = next,
            plan = plan,
            reconciliation = reconciled.report
        )
    }
}
