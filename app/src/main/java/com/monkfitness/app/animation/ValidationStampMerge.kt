package com.monkfitness.app.animation

import com.monkfitness.app.BuildConfig

/**
 * R4/R6 — single merge surface for multi-producer Validation Stamps
 * (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md, Phase 2; RFC_RUNTIME_SKELETON_ARCHITECTURE
 * §5 R4, §5 R6, §4.4 merge-rule column).
 *
 * Each function implements exactly the frozen merge rule of its stamp:
 *  - [clamp]    — Clamp Stamp, rule **max** (a larger clamp reading is a stronger finding);
 *  - [verified] — Bone-Lengths-Verified Flag, rule **AND** (a violated limb further restricts
 *                 the claim — "further-restricting" is the strengthen direction fixed by §4.4);
 *  - [dropped]  — Straight-Intent-Dropped Flag, rule **OR** (any dropped limb strengthens).
 *
 * A secondary producer may only strengthen (R6): every function carries a debug-only
 * [check] that the result strengthens the prior value per its rule. Release builds compile
 * the check out and keep the pure merge (unit tests run with `BuildConfig.DEBUG == true`,
 * so the JVM suite exercises the checks).
 *
 * Scope note (Phase 2 decision F2): the once-per-build re-arm of the Bone-Lengths-Verified
 * Flag (the `isTransformsUpdated`-gated reset in the Active Limb Solver implementations) is
 * NOT routed through this object. A re-arm opens a fresh write window before any current-
 * build producer has spoken; it is not a strengthening merge.
 */
object ValidationStampMerge {

    /** Clamp Stamp merge (rule: max, RFC §4.4). Monotone non-decreasing in both inputs. */
    fun clamp(old: Float, reading: Float): Float {
        val merged = if (reading > old) reading else old
        if (BuildConfig.DEBUG) {
            check(merged >= old) { "R6 violation: clamp merge weakened the prior stamp" }
        }
        return merged
    }

    /**
     * Bone-Lengths-Verified Flag merge (rule: AND, RFC §4.4). Further-restricting:
     * a violated limb strengthens the claim toward `false`, and a primary `false` can
     * never be restored by a secondary reading.
     */
    fun verified(old: Boolean, reading: Boolean): Boolean {
        val merged = old && reading
        if (BuildConfig.DEBUG) {
            check(old || !merged) { "R6 violation: verified merge weakened the prior stamp" }
        }
        return merged
    }

    /** Straight-Intent-Dropped Flag merge (rule: OR, RFC §4.4). Producers arrive in Phase 4. */
    fun dropped(old: Boolean, dropped: Boolean): Boolean {
        val merged = old || dropped
        if (BuildConfig.DEBUG) {
            check(merged >= old) { "R6 violation: dropped merge strengthened beyond the prior stamp" }
        }
        return merged
    }
}
