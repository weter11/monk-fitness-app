package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * B2 (RFC_BRANCH_B_IMPLEMENTATION §2) — Finalizer intent consumers.
 *
 * B2 makes the §1.1 `spineIntent` and `jointIntents` carriers live: every trunk/hip/girdle/extremity
 * authoring helper forwards its intent through the sole-mutator `IntentBuilder`, and the
 * [SkeletonPoseFinalizer] consumes those carriers ([SkeletonPoseFinalizer.applyIntentCarriers]) to
 * re-derive the declared node rotations and re-propagate FK. The helpers ALSO keep writing the node
 * during `build()` (so build-time logic that reads a node's world transform keeps working), so the
 * Finalizer's carrier re-application is idempotent — the rendered frame is byte-identical.
 *
 * (Phase B flag collapse) FINALIZER_CONSUMES_INTENT was collapsed to its true branch and removed,
 * so consumption is now unconditional. This suite asserts the carriers are populated and the
 * rendered frame is finite and deterministic.
 */
class FinalizerIntentConsumersTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun productionPoses(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "StaticForearmPlank" to { StaticForearmPlankPose() },
        "PikePushUp" to { PikePushUpPose() },
        "QuadrupedThoracicRotations" to { QuadrupedThoracicRotationsPose() },
        "DynamicWorldsGreatestStretch" to { DynamicWorldsGreatestStretchPose() },
        "AlternatingForwardLunges" to { AlternatingForwardLungesPose() }
    )

    private fun contactPoses(): List<Pair<String, () -> BaseValidationPose>> = listOf(
        "DeepOverheadSquat" to { DeepOverheadSquatPose() },
        "DeadHang" to { DeadHangPose() },
        "MiddleSplit" to { MiddleSplitPose() },
        "PikeSit" to { PikeSitPose() }
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
    fun carriersPopulatedByTrunkHipPoses() {
        for ((name, factory) in productionPoses()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name must populate jointIntents (B2 carrier live) got=${pose.jointIntents.size}", pose.jointIntents.isNotEmpty())
        }
        for ((name, factory) in contactPoses()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name must populate jointIntents (B2 carrier live) got=${pose.jointIntents.size}", pose.jointIntents.isNotEmpty())
        }
    }

    @Test
    fun productionPosesRenderDeterministic() {
        var maxDev = 0f
        var worst = ""
        for ((name, factory) in productionPoses()) {
            for (i in 0..20) {
                val p = i / 20f
                val ctx = PoseContext(p, Side.LEFT, def)
                val a = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                val b = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                val d = maxDeviation(a, b)
                if (d > maxDev) { maxDev = d; worst = "$name @$p" }
            }
        }
        assertEquals("Finalizer intent consumption must render deterministically at $worst", 0f, maxDev, 1e-4f)
    }

    @Test
    fun contactPosesRenderDeterministic() {
        var maxDev = 0f
        var worst = ""
        for ((name, factory) in contactPoses()) {
            for (p in listOf(0f, 0.5f, 1f)) {
                val ctx = PoseContext(p, Side.LEFT, def)
                val a = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                val b = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                val d = maxDeviation(a, b)
                if (d > maxDev) { maxDev = d; worst = "$name @$p" }
            }
        }
        assertEquals("Finalizer intent consumption must render contact instruments deterministically at $worst", 0f, maxDev, 1e-4f)
    }
}
