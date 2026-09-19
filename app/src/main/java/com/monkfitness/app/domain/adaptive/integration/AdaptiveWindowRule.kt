package com.monkfitness.app.domain.adaptive.integration

import java.time.Duration
import java.time.Instant

/**
 * How far back a decision window reaches — the **lookback interval**, owned here and not by the engine.
 *
 * The engine is told a window's earliest instant ([com.monkfitness.app.domain.adaptive
 * .AdaptiveInputSnapshot.windowStart]) and derives nothing from it: §30 step 11 forbids the engine a
 * clock, so a window length hidden inside it would be a rule with no owner. This type is that owner —
 * one value, one place, stated as an interval from the moment the window is captured, which is the only
 * moment the integration knows.
 *
 * ### Why v1 is four weeks
 *
 * The rules the window has to feed are §13's trend (which needs a minimum number of comparable
 * exposures to exist at all) and §7's progression and regression conditions (which need more), and those
 * are all counted over the exposures the window contains. Four weeks is long enough for a plan that
 * trains a family once or twice a week to hold the several comparable exposures those gates require,
 * and short enough that a family's recent direction is what the window reads rather than its whole
 * history. It is a **decision** of the integration's policy owner, not a derived constant: a program
 * whose plan trains a family daily would be well served by a shorter interval, and one that trains it
 * twice a month by a longer one.
 *
 * ### What it does not do
 *
 * It computes no date, reads no calendar and knows no zone: [windowStart] subtracts an interval from an
 * instant, which is the whole arithmetic. Where a *date* has to be compared against the window — the
 * plan's opportunities for a family fall on the calendar — that conversion is the integration's, made
 * in the zone it was given, because the slot's date and the capture's instant are different facts
 * (§20, §26).
 *
 * @property lookback how far before the capture the window begins. Must be positive: a window of no
 *   length reaches no exposure at all, which is not a window but a mistake.
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

        /** The documented v1 lookback: four weeks (see the type's own note on why). */
        val V1: AdaptiveWindowRule = AdaptiveWindowRule(Duration.ofDays(28))
    }
}
