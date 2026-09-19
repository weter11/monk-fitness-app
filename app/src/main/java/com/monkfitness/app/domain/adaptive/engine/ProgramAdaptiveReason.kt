package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction

/**
 * Why the adaptive engine answered what it answered — one stable domain token per rule (§15, §22).
 *
 * A `HOLD` is a real result, and this vocabulary is what keeps it from being read as one thing. "There
 * was not enough history", "the history is there but it points both ways", "the user is plateaued",
 * "the change was earned but is cooling down", "the next step is not available in the user's own
 * selection" and "the aggregate load guard refused it" are six different facts, and a program's
 * decision trail has to be able to tell them apart: a screen showing *"no change"* for all six is the
 * silent-failure shape §22 exists to prevent.
 *
 * The tokens are stable identifiers and not user-facing text (§25): the UI layer localizes them, and
 * nothing here invents a sentence.
 *
 * Fields record the action each reason accompanies, and the two lists are exhaustive — a reason belongs
 * to one side or the other:
 *
 *  * [holdingReasons] — everything that ends in `HOLD` (including `RECOVERY_ENTERED`, `RECOVERY_HELD`
 *    and `RECOVERY_EXITED`, which are `HOLD` decisions about a family that is in the recovery *state*);
 *  * [changeReasons] — the three reasons that accompany a change the engine is asking the plan's
 *    presentation for.
 */
enum class ProgramAdaptiveReason {

    /** Nothing comparable was measured, or what was measured is not strong enough to justify a change. */
    INSUFFICIENT_EVIDENCE,

    /** The evidence is there, and the confidence in it is not: two judgements, one gate each (§12). */
    LOW_CONFIDENCE,

    /** The history is long enough and steady: neither direction is earned, so nothing is asked for. */
    STABLE_PLATEAU,

    /** The window points in different directions at once — a plateau with a shortfall, for instance. */
    MIXED_EVIDENCE,

    /** The conditions for a change held in this window and not in the previous one(s) yet (§15). */
    AWAITING_CONFIRMATION,

    /** A change was earned and is held back by the progression cooldown (§15). */
    PROGRESSION_COOLDOWN,

    /** The recent context is already above what the plan prescribes, so nothing is added to it. */
    RECENT_LOAD_ELEVATED,

    /** The attendance over the window's opportunities is too low for a change to be justified. */
    INCONSISTENT_ATTENDANCE,

    /** §14's recovery context is cautious or unknown, so progression is not asked for. */
    CAUTIOUS_CONTEXT,

    /** The family is at the top of its own hierarchy: §15's ceiling stops the progression. */
    CEILING_REACHED,

    /** The family is at the bottom of its own hierarchy: §15's floor stops the regression. */
    FLOOR_REACHED,

    /**
     * The family's own relation declares no single step in the ordered direction, or the variant it
     * declares is not in the user's own selection: a bounded non-progressing answer, never a
     * substituted exercise (§9, §15).
     */
    PROGRESSION_UNAVAILABLE,

    /**
     * A rest adaptation was asked for. `REST_BASED` is named and unimplemented (§10), so a rest change
     * cannot be expressed as a presentation and is reported as unsupported rather than faked.
     */
    REST_CHANGE_UNSUPPORTED,

    /**
     * The element is the user's own content. §18 guards only automatic changes and §15 keeps adaptive
     * logic away from a pinned choice, so nothing is even evaluated: the reason records whose element
     * this was, so the trail shows that the engine stayed out of the user's way.
     */
    USER_AUTHORED_ELEMENT,

    /** A change was earned, and §18's aggregate load guard refused it. The decision keeps the reason. */
    AGGREGATE_LOAD_GUARD,

    /** §14's safety path: strong evidence of unabsorbed load switched the family into the recovery state. */
    RECOVERY_ENTERED,

    /** The family is in recovery and its exit gate has not been met yet. */
    RECOVERY_HELD,

    /** The family's recovery exit gate is met: it leaves recovery to `HOLD`, and never to a change. */
    RECOVERY_EXITED,

    /** Confirmed positive performance: the family is asked to move up its own hierarchy. */
    SUSTAINED_POSITIVE,

    /** Confirmed negative performance: the family is asked to move down its own hierarchy. */
    SUSTAINED_NEGATIVE,

    /** The presented exercise is not a variant the family declares as available, and another one is. */
    VARIANT_REALIGNED;

    /** Whether this reason accompanies a `HOLD`. */
    val isHold: Boolean
        get() = this in holdingReasons

    /** Whether this reason accompanies a change the engine is asking the presentation for. */
    val isChange: Boolean
        get() = this in changeReasons

    /** The action this reason accompanies. */
    val action: AdaptiveAction
        get() = when (this) {
            SUSTAINED_POSITIVE -> AdaptiveAction.PROGRESS
            SUSTAINED_NEGATIVE -> AdaptiveAction.REGRESS
            VARIANT_REALIGNED -> AdaptiveAction.CHANGE_VARIANT
            else -> AdaptiveAction.HOLD
        }

    companion object {

        /** Every reason that ends in `HOLD`. */
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

        /** Every reason that accompanies a change. */
        val changeReasons: List<ProgramAdaptiveReason> = listOf(
            SUSTAINED_POSITIVE,
            SUSTAINED_NEGATIVE,
            VARIANT_REALIGNED
        )
    }
}
