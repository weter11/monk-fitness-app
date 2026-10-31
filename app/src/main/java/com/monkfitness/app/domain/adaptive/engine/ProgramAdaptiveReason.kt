package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction

/**
 * Why the engine answered what it answered — one stable domain token per rule.
 *
 * A `HOLD` is a real result and these tokens keep it from being read as one thing: "not enough
 * history", "the user is plateaued", "cooling down" and "the guard refused it" are different facts,
 * and a decision trail that shows "no change" for all of them is the silent-failure shape this exists to
 * prevent. The tokens are stable identifiers, never user-facing text.
 */
enum class ProgramAdaptiveReason {

    INSUFFICIENT_EVIDENCE,

    LOW_CONFIDENCE,

    STABLE_PLATEAU,

    MIXED_EVIDENCE,

    AWAITING_CONFIRMATION,

    PROGRESSION_COOLDOWN,

    RECENT_LOAD_ELEVATED,

    INCONSISTENT_ATTENDANCE,

    CAUTIOUS_CONTEXT,

    CEILING_REACHED,

    FLOOR_REACHED,

    /** The relation declares no single step, or the one it declares is not in the user's selection. */
    PROGRESSION_UNAVAILABLE,

    /** `REST_BASED` is unimplemented, so a rest change cannot be expressed and is reported, not faked. */
    REST_CHANGE_UNSUPPORTED,

    USER_AUTHORED_ELEMENT,

    AGGREGATE_LOAD_GUARD,

    RECOVERY_ENTERED,

    RECOVERY_HELD,

    RECOVERY_EXITED,

    SUSTAINED_POSITIVE,

    SUSTAINED_NEGATIVE,

    /**
     * A **policy** emits this from the gap facts rather than from the window's own trend, because its
     * evidence is a stretch of calendar the window cannot measure. It accompanies a `REGRESS` and is
     * answered by the within-range prescription step — the same exercise, lighter — never by moving the
     * family down its ladder.
     */
    RETURN_AFTER_GAP,

    /**
     * The last session logged less than it was asked for, in the exercise's own unit, so the next one
     * starts from what was actually done. The policy cannot see this: it counts sets completed, and a
     * complete set of six against an eight is a completed set.
     */
    BELOW_TARGET_CORRECTION,

    VARIANT_REALIGNED;

    val isHold: Boolean
        get() = this in holdingReasons

    val isChange: Boolean
        get() = this in changeReasons

    val action: AdaptiveAction
        get() = when (this) {
            SUSTAINED_POSITIVE -> AdaptiveAction.PROGRESS
            SUSTAINED_NEGATIVE -> AdaptiveAction.REGRESS
            RETURN_AFTER_GAP -> AdaptiveAction.REGRESS
            BELOW_TARGET_CORRECTION -> AdaptiveAction.REGRESS
            VARIANT_REALIGNED -> AdaptiveAction.CHANGE_VARIANT
            else -> AdaptiveAction.HOLD
        }

    companion object {

        val holdingReasons: List<ProgramAdaptiveReason> = listOf(
            INSUFFICIENT_EVIDENCE,
            LOW_CONFIDENCE,
            STABLE_PLATEAU,
            MIXED_EVIDENCE,
            AWAITING_CONFIRMATION,
            PROGRESSION_COOLDOWN,
            RECENT_LOAD_ELEVATED,
            INCONSISTENT_ATTENDANCE,
            CAUTIOUS_CONTEXT,
            CEILING_REACHED,
            FLOOR_REACHED,
            PROGRESSION_UNAVAILABLE,
            REST_CHANGE_UNSUPPORTED,
            USER_AUTHORED_ELEMENT,
            AGGREGATE_LOAD_GUARD,
            RECOVERY_ENTERED,
            RECOVERY_HELD,
            RECOVERY_EXITED
        )

        val changeReasons: List<ProgramAdaptiveReason> = listOf(
            SUSTAINED_POSITIVE,
            SUSTAINED_NEGATIVE,
            RETURN_AFTER_GAP,
            BELOW_TARGET_CORRECTION,
            VARIANT_REALIGNED
        )
    }
}
