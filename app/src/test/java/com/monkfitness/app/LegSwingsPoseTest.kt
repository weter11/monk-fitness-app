package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.LegSwingsPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/**
 * Leg Swings (`leg_swings_hold`) — the exercise's own biomechanics on the PUBLISHED frame.
 *
 * ## Stated intent (there is no BPS and no copy for this exercise)
 *
 * `WorkoutGenerator` passes `R.string.ex_leg_swings` as *every* field, so the catalog entry is the
 * title and nothing else. Its identity is derived (recorded in the pose's KDoc) from the localized
 * name — `values-ru` *"Динамические махи ногами"*, `values-uk` *"Махи ногами"* = **dynamic leg
 * swings** — and from the catalog's own separation of the two neighbours it could be confused with
 * (`ex_hip_cars_desc`: the leg circle, *"not a swing"*; `hip_circles`: the pelvic circle).
 *
 * The assertions therefore measure a **pendulum**: a constant-radius arc of the swinging leg through
 * its own hip, crossing the vertical into both a forward and a back extreme, under a still pelvis and
 * a planted stance foot. Every reading is taken through [PoseFrameSweep] (the pipeline entered exactly
 * as `SkeletonRenderer` enters it), never on a raw `build()` result.
 */
class LegSwingsPoseTest {

    private val ID = "leg_swings_hold"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = LegSwingsPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.3f", v)

    /** The measured angle of the swinging leg from the vertical: `+` in front of the hip, `−` behind. */
    private fun swingAngleDeg(frame: PoseFrameSweep.Frame, hip: Joint, ankle: Joint): Float =
        Math.toDegrees(
            LegSwingsPose.angleFromVertical(
                frame[ankle].x - frame[hip].x,
                frame[ankle].y - frame[hip].y
            ).toDouble()
        ).toFloat()

