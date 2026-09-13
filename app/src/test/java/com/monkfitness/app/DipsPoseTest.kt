package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.DipsPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Parallel-Bar Dip (`dip_parallel_bar`) — the exercise's own biomechanics on the PUBLISHED frame.
 *
 * Stated intent (the only in-repo specification for this exercise; no BPS exists for it) —
 * `ex_dips_steps`: §1 "Grip parallel bars and lift yourself up with locked arms", §2 "Lower your body
 * by bending elbows until they are at a 90-degree angle", §3 "Keep your torso slightly leaned forward
 * for chest emphasis or upright for triceps", §4 "Push back up to the starting position";
 * `ex_dips_tech` "Keep your shoulders down and away from your ears. Ensure elbows don't flare out too
 * much. Control the descent to protect the shoulder joint."; `ex_dips_mistakes` "Flaring elbows,
 * excessive swinging, and dipping too deep (below 90 degrees) which can strain shoulders."
 */
class DipsPoseTest {

    private val ID = "dip_parallel_bar"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = DipsPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private val barY = DipsPose.BAR_Y
    private val gripZ = DipsPose.GRIP_WIDTH_FACTOR * def.shoulderWidth

    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(DipsPose::class.java, cfg!!.builder.javaClass)
        assertTrue(ID in PoseRegistry.getDedicatedAnimationIds())
    }

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        assertEquals(f.size, PoseFrameSweep.distinctFrameCount(f))
    }

    /** `steps` §1: the athlete grips PARALLEL BARS — two bars, one under each hand, with its own anchor. */
    @Test
    fun theTwoParallelBarsExistAndEachHandGripsItsOwnBar() {
        val env = pose().metadata.environment
        assertEquals("the dip is performed on a PAIR of bars", 2, env.props.filterIsInstance<BoxProp>().size)
        val anchors = env.anchors
        assertEquals(2, anchors.size)
        assertTrue(
            "both anchors must be parallel bars",
            anchors.all { it.type == EnvironmentAnchorType.PARALLEL_BARS }
        )
        // One bar per hand, each at its own grip width (the expectation is derived in the test from
        // the pose's published factor × the definition's shoulder width, and compared with a
        // tolerance: both sides are Float arithmetic on the same quantity, so an exact `==` would
        // assert the rounding of two different expressions rather than the geometry).
        for ((anchor, expectedZ) in anchors.sortedBy { it.worldPosition.z }.zip(listOf(-gripZ, gripZ))) {
            assertEquals(
                "the bar ${anchor.id} must stand at its own grip width (measured %.4f u)".format(anchor.worldPosition.z),
                expectedZ, anchor.worldPosition.z, 1e-3f
            )
        }
        val byPoint = pose().metadata.support.contacts.associate { it.point to it.anchorId }
        assertEquals("LEFT_HAND must name its own bar", DipsPose.BAR_A_ANCHOR_ID, byPoint[SupportPoint.LEFT_HAND])
        assertEquals("RIGHT_HAND must name its own bar", DipsPose.BAR_P_ANCHOR_ID, byPoint[SupportPoint.RIGHT_HAND])
        assertEquals("the dip pivots about the hands", PivotType.HANDS, pose().metadata.support.pivot)
        // The hands are IK'd to CONSTANTS on the bars.
        for (frame in frames()) {
            assertEquals("HAND_A left its bar at p=%.3f (y)".format(frame.progress), barY, frame[Joint.HAND_A].y, 0.01f)
            assertEquals("HAND_A left its bar at p=%.3f (z)".format(frame.progress), -gripZ, frame[Joint.HAND_A].z, 0.01f)
            assertEquals("HAND_P left its bar at p=%.3f".format(frame.progress), gripZ, frame[Joint.HAND_P].z, 0.01f)
        }
    }

    /** The support's own sign: the shoulders hang ABOVE the fixed hands (this is a support, not a hang). */
    @Test
    fun theShouldersAreSupportedAboveTheHandsAtEveryPhase() {
        for (frame in frames()) {
            assertTrue(
                "the shoulder is %.2f u above the grip at p=%.3f — the body must be supported".format(
                    frame[Joint.SHOULDER_A].y - frame[Joint.HAND_A].y, frame.progress
                ),
                frame[Joint.SHOULDER_A].y > frame[Joint.HAND_A].y + 80f
            )
        }
    }

    /** `steps` §1: "lockout" — the top of the rep is the arm chain at its own near-full extension. */
    @Test
    fun theLockoutIsTheArmChainsFullExtension() {
        val top = frames().first()
        val reach = Vector3().set(top[Joint.HAND_A]).subtract(top[Joint.SHOULDER_A]).mag()
        val maxReach = SkeletonMath.maxReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
        assertEquals(
            "the lockout must be the authored near-full-extension reach",
            DipsPose.LOCKOUT_REACH, reach, 0.05f
        )
        assertTrue(
            "the arms must be at the chain's full extension at the lockout (%.2f of %.2f)".format(reach, maxReach),
            reach >= 0.95f * maxReach
        )
        val elbow = PoseFrameSweep.interiorAngle(top, Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A)
        assertTrue("the lockout elbow is at %.1f deg".format(elbow), elbow >= 138f)
    }

    /**
     * `steps` §2 / `mistakes`: the bottom IS the copy's own 90-degree angle — and the rep can never go
     * deeper than it ("dipping too deep (below 90 degrees)"). The depth is authored from the
     * definition (`[DipsPose.ninetyDegreeReach]` = `sqrt(upperArm² + forearm²)`, the reach whose elbow
     * interior angle is exactly 90°), not tuned.
     */
    @Test
    fun theElbowsReachNinetyDegreesAtTheBottomAndNoDeeper() {
        val f = frames()
        val bottom = PoseFrameSweep.interiorAngle(f.last(), Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A)
        assertEquals(
            "the bottom of the rep must BE the copy's 90-degree elbow (measured %.2f deg)".format(bottom),
            90.0f, bottom, 0.5f
        )
        for (frame in f) {
            val angle = PoseFrameSweep.interiorAngle(frame, Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A)
            assertTrue(
                "the rep dips past the copy's 90 degrees at p=%.3f (elbow %.2f deg)".format(frame.progress, angle),
                angle >= 89.5f
            )
        }
        val reach = Vector3().set(f.last()[Joint.HAND_A]).subtract(f.last()[Joint.SHOULDER_A]).mag()
        assertEquals(
            "the bottom reach must be the definition's own 90-degree reach",
            DipsPose.ninetyDegreeReach(def), reach, 0.05f
        )
    }

    /** `steps` §4: the body descends through the palm and presses back to the lockout. */
    @Test
    fun theBodyDescendsThroughTheRep() {
        val f = frames()
        val shoulders = f.map { it[Joint.SHOULDER_A].y }
        val travel = shoulders.max() - shoulders.min()
        assertTrue("measured shoulder travel %.2f u".format(travel), travel >= 30f)
        for (i in 1 until shoulders.size) {
            assertTrue(
                "the body must descend monotonically (%.2f → %.2f)".format(shoulders[i - 1], shoulders[i]),
                shoulders[i] <= shoulders[i - 1] + 0.01f
            )
        }
        assertEquals(
            "the descent at both ends must be the authored reach schedule's own geometry",
            DipsPose.LOCKOUT_REACH - DipsPose.ninetyDegreeReach(def), travel, 1f
        )
    }

    /** `steps` §3's first option: the torso is leaned forward, so the hips/shins sweep back. */
    @Test
    fun theTorsoLeansForwardAndTheLegsSweepBehindTheBars() {
        for (frame in frames()) {
            val trunk = Vector3().set(frame[Joint.CHEST]).subtract(frame[Joint.PELVIS])
            val lean = Math.toDegrees(kotlin.math.atan2(trunk.x.toDouble(), trunk.y.toDouble())).toFloat()
            assertEquals(
                "the authored torso lean at p=%.3f".format(frame.progress),
                Math.toDegrees(DipsPose.TRUNK_LEAN.toDouble()).toFloat(), lean, 1.5f
            )
            assertTrue(
                "the hips must sit behind the bars (%.2f u)".format(frame[Joint.PELVIS].x),
                frame[Joint.PELVIS].x < frame[Joint.HAND_A].x - 10f
            )
        }
    }

    /** `tech`: "Keep your shoulders down and away from your ears" — nothing elevates the shoulder. */
    @Test
    fun theShouldersNeverShrug() {
        val f = frames()
        val first = f.first()[Joint.SHOULDER_A].y
        // The shoulder descends with the body (the rep's own travel); it must never rise ABOVE its
        // lockout height, which is what a shrug would do.
        for (frame in f) {
            assertTrue(
                "the shoulder rose above the lockout at p=%.3f (%.2f > %.2f)".format(frame.progress, frame[Joint.SHOULDER_A].y, first),
                frame[Joint.SHOULDER_A].y <= first + 0.01f
            )
        }
    }

    /** `tech`/`mistakes`: "elbows don't flare out too much" — the elbows travel BEHIND the body. */
    @Test
    fun theElbowsTravelBehindTheShoulderLineWithoutFlaring() {
        for (frame in frames()) {
            val behind = frame[Joint.SHOULDER_A].x - frame[Joint.ELBOW_A].x
            assertTrue(
                "the elbow must stay behind the shoulder at p=%.3f (measured %.2f u forward)".format(frame.progress, -behind),
                behind >= 15f
            )
            val flare = abs(frame[Joint.ELBOW_A].z - frame[Joint.SHOULDER_A].z)
            assertTrue(
                "the elbow flared %.2f u laterally at p=%.3f".format(flare, frame.progress),
                flare <= 20f
            )
        }
        // The descent straightens the elbow backwards, not outwards: the dorsal travel grows.
        val f = frames()
        val bottomDorsal = f.last()[Joint.SHOULDER_A].x - f.last()[Joint.ELBOW_A].x
        val topDorsal = f.first()[Joint.SHOULDER_A].x - f.first()[Joint.ELBOW_A].x
        assertTrue(
            "the elbows must travel further behind as the body descends (%.2f → %.2f)".format(topDorsal, bottomDorsal),
            bottomDorsal > topDorsal + 15f
        )
    }

    /** Nothing supports the feet: the athlete hangs from the bars, clear of the ground. */
    @Test
    fun theFeetHangClearOfTheGround() {
        assertEquals(
            "the dip declares only the hands as support (nothing under the feet)",
            setOf(SupportPoint.LEFT_HAND, SupportPoint.RIGHT_HAND),
            pose().metadata.support.contacts.map { it.point }.toSet()
        )
        for (frame in frames()) {
            for (joint in listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)) {
                assertTrue(
                    "$joint is %.2f u above the floor at p=%.3f — the feet must hang clear".format(frame[joint].y, frame.progress),
                    frame[joint].y >= 15f
                )
            }
        }
    }

    /** The gripping hands lie in the bars' own plane (the surface their declaration names). */
    @Test
    fun theGripLiesInTheBarsOwnPlane() {
        for (frame in frames()) {
            for (chain in listOf(
                listOf(Joint.HAND_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A),
                listOf(Joint.HAND_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)
            )) {
                for (joint in chain) {
                    assertTrue(
                        "$joint is %.3f u off the bar's top plane at p=%.3f".format(frame[joint].y - barY, frame.progress),
                        abs(frame[joint].y - barY) <= 2f
                    )
                }
            }
        }
    }

    /** The hanging legs are one (nearly) straight segment, below the hips. */
    @Test
    fun theLegsHangBelowTheHips() {
        for (frame in frames()) {
            for ((hip, knee, ankle) in listOf(
                Triple(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F),
                Triple(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B)
            )) {
                assertTrue(
                    "$ankle is not below $knee below $hip at p=%.3f".format(frame.progress),
                    frame[ankle].y < frame[knee].y && frame[knee].y < frame[hip].y
                )
                val angle = PoseFrameSweep.interiorAngle(frame, hip, knee, ankle)
                assertTrue(
                    "$hip/$knee/$ankle is bent to %.1f deg at p=%.3f".format(angle, frame.progress),
                    angle >= 150f
                )
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

    /** The bar height is a product choice: it must leave the ground visible under the hanging feet. */
    @Test
    fun theFeetClearTheGroundWithRoomToSpare() {
        val f = frames()
        val worst = f.minOf { frame -> listOf(Joint.TOE_F, Joint.TOE_B).minOf { frame[it].y } }
        assertTrue("the worst toe clearance is %.2f u".format(worst), worst >= 25f)
        // and the bars stand above the floor by their own authored height
        assertEquals(barY, pose().metadata.environment.anchors.first().worldPosition.y, 0.01f)
        assertTrue("the bars must stand above the floor", barY > 0f)
        // the support's own geometry is exact: the reach is never projected onto the chain's band
        val maxReach = SkeletonMath.maxReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
        for (frame in frames()) {
            val reach = Vector3().set(frame[Joint.HAND_A]).subtract(frame[Joint.SHOULDER_A]).mag()
            assertTrue("the realized reach %.4f is inside the chain's band".format(reach), reach <= maxReach)
        }
    }
}
