package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.CatCowPose
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **The spinal wave of Cat-Cow is carried by the SPINE, not by the root.**
 *
 * `CatCowPose`'s published motion used to be a rigid trunk/root bob: the pelvis's tilt was the whole
 * authored arc (`declarePelvisTilt(pelvis, …, spineTilt)` and nothing else), the canonical
 * `PELVIS -> LUMBAR -> CHEST` spine published **no articulation at all** — the `LUMBAR`'s world
 * rotation was bit-identical to the `PELVIS`'s and the `CHEST`'s `localRotation` was `≡ 0` at every
 * sampled phase — and the trunk's own chord swept `2.386°` across the whole rep while the body
 * translated `5` u at the pelvis and `10` u at the chest (all measured through
 * `SkeletonPipeline.produceFrame`). The exercise's identity is spinal flexion/extension, so a rigid
 * trunk cannot represent it (BPS `Cat-Cow (Reps)` §1/§5: "it mobilizes the entire vertebral column,
 * particularly the thoracic and lumbar regions"; "the motion is a sequential wave from the
 * pelvis/coccyx through the lumbar, thoracic, and cervical segments").
 *
 * The authored wave this file pins (`CAT_FLEXION` … `COW_EXTENSION`, the pelvis's own tilt share and
 * the thoracic share) is the pose's declaration, restated here as the contract:
 *
 *  * the pelvis→chest chord's flexion `= lerp(A, −0.0417, progress)`, `A = asin(5/120) ≈ 0.0417` —
 *    the Cat end's shoulder end rises `torsoLength · sin(A) = 5.00` u above the pelvis, the Cow end
 *    sinks
 *    `torsoLength · sin(0.0417) = 5.00` u below it;
 *  * the PELVIS's own tilt reverses with the wave (BPS §3/§7/§9: posterior "tail tuck" in Cat,
 *    anterior "tail lift" in Cow) and is a fraction of the wave, never the whole of it;
 *  * the LUMBAR carries the remainder of the chord's motion — the visible flexion/extension — and the
 *    CHEST carries the thoracic share;
 *  * nothing else moves: the pelvis's placement schedule (which the leg chain, the floor relationship
 *    and the four-point base resolve against), the leg targets/poles, the planted hands, the gaze
 *    sweep and the cycle/phase timing are the pose's authored ones, unchanged.
 *
 * Every measurement is taken on the **published frame** (`SkeletonPipeline.produceFrame(pose, ctx)`,
 * the production entry point), captured BY VALUE — the pipeline hands back a reused mutable buffer
 * (the T-7 aliasing trap), so a stored `.pose` reference would silently alias every sample.
 */
class CatCowSpineWaveTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val torso = def.torsoLength       // 120
    private val neckLength = def.neckLength   // 18
    private val headLength = 18f
    private val ankleHeight = def.foot.ankleHeight

    /** A dense sweep, so the wave's shape is measured rather than sampled. */
    private val sweep = (0..12).map { it / 12f }

    /** The pose's authored quadruped layout: the trunk chord lies horizontally. */
    private val quadrupedPitch = (PI / 2.0).toFloat()

    /**
     * The Cat end's world-space chord flexion — `asin(5/120) ≈ 0.0417` rad, i.e.
     * `torsoLength·sin(A) = 5.00` u of rise: the STRICT pre-fix envelope (the pre-correction rep's
     * own Cow-end `5` u sag, mirrored at the Cat end), by owner decision.
     */
    private val catFlexion = asin(5f / 120f)

    /**
     * The Cow end's world-space chord extension (`torsoLength·sin(0.0417) = 5.00` u of drop) — the
     * pre-correction authoring's own Cow extreme, preserved so the exercise's other end does not move.
     */
    private val cowExtension = 0.0417f

    /** The PELVIS's own tilt at each end range (posterior in Cat, anterior in Cow). */
    private val catPelvicTilt = 0.045f
    private val cowPelvicTilt = 0.045f

    /** The thoracic segment's share of the wave. */
    private val thoracicShare = 0.25f

    /** The pose's authored pelvis placement/bend: X = 50, Y = lerp(45, 40, p) + ankleHeight. */
    private val pelvisX = 50f
    private fun pelvisY(p: Float) = 45f + (40f - 45f) * p + ankleHeight

    /** The pose's mat level (its declared ground plane) — where the hands are planted. */
    private val matLevel = 0f

    /** Angular tolerance: the geometry is reproduced from the same float expressions. */
    private val angleBand = 0.005f

    /** Positional tolerance for the trunk/limb models. */
    private val posBand = 0.05f

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun snapshot(frame: SkeletonPose): SkeletonPose =
        SkeletonPose().apply { copyFrom(frame) }

    /** A genuinely COLD frame: a fresh pose instance on a fresh pipeline. */
    private fun coldFrame(p: Float): SkeletonPose =
        snapshot(SkeletonPipeline(def).produceFrame(CatCowPose(), ctx(p)).pose)

    /** One builder + one pipeline advancing through the sweep; every frame captured by value. */
    private fun playingFrames(): List<Pair<Float, SkeletonPose>> {
        val pipeline = SkeletonPipeline(def)
        val builder = CatCowPose()
        return sweep.map { p -> p to snapshot(pipeline.produceFrame(builder, ctx(p)).pose) }
    }

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private fun deg(v: Float) = String.format(Locale.US, "%.3f", Math.toDegrees(v.toDouble()))

    private fun dist(a: Vector3, b: Vector3) =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    /** The authored wave at a phase: the pelvis→chest chord's flexion, `+` at Cat. */
    private fun authoredFlexion(p: Float) = catFlexion + (-cowExtension - catFlexion) * p

    /** The authored pelvis tilt at a phase: `+` = posterior (Cat), `−` = anterior (Cow). */
    private fun authoredPelvicTilt(p: Float) = catPelvicTilt + (-cowPelvicTilt - catPelvicTilt) * p

    /** The authored LUMBAR articulation (the chord's motion the pelvis does not carry). */
    private fun authoredLumbar(p: Float) = -authoredFlexion(p) - authoredPelvicTilt(p)

    /** The authored CHEST articulation (the thoracic segment's share of the wave). */
    private fun authoredThoracic(p: Float) = -thoracicShare * authoredFlexion(p)

    /** The signed rotation of a published joint about +Z (all these articulations are about Z). */
    private fun zAngle(pose: SkeletonPose, joint: Joint): Float {
        val r = pose.getJointRotation(joint)
        val sign = if (r.axis.z < 0f) -1f else 1f
        return r.angle * sign
    }

    /** The published chord's flexion: the pelvis→chest direction's angle off the horizontal, `+` up. */
    private fun publishedFlexion(pose: SkeletonPose): Float {
        val pel = pose.getJoint(Joint.PELVIS)
        val chest = pose.getJoint(Joint.CHEST)
        return atan2(chest.y - pel.y, -(chest.x - pel.x))
    }

    // ------------------------------------------------------------------------------------------
    // 1. The defect itself — which joint carries the rep
    // ------------------------------------------------------------------------------------------

    @Test
    fun theSpinalWaveIsCarriedByTheCanonicalSpineSegmentsNotByThePelvis() {
        val frames = playingFrames()
        val witnesses = mutableListOf<String>()
        val wrongLumbar = mutableListOf<String>()
        val wrongChest = mutableListOf<String>()
        val wrongPelvis = mutableListOf<String>()

        for ((p, frame) in frames) {
            val pelvisRot = zAngle(frame, Joint.PELVIS)
            val lumbarRot = zAngle(frame, Joint.LUMBAR)
            val chestRot = zAngle(frame, Joint.CHEST)
            val lumbarLocal = lumbarRot - pelvisRot
            val chestLocal = chestRot - lumbarRot
            val pelvisLocal = pelvisRot - quadrupedPitch

            witnesses.add(
                "p=$p pelvis=${deg(pelvisLocal)}° lumbar=${deg(lumbarLocal)}° chest=${deg(chestLocal)}° " +
                    "chord=${deg(publishedFlexion(frame))}°"
            )

            // The LUMBAR (relative to the PELVIS) carries the remainder of the chord's motion.
            if (abs(lumbarLocal - authoredLumbar(p)) > angleBand) {
                wrongLumbar.add(
                    "p=$p the LUMBAR's own articulation is ${f(lumbarLocal)} rad " +
                        "(${deg(lumbarLocal)}°), the authored wave requires ${f(authoredLumbar(p))} " +
                        "(${deg(authoredLumbar(p))}°)"
                )
            }
            // The CHEST carries the thoracic share of the same wave.
            if (abs(chestLocal - authoredThoracic(p)) > angleBand) {
                wrongChest.add(
                    "p=$p the CHEST's own articulation is ${f(chestLocal)} rad " +
                        "(${deg(chestLocal)}°), the thoracic share requires ${f(authoredThoracic(p))}"
                )
            }
            // The PELVIS carries ITS OWN tilt (reversing with the wave), not the wave.
            if (abs(pelvisLocal - authoredPelvicTilt(p)) > angleBand) {
                wrongPelvis.add(
                    "p=$p the PELVIS's own tilt is ${f(pelvisLocal)} rad (${deg(pelvisLocal)}°), the " +
                        "authored pelvic tilt is ${f(authoredPelvicTilt(p))} (${deg(authoredPelvicTilt(p))}°)"
                )
            }
        }

        assertTrue(
            "the LUMBAR must carry the wave's remainder as its own articulation — the defect was a " +
                "rigid trunk whose lower-spine segment published the PELVIS's rotation verbatim:\n" +
                wrongLumbar.take(6).joinToString("\n") + "\nwitnesses:\n" + witnesses.joinToString("\n"),
            wrongLumbar.isEmpty()
        )
        assertTrue(
            "the CHEST must carry the thoracic share of the wave:\n" + wrongChest.take(6).joinToString("\n"),
            wrongChest.isEmpty()
        )
        assertTrue(
            "the PELVIS must carry its own pelvic tilt — the amount the exercise justifies for it, " +
                "which is NOT the whole wave (BPS §3/§7:\"the pelvis may retain the amount of motion " +
                "justified by the exercise\"):\n" + wrongPelvis.take(6).joinToString("\n"),
            wrongPelvis.isEmpty()
        )

        // The three articulations are three DIFFERENT motions: at the Cat end the lower spine and the
        // rib cage are both articulated, and neither equals the root's.
        //
        // The floors are the MEASUREMENT band (the articulations must exceed the tolerance they are
        // compared in and differ from one another), not an absolute angle calibrated to an amplitude:
        // under the strict pre-fix envelope (`CAT_FLEXION = asin(5/120) ≈ 0.0417` rad) the thoracic
        // share is `0.0104` rad, i.e. `2.1 ×` this band, which is the owner-decided amplitude's own
        // consequence. The primary assertion above (each segment EQUALS its authored share) is
        // unchanged and is the real contract.
        val cat = frames.first().second
        val catPelvis = zAngle(cat, Joint.PELVIS) - quadrupedPitch
        val catLumbar = zAngle(cat, Joint.LUMBAR) - zAngle(cat, Joint.PELVIS)
        val catChest = zAngle(cat, Joint.CHEST) - zAngle(cat, Joint.LUMBAR)
        assertTrue(
            "anti-vacuity: the Cat end must articulate all three segments differently " +
                "(pelvis ${f(catPelvis)}, lumbar ${f(catLumbar)}, chest ${f(catChest)}; band ${f(angleBand)})",
            abs(catLumbar) > angleBand && abs(catChest) > angleBand &&
                abs(catPelvis) > angleBand &&
                abs(catLumbar - catPelvis) > angleBand && abs(catLumbar - catChest) > angleBand
        )
    }

    // ------------------------------------------------------------------------------------------
    // 2. The wave's own arc (the exercise's visible identity)
    // ------------------------------------------------------------------------------------------

    @Test
    fun theTrunkChordSweepsTheAuthoredCatAndCowArc() {
        val frames = playingFrames()
        val wrong = mutableListOf<String>()
        for ((p, frame) in frames) {
            val measured = publishedFlexion(frame)
            val authored = authoredFlexion(p)
            if (abs(measured - authored) > angleBand) {
                wrong.add("p=$p chord flexion measured ${f(measured)} rad (${deg(measured)}°), authored ${f(authored)}")
            }
        }
        assertTrue(
            "the pelvis→chest chord must BE the authored wave (the Cat round and the Cow arch on the " +
                "chord's own two ends):\n" + wrong.take(6).joinToString("\n"),
            wrong.isEmpty()
        )

        val cat = frames.first().second
        val cow = frames.last().second
        assertEquals(
            "the Cat end must rise the authored torsoLength·sin($catFlexion) above the pelvis",
            torso * sin(catFlexion), cat.getJoint(Joint.CHEST).y - cat.getJoint(Joint.PELVIS).y, 0.05f
        )
        assertEquals(
            "the Cow end must sink the authored torsoLength·sin($cowExtension) below the pelvis",
            -torso * sin(cowExtension), cow.getJoint(Joint.CHEST).y - cow.getJoint(Joint.PELVIS).y, 0.05f
        )
        val swing = abs(publishedFlexion(cat) - publishedFlexion(cow))
        assertEquals(
            "the rep's total chord swing must be the authored Cat flexion + Cow extension (no added ROM)",
            catFlexion + cowExtension, swing, 0.01f
        )
        assertTrue(
            "the wave must be monotonically progressive through the rep (Cat → Cow, the pose's own " +
                "phase order); measured chord flexions " +
                frames.joinToString(", ") { deg(publishedFlexion(it.second)) },
            frames.zipWithNext().all { (a, b) -> publishedFlexion(b.second) < publishedFlexion(a.second) + 1e-4f }
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3. The distribution: the pelvis keeps its few degrees, the spine carries the range
    // ------------------------------------------------------------------------------------------

    @Test
    fun theFlexionPropagatesThroughTheLumbarAndThoracicSegments() {
        val frames = playingFrames()
        val cat = frames.first().second
        val cow = frames.last().second

        val pelvisSwing = abs(
            (zAngle(cat, Joint.PELVIS) - quadrupedPitch) - (zAngle(cow, Joint.PELVIS) - quadrupedPitch)
        )
        val lumbarSwing = abs(
            (zAngle(cat, Joint.LUMBAR) - zAngle(cat, Joint.PELVIS)) -
                (zAngle(cow, Joint.LUMBAR) - zAngle(cow, Joint.PELVIS))
        )
        val thoracicSwing = abs(
            (zAngle(cat, Joint.CHEST) - zAngle(cat, Joint.LUMBAR)) -
                (zAngle(cow, Joint.CHEST) - zAngle(cow, Joint.LUMBAR))
        )
        val chordSwing = abs(publishedFlexion(cat) - publishedFlexion(cow))

        assertTrue(
            "the lower spine must carry MORE of the wave than the pelvis does — the pelvis's tilt is " +
                "the exercise's justified share, not the movement (measured pelvis ${deg(pelvisSwing)}° " +
                "vs lumbar ${deg(lumbarSwing)}°, chord ${deg(chordSwing)}°)",
            lumbarSwing > pelvisSwing * 1.5f && lumbarSwing > chordSwing * 0.8f
        )
        assertTrue(
            "the thoracic segment must carry a real share of the same wave (measured " +
                "${deg(thoracicSwing)}° = ${f(thoracicSwing)} rad; the authored share at each end is " +
                "${f(abs(authoredThoracic(0f)))} rad, the comparison band ${f(angleBand)} rad)",
            // The floor is the comparison band (2×), not an absolute angle calibrated to an older
            // amplitude: under the strict pre-fix envelope the total thoracic swing is
            // `2 · 0.25 · asin(5/120) = 0.0208` rad = `2.1 ×` the band. The exact-share assertion is
            // the wave test's own (`theSpinalWaveIsCarriedByTheCanonicalSpineSegmentsNotByThePelvis`).
            thoracicSwing > angleBand * 2f
        )

        // The pelvis's tilt REVERSES with the wave (BPS §3/§7/§9): posterior in Cat, anterior in Cow.
        assertTrue(
            "the pelvis must tilt posteriorly at the Cat end (measured ${deg(zAngle(cat, Joint.PELVIS) - quadrupedPitch)}°)",
            zAngle(cat, Joint.PELVIS) - quadrupedPitch > angleBand
        )
        assertTrue(
            "the pelvis must tilt anteriorly at the Cow end (measured ${deg(zAngle(cow, Joint.PELVIS) - quadrupedPitch)}°)",
            zAngle(cow, Joint.PELVIS) - quadrupedPitch < -angleBand
        )

        // No added range: the pelvis + the lumbar sum to the authored rep at every phase.
        val wrong = mutableListOf<String>()
        for ((p, frame) in frames) {
            val sum = zAngle(frame, Joint.PELVIS) + (zAngle(frame, Joint.LUMBAR) - zAngle(frame, Joint.PELVIS))
            val authored = quadrupedPitch - authoredFlexion(p)
            if (abs(sum - authored) > angleBand) {
                wrong.add("p=$p pelvis+lumbar = ${f(sum)} rad, the authored chord = ${f(authored)}")
            }
        }
        assertTrue(
            "the split must not add range: the two lower segments always sum to the authored chord:\n" +
                wrong.take(6).joinToString("\n"),
            wrong.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 4. Sagittal-only, rigid bones (BPS §5/§11) — no rotation, no lateral flexion
    // ------------------------------------------------------------------------------------------

    @Test
    fun theWaveStaysSagittalAndTheBonesKeepTheirLengths() {
        val frames = playingFrames()
        val leakyAxis = mutableListOf<String>()
        val outOfPlane = mutableListOf<String>()
        val wrongBones = mutableListOf<String>()

        for ((p, frame) in frames) {
            for (joint in listOf(Joint.PELVIS, Joint.LUMBAR, Joint.CHEST)) {
                val r = frame.getJointRotation(joint)
                if (abs(r.axis.x) > 1e-4f || abs(r.axis.y) > 1e-4f) {
                    leakyAxis.add("p=$p $joint axis=(${f(r.axis.x)}, ${f(r.axis.y)}, ${f(r.axis.z)})")
                }
            }
            // The spine/neck/head chain is the sagittal plane's own subject: it must stay in the
            // x=0/z=0 plane at every phase (the shoulders straddle the chest along Z by construction,
            // so they are not part of this check).
            for (joint in listOf(Joint.PELVIS, Joint.LUMBAR, Joint.CHEST, Joint.NECK_END, Joint.HEAD_POS)) {
                if (abs(frame.getJoint(joint).z) > posBand) {
                    outOfPlane.add("p=$p $joint z=${f(frame.getJoint(joint).z)}")
                }
            }
            if (abs(dist(frame.getJoint(Joint.PELVIS), frame.getJoint(Joint.CHEST)) - torso) > posBand) {
                wrongBones.add(
                    "p=$p the trunk bone reads " +
                        "${f(dist(frame.getJoint(Joint.PELVIS), frame.getJoint(Joint.CHEST)))}"
                )
            }
            if (abs(dist(frame.getJoint(Joint.CHEST), frame.getJoint(Joint.NECK_END)) - neckLength) > posBand) {
                wrongBones.add("p=$p the neck bone moved")
            }
            if (abs(dist(frame.getJoint(Joint.NECK_END), frame.getJoint(Joint.HEAD_POS)) - headLength) > posBand) {
                wrongBones.add("p=$p the head bone moved")
            }
        }
        assertTrue("the spine's articulations must be about the lateral (Z) axis only:\n" + leakyAxis.take(6).joinToString("\n"), leakyAxis.isEmpty())
        assertTrue("the body is sagittal — no joint may leave the x=0/z=0 plane:\n" + outOfPlane.take(6).joinToString("\n"), outOfPlane.isEmpty())
        assertTrue("the trunk/neck/head bones must keep the definition's lengths:\n" + wrongBones.take(6).joinToString("\n"), wrongBones.isEmpty())
    }

    // ------------------------------------------------------------------------------------------
    // 5. Everything else the pose is: support, planting, placement, cycle
    // ------------------------------------------------------------------------------------------

    @Test
    fun theFourPointBaseThePlantingAndThePlacementAreUnchanged() {
        val declared = CatCowPose().metadata.support.contacts.map { it.point }.toSet()
        assertEquals(
            "the pose's declared four-point base must be unchanged (both hands, both knees)",
            setOf(SupportPoint.LEFT_HAND, SupportPoint.RIGHT_HAND, SupportPoint.LEFT_KNEE, SupportPoint.RIGHT_KNEE),
            declared
        )

        val wrongHands = mutableListOf<String>()
        val wrongPlacement = mutableListOf<String>()
        val wrongLegs = mutableListOf<String>()
        val relocated = mutableListOf<String>()

        for ((p, frame) in playingFrames()) {
            // The declaration reaches the published frame (R8/B-5), and the pose's own placement
            // schedule is untouched: the pelvis stays at (50, lerp(45, 40, p) + ankleHeight).
            if (frame.supportedPoints.toSet() != declared) {
                wrongPlacement.add("p=$p published supportedPoints=${frame.supportedPoints}")
            }
            val pelvis = frame.getJoint(Joint.PELVIS)
            if (abs(pelvis.x - pelvisX) > posBand || abs(pelvis.y - pelvisY(p)) > posBand) {
                wrongPlacement.add("p=$p pelvis=(${f(pelvis.x)}, ${f(pelvis.y)})")
            }
            // The hands stay planted flat on the mat, directly under their shoulders (BPS §6/§8/§11),
            // and realize the declared target (no solver relocation).
            for ((hand, shoulder) in listOf(Joint.HAND_A to Joint.SHOULDER_A, Joint.HAND_P to Joint.SHOULDER_P)) {
                val h = frame.getJoint(hand); val s = frame.getJoint(shoulder)
                if (abs(h.y - matLevel) > posBand || abs(h.x - s.x) > posBand || abs(h.z - s.z) > posBand) {
                    wrongHands.add(
                        "p=$p $hand=(${f(h.x)}, ${f(h.y)}, ${f(h.z)}) against its shoulder " +
                            "(${f(s.x)}, ${f(s.z)})"
                    )
                }
                val target = frame.limbTargets.firstOrNull { it.joint == hand }
                    ?: error("the published frame must carry the arm's declared target (the §1.1 carrier)")
                if (dist(h, target.world) > posBand) {
                    relocated.add("p=$p $hand relocated ${f(dist(h, target.world))}")
                }
            }
            // The leg chain's published geometry is the pose's own planted quadruped base and does not
            // react to the spinal wave: each leg joint's offset from its own hip is phase-invariant,
            // and it is the value the tree published before this correction (the leg authoring is the
            // pose's own and is deliberately untouched).
            for ((joint, hip, want) in LEG_OFFSETS) {
                val got = Vector3().set(frame.getJoint(joint)).subtract(frame.getJoint(hip))
                if (dist(got, want) > posBand) {
                    wrongLegs.add(
                        "p=$p $joint − $hip = (${f(got.x)}, ${f(got.y)}, ${f(got.z)}) against the " +
                            "authored (${f(want.x)}, ${f(want.y)}, ${f(want.z)})"
                    )
                }
            }
            // The engine's own reachability flag must stay silent (the declared limbs are reachable).
            if (frame.maxIkClampAmount > 0.05f) {
                wrongPlacement.add("p=$p maxIkClampAmount=${f(frame.maxIkClampAmount)}")
            }
        }
        assertTrue("the pelvis's authored placement must be untouched:\n" + wrongPlacement.take(8).joinToString("\n"), wrongPlacement.isEmpty())
        assertTrue("the hands must stay planted under their shoulders:\n" + wrongHands.take(8).joinToString("\n"), wrongHands.isEmpty())
        assertTrue("no solver relocation of the arms:\n" + relocated.take(8).joinToString("\n"), relocated.isEmpty())
        assertTrue(
            "the leg chain (the planted quadruped base) must not move with the spinal wave:\n" +
                wrongLegs.take(8).joinToString("\n"),
            wrongLegs.isEmpty()
        )
    }

    /**
     * The leg chain's published offsets from their own hip — the pose's planted quadruped base, as the
     * pre-correction tree published it. The authored ankle target `(50, ankleHeight, ±hipWidth)` is
     * projected onto the leg chain's reachable annulus along its own ray (which is exactly the −Y
     * direction from the hip at every phase), so the whole chain rides the pelvis rigidly: the values
     * are constant across the sweep, and every one of them is unchanged by this correction — the
     * spinal wave moves the trunk, never the planted base (BPS §7/§8/§11: "knees under hips … the
     * thighs do not splay", "the four-point base remains in contact throughout"). Measured on the
     * batch's whole-corpus A/B (`68` classes × `5` samples × every joint XYZ, pristine `origin/main`
     * @ `9cf4c32` vs the corrected tree): the leg chain's rows differ by at most `3.052e-05` u — the
     * float re-association of the bake's parent frame, whose rotation the correction changes — which
     * is why the band here is the pose's positional tolerance rather than a bit-exact comparison.
     */
    private val LEG_OFFSETS: List<Triple<Joint, Joint, Vector3>> by lazy {
        val knee = Vector3(-69.2675f, -54.2957f, -69.2675f)
        val ankle = Vector3(0f, -57.1292f, 0f)
        val heel = Vector3(-7.1801f, -57.4224f, 7.1681f)
        val toe = Vector3(17.5789f, -56.4113f, -17.5496f)
        fun mirror(v: Vector3) = Vector3(v.x, v.y, -v.z)
        listOf(
            Triple(Joint.KNEE_F, Joint.HIP_F, knee),
            Triple(Joint.ANKLE_F, Joint.HIP_F, ankle),
            Triple(Joint.HEEL_F, Joint.HIP_F, heel),
            Triple(Joint.TOE_F, Joint.HIP_F, toe),
            Triple(Joint.KNEE_B, Joint.HIP_B, mirror(knee)),
            Triple(Joint.ANKLE_B, Joint.HIP_B, mirror(ankle)),
            Triple(Joint.HEEL_B, Joint.HIP_B, mirror(heel)),
            Triple(Joint.TOE_B, Joint.HIP_B, mirror(toe))
        )
    }

    @Test
    fun theAuthoredCyclePhaseTimingAndGazeArePreserved() {
        val frames = playingFrames()

        // A LOOP with the pose's own 0 (= Cat) → 1 (= Cow) phase order: the wave is linear in the
        // progress the engine supplies, exactly like the motion it replaces.
        assertTrue(
            "the rep must START at the Cat extreme — the authored chord flexion "
                + "torsoLength·sin($catFlexion) at p = 0 (measured ${f(publishedFlexion(frames.first().second))})",
            abs(publishedFlexion(frames.first().second) - catFlexion) < angleBand
        )
        assertTrue(
            "the rep must end at the Cow extreme (measured ${deg(publishedFlexion(frames.last().second))}°)",
            publishedFlexion(frames.last().second) < -cowExtension * 0.9f
        )

        // The gaze intent is the pose's authored one (the legacy headPitch sweep, ±0.5 rad) and the
        // head chain follows the spine: the realized neck/head direction is the authored gaze rotated
        // by the chest frame the resolver applies it in.
        val wrongGaze = mutableListOf<String>()
        for ((p, frame) in frames) {
            val headPitch = -0.5f + (0.5f - (-0.5f)) * p
            val gaze = Vector3(-cos(headPitch), sin(headPitch), 0f).normalize()
            val chestRot = frame.getJointRotation(Joint.CHEST)
            val realized = SkeletonMath.rotAround(gaze, chestRot.axis, chestRot.angle, Vector3())
            val actual = Vector3().set(frame.getJoint(Joint.NECK_END)).subtract(frame.getJoint(Joint.CHEST))
            if (actual.mag() > 1e-4f) actual.normalize()
            if (dist(actual, realized) > 1e-3f) {
                wrongGaze.add(
                    "p=$p realized neck direction (${f(actual.x)}, ${f(actual.y)}, ${f(actual.z)}) " +
                        "vs the authored gaze rotated into the chest frame " +
                        "(${f(realized.x)}, ${f(realized.y)}, ${f(realized.z)})"
                )
            }
        }
        assertTrue(
            "the head/neck chain must follow the spine through the chest frame (the authored gaze " +
                "intent is preserved, BPS §4/§11 \"Neck follows the spine\"):\n" + wrongGaze.take(4).joinToString("\n"),
            wrongGaze.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 6. Frame-condition invariance (the B-8 cold-frame defect class)
    // ------------------------------------------------------------------------------------------

    @Test
    fun theWaveIsFrameConditionInvariant() {
        val frameBand = 0.1f
        val cold = sweep.map { coldFrame(it) }
        val playing = playingFrames().map { it.second }
        val drifting = mutableListOf<String>()
        for (i in sweep.indices) {
            for (joint in listOf(Joint.PELVIS, Joint.LUMBAR, Joint.CHEST, Joint.NECK_END, Joint.HEAD_POS,
                Joint.SHOULDER_A, Joint.SHOULDER_P, Joint.ELBOW_A, Joint.ELBOW_P, Joint.HAND_A, Joint.HAND_P)) {
                val d = dist(cold[i].getJoint(joint), playing[i].getJoint(joint))
                if (d > frameBand) drifting.add("p=${sweep[i]} $joint cold-vs-playing ${f(d)}")
            }
            val dAngle = abs(publishedFlexion(cold[i]) - publishedFlexion(playing[i]))
            if (dAngle > frameBand) drifting.add("p=${sweep[i]} chord flexion cold-vs-playing ${f(dAngle)}")
        }
        assertTrue(
            "the wave's geometry must not be frame-condition sensitive (the B-8 cold-frame class):\n" +
                drifting.take(8).joinToString("\n"),
            drifting.isEmpty()
        )
    }
}
