package com.monkfitness.app.domain.adaptive.decision

/**
 * What the adaptive stage may ask for (§15).
 *
 * The action space is small on purpose, and every rule that keeps it safe is a rule about this enum:
 *
 *  * **one bounded primary change per decision** — an action asks for one thing, not a bundle;
 *  * `HOLD` is a first-class result, not the absence of one. It is the correct answer whenever the
 *    evidence is not there, and it is what a decision reports when the load guard filters it out;
 *  * one bad result does not automatically regress; repeated negative evidence may permit `REGRESS`;
 *  * `PROGRESS` requires sufficient evidence, a ceiling stops it, and a floor stops `REGRESS`;
 *  * `CHANGE_VARIANT` requires an explicit progression relation between the two variants — a variant
 *    swap that is not declared is not an adaptation;
 *  * the adaptive stage never changes a pinned choice, never silently changes the focus, duration,
 *    frequency or schedule of a program, and never edits a session that has happened. Those are not
 *    actions it can take, which is why there is no action for them here.
 *
 * The labels describe direction, not magnitude: which concrete step, variant or value an action maps
 * to belongs to the progression hierarchy and to the policy, so no coefficients and no amounts appear
 * in this vocabulary.
 *
 * Values are persisted by name.
 */
enum class AdaptiveAction {

    /** Keep the current stimulus. A real result, and the default when nothing justifies a change. */
    HOLD,

    /** The current stimulus can be increased. */
    PROGRESS,

    /** The current stimulus is beyond what the user currently handles. */
    REGRESS,

    /** Move to a declared variant of the exercise (a progression relation, never a guess). */
    CHANGE_VARIANT,

    /** Change the rest of the element. The rest vocabulary arrives with `REST_BASED` (§10). */
    CHANGE_REST
}

/**
 * How a decision ended (§18).
 *
 * Two outcomes, and the second one is not an error: the aggregate load guard may filter a decision
 * out — a progression that would compound into too much accumulated load becomes a `HOLD` — and a
 * filtered-out decision **stays recorded** with `NOT_APPLIED` so that the reasoning is auditable
 * instead of silently disappearing. The guard does not invent a `REGRESS` in its place.
 *
 * An applied decision produced exactly one adjustment; a not-applied one produced none. That
 * equivalence is asserted on [AdaptiveDecision] itself, so the outcome can never disagree with the
 * adjustment it is describing.
 *
 * Values are persisted by name.
 */
enum class DecisionOutcome {
    APPLIED,
    NOT_APPLIED
}
