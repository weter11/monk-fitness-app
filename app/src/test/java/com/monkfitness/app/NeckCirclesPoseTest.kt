package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.NeckCirclesPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Neck Circles (`neck_circles_hold`) — the exercise's own cervical biomechanics on the PUBLISHED frame.
 *
 * ## Stated intent (there is no BPS and no exercise copy)
 *
 * `WorkoutGenerator` passes `R.string.ex_neck_circles` as *every* field, so the catalog entry is the
 * title and nothing else; the identity is that title across all three locales — *"Neck Circles"*,
 * `values-ru` *"Круговые движения головой"*, `values-uk` *"Обертания головою"* — i.e. a controlled
 * circle of the head, filed as a `neck_mobility` timer drill.
 *
 * The assertions below therefore measure a **cervical cone**: both bones of the chain lie on one
 * direction that advances through a full turn, so the neck's tip rolls on a circle of radius
 * `neckLength · sin θ` while the head's centre rides a bigger one (`(neckLength + 18) · sin θ`) at a
 * **constant height** — and nothing outside the chain moves at all, which is what separates a neck
 * circle from a whole-body roll.
 *
 * Every reading is taken through [PoseFrameSweep] (the pipeline entered exactly as `SkeletonRenderer`
 * enters it), never on a raw `build()` result.
 */
class NeckCirclesPoseTest {

    private val ID = "neck_circles_hold"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = NeckCirclesPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.4f", v)

    private fun angleDeg(a: Vector3, b: Vector3): Float = Math.toDegrees(
        acos(((a.x * b.x + a.y * b.y + a.z * b.z) / (a.mag() * b.mag())).coerceIn(-1f, 1f).toDouble())
    ).toFloat()

    private fun neckDir(f: PoseFrameSweep.Frame) = Vector3().set(f[Joint.NECK_END]).subtract(f[Joint.CHEST])

    private fun headDir(f: PoseFrameSweep.Frame) = Vector3().set(f[Joint.HEAD_POS]).subtract(f[Joint.NECK_END])

    /** The locus centre of [joint] measured from the published samples (the bounding box's centre). */
    private fun centreOf(f: List<PoseFrameSweep.Frame>, joint: Joint): Pair<Float, Float> {
        val xs = f.map { it[joint].x }
        val zs = f.map { it[joint].z }
        return (xs.max() + xs.min()) / 2f to (zs.max() + zs.min()) / 2f
    }

