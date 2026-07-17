package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Branch B (RFC_BRANCH_B_IMPLEMENTATION §2 B1) — IkStage extraction byte-identity guard.
 *
 * B1 extracted limb solving out of the pose's `bakeIkLimb` (now a forward that only records a
 * [WorldTarget]) into the pipeline-owned [IkStage], which replays the identical [SkeletonMath]
 * solve on the built node tree and writes the limb `localPosition`. This test pins that the
 * extraction preserved geometry exactly:
 *
 *  1. **Carrier live** — every limb-bearing pose now populates `limbTargets` (the dead→live
 *     flip `Section11CarriersTest` also asserts).
 *  2. **Faithful replay** — for each recorded [WorldTarget], [IkStage] writes the exact same
 *     `localPosition` values the *legacy* `bakeIkLimb` body produced, because both call the
 *     same [SkeletonMath] solve with the same captured inputs. The test replays the legacy core
 *     math independently and asserts equality with the tree the stage wrote (maxDev ≈ 0).
 *  3. **End-to-end pipeline** — `SkeletonPipeline.produceFrame` (IkStage → Solver → Finalizer)
 *     runs without error and the limb targets remain read-only (size unchanged by the engine).
 *
 * The end-to-end geometry is additionally pinned by the existing contact-instrument tests
 * ([ConstraintSolverPhase2Test], [ValidatorRomClusterTest], the `*PoseTest` family), which assert
 * specific world positions; they stay green because B1 is byte-identical.
 */
class IkStageB1Test {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun nodeByJoint(pose: SkeletonPose): Map<Joint, SkeletonNode> {
        val map = mutableMapOf<Joint, SkeletonNode>()
        fun visit(n: SkeletonNode) { map[n.joint] = n; n.children.forEach { visit(it) } }
        pose.roots.forEach { visit(it) }
        return map
    }

    /** Replays the legacy `bakeIkLimb` core math on a fresh localPosition pair for [spec]. */
    private fun expectedLocals(
        spec: WorldTarget,
        nodes: Map<Joint, SkeletonNode>
    ): Pair<Vector3, Vector3> {
        val parentRot = spec.parentRotation
        val worldPole = if (spec.pole.mag() < 1e-4f) {
            SkeletonMath.deriveDefaultPole(spec.rootWorld, spec.targetWorld, Vector3())
        } else spec.pole
        val buf = SkeletonMath.IKResult()
        val ik = if (spec.straight) {
            SkeletonMath.solveStraightLimb(spec.rootWorld, spec.targetWorld, spec.length1, spec.length2, spec.constraint, buf, spec.contact)
        } else {
            SkeletonMath.solveIK(spec.rootWorld, spec.targetWorld, spec.length1, spec.length2, worldPole, spec.constraint, buf)
        }
        val mid = Vector3(); val end = Vector3()
        val v = Vector3()
        v.set(ik.joint).subtract(spec.rootWorld)
        SkeletonMath.toLocalDirection(v, parentRot, mid)
        v.set(ik.end).subtract(ik.joint)
        SkeletonMath.toLocalDirection(v, parentRot, end)
        return Pair(mid, end)
    }

    private fun assertLimbBakeMatches(pose: SkeletonPose, label: String) {
        assertTrue("$label: limbTargets must be live (B1 dead→live) got=${pose.limbTargets.size}", pose.limbTargets.size > 0)
        val nodes = nodeByJoint(pose)
        for (spec in pose.limbTargets) {
            val (expMid, expEnd) = expectedLocals(spec, nodes)
            val midNode = nodes[spec.middleJoint]!!
            val endNode = nodes[spec.endJoint]!!
            assertEquals("$label: middle $midNode localPosition.x", expMid.x, midNode.localPosition.x, 1e-4f)
            assertEquals("$label: middle $midNode localPosition.y", expMid.y, midNode.localPosition.y, 1e-4f)
            assertEquals("$label: middle $midNode localPosition.z", expMid.z, midNode.localPosition.z, 1e-4f)
            assertEquals("$label: end $endNode localPosition.x", expEnd.x, endNode.localPosition.x, 1e-4f)
            assertEquals("$label: end $endNode localPosition.y", expEnd.y, endNode.localPosition.y, 1e-4f)
            assertEquals("$label: end $endNode localPosition.z", expEnd.z, endNode.localPosition.z, 1e-4f)
        }
    }

    @Test
    fun deadHangLimbBakeMatchesLegacyReplay() {
        val pose = DeadHangPose().build(PoseContext(0.5f, Side.LEFT, def))
        // Isolate the IkStage from the contact Solver: run only the stage on the built tree.
        val stage = IkStage(def)
        stage.solve(pose)
        assertLimbBakeMatches(pose, "DeadHang")
    }

    @Test
    fun middleSplitLimbBakeMatchesLegacyReplay() {
        val pose = MiddleSplitPose().build(PoseContext(0.5f, Side.LEFT, def))
        val stage = IkStage(def)
        stage.solve(pose)
        assertLimbBakeMatches(pose, "MiddleSplit")
    }

    @Test
    fun pikeSitLimbBakeMatchesLegacyReplay() {
        val pose = PikeSitPose().build(PoseContext(0.5f, Side.LEFT, def))
        val stage = IkStage(def)
        stage.solve(pose)
        assertLimbBakeMatches(pose, "PikeSit")
    }

    @Test
    fun deepOverheadSquatLimbBakeMatchesLegacyReplay() {
        val pose = DeepOverheadSquatPose().build(PoseContext(0.5f, Side.LEFT, def))
        val stage = IkStage(def)
        stage.solve(pose)
        assertLimbBakeMatches(pose, "DeepOverheadSquat")
    }

    @Test
    fun pipelineProduceFrameKeepsLimbTargetsReadOnly() {
        // The engine consumes (reads) limbTargets but never appends to the carrier list.
        for (pair in listOf(
            "DeadHang" to { DeadHangPose() },
            "MiddleSplit" to { MiddleSplitPose() },
            "PikeSit" to { PikeSitPose() },
            "DeepOverheadSquat" to { DeepOverheadSquatPose() }
        )) {
            val name = pair.first
            val factory = pair.second
            val built = factory().build(PoseContext(0.5f, Side.LEFT, def))
            val before = built.limbTargets.size
            assertTrue("$name: limbTargets live before pipeline", before > 0)
            val out = SkeletonPipeline(def).produceFrame(factory(), PoseContext(0.5f, Side.LEFT, def))
            assertEquals("$name: limbTargets size unchanged by pipeline (read-only)", before, out.pose.limbTargets.size)
        }
    }

    @Test
    fun ikStageIsNoOpForEmptyLimbTargets() {
        // A pose that declares no limbs must not be touched by the stage.
        val pose = SkeletonPose()
        pose.roots = SkeletonFactory.createStandardSkeleton().roots
        val stage = IkStage(def)
        stage.solve(pose) // must not throw and leaves limbTargets empty
        assertEquals(0, pose.limbTargets.size)
    }
}
