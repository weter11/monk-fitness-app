package com.monkfitness.app.animation

import com.monkfitness.app.BuildConfig

/**
 * Single merge surface for multi-producer Validation Stamps. Each function implements exactly the
 * frozen merge rule of its stamp: [clamp] max (a larger clamp reading is the stronger finding),
 * [verified] AND (a violated limb further restricts the claim), [dropped] OR (any dropped limb
 * strengthens).
 *
 * A secondary producer may only strengthen the prior value: each function carries a debug-only
 * `check` for that, which release builds compile out and keep the pure merge.
 *
 * The once-per-build re-arm of the Bone-Lengths-Verified flag (the `isTransformsUpdated`-gated
 * reset in the Active Limb Solver implementations) is NOT routed through here: a re-arm opens a
 * fresh write window before any current-build producer has spoken, so it is not a merge.
 */
object ValidationStampMerge {

    /** Clamp Stamp merge (max). Monotone non-decreasing in both inputs. */
    fun clamp(old: Float, reading: Float): Float {
        val merged = if (reading > old) reading else old
        if (BuildConfig.DEBUG) {
            check(merged >= old) { "R6 violation: clamp merge weakened the prior stamp" }
        }
        return merged
    }

    /**
     * Bone-Lengths-Verified flag merge (AND): a violated limb strengthens the claim toward
     * `false`, and a primary `false` can never be restored by a secondary reading.
     */
    fun verified(old: Boolean, reading: Boolean): Boolean {
        val merged = old && reading
        if (BuildConfig.DEBUG) {
            check(old || !merged) { "R6 violation: verified merge weakened the prior stamp" }
        }
        return merged
    }

    /** Straight-Intent-Dropped flag merge (OR). */
    fun dropped(old: Boolean, dropped: Boolean): Boolean {
        val merged = old || dropped
        if (BuildConfig.DEBUG) {
            check(merged >= old) { "R6 violation: dropped merge strengthened beyond the prior stamp" }
        }
        return merged
    }
}
