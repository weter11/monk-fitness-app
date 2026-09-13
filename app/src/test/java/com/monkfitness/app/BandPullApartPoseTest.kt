package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.BandPullApartPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Band Pull-Aparts (`band_pull_aparts_standard`) — the exercise's own biomechanics on the PUBLISHED
 * frame.
 *
 * Stated intent (the only in-repo specification for this exercise; no BPS exists for it):
 * `ex_band_pull_aparts_desc` "reinforces scapular retraction and shoulder alignment";
 * `steps` §1 "Hold a light resistance band at shoulder height with straight arms", §2 "Pull the band
 * apart by moving the hands out to the sides", §3 "Finish when the band reaches the chest and the
 * shoulder blades squeeze together", §4 "Return to the start under control and repeat";
 * `tech` "Keep the ribs stacked over the pelvis and avoid arching the back… Think about moving from
 * the upper back, not the wrists"; `mistakes` "Bent wrists, shrugged shoulders, and letting the lower
 * back overextend to finish the rep".
 */
class BandPullApartPoseTest {

    private val ID = "band_pull_aparts_standard"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = BandPullApartPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private val maxReach = SkeletonMath.maxReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)

    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(BandPullApartPose::class.java, cfg!!.builder.javaClass)
        assertTrue(ID in PoseRegistry.getDedicatedAnimationIds())
    }

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        assertEquals(f.size, PoseFrameSweep.distinctFrameCount(f))
    }

    /** `steps` §2: "Pull the band apart by moving the hands out to the sides" — the hands travel APART. */
    @Test
    fun theHandsTravelApartThroughTheRep() {
        val f = frames()
        for ((hand, shoulder) in listOf(Joint.HAND_A to Joint.SHOULDER_A, Joint.HAND_P to Joint.SHOULDER_P)) {
            val spread = f.map { abs(it[hand].z - it[shoulder].z) }
            assertTrue(
                "$hand starts %.2f u outside its shoulder".format(spread.first()),
                spread.first() >= 20f
            )
            assertTrue(
                "$hand must end far outside its shoulder (measured %.2f u)".format(spread.last()),
                spread.last() >= 120f
            )
            assertTrue(
                "the lateral spread must grow monotonically (%.2f → %.2f)".format(spread.first(), spread.last()),
                spread.last() - spread.first() >= 60f &&
                    (1 until spread.size).all { spread[it] >= spread[it - 1] - 0.01f }
            )
        }
        // …and the hands come BACK toward the body's own plane as they widen: the band's line ends at
        // the chest, which is what `steps` §3's "the band reaches the chest" states.
        val f1 = f.last()
        assertTrue(
            "at the end of the sweep the hands stand only %.2f u in front of the shoulder line".format(f1[Joint.HAND_A].x - f1[Joint.SHOULDER_A].x),
            f1[Joint.HAND_A].x - f1[Joint.SHOULDER_A].x <= 50f
        )
        assertTrue(
            "at the start of the sweep the hands are held %.2f u out in front".format(f.first()[Joint.HAND_A].x - f.first()[Joint.SHOULDER_A].x),
            f.first()[Joint.HAND_A].x - f.first()[Joint.SHOULDER_A].x >= 120f
        )
    }

    /** `steps` §1: "at shoulder height with straight arms" — the arms hold the chain's full extension. */
    @Test
    fun theArmsStayStraightAtShoulderHeight() {
        for (frame in frames()) {
            for ((hand, shoulder) in listOf(Joint.HAND_A to Joint.SHOULDER_A, Joint.HAND_P to Joint.SHOULDER_P)) {
                val reach = Vector3().set(frame[hand]).subtract(frame[shoulder]).mag()
                assertTrue(
                    "the arm is at %.2f of the chain's %.2f extension at p=%.3f".format(reach / maxReach, maxReach, frame.progress),
                    reach >= BandPullApartPose.ARM_RADIUS - 0.05f
                )
                assertTrue(
                    "the elbow is at %.1f deg at p=%.3f — the arms must stay straight".format(
                        PoseFrameSweep.interiorAngle(frame, shoulder, if (hand == Joint.HAND_A) Joint.ELBOW_A else Joint.ELBOW_P, hand),
                        frame.progress
                    ),
                    PoseFrameSweep.interiorAngle(frame, shoulder, if (hand == Joint.HAND_A) Joint.ELBOW_A else Joint.ELBOW_P, hand) >= 138f
                )
                // "at shoulder height": the hands stay on the shoulder's own horizontal line, so a
                // shrug (or a drifting hand) cannot hide in the sweep.
                assertEquals(
                    "$hand is %.2f u off the shoulder's height at p=%.3f".format(frame[hand].y - frame[shoulder].y, frame.progress),
                    0f, frame[hand].y - frame[shoulder].y, 0.5f
                )
            }
        }
    }

    /** `desc`/§3: the shoulder blades squeeze together — and `mistakes`: the shoulders never shrug. */
    @Test
    fun theShoulderBladesRetractAndTheShouldersNeverShrug() {
        val f = frames()
        val startOffset = f.first()[Joint.SHOULDER_A].x - f.first()[Joint.CHEST].x
        val endOffset = f.last()[Joint.SHOULDER_A].x - f.last()[Joint.CHEST].x
        assertTrue(
            "the shoulder must retract posteriorly off the chest (measured %.2f → %.2f u)".format(startOffset, endOffset),
            startOffset >= -0.01f && endOffset <= -5f
        )
        val startY = f.first()[Joint.SHOULDER_A].y
        for (frame in f) {
            assertTrue(
                "the shoulder rose above its start at p=%.3f (%.2f > %.2f) — a shrug".format(frame.progress, frame[Joint.SHOULDER_A].y, startY),
                frame[Joint.SHOULDER_A].y <= startY + 0.01f
            )
        }
    }

    /** `tech`: "Keep the ribs stacked over the pelvis and avoid arching the back". */
    @Test
    fun theRibsStayStackedOverThePelvis() {
        for (frame in frames()) {
            assertEquals(
                "the chest drifted %.2f u fore/aft of the pelvis at p=%.3f".format(frame[Joint.CHEST].x - frame[Joint.PELVIS].x, frame.progress),
                0f, frame[Joint.CHEST].x - frame[Joint.PELVIS].x, 2f
            )
            assertTrue(
                "the trunk is not standing over the pelvis at p=%.3f".format(frame.progress),
                frame[Joint.CHEST].y - frame[Joint.PELVIS].y >= def.torsoLength * 0.95f
            )
            // No arch: the two spine segments are colinear with the trunk.
            val trunk = PoseFrameSweep.interiorAngle(frame, Joint.PELVIS, Joint.CHEST, Joint.NECK_END)
            assertTrue(
                "the trunk carries a %.1f deg hinge at p=%.3f".format(180f - trunk, frame.progress),
                trunk >= 170f
            )
        }
    }

    /** `mistakes`: "Bent wrists" cannot exist — the pose authors no wrist articulation at all. */
    @Test
    fun theWristsAreLeftToTheEngineSoTheyCannotBeBent() {
        for (frame in frames()) {
            for ((hand, palm) in listOf(Joint.HAND_A to Joint.PALM_A, Joint.HAND_P to Joint.PALM_P)) {
                val palmDir = Vector3().set(frame[palm]).subtract(frame[hand])
                val forearm = Vector3().set(frame[hand]).subtract(frame[if (hand == Joint.HAND_A) Joint.ELBOW_A else Joint.ELBOW_P])
                val cos = palmDir.dot(forearm) / (palmDir.mag() * forearm.mag())
                assertTrue(
                    "the hand is bent %.1f deg off the forearm at p=%.3f".format(Math.toDegrees(kotlin.math.acos(cos.coerceIn(-1f, 1f).toDouble())).toFloat(), frame.progress),
                    cos >= 0.999f
                )
            }
        }
    }

    @Test
    fun theFeetStayPlanted() {
        val f = frames()
        for (joint in listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)) {
            val worst = f.indices.maxOf { PoseFrameSweep.deviation(f, joint, 0, it) }
            assertTrue("$joint slides %.3f u during the drill".format(worst), worst <= 0.5f)
        }
        assertEquals(
            "the drill is performed on the feet (the ONE canonical declaration channel)",
            setOf(SupportPoint.LEFT_FOOT, SupportPoint.RIGHT_FOOT),
            pose().metadata.support.contacts.map { it.point }.toSet()
        )
    }

    /**
     * The standing family's own reachability reading: the M8-corrected standing poses author their
     * limbs at the chain roots they own, and the leg chain sits exactly at its extension cap (measured
     * `0.047` for this family). The validator's own flag threshold is `0.1`.
     */
    @Test
    fun theReachabilityStampStaysBelowTheValidatorsFlag() {
        for (frame in frames()) {
            assertTrue(
                "a limb was relocated at p=%.3f (clamp %.4f)".format(frame.progress, frame.maxIkClampAmount),
                frame.maxIkClampAmount <= 0.1f
            )
        }
    }

    @Test
    fun nothingGoesBelowTheFloor() {
        val (joint, clearance) = PoseFrameSweep.worstClearance(frames(), def, level = 0f)
        assertTrue("$joint is %.3f u below the floor".format(clearance), clearance >= -0.05f)
    }

    /** The band itself is not modeled (no band primitive exists in the rig) — recorded, not invented. */
    @Test
    fun theEnvironmentIsTheGroundOnlyAsInTheFamilysOtherBandedPose() {
        val env = pose().metadata.environment
        assertTrue(
            "the pull-apart models no prop, like the family's own banded face pull (the rig has no band primitive)",
            env.props.isEmpty() && env.anchors.isEmpty()
        )
        assertEquals(0f, env.ground.level, 0f)
    }
}
