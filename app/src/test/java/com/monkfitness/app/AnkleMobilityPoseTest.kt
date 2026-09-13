package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.AnkleMobilityPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ankle Mobility (`ankle_mobility_standard`) — the knee-over-toe drill on the published frame.
 *
 * Stated intent (the only in-repo specification; no BPS exists for this exercise):
 * `ex_ankle_mobility_steps` "Face a wall in a split stance. Keep the front heel down and drive the
 * front knee toward the wall. Pause when you reach the end of your clean range. Ease back and repeat
 * smoothly. Switch sides after all reps."  `ex_ankle_mobility_tech` "Let the knee track over the
 * second toe. Start close enough to the wall to stay controlled. Use slow pulses, not bounces."
 * `ex_ankle_mobility_mistakes` "Heel lifting off the floor. Foot collapsing inward. Forcing range with
 * a hard bounce."
 *
 * The authored side is the F (foreground) leg forward, the B leg back — the unilateral drill's one
 * side, like the other unilateral registry poses (Cossack, Hip CARs, Hip Flexor stretch).
 */
class AnkleMobilityPoseTest {

    private val ID = "ankle_mobility_standard"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = AnkleMobilityPose()

    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(AnkleMobilityPose::class.java, cfg!!.builder.javaClass)
        assertTrue(ID in PoseRegistry.getDedicatedAnimationIds())
    }

    private fun frames() = PoseFrameSweep.sweep(pose())

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        assertEquals(f.size, PoseFrameSweep.distinctFrameCount(f))
    }

    /** "Face a wall in a split stance." */
    @Test
    fun theStanceIsASplitWithOneFootForward() {
        val f = frames()
        for (frame in f) {
            val split = frame[Joint.ANKLE_F].x - frame[Joint.ANKLE_B].x
            assertTrue("the feet are only %.1f u apart at p=%.3f — not a split stance".format(split, frame.progress), split >= 100f)
        }
    }

    /** "drive the front knee toward the wall. Pause when you reach the end of your clean range." */
    @Test
    fun theFrontKneeDrivesForwardAndReturns() {
        val f = frames()
        val kneeTravel = PoseFrameSweep.travel(f, Joint.KNEE_F, 0)
        assertTrue(
            "the front knee travels %.2f u forward (start %.2f -> end %.2f)".format(kneeTravel, f.first()[Joint.KNEE_F].x, f.last()[Joint.KNEE_F].x),
            kneeTravel >= 12f
        )
        assertTrue("the knee must END further forward than it started", f.last()[Joint.KNEE_F].x > f.first()[Joint.KNEE_F].x + 8f)
        assertTrue(
            "the body does not travel: pelvis x travel %.2f u".format(PoseFrameSweep.travel(f, Joint.PELVIS, 0)),
            PoseFrameSweep.travel(f, Joint.PELVIS, 0) >= 8f
        )
    }

    /** "Pause when you reach the end of your clean range" — the drill reaches toward the wall. */
    @Test
    fun theKneeApproachesTheWallWithoutReachingIt() {
        val walls = pose().metadata.environment.props.filterIsInstance<WallProp>()
        assertTrue("ankle_mobility faces a wall but declares no WallProp", walls.size == 1)
        val face = AnkleMobilityPose.wallFaceX(walls.first())
        val f = frames()
        for (frame in f) {
            assertTrue(
                "the knee is %.2f u past the wall face (%.2f) at p=%.3f".format(frame[Joint.KNEE_F].x - face, face, frame.progress),
                frame[Joint.KNEE_F].x < face
            )
        }
        assertTrue(
            "the knee ends %.1f u short of the wall — the drill must reach toward it".format(face - f.last()[Joint.KNEE_F].x),
            f.last()[Joint.KNEE_F].x >= face - 30f
        )
    }

    /** "Keep the front heel down" / `mistakes`: "Heel lifting off the floor." */
    @Test
    fun theFrontHeelStaysDownAndPlanted() {
        val f = frames()
        for (joint in listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)) {
            val worst = f.indices.maxOf { PoseFrameSweep.deviation(f, joint, 0, it) }
            assertTrue("$joint slides %.3f u during the drill (the foot is planted)".format(worst), worst <= 0.5f)
        }
        for (frame in f) {
            assertTrue(
                "the front heel rides %.3f below its ankle".format(frame[ankleFor(Joint.HEEL_F)].y - frame[Joint.HEEL_F].y),
                frame[Joint.HEEL_F].y >= frame[Joint.ANKLE_F].y - 0.75f
            )
        }
    }

    /** "Keep the back leg straight and the foot pointed ahead" (the family's stance contract). */
    @Test
    fun theBackLegStaysStraightWithTheFootAhead() {
        for (frame in frames()) {
            val angle = PoseFrameSweep.interiorAngle(frame, Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B)
            assertTrue("the back leg is bent %.1f deg from straight at p=%.3f".format(180f - angle, frame.progress), angle >= 140f)
            val toeOut = toeOutDeg(frame)
            assertTrue("the back foot yaws %.1f deg off 'pointed ahead'".format(toeOut), abs(toeOut) <= 8f)
        }
    }

    /** `tech`: "Let the knee track over the second toe" (+ the "foot collapsing inward" mistake). */
    @Test
    fun theKneeTracksOverItsOwnFoot() {
        for (frame in frames()) {
            assertTrue(
                "the front knee is %.1f u fore/aft of its toe at p=%.3f".format(abs(frame[Joint.KNEE_F].x - frame[Joint.TOE_F].x), frame.progress),
                abs(frame[Joint.KNEE_F].x - frame[Joint.TOE_F].x) <= def.footLength
            )
            assertTrue(
                "the front knee drifts %.1f u laterally off its foot at p=%.3f".format(abs(frame[Joint.KNEE_F].z - frame[Joint.ANKLE_F].z), frame.progress),
                abs(frame[Joint.KNEE_F].z - frame[Joint.ANKLE_F].z) <= def.hipWidth
            )
        }
    }

    @Test
    fun everyAuthoredTargetIsInsideItsChainsReach() {
        for (frame in frames()) {
            assertEquals(
                "the solver relocated a limb at p=%.3f (clamp %.4f)".format(frame.progress, frame.maxIkClampAmount),
                0f, frame.maxIkClampAmount, 1e-4f
            )
        }
    }

    @Test
    fun nothingGoesBelowTheFloor() {
        val f = frames()
        val (joint, clearance) = PoseFrameSweep.worstClearance(f, def, level = 0f)
        assertTrue("$joint is %.3f u below the floor".format(clearance), clearance >= -0.05f)
        for (frame in f) {
            for ((heel, toe, ankle) in listOf(
                Triple(Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_F),
                Triple(Joint.HEEL_B, Joint.TOE_B, Joint.ANKLE_B)
            )) {
                assertTrue("$heel is not flat at p=%.3f".format(frame.progress), abs(frame[heel].y - frame[ankle].y) <= 0.75f)
                assertTrue("$toe is not flat at p=%.3f".format(frame.progress), abs(frame[toe].y - frame[ankle].y) <= 0.75f)
            }
        }
    }

    private fun ankleFor(heel: Joint) = if (heel == Joint.HEEL_F) Joint.ANKLE_F else Joint.ANKLE_B

    /** Signed yaw of the BACK foot's long axis off the forward (+X) axis, in degrees. */
    private fun toeOutDeg(frame: PoseFrameSweep.Frame): Float {
        val dx = frame[Joint.TOE_B].x - frame[Joint.ANKLE_B].x
        val dz = frame[Joint.TOE_B].z - frame[Joint.ANKLE_B].z
        val horiz = sqrt(dx * dx + dz * dz)
        if (horiz < 1e-4f) return 0f
        return Math.toDegrees(kotlin.math.atan2(dz.toDouble(), dx.toDouble())).toFloat()
    }
}
