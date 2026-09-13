package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **B4 — the supine poses' realized elbows must not pass through the mat they declare.**
 *
 * `GluteBridgePose` and `PelvicTiltPose` are the two supine (lying-on-the-back) production poses.
 * Both author the arms the same way — hands at a fixed flank position
 * (`(-35, 12, ±(shoulderWidth + 5))`, *"static (prevents hand sliding)"*) realized through
 * `bakeIkLimb` with the pole `(0, -1, ∓1)` — and both publish their `ELBOW_A`/`ELBOW_P` below their
 * own declared plane (`metadata.environment.ground.level = 0`) at EVERY phase of the rep.
 *
 * **Measured on the tree that carries B1/B2/B3 (`origin/main` @ `ba3928b`), on the published path
 * `SkeletonPipeline.produceFrame(pose, ctx)`:** `GluteBridgePose` `ELBOW_A/P` worst `−30.6914`
 * (at `p = 1.0`), `PelvicTiltPose` `ELBOW_A/P` worst `−33.3501`; the readings are identical under
 * both frame conditions (the elbow is not frame-condition sensitive here). Both pairs are pinned as
 * attributed open items by `PublishedBelowGroundInvariantTest.knownBelowGround` (T2); that table's
 * stale-pin guard is this correction's exit criterion, so the entries cannot outlive it.
 *
 * **The root cause is the authored pole, provably (the B1 shape).** The elbow is an IK *output*: the
 * pose owns three things — the chain root (the shoulder), the end target (the hand) and the pole
 * (the bend side). Re-deriving the published elbow from that triangle
 * (`a = (d² + L1² − L2²)/2d`, `h = √(L1² − a²)`, `phat = normalize(pole − u·(pole·u))`, elbow
 * `= root + u·a + phat·h`) reproduces the published joint exactly at every sampled phase of both
 * poses (`err ≤ 0.032`, and `0.000000` on most samples) — so nothing else (solver, finalizer,
 * frames) contributes and the authored pole is the whole cause.
 *
 * `(0, -1, ∓1)` is the standing family's convention (BaseSquat/BaseLunge/ArmCircles/LatStretch/
 * HipCars/KettlebellSwing) where a downward bow is harmless. On these supine poses the chord
 * shoulder→hand lies in the floor plane (shoulder `y ≈ 12.9 … 14.0`, hand `y = 12.0`), so the
 * pole's `−1` Y component lands the perpendicular residual on the chord's DOWNWARD basis vector:
 * measured `phat_y = −0.7069 … −0.7075` against `h = 58.49 … 60.74`, i.e. the elbow bows
 * `≈ 41 u` below the chord — through the mat, by `28.6 … 33.4 u`, at every phase of both poses.
 *
 * **The correction is pose-side bend-side authoring only** (no solver/engine/phase/ownership
 * change, no tolerance moved): the pole becomes the pose's own lateral axis, `(0, 0, ∓1)` — both
 * poses rotate about the world Z axis only (`declarePelvisTilt(..., Vector3(0, 0, 1), torsoAngle)`),
 * so world `±Z` IS the body's lateral axis at every phase, and the arm's plane becomes the floor
 * plane ("*arms lying flat alongside the body*", the pose's own comment). The elbow keeps the
 * lateral side the current pole's Z sign already selects (outboard, never across the torso) and
 * moves from `28.6 … 33.4 u` BESIDE the mat to `+7.1 … +12.9 u` above it, its residual bow
 * horizontal.
 *
 * **What this file asserts (all on the PUBLISHED frame, captured by value — `produceFrame(...).pose`
 * is the Finalizer's reused output buffer, the T-7 trap), over a DENSE 51-sample sweep in both
 * frame conditions:**
 *  1. no realized joint of either pose's arm chain (elbow and everything distal — the joints the arm
 *     authoring owns) passes below that pose's own declared plane, and for `GluteBridgePose` the
 *     whole body does not ([noArmChainJointPassesBelowThePosesOwnDeclaredPlane]);
 *  2. the elbow's clearance and its bend side — it lies IN the floor plane (its Y offset from its
 *     own shoulder→hand chord is a fraction of `h`), stays outboard, and is a real solution of its
 *     own chain ([theElbowLiesInTheFloorPlaneAndClearsTheMat]);
 *  3. the authored hand placement, the planted feet and the declared support model are untouched
 *     ([theAuthoredHandsThePlantedFeetAndTheSupportDeclarationAreUnchanged]);
 *  4. the two poses' arms are exact mirrors of each other, and both poses still declare the SAME
 *     arm authoring ([theTwoArmsAreExactMirrorsOfTheAuthoredChain]);
 *  5. the authored rep still moves (the CoreMotionTest / MobilityMotionTest floors) and the sweep
 *     publishes distinct frames ([thePosesChoreographyAndMotionContractAreUnchanged]);
 *  6. the arm geometry is identical on a genuinely cold first frame and on a settled frame
 *     ([theArmGeometryIsFrameConditionInvariant]) — this change must not introduce a cold-frame
 *     defect (the B-8 class).
 *
 * **Out of scope of THIS file: `PelvicTiltPose`'s trunk chain.** This file's subject is the two poses'
 * arm authoring. That pose's rigid trunk was authored through its own mat by the same class of defect
 * — a pose-side authoring constant, not an engine item — but with its own root cause: the trunk's
 * tilt direction, on a pelvis pinned at `y = 14` (`CHEST`/`SHOULDER_*` `−0.3659`, `NECK_END` `−2.5295`,
 * `HEAD_POS` `−2.5384` at `p = 1.0`). It was recorded and pinned in T2 as a separate open item and is
 * corrected separately by the B4 trunk change (`fix/b4-pelvic-tilt-trunk-ground-plane`: the tilt arc
 * is authored out of the mat, the arms' bend side untouched), whose gate is
 * `PelvicTiltTrunkPlaneTest`. This file therefore keeps claiming the plane for `GluteBridgePose`'s
 * whole body and for both poses' arm chains — and its assertions are re-run **unchanged** on the
 * trunk-corrected tree, which is what shows that correction did not undo this one.
 */
class SupineArmElbowPlaneTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** The two supine poses, treated together because their arm authoring is identical (measured). */
    private val poses = listOf("GluteBridgePose", "PelvicTiltPose")

    /** A DENSE sweep: the violation holds at every phase, so 5 samples cannot step over it. */
    private val sweep = (0..50).map { it * 0.02f }

    /** The T2 band: a joint the derivation places ON the plane reads `0.000000`, so this absorbs noise only. */
    private val planeBand = 0.05f

    /**
     * Cosine tolerance for "the declared target still lies on the pose's authored ray" — the property
     * the R2/R4 reach projection preserves (fourth reach-band batch). The projection is exact (the
     * same float expression from the same root), so `1e-5` is float slack.
     */
    private val rayTolerance = 1f - 1e-5f

    /** cos of the angle between `declared − root` and `authored − root`. */
    private fun rayCos(root: Vector3, declared: Vector3, authored: Vector3): Float {
        val a = Vector3(declared.x - root.x, declared.y - root.y, declared.z - root.z)
        val b = Vector3(authored.x - root.x, authored.y - root.y, authored.z - root.z)
        val am = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        val bm = sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
        if (am < 1e-6f || bm < 1e-6f) return 0f
        return ((a.x * b.x + a.y * b.y + a.z * b.z) / (am * bm)).coerceIn(-1f, 1f)
    }

    /** A real floor for the realized elbow — an order of magnitude above [planeBand]. */
    private val clearanceFloor = 1.0f

    /** How far the elbow's residual bow may leave the chord's plane: the arm lies IN the floor plane. */
    private val flatBand = 2.0f

    /** The pose's authored static hand position (its own comment: *"prevents hand sliding"*). */
    private val handX = -35f
    private val handY = 12f
    private val handZ = 51f // def.shoulderWidth (46) + 5

    /** The joints the arm authoring owns: the elbow and everything distal of it. */
    private val armChain = listOf(
        Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A,
        Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P
    )

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** A frame captured BY VALUE (the pipeline publishes a reused buffer). */
    private fun snapshot(frame: SkeletonPose): SkeletonPose =
        SkeletonPose().apply { copyFrom(frame) }

    /** A genuinely COLD frame: a fresh pose instance on a fresh pipeline (the T2 COLD leg). */
    private fun coldFrame(name: String, p: Float): SkeletonPose =
        snapshot(SkeletonPipeline(def).produceFrame(MotionProbe.build(name), ctx(p)).pose)

    /** One builder + one pipeline advancing through the sweep, every frame captured by value. */
    private fun playingFrames(name: String): List<Pair<Float, SkeletonPose>> {
        val pipeline = SkeletonPipeline(def)
        val builder = MotionProbe.build(name)
        return sweep.map { p -> p to snapshot(pipeline.produceFrame(builder, ctx(p)).pose) }
    }

    /** One (condition, progress, frame) triple per evaluated frame of both frame conditions. */
    private fun allFrames(name: String): List<Triple<String, Float, SkeletonPose>> {
        val frames = mutableListOf<Triple<String, Float, SkeletonPose>>()
        for (p in sweep) frames.add(Triple("COLD", p, coldFrame(name, p)))
        for ((p, f) in playingFrames(name)) frames.add(Triple("PLAYING", p, f))
        return frames
    }

    private fun ground(name: String): Float = MotionProbe.build(name).metadata.environment.ground.level

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private fun dist(a: Vector3, b: Vector3) =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    private fun sideOf(joint: Joint) = if (joint.name.endsWith("_A")) "_A" else "_P"
    private fun elbowOf(side: String) = if (side == "_A") Joint.ELBOW_A else Joint.ELBOW_P
    private fun handOf(side: String) = if (side == "_A") Joint.HAND_A else Joint.HAND_P
    private fun shoulderOf(side: String) = if (side == "_A") Joint.SHOULDER_A else Joint.SHOULDER_P

    /**
     * The elbow's signed offset from its own shoulder→hand chord, expressed on the world Y axis:
     * the chord point nearest the elbow is the projection, and this is the elbow's residual bow.
     * `≈ 0` = the arm's plane is the floor plane (the corrected authoring); the pre-fix authoring
     * measured `≈ −41`.
     */
    private fun bowOffsetY(frame: SkeletonPose, side: String): Float {
        val shoulder = frame.getJoint(shoulderOf(side))
        val elbow = frame.getJoint(elbowOf(side))
        val hand = frame.getJoint(handOf(side))
        val cx = hand.x - shoulder.x; val cy = hand.y - shoulder.y; val cz = hand.z - shoulder.z
        val vx = elbow.x - shoulder.x; val vy = elbow.y - shoulder.y; val vz = elbow.z - shoulder.z
        val len2 = cx * cx + cy * cy + cz * cz
        val t = (vx * cx + vy * cy + vz * cz) / len2
        return elbow.y - (shoulder.y + t * cy)
    }

    // ------------------------------------------------------------------------------------------
    // 1. The invariant this fix exists for — the arm chain against the pose's OWN declared plane
    // ------------------------------------------------------------------------------------------

    @Test
    fun noArmChainJointPassesBelowThePosesOwnDeclaredPlane() {
        val below = mutableListOf<String>()
        for (name in poses) {
            // The plane is READ from the pose, never assumed to be zero (the T2 channel).
            val level = ground(name)
            val frames = allFrames(name)
            assertEquals(
                "the sweep must evaluate every joint of every frame of both conditions",
                sweep.size * Joint.entries.size * 2, frames.size * Joint.entries.size
            )
            for ((condition, p, frame) in frames) {
                for (joint in armChain) {
                    val y = frame.getJoint(joint).y
                    if (y < level - planeBand) {
                        below.add("$name $condition p=$p $joint y=${f(y)} (${f(y - level)} vs its plane)")
                    }
                }
            }
        }
        assertTrue(
            "realized arm-chain joints below their pose's own declared plane (band = $planeBand).\n" +
                "The elbow is an IK output of the authored pole: a pole whose residual bow points into " +
                "the mat sinks the whole distal chain:\n" + below.take(12).joinToString("\n"),
            below.isEmpty()
        )

        // Full-strength whole-body claim for the pose whose ONLY below-plane joint was its elbow.
        val gluteBelow = mutableListOf<String>()
        for ((condition, p, frame) in allFrames("GluteBridgePose")) {
            for (joint in Joint.entries) {
                val y = frame.getJoint(joint).y
                if (y < ground("GluteBridgePose") - planeBand) {
                    gluteBelow.add("$condition p=$p $joint y=${f(y)}")
                }
            }
        }
        assertTrue(
            "GluteBridgePose: with the elbow corrected, NO joint of the pose may pass below its own " +
                "declared plane (its whole-body table entry was the elbow pair):\n" +
                gluteBelow.take(12).joinToString("\n"),
            gluteBelow.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 2. The elbow's clearance and bend side — the physical shape the pose declares
    // ------------------------------------------------------------------------------------------

    @Test
    fun theElbowLiesInTheFloorPlaneAndClearsTheMat() {
        val deepest = mutableListOf<String>()
        val bowed = mutableListOf<String>()
        val inboard = mutableListOf<String>()
        val broken = mutableListOf<String>()

        for (name in poses) {
            val level = ground(name)
            for ((condition, p, frame) in allFrames(name)) {
                for (side in listOf("_A", "_P")) {
                    val elbow = frame.getJoint(elbowOf(side))
                    val shoulder = frame.getJoint(shoulderOf(side))
                    val hand = frame.getJoint(handOf(side))

                    if (elbow.y < level + clearanceFloor) {
                        deepest.add("$name $condition p=$p $side y=${f(elbow.y)} (floor ${f(level + clearanceFloor)})")
                    }
                    val bow = bowOffsetY(frame, side)
                    if (abs(bow) > flatBand) {
                        bowed.add("$name $condition p=$p $side bowOffsetY=${f(bow)} (band ±$flatBand)")
                    }
                    // The bend side: the elbow bows OUTBOARD of its own shoulder line, never across the
                    // torso (the side the authored pole's Z sign selects today).
                    if (abs(elbow.z) < abs(shoulder.z)) {
                        inboard.add("$name $condition p=$p $side elbow.z=${f(elbow.z)} shoulder.z=${f(shoulder.z)}")
                    }
                    // Anti-vacuity: the elbow must be a real solution of ITS OWN chain, not a moved joint.
                    val l1 = dist(shoulder, elbow); val l2 = dist(elbow, hand)
                    if (abs(l1 - def.upperArmLength) > 0.05f || abs(l2 - def.forearmLength) > 0.05f) {
                        broken.add("$name $condition p=$p $side |shoulder-elbow|=${f(l1)} |elbow-hand|=${f(l2)}")
                    }
                }
            }
        }

        assertTrue(
            "the realized elbow must clear the mat the pose declares (B4's subject):\n" +
                deepest.take(8).joinToString("\n"),
            deepest.isEmpty()
        )
        assertTrue(
            "the arm lies flat: the elbow's residual bow must stay IN the floor plane, not bow out of " +
                "it — the pre-fix pole bowed ≈ −41 u (down, through the mat):\n" +
                bowed.take(8).joinToString("\n"),
            bowed.isEmpty()
        )
        assertTrue(
            "the elbow must bow outboard of its own shoulder line (the side the authored pole selects):\n" +
                inboard.take(8).joinToString("\n"),
            inboard.isEmpty()
        )
        assertTrue(
            "the elbow must remain a real IK solution of its own chain (upper arm / forearm at their " +
                "declared lengths):\n" + broken.take(8).joinToString("\n"),
            broken.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3. The authored hand placement, the planted feet and the declaration — guards that must NOT move
    // ------------------------------------------------------------------------------------------

    @Test
    fun theAuthoredHandsThePlantedFeetAndTheSupportDeclarationAreUnchanged() {
        for (name in poses) {
            // The pose's own declaration channel (B-2/B-4): the planted feet, unchanged by this fix.
            assertEquals(
                "$name: the declared support model must be the pose's own (unchanged by this correction)",
                setOf(SupportPoint.LEFT_FOOT, SupportPoint.RIGHT_FOOT),
                MotionProbe.build(name).metadata.support.contacts.map { it.point }.toSet()
            )

            val relocated = mutableListOf<String>()
            val moved = mutableListOf<String>()
            val legDeclaration = mutableListOf<String>()
            val feet = mutableListOf<String>()
            for ((condition, p, frame) in allFrames(name)) {
                for (side in listOf("_A", "_P")) {
                    val hand = frame.getJoint(handOf(side))
                    val sign = if (side == "_A") -1f else 1f
                    // The authored static hand (the pose's own "prevents hand sliding" declaration).
                    if (abs(hand.x - handX) > planeBand || abs(hand.y - handY) > planeBand ||
                        abs(hand.z - sign * handZ) > planeBand
                    ) {
                        moved.add("$name $condition p=$p $side hand=(${f(hand.x)}, ${f(hand.y)}, ${f(hand.z)})")
                    }
                    // …and the solver must realize that authored target, not relocate it (§1.1 carrier).
                    val target = frame.limbTargets.firstOrNull { it.joint == handOf(side) }
                        ?: error("the published frame must carry the arm's declared target (the §1.1 carrier)")
                    if (dist(hand, target.world) > planeBand) {
                        relocated.add("$name $condition p=$p $side relocated ${f(dist(hand, target.world))}")
                    }
                }
                // The legs are not this fix's subject: their DECLARED stance and their clearance from
                // the mat must be what the pose authored. The fourth (final) reach-band batch moved
                // that declaration ALONG the pose's own ray (`45, ankleHeight, ±hipWidth` seen from the
                // hip) onto the leg chain's annulus — the authored stance folded the knee to an interior
                // 23.52° against the constraint's 30° stop, so the projection re-declares the stance the
                // solver had always published (`56.0008, 15.2445, ±22`) and leaves the realized legs
                // byte-identical. The pose's MEANING is the ray, so the ray is what is pinned here.
                for ((joint, hip, sign) in listOf(
                    Triple(Joint.ANKLE_F, Joint.HIP_F, -1f), Triple(Joint.ANKLE_B, Joint.HIP_B, 1f)
                )) {
                    val legTarget = frame.limbTargets.firstOrNull { it.joint == joint }
                        ?: error("$name: the published frame must carry the leg's declared target")
                    val authoredLeg = Vector3(45f, def.foot.ankleHeight, sign * def.hipWidth)
                    if (rayCos(frame.getJoint(hip), legTarget.world, authoredLeg) < rayTolerance) {
                        legDeclaration.add("$name $condition p=$p $joint target=${legTarget.world.x}")
                    }
                    val y = frame.getJoint(joint).y
                    if (y < planeBand) feet.add("$name $condition p=$p $joint y=${f(y)}")
                }
            }
            assertTrue(
                "$name: the AUTHORED hand placement is the exercise's, not the elbow's fix — the hands " +
                    "must stay at their static flank position:\n" + moved.take(8).joinToString("\n"),
                moved.isEmpty()
            )
            assertTrue(
                "$name: the realized hand must be the authored target (no solver relocation):\n" +
                    relocated.take(8).joinToString("\n"),
                relocated.isEmpty()
            )
            assertTrue(
                "$name: the planted feet's DECLARED stance must be the pose's own — the elbow's fix " +
                    "must not move the leg authoring:\n" + legDeclaration.take(8).joinToString("\n"),
                legDeclaration.isEmpty()
            )
            assertTrue(
                "$name: the planted feet must stay above the declared plane:\n" +
                    feet.take(8).joinToString("\n"),
                feet.isEmpty()
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 4. The two arms as mirrors, and the two poses' shared arm authoring
    // ------------------------------------------------------------------------------------------

    @Test
    fun theTwoArmsAreExactMirrorsOfTheAuthoredChain() {
        val asymmetric = mutableListOf<String>()
        val wrongAuthoring = mutableListOf<String>()

        for (name in poses) {
            for ((condition, p, frame) in allFrames(name)) {
                for (joint in armChain) {
                    if (!joint.name.endsWith("_A")) continue
                    val a = frame.getJoint(joint)
                    val mirror = Joint.valueOf(joint.name.removeSuffix("_A") + "_P")
                    val b = frame.getJoint(mirror)
                    if (abs(a.x - b.x) > 1e-3f || abs(a.y - b.y) > 1e-3f || abs(a.z + b.z) > 1e-3f) {
                        asymmetric.add(
                            "$name $condition p=$p $joint=(${f(a.x)}, ${f(a.y)}, ${f(a.z)}) " +
                                "$mirror=(${f(b.x)}, ${f(b.y)}, ${f(b.z)})"
                        )
                    }
                }
                // Both supine poses must still declare the SAME arm intent: the same end target
                // (static hands) and the same bend side (the pose's own lateral axis). This is what
                // makes one shared correction legitimate for the pair.
                for (joint in listOf(Joint.HAND_A, Joint.HAND_P)) {
                    val target = frame.limbTargets.firstOrNull { it.joint == joint }
                        ?: error("$name: the published frame must carry $joint's declared target")
                    val sign = if (joint == Joint.HAND_A) -1f else 1f
                    if (abs(target.world.x - handX) > planeBand || abs(target.world.y - handY) > planeBand ||
                        abs(target.world.z - sign * handZ) > planeBand
                    ) {
                        wrongAuthoring.add("$name $condition p=$p $joint target=${target.world.x}")
                    }
                    // The pole is the bend side: the pose's own lateral axis, no vertical component.
                    if (abs(target.pole.x) > 1e-4f || abs(target.pole.y) > 1e-4f ||
                        abs(target.pole.z - sign) > 1e-4f
                    ) {
                        wrongAuthoring.add(
                            "$name $condition p=$p $joint pole=(${f(target.pole.x)}, " +
                                "${f(target.pole.y)}, ${f(target.pole.z)}) — expected (0, 0, ${f(sign)})"
                        )
                    }
                }
            }
        }
        assertTrue(
            "the two arms must be exact mirrors of each other about the body's midline (both poses):\n" +
                asymmetric.take(8).joinToString("\n"),
            asymmetric.isEmpty()
        )
        assertTrue(
            "both supine poses must declare the SAME arm authoring — the static hand placement and the " +
                "bend side on the pose's own lateral axis:\n" + wrongAuthoring.take(8).joinToString("\n"),
            wrongAuthoring.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 5. Choreography — the poses must keep moving, exactly as they did
    // ------------------------------------------------------------------------------------------

    @Test
    fun thePosesChoreographyAndMotionContractAreUnchanged() {
        // GluteBridgePose — the hip lift is the pose's own choreography and its motion contract
        // (CoreMotionTest floor 35 for this pose).
        val gluteFrames = playingFrames("GluteBridgePose")
        val pelvisYs = gluteFrames.map { it.second.getJoint(Joint.PELVIS).y }
        assertTrue(
            "GluteBridgePose: the authored hip lift must be preserved (measured ${f(pelvisYs.max() - pelvisYs.min())})",
            pelvisYs.max() - pelvisYs.min() >= 35f
        )
        assertTrue(
            "GluteBridgePose: the rep must still start resting on the mat (measured ${f(pelvisYs.min())})",
            abs(pelvisYs.min() - 14f) <= 0.05f
        )
        val gluteTravel = MotionProbe.maxVerticalTravel(MotionProbe.build("GluteBridgePose"))
        assertTrue(
            "GluteBridgePose: motion contract unchanged (travel=${f(gluteTravel)}, CoreMotionTest floor 35)",
            gluteTravel >= 35f
        )

        // PelvicTiltPose — the pelvis is the pose's static base and the TORSO tilt is the rep
        // (MobilityMotionTest floor 12 for this pose).
        val tiltFrames = playingFrames("PelvicTiltPose")
        val headYs = tiltFrames.map { it.second.getJoint(Joint.HEAD_POS).y }
        assertTrue(
            "PelvicTiltPose: the authored torso tilt must be preserved (measured spread ${f(headYs.max() - headYs.min())})",
            headYs.max() - headYs.min() >= 12f
        )
        assertEquals(
            "PelvicTiltPose: the pelvis stays the pose's static base",
            listOf(14f),
            tiltFrames.map { it.second.getJoint(Joint.PELVIS).y }.distinct()
        )
        val tiltTravel = MotionProbe.maxVerticalTravel(MotionProbe.build("PelvicTiltPose"))
        assertTrue(
            "PelvicTiltPose: motion contract unchanged (travel=${f(tiltTravel)}, MobilityMotionTest floor 12)",
            tiltTravel >= 12f
        )

        // Anti-vacuity (T-7): the sweep must publish DISTINCT frames of both poses.
        for (name in poses) {
            val frames = playingFrames(name)
            assertEquals(
                "$name: every sampled frame must be an independent capture (the buffer-aliasing trap)",
                sweep.size, frames.map { System.identityHashCode(it.second) }.distinct().size
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 6. Cold-frame invariance (the B-8 class must not be re-opened here)
    // ------------------------------------------------------------------------------------------

    @Test
    fun theArmGeometryIsFrameConditionInvariant() {
        // The band is the measured pre-existing cold/settled residue, not a licence: these poses do
        // not declare their chest frame, so the engine's Phase-3 reconstruction differs from the
        // authored value by float rounding and the chest subtree (the shoulder the arm hangs from)
        // lands ~0.03-0.05 u away on a genuinely cold frame. Measured worst here 0.0467 u — three
        // orders below the 28.6-33.4 u penetration this change corrects and below the 58 u bow.
        val frameBand = 0.1f
        val drifting = mutableListOf<String>()
        for (name in poses) {
            // Both legs run the SAME progress sweep in the same order, so pair them by index.
            val cold = sweep.map { coldFrame(name, it) }
            val playing = playingFrames(name).map { it.second }
            assertEquals("the two frame-condition legs must sample the same sweep", cold.size, playing.size)
            for (i in sweep.indices) {
                val p = sweep[i]
                for (joint in armChain) {
                    val d = dist(cold[i].getJoint(joint), playing[i].getJoint(joint))
                    if (d > frameBand) drifting.add("$name p=$p $joint cold-vs-playing ${f(d)}")
                }
            }
        }
        assertTrue(
            "the arm geometry must not be frame-condition sensitive (a genuinely cold first frame must " +
                "publish the same arm as a settled frame — the B-8 cold-frame defect class):\n" +
                drifting.take(8).joinToString("\n"),
            drifting.isEmpty()
        )
    }
}
