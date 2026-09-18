package com.monkfitness.app.domain.adaptive

/**
 * The granularity an adaptive fact is stated at (§18).
 *
 * The aggregate load guard compares a baseline against a candidate at four levels — one exercise, one
 * family (a set of interchangeable progression variants), one focus (how much of the plan's attention
 * a training area gets) and one whole session — and an adaptive decision names the level it changes.
 * Keeping the scope explicit is what stops two facts measured at different levels from being compared:
 * "more work than last time" means something different for one exercise and for a session.
 *
 * `FOCUS` is a scope, not a focus vocabulary: which focuses exist is the Focus Planner's vocabulary
 * (§8) and is deliberately not enumerated here. A focus is named by its id, exactly as an exercise is
 * named by its `exerciseId`, so the domain never carries a second copy of a catalogue it does not own.
 *
 * Values are persisted by name.
 */
enum class AdaptiveScope {
    EXERCISE,
    FAMILY,
    FOCUS,
    SESSION
}
