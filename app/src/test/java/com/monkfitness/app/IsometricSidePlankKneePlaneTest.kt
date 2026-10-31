package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The side plank's support knee must not pass through the mat the pose itself declares.
 *
 * Measured pre-fix at `p = 0.0`: `KNEE_B = (-100.9380, -25.8897, 0.0000)` — below the plane at all
 * 51 sampled phases under both frame conditions (worst `-25.8897`); the next lowest published joint
 * is `HIP_B` at `+3.0233`, the settled down-side hip resting on the mat.
 *
 * Why the geometry is forced, not tuned: the support stance is `ankleX = -190`, `ankleY = 15`, and
 * at the settled frame the down-side hip sits at `(7.2657, 3.0233)`, so the hip→ankle chord is
 * `197.6289` for a `112 / 98` chain (`maxReach = 205.8000`, `minReach = 56.0090`). That 8.17 units
 * of slack is unavoidable (the 0.98 extension cap forbids a perfectly straight chain) and fixes the
 * knee's IK locus: a circle of radius `h = 35.4166` centred `a = 106.2501` along the chord. The
 * authored pole `(0, -1, 0)` aims that bow straight INTO the mat — the knee lands on the `-Y` side
 * of a chord lying only `3.0 … 15.0` units above the plane, i.e. `25.8897` below it. The sibling
 * `StaticForearmPlankPose` already authors `(0, 1, 0)` for this reason ("residual knee bend points
 * up, never sagging through the floor"), and its leg carries a larger residual (`h = 46.6668`).
 *
 * All assertions read the PUBLISHED frame captured by value: `produceFrame(...).pose` is the
 * Finalizer's reused output buffer, so a stored frame aliases the next one.
 */
class IsometricSidePlankKneePlaneTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val name = "IsometricSidePlankPose"

    /** A DENSE sweep: the violation holds at every phase, so 5 samples cannot step over it. */
    private val sweep = (0..50).map { it * 0.02f }

    /** The plane band: a joint DERIVED onto the plane reads `0.000000`, so this absorbs float noise only. */
    private val planeBand = 0.05f

    /** A real floor for the support knee — an order of magnitude above [planeBand]. */
    private val clearanceFloor = 1.0f

    /** The family's planted-foot height (`BasePlankPose.contactY` mirrors `FootDefinition.ankleHeight`). */
    private val floorContactY = 15f

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** A frame captured BY VALUE (the pipeline publishes a reused buffer). */
    private fun snapshot(frame: SkeletonPose): SkeletonPose =
        SkeletonPose().apply { copyFrom(frame) }

    /** A genuinely COLD frame: a fresh pose instance on a fresh pipeline (the T2 COLD leg). */
    private fun coldFrame(p: Float): SkeletonPose =
        snapshot(SkeletonPipeline(def).produceFrame(MotionProbe.build(name), ctx(p)).pose)

    /** One builder + one pipeline advancing through the sweep, every frame captured by value. */
    private fun playingFrames(): List<Pair<Float, SkeletonPose>> {
        val pipeline = SkeletonPipeline(def)
        val builder = MotionProbe.build(name)
        return sweep.map { p -> p to snapshot(pipeline.produceFrame(builder, ctx(p)).pose) }
    }

    /** One (condition, progress, frame) triple per evaluated frame of both frame conditions. */
    private fun allFrames(): List<Triple<String, Float, SkeletonPose>> {
        val frames = mutableListOf<Triple<String, Float, SkeletonPose>>()
        for (p in sweep) frames.add(Triple("COLD", p, coldFrame(p)))
        for ((p, f) in playingFrames()) frames.add(Triple("PLAYING", p, f))
        return frames
    }

    private fun f(v: Float) = String.format(java.util.Locale.US, "%.4f", v)

    @Test
    fun noPublishedJointPassesBelowThePosesOwnDeclaredPlane() {
        // The plane is READ from the pose, never assumed to be zero.
        val ground = MotionProbe.build(name).metadata.environment.ground.level
        val frames = allFrames()
        val below = mutableListOf<String>()
        for ((condition, p, frame) in frames) {
            for (joint in Joint.entries) {
                val y = frame.getJoint(joint).y
                if (y < ground - planeBand) below.add("$condition p=$p $joint y=${f(y)} (${f(y - ground)} vs its plane)")
            }
        }
        assertEquals(
            "the sweep must evaluate every joint of every frame of both conditions",
            sweep.size * Joint.entries.size * 2, frames.size * Joint.entries.size
        )
        assertTrue(
            "published joints below this pose's own declared plane (level = $ground, band = $planeBand);\n" +
                "the support knee is the B3 pair — its bend must leave the mat, not pass through it:\n" +
                below.take(12).joinToString("\n"),
            below.isEmpty()
        )
    }

    /**
     * The knee's signed deviation from its own hip→ankle chord, in the world Y axis: the chord point
     * nearest the knee is the projection, and the residual bend is the offset of the realized knee
     * from it. `> 0` means the bow leaves the mat (upward); the pre-fix authoring measured it `< 0`
     * by the whole `h = 35.4166` of the chain's slack.
     */
    private fun bendOffsetY(frame: SkeletonPose): Float {
        val hip = frame.getJoint(Joint.HIP_B)
        val knee = frame.getJoint(Joint.KNEE_B)
        val ankle = frame.getJoint(Joint.ANKLE_B)
        val cx = ankle.x - hip.x; val cy = ankle.y - hip.y; val cz = ankle.z - hip.z
        val vx = knee.x - hip.x; val vy = knee.y - hip.y; val vz = knee.z - hip.z
        val len2 = cx * cx + cy * cy + cz * cz
        val t = (vx * cx + vy * cy + vz * cz) / len2
        val nx = cx * t - vx; val ny = cy * t - vy; val nz = cz * t - vz
        return -ny
    }

    @Test
    fun theSupportKneeLeavesTheMatOnTheUpSide() {
        val frames = allFrames()
        val deepest = frames.minBy { it.third.getJoint(Joint.KNEE_B).y }
        val kneeY = deepest.third.getJoint(Joint.KNEE_B).y
        assertTrue(
            "the support knee must stay above the mat this pose declares, not pass through it " +
                "(worst ${f(kneeY)} at ${deepest.first} p=${deepest.second}, clearance floor $clearanceFloor)",
            kneeY >= clearanceFloor
        )

        // …and the residual bend must sit ABOVE the hip→ankle chord: the chain's slack is what it is
        // (the 0.98 extension cap), so the only authored choice is which side of the chord it bows to,
        // and the mat is not one of them.
        val wrongSide = frames.filter { bendOffsetY(it.third) <= 0f }
            .map { "${it.first} p=${it.second} offsetY=${f(bendOffsetY(it.third))}" }
        assertTrue(
            "the support leg's residual knee bend must leave the mat, never sag toward it (BPS §7/§11 " +
                "extended supporting knee; the sibling StaticForearmPlankPose authors the same side):\n" +
                wrongSide.take(8).joinToString("\n"),
            wrongSide.isEmpty()
        )

        // The bowed knee must hang ABOVE its own supporting hip at every phase — the pre-fix pose put
        // it 28.9 units BELOW the hip it is stacked under.
        val belowHip = frames.filter { it.third.getJoint(Joint.KNEE_B).y <= it.third.getJoint(Joint.HIP_B).y }
            .map { "${it.first} p=${it.second} knee=${f(it.third.getJoint(Joint.KNEE_B).y)} " +
                "hip=${f(it.third.getJoint(Joint.HIP_B).y)}" }
        assertTrue(
            "the support knee must not sit below its hip:\n" + belowHip.take(8).joinToString("\n"),
            belowHip.isEmpty()
        )
    }

    @Test
    fun theSupportLegStanceIsRealizedWhereThePoseDeclaresIt() {
        val maxReach = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val minReach = SkeletonMath.minReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val frames = allFrames()

        val relocated = mutableListOf<String>()
        val folded = mutableListOf<String>()
        val unreachable = mutableListOf<String>()
        for ((condition, p, frame) in frames) {
            val hip = frame.getJoint(Joint.HIP_B)
            val ankle = frame.getJoint(Joint.ANKLE_B)
            val target = frame.limbTargets.firstOrNull { it.joint == Joint.ANKLE_B }
                ?: error("the published frame must carry the support leg's declared target (the §1.1 carrier)")
            // the solver must realize the authored target, not relocate it (per-limb clamp attribution)
            val reloc = sqrt(
                (ankle.x - target.world.x).let { it * it } +
                    (ankle.y - target.world.y).let { it * it } +
                    (ankle.z - target.world.z).let { it * it }
            )
            if (reloc > planeBand) relocated.add("$condition p=$p relocated ${f(reloc)}")
            // …and the realized chain must be the near-extended support leg the BPS declares: the
            // stance deliberately spends most of the leg's slack, and the 0.98 cap is the rest.
            val d = sqrt(
                (ankle.x - hip.x).let { it * it } + (ankle.y - hip.y).let { it * it } + (ankle.z - hip.z).let { it * it }
            )
            if (d < minReach || d > maxReach) unreachable.add("$condition p=$p d=${f(d)} outside [$minReach, $maxReach]")
            if (d < 0.95f * maxReach) folded.add("$condition p=$p d=${f(d)} of maxReach ${f(maxReach)} — folded, not extended")
        }
        assertTrue(
            "the support leg's realized end effector must be the authored target (the printed foot " +
                "stays where the pose plants it — no solver relocation):\n" +
                relocated.take(8).joinToString("\n"),
            relocated.isEmpty()
        )
        assertTrue(
            "the support leg's chord must stay inside the chain's own reach band:\n" +
                unreachable.take(8).joinToString("\n"),
            unreachable.isEmpty()
        )
        assertTrue(
            "the support leg must realize the near-extended stance the pose declares " +
                "(BPS §7 \"the supporting leg is straight and in line with the trunk\"):\n" +
                folded.take(8).joinToString("\n"),
            folded.isEmpty()
        )
    }

    @Test
    fun thePlantedFootAndTheSupportDeclarationAreUnchanged() {
        // The pose's support model is not this fix's subject.
        assertEquals(
            "the declared support model must be the pose's own (unchanged by this correction)",
            setOf(SupportPoint.RIGHT_FOREARM, SupportPoint.RIGHT_FOOT),
            MotionProbe.build(name).metadata.support.contacts.map { it.point }.toSet()
        )

        val frames = allFrames()
        val offPlane = mutableListOf<String>()
        val sliding = mutableListOf<String>()
        val plantedX = frames.first().third.getJoint(Joint.ANKLE_B).x
        for ((condition, p, frame) in frames) {
            val ankle = frame.getJoint(Joint.ANKLE_B)
            if (abs(ankle.y - floorContactY) > planeBand) {
                offPlane.add("$condition p=$p ANKLE_B y=${f(ankle.y)} (expected $floorContactY)")
            }
            // the foot chain the engine derives must also rest on the plane, never under it
            for (joint in listOf(Joint.HEEL_B, Joint.TOE_B)) {
                val y = frame.getJoint(joint).y
                if (y < -planeBand) offPlane.add("$condition p=$p $joint y=${f(y)}")
            }
            if (abs(ankle.x - plantedX) > planeBand) {
                sliding.add("$condition p=$p ANKLE_B x=${f(ankle.x)} (planted at ${f(plantedX)})")
            }
        }
        assertTrue(
            "the planted support foot must stay on the family's floor-contact height:\n" +
                offPlane.take(8).joinToString("\n"),
            offPlane.isEmpty()
        )
        assertTrue(
            "the planted support foot must not slide through the hold (a fixed world anchor):\n" +
                sliding.take(8).joinToString("\n"),
            sliding.isEmpty()
        )
    }

    @Test
    fun thePosesChoreographyIsUnchangedAndTheFramesAreDistinct() {
        val frames = playingFrames()

        // The hip lift is the pose's declared choreography (the hips are the prime mover): the
        // settled down-side hip rests on the mat and the braced one travels ~40.7 units.
        val hipYs = frames.map { it.second.getJoint(Joint.HIP_B).y }
        val hipLift = hipYs.max() - hipYs.min()
        assertTrue(
            "the authored hip lift must be preserved (measured ${f(hipLift)}, floor 40)",
            hipLift >= 40f
        )
        assertTrue(
            "the settled frame must still start with the down-side hip ON the mat " +
                "(measured ${f(hipYs.min())})",
            hipYs.min() < 5f
        )

        // The top foot settles onto the planted one as the hips lift (its authored 0 -> 10 rise).
        val topFootYs = frames.map { it.second.getJoint(Joint.ANKLE_F).y }
        assertTrue(
            "the top foot's authored settle onto the planted foot must be preserved " +
                "(measured spread ${f(topFootYs.max() - topFootYs.min())}, floor 10)",
            topFootYs.max() - topFootYs.min() >= 10f
        )

        // The family motion contract (CoreMotionTest's floor for this pose) must stay satisfied.
        val travel = MotionProbe.maxTravel3D(MotionProbe.build(name))
        assertTrue("the pose's authored choreography must keep moving (travel=${f(travel)}, floor 40)", travel >= 40f)

        // Anti-vacuity: the sweep must publish DISTINCT frames, so the assertions above cannot be
        // satisfied by one aliased buffer.
        assertEquals(
            "every sampled frame must be an independent capture (the buffer-aliasing trap)",
            sweep.size, frames.map { System.identityHashCode(it.second) }.distinct().size
        )
    }
}
