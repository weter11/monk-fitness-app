package com.monkfitness.app.domain.adaptive.integration

/**
 * Why an adaptive pass produced no decision at all — an **expected** result, named. Each member is one
 * concrete missing or ineligible fact, because representing materially different outcomes with `null`, an
 * empty list or `false` is what the architecture forbids.
 *
 * A gap is **not** a decision — the engine was never asked, so nothing is written — and not a failure:
 * stored-facts-that-cannot-be-true and storage errors travel as their own results.
 */
enum class AdaptiveInputGap {

    NO_SUCH_SESSION,

    /**
     * The attempt ended as a **cancellation**, so there is no completion for it.
     *
     * It keeps its partial work as exposure for later windows and never becomes the completion that adapts
     * anything. An attempt still **in progress** is the opposite case and is *not* a gap.
     */
    SESSION_WAS_CANCELLED,

    /**
     * A manual Program is fully user-authored, so automatic adaptation has nothing in it to change. It is
     * not adapted *conservatively* — it is not adapted at all.
     */
    PROGRAM_MODE_IS_MANUAL,

    /**
     * The revision holds no slot still ahead of the user: the ordinary end of a program, explicitly **not**
     * an exception — nothing is written and the Scheduler is not asked to create a slot.
     */
    NO_FUTURE_SLOT,

    /** A rest day is a real plan day with no elements, so there is no element and no family to decide about. */
    PLAN_DAY_PRESENTS_NOTHING,

    /**
     * *"We do not know which family this exercise belongs to"* and *"we know the family and declare no
     * ladder for it"* are fixed by **different artefacts**, so a single "cannot adapt" would hide which
     * one is missing. Nothing is inferred from an exercise id or a name to bridge either one.
     */
    NO_FAMILY_CLASSIFICATION,

    /**
     * A day that trains other families is not adapted *now*: the family's own window opens when its next
     * appearance is completed, which is when its evidence has moved on.
     */
    NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT,

    /**
     * The family is trained and presented, but no [ProgressionRelationProvider] declares its ladder, so no
     * honest step exists — recorded as an explicit gap rather than a fabricated ladder or a default level.
     */
    NO_DECLARED_PROGRESSION_RELATION
}
