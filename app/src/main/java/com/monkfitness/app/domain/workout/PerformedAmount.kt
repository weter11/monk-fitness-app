package com.monkfitness.app.domain.workout

/**
 * The "no limit" amount an unset or until-failure target carries; at or above it a value is a
 * placeholder — 27 hours of held work — rather than a performance.
 */
const val PLACEHOLDER_PERFORMED_AMOUNT: Int = 99_999

/** Whether [amount] is a measurement a person can have performed, in either unit. */
fun isPerformedAmount(amount: Int): Boolean = amount in 1 until PLACEHOLDER_PERFORMED_AMOUNT

/** Whether a set records a measured amount in its own unit rather than a placeholder. */
fun SetResult.isMeasuredSet(): Boolean =
    isPerformedAmount(if (durationSeconds > 0) durationSeconds else completedReps)
