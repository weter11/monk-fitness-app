package com.monkfitness.app.domain.adaptive

/**
 * What the policy orders for the family in this decision window. `AdaptivePolicy` produces exactly
 * one action per decision at this stage; the list shape leaves room for the per-family expansion
 * later without changing this type.
 *
 * The actions stay abstract on purpose: which concrete difficulty step, variation or duration a
 * `INCREASE_STIMULUS` maps to is decided by the progression resolver from the family profile, not
 * here.
 */
enum class AdaptiveAction {
    /** HOLD: keep the current stimulus. */
    MAINTAIN_STIMULUS,

    /** PROGRESS: the current stimulus can be increased. */
    INCREASE_STIMULUS,

    /** REGRESS: the current stimulus is beyond what the user currently handles. */
    REDUCE_STIMULUS,

    /** RECOVERY: use the recovery profile for the next session; accumulated level is preserved. */
    RECOVERY_LOAD;

    companion object {
        fun of(state: AdaptiveState): AdaptiveAction = when (state) {
            AdaptiveState.HOLD -> MAINTAIN_STIMULUS
            AdaptiveState.PROGRESS -> INCREASE_STIMULUS
            AdaptiveState.REGRESS -> REDUCE_STIMULUS
            AdaptiveState.RECOVERY -> RECOVERY_LOAD
        }
    }
}

/**
 * One auditable adaptation decision. It is a value: the same evidence always yields the same
 * decision, and a decision can be persisted as-is (state, ordered actions, stable reason code, and
 * the policy version that produced it).
 *
 * [previousState] is the state the caller reported in `AdaptiveEvidence.currentState`, so the
 * transition itself is part of the record. Note that PROGRESS and REGRESS are *orders* for this
 * window, not a memory: a window that does not re-qualify reports HOLD again.
 */
data class AdaptiveDecision(
    val state: AdaptiveState,
    val previousState: AdaptiveState,
    val actions: List<AdaptiveAction>,
    val reasonCode: AdaptiveReasonCode,
    val policyVersion: Int
) {

    init {
        require(actions.isNotEmpty()) { "an adaptation decision carries at least one action" }
        require(policyVersion >= 1) { "policyVersion must be >= 1, was $policyVersion" }
    }

    /** True when this decision changes the adaptation state the caller reported. */
    val isTransition: Boolean
        get() = state != previousState
}