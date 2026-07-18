package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Branch B — B4 (Pose migration, family-by-family).
 *
 * B4 is the per-family migration from raw node-writing authoring to declarative intent: each
 * trunk/hip/girdle/extremity pose records its authored rotation as a `jointIntents` carrier
 * (via `declareJointIntent` / `IntentBuilder.joint`) while still setting the local node during
 * `build()` so build-time FK keeps working. The [SkeletonPoseFinalizer] then consumes the carrier
 * (B2) and re-derives the node rotation, idempotently.
 *
 * (Phase B flag collapse) FINALIZER_CONSUMES_INTENT was collapsed to its true branch and removed,
 * so consumption is now unconditional. This suite locks in the B4 contract for the migrated
 * families: every migrated family records a `jointIntents` entry, and the rendered frame is finite
 * and deterministic.
 */
class BranchBFamilyMigrationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun migratedFamilies(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "ThoracicExtension" to { ThoracicExtensionPose() },
        "IsometricSidePlank" to { IsometricSidePlankPose() },
        "HamstringStretch" to { HamstringStretchPose() },
        "CouchStretch" to { CouchStretchPose() },
        "QuadrupedThoracicRotations" to { QuadrupedThoracicRotationsPose() },
        "DynamicWorldsGreatestStretch" to { DynamicWorldsGreatestStretchPose() },
        "GluteBridge" to { GluteBridgePose() },
        "PelvicTilt" to { PelvicTiltPose() },
        "AirSquat" to { AirSquatPose() },
        "SumoSquat" to { SumoSquatPose() }
    )

    private fun maxDeviation(a: SkeletonPose, b: SkeletonPose): Float {
        var max = 0f
        for (j in Joint.entries) {
            val pa = a.getJoint(j); val pb = b.getJoint(j)
            val d = maxOf(abs(pa.x - pb.x), abs(pa.y - pb.y), abs(pa.z - pb.z))
            if (d > max) max = d
        }
        return max
    }

    @Test
    fun migratedFamiliesRecordJointIntents() {
        for ((name, factory) in migratedFamilies()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name must record a joint intent after B4 migration (got ${pose.jointIntents.size})", pose.jointIntents.isNotEmpty())
        }
    }

    @Test
    fun migratedFamiliesRenderDeterministic() {
        var maxDev = 0f
        var worst = ""
        for ((name, factory) in migratedFamilies()) {
            for (i in 0..20) {
                val p = i / 20f
                val ctx = PoseContext(p, Side.LEFT, def)
                val a = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                val b = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                val d = maxDeviation(a, b)
                if (d > maxDev) { maxDev = d; worst = "$name @$p" }
            }
        }
        assertEquals("B4 migration must render migrated families deterministically at $worst", 0f, maxDev, 1e-4f)
    }
}
