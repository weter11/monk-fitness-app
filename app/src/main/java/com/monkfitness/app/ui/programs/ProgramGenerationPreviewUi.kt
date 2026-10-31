package com.monkfitness.app.ui.programs

import androidx.annotation.StringRes
import com.monkfitness.app.domain.prescription.PrescriptionDimension

/**
 * The Preview, presented: what one generation pass *would* produce, and what it *would* do to the draft
 * the user already has — built by [ProgramsController] from the
 * [com.monkfitness.app.domain.program.generated.GeneratedDraftEdit] the application service returned.
 *
 * One pass already yields three values — the prospective draft, the plan it was built from, and the
 * reconciliation of that plan into the draft the user had — and this type makes the last two visible
 * without a second generation algorithm. It is a projection, and the projection is the whole feature.
 *
 * No generated-domain type crosses into a Composable: a screen receives [ProgramGenerationPreviewUi] and
 * never a `GeneratedPlan`, a `GeneratedSlot`, a `ReconciliationReport` or a `GenerationLimitation`, so the
 * generated vocabulary cannot leak into layout code and the screen has no vocabulary in which to *decide*
 * anything. It may not sort or rank ([days] and [elements] are the plan's own order, carried straight
 * through), may not read a domain message ([messageRes] and [reasonRes] are string resources, because the
 * domain's own sentences are developer-facing English), and may not recompute a count ([preservedCount],
 * [addedCount], [droppedCount], [removedDayCount] are the reconciliation's own figures, read once).
 *
 * A conflict is not an error: [conflicts] is content the **user owns** — pinned, or authored by them —
 * that this plan would not have produced. It is kept, and emphatically not something the preview may "fix"
 * or count as removed. [limitations] is never dropped either: a limitation the user cannot see is a silent
 * substitution of the focus they did not get.
 *
 * Nothing here pairs a dropped element with an added one, and there is deliberately no field that could:
 * [ReconciliationReport] reports a replacement as its two halves precisely because the planner has no
 * relation between them, and a presentation layer that invented one would assert something the domain
 * refused to state.
 *
 * [droppedCount] counts **generated** elements only — a user's own element cannot be dropped, and showing
 * one as removed would tell the user their own work is gone. [removedDayCount] is reported separately
 * because a day disappearing is not a count of exercises.
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
     * The reconciliation's detail list, in the reconciliation's own order: the *same* changes the four
     * counts summarise, so summary and detail can never disagree, and a day that disappeared reads as a
     * day rather than as an exercise the user lost.
     */
    val changes: List<ProgramGenerationPreviewChangeUi> = emptyList()
) {

    /** How many days the plan holds — the headline figure the Preview opens with. */
    val dayCount: Int
        get() = days.size

    val exerciseCount: Int
        get() = days.sumOf { day -> day.elements.size }

    val hasReconciliation: Boolean
        get() = preservedCount > 0 || addedCount > 0 || droppedCount > 0 || removedDayCount > 0
}

/**
 * One planned day, as the Preview lists it.
 *
 * [position] is carried rather than re-derived so a Composable can render the day number without knowing
 * what a slot is; an empty [secondaryFocusRes] is an ordinary day, not a missing value.
 */
data class ProgramGenerationPreviewDayUi(
    val position: Int,
    val primaryFocusRes: Int,
    val secondaryFocusRes: List<Int>,
    val elements: List<ProgramGenerationPreviewElementUi>
)

/**
 * One generated element of one previewed day: which exercise, under which label, and what it prescribes.
 *
 * [targetsPerSet] is a list and not a number because both prescription shapes are **unequal per set**
 * (`12 / 10 / 8 / 6`, `30s / 30s / 45s`) and
 * [com.monkfitness.app.domain.program.generated.GenerationPolicy] uses them verbatim: a single
 * `targetPerSet` would show `4 sets × 12 reps` for a plan that applies `12 / 10 / 8 / 6`, misstating it in
 * the direction that makes the plan look gentler than it is. The list is carried **in the prescription's
 * own order** — it is a progression, and reordering it would be as much a lie as truncating it — and is
 * never empty in a real plan, because the prescription's own constructor refuses an empty one.
 *
 * [exerciseId] is a *fallback* label: [nameRes] is the localized name whenever the catalogue knows the
 * exercise, and the screen renders the id only when it does not.
 */
data class ProgramGenerationPreviewElementUi(
    val exerciseId: String,
    val nameRes: Int,
    val dimension: PrescriptionDimension,
    val sets: Int,
    val targetsPerSet: List<Int>
) {

    /**
     * Whether the sets are prescribed unequally. A caller may render one number plainly when the
     * prescription *is* uniform, but that is a presentation choice, never a licence to drop the other
     * terms: the full list is always carried.
     */
    val isUniformPrescription: Boolean
        get() = targetsPerSet.distinct().size <= 1
}

