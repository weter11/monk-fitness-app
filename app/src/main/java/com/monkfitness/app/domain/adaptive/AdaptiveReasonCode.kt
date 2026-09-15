package com.monkfitness.app.domain.adaptive

/**
 * Stable domain identifiers for why a decision came out the way it did.
 *
 * These are enum names, not user-facing prose: the UI/localization layer maps them to text, and a
 * future explanation layer may narrate them without gaining any authority over the decision. They
 * are part of the persisted audit trail ([AdaptiveDecision.policyVersion] pins the policy they were
 * produced under), so they may be added but not freely renamed.
 *
 * [CUSTOM_CONFIGURATION_LIMITATION] is reserved for the custom-program stage: v1 can represent it,
 * and never emits it.
 */
enum class AdaptiveReasonCode {
    /** HOLD: the evidence is too thin, mixed, or a stable plateau — no change is warranted. */
    INSUFFICIENT_EVIDENCE,

    /** PROGRESS: the progress conditions held in two consecutive decision windows. */
    SUSTAINED_POSITIVE_PERFORMANCE,

    /** REGRESS: low exposure and a negative trend held in two consecutive decision windows. */
    SUSTAINED_DECLINE,

    /** RECOVERY: high recent load with deterioration or strongly reduced exposure. */
    HIGH_LOAD_DETERIORATION,

    /**
     * RECOVERY: entered for the prolonged low-exposure pattern (strong low exposure plus a negative
     * trend, with no HIGH load involved), still inside recovery, or released out of it to HOLD.
     */
    RECOVERY,

    /** HOLD: progression qualified, but the last level change for this family is too recent. */
    PROGRESSION_COOLDOWN,

    /** Reserved: the user's custom exercise configuration limits the available progression. */
    CUSTOM_CONFIGURATION_LIMITATION
}