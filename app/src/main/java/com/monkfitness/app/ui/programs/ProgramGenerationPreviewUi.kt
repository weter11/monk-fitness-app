package com.monkfitness.app.ui.programs

import androidx.annotation.StringRes
import com.monkfitness.app.domain.prescription.PrescriptionDimension

/**
 * §7's **Preview**, presented: what one generation pass *would* produce, and what it *would* do to the
 * draft the user already has — built by [ProgramsController] out of the
 * [com.monkfitness.app.domain.program.generated.GeneratedDraftEdit] the application service returned.
 *
 * ### Why this type exists at all
 *
 * P24 already produced three values from one pass — the prospective **draft**, the **plan** it was
 * built from, and the **reconciliation** of that plan into the draft the user had — and the controller
 * used only the first. This type is what makes the other two *visible* without creating a second
 * generation algorithm: the planner has not changed, the request has not changed, the reconciler has
 * not changed, and this file contains no decision of any kind. It is a projection, and the projection
 * is the whole feature.
 *
 * ### Nothing generated-domain crosses into a Composable
 *
 * §16's chain ends in `UI state ⇄ Composable`, and every type in [ProgramUiModels] is a presentation
 * built by the controller from domain values and nothing else. That rule is what this file extends to
 * the generated domain: a screen receives [ProgramGenerationPreviewUi] and never a
 * `GeneratedPlan`, a `GeneratedSlot`, a `ReconciliationReport` or a `GenerationLimitation`, so the
 * generated vocabulary cannot leak into layout code and the screen has no vocabulary in which to
 * *decide* anything.
 *
 * What the screen is forbidden from doing is correspondingly narrow. It may not sort or rank
 * ([days] and [elements] are the plan's own order, carried straight through), it may not read a
 * domain message ([messageRes] and [reasonRes] are string resources, because §14 requires every word
 * a user reads to come from a resource and the domain's own sentences are developer-facing English),
 * and it may not recompute a count ([preservedCount], [addedCount], [droppedCount] and
 * [removedDayCount] are the reconciliation's own figures, read once).
 *
 * ### A conflict is not an error
 *
 * [conflicts] is the main thing a user needs here and the easiest thing to get wrong. A conflict is
 * §7's *preserved despite disagreement*: content the **user owns** — pinned, or authored by them —
 * that this plan would not have produced. It is kept, it is not an error, and it is emphatically not
 * something the preview may "fix" or count as removed. It is reported, which is exactly what §7's
 * *"conflicts with user choices are shown explicitly"* asks for.
 *
 * Nothing here pairs a dropped element with an added one, and there is deliberately no field that
 * could: [ReconciliationReport] reports a replacement as its two halves precisely because the planner
 * has no relation between them (§7's own recorded decision), and a presentation layer that invented
 * one would be asserting something the domain refused to state.
 *
 * @property days the plan's own days, in the plan's own order — never sorted, never re-ranked.
 * @property limitations what the planner could not plan, mapped to resources. Never dropped: a
 *   limitation the user cannot see is a silent substitution of the focus they did not get (§33).
 * @property preservedCount how many elements survive, at any level.
 * @property addedCount how many elements enter the plan.
 * @property droppedCount how many **generated** elements leave the plan. A user's own element is
 *   never counted here: it cannot be dropped, and if a UI ever showed one as removed it would be
 *   telling the user their own work is gone.
 * @property removedDayCount how many whole days leave the plan — reported separately, because a day
 *   disappearing is not a count of exercises and folding it into [droppedCount] would misreport it.
 * @property conflicts the user's own content this plan disagrees with, all kept.
 */
data class ProgramGenerationPreviewUi(
    val days: List<ProgramGenerationPreviewDayUi>,
    val limitations: List<ProgramGenerationLimitationUi>,
    val preservedCount: Int,
    val addedCount: Int,
    val droppedCount: Int,
    val removedDayCount: Int,
    val conflicts: List<ProgramGenerationPreviewConflictUi>,
    /**
     * The reconciliation's detail list, in the reconciliation's own order, for a caller that wants to
     * expand it.
     *
     * It is the *same* changes the four counts summarise, listed as they were reported — so the
     * summary and the detail can never disagree, and a day that disappeared reads as a day rather than
     * as an exercise the user lost.
     */
    val changes: List<ProgramGenerationPreviewChangeUi> = emptyList()
) {

    /** How many days the plan holds — the headline figure the Preview opens with. */
    val dayCount: Int
        get() = days.size

    /** How many elements the whole plan prescribes, counting every occurrence. */
    val exerciseCount: Int
        get() = days.sumOf { day -> day.elements.size }

    /** Whether there is anything at all to show as a reconciliation summary. */
    val hasReconciliation: Boolean
        get() = preservedCount > 0 || addedCount > 0 || droppedCount > 0 || removedDayCount > 0
}

