package com.monkfitness.app.domain.adaptive

/**
 * How much evidence the adaptive stage has for a scope it is about to decide on (§12).
 *
 * Three qualitative levels, and no fourth meaning: `INSUFFICIENT` means there is not enough
 * comparable history to say anything — the honest answer for a new exercise, a changed variant, a
 * long gap, or a scope that has barely been trained; `STABLE` means the picture is consistent enough
 * to hold; `STRONG` means there is enough consistent history to change something. A level is a
 * statement about the evidence, never about the user's physiology.
 *
 * **Confidence is a separate judgement** ([ConfidenceLevel]): evidence says how much history there is,
 * confidence says how much that history can be trusted for this decision. Collapsing the two into one
 * number — or into a percentage — is precisely the fake precision the architecture rejects.
 *
 * Values are persisted by name.
 */
enum class EvidenceLevel {
    INSUFFICIENT,
    STABLE,
    STRONG
}

/**
 * How much the evidence for a scope can be trusted for the decision at hand (§12).
 *
 * The architecture fixes the *separation* between evidence and confidence and leaves the exact
 * vocabulary open, so this is the smallest scale that can carry the distinction: a three-level
 * qualitative ordering with no numeric mapping, no percentages and no thresholds of its own. Which
 * inputs raise or lower it is the policy's business, and the policy is not part of this foundation.
 *
 * Values are persisted by name.
 */
enum class ConfidenceLevel {
    LOW,
    MODERATE,
    HIGH
}

/**
 * The recovery context a decision is made in (§14).
 *
 * Recovery is **context, not diagnosis**: there is no recovery score, no readiness percentage and no
 * countdown, because none of those are facts the app has. What it can honestly say is whether recent
 * load, exposure, time since exposure, performance trend and the user's own caution point towards
 * being careful, towards being fine, or towards not knowing yet.
 *
 * Recovery may make progression more conservative. It may never move a workout, replace a focus,
 * cancel a program or rewrite history, and there is no universal hard rule (no fixed 48-hour
 * cooldown) hiding in this vocabulary. Inactivity and a paused program are not failures and are not
 * evidence of anything on their own.
 *
 * Values are persisted by name.
 */
enum class RecoveryContext {
    FAVORABLE,
    CAUTIOUS,
    UNKNOWN
}
