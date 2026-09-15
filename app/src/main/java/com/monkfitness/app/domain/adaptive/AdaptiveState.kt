package com.monkfitness.app.domain.adaptive

/**
 * The four adaptation states. [HOLD] is the default and the normal outcome: it means insufficient
 * evidence, mixed signals or a stable plateau, and never that the user failed.
 *
 * Declared in emission-priority order for readability only — a decision's state always comes from
 * the evidence, never from this ordering:
 *
 *  * [RECOVERY] is the one state the policy reads back from the caller, because its exit gate is
 *    counted in qualifying sessions rather than decided from a single window's signals;
 *  * [PROGRESS] and [REGRESS] are only emitted when their conditions hold in two consecutive
 *    decision windows (see `AdaptivePolicy.progressConfirmingWindows`);
 *  * anything else is [HOLD].
 */
enum class AdaptiveState {
    HOLD,
    PROGRESS,
    REGRESS,
    RECOVERY
}