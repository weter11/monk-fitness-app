package com.monkfitness.app

import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.poses.GluteBridgePose
import com.monkfitness.app.poses.StandardPushUpPose
import com.monkfitness.app.validation.poses.DeadHangPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the carrier-accumulation leak (audit 2026-08-23): pose instances are
 * long-lived singletons reused across animation frames. Before the fix, [SkeletonPose.IntentBuilder.reset]
 * had zero callers, so every build appended entries into the builder's reusable `jointsBuffer`
 * (+1–2 [com.monkfitness.app.animation.RelativeArticulation] per frame on non-contact poses),
 * growing the Finalizer's per-frame consume cost linearly forever.
 *
 * The invariant is asserted on the builder's INTERNAL buffer, mirroring how the audit measured
 * the bug: `build()` returns the post-Finalizer pose whose §1.1 intent carriers are legitimately
 * consumed (emptied), so the leak is only observable on the reused buffer itself. White-box
 * access via reflection matches the precedent in [SquatFamilyConsistencyTest].
 *
 * Contract: buffer carrier counts are a pure function of one build — repeated builds on the
 * SAME instance must not grow them (per-frame intent complexity locked to O(1)), and the test
 * must prove it exercises real carrier writes (non-zero totals), so it cannot pass vacuously.
 */
class CarrierHygieneTest {

    private fun context(def: SkeletonDefinition) = PoseContext(
        progress = 0.5f, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** Carrier counts on the builder's reusable buffer, found walking the class hierarchy. */
    private fun bufferCarrierSizes(pose: PoseBuilder): List<Int> {
        var c: Class<*>? = pose.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("jointsBuffer")
                f.isAccessible = true
                val buf = f.get(pose) as SkeletonPose
                return listOf(
                    buf.jointIntents.size,
                    buf.limbTargets.size,
                    buf.extremityArticulations.size,
                    buf.contacts.size
                )
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw AssertionError("${pose.javaClass.simpleName}: no jointsBuffer field found")
    }

    /**
     * Builds once on a fresh instance, snapshots the buffer's carrier counts, then builds 60
     * more times on the SAME instance and re-snapshots. The counts must be identical.
     */
    private fun assertNoAccumulation(name: String, makePose: () -> PoseBuilder) {
        val pose = makePose()
        val ctx = context(SkeletonDefinition.DEFAULT_ADULT)

        pose.build(ctx)
        val afterFirst = bufferCarrierSizes(pose)
        assertTrue(
            "$name: test must exercise real carrier writes (got $afterFirst) — " +
                "a zero-write probe cannot detect accumulation",
            afterFirst.sum() > 0
        )
        repeat(60) { pose.build(ctx) }
        assertEquals(
            "$name: repeated builds on the same instance must not accumulate carriers",
            afterFirst,
            bufferCarrierSizes(pose)
        )
    }

    @Test
    fun basePoseFamily_repeatedBuildsDoNotAccumulate() {
        assertNoAccumulation("StandardPushUpPose (BasePose family)") { StandardPushUpPose() }
    }

    @Test
    fun directImpl_repeatedBuildsDoNotAccumulate() {
        assertNoAccumulation("GluteBridgePose (direct PoseBuilder)") { GluteBridgePose() }
    }

    @Test
    fun validationPose_repeatedBuildsDoNotAccumulate() {
        assertNoAccumulation("DeadHangPose (BaseValidationPose)") { DeadHangPose() }
    }
}
