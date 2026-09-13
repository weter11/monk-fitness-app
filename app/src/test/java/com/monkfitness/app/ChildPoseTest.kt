package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.ChildPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Child's Pose (`child_pose_hold`) — the exercise's own biomechanics on the PUBLISHED frame.
 *
 * ## Stated intent (its own copy — the only specification in the repository; there is no BPS)
 *
 * `desc` *"A restorative stretch that gently lengthens the back and helps relax the hips and
 * shoulders."* — `steps` *"1. Kneel on the floor and sit the hips back toward the heels. 2. Fold the
 * torso forward and reach the arms long in front. 3. Let the forehead rest down and breathe into the
 * ribcage. 4. Hold the stretch, then slowly rise back up."* — `tech` *"Relax the shoulders and keep the
 * breath slow. Widen the knees if you need more space for the torso."* — `mistakes` *"Holding tension in
 * the neck, bouncing into the stretch, and forcing the hips to the heels when mobility is limited."*
 *
 * The assertions below walk the copy in its own order and measure each clause on the published frame:
 * the **kneel is pinned** (the knee and the ankle do not move while the hips travel on the circle of
 * radius `thighLength` about that knee — "sit the hips back toward the heels" is the femur rotating on
 * a planted knee), the **shins lie flat**, the **torso folds** `109°` at the root plus `26°` at the
 * thorax, the **arms reach long in front** onto the mat, the **forehead rests at the corpus's own mat
 * layer**, the **hold is flat**, the **rise closes the cycle**, nothing **bounces**, and the sit-back
 * **stops at the leg chain's own fold bound** (`mistakes`' "forcing the hips to the heels").
 *
 * Every reading is taken through [PoseFrameSweep] (the pipeline entered exactly as `SkeletonRenderer`
 * enters it), never on a raw `build()` result.
 */
class ChildPoseTest {

    private val ID = "child_pose_hold"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = ChildPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.4f", v)

    private fun frameAt(f: List<PoseFrameSweep.Frame>, progress: Float): PoseFrameSweep.Frame =
        f.first { abs(it.progress - progress) < 1e-6f }

    private fun dir(fr: PoseFrameSweep.Frame, a: Joint, b: Joint): Vector3 {
        val v = Vector3().set(fr[b]).subtract(fr[a])
        val m = v.mag()
        return if (m < 1e-5f) Vector3(0f, 0f, 0f) else Vector3(v.x / m, v.y / m, v.z / m)
    }

    /** The hold: the samples inside the fold's plateau. */
    private fun plateau(f: List<PoseFrameSweep.Frame>) =
        f.filter { it.progress in ChildPose.ENTRY_END..ChildPose.EXIT_START }

