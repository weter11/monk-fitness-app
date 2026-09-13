package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.CalfStretchPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Calf Stretch (`calf_stretch_hold`) — the standing wall calf stretch on the published frame.
 *
 * Stated intent (the only in-repo specification; no BPS exists for this exercise):
 * `ex_calf_stretch_steps` "Face a wall and place both hands on it. Step one leg back with the heel
 * flat. Bend the front knee and shift forward until the back calf stretches. Keep the back leg
 * straight and the foot pointed ahead. Hold, then switch sides."  `ex_calf_stretch_tech` "Drive the
 * back heel into the floor. Square the hips toward the wall."  `ex_calf_stretch_mistakes` "Letting
 * the back heel lift. Turning the back foot out."
 */
class CalfStretchPoseTest {

    private val ID = "calf_stretch_hold"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = CalfStretchPose()

    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(CalfStretchPose::class.java, cfg!!.builder.javaClass)
        assertTrue(ID in PoseRegistry.getDedicatedAnimationIds())
    }

    private fun frames() = PoseFrameSweep.sweep(pose())

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        assertEquals(f.size, PoseFrameSweep.distinctFrameCount(f))
    }

    /** "Face a wall and place both hands on it." */
    @Test
    fun theHandsRestOnTheWallThroughoutTheStretch() {
        val walls = pose().metadata.environment.props.filterIsInstance<WallProp>()
        assertTrue("calf_stretch presses a wall but declares no WallProp", walls.size == 1)
        val face = CalfStretchPose.wallFaceX(walls.first())
        val f = frames()
        for (joint in listOf(Joint.HAND_A, Joint.HAND_P)) {
            val worst = f.indices.maxOf { PoseFrameSweep.deviation(f, joint, 0, it) }
            assertTrue("$joint slides %.3f u along the wall during the stretch".format(worst), worst <= 8f)
            for (frame in f) {
                val gap = face - frame[joint].x
                assertTrue(
                    "$joint is %.2f u off the wall face (%.2f) at p=%.3f".format(gap, face, frame.progress),
                    abs(gap) <= 8f
                )
            }
        }
    }

    /** "Bend the front knee and shift forward until the back calf stretches." */
    @Test
    fun theBodyShiftsForwardAndTheFrontKneeBends() {
        val f = frames()
        assertTrue(
            "the pelvis shifts %.2f u forward".format(PoseFrameSweep.travel(f, Joint.PELVIS, 0)),
            PoseFrameSweep.travel(f, Joint.PELVIS, 0) >= 12f
        )
        assertTrue("the body must END further forward than it started", f.last()[Joint.PELVIS].x > f.first()[Joint.PELVIS].x + 8f)
        val startFlex = PoseFrameSweep.interiorAngle(f.first(), Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F)
        val endFlex = PoseFrameSweep.interiorAngle(f.last(), Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F)
        assertTrue(
            "the front knee only went from %.1f to %.1f deg interior (the drill bends it forward)".format(startFlex, endFlex),
            startFlex - endFlex >= 8f
        )
    }

    /** "Keep the back leg straight and the foot pointed ahead." */
    @Test
    fun theBackLegStaysStraight() {
        for (frame in frames()) {
            val angle = PoseFrameSweep.interiorAngle(frame, Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B)
            assertTrue("the back leg is bent %.1f deg off straight at p=%.3f".format(180f - angle, frame.progress), angle >= 140f)
        }
    }

    /** `mistakes`: "Letting the back heel lift. Turning the back foot out." */
    @Test
    fun theBackHeelStaysDownAndPointsAhead() {
        val f = frames()
        for (joint in listOf(Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)) {
            val worst = f.indices.maxOf { PoseFrameSweep.deviation(f, joint, 0, it) }
            assertTrue("$joint slides %.3f u during the stretch (the foot is planted)".format(worst), worst <= 0.5f)
        }
        for (frame in f) {
            assertTrue(
                "the back heel lifts to %.3f against its ankle %.3f".format(frame[Joint.HEEL_B].y, frame[Joint.ANKLE_B].y),
                abs(frame[Joint.HEEL_B].y - frame[Joint.ANKLE_B].y) <= 0.75f
            )
            val dx = frame[Joint.TOE_B].x - frame[Joint.ANKLE_B].x
            val dz = frame[Joint.TOE_B].z - frame[Joint.ANKLE_B].z
            val yaw = Math.toDegrees(kotlin.math.atan2(dz.toDouble(), dx.toDouble())).toFloat()
            assertTrue("the back foot yaws %.1f deg off 'pointed ahead' at p=%.3f".format(yaw, frame.progress), abs(yaw) <= 8f)
        }
    }

    /** "Square the hips toward the wall" — the two hips stay level and the stance is front/back. */
    @Test
    fun theHipsAreSquareAndTheStanceIsASplit() {
        for (frame in frames()) {
            assertTrue(
                "the hips are not level: %.2f u".format(abs(frame[Joint.HIP_F].y - frame[Joint.HIP_B].y)),
                abs(frame[Joint.HIP_F].y - frame[Joint.HIP_B].y) <= 6f
            )
            val split = frame[Joint.ANKLE_F].x - frame[Joint.ANKLE_B].x
            assertTrue("the feet are only %.1f u apart — not a split stance".format(split), split >= 100f)
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

    @Suppress("unused")
    private fun dist(a: Vector3, b: Vector3) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
}