/**
 * One planned day, as the Preview lists it.
 *
 * @property position the plan day's own 1-based position, shown as it is. Carrying it rather than
 *   re-deriving an index means a Composable can render the day number without knowing what a slot is.
 * @property primaryFocusRes the focus this day is built around — §8's *1 primary focus*, in the
 *   resource the existing focus vocabulary already has, never the enum's own English name.
 * @property secondaryFocusRes the additional focuses, in the order the plan states them. Empty is an
 *   ordinary day, not a missing value.
 * @property elements the day's planned work, one entry per assigned focus, in the plan's own order.
 */
data class ProgramGenerationPreviewDayUi(
    val position: Int,
    val primaryFocusRes: Int,
    val secondaryFocusRes: List<Int>,
    val elements: List<ProgramGenerationPreviewElementUi>
)

/**
 * One generated element of one previewed day: which exercise, under which label, and what it
 * prescribes.
 *
 * @property exerciseId the library id. It is carried — it is the domain's own opaque handle and the
 *   catalogue's key — but it is a *fallback* label: [nameRes] is the localized name whenever the
 *   catalogue knows the exercise, and the screen renders the id only when it does not. §14 forbids
 *   showing a raw English identifier when a localized name exists.
 * @property dimension the unit the element is prescribed in. The existing prescription dimension and
 *   nothing invented: this stage defines no new formatting semantics for a prescription.
 */
data class ProgramGenerationPreviewElementUi(
    val exerciseId: String,
    val nameRes: Int,
    val dimension: PrescriptionDimension,
    val sets: Int,
    val targetPerSet: Int
)

/**
 * One thing the planner could not do, as a sentence the user can read.
 *
 * The domain's [com.monkfitness.app.domain.program.generated.GenerationLimitation.message] is
 * deliberately **not** carried here. That message is developer-facing English built from the enum's
 * own name (`MOBILITY cannot be planned: …`), and §33 requires a limitation to be *reported* in a
 * form the user can act on. What reaches a screen is a *pair of resources* — the focus's label and the
 * reason's own sentence — which the controller maps in one place so the phrasing lives in
 * `strings.xml` where a translator can reach it.
 *
 * @property focusLabelRes the focus that could not be planned, or `0` for the limitation that names
 *   no single focus (§8's *no focus of this configuration can be planned at all*).
 * @property reasonRes the sentence for the reason, as a resource.
 */
data class ProgramGenerationLimitationUi(
    val focusLabelRes: Int,
    val reasonRes: Int
) {

    /** Whether this limitation names one focus, which is what decides how the sentence is built. */
    val namesAFocus: Boolean
        get() = focusLabelRes != 0
}

/**
 * One piece of the user's own content this plan would not have produced — and will keep anyway.
 *
 * §7's *conflicts with user choices are shown explicitly* is the whole reason a Preview is worth
 * having: the plan a user is asked to accept may disagree with what they pinned or wrote, and the
 * honest answer is neither to overwrite it nor to refuse — it is to say so and keep it.
 *
 * @property levelRes what kind of user ownership this is, as a resource: a **pin** (never
 *   automatically changed) or an **explicit user override** (never silently replaced). The two are
 *   distinguished because §7 distinguishes them, not because one is more of an error than the other.
 * @property dayPosition the day the element is on, in the plan's own numbering.
 */
data class ProgramGenerationPreviewConflictUi(
    val levelRes: Int,
    val dayPosition: Int,
    val exerciseId: String,
    val nameRes: Int
) {

    /** The catalogue's label for this element, or `0` when the catalogue does not know the id. */
    val hasLocalizedName: Boolean
        get() = nameRes != 0
}

/**
 * One line of the reconciliation detail list, as a resource plus the numbers it needs.
 *
 * The kind is a **resource** rather than a [com.monkfitness.app.domain.program.generated.ChangeKind]
 * because the four kinds mean four different things to a user and the wording belongs to §14's
 * translation surface, not to the generated domain. `DAY_REMOVED` in particular must not read like a
 * removed exercise: it is a whole day, and conflating the two would misreport what happened.
 *
 * @property kindRes the sentence for this kind of change.
 * @property dayPosition the day the change is about.
 * @property exerciseId the exercise, or `null` for a whole-day change — which is the only case in
 *   which it is absent, so absence always means *the day*, never a missing name.
 * @property nameRes the catalogue's label for [exerciseId], or `0` when the catalogue has none.
 */
data class ProgramGenerationPreviewChangeUi(
    val kindRes: Int,
    val dayPosition: Int,
    val exerciseId: String?,
    val nameRes: Int
) {

    /** Whether this change is about a whole day rather than about one exercise. */
    val isDayChange: Boolean
        get() = exerciseId == null
}

/**
 * The Preview's own small vocabulary: the string resources the controller hands a Composable.
 *
 * Declared here rather than in [ProgramNotice] because these are not *notices* — nothing has happened
 * yet. A notice reports an outcome; these name the parts of a prospective result. Keeping them apart
 * is what stops a preview label from being mistaken for something that did.
 */
object ProgramGenerationPreviewRes {

    /** The Preview section's own heading. */
    val TITLE: Int = com.monkfitness.app.R.string.programs_preview_title

