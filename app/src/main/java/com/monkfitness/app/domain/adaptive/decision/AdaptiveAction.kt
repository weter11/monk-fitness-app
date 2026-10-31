package com.monkfitness.app.domain.adaptive.decision

/**
 * What the adaptive stage may ask for. The space is small on purpose: one bounded primary change per
 * decision, and `HOLD` is a first-class result rather than the absence of one — it is also what a decision
 * reports when the load guard filters it out. `CHANGE_VARIANT` requires an explicit progression relation,
 * and the stage can take no action against a pinned choice, a focus, a schedule or a past session, which
 * is why there is no action for them.
 *
 * The labels describe direction, not magnitude: no coefficients and no amounts appear here. Values are
 * persisted by name.
 */
enum class AdaptiveAction {

    HOLD,

    PROGRESS,

    REGRESS,

    CHANGE_VARIANT,

    CHANGE_REST
}

/**
 * How a decision ended. The second value is not an error: a filtered-out decision **stays recorded** as
 * `NOT_APPLIED` so the reasoning is auditable instead of vanishing, and the guard never invents a
 * `REGRESS` in its place. Values are persisted by name.
 */
enum class DecisionOutcome {
    APPLIED,
    NOT_APPLIED
}
