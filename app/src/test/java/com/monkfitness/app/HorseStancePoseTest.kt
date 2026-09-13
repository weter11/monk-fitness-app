package com.monkfitness.app

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.Side
import com.monkfitness.app.poses.HorseStancePose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Horse Stance (`horse_stance_hold`) — the exercise's own biomechanics on the published frame.
 *
 * Stated intent (the only in-repo specification — there is no BPS for this exercise):
 * `ex_horse_stance_steps` "Stand with feet double shoulder-width apart, toes pointed slightly
 * outward. Sink your hips down until thighs are parallel to the floor. Keep your back straight and
 * hands in front or on your hips. Hold."  `ex_horse_stance_tech` "Tuck your pelvis slightly to avoid
 * overarching the lower back. Distribute weight evenly across the entire foot."
 * `ex_horse_stance_mistakes` "Letting the knees collapse inward, rounding the back, and not sinking
 * deep enough."
 *
 * Every assertion below is measured through [PoseFrameSweep] (the production pipeline's PUBLISHED
 * frames), never on a raw `build()` result.
 */
class HorseStancePoseTest {

    private val ID = "horse_stance_hold"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = HorseStancePose()

    /** The coverage metric itself: this exercise must resolve to a REAL skeletal animation. */
    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(HorseStancePose::class.java, cfg!!.builder.javaClass)
        assertTrue("$ID is not a deduplicated dedicated animation", ID in PoseRegistry.getDedicatedAnimationIds())
    }

    private fun frames() = PoseFrameSweep.sweep(pose())

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        assertEquals(
            "the published-frame sweep aliased (T-7 class): storing the reused pipeline buffer " +
                "reports one frame N times and every travel assertion reads 0",
            f.size, PoseFrameSweep.distinctFrameCount(f)
        )
    }

    /** "Sink your hips down until thighs are parallel to the floor" — a real, deep descent. */
    @Test
    fun theHipsSinkIntoTheStanceAndReturn() {
        val f = frames()
        val top = f.first().y(Joint.PELVIS)
        val bottom = f.last().y(Joint.PELVIS)
        assertTrue(
            "the horse stance must sink: measured pelvis travel %.2f u (top %.2f -> bottom %.2f)".format(
                PoseFrameSweep.travelY(f, Joint.PELVIS), top, bottom
            ),
            PoseFrameSweep.travelY(f, Joint.PELVIS) >= 40f
        )
        assertTrue("the hold must be deeper than the entry", bottom < top - 40f)
    }

    /** "feet double shoulder-width apart" — the stance width is the exercise's defining geometry. */
    @Test
    fun theStanceIsDoubleShoulderWidth() {
        val f = frames()
        for (frame in f) {
            val width = abs(frame[Joint.ANKLE_F].z - frame[Joint.ANKLE_B].z)
            val expected = 2f * def.shoulderWidth
            assertTrue(
                "stance width at p=%.3f is %.2f u; double shoulder-width is %.2f u".format(frame.progress, width, expected),
                abs(width - expected) <= 4f
            )
            // Symmetric about the mid-line (even weight distribution, `tech`).
            assertTrue(
                "the stance is not centred: ankles at z=%.2f / %.2f".format(frame[Joint.ANKLE_F].z, frame[Joint.ANKLE_B].z),
                abs(frame[Joint.ANKLE_F].z + frame[Joint.ANKLE_B].z) <= 1f
            )
        }
    }

    /** "Distribute weight evenly across the entire foot" — both feet stay planted through the rep. */
    @Test
    fun theFeetStayPlantedThroughTheWholeCycle() {
        val f = frames()
        for (joint in listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)) {
            val worst = f.indices.maxOf { PoseFrameSweep.deviation(f, joint, 0, it) }
            assertTrue(
                "$joint slides %.3f u through the stance (a planted foot may not travel)".format(worst),
                worst <= 0.5f
            )
        }
    }

    /** "toes pointed slightly outward" — an authored foot heading, mirrored across the mid-line. */
    @Test
    fun theToesTurnOut() {
        val f = frames()
        // 1 u outward / 1 u forward = 45 deg; a "slight" toe-out reads as a few degrees.
        val minYaw = 8f
        for (frame in f) {
            val toeOutF = toeOutAngleDeg(frame, Joint.ANKLE_F, Joint.TOE_F, outwardSign = -1f)
            val toeOutB = toeOutAngleDeg(frame, Joint.ANKLE_B, Joint.TOE_B, outwardSign = 1f)
            assertTrue("ankle_f foot yaw %.1f deg is not outward".format(toeOutF), toeOutF >= minYaw)
            assertTrue("ankle_b foot yaw %.1f deg is not outward".format(toeOutB), toeOutB >= minYaw)
            assertTrue(
                "the toe-out is not mirrored: %.1f vs %.1f".format(toeOutF, toeOutB),
                abs(toeOutF - toeOutB) <= 1f
            )
        }
    }

    /** "Sink … until thighs are parallel to the floor", knees tracking out — not collapsing in. */
    @Test
    fun theThighsComeParallelAtTheHold() {
        val bottom = frames().last()
        val kneeF = PoseFrameSweep.interiorAngle(bottom, Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F)
        val kneeB = PoseFrameSweep.interiorAngle(bottom, Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B)
        assertTrue("interior knee angle at the hold is %.1f deg (target ~90)".format(kneeF), kneeF in 75f..115f)
        assertTrue("the knees are not symmetric: %.1f vs %.1f".format(kneeF, kneeB), abs(kneeF - kneeB) <= 2f)
        val thighDropF = abs(bottom[Joint.HIP_F].y - bottom[Joint.KNEE_F].y)
        assertTrue(
            "the thigh is %.1f u off horizontal (thigh length %.0f) — not parallel to the floor".format(thighDropF, def.thighLength),
            thighDropF <= def.thighLength * 0.42f
        )
    }

    /** "Letting the knees collapse inward" is the named mistake: the knees stay outboard. */
    @Test
    fun theKneesTrackOutboardOfTheAnkles() {
        val bottom = frames().last()
        assertTrue(
            "knee_f z=%.2f is inboard of its own hip".format(bottom[Joint.KNEE_F].z),
            bottom[Joint.KNEE_F].z <= bottom[Joint.HIP_F].z + 1f
        )
        assertTrue(
            "knee_b z=%.2f is inboard of its own hip".format(bottom[Joint.KNEE_B].z),
            bottom[Joint.KNEE_B].z >= bottom[Joint.HIP_B].z - 1f
        )
    }

    /** "Keep your back straight" — an upright trunk, never a rounded/hinged one. */
    @Test
    fun theTrunkStaysUpright() {
        for (frame in frames()) {
            val trunk = com.monkfitness.app.animation.Vector3()
                .set(frame[Joint.CHEST]).subtract(frame[Joint.PELVIS])
            val offVertical = Math.toDegrees(atan2(sqrt(trunk.x * trunk.x + trunk.z * trunk.z).toDouble(), trunk.y.toDouble())).toFloat()
            assertTrue("trunk is %.1f deg off vertical at p=%.3f".format(offVertical, frame.progress), offVertical <= 25f)
        }
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
        // A planted foot lies FLAT: heel and toe ride at their ankle's own height (the floor frame the
        // family authors its foot targets in). This is the measurement a flat plant produces across
        // the squat corpus; "on the floor" is not asserted against level 0 — no standing pose in this
        // rig puts the ankle joint on the ground plane (the foot definition's `ankleHeight` is 15).
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

    private fun toeOutAngleDeg(
        frame: PoseFrameSweep.Frame,
        ankle: Joint,
        toe: Joint,
        outwardSign: Float
    ): Float {
        val dx = frame[toe].x - frame[ankle].x
        val dz = frame[toe].z - frame[ankle].z
        if (abs(dx) < 1e-4f && abs(dz) < 1e-4f) return 0f
        val outward = dz * outwardSign
        if (outward <= 0f) return -1f
        return Math.toDegrees(atan2(outward.toDouble(), abs(dx).toDouble())).toFloat()
    }

    @Suppress("unused")
    private fun unusedSideGuard() = Side.RIGHT
}
