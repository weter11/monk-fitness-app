package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **B4 (trunk half) — `PelvicTiltPose` must not publish its trunk through the mat it declares.**
 *
 * `PelvicTiltPose` is a supine (lying-on-the-back) production pose: a static pelvis at the pose's own
 * resting layer (`y = 14`, the pose's comment *"Pelvis Y remains static on the floor (14f)"*) and a
 * rigid authored trunk chain on top of it (`PELVIS → CHEST(= torsoLength 120) → NECK_END(= 18) →
 * HEAD_POS(= 18)`, plus the shoulders), laid flat by the pose's declared supine tilt
 * (`declarePelvisTilt(..., Vector3(0, 0, 1), torsoAngle)`) and then rotated through the rep by the
 * authored tilt arc (`angleOffset = lerp(0, 0.12, progress)`).
 *
 * **Measured on the tree that carries B1/B2/B3/B4-elbow (`origin/main` @ `7012ccb`), on the published
 * path `SkeletonPipeline.produceFrame(pose, ctx)`:** the authored tilt is `torsoAngle = 1.5708 +
 * angleOffset`, i.e. the pelvis's superior axis tips PAST the flat supine orientation on every phase
 * of the rep, so every trunk joint is carried BELOW the pose's own declared ground plane
 * (`metadata.environment.ground.level = 0`) from `p ≈ 0.855` onward — at `p = 1.0`: `CHEST −0.3659`,
 * `SHOULDER_A/P −0.3659`, `NECK_END −2.5295`, `HEAD_POS −2.5384`; the whole-body minimum is
 * `HEAD_POS −2.5384`. Those five pairs are pinned as attributed open items by
 * `PublishedBelowGroundInvariantTest.knownBelowGround` (T2); that table's stale-pin guard is this
 * correction's exit criterion, so the entries cannot outlive it.
 *
 * **The root cause is the tilt's DIRECTION, never its magnitude (the B1/B4 shape: reproduce the
 * published geometry from the pose's own authoring).** The trunk is a rigid chain hanging off the
 * pose's static pelvis, so a published trunk joint is exactly
 * `pelvis + Rz(θ)·(0, chainLength, 0)` with `Rz(θ)·(0, L, 0) = (−L·sin θ, L·cos θ)`. The chain is
 * `CHEST 120`, `NECK_END 138`, `HEAD_POS` at the flat orientation (the neck's authored articulation
 * `∓angleOffset` cancels the trunk's tilt exactly, so the head chain keeps the supine orientation
 * `1.5708`). The tilt's `+0.12` therefore spends itself on `sin`: the chain's Y drops by
 * `chainLength · sin(0.12)` — `14.3659` at the chest, `16.5205` at `NECK_END`/`HEAD_POS` — against a
 * pelvis that only has `14` of resting layer above the mat. The deficit is the measurement:
 * `CHEST −0.3659 = 14 − 120·sin(0.12)` and `HEAD_POS −2.5384 = 14 − 138·sin(0.12)`, reproduced here
 * from the pose's own authors (see [thePublishedTrunkIsTheAuthoredTiltAndTheSignIsTheDefect]).
 *
 * **The correction is pose-side tilt-direction authoring only** (no solver/engine/phase/ownership
 * change, no tolerance moved, no assertion weakened): the tilt becomes `torsoAngle = 1.5708 −
 * angleOffset`, i.e. the pelvis's superior axis tips AWAY from the mat by the same authored `0.12 rad`,
 * and the neck's articulation flips with it (`+angleOffset`) so the head chain keeps the SAME world
 * supine orientation it always had (the neck's world rotation is `1.5708` before and after). The
 * trunk then swings in the half-space the pose's resting layer allows: at `p = 1.0`
 * `CHEST +28.3650`, `SHOULDER_A/P +28.3650`, `NECK_END +30.5198`, `HEAD_POS +30.5197`, and the pose's
 * whole body measures `min y = 0.000000` at every phase. The pelvis keeps its authored static base,
 * the arms keep the B4 elbow correction (elbow `+12.7988 … +18.5614`, bow IN the floor plane), the
 * legs stay quiet (identical published positions at every phase), and the rep's authored amplitude,
 * start configuration and shape are untouched.
 *
 * **The direction, not the amplitude, is the fix — and the amplitude could not rescue it.** Bounding
 * `angleOffset` so that the DOWNWARD arc stops at the plane needs `sin(offset) ≤ 14 / 138`, which
 * puts the trunk's spine centres (its resting layer is 14 above the mat) on the mat's own surface —
 * a knife-edge reading that sinks the trunk's volume into the floor while keeping the centres legal,
 * with a travel of at most 14 (the pose's mobility floor is 12). Authoring the tilt out of the mat
 * keeps the authored `0.12 rad` (travel `16.5203`, the same arc, mirrored) and a healthy clearance.
 *
 * **What this file asserts (all on the PUBLISHED frame, captured by value — `produceFrame(...).pose`
 * is the Finalizer's reused output buffer, the T-7 trap), over a DENSE 51-sample sweep in both frame
 * conditions:**
 *  1. no published joint of the pose passes below the plane the pose itself declares
 *     ([noPublishedJointPassesBelowThePosesOwnDeclaredPlane]);
 *  2. the trunk keeps the pose's own resting layer instead of tipping through it, the tilt arc keeps
 *     the authored amplitude, and the trunk chain's published geometry is reproduced from the pose's
 *     authors — including the demonstration that the MIRRORED tilt (the pre-fix direction) is exactly
 *     what puts `CHEST`/`HEAD_POS` below the plane
 *     ([theTrunkKeepsThePosesRestingLayerInsteadOfTippingThroughIt],
 *     [thePublishedTrunkIsTheAuthoredTiltAndTheSignIsTheDefect]);
 *  3. the authored rep is preserved — the rep starts at the pose's resting supine configuration, the
 *     pelvis stays the static base, the head travels through the authored range, and the sweep
 *     publishes distinct frames ([theAuthoredTiltRepAndTheRestingConfigurationArePreserved]);
 *  4. the B4 elbow correction is NOT undone — the elbows clear the mat, lie in the floor plane, bow
 *     outboard, stay real solutions of their own chain, and keep the declared
 *     `(0, 0, ∓1)` bend side ([theB4ElbowCorrectionIsNotUndone]);
 *  5. the legs stay quiet and the pose's own declaration (both feet, declared plane) is unchanged
 *     ([theLegsStayQuietAndTheDeclarationIsUnchanged]);
 *  6. the trunk geometry is not frame-condition sensitive (the B-8 cold-frame defect class)
 *     ([theTrunkGeometryIsFrameConditionInvariant]).
 */
class PelvicTiltTrunkPlaneTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private val pose = "PelvicTiltPose"

    /** A DENSE sweep: the crossing is a function of the tilt, so 5 samples could step over it. */
    private val sweep = (0..50).map { it * 0.02f }

    /** The T2 band: a joint the pose authors ON its layer reads its layer value exactly. */
    private val planeBand = 0.05f

    /** The pose's authored supine orientation (its own literal `1.5708f`) — see the class comment. */
    private val supineTilt = 1.5708f

    /** The pose's authored tilt amplitude over the rep. */
    private val tiltAmplitude = 0.12f

    /** The pose's authored pelvis height: the pose's own declared resting layer. */
    private val restingLayer = 14f

    /** The pose's authored static hand placement ("prevents hand sliding"). */
    private val handX = -35f
    private val handY = 12f

    /** A real floor for the realized elbow — an order of magnitude above [planeBand] (the B4 band). */
    private val elbowClearanceFloor = 1.0f

    /** How far the elbow's residual bow may leave its chord's plane (the B4 band). */
    private val flatBand = 2.0f

    /** The trunk joints the pose authors: the pelvis is the chain's root, the rest hang off it. */
    private val trunkChain = listOf(Joint.PELVIS, Joint.CHEST, Joint.SHOULDER_A, Joint.SHOULDER_P, Joint.NECK_END, Joint.HEAD_POS)

    /** The elbow and everything distal of it (the B4 correction's subject). */
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
    private fun coldFrame(p: Float): SkeletonPose =
        snapshot(SkeletonPipeline(def).produceFrame(MotionProbe.build(pose), ctx(p)).pose)

    /** One builder + one pipeline advancing through the sweep, every frame captured by value. */
    private fun playingFrames(): List<Pair<Float, SkeletonPose>> {
        val pipeline = SkeletonPipeline(def)
        val builder = MotionProbe.build(pose)
        return sweep.map { p -> p to snapshot(pipeline.produceFrame(builder, ctx(p)).pose) }
    }

    /** One (condition, progress, frame) triple per evaluated frame of both frame conditions. */
    private fun allFrames(): List<Triple<String, Float, SkeletonPose>> {
        val frames = mutableListOf<Triple<String, Float, SkeletonPose>>()
        for (p in sweep) frames.add(Triple("COLD", p, coldFrame(p)))
        for ((p, f) in playingFrames()) frames.add(Triple("PLAYING", p, f))
        return frames
    }

    /** The plane is READ from the pose's own declaration, never assumed to be zero (the T2 channel). */
    private fun ground(): Float = MotionProbe.build(pose).metadata.environment.ground.level

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private fun dist(a: Vector3, b: Vector3) =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    /** The authored tilt angle at a progress: the pose's own `1.5708 ± lerp(0, 0.12, p)`. */
    private fun authoredTilt(p: Float, mirrored: Boolean = false): Float {
        val offset = tiltAmplitude * p
        return if (mirrored) supineTilt + offset else supineTilt - offset
    }

    /**
     * The pose's own authoring, evaluated: the rigid trunk chain hanging off the static pelvis, with
     * `Rz(θ)·(0, L, 0) = (−L·sin θ, L·cos θ)`. The head chain keeps the flat supine orientation
     * because the neck's authored articulation cancels the trunk's tilt (see [trunkModel]).
     */
    private fun trunkModel(p: Float, mirrored: Boolean = false): Map<Joint, Pair<Float, Float>> {
        val theta = authoredTilt(p, mirrored)
        fun step(from: Pair<Float, Float>, length: Float, angle: Float): Pair<Float, Float> =
            (from.first - length * sin(angle)) to (from.second + length * cos(angle))
        val pelvis = 0f to restingLayer
        val chest = step(pelvis, def.torsoLength, theta)
        val neck = step(chest, def.neckLength, theta)
        val head = step(neck, 18f, supineTilt)
        return mapOf(
            Joint.PELVIS to pelvis, Joint.CHEST to chest, Joint.NECK_END to neck, Joint.HEAD_POS to head
        )
    }

    // ------------------------------------------------------------------------------------------
    // 1. The invariant this fix exists for — every published joint against the pose's own plane
    // ------------------------------------------------------------------------------------------

    @Test
    fun noPublishedJointPassesBelowThePosesOwnDeclaredPlane() {
        val level = ground()
        val frames = allFrames()
        assertEquals(
            "the sweep must evaluate every frame of both conditions",
            sweep.size * 2, frames.size
        )

        val below = mutableListOf<String>()
        for ((condition, p, frame) in frames) {
            for (joint in Joint.entries) {
                val y = frame.getJoint(joint).y
                if (y < level - planeBand) {
                    below.add("$condition p=$p $joint y=${f(y)} (${f(y - level)} vs the declared plane)")
                }
            }
        }
        assertTrue(
            "published joints below the pose's own declared plane (band = $planeBand, plane = $level).\n" +
                "This pose's trunk is a rigid chain on a static pelvis at the pose's resting layer " +
                "($restingLayer): a tilt authored PAST the flat supine orientation carries the chain " +
                "120·sin(0.12) = 14.366 u below that layer:\n" +
                below.take(16).joinToString("\n"),
            below.isEmpty()
        )
        assertTrue(
            "anti-vacuity: the sweep must have evaluated something (${frames.size} frames × " +
                "${Joint.entries.size} joints)",
            frames.size * Joint.entries.size >= 3300
        )
    }

    // ------------------------------------------------------------------------------------------
    // 2. The root cause — the trunk's layer, the arc's amplitude, and the authored model
    // ------------------------------------------------------------------------------------------

    /**
     * The trunk keeps the pose's own resting layer: no trunk joint may descend below the layer the
     * pose's static pelvis declares, at any phase of the rep. The pre-fix authoring crossed it at
     * `p ≈ 0.855` and reached `HEAD_POS −2.5384`; the corrected authoring measures its minimum on the
     * layer itself (`13.9996` at `p = 0.0`).
     */
    @Test
    fun theTrunkKeepsThePosesRestingLayerInsteadOfTippingThroughIt() {
        val deepest = mutableListOf<String>()
        var lowest = Float.MAX_VALUE
        var lowestJoint = Joint.PELVIS
        for ((condition, p, frame) in allFrames()) {
            for (joint in trunkChain) {
                val y = frame.getJoint(joint).y
                if (y < lowest) { lowest = y; lowestJoint = joint }
                if (y < restingLayer - planeBand) {
                    deepest.add("$condition p=$p $joint y=${f(y)} (resting layer $restingLayer)")
                }
            }
        }
        assertTrue(
            "the trunk must not be carried below the pose's own resting layer ($restingLayer — the " +
                "layer its static pelvis declares and its whole supine layout lies on); measured worst " +
                "trunk reading ${f(lowest)} at $lowestJoint:\n" + deepest.take(16).joinToString("\n"),
            deepest.isEmpty()
        )

        // The tilt's amplitude is preserved: the authored arc is 0.12 rad and it is still spent in
        // full — the corrected authoring mirrors the direction, it does not shrink the rep.
        val trunkAngles = playingFrames().map { (p, frame) ->
            val pelvis = frame.getJoint(Joint.PELVIS)
            val chest = frame.getJoint(Joint.CHEST)
            kotlin.math.atan2(chest.y - pelvis.y, chest.x - pelvis.x) to p
        }
        val flat = kotlin.math.atan2(0f, -1f)
        val maxSwing = trunkAngles.maxOf { (a, _) -> abs(normalizeRadians(a - flat)) }
        assertEquals(
            "the authored tilt arc must be spent in full: the trunk's world axis must swing the " +
                "authored 0.12 rad off the flat supine orientation (measured ${f(maxSwing)})",
            tiltAmplitude, maxSwing, 0.01f
        )
    }

    /**
     * **The defect is the SIGN, provably** (the B1/B4 "re-derive from the pose's own authors" shape).
     *
     * (a) The corrected authoring reproduces the published trunk EXACTLY:
     * `pelvis + Rz(1.5708 ∓ offset)·(0, chainLength, 0)`, with the head chain at the flat supine
     * orientation (the neck's `±offset` articulation cancels the trunk's tilt, so the neck's world
     * rotation is `1.5708` both before and after this change).
     * (b) The MIRRORED tilt — the pre-fix `1.5708 + offset` — puts the same chain below the pose's
     * own plane at the top of the rep, and the depths are the ticket's: `CHEST = 14 − 120·sin(0.12) =
     * −0.3659`, `HEAD_POS = 14 − 138·sin(0.12) = −2.5384`.
     *
     * So nothing else (solver, finalizer, frames, amplitudes) contributes: the authored tilt's
     * direction is the whole cause.
     */
    @Test
    fun thePublishedTrunkIsTheAuthoredTiltAndTheSignIsTheDefect() {
        val mine = mutableListOf<String>()
        val mirrored = mutableListOf<String>()

        for ((p, frame) in playingFrames()) {
            val model = trunkModel(p)
            val mirroredModel = trunkModel(p, mirrored = true)
            for (joint in listOf(Joint.CHEST, Joint.NECK_END, Joint.HEAD_POS)) {
                val published = frame.getJoint(joint)
                val (mx, my) = model[joint]!!
                if (abs(published.x - mx) > planeBand || abs(published.y - my) > planeBand) {
                    mine.add("p=$p $joint published=(${f(published.x)}, ${f(published.y)}) model=(${f(mx)}, ${f(my)})")
                }
                val (nx, ny) = mirroredModel[joint]!!
                if (abs(published.x - nx) > planeBand || abs(published.y - ny) > planeBand) {
                    mirrored.add("p=$p $joint published=(${f(published.x)}, ${f(published.y)}) mirrored=(${f(nx)}, ${f(ny)})")
                }
            }
        }
        assertTrue(
            "the published trunk must BE the pose's own authoring — the static pelvis plus the " +
                "authored tilt and chain lengths (this is what makes the tilt the whole cause):\n" +
                mine.take(8).joinToString("\n"),
            mine.isEmpty()
        )
        assertTrue(
            "control: the MIRRORED tilt (the pre-fix `1.5708 + angleOffset`) must NOT reproduce the " +
                "published trunk — it is the authoring this fix removes:\n" + mirrored.take(8).joinToString("\n"),
            mirrored.isNotEmpty()
        )

        // (b) …and the mirrored tilt is exactly the below-plane authoring: at the top of the rep it
        // lands on the ticket's numbers, still inside the chain-length model this pose declares.
        val top = trunkModel(1.0f, mirrored = true)
        assertEquals(
            "the pre-fix tilt's chest depth is 14 − 120·sin(0.12) — the ticket's CHEST reading",
            14f - def.torsoLength * sin(tiltAmplitude), top[Joint.CHEST]!!.second, 0.01f
        )
        assertEquals(
            "the pre-fix tilt's head depth is 14 − 138·sin(0.12) — the ticket's HEAD_POS reading",
            14f - (def.torsoLength + def.neckLength) * sin(tiltAmplitude), top[Joint.HEAD_POS]!!.second, 0.01f
        )
        assertTrue(
            "the pre-fix direction is below the pose's own declared plane at the top of the rep " +
                "(chest ${f(top[Joint.CHEST]!!.second)}, head ${f(top[Joint.HEAD_POS]!!.second)})",
            top[Joint.CHEST]!!.second < -planeBand && top[Joint.HEAD_POS]!!.second < -planeBand
        )
        assertTrue(
            "the corrected direction is above it at the same phase (chest " +
                "${f(trunkModel(1.0f)[Joint.CHEST]!!.second)})",
            trunkModel(1.0f)[Joint.CHEST]!!.second > planeBand
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3. The authored rep — nothing about the exercise's motion may be lost
    // ------------------------------------------------------------------------------------------

    @Test
    fun theAuthoredTiltRepAndTheRestingConfigurationArePreserved() {
        val frames = playingFrames()
        val atRest = frames.first().second
        val atEnd = frames.last().second

        // p = 0.0 is the pose's own supine resting configuration (BPS §3: back on the floor).
        for (joint in trunkChain) {
            assertEquals(
                "$joint: the rep must start at the pose's resting supine configuration (its layer)",
                restingLayer, atRest.getJoint(joint).y, 0.01f
            )
        }
        // …and p = 1.0 is the authored end range: the authored tilt in full, on the chain's lengths.
        assertEquals(
            "the end range must be the authored tilt of the authored chain (chest)",
            def.torsoLength * sin(tiltAmplitude), atEnd.getJoint(Joint.CHEST).y - restingLayer, 0.01f
        )
        assertEquals(
            "the end range must be the authored tilt of the authored chain (head, at the flat " +
                "head-chain orientation)",
            (def.torsoLength + def.neckLength) * sin(tiltAmplitude),
            atEnd.getJoint(Joint.HEAD_POS).y - restingLayer, 0.01f
        )

        // The pelvis is the pose's static base; the rep moves the trunk, not the root.
        assertEquals(
            "the pelvis stays the pose's static base (its declared resting layer)",
            listOf(restingLayer), frames.map { it.second.getJoint(Joint.PELVIS).y }.distinct()
        )

        // Motion contract (MobilityMotionTest floor 12 for this pose).
        val headYs = frames.map { it.second.getJoint(Joint.HEAD_POS).y }
        assertTrue(
            "the authored tilt must still travel through its range (head Y spread ${f(headYs.max() - headYs.min())})",
            headYs.max() - headYs.min() >= 12f
        )
        val travel = MotionProbe.maxTravel3D(MotionProbe.build(pose))
        assertTrue(
            "motion contract unchanged (travel = ${f(travel)}, MobilityMotionTest floor 12)",
            travel >= 12f
        )

        // Anti-vacuity (T-7): the sweep must publish DISTINCT frames (no buffer aliasing).
        assertEquals(
            "every sampled frame must be an independent capture (the buffer-aliasing trap)",
            sweep.size, frames.map { System.identityHashCode(it.second) }.distinct().size
        )
    }

    // ------------------------------------------------------------------------------------------
    // 4. The B4 elbow correction must NOT be undone by this change
    // ------------------------------------------------------------------------------------------

    @Test
    fun theB4ElbowCorrectionIsNotUndone() {
        val level = ground()
        val deepest = mutableListOf<String>()
        val bowed = mutableListOf<String>()
        val inboard = mutableListOf<String>()
        val broken = mutableListOf<String>()

        for ((condition, p, frame) in allFrames()) {
            for (side in listOf("_A", "_P")) {
                val elbowJ = if (side == "_A") Joint.ELBOW_A else Joint.ELBOW_P
                val shoulderJ = if (side == "_A") Joint.SHOULDER_A else Joint.SHOULDER_P
                val handJ = if (side == "_A") Joint.HAND_A else Joint.HAND_P
                val elbow = frame.getJoint(elbowJ)
                val shoulder = frame.getJoint(shoulderJ)
                val hand = frame.getJoint(handJ)

                if (elbow.y < level + elbowClearanceFloor) {
                    deepest.add("$condition p=$p $side y=${f(elbow.y)}")
                }
                val bow = bowOffsetY(shoulder, elbow, hand)
                if (abs(bow) > flatBand) bowed.add("$condition p=$p $side bow=${f(bow)}")
                if (abs(elbow.z) < abs(shoulder.z)) {
                    inboard.add("$condition p=$p $side elbow.z=${f(elbow.z)} shoulder.z=${f(shoulder.z)}")
                }
                val l1 = dist(shoulder, elbow); val l2 = dist(elbow, hand)
                if (abs(l1 - def.upperArmLength) > 0.05f || abs(l2 - def.forearmLength) > 0.05f) {
                    broken.add("$condition p=$p $side |shoulder-elbow|=${f(l1)} |elbow-hand|=${f(l2)}")
                }
            }
        }
        assertTrue("the realized elbows must clear the mat (B4):\n" + deepest.take(8).joinToString("\n"), deepest.isEmpty())
        assertTrue(
            "the arms must stay in the floor plane — the B4 bend side, not a vertical bow:\n" +
                bowed.take(8).joinToString("\n"),
            bowed.isEmpty()
        )
        assertTrue("the elbow must stay outboard of its shoulder line:\n" + inboard.take(8).joinToString("\n"), inboard.isEmpty())
        assertTrue(
            "the elbow must remain a real IK solution of its own chain:\n" + broken.take(8).joinToString("\n"),
            broken.isEmpty()
        )

        // The declared bend side is the B4 one (the pose's own lateral axis), the hands stay at the
        // authored static placement and realize it (no solver relocation).
        val wrongAuthoring = mutableListOf<String>()
        for ((condition, p, frame) in allFrames()) {
            for (joint in listOf(Joint.HAND_A, Joint.HAND_P)) {
                val sign = if (joint == Joint.HAND_A) -1f else 1f
                val target = frame.limbTargets.firstOrNull { it.joint == joint }
                    ?: error("the published frame must carry the arm's declared target (the §1.1 carrier)")
                val hand = frame.getJoint(joint)
                if (abs(hand.x - handX) > planeBand || abs(hand.y - handY) > planeBand) {
                    wrongAuthoring.add("$condition p=$p $joint hand=(${f(hand.x)}, ${f(hand.y)})")
                }
                if (dist(hand, target.world) > planeBand) {
                    wrongAuthoring.add("$condition p=$p $joint relocated ${f(dist(hand, target.world))}")
                }
                if (abs(target.pole.x) > 1e-4f || abs(target.pole.y) > 1e-4f || abs(target.pole.z - sign) > 1e-4f) {
                    wrongAuthoring.add(
                        "$condition p=$p $joint pole=(${f(target.pole.x)}, ${f(target.pole.y)}, " +
                            "${f(target.pole.z)}) — the B4 bend side is (0, 0, ${f(sign)})"
                    )
                }
            }
        }
        assertTrue(
            "the B4 elbow correction's authored hand placement and bend side must be intact:\n" +
                wrongAuthoring.take(8).joinToString("\n"),
            wrongAuthoring.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 5. The legs stay quiet; the pose's declaration is the pose's own
    // ------------------------------------------------------------------------------------------

    @Test
    fun theLegsStayQuietAndTheDeclarationIsUnchanged() {
        val feet = listOf(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F,
            Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
        val drifting = mutableListOf<String>()
        val below = mutableListOf<String>()
        val relocated = mutableListOf<String>()
        val frames = playingFrames()
        val first = frames.first().second

        for ((p, frame) in frames) {
            for (joint in feet) {
                val now = frame.getJoint(joint)
                val start = first.getJoint(joint)
                // BPS §7: "the legs remain quiet; only the pelvis rotates" — the pelvis's tilt must
                // not leak into the leg chain (the leg IK's root, target and pole are all static).
                if (dist(now, start) > planeBand) {
                    drifting.add("p=$p $joint start=(${f(start.x)}, ${f(start.y)}) now=(${f(now.x)}, ${f(now.y)})")
                }
                if (now.y < ground() - planeBand) below.add("p=$p $joint y=${f(now.y)}")
            }
        }
        // The legs' DECLARED stance — the pose's own authoring, not this fix's subject.
        for ((joint, sign) in listOf(Joint.ANKLE_F to -1f, Joint.ANKLE_B to 1f)) {
            val target = first.limbTargets.firstOrNull { it.joint == joint }
                ?: error("the published frame must carry the leg's declared target")
            if (abs(target.world.x - 45f) > planeBand || abs(target.world.y - def.foot.ankleHeight) > planeBand ||
                abs(target.world.z - sign * def.hipWidth) > planeBand
            ) {
                relocated.add("$joint target=(${f(target.world.x)}, ${f(target.world.y)}, ${f(target.world.z)})")
            }
        }

        assertTrue(
            "the legs must stay quiet through the rep — the tilt moves the trunk, not the leg chain:\n" +
                drifting.take(8).joinToString("\n"),
            drifting.isEmpty()
        )
        assertTrue("the planted feet must stay above the declared plane:\n" + below.take(8).joinToString("\n"), below.isEmpty())
        assertTrue("the leg authoring must be the pose's own:\n" + relocated.take(8).joinToString("\n"), relocated.isEmpty())

        assertEquals(
            "the pose's declared plane must be the pose's own (read, never assumed)",
            0f, ground(), 0f
        )
        assertEquals(
            "the pose's declared support model must be the pose's own (unchanged by this correction)",
            setOf(SupportPoint.LEFT_FOOT, SupportPoint.RIGHT_FOOT),
            MotionProbe.build(pose).metadata.support.contacts.map { it.point }.toSet()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 6. Cold-frame invariance (the B-8 class must not be re-opened here)
    // ------------------------------------------------------------------------------------------

    @Test
    fun theTrunkGeometryIsFrameConditionInvariant() {
        // The position drift of the engine's chest-frame reconstruction on a genuinely cold first
        // frame (this pose does not declare its chest frame). Measured pre-existing residue ~0.05;
        // this change must not add a cold-frame defect (the B-8 class), so the band is 0.1 — three
        // orders below the 14.37/16.52 u this correction moves the trunk up out of the mat.
        val frameBand = 0.1f
        val drifting = mutableListOf<String>()
        val cold = sweep.map { coldFrame(it) }
        val playing = playingFrames().map { it.second }
        assertEquals("the two frame-condition legs must sample the same sweep", cold.size, playing.size)
        for (i in sweep.indices) {
            for (joint in trunkChain + armChain) {
                val d = dist(cold[i].getJoint(joint), playing[i].getJoint(joint))
                if (d > frameBand) drifting.add("p=${sweep[i]} $joint cold-vs-playing ${f(d)}")
            }
        }
        assertTrue(
            "the trunk and arm geometry must not be frame-condition sensitive (the B-8 cold-frame " +
                "defect class):\n" + drifting.take(8).joinToString("\n"),
            drifting.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    /** The elbow's signed Y offset from its own shoulder→hand chord (the B4 in-plane bow measure). */
    private fun bowOffsetY(shoulder: Vector3, elbow: Vector3, hand: Vector3): Float {
        val cx = hand.x - shoulder.x; val cy = hand.y - shoulder.y; val cz = hand.z - shoulder.z
        val vx = elbow.x - shoulder.x; val vy = elbow.y - shoulder.y; val vz = elbow.z - shoulder.z
        val len2 = cx * cx + cy * cy + cz * cz
        val t = (vx * cx + vy * cy + vz * cz) / len2
        return elbow.y - (shoulder.y + t * cy)
    }

    /** The signed smallest rotation carrying one angle onto another, in radians. */
    private fun normalizeRadians(a: Float): Float {
        var x = a
        while (x > Math.PI) x -= (2 * Math.PI).toFloat()
        while (x < -Math.PI) x += (2 * Math.PI).toFloat()
        return x
    }
}