    /** The coverage metric itself: this exercise must resolve to a REAL skeletal animation. */
    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(ChildPose::class.java, cfg!!.builder.javaClass)
        assertTrue("$ID is not a deduplicated dedicated animation", ID in PoseRegistry.getDedicatedAnimationIds())
    }

    @Test
    fun theSweepSamplesTheCyclesOwnDistinctFrames() {
        // The hold's plateau collapses the middle samples (the drill is a HOLD: from ENTRY_END to
        // EXIT_START every phase publishes the same frame), so the cycle's own distinct count is 5 of
        // the 9 samples — the seam (0 ≡ 1), the two entry samples, the plateau and the one exit sample.
        assertEquals(
            "the published frames of the cycle changed — re-measure the rhythm",
            5, PoseFrameSweep.distinctFrameCount(frames())
        )
    }

    /**
     * `steps` §1 *"Kneel on the floor"* — and the kneel is the drill's *axis*, not a starting
     * position: the knees and the ankles are pinned on the mat for the whole cycle while the hips
     * travel on the circle of radius `thighLength` about the pinned knee.
     */
    @Test
    fun theKneelIsPinnedForTheWholeCycle() {
        val f = frames()
        for (joint in listOf(Joint.KNEE_F, Joint.KNEE_B, Joint.ANKLE_F, Joint.ANKLE_B, Joint.TOE_F, Joint.TOE_B)) {
            assertEquals(
                "$joint travels ${fmt(PoseFrameSweep.travel3D(f, joint))} u — the kneel's floor contacts must not " +
                    "move while the hips sit back",
                0f, PoseFrameSweep.travel3D(f, joint), 0.05f
            )
        }
        for (fr in f) {
            for ((knee, z) in listOf(Joint.KNEE_F to -ChildPose.kneeZ(def), Joint.KNEE_B to ChildPose.kneeZ(def))) {
                assertEquals(
                    "$knee is at y = ${fmt(fr[knee].y)} at p=${fmt(fr.progress)} — the knees rest on the mat's " +
                        "own knee layer (${fmt(ChildPose.KNEE_Y)} u)",
                    0f, fr[knee].y - ChildPose.KNEE_Y, 0.05f
                )
                assertEquals("$knee is at z = ${fmt(fr[knee].z)} (the kneel's own width)", z, fr[knee].z, 0.05f)
                assertEquals(
                    "$knee is at x = ${fmt(fr[knee].x)} — the kneel's sagittal placement must be pinned",
                    ChildPose.kneeX(def), fr[knee].x, 0.05f
                )
            }
            // ... and the hip rides the circle of exactly one thigh length about that knee.
            for ((hip, knee) in listOf(Joint.HIP_F to Joint.KNEE_F, Joint.HIP_B to Joint.KNEE_B)) {
                assertEquals(
                    "$hip is ${fmt(Vector3().set(fr[hip]).subtract(fr[knee]).mag())} u from its pinned knee at " +
                        "p=${fmt(fr.progress)} — it must stay on the femur's own circle",
                    def.thighLength, Vector3().set(fr[hip]).subtract(fr[knee]).mag(), 0.05f
                )
            }
        }
    }

    /** `steps` §1: the shins lie **flat** on the mat — and along the mid-line, not splayed. */
    @Test
    fun theShinsLieFlatOnTheMatForTheWholeCycle() {
        for (fr in frames()) {
            for ((knee, ankle) in listOf(Joint.KNEE_F to Joint.ANKLE_F, Joint.KNEE_B to Joint.ANKLE_B)) {
                val d = dir(fr, knee, ankle)
                assertEquals(
                    "$knee→$ankle is ${fmt((Vector3().set(fr[ankle]).subtract(fr[knee]).mag()))} u (the shin bone)",
                    def.shinLength, Vector3().set(fr[ankle]).subtract(fr[knee]).mag(), 0.05f
                )
                assertTrue(
                    "the shin leans ${fmt(Math.toDegrees(acos(d.y.coerceIn(-1f, 1f).toDouble())).toFloat() - 90f)}° " +
                        "off the mat at p=${fmt(fr.progress)} — a kneel's shins lie flat",
                    abs(d.y) < 0.01f
                )
                assertTrue(
                    "the shin points ${fmt(d.z)} laterally at p=${fmt(fr.progress)} — the shins lie along the " +
                        "mid-line with the knees under their own hips",
                    abs(d.z) < 0.01f && d.x < -0.99f
                )
            }
        }
    }

    /**
     * `steps` §1 *"sit the hips back toward the heels"*: measured, the hip travels **`92.0 u` back and
     * `47.8 u` down** between the tall kneel (femur vertical) and the hold (femur `55°`), and the entry
     * is monotone — nothing bounces on the way in (`mistakes`).
     */
    @Test
    fun theHipsSitBackOverTheHeelsAndNothingBounces() {
        val f = frames()
        val kneel = frameAt(f, 0f)
        val hold = frameAt(f, 0.5f)
        assertEquals(
            "the tall kneel's hip is not above the pinned knee (x ${fmt(kneel[Joint.PELVIS].x)})",
            0f, kneel[Joint.PELVIS].x - ChildPose.kneelHipX(def), 0.05f
        )
        assertEquals("the tall kneel's hip height moved", ChildPose.kneelHipY(def), kneel[Joint.PELVIS].y, 0.05f)
        assertEquals("the hold's hip X moved", ChildPose.foldHipX(def), hold[Joint.PELVIS].x, 0.05f)
        assertEquals("the hold's hip Y moved", ChildPose.foldHipY(def), hold[Joint.PELVIS].y, 0.05f)
        val back = kneel[Joint.PELVIS].x - hold[Joint.PELVIS].x
        val down = kneel[Joint.PELVIS].y - hold[Joint.PELVIS].y
        assertEquals("the hips travel ${fmt(back)} u back", 92.0f, back, 1f)
        assertEquals("the hips travel ${fmt(down)} u down", 47.8f, down, 1f)
        assertTrue("the hips do not travel back at all", back > 50f)
        // Monotone on the way in and on the way out: the hip's X never turns around inside a ramp.
        val entry = f.filter { it.progress <= ChildPose.ENTRY_END }
        for (i in 1 until entry.size) {
            assertTrue(
                "the hips come forward again inside the entry (p=${fmt(entry[i].progress)}) — `mistakes` is " +
                    "`bouncing into the stretch`",
                entry[i][Joint.PELVIS].x <= entry[i - 1][Joint.PELVIS].x + 1e-3f
            )
        }
        val exit = f.filter { it.progress >= ChildPose.EXIT_START }
        for (i in 1 until exit.size) {
            assertTrue(
                "the hips go back again inside the rise (p=${fmt(exit[i].progress)}) — `mistakes` is `bouncing`",
                exit[i][Joint.PELVIS].x >= exit[i - 1][Joint.PELVIS].x - 1e-3f
            )
        }
    }

    /**
     * `steps` §2 *"Fold the torso forward"*: the two-segment spine carries the trunk `109°` at the root
     * and rounds a further `26°` at the thorax, so the chest publishes **below and in front of** the
     * pelvis — the fold is real, and the shoulders ride it.
     */
    @Test
    fun theTorsoFoldsForwardAndDown() {
        val hold = frameAt(frames(), 0.5f)
        assertEquals(
            "the trunk's own fold at the hold is ${fmt(trunkTilt(hold))}° off vertical",
            Math.toDegrees(ChildPose.TRUNK_FOLD.toDouble()).toFloat(), trunkTilt(hold), 0.5f
        )
        assertEquals("the hold's chest X moved", ChildPose.foldChestX(def), hold[Joint.CHEST].x, 0.05f)
        assertEquals("the hold's chest Y moved", ChildPose.foldChestY(def), hold[Joint.CHEST].y, 0.05f)
        assertTrue(
            "the chest is at y = ${fmt(hold[Joint.CHEST].y)} against the pelvis at ${fmt(hold[Joint.PELVIS].y)} — " +
                "the torso has not folded DOWN",
            hold[Joint.CHEST].y < hold[Joint.PELVIS].y - 20f
        )
        assertTrue(
            "the chest is at x = ${fmt(hold[Joint.CHEST].x)} against the pelvis at ${fmt(hold[Joint.PELVIS].x)} — " +
                "the torso has not folded FORWARD",
            hold[Joint.CHEST].x > hold[Joint.PELVIS].x + 50f
        )
        // The thorax rounds the trunk further: the head's chain leaves the trunk's own direction.
        val trunkDir = dir(hold, Joint.PELVIS, Joint.CHEST)
        val headDir = dir(hold, Joint.NECK_END, Joint.HEAD_POS)
        val between = Math.toDegrees(acos(
            (trunkDir.x * headDir.x + trunkDir.y * headDir.y + trunkDir.z * headDir.z).coerceIn(-1f, 1f).toDouble()
        )).toFloat()
        assertEquals(
            "the head's chain leaves the trunk's direction by ${fmt(between)}° — the thorax's own rounding " +
                "(${fmt(Math.toDegrees(ChildPose.THORACIC_FOLD.toDouble()).toFloat())}°) plus the cervical tuck",
            Math.toDegrees((ChildPose.THORACIC_FOLD + ChildPose.CERVICAL_TUCK).toDouble()).toFloat(), between, 0.5f
        )
    }

    /** `steps` §2 *"reach the arms long in front"*: both hands are on the mat, ahead of the head. */
    @Test
    fun theArmsReachLongInFrontOntoTheMat() {
        val hold = frameAt(frames(), 0.5f)
        for ((shoulder, hand, elbow) in listOf(
            Triple(Joint.SHOULDER_A, Joint.HAND_A, Joint.ELBOW_A),
            Triple(Joint.SHOULDER_P, Joint.HAND_P, Joint.ELBOW_P)
        )) {
            val reach = Vector3().set(hold[hand]).subtract(hold[shoulder]).mag()
            assertEquals(
                "the $hand is ${fmt(reach)} u from its shoulder at the hold against the authored " +
                    "${fmt(ChildPose.foldHandReach(def))} u",
                0f, reach - ChildPose.foldHandReach(def), 0.05f
            )
            assertEquals(
                "$hand is at y = ${fmt(hold[hand].y)} — the palms rest on the mat's own layer",
                0f, hold[hand].y - ChildPose.HAND_Y, 0.05f
            )
            assertTrue(
                "$hand is at x = ${fmt(hold[hand].x)} against the head at ${fmt(hold[Joint.HEAD_POS].x)} — the " +
                    "copy asks for the arms LONG IN FRONT",
                hold[hand].x > hold[Joint.HEAD_POS].x + 50f
            )
            val interior = PoseFrameSweep.interiorAngle(hold, shoulder, elbow, hand)
            assertEquals(
                "the arm's elbow reads ${fmt(interior)}° at the hold — the authored reach's own angle",
                0f, interior - ChildPose.foldElbowInteriorDeg(def), 0.5f
            )
            assertTrue("the arms are bent to ${fmt(interior)}° — not a long reach", interior > 130f)
        }
        // The hands are inside the arm chain's own band at the hold.
        val reach = ChildPose.foldHandReach(def)
        assertTrue(
            "the authored reach ${fmt(reach)} u is not inside the arm chain's band",
            reach <= SkeletonMath.maxReach(def.upperArmLength, def.forearmLength, def.armIKConstraint) &&
                reach >= SkeletonMath.minReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
        )
    }

    /**
     * `steps` §3 *"Let the forehead rest down"*: the cervical chain carries a further tuck and the
     * head's tip publishes at the corpus's own mat layer — measured `8.999 u` (the prone family's mat
     * layer is `10 u`), and never through it.
     */
    @Test
    fun theHeadRestsAtTheMatLayer() {
        val f = frames()
        val hold = frameAt(f, 0.5f)
        assertEquals(
            "the head's tip is at y = ${fmt(hold[Joint.HEAD_POS].y)} at the hold against the authored " +
                "${fmt(ChildPose.headTipY(def))} u",
            0f, hold[Joint.HEAD_POS].y - ChildPose.headTipY(def), 0.05f
        )
        assertTrue(
            "the head's tip is ${fmt(hold[Joint.HEAD_POS].y)} u above the mat — `steps` asks for the forehead to " +
                "rest DOWN",
            hold[Joint.HEAD_POS].y < 15f
        )
        for (fr in f) {
            assertTrue(
                "the head is ${fmt(fr[Joint.HEAD_POS].y)} u through the mat at p=${fmt(fr.progress)}",
                fr[Joint.HEAD_POS].y > 0.5f
            )
        }
        // The neck's own bone is conserved (a rigid cervical chain, not a stretch).
        for (fr in f) {
            assertEquals(
                "the neck reads ${fmt(Vector3().set(fr[Joint.NECK_END]).subtract(fr[Joint.CHEST]).mag())} u",
                def.neckLength, Vector3().set(fr[Joint.NECK_END]).subtract(fr[Joint.CHEST]).mag(), 0.05f
            )
        }
    }

    /** `steps` §4 *"Hold the stretch"*: the plateau is measurably flat and holds for 45 % of the cycle. */
    @Test
    fun theHoldPlateauIsFlat() {
        val f = frames()
        val hold = plateau(f)
        assertTrue("the sweep must sample the hold plateau (got ${hold.size})", hold.size >= 3)
        for (joint in Joint.entries) {
            val worst = hold.indices.maxOf { PoseFrameSweep.deviation(hold, joint, 0, it) }
            assertEquals(
                "$joint drifts ${fmt(worst)} u inside the hold plateau — the copy asks for a HOLD",
                0f, worst, 0.01f
            )
        }
        assertTrue(
            "the plateau covers only ${fmt((ChildPose.EXIT_START - ChildPose.ENTRY_END) * 100f)} % of the cycle",
            ChildPose.EXIT_START - ChildPose.ENTRY_END >= 0.4f
        )
    }

    /** `steps` §4 *"then slowly rise back up"*: the cycle closes and the rise is the entry mirrored. */
    @Test
    fun theCycleClosesAndRisesBackUp() {
        val f = frames()
        val first = f.first()
        val last = f.last()
        for (joint in Joint.entries) {
            assertEquals(
                "the cycle does not close: $joint ends ${fmt(PoseFrameSweep.deviation(f, joint, 0, f.size - 1))} u " +
                    "from where it started",
                0f, PoseFrameSweep.deviation(f, joint, 0, f.size - 1), 1e-3f
            )
        }
        assertEquals("the fold fraction does not return to the kneel", 0f, ChildPose.foldFraction(1f), 1e-4f)
        assertEquals("the fold fraction does not start at the kneel", 0f, ChildPose.foldFraction(0f), 1e-4f)
        assertEquals("the hold is not held", 1f, ChildPose.foldFraction(0.5f), 1e-4f)
        // The rise is the reverse motion: the last sample outside the plateau is back above the hold.
        val rising = frameAt(f, 0.875f)
        assertTrue(
            "the hips are at ${fmt(rising[Joint.PELVIS].x)} while rising against the hold's " +
                "${fmt(foldX())} — the rise must carry them back over the heels",
            rising[Joint.PELVIS].x > foldX() + 5f
        )
        assertTrue(
            "the rising trunk is still folded flat (${fmt(trunkTilt(rising))}°)",
            trunkTilt(rising) < Math.toDegrees(ChildPose.TRUNK_FOLD.toDouble()).toFloat() - 10f
        )
        assertTrue("the first and last samples are not the same frame", first.progress == 0f && last.progress == 1f)
    }

    private fun foldX(): Float = ChildPose.foldHipX(def)

    /** The trunk's tilt from vertical on a published frame (measured `PELVIS → CHEST`). */
    private fun trunkTilt(fr: PoseFrameSweep.Frame): Float {
        val v = Vector3().set(fr[Joint.CHEST]).subtract(fr[Joint.PELVIS])
        return Math.toDegrees(acos((v.y / v.mag()).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /**
     * `mistakes` *"forcing the hips to the heels when mobility is limited"* — honoured structurally:
     * the sit-back stops where the LEG CHAIN stops. At the authored `55°` the hip→ankle chord is
     * `64.5 u` of the chain's `56.01 u` fold stop (`8.5 u` of headroom); `58°` would sit exactly on it.
     */
    @Test
    fun theSitBackStopsAtTheLegChainsOwnFoldBound() {
        val f = frames()
        val minReach = SkeletonMath.minReach(def.thighLength, def.shinLength, def.legIKConstraint)
        var shortest = Float.MAX_VALUE
        for (fr in f) {
            shortest = minOf(shortest, Vector3().set(fr[Joint.ANKLE_F]).subtract(fr[Joint.HIP_F]).mag())
        }
        assertTrue(
            "the deepest sit-back asks for a ${fmt(shortest)} u leg chord against the chain's ${fmt(minReach)} u " +
                "fold stop — the pose would be forcing the hips past the body's own fold",
            shortest >= minReach
        )
        assertTrue(
            "the sit-back leaves only ${fmt(shortest - minReach)} u of the chain's fold stop",
            shortest - minReach > 1f
        )
        assertTrue(
            "the femur's authored angle ${fmt(Math.toDegrees(ChildPose.FOLD_ALPHA.toDouble()).toFloat())}° is past " +
                "the chain's fold bound",
            Math.toDegrees(ChildPose.FOLD_ALPHA.toDouble()).toFloat() < 58f
        )
    }

    /** The reach contract: every authored target is inside its own chain's band at every phase. */
    @Test
    fun everyAuthoredTargetIsInsideItsChainsReach() {
        for (fr in frames()) {
            assertEquals(
                "the solver relocated a limb at p=${fmt(fr.progress)} (clamp ${fmt(fr.maxIkClampAmount)})",
                0f, fr.maxIkClampAmount, 1e-4f
            )
        }
    }

    /** The body never goes through the mat, and every floor contact rests AT its layer. */
    @Test
    fun theBodyNeverGoesThroughTheMat() {
        val f = frames()
        val (joint, clearance) = PoseFrameSweep.worstClearance(f, def, level = 0f)
        assertTrue("$joint is ${fmt(clearance)} u below the mat", clearance >= -0.05f)
        val hold = frameAt(f, 0.5f)
        for (joint in listOf(Joint.KNEE_F, Joint.KNEE_B, Joint.ANKLE_F, Joint.ANKLE_B, Joint.HAND_A, Joint.HAND_P)) {
            assertTrue(
                "$joint is at y = ${fmt(hold[joint].y)} at the hold — not within a layer's reach of the mat",
                hold[joint].y <= ChildPose.KNEE_Y + 0.5f
            )
        }
        // The head's tip is the lowest published joint at the hold (the fold's own signature).
        assertEquals("the lowest joint at the hold is not the head's tip", Joint.HEAD_POS, joint)
    }

    /** The feet lie along the shins (the kneel's own foot), flat on the mat and pointing back. */
    @Test
    fun theFeetLieAlongTheShinsOnTheMat() {
        for (fr in frames()) {
            for ((ankle, heel, toe) in listOf(
                Triple(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F),
                Triple(Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
            )) {
                assertEquals("$heel is at y = ${fmt(fr[heel].y)} at p=${fmt(fr.progress)}", 0f, fr[heel].y - ChildPose.KNEE_Y, 0.05f)
                assertEquals("$toe is at y = ${fmt(fr[toe].y)} at p=${fmt(fr.progress)}", 0f, fr[toe].y - ChildPose.KNEE_Y, 0.05f)
                assertTrue(
                    "$toe is ${fmt(fr[toe].x - fr[ankle].x)} u from its ankle — a kneel's foot points BACK along " +
                        "the shin it continues",
                    fr[toe].x < fr[ankle].x - 10f
                )
                assertTrue(
                    "$heel is ${fmt(fr[heel].x - fr[ankle].x)} u from its ankle — the heel is the forward end",
                    fr[heel].x > fr[ankle].x
                )
            }
        }
    }

    /** The declared base of support is the one the published frame carries (R8/B-5, the hero's model). */
    @Test
    fun theDeclaredSupportReachesThePublishedFrame() {
        val f = frames()
        val declared = pose().metadata.support.supportPoints
        assertEquals(
            "the declaration is not the kneeling four-point base (knees + hands) plus the feet the kneel rests on",
            setOf(
                SupportPoint.LEFT_KNEE, SupportPoint.RIGHT_KNEE,
                SupportPoint.LEFT_HAND, SupportPoint.RIGHT_HAND,
                SupportPoint.LEFT_FOOT, SupportPoint.RIGHT_FOOT
            ),
            declared
        )
        for (fr in f) {
            assertEquals(
                "the published frame at p=${fmt(fr.progress)} does not carry the declared support model",
                declared, fr.supportedPoints
            )
        }
        assertEquals(
            "the pivot is not the kneeling one",
            PivotType.KNEES, pose().metadata.support.pivot
        )
        // ... and the declared knees are really the knee joints the drill rests on.
        assertEquals(
            "LEFT_KNEE does not resolve to the knee joint",
            listOf(Joint.KNEE_F), SupportMath.jointsFor(SupportPoint.LEFT_KNEE)
        )
    }

    /** The chains keep their own bones (the anatomy the fold is authored against). */
    @Test
    fun theChainsKeepTheirBoneLengths() {
        for (fr in frames()) {
            assertEquals("the trunk reads ${fmt(Vector3().set(fr[Joint.CHEST]).subtract(fr[Joint.PELVIS]).mag())} u", def.torsoLength, Vector3().set(fr[Joint.CHEST]).subtract(fr[Joint.PELVIS]).mag(), 0.05f)
            for ((shoulder, elbow, hand) in listOf(
                Triple(Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A),
                Triple(Joint.SHOULDER_P, Joint.ELBOW_P, Joint.HAND_P)
            )) {
                assertEquals("the upper arm reads ${fmt(Vector3().set(fr[elbow]).subtract(fr[shoulder]).mag())} u", def.upperArmLength, Vector3().set(fr[elbow]).subtract(fr[shoulder]).mag(), 0.05f)
                assertEquals("the forearm reads ${fmt(Vector3().set(fr[hand]).subtract(fr[elbow]).mag())} u", def.forearmLength, Vector3().set(fr[hand]).subtract(fr[elbow]).mag(), 0.05f)
            }
            for ((hip, knee, ankle) in listOf(
                Triple(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F),
                Triple(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B)
            )) {
                assertEquals("the thigh reads ${fmt(Vector3().set(fr[knee]).subtract(fr[hip]).mag())} u", def.thighLength, Vector3().set(fr[knee]).subtract(fr[hip]).mag(), 0.05f)
                assertEquals("the shin reads ${fmt(Vector3().set(fr[ankle]).subtract(fr[knee]).mag())} u", def.shinLength, Vector3().set(fr[ankle]).subtract(fr[knee]).mag(), 0.05f)
            }
        }
    }
}
