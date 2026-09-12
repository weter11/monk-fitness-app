package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **B3 — the side plank's support knee must not pass through the mat the pose itself declares.**
 *
 * `IsometricSidePlankPose` authors a lateral hold: the body rolled onto its down side, the support
 * forearm planted and the support foot planted, everything read against its own plane
 * (`metadata.environment.ground.level = 0`, the family's `plankEnvironment`).
 *
 * **Measured on `origin/main` @ `2fb6079`, published path `SkeletonPipeline.produceFrame(pose, ctx)`:
 * `KNEE_B = (-100.9380, -25.8897, 0.0000)` at `p = 0.0`** — the deepest reading of the rep, and the
 * only joint of the pose that penetrates: the whole-body sweep reports `KNEE_B` below the plane at
 * **all 51 sampled phases under both frame conditions** (102 readings, worst `-25.8897`; the next
 * lowest published joint of the pose is `HIP_B` at `+3.0233`, i.e. the settled down-side hip resting
 * on the mat). The pair is pinned as an attributed open item by
 * `PublishedBelowGroundInvariantTest.knownBelowGround` (T2), whose exit criterion is exactly this
 * correction: that table's stale-pin guard fires on the fixing change, so the entry cannot outlive it.
 *
 * **Why the geometry is forced, not tuned.** The support leg's authored stance is
 * `ankleX = -(thighLength + shinLength) + 20 = -190`, `ankleY = 15` (the family's planted-foot
 * height). At the settled frame the down-side hip sits at `(7.2657, 3.0233)` — the hip that the
 * pose's own choreography has just lowered onto the mat — so the hip→ankle chord is `197.6289` for a
 * `112 / 98` chain (`maxReach = 205.8000`, `minReach = 56.0090`). That 8.17 units of slack is
 * unavoidable (`maxReach` is the constraint's own 0.98 extension cap, i.e. the chain can never be
 * drawn perfectly straight on this path) and it fixes the knee's IK locus: a circle of radius
 * `h = 35.4166` centred `a = 106.2501` along the chord. **The authored pole `(0, -1, 0)` aims that
 * bow straight INTO the mat** — the knee is placed on the `-Y` side of a chord that itself lies only
 * `3.0 … 15.0` units above the plane, so it lands `25.8897` units below it. Handing the same pole to
 * the mirror branch (up) would keep the chain's residual bend, but out of the mat; the family's own
 * convention is already that: the sibling `StaticForearmPlankPose` authors `poleF/poleB = (0, 1, 0)`
 * with the comment *"residual knee bend points up, never sagging through the floor"*, and its leg
 * carries a LARGER residual (`h = 46.6668`, `187.9900` of `205.8000` at its settled frame).
 *
 * **The correction is therefore pose-side support authoring, not engine work:** the support leg keeps
 * its stance, its planted foot and its reach, and the residual bend is authored to point out of the
 * mat (BPS `Plank (Side)` §7 *"the supporting leg is straight and in line with the trunk"* / §11
 * *"knees straight (supporting leg)"*, realized as the chain's own nearest-to-straight solution).
 * Nothing else about the pose moves: the hip lift, the breathing float, the top foot's settle, the
 * top hand on the hip and the declared support model are all unchanged, and the only published joint
 * whose geometry moves is the support knee itself.
 *
 * **What this file asserts (all on the PUBLISHED frame, captured by value — `produceFrame(...).pose`
 * is the Finalizer's reused output buffer, the T-7 trap):** the whole-body plane invariant over a
 * dense 51-sample sweep in both frame conditions ([noPublishedJointPassesBelowThePosesOwnDeclaredPlane]),
 * the support knee's clearance and bend side ([theSupportKneeLeavesTheMatOnTheUpSide]), the stance the
 * chain realizes and where the engine puts it ([theSupportLegStanceIsRealizedWhereThePoseDeclaresIt]),
 * the planted foot and the support declaration
 * ([thePlantedFootAndTheSupportDeclarationAreUnchanged]), and the pose's own choreography
 * ([thePosesChoreographyIsUnchangedAndTheFramesAreDistinct]).
 *
 * **Instruments blind to this pair** (named, not implied): `ExerciseValidator`'s ground rule keys on
 * the 6 foot joints (`KNEE_B` is not one); the declaration-keyed `EnvironmentPenetrationTest` (B-6)
 * can only see a pose's declared support family, and this pose declares
 * `RIGHT_FOREARM`/`RIGHT_FOOT` (arms and feet — `KNEE_B` is outside it); the motion tests assert
 * travel only. T2's own 5-sample sweep sees the pair because the violation here is phase-independent
 * — this file keeps the 5-sample corpus honest with a dense sweep anyway.
 */
class IsometricSidePlankKneePlaneTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val name = "IsometricSidePlankPose"

    /** A DENSE sweep: the violation holds at every phase, so 5 samples cannot step over it. */
    private val sweep = (0..50).map { it * 0.02f }

    /** The T2 band: a joint DERIVED onto the plane reads `0.000000`, so this absorbs float noise only. */
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

    // ------------------------------------------------------------------------------------------
    // 1. The invariant this fix exists for — the whole body against the pose's OWN declared plane
    // ------------------------------------------------------------------------------------------

    @Test
    fun noPublishedJointPassesBelowThePosesOwnDeclaredPlane() {
        // The plane is READ from the pose, never assumed to be zero (the T2 channel).
        val ground = MotionProbe.build(name).metadata.environment.ground.level
        val frames = allFrames()
        val below = mutableListOf<String>()
        for ((condition, p, frame) in frames) {
            for (joint in Joint.entries) {
                val y = frame.getJoint(joint).y
                if (y < ground - planeBand) below.add("$condition p=$p $joint y=${f(y)} (${f(y - ground)} vs its plane)")
            }
        }
        // non-vacuity: the sweep is dense, covers both conditions and every published joint
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

    // ------------------------------------------------------------------------------------------
    // 2. The support knee's clearance and bend side (the physical shape the pose declares)
    // ------------------------------------------------------------------------------------------

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
        // The offset of the knee from the chord, expressed as a world-Y contribution.
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

    // ------------------------------------------------------------------------------------------
    // 3. The stance the chain realizes, and where the engine puts it
    // ------------------------------------------------------------------------------------------

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

    // ------------------------------------------------------------------------------------------
    // 4. The planted foot and the declaration — guards that must NOT move
    // ------------------------------------------------------------------------------------------

    @Test
    fun thePlantedFootAndTheSupportDeclarationAreUnchanged() {
        // The pose's support model is the canonical channel (B-2/B-4) and is not this fix's subject.
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

    // ------------------------------------------------------------------------------------------
    // 5. Choreography — the pose must keep moving, exactly as it did
    // ------------------------------------------------------------------------------------------

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

        // Anti-vacuity (T-7): the sweep must publish DISTINCT frames, so the assertions above cannot
        // be satisfied by one aliased buffer.
        assertEquals(
            "every sampled frame must be an independent capture (the buffer-aliasing trap)",
            sweep.size, frames.map { System.identityHashCode(it.second) }.distinct().size
        )
    }
}