    /** One plan day's own heading: "Day N", the plan's own position and nothing derived from it. */
    val DAY: Int = com.monkfitness.app.R.string.programs_preview_change_day

    /** §8's *1 primary focus per slot*, as a line naming the focus. */
    val PRIMARY_FOCUS: Int = com.monkfitness.app.R.string.programs_preview_primary_focus

    /** §8's *0–2 secondary focuses per slot*, as a line naming them. */
    val SECONDARY_FOCUS: Int = com.monkfitness.app.R.string.programs_preview_secondary_focus

    /** A prescription in repetitions — the existing dimension, no new formatting semantics. */
    val PRESCRIPTION_REPS: Int = com.monkfitness.app.R.string.programs_preview_reps

    /** A prescription in seconds — the other existing dimension. */
    val PRESCRIPTION_SECONDS: Int = com.monkfitness.app.R.string.programs_preview_seconds

    /** The explicit action that adopts the prospective draft, and nothing else. */
    val APPLY: Int = com.monkfitness.app.R.string.programs_preview_apply

    /** Dismiss the Preview without touching the working draft. */
    val DISMISS: Int = com.monkfitness.app.R.string.programs_preview_dismiss

    /** The summary line: N days, N exercises. */
    val SUMMARY: Int = com.monkfitness.app.R.string.programs_preview_summary

    /** The reconciliation block's heading. */
    val RECONCILIATION: Int = com.monkfitness.app.R.string.programs_preview_reconciliation

    /** One preserved element. */
    val PRESERVED: Int = com.monkfitness.app.R.string.programs_preview_preserved

    /** One added element. */
    val ADDED: Int = com.monkfitness.app.R.string.programs_preview_added

    /** One dropped **generated** element. */
    val DROPPED: Int = com.monkfitness.app.R.string.programs_preview_dropped

    /** One whole day that leaves the plan. */
    val DAY_REMOVED: Int = com.monkfitness.app.R.string.programs_preview_day_removed

    /** The conflicts block's heading. */
    val CONFLICTS: Int = com.monkfitness.app.R.string.programs_preview_conflicts

    /** §7's highest level: a pin. */
    val LEVEL_PINNED: Int = com.monkfitness.app.R.string.programs_preview_level_pinned

    /** §7's second level: an explicit user override. */
    val LEVEL_OVERRIDE: Int = com.monkfitness.app.R.string.programs_preview_level_override

    /** The limitations block's heading. */
    val LIMITATIONS: Int = com.monkfitness.app.R.string.programs_preview_limitations

    /**
     * No exercise in the user's selection trains this focus.
     *
     * A formatted sentence taking the focus's label, rather than a bare resource per focus: the seven
     * focuses would otherwise need seven near-identical strings in seven locales, and a translator
     * would have to keep seven in step for one sentence.
     */
    val REASON_NO_EXERCISE: Int = com.monkfitness.app.R.string.programs_preview_reason_no_exercise

    /** Every exercise for this focus needs equipment the user has not declared. */
    val REASON_EQUIPMENT: Int = com.monkfitness.app.R.string.programs_preview_reason_equipment

    /** Every exercise for this focus is prescribed in a dimension the app does not generate. */
    val REASON_DIMENSION: Int =
        com.monkfitness.app.R.string.programs_preview_reason_dimension

    /** No focus of the configuration can be planned, so the plan holds no slot. */
    val REASON_NO_PLANNABLE_FOCUS: Int =
        com.monkfitness.app.R.string.programs_preview_reason_no_plannable_focus
}

/**
 * The one place a [com.monkfitness.app.domain.program.generated.FocusUnusableReason] becomes a
 * sentence a user reads.
 *
 * It is a mapping and nothing else — no rule, no fallback, no default. Each of the domain's three
 * reasons has its own resource, so a focus the equipment forbids reads differently from one nothing in
 * the user's selection trains, because those are two different things a user can act on
 * (declare your equipment vs. choose another focus) and §33's *"no silent substitution"* is what keeps
 * them apart.
 *
 * Exhaustive over [FocusUnusableReason] on purpose: a reason added to the domain without a sentence
 * here is a limitation this stage would show as *nothing*, which is the defect the Preview exists to
 * remove. `ProgramsLocalizationTest` is what turns that into a failing test rather than a dropped line.
 *
 * @return the string resource for [reason].
 */
@StringRes
internal fun focusUnusableReasonRes(
    reason: com.monkfitness.app.domain.program.generated.FocusUnusableReason
): Int = when (reason) {
    com.monkfitness.app.domain.program.generated.FocusUnusableReason
        .NO_EXERCISE_TRAINS_THE_FOCUS -> ProgramGenerationPreviewRes.REASON_NO_EXERCISE

    com.monkfitness.app.domain.program.generated.FocusUnusableReason
        .EVERY_EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT ->
        ProgramGenerationPreviewRes.REASON_EQUIPMENT

    com.monkfitness.app.domain.program.generated.FocusUnusableReason
        .PRESCRIPTION_DIMENSION_NOT_IMPLEMENTED -> ProgramGenerationPreviewRes.REASON_DIMENSION
}