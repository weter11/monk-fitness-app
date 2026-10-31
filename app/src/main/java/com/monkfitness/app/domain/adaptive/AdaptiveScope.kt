package com.monkfitness.app.domain.adaptive

/**
 * The granularity an adaptive fact is stated at: one exercise, one family, one focus, or a whole session.
 *
 * Keeping the scope explicit is what stops two facts measured at different levels from being compared —
 * "more work than last time" means something different for one exercise and for a session. `FOCUS` is a
 * scope, not a focus vocabulary: which focuses exist belongs to the Focus Planner. Values are persisted by
 * name.
 */
enum class AdaptiveScope {
    EXERCISE,
    FAMILY,
    FOCUS,
    SESSION
}
