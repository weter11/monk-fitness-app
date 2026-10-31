package com.monkfitness.app.domain.adaptive

/**
 * The four adaptation states. [HOLD] is the default and the normal outcome — insufficient evidence, mixed
 * signals or a stable plateau — and never that the user failed.
 *
 * The declaration order is readability only, never priority: a state always comes from the evidence.
 * [RECOVERY] is the one state the policy reads back from the caller, because its exit gate is counted in
 * qualifying sessions rather than decided from a single window's signals.
 */
enum class AdaptiveState {
    HOLD,
    PROGRESS,
    REGRESS,
    RECOVERY
}