/**
 * One thing the planner could not do, as a sentence the user can read.
 *
 * The domain's `GenerationLimitation.message` is deliberately **not** carried here: it is
 * developer-facing English built from the enum's own name (`MOBILITY cannot be planned: …`). What reaches
 * a screen is a *pair of resources* — the focus's label and the reason's own sentence — mapped in one
 * place so the phrasing lives in `strings.xml` where a translator can reach it.
 *
 * [focusLabelRes] is `0` for the limitation that names no single focus.
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
 * The plan a user is asked to accept may disagree with what they pinned or wrote, and the honest answer is
 * neither to overwrite it nor to refuse: it is to say so and keep it. [levelRes] distinguishes a **pin**
 * (never automatically changed) from an **explicit user override** (never silently replaced), because the
 * domain distinguishes them, not because one is more of an error than the other.
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
 * The kind is a **resource** rather than a `ChangeKind` because the four kinds mean four different things
 * to a user and the wording belongs to the translation surface, not to the generated domain.
 * `DAY_REMOVED` in particular must not read like a removed exercise: it is a whole day.
 *
 * [exerciseId] is `null` for a whole-day change — the only case in which it is absent, so absence always
 * means *the day*, never a missing name.
 */
data class ProgramGenerationPreviewChangeUi(
    val kindRes: Int,
    val dayPosition: Int,
    val exerciseId: String?,
    val nameRes: Int
) {

    val isDayChange: Boolean
        get() = exerciseId == null
}

/**
 * The Preview's own small vocabulary: the string resources the controller hands a Composable.
 *
 * Declared here rather than in [ProgramNotice] because these are not *notices* — nothing has happened
 * yet. A notice reports an outcome; these name the parts of a prospective result, and keeping them apart
 * stops a preview label from being mistaken for something that did.
 */
object ProgramGenerationPreviewRes {

    val TITLE: Int = com.monkfitness.app.R.string.programs_preview_title

    /** One plan day's heading: "Day N", the plan's own position and nothing derived from it. */
    val DAY: Int = com.monkfitness.app.R.string.programs_preview_change_day

    /** A line naming the day's one primary focus. */
    val PRIMARY_FOCUS: Int = com.monkfitness.app.R.string.programs_preview_primary_focus

    /** A line naming the day's secondary focuses, which may be none. */
    val SECONDARY_FOCUS: Int = com.monkfitness.app.R.string.programs_preview_secondary_focus

    /**
     * A prescription in repetitions, one term per set.
     *
     * A **format** rather than a sentence: it takes the whole per-set list, because the prescriptions are
     * unequal by design (`12 / 10 / 8 / 6`) and one string per *term* would mean four near-identical
     * sentences in seven locales for a value that is really an ordered list. The list is joined with the
     * existing separator and shown in the prescription's own order.
     */
    val PRESCRIPTION_REPS: Int = com.monkfitness.app.R.string.programs_preview_reps

    /** The same list in seconds (`30 / 30 / 45`). */
    val PRESCRIPTION_SECONDS: Int = com.monkfitness.app.R.string.programs_preview_seconds


    val APPLY: Int = com.monkfitness.app.R.string.programs_preview_apply

    /** Dismiss the Preview without touching the working draft. */
    val DISMISS: Int = com.monkfitness.app.R.string.programs_preview_dismiss

    val SUMMARY: Int = com.monkfitness.app.R.string.programs_preview_summary

    val RECONCILIATION: Int = com.monkfitness.app.R.string.programs_preview_reconciliation

    val PRESERVED: Int = com.monkfitness.app.R.string.programs_preview_preserved

    val ADDED: Int = com.monkfitness.app.R.string.programs_preview_added

    /** One dropped **generated** element. */
    val DROPPED: Int = com.monkfitness.app.R.string.programs_preview_dropped

    /** One whole day that leaves the plan. */
    val DAY_REMOVED: Int = com.monkfitness.app.R.string.programs_preview_day_removed

    val CONFLICTS: Int = com.monkfitness.app.R.string.programs_preview_conflicts

    /** A pin: never automatically changed. */
    val LEVEL_PINNED: Int = com.monkfitness.app.R.string.programs_preview_level_pinned

    /** An explicit user override: never silently replaced. */
    val LEVEL_OVERRIDE: Int = com.monkfitness.app.R.string.programs_preview_level_override

    val LIMITATIONS: Int = com.monkfitness.app.R.string.programs_preview_limitations

    /**
     * No exercise in the user's selection trains this focus.
     *
     * A formatted sentence taking the focus's label, rather than a bare resource per focus: the seven
     * focuses would otherwise need seven near-identical strings in seven locales, kept in step for one
     * sentence.
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
 * A mapping and nothing else — no rule, no fallback, no default. Each of the domain's three reasons has
 * its own resource, because they are two different things a user can act on (declare your equipment vs.
 * choose another focus) and no silent substitution keeps them apart.
 *
 * Exhaustive over `FocusUnusableReason` on purpose: a reason added to the domain without a sentence here
 * is a limitation this stage would show as *nothing*, which is the defect the Preview exists to remove.
 * `ProgramsLocalizationTest` turns that into a failing test rather than a dropped line.
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