    /** The coverage metric itself: this exercise must resolve to a REAL skeletal animation. */
    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(NeckCirclesPose::class.java, cfg!!.builder.javaClass)
        assertTrue("$ID is not a deduplicated dedicated animation", ID in PoseRegistry.getDedicatedAnimationIds())
    }

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        // Measured 9 of 9: every sample of the circle publishes its own geometry (the seam pair p=0/p=1
        // differs only by `sin 2π` float noise). A sweep that collapsed to one frame is the T-7 aliasing
        // signature and would make every assertion below vacuous.
        assertTrue(
            "the published-frame sweep aliased: only ${PoseFrameSweep.distinctFrameCount(f)} of ${f.size} " +
                "samples are distinct",
            PoseFrameSweep.distinctFrameCount(f) >= f.size - 1
        )
    }

    /** THE exercise: the head's centre traces a horizontal circle about the neck's base. */
    @Test
    fun theHeadTracesACircleInTheHorizontalPlane() {
        val f = frames()
        val (centreX, centreZ) = centreOf(f, Joint.HEAD_POS)
        val radius = NeckCirclesPose.headCircleRadius(def)
        for (frame in f) {
            val dx = frame[Joint.HEAD_POS].x - centreX
            val dz = frame[Joint.HEAD_POS].z - centreZ
            assertEquals(
                "the head is ${fmt(sqrt(dx * dx + dz * dz))} u from the circle's centre at p=${fmt(frame.progress)} " +
                    "(authored radius ${fmt(radius)})",
                radius, sqrt(dx * dx + dz * dz), 0.5f
            )
        }
        assertEquals(
            "the locus is an ellipse: X travel ${fmt(PoseFrameSweep.travel(f, Joint.HEAD_POS, 0))} u vs " +
                "Z travel ${fmt(PoseFrameSweep.travel(f, Joint.HEAD_POS, 2))} u",
            PoseFrameSweep.travel(f, Joint.HEAD_POS, 0), PoseFrameSweep.travel(f, Joint.HEAD_POS, 2), 0.5f
        )
        assertEquals(
            "the head's circle is not the authored diameter",
            2f * radius, PoseFrameSweep.travel(f, Joint.HEAD_POS, 0), 0.5f
        )
        // The circle is HORIZONTAL: the head's height never changes (a roll would change it).
        assertEquals(
            "the head's height changes by ${fmt(PoseFrameSweep.travelY(f, Joint.HEAD_POS))} u — the circle " +
                "is not horizontal",
            0f, PoseFrameSweep.travelY(f, Joint.HEAD_POS), 0.05f
        )
    }

    /** The whole cervical chain lies on that cone: both bones share one direction at every phase. */
    @Test
    fun bothCervicalBonesLieOnTheSameCone() {
        for (frame in frames()) {
            assertEquals(
                "the neck's bone is ${fmt(angleDeg(neckDir(frame), Vector3(0f, 1f, 0f)))} deg off vertical at " +
                    "p=${fmt(frame.progress)} against the authored cone ${fmt(Math.toDegrees(NeckCirclesPose.CONE_RAD.toDouble()).toFloat())} deg",
                0f, angleDeg(neckDir(frame), Vector3(0f, 1f, 0f)) - Math.toDegrees(NeckCirclesPose.CONE_RAD.toDouble()).toFloat(),
                0.05f
            )
            assertEquals(
                "the head's bone is not on the same cone as the neck's at p=${fmt(frame.progress)}",
                0f, angleDeg(headDir(frame), neckDir(frame)), 0.05f
            )
        }
    }

    /** A circle, not a there-and-back line: the azimuth advances through one full turn in one direction. */
    @Test
    fun theConeIsTraversedThroughAFullTurnInOneDirection() {
        val f = frames()
        val angles = f.map {
            Math.toDegrees(
                atan2(
                    (it[Joint.HEAD_POS].z - it[Joint.NECK_END].z).toDouble(),
                    (it[Joint.HEAD_POS].x - it[Joint.NECK_END].x).toDouble()
                )
            ).toFloat()
        }
        var total = 0f
        var direction = 0
        for (i in 1 until angles.size) {
            var step = angles[i] - angles[i - 1]
            while (step > 180f) step -= 360f
            while (step < -180f) step += 360f
            assertTrue(
                "the cone's azimuth step ${fmt(step)} deg at sample $i is not an even eighth turn",
                abs(step) >= 25f && abs(step) <= 65f
            )
            if (direction == 0) direction = if (step > 0f) 1 else -1
            assertTrue(
                "the circle reverses direction at sample $i (step ${fmt(step)})",
                (if (step > 0f) 1 else -1) == direction
            )
            total += step
        }
        assertTrue(
            "the sweep covered only ${fmt(total)} deg — a full turn is 360",
            abs(abs(total) - 360f) <= 45f
        )
    }

    /** The neck's own tip rolls at exactly half the head's radius (both bones the same length). */
    @Test
    fun theNeckTipRollsAtTheInnerRadiusAndTheHeadAtTheOuter() {
        val f = frames()
        val headRadius = NeckCirclesPose.headCircleRadius(def)
        val (nx, nz) = centreOf(f, Joint.NECK_END)
        for (frame in f) {
            val dx = frame[Joint.NECK_END].x - nx
            val dz = frame[Joint.NECK_END].z - nz
            assertEquals(
                "the neck's tip rides ${fmt(sqrt(dx * dx + dz * dz))} u from the circle's centre at " +
                    "p=${fmt(frame.progress)} — the chain's inner circle is half the head's radius",
                headRadius / 2f, sqrt(dx * dx + dz * dz), 0.5f
            )
        }
    }

    /** The drill is cervical: nothing outside the chain may move (no whole-body circle). */
    @Test
    fun nothingOutsideTheCervicalChainMoves() {
        val f = frames()
        val still = listOf(
            Joint.PELVIS, Joint.CHEST, Joint.SHOULDER_A, Joint.SHOULDER_P,
            Joint.ELBOW_A, Joint.HAND_A, Joint.ELBOW_P, Joint.HAND_P,
            Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F,
            Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B
        )
        for (joint in still) {
            assertEquals(
                "$joint moves ${fmt(PoseFrameSweep.travel3D(f, joint))} u — a neck circle moves the head, " +
                    "not the body",
                0f, PoseFrameSweep.travel3D(f, joint), 0.05f
            )
        }
    }

    /** A *circles* drill is not a neck stretch: the amplitude stays low (the task's own band). */
    @Test
    fun theCirclesStayLowAmplitude() {
        val radius = NeckCirclesPose.headCircleRadius(def)
        assertTrue(
            "the authored head circle (radius ${fmt(radius)} u) is not a low-amplitude cervical movement",
            radius <= 12f
        )
        assertTrue(
            "the head does not circle at all (radius ${fmt(radius)} u)",
            radius >= 5f
        )
        // ... and the head's excursion is exactly the authored `(neckLength + 18) · sin θ`, i.e. a
        // fraction of the rig's own head bone (18 u) rather than a limb-sized move.
        assertEquals(
            "the head's circle radius is not the authored `(neckLength + 18) · sin θ`",
            (def.neckLength + 18f) * sin(NeckCirclesPose.CONE_RAD), radius, 1e-3f
        )
        val f = frames()
        assertEquals(
            "the head's published excursion is not the authored circle's diameter",
            2f * radius, PoseFrameSweep.travel3D(f, Joint.HEAD_POS), 0.5f
        )
    }

    /** The chain's own anatomy is preserved: both cervical bones keep their authored lengths. */
    @Test
    fun theCervicalBonesKeepTheirLengths() {
        for (frame in frames()) {
            assertEquals(
                "CHEST→NECK_END is ${fmt(neckDir(frame).mag())} u at p=${fmt(frame.progress)}",
                def.neckLength, neckDir(frame).mag(), 0.05f
            )
            assertEquals(
                "NECK_END→HEAD_POS is ${fmt(headDir(frame).mag())} u at p=${fmt(frame.progress)}",
                18f, headDir(frame).mag(), 0.05f
            )
        }
    }

    /** The reach contract: every authored target is inside its own chain's reachable band. */
    @Test
    fun everyAuthoredTargetIsInsideItsChainsReach() {
        for (frame in frames()) {
            assertEquals(
                "the solver relocated a limb at p=${fmt(frame.progress)} (clamp ${fmt(frame.maxIkClampAmount)})",
                0f, frame.maxIkClampAmount, 1e-4f
            )
        }
    }

    /** The athlete stands on the floor: flat, planted feet and nothing below the ground. */
    @Test
    fun theAthleteStandsFlatOnTheFloor() {
        val f = frames()
        val (joint, clearance) = PoseFrameSweep.worstClearance(f, def, level = 0f)
        assertTrue("$joint is ${fmt(clearance)} u below the floor", clearance >= -0.05f)
        for (joint in listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)) {
            assertEquals(
                "the planted $joint slides ${fmt(PoseFrameSweep.travel3D(f, joint))} u",
                0f, PoseFrameSweep.travel3D(f, joint), 0.05f
            )
        }
        for (frame in f) {
            for ((heel, toe, ankle) in listOf(
                Triple(Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_F),
                Triple(Joint.HEEL_B, Joint.TOE_B, Joint.ANKLE_B)
            )) {
                assertEquals(
                    "$heel rides ${fmt(abs(frame[heel].y - frame[ankle].y))} u off its ankle (not a flat plant)",
                    0f, abs(frame[heel].y - frame[ankle].y), 0.75f
                )
                assertEquals(
                    "$toe rides ${fmt(abs(frame[toe].y - frame[ankle].y))} u off its ankle (not a flat plant)",
                    0f, abs(frame[toe].y - frame[ankle].y), 0.75f
                )
            }
        }
    }

    /** The declared base of support reaches the published frame (R8/B-5, the hero's own model). */
    @Test
    fun theDeclaredSupportReachesThePublishedFrame() {
        val f = frames()
        val declared = pose().metadata.support.supportPoints
        assertEquals(
            "the declaration is not the planted-feet base this drill stands on",
            setOf(SupportPoint.LEFT_FOOT, SupportPoint.RIGHT_FOOT), declared
        )
        for (frame in f) {
            assertEquals(
                "the published frame at p=${fmt(frame.progress)} does not carry the declared support model",
                declared, frame.supportedPoints
            )
        }
        val stance = Vector3().set(f.first()[Joint.ANKLE_F]).subtract(f.first()[Joint.ANKLE_B])
        assertTrue("the feet are ${fmt(stance.mag())} u apart — not a stance", stance.mag() > 2f * def.hipWidth)
    }

    /**
     * The head's circle is centred on the neck's own base column: the body does not carry it, and the
     * locus is symmetric about the mid-line (`X` and `Z` both centred on `0`).
     */
    @Test
    fun theCircleIsCentredOnTheNecksOwnColumn() {
        val f = frames()
        val (centreX, centreZ) = centreOf(f, Joint.HEAD_POS)
        assertEquals("the head's circle is not centred on the neck's column (X)", 0f, centreX, 0.5f)
        assertEquals("the head's circle is not centred on the neck's column (Z)", 0f, centreZ, 0.5f)
        // The cone's own height: the head sits `(neckLength + 18) · cos θ` above the chain's base at
        // EVERY phase (a roll would move it) — the published form of "the circle is horizontal".
        assertEquals(
            "the head's height above the chain's base is not the authored cone's",
            cos(NeckCirclesPose.CONE_RAD) * (def.neckLength + 18f),
            f.first()[Joint.HEAD_POS].y - f.first()[Joint.CHEST].y,
            0.5f
        )
    }
}
