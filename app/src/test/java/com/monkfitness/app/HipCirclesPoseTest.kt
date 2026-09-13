package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.HipCirclesPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Hip Circles (`hip_circles_hold`) — the exercise's own biomechanics on the PUBLISHED frame.
 *
 * ## Stated intent (there is no BPS and no copy for this exercise)
 *
 * `WorkoutGenerator` passes `R.string.ex_hip_circles` as *every* field, so the catalog entry is the
 * title and nothing else. The movement's identity is derived (and recorded in the pose's KDoc) from
 * the two channels that do state it:
 *
 *  * `values-ru` `ex_hip_circles` = *"Круговые движения тазом"*, `values-uk` = *"Обертання тазом"* —
 *    the circling subject is the **pelvis**;
 *  * `ex_hip_cars_desc` claims the leg-circle for the *other* exercise (*"A slow hip circle … not a
 *    swing"*), so this pose may not be a second leg circle.
 *
 * The assertions below therefore measure a **pelvic circle**: the pelvis's horizontal locus is a
 * circle traversed through a full turn, while both feet stay planted and the trunk stays upright.
 * Every reading is taken through [PoseFrameSweep] (the pipeline entered exactly as `SkeletonRenderer`
 * enters it), never on a raw `build()` result.
 */
class HipCirclesPoseTest {

    private val ID = "hip_circles_hold"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = HipCirclesPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.3f", v)

    /** The coverage metric itself: this exercise must resolve to a REAL skeletal animation. */
    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(HipCirclesPose::class.java, cfg!!.builder.javaClass)
        assertTrue("$ID is not a deduplicated dedicated animation", ID in PoseRegistry.getDedicatedAnimationIds())
    }

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        assertEquals(
            "the published-frame sweep aliased (T-7 class): storing the reused pipeline buffer " +
                "reports one frame N times and every travel assertion reads 0",
            f.size, PoseFrameSweep.distinctFrameCount(f)
        )
    }

    /**
     * The circle's centre, MEASURED from the published locus: the bounding-box centre of a locus
     * traversed through a full turn is the circle's own centre (a mean of the samples would be biased
     * both by the repeated seam sample and by any asymmetric sampling).
     */
    private fun measuredCentre(f: List<PoseFrameSweep.Frame>): Pair<Float, Float> {
        val xs = f.map { it[Joint.PELVIS].x }
        val zs = f.map { it[Joint.PELVIS].z }
        return (xs.max() + xs.min()) / 2f to (zs.max() + zs.min()) / 2f
    }

    /**
     * THE exercise: the pelvis's horizontal locus is a **circle** — equal amplitude on both axes with
     * a quarter-cycle phase difference — not a sway along one axis.
     */
    @Test
    fun thePelvisTracesACircleInTheHorizontalPlane() {
        val f = frames()
        val (centreX, centreZ) = measuredCentre(f)
        for (frame in f) {
            val dx = frame[Joint.PELVIS].x - centreX
            val dz = frame[Joint.PELVIS].z - centreZ
            val radius = sqrt(dx * dx + dz * dz)
            assertTrue(
                "the pelvis is %.2f u from the circle's centre at p=%.3f (authored radius %.1f)".format(
                    radius, frame.progress, HipCirclesPose.CIRCLE_RADIUS
                ),
                abs(radius - HipCirclesPose.CIRCLE_RADIUS) <= 0.5f
            )
        }
        // Equal amplitude on both axes is what makes it a circle rather than a front-to-back sway.
        val travelX = PoseFrameSweep.travel(f, Joint.PELVIS, 0)
        val travelZ = PoseFrameSweep.travel(f, Joint.PELVIS, 2)
        assertEquals(
            "the locus is an ellipse: X travel ${fmt(travelX)} u vs Z travel ${fmt(travelZ)} u",
            travelX, travelZ, 0.5f
        )
        assertEquals(
            "the pelvis's circle must be the authored diameter",
            2f * HipCirclesPose.CIRCLE_RADIUS, travelX, 0.5f
        )
    }

    /** A circle, not a there-and-back line: the phase angle advances through a full turn. */
    @Test
    fun theCircleIsTraversedThroughAFullTurnInOneDirection() {
        val f = frames()
        val (centreX, centreZ) = measuredCentre(f)
        val angles = f.map {
            Math.toDegrees(atan2((it[Joint.PELVIS].z - centreZ).toDouble(), (it[Joint.PELVIS].x - centreX).toDouble())).toFloat()
        }
        var total = 0f
        var direction = 0
        for (i in 1 until angles.size) {
            var step = angles[i] - angles[i - 1]
            while (step > 180f) step -= 360f
            while (step < -180f) step += 360f
            assertTrue(
                "the circle's phase step %.1f deg at sample %d is not an even quarter turn".format(step, i),
                abs(step) >= 25f && abs(step) <= 65f
            )
            if (direction == 0) direction = if (step > 0f) 1 else -1
            assertTrue(
                "the circle reverses direction at sample %d (step %.1f)".format(i, step),
                (if (step > 0f) 1 else -1) == direction
            )
            total += step
        }
        assertTrue(
            "the sweep only covered %.1f deg — a full turn is %.1f".format(total, 360f),
            abs(abs(total) - 360f) <= 45f
        )
        // The loop seam: the last frame is the first frame of the next rep.
        assertEquals(
            "the circle does not close (pelvis X %.3f → %.3f)".format(f.first()[Joint.PELVIS].x, f.last()[Joint.PELVIS].x),
            0f, abs(f.first()[Joint.PELVIS].x - f.last()[Joint.PELVIS].x), 1e-3f
        )
        assertEquals(
            "the circle does not close (pelvis Z)",
            0f, abs(f.first()[Joint.PELVIS].z - f.last()[Joint.PELVIS].z), 1e-3f
        )
    }

    /** The hips circle under the trunk: the height never changes (a horizontal circle, not a bob). */
    @Test
    fun theHipsCircleWithoutBobbing() {
        val f = frames()
        assertEquals(
            "the pelvis changes height by %.3f u — the circle is not horizontal".format(PoseFrameSweep.travelY(f, Joint.PELVIS)),
            0f, PoseFrameSweep.travelY(f, Joint.PELVIS), 0.5f
        )
        for (frame in f) {
            assertEquals(
                "the pelvis height at p=%.3f is not the authored stance".format(frame.progress),
                HipCirclesPose.STANCE_PELVIS_Y, frame.y(Joint.PELVIS), 0.5f
            )
        }
    }

    /** The drill moves the HIPS, not the feet: both feet are planted for the whole circle. */
    @Test
    fun bothFeetStayPlantedThroughTheWholeCircle() {
        val f = frames()
        for (joint in listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)) {
            val worst = f.indices.maxOf { PoseFrameSweep.deviation(f, joint, 0, it) }
            assertTrue(
                "$joint slides %.3f u through the circle (a planted foot may not travel)".format(worst),
                worst <= 0.5f
            )
        }
    }

    /** The trunk stays upright over the circling hips (the hips travel UNDER the trunk). */
    @Test
    fun theTrunkStaysUprightAboveTheCirclingHips() {
        for (frame in frames()) {
            val trunk = Vector3().set(frame[Joint.CHEST]).subtract(frame[Joint.PELVIS])
            val offVertical = Math.toDegrees(
                atan2(sqrt(trunk.x * trunk.x + trunk.z * trunk.z).toDouble(), trunk.y.toDouble())
            ).toFloat()
            assertTrue(
                "the trunk is %.1f deg off vertical at p=%.3f".format(offVertical, frame.progress),
                offVertical <= 5f
            )
        }
    }

    /** The legs are live, not slack: each knee works through the circle to keep the feet planted. */
    @Test
    fun theLegsAbsorbTheCircleThroughBothKnees() {
        val f = frames()
        for ((hip, knee, ankle) in listOf(
            Triple(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F),
            Triple(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B)
        )) {
            val angles = f.map { PoseFrameSweep.interiorAngle(it, hip, knee, ankle) }
            assertTrue(
                "$knee stays at %.2f–%.2f deg — outside the light-stance band".format(angles.min(), angles.max()),
                angles.min() >= 140f && angles.max() <= 165f
            )
            assertTrue(
                "$knee only moves %.2f deg through the circle — the leg is not absorbing it".format(angles.max() - angles.min()),
                angles.max() - angles.min() >= 1f
            )
        }
        // The knees circle with the pelvis (a planted foot with a moving hip = a moving knee).
        assertTrue(
            "the knee barely moves (%.2f u) while the pelvis circles 48 u".format(PoseFrameSweep.travel3D(f, Joint.KNEE_F)),
            PoseFrameSweep.travel3D(f, Joint.KNEE_F) >= 15f
        )
    }

    /** The reach contract: every authored target is inside its own chain's reachable band. */
    @Test
    fun everyAuthoredTargetIsInsideItsChainsReach() {
        for (frame in frames()) {
            assertEquals(
                "the solver relocated a limb at p=%.3f (clamp %.4f)".format(frame.progress, frame.maxIkClampAmount),
                0f, frame.maxIkClampAmount, 1e-4f
            )
        }
    }

    /** The ground invariant: the athlete stands ON the floor with flat, planted feet. */
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
                assertTrue(
                    "$heel rides at y=%.3f against its ankle y=%.3f (not a flat plant)".format(frame[heel].y, frame[ankle].y),
                    abs(frame[heel].y - frame[ankle].y) <= 0.75f
                )
                assertTrue(
                    "$toe rides at y=%.3f against its ankle y=%.3f (not a flat plant)".format(frame[toe].y, frame[ankle].y),
                    abs(frame[toe].y - frame[ankle].y) <= 0.75f
                )
            }
        }
    }

    /**
     * The COM stays over the base of support: the circle's radius is inside the stance on both axes,
     * so no phase of the drill can tip the athlete.
     */
    @Test
    fun theCircleStaysInsideTheBaseOfSupport() {
        val footZ = HipCirclesPose.footZ(def)
        assertTrue(
            "the circle (%.1f u) reaches past the stance (%.1f u)".format(HipCirclesPose.CIRCLE_RADIUS, footZ),
            HipCirclesPose.CIRCLE_RADIUS < footZ
        )
        // and the legs never reach their own extension cap at any phase (the authored depth's job)
        val cap = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        for (frame in frames()) {
            for ((hip, ankle) in listOf(Joint.HIP_F to Joint.ANKLE_F, Joint.HIP_B to Joint.ANKLE_B)) {
                val chord = Vector3().set(frame[ankle]).subtract(frame[hip]).mag()
                assertTrue(
                    "the $hip→$ankle chord is %.2f u of the chain's %.2f cap at p=%.3f".format(
                        chord, cap, frame.progress
                    ),
                    chord <= cap
                )
            }
        }
    }

    /** The hands ride the hips they are planted on (both axes of the circle). */
    @Test
    fun theHandsRideTheHipsThroughTheCircle() {
        val f = frames()
        for (frame in f) {
            val pel = frame[Joint.PELVIS]
            assertEquals(
                "HAND_A left its hip at p=%.3f (x gap %.2f u)".format(frame.progress, frame[Joint.HAND_A].x - pel.x),
                6f, frame[Joint.HAND_A].x - pel.x, 1.5f
            )
            assertEquals(
                "HAND_A slid off the hip laterally at p=%.3f".format(frame.progress),
                -(def.hipWidth + 5f), frame[Joint.HAND_A].z - pel.z, 1.5f
            )
            assertTrue(
                "HAND_A travels %.2f u but the hips travel 48 u — the hands are not riding them".format(
                    PoseFrameSweep.travel3D(f, Joint.HAND_A)
                ),
                PoseFrameSweep.travel3D(f, Joint.HAND_A) >= 40f
            )
        }
    }
}
