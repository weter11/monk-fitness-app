package com.monkfitness.app.domain.adaptive

/**
 * How much evidence there is for a scope. `INSUFFICIENT` is the honest answer for a new exercise, a
 * changed variant, a long gap or a barely-trained scope; a level is a statement about the evidence, never
 * about the user's physiology.
 *
 * **Confidence is a separate judgement** ([ConfidenceLevel]): these levels say how much history there is,
 * that one says how far it can be trusted. Collapsing the two into one number is the fake precision the
 * architecture rejects. Values are persisted by name.
 */
enum class EvidenceLevel {
    INSUFFICIENT,
    STABLE,
    STRONG
}

/**
 * How far the evidence can be trusted for this decision: a three-level ordering with no numeric mapping
 * and no thresholds of its own. Which inputs raise or lower it is the policy's business. Values are
 * persisted by name.
 */
enum class ConfidenceLevel {
    LOW,
    MODERATE,
    HIGH
}

/**
 * The recovery context a decision is made in. **Context, not diagnosis**: there is no recovery score, no
 * readiness percentage and no countdown, and no universal hard rule (no fixed 48-hour cooldown) hides in
 * here. It may make progression more conservative; it never moves a workout or rewrites history, and
 * inactivity is neither a failure nor evidence of anything. Values are persisted by name.
 */
enum class RecoveryContext {
    FAVORABLE,
    CAUTIOUS,
    UNKNOWN
}
