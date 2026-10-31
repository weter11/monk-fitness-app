package com.monkfitness.app.domain.adaptive.integration

import java.time.Duration
import java.time.Instant

/**
 * How far back a decision window reaches. The lookback is owned here and not by the engine, which is told
 * a window's earliest instant and may read no clock — a window length hidden inside it would be a rule
 * with no owner.
 *
 * Four weeks is long enough for a plan training a family once or twice a week to hold the comparable
 * exposures the trend and progression gates require, and short enough that the window reads a recent
 * direction rather than the whole history. It is a policy decision, not a derived constant: a plan that
 * trains a family daily would want shorter, one that trains it twice a month longer.
 */
data class AdaptiveWindowRule(val lookback: Duration) {

    init {
        require(!lookback.isNegative && !lookback.isZero) {
            "a decision window reaches back a positive interval, was $lookback"
        }
    }

    /** The earliest instant a window captured at [capturedAt] may consider, inclusive. */
    fun windowStart(capturedAt: Instant): Instant = capturedAt.minus(lookback)

    companion object {

        val V1: AdaptiveWindowRule = AdaptiveWindowRule(Duration.ofDays(28))
    }
}
