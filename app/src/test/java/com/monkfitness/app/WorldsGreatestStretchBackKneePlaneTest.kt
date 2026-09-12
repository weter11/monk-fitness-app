package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **B2 — the runner's-lunge back knee must not pass through the mat the pose itself declares.**
 *
 * `DynamicWorldsGreatestStretchPose` authors a runner's lunge: the front foot flat forward, *"the back
 * leg extended with the toe on the floor"* (its own KDoc), the pelvis low, everything read against its
 * own plane (`metadata.environment.ground.level = 0`, `thoracicGround`).
 *
 * **Measured on `origin/main` @ `2fb6079`, published path `SkeletonPipeline.produceFrame(pose, ctx)`:
 * `KNEE_B = (-55.2148, -46.1056, +38.6517)` at EVERY sampled phase of the rep** — i.e. the only
 * IK-realised joint of the back leg sits `46.1056` units BELOW the pose's own mat, and `16.65` units
 * out of the leg's own sagittal plane (`z = +22`). The pair is pinned as an attributed open item by
 * `PublishedBelowGroundInvariantTest.knownBelowGround` (T2), whose exit criterion is exactly this
 * correction: that table's stale-pin guard fires on the fixing change, so the entry cannot outlive it.
 *
 * **Why the geometry is forced, not tuned.** The authored back ankle (`pelvisX - 120`, `y = 15`) sits
 * `126.4945` from the hip for a `112 / 98` chain (`minReach(112, 98, 30°) = 56.0090`,
 * `maxReach = 205.8000`), so the chain must fold. The locus of the knee is a circle of radius
 * `h = 83.2989` centred `74.87` along the hip→ankle chord: **both of its in-plane branches are
 * unusable** — the one the authored pole `(0.1, -1, 0.2)` selects realizes the knee `46.1056` under
 * the mat, its mirror puts the knee `55.4` ABOVE the hip (a chicken-wing back leg), and every
 * branch that keeps the knee near the mat requires splaying it `~78` units laterally out of the leg
 * plane. The authored stance stub, not the pole in isolation, is what makes a legal back leg
 * impossible, which is why the correction is the stance the KDoc declares (extended, toe on the
 * floor) with the pole derived from the leg's own chord.
 *
 * **What this file asserts (all on the PUBLISHED frame, captured by value — `produceFrame(...).pose`
 * is the Finalizer's reused output buffer, the T-7 trap):** the whole-body plane invariant over a
 * dense 51-sample sweep in both frame conditions ([noPublishedJointPassesBelowThePosesOwnDeclaredPlane]),
 * the back knee's clearance and bend side ([theBackKneeHoversJustAboveTheMat], its own sagittal
 * plane ([theBackKneeStaysInTheLegsOwnSagittalPlane]), the back leg's realized extension
 * ([theBackLegRealizesTheExtensionItsStanceDeclares]), the pose's own choreography and declaration
 * (the guards that must NOT move: [thePosesChoreographyAndDeclarationAreUnchanged]).
 *
 * **Instruments blind to this pair** (named, not implied): `ExerciseValidator`'s ground rule keys on
 * the 6 foot joints; the declaration-keyed `EnvironmentPenetrationTest` (B-6) can only see a pose's
 * declared support family, and `KNEE_B` is outside it (`LEFT_FOOT`/`RIGHT_FOOT`/`RIGHT_HAND`); the
 * motion tests assert travel only. T2's own 5-sample sweep sees the pair because the violation here
 * is phase-independent — this file keeps the 5-sample corpus honest with a dense sweep anyway.
 */
class WorldsGreatestStretchBackKneePlaneTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val name = "DynamicWorldsGreatestStretchPose"

    /** A DENSE sweep: the violation holds at every phase, so 51 samples cannot step over it. */
    private val sweep = (0..50).map { it * 0.02f }

    /** The T2 band: a joint DERIVED onto the plane reads `0.000000`, so this absorbs float noise only. */
    private val planeBand = 0.05f

    /** A real floor for the back knee — two orders of magnitude above [planeBand]. */
    private val clearanceFloor = 1.0f

    /** A realignment tolerance: the pole's own lateral component splayed the knee 16.65 u pre-fix. */
    private val planeTolerance = 0.5f

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
                "the back knee is the B2 pair — its stance must be the extension the pose declares, not a\n" +
                "stub the 112/98 chain has to fold through:\n" + below.take(12).joinToString("\n"),
            below.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 2. The back knee's clearance and bend side (the physical shape the pose declares)
    // ------------------------------------------------------------------------------------------

    @Test
    fun theBackKneeHoversJustAboveTheMat() {
        val frames = allFrames()
        val worst = frames.minBy { it.third.getJoint(Joint.KNEE_B).y }
        val kneeY = worst.third.getJoint(Joint.KNEE_B).y
        val hipY = worst.third.getJoint(Joint.HIP_B).y
        assertTrue(
            "the lunge's back knee must hover above the mat it declares, not pass through it " +
                "(worst $kneeY at ${worst.first} p=${worst.second}, clearance floor $clearanceFloor)",
            kneeY >= clearanceFloor
        )
        // …and it must HANG below the hip: the only other in-plane branch of the pre-fix IK circle
        // puts the knee 55.4 units ABOVE the hip, which is not a lunge's back leg either.
        assertTrue(
            "the back knee must hang below its hip (measured knee=$kneeY hip=$hipY)",
            kneeY < hipY
        )
    }

    @Test
    fun theBackKneeStaysInTheLegsOwnSagittalPlane() {
        val frames = allFrames()
        val worst = frames.maxBy { abs(it.third.getJoint(Joint.KNEE_B).z - it.third.getJoint(Joint.HIP_B).z) }
        val off = abs(worst.third.getJoint(Joint.KNEE_B).z - worst.third.getJoint(Joint.HIP_B).z)
        assertTrue(
            "the realized knee must stay in the leg's own plane (hip z=${f(worst.third.getJoint(Joint.HIP_B).z)}, " +
                "ankle z=${f(worst.third.getJoint(Joint.ANKLE_B).z)}): a pole with a lateral component throws it out " +
                "by ~h (measured worst ${f(off)} at ${worst.first} p=${worst.second}, tolerance $planeTolerance)",
            off <= planeTolerance
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3. The authored stance is the extension the pose declares (reachable by construction)
    // ------------------------------------------------------------------------------------------

    @Test
    fun theBackLegRealizesTheExtensionItsStanceDeclares() {
        val maxReach = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val minReach = SkeletonMath.minReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val frames = allFrames()

        val relocated = mutableListOf<String>()
        val folded = mutableListOf<String>()
        for ((condition, p, frame) in frames) {
            val hip = frame.getJoint(Joint.HIP_B)
            val ankle = frame.getJoint(Joint.ANKLE_B)
            val target = frame.limbTargets.firstOrNull { it.joint == Joint.ANKLE_B }
                ?: error("the published frame must carry the back leg's declared target (the §1.1 carrier)")
            // the solver must realize the authored target, not relocate it (per-limb clamp attribution)
            val reloc = sqrt(
                (ankle.x - target.world.x).let { it * it } +
                    (ankle.y - target.world.y).let { it * it } +
                    (ankle.z - target.world.z).let { it * it }
            )
            if (reloc > planeBand) relocated.add("$condition p=$p relocated ${f(reloc)}")
            // …and the realized leg must be the near-straight chain the KDoc's "extended" means
            val d = sqrt(
                (ankle.x - hip.x).let { it * it } + (ankle.y - hip.y).let { it * it } + (ankle.z - hip.z).let { it * it }
            )
            if (d < minReach || d > maxReach) folded.add("$condition p=$p d=${f(d)} outside the reachable band")
            if (d < 0.95f * maxReach) folded.add("$condition p=$p d=${f(d)} of maxReach ${f(maxReach)} — the leg is folded, not extended")
        }
        assertTrue(
            "the back leg's realized end effector must be the authored target (the chain's own " +
                "reach band, not a solver relocation):\n" + relocated.take(8).joinToString("\n"),
            relocated.isEmpty()
        )
        assertTrue(
            "the back leg must realize the extension the pose declares (\"back leg extended with " +
                "the toe on the floor\") rather than folding through the mat:\n" + folded.take(8).joinToString("\n"),
            folded.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 4. Guards — the pose's choreography and declaration must NOT move
    // ------------------------------------------------------------------------------------------

    @Test
    fun thePosesChoreographyAndDeclarationAreUnchanged() {
        // The pose's support declaration is the canonical channel (B-2/B-3) and is not this fix's subject.
        assertEquals(
            "the declared support model must be the pose's own (unchanged by this correction)",
            setOf(SupportPoint.LEFT_FOOT, SupportPoint.RIGHT_FOOT, SupportPoint.RIGHT_HAND),
            MotionProbe.build(name).metadata.support.contacts.map { it.point }.toSet()
        )

        // The choreography is the thoracic twist's reach sweep — the family motion contract
        // (MobilityMotionTest's 70 u floor) must stay satisfied, by measurement, not by lowering it.
        val travel = MotionProbe.maxTravel3D(MotionProbe.build(name))
        assertTrue("the pose's authored choreography must keep moving (travel=${f(travel)}, floor 70)", travel >= 70f)

        // Anti-vacuity (T-7): the sweep must publish DISTINCT frames with a real spread of the
        // reaching hand, so the assertions above cannot be satisfied by one aliased frame.
        val frames = playingFrames()
        assertEquals(
            "every sampled frame must be an independent capture (the buffer-aliasing trap)",
            sweep.size, frames.map { System.identityHashCode(it.second) }.distinct().size
        )
        val handYs = frames.map { it.second.getJoint(Joint.HAND_A).y }
        val spread = handYs.max() - handYs.min()
        assertTrue(
            "the reaching hand must actually sweep (measured spread ${f(spread)}, floor 100)",
            spread >= 100f
        )
    }
}
