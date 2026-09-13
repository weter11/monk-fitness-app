package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.poses.WallSitPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Wall Sit (`wall_sit_hold`) — the exercise's own biomechanics on the published frame.
 *
 * Stated intent (the only in-repo specification; no BPS exists for this exercise):
 * `ex_wall_sit_steps` "Stand with your back against a wall. Slide down until the knees are about 90
 * degrees. Keep the feet flat and shins close to vertical. Press the low back gently into the wall.
 * Hold." `ex_wall_sit_tech` "Keep the knees tracking over the middle toes. Spread the weight across
 * the full foot." `ex_wall_sit_mistakes` "Feet set too close. Hands pushing on the thighs. Hips
 * sitting higher and higher as fatigue builds."
 */
class WallSitPoseTest {

    private val ID = "wall_sit_hold"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = WallSitPose()

    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(WallSitPose::class.java, cfg!!.builder.javaClass)
        assertTrue(ID in PoseRegistry.getDedicatedAnimationIds())
    }

    private fun frames() = PoseFrameSweep.sweep(pose())

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        assertEquals(f.size, PoseFrameSweep.distinctFrameCount(f))
    }

    /** "the wall" is part of the exercise: the environment must actually carry it (H1's lesson). */
    @Test
    fun theWallExistsAndTheBackStaysOnIt() {
        val walls = pose().metadata.environment.props.filterIsInstance<WallProp>()
        assertTrue("wall_sit defines no WallProp — the athlete leans into empty space", walls.size == 1)
        val face = WallSitPose.wallFaceX(walls.first())
        for (frame in frames()) {
            val standoff = frame[Joint.PELVIS].x - face
            assertTrue(
                "the pelvis is %.2f u from the wall face at p=%.3f (expected a constant body standoff)".format(standoff, frame.progress),
                standoff >= 8f && standoff <= 34f
            )
        }
        // The narrow band above is only meaningful if the SLIDE is vertical: the pelvis may not drift
        // off the wall plane while it descends.
        val pelvisXTravel = PoseFrameSweep.travel(frames(), Joint.PELVIS, 0)
        assertTrue(
            "the pelvis drifts %.2f u forward/back — a wall sit slides DOWN the wall".format(pelvisXTravel),
            pelvisXTravel <= 1.5f
        )
    }

    @Test
    fun theHipsSlideDownTheWallAndHold() {
        val f = frames()
        val travel = PoseFrameSweep.travelY(f, Joint.PELVIS)
        assertTrue("measured pelvis travel %.2f u (top %.2f -> hold %.2f)".format(travel, f.first().y(Joint.PELVIS), f.last().y(Joint.PELVIS)), travel >= 60f)
    }

    /** "Slide down until the knees are about 90 degrees." */
    @Test
    fun theKneesReachNinetyDegreesAtTheHold() {
        val hold = frames().last()
        for ((hip, knee, ankle) in listOf(
            Triple(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F),
            Triple(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B)
        )) {
            val angle = PoseFrameSweep.interiorAngle(hold, hip, knee, ankle)
            assertTrue("interior knee angle at the hold is %.1f deg (target ~90)".format(angle), angle in 80f..100f)
        }
    }

    /**
     * "Keep … shins close to vertical" — this is the HOLD's contract (`steps` §3 describes the held
     * position). Through the slide the shins are still travelling from the tall wall-lean, where the
     * legs are nearly extended and the back is on the wall, so the per-frame bound is the geometry of
     * that entry rather than the hold's own number.
     */
    @Test
    fun theShinsComeVerticalAtTheHold() {
        val f = frames()
        val hold = f.last()
        for ((knee, ankle) in listOf(Joint.KNEE_F to Joint.ANKLE_F, Joint.KNEE_B to Joint.ANKLE_B)) {
            val holdTilt = shinTilt(hold, knee, ankle)
            assertTrue("$knee/$ankle shin is %.1f deg off vertical at the hold".format(holdTilt), holdTilt <= 12f)
            assertTrue("the shins must END closer to vertical than they started", holdTilt < shinTilt(f.first(), knee, ankle))
            for (frame in f) {
                val tilt = shinTilt(frame, knee, ankle)
                assertTrue("$knee/$ankle shin is %.1f deg off vertical at p=%.3f".format(tilt, frame.progress), tilt <= 40f)
            }
        }
    }

    private fun shinTilt(frame: PoseFrameSweep.Frame, knee: Joint, ankle: Joint): Float {
        val shin = Vector3().set(frame[knee]).subtract(frame[ankle])
        return Math.toDegrees(kotlin.math.atan2(sqrt(shin.x * shin.x + shin.z * shin.z).toDouble(), shin.y.toDouble())).toFloat()
    }

    /** The thighs come parallel at the hold (the "90 degrees" the exercise names). */
    @Test
    fun theThighsComeParallelAtTheHold() {
        val hold = frames().last()
        val drop = abs(hold[Joint.HIP_F].y - hold[Joint.KNEE_F].y)
        assertTrue(
            "the thigh is %.1f u off horizontal at the hold (thigh length %.0f)".format(drop, def.thighLength),
            drop <= def.thighLength * 0.2f
        )
    }

    /** "Keep the knees tracking over the middle toes." */
    @Test
    fun theKneesTrackOverTheirFeet() {
        for (frame in frames()) {
            for ((knee, ankle) in listOf(Joint.KNEE_F to Joint.ANKLE_F, Joint.KNEE_B to Joint.ANKLE_B)) {
                val overAnkle = abs(frame[knee].x - frame[ankle].x)
                assertTrue(
                    "$knee is %.1f u fore/aft of its ankle at p=%.3f (the knee may not travel past the foot)".format(overAnkle, frame.progress),
                    overAnkle <= def.footLength
                )
                assertTrue(
                    "$knee is %.1f u lateral of its ankle at p=%.3f".format(abs(frame[knee].z - frame[ankle].z), frame.progress),
                    abs(frame[knee].z - frame[ankle].z) <= def.hipWidth * 1.5f
                )
            }
        }
    }

    /** "Keep the feet flat … spread the weight across the full foot" — planted through the slide. */
    @Test
    fun theFeetStayFlatAndPlanted() {
        val f = frames()
        for (joint in listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)) {
            val worst = f.indices.maxOf { PoseFrameSweep.deviation(f, joint, 0, it) }
            assertTrue("$joint slides %.3f u during the wall sit".format(worst), worst <= 0.5f)
        }
        for (frame in f) {
            for ((heel, toe, ankle) in listOf(
                Triple(Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_F),
                Triple(Joint.HEEL_B, Joint.TOE_B, Joint.ANKLE_B)
            )) {
                assertTrue("$heel is not flat (y=%.3f vs ankle %.3f)".format(frame[heel].y, frame[ankle].y), abs(frame[heel].y - frame[ankle].y) <= 0.75f)
                assertTrue("$toe is not flat (y=%.3f vs ankle %.3f)".format(frame[toe].y, frame[ankle].y), abs(frame[toe].y - frame[ankle].y) <= 0.75f)
            }
        }
    }

    /** `mistakes`: "Hands pushing on the thighs" — the hands hang clear of the legs. */
    @Test
    fun theHandsDoNotPushOnTheThighs() {
        for (frame in frames()) {
            for ((hand, hip, knee) in listOf(
                Triple(Joint.HAND_A, Joint.HIP_F, Joint.KNEE_F),
                Triple(Joint.HAND_P, Joint.HIP_B, Joint.KNEE_B)
            )) {
                val d = pointToSegment(frame[hand], frame[hip], frame[knee])
                assertTrue(
                    "$hand is %.1f u from its thigh at p=%.3f (hands must not push the thighs)".format(d, frame.progress),
                    d >= 18f
                )
            }
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
        val (joint, clearance) = PoseFrameSweep.worstClearance(frames(), def, level = 0f)
        assertTrue("$joint is %.3f u below the floor".format(clearance), clearance >= -0.05f)
    }

    private fun pointToSegment(p: Vector3, a: Vector3, b: Vector3): Float {
        val ab = Vector3().set(b).subtract(a)
        val ap = Vector3().set(p).subtract(a)
        val len2 = ab.x * ab.x + ab.y * ab.y + ab.z * ab.z
        if (len2 < 1e-6f) return ap.mag()
        val t = ((ap.x * ab.x + ap.y * ab.y + ap.z * ab.z) / len2).coerceIn(0f, 1f)
        val proj = Vector3(a.x + ab.x * t, a.y + ab.y * t, a.z + ab.z * t)
        return Vector3().set(p).subtract(proj).mag()
    }
}