    /** The coverage metric itself: this exercise must resolve to a REAL skeletal animation. */
    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(LegSwingsPose::class.java, cfg!!.builder.javaClass)
        assertTrue("$ID is not a deduplicated dedicated animation", ID in PoseRegistry.getDedicatedAnimationIds())
    }

    /**
     * The sweep is not aliased (T-7), and the pendulum's own periodicity is stated exactly: the seam
     * `progress 0 ≡ 1` is the SAME point of the arc, while every other sample is a distinct frame.
     */
    @Test
    fun theSweepSamplesDistinctFramesAndAClosedSeam() {
        val f = frames()
        assertTrue(
            "the published-frame sweep aliased (T-7 class): only ${PoseFrameSweep.distinctFrameCount(f)} " +
                "distinct frames for ${f.size} samples",
            PoseFrameSweep.distinctFrameCount(f) >= f.size - 1
        )
        // A pendulum passes through the same point on the way back: the loop's seam closes EXACTLY.
        for (joint in listOf(Joint.ANKLE_F, Joint.KNEE_F, Joint.HEEL_F, Joint.TOE_F)) {
            assertEquals(
                "the swing does not close its loop at $joint",
                0f, PoseFrameSweep.deviation(f, joint, 0, f.size - 1), 1e-3f
            )
        }
        // ... and the samples that are NOT the seam are genuinely different frames.
        assertTrue(
            "the mid-swing frame is the seam frame — the sweep measured one pose",
            PoseFrameSweep.deviation(f, Joint.ANKLE_F, 0, f.size / 2) >= 100f
        )
    }

    /**
     * THE exercise: a swing in ONE plane. The leg travels ~`218 u` fore/aft while its lateral position
     * never changes — a swing, not a fan and not a circle.
     */
    @Test
    fun theSwingLivesInASingleSagittalPlane() {
        val f = frames()
        val travelX = PoseFrameSweep.travel(f, Joint.ANKLE_F, 0)
        val travelZ = PoseFrameSweep.travel(f, Joint.ANKLE_F, 2)
        assertTrue(
            "the swing travels only ${fmt(travelX)} u fore/aft",
            travelX >= 180f
        )
        assertTrue(
            "the swing leaves its sagittal plane by ${fmt(travelZ)} u — a swing is one plane",
            travelZ <= 0.5f
        )
    }

    /** The pendulum crosses the vertical: a real forward extreme AND a real back extreme. */
    @Test
    fun theSwingReachesBothItsForwardAndItsBackExtreme() {
        val f = frames()
        val front = f.first()
        val back = f[f.size / 2]
        val frontAngle = swingAngleDeg(front, Joint.HIP_F, Joint.ANKLE_F)
        val backAngle = swingAngleDeg(back, Joint.HIP_F, Joint.ANKLE_F)

        assertEquals(
            "the forward extreme is %.2f deg, not the authored %.1f".format(frontAngle, LegSwingsPose.SWING_FORWARD_DEG),
            LegSwingsPose.SWING_FORWARD_DEG, frontAngle, 1f
        )
        assertEquals(
            "the back extreme is %.2f deg, not the authored −%.1f".format(backAngle, LegSwingsPose.SWING_BACK_DEG),
            -LegSwingsPose.SWING_BACK_DEG, backAngle, 1f
        )
        // The foot is genuinely in FRONT of the hip at one end and BEHIND it at the other.
        assertTrue(
            "the foot never gets in front of its own hip (worst %.2f u)".format(front[Joint.ANKLE_F].x - front[Joint.HIP_F].x),
            front[Joint.ANKLE_F].x - front[Joint.HIP_F].x >= 120f
        )
        assertTrue(
            "the foot never gets behind its own hip (best %.2f u)".format(back[Joint.ANKLE_F].x - back[Joint.HIP_F].x),
            back[Joint.ANKLE_F].x - back[Joint.HIP_F].x <= -70f
        )
        // and the foot rises in front and dips behind: the arc, not a translation.
        assertTrue(
            "the foot does not rise at the front extreme (front y %.2f vs back y %.2f)".format(front[Joint.ANKLE_F].y, back[Joint.ANKLE_F].y),
            front[Joint.ANKLE_F].y > back[Joint.ANKLE_F].y + 20f
        )
    }

    /** It is the HIP that swings: the leg keeps the chain's own length — no knee lift, no fold. */
    @Test
    fun theLegSwingsAtTheHipWithoutFoldingTheKnee() {
        val f = frames()
        val angles = f.map { PoseFrameSweep.interiorAngle(it, Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F) }
        assertTrue(
            "the swinging knee closes to %.2f deg — the leg is folding, not swinging".format(angles.min()),
            angles.min() >= 148f
        )
        assertTrue(
            "the swinging knee changes by %.2f deg across the arc (a swing keeps its length)".format(angles.max() - angles.min()),
            angles.max() - angles.min() <= 5f
        )
        for (frame in f) {
            val radius = Vector3().set(frame[Joint.ANKLE_F]).subtract(frame[Joint.HIP_F]).mag()
            assertEquals(
                "the swing's radius at p=%.3f is %.2f u, not the authored %.2f — the arc is not a pendulum".format(
                    frame.progress, radius, LegSwingsPose.SWING_RADIUS
                ),
                LegSwingsPose.SWING_RADIUS, radius, 0.5f
            )
        }
    }

    /** Stable supporting mechanics: the stance foot is planted and flat, and it does not creep. */
    @Test
    fun theStanceFootStaysPlantedAndFlat() {
        val f = frames()
        for (joint in listOf(Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)) {
            val worst = f.indices.maxOf { PoseFrameSweep.deviation(f, joint, 0, it) }
            assertTrue(
                "$joint slides %.3f u while the other leg swings (the support must not move)".format(worst),
                worst <= 0.5f
            )
        }
        // The declared contact is what makes the engine flatten the foot onto the floor it stands on.
        assertTrue(
            "the swinging leg's foot must NOT be declared as support",
            pose().metadata.support.contacts.map { it.point } == listOf(SupportPoint.RIGHT_FOOT)
        )
        for (frame in f) {
            assertTrue(
                "HEEL_B rides at y=%.3f against its ankle y=%.3f (not a flat plant)".format(frame[Joint.HEEL_B].y, frame[Joint.ANKLE_B].y),
                abs(frame[Joint.HEEL_B].y - frame[Joint.ANKLE_B].y) <= 0.75f
            )
            assertTrue(
                "TOE_B rides at y=%.3f against its ankle y=%.3f (not a flat plant)".format(frame[Joint.TOE_B].y, frame[Joint.ANKLE_B].y),
                abs(frame[Joint.TOE_B].y - frame[Joint.ANKLE_B].y) <= 0.75f
            )
        }
        // The supporting knee holds its own angle: the stance leg carries the body, it does not pump.
        val stanceKnee = f.map { PoseFrameSweep.interiorAngle(it, Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B) }
        assertTrue(
            "the supporting knee moves %.2f deg through the swing".format(stanceKnee.max() - stanceKnee.min()),
            stanceKnee.max() - stanceKnee.min() <= 3f
        )
    }

    /** The body the swing hangs from does not travel: a still pelvis under an upright trunk. */
    @Test
    fun thePelvisAndTrunkStayStillAndUpright() {
        val f = frames()
        for (joint in listOf(Joint.PELVIS, Joint.CHEST, Joint.HEAD_POS)) {
            assertTrue(
                "$joint drifts %.3f u during the swing".format(PoseFrameSweep.travel3D(f, joint)),
                PoseFrameSweep.travel3D(f, joint) <= 0.5f
            )
        }
        for (frame in f) {
            val trunk = Vector3().set(frame[Joint.CHEST]).subtract(frame[Joint.PELVIS])
            val horizontal = kotlin.math.sqrt(trunk.x * trunk.x + trunk.z * trunk.z)
            val tilt = Math.toDegrees(kotlin.math.atan2(horizontal.toDouble(), trunk.y.toDouble())).toFloat()
            assertTrue(
                "the trunk is %.1f deg off vertical at p=%.3f".format(tilt, frame.progress),
                tilt <= 5f
            )
        }
    }

    /** The swinging foot never touches the floor: the drill swings its leg clear of the ground. */
    @Test
    fun theSwingingFootNeverTouchesTheFloor() {
        val f = frames()
        for (frame in f) {
            val lowest = listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F).minOf { frame[it].y }
            assertTrue(
                "the swinging foot comes down to y=%.3f at p=%.3f".format(lowest, frame.progress),
                lowest >= 5f
            )
        }
        // and the swing is a real movement, not a nudge
        assertTrue(
            "the swinging ankle only travels %.2f u".format(PoseFrameSweep.travel3D(f, Joint.ANKLE_F)),
            PoseFrameSweep.travel3D(f, Joint.ANKLE_F) >= 200f
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

    /** The ground invariant: the athlete stands ON the floor. */
    @Test
    fun nothingGoesBelowTheFloor() {
        val (joint, clearance) = PoseFrameSweep.worstClearance(frames(), def, level = 0f)
        assertTrue("$joint is %.3f u below the floor".format(clearance), clearance >= -0.05f)
    }
}
