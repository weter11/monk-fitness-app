package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * R2 — Reach target authoring invariants (see docs/ENGINE_DEFECT_REMEDIATION_PLAN.md).
 *
 * `clampTargetToReach` projects an authored IK target onto the reachable annulus
 * `[minReach, maxReach]` so poses are reachable-by-construction and the solver records zero
 * clamp (the IK_TARGET_UNREACHABLE signal stays honest — we fix the target, not the validator).
 */
class ReachTargetTest {

    private val L1 = 80f   // upper arm
    private val L2 = 66f   // forearm
    private val c = IKConstraint.ArmConstraint
    private val root = Vector3(0f, 0f, 0f)

    private fun dist(a: Vector3, b: Vector3) =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    @Test
    fun inBandTargetIsUnchanged() {
        val mid = (SkeletonMath.minReach(L1, L2, c) + SkeletonMath.maxReach(L1, L2, c)) / 2f
        val target = Vector3(mid, 0f, 0f)
        val out = Vector3()
        SkeletonMath.clampTargetToReach(root, target, L1, L2, c, out)
        assertEquals(target.x, out.x, 1e-4f)
        assertEquals(target.y, out.y, 1e-4f)
        assertEquals(target.z, out.z, 1e-4f)
    }

    @Test
    fun tooCloseTargetIsPushedToMinReach() {
        // 9 units — the Sumo "hands at crotch" case (well below minReach ~40).
        val target = Vector3(9f, 0f, 0f)
        val out = Vector3()
        SkeletonMath.clampTargetToReach(root, target, L1, L2, c, out)
        val d = dist(root, out)
        assertTrue("must be at/above minReach", d >= SkeletonMath.minReach(L1, L2, c))
        assertTrue("must stay within maxReach", d <= SkeletonMath.maxReach(L1, L2, c))
        // direction preserved (still along +X)
        assertTrue(out.x > 0f)
    }

    @Test
    fun tooFarTargetIsPulledToMaxReach() {
        // 318 units — the KneePushUp gross-overreach magnitude.
        val target = Vector3(318f, 0f, 0f)
        val out = Vector3()
        SkeletonMath.clampTargetToReach(root, target, L1, L2, c, out)
        val d = dist(root, out)
        assertTrue("must be within maxReach", d <= SkeletonMath.maxReach(L1, L2, c))
        assertTrue("must be at/above minReach", d >= SkeletonMath.minReach(L1, L2, c))
    }

    @Test
    fun clampedTargetProducesNoSolverClamp() {
        // A too-far target, once clamped, must solve with zero clamp (honest reachability).
        val target = Vector3(0f, -318f, 0f)
        val out = Vector3()
        SkeletonMath.clampTargetToReach(root, target, L1, L2, c, out)
        val res = SkeletonMath.solveIK(root, out, L1, L2, Vector3(0f, 0f, 1f), c)
        assertEquals("solver must record no clamp for an in-band target", 0f, res.clampAmount, 0.1f)
    }
}
