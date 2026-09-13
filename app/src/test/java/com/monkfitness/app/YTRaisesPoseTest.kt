package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.poses.YTRaisesPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Y-T Raises (`yt_raises_standard`) — the exercise's own biomechanics on the PUBLISHED frame.
 *
 * Stated intent (the only in-repo specification for this exercise; no BPS exists for it):
 * `ex_y_t_raises_steps` §1 "Lie face down with the forehead lightly supported", §2 "Raise the arms
 * overhead into a Y shape", §3 "Lower with control, then move the arms out into a T shape",
 * §4 "Lift again while keeping the shoulders down", §5 "Alternate the two positions for the set";
 * `desc` "A shoulder-blade drill… Reach long and lift from the mid-back, not the neck";
 * `tech` "Keep the thumbs pointing up. Lift only as high as you can without shrugging.";
 * `mistakes` "Cranking the neck up. Bending the elbows too much. Swinging the arms instead of lifting
 * with control."
 */
class YTRaisesPoseTest {

    private val ID = "yt_raises_standard"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = YTRaisesPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    /** The two peaks of the cycle: the Y raise (first half) and the T raise (second half). */
    private val Y_PEAK = 0.25f
    private val T_PEAK = 0.75f

    private val maxReach = SkeletonMath.maxReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)

    private fun frameAt(p: Float) = PoseFrameSweep.sweep(pose(), floatArrayOf(p)).single()

    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(YTRaisesPose::class.java, cfg!!.builder.javaClass)
        assertTrue(ID in PoseRegistry.getDedicatedAnimationIds())
    }

    /**
     * The sweep samples the authored rhythm's own frames — measured, never assumed.
     *
     * The cycle is TWO raises with a controlled lowering between them
     * ([theArmsRiseTwicePerCycleAndReturnToTheMat]), so the lift is `sin(π·fraction)` inside each half:
     * the first half raises to the Y, the second to the T. The nine [PoseFrameSweep.DEFAULT_PROGRESS]
     * samples therefore resolve to FIVE distinct published frames **by construction** —
     *
     *  * the rest position: `p = 0`, `½`, `1` (the lift is zero at all three, and at zero lift the
     *    half's own target only weights a zero vector, so the arm target IS the rest direction);
     *  * each raise's mid-lift: `p = ⅛ ≡ ⅜` (Y) and `p = ⅝ ≡ ⅞` (T) — a raise and its controlled
     *    lowering pass through the same arm position, which is what a symmetric rep means;
     *  * each raise's peak: `p = ¼` (Y) and `p = ¾` (T).
     *
     * The symmetric rep is the drill the copy states ("Lower with control… Lift again"), so the
     * duplication is the authored motion, not a repeated sample: the three frames that must differ
     * from one another (rest, Y peak, T peak) are asserted apart below, and the hand travel is
     * asserted by [theHandsSweepTheFullArc].
     */
    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        assertEquals(
            "the sweep no longer resolves to the rhythm's own five frames",
            5, PoseFrameSweep.distinctFrameCount(f)
        )
        val rest = frameAt(0f)
        for ((tag, frame) in listOf("Y" to frameAt(Y_PEAK), "T" to frameAt(T_PEAK))) {
            val lifted = Vector3().set(frame[Joint.HAND_A]).subtract(rest[Joint.HAND_A]).mag()
            assertTrue(
                "the $tag raise leaves the hand at its rest position (measured %.2f u of travel)".format(lifted),
                lifted >= 10f
            )
        }
        val yt = Vector3().set(frameAt(Y_PEAK)[Joint.HAND_A]).subtract(frameAt(T_PEAK)[Joint.HAND_A]).mag()
        assertTrue(
            "the two raises must be different positions (measured %.2f u apart)".format(yt),
            yt >= 40f
        )
    }

    /** `steps` §1: a PRONE body on the mat, and `mistakes`' "cranking the neck up" must not happen. */
    @Test
    fun theBodyLiesProneAndTheNeckNeverCranksUp() {
        for (frame in frames()) {
            for (joint in listOf(Joint.PELVIS, Joint.CHEST, Joint.NECK_END, Joint.HEAD_POS)) {
                assertEquals(
                    "$joint left the mat's plane at p=%.3f".format(frame.progress),
                    YTRaisesPose.PRONE_MAT_Y, frame[joint].y, 0.5f
                )
            }
            // The head continues the body's line: it is never lifted above the neck (no cervical
            // extension, the mistake the copy names).
            assertTrue(
                "the head is %.2f u above the neck at p=%.3f".format(frame[Joint.HEAD_POS].y - frame[Joint.NECK_END].y, frame.progress),
                frame[Joint.HEAD_POS].y <= frame[Joint.NECK_END].y + 0.5f
            )
            assertTrue(
                "the head is ahead of the body's line at p=%.3f".format(frame.progress),
                frame[Joint.HEAD_POS].x > frame[Joint.NECK_END].x && frame[Joint.NECK_END].x > frame[Joint.CHEST].x
            )
        }
    }

    /** The legs lie straight on the mat (the authoring bypasses the leg chain's `0.98` cap). */
    @Test
    fun theLegsLieStraightAndFlatOnTheMat() {
        for (frame in frames()) {
            for ((hip, knee, ankle, heel, toe) in listOf(
                listOf(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F),
                listOf(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
            )) {
                val kneeAngle = PoseFrameSweep.interiorAngle(frame, hip, knee, ankle)
                assertTrue(
                    "the leg is bent to %.1f deg at p=%.3f — it lies straight on the mat".format(kneeAngle, frame.progress),
                    kneeAngle >= 175f
                )
                assertEquals("$ankle left the mat's plane", YTRaisesPose.PRONE_MAT_Y, frame[ankle].y, 0.5f)
                assertEquals("$heel left the mat's plane", YTRaisesPose.PRONE_MAT_Y, frame[heel].y, 0.5f)
                assertEquals("$toe left the mat's plane", YTRaisesPose.PRONE_MAT_Y, frame[toe].y, 0.5f)
                // The feet point away from the head (down the body), flat on the mat.
                val long = Vector3().set(frame[toe]).subtract(frame[heel])
                assertTrue("the foot's long axis is %.2f in X".format(long.x), long.x < -10f)
                assertTrue("the foot is %.2f u off the body's axis laterally".format(long.z), abs(long.z) <= 1f)
            }
        }
    }

    /**
     * `steps` §2/§4: the arms RISE twice per cycle and return to the mat between the raises — the
     * "lift with control" rhythm (`sin(π·fraction)` per half), and `desc`'s "lift from the mid-back"
     * leaves the hands clear of the mat at both peaks.
     */
    @Test
    fun theArmsRiseTwicePerCycleAndReturnToTheMat() {
        val rest = frameAt(0f)
        assertTrue(
            "the arms must start on the mat (hand %.2f u above the plane)".format(rest[Joint.HAND_A].y - YTRaisesPose.PRONE_MAT_Y),
            abs(rest[Joint.HAND_A].y - YTRaisesPose.PRONE_MAT_Y) <= 0.5f
        )
        for (peak in listOf(Y_PEAK, T_PEAK)) {
            val lifted = frameAt(peak)
            assertTrue(
                "the hands are only %.2f u above the mat at the raise (p=%.2f)".format(lifted[Joint.HAND_A].y - YTRaisesPose.PRONE_MAT_Y, peak),
                lifted[Joint.HAND_A].y - YTRaisesPose.PRONE_MAT_Y >= 10f
            )
            // …and they come back down between/after the raises.
            assertEquals(
                "the hands must return to the mat at the cycle's own seam (p=%.2f)".format(peak + 0.25f),
                YTRaisesPose.PRONE_MAT_Y, frameAt(peak + 0.25f)[Joint.HAND_A].y, 0.5f
            )
        }
    }

    /**
     * `steps` §5: "Alternate the two positions for the set" — the first raise is the Y (the hands
     * overhead, past the head's own X) and the second is the T (perpendicular to the body, out at the
     * widest lateral span). The two are told apart by the hands' measured positions, not by name.
     */
    @Test
    fun theFirstRaiseIsTheYAndTheSecondIsTheT() {
        val y = frameAt(Y_PEAK)
        val t = frameAt(T_PEAK)
        val headX = y[Joint.HEAD_POS].x
        assertTrue(
            "the Y raise must take the hands overhead (hand X %.2f vs head %.2f)".format(y[Joint.HAND_A].x, headX),
            y[Joint.HAND_A].x > headX + 40f
        )
        val ySpread = abs(y[Joint.HAND_A].z - y[Joint.SHOULDER_A].z)
        val tSpread = abs(t[Joint.HAND_A].z - t[Joint.SHOULDER_A].z)
        assertTrue(
            "the T raise must be the wider lateral span (Y %.2f vs T %.2f u)".format(ySpread, tSpread),
            tSpread >= ySpread + 40f
        )
        assertTrue(
            "the T raise must be perpendicular to the body (hand X %.2f vs shoulder X %.2f)".format(t[Joint.HAND_A].x, t[Joint.SHOULDER_A].x),
            abs(t[Joint.HAND_A].x - t[Joint.SHOULDER_A].x) <= 15f
        )
        // Both raises are the SAME lift off the mat (the copy raises to a height, not to an angle).
        assertEquals(
            "the two raises must lift the hands equally",
            y[Joint.HAND_A].y - YTRaisesPose.PRONE_MAT_Y, t[Joint.HAND_A].y - YTRaisesPose.PRONE_MAT_Y, 5f
        )
    }

    /** `desc`/`mistakes`: "Reach long… Bending the elbows too much" — the arms hold their extension. */
    @Test
    fun theArmsStayLongThroughTheDrill() {
        for (frame in frames()) {
            for ((hand, shoulder, elbow) in listOf(
                Triple(Joint.HAND_A, Joint.SHOULDER_A, Joint.ELBOW_A),
                Triple(Joint.HAND_P, Joint.SHOULDER_P, Joint.ELBOW_P)
            )) {
                val reach = Vector3().set(frame[hand]).subtract(frame[shoulder]).mag()
                assertEquals("the arm's arc radius moved at p=%.3f".format(frame.progress), YTRaisesPose.ARM_RADIUS, reach, 0.05f)
                assertTrue(
                    "the arm is at %.1f of the chain's extension at p=%.3f".format(100f * reach / maxReach, frame.progress),
                    reach >= 0.95f * maxReach
                )
                val angle = PoseFrameSweep.interiorAngle(frame, shoulder, elbow, hand)
                assertTrue(
                    "the elbow is at %.1f deg at p=%.3f".format(angle, frame.progress),
                    angle >= 138f
                )
            }
        }
    }

    /** The arms never cross the body's mid-line, and each hand owns its own side. */
    @Test
    fun eachHandStaysOnItsOwnSideOfTheBody() {
        for (frame in frames()) {
            assertTrue(
                "HAND_A (z=%.2f) crossed its shoulder's side (z=%.2f) at p=%.3f".format(frame[Joint.HAND_A].z, frame[Joint.SHOULDER_A].z, frame.progress),
                frame[Joint.HAND_A].z <= frame[Joint.SHOULDER_A].z + 0.01f
            )
            assertTrue(
                "HAND_P (z=%.2f) crossed its shoulder's side (z=%.2f) at p=%.3f".format(frame[Joint.HAND_P].z, frame[Joint.SHOULDER_P].z, frame.progress),
                frame[Joint.HAND_P].z >= frame[Joint.SHOULDER_P].z - 0.01f
            )
            assertEquals(
                "the two hands must mirror about the mid-line at p=%.3f".format(frame.progress),
                frame[Joint.HAND_A].z, -frame[Joint.HAND_P].z, 0.5f
            )
        }
    }

    /** The prone body's floor line: the feet and the pelvis region rest on the mat; the lifted hands do not. */
    @Test
    fun theDeclaredFloorLineIsTheFeetAndTheHipsNotTheLiftedHands() {
        val declared = pose().metadata.support.contacts.map { it.point }.toSet()
        assertEquals(
            "the prone body rests on its feet and its pelvis region",
            setOf(SupportPoint.LEFT_FOOT, SupportPoint.RIGHT_FOOT, SupportPoint.HIPS),
            declared
        )
        assertTrue(
            "the hands lift off the mat twice per cycle — they must not be declared as a static support",
            declared.none { it == SupportPoint.LEFT_HAND || it == SupportPoint.RIGHT_HAND }
        )
        assertEquals(PivotType.HIPS, pose().metadata.support.pivot)
        assertTrue(
            "every declared point must resolve through the one canonical map",
            declared.all { SupportMath.jointsFor(it).isNotEmpty() }
        )
        for (frame in frames()) {
            val published = frame.joints.keys
            assertTrue("the published frame carries no joints at all", published.isNotEmpty())
        }
        // the declared feet are the joints the derivation actually plants (they are level with their ankles)
        for (frame in frames()) {
            for ((ankle, heel, toe) in listOf(
                Triple(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F),
                Triple(Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
            )) {
                assertEquals("$heel is off $ankle's plane".format(), frame[ankle].y, frame[heel].y, 0.75f)
                assertEquals("$toe is off $ankle's plane".format(), frame[ankle].y, frame[toe].y, 0.75f)
            }
        }
    }

    @Test
    fun everyAuthoredTargetIsInsideItsChainsReach() {
        for (frame in frames()) {
            assertTrue(
                "the solver relocated a limb at p=%.3f (clamp %.4f)".format(frame.progress, frame.maxIkClampAmount),
                frame.maxIkClampAmount <= 1e-3f
            )
        }
    }

    @Test
    fun nothingGoesBelowTheFloor() {
        val (joint, clearance) = PoseFrameSweep.worstClearance(frames(), def, level = 0f)
        assertTrue("$joint is %.3f u below the floor".format(clearance), clearance >= -0.05f)
    }

    /** The sweep must actually sweep: the hands' travel is the drill's own evidence. */
    @Test
    fun theHandsSweepTheFullArc() {
        val hands = frames().map { it[Joint.HAND_A] }
        val xTravel = hands.maxOf { it.x } - hands.minOf { it.x }
        val zTravel = hands.maxOf { it.z } - hands.minOf { it.z }
        assertTrue("measured hand X travel %.2f u".format(xTravel), xTravel >= 150f)
        assertTrue("measured hand Z travel %.2f u".format(zTravel), zTravel >= 100f)
    }
}
