package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.ChinTuckPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Chin Tucks (`chin_tuck_standard`) — the exercise's own cervical biomechanics on the PUBLISHED frame.
 *
 * ## Stated intent (its own copy — the only specification in the repository; there is no BPS)
 *
 * `desc` *"A posture drill that trains the deep neck flexors and stacks the head over the ribcage. The
 * move is small but precise."* — `steps` *"1. Stand tall or lie on your back. 2. Look straight ahead.
 * 3. Gently draw the chin straight back as if making a double chin. 4. Hold the end position briefly
 * without tipping the head up or down. 5. Relax and repeat."* — `tech` *"Think back, not down …"* —
 * `mistakes` *"Tilting the head toward the floor. Poking the chin forward between reps. Shrugging the
 * shoulders."*
 *
 * The assertions below measure a **retraction**: the neck's own bone turns posteriorly by the authored
 * angle while the head's bone stays the chain's vertical axis, the head therefore travels straight back
 * and never forward, and **nothing outside the cervical chain moves at all** (the whole body is still —
 * that is the "without translating the whole body" property of a neck drill, and it is the misreading
 * this class exists to catch: a whole-body "nod" or a shoulder shrug).
 *
 * Every reading is taken through [PoseFrameSweep] (the pipeline entered exactly as `SkeletonRenderer`
 * enters it), never on a raw `build()` result.
 */
class ChinTuckPoseTest {

    private val ID = "chin_tuck_standard"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = ChinTuckPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.4f", v)

    private fun angleDeg(a: Vector3, b: Vector3): Float = Math.toDegrees(
        acos(((a.x * b.x + a.y * b.y + a.z * b.z) / (a.mag() * b.mag())).coerceIn(-1f, 1f).toDouble())
    ).toFloat()

    /** The neck's published bone direction (`CHEST → NECK_END`) at a frame. */
    private fun neckDir(f: PoseFrameSweep.Frame) = Vector3().set(f[Joint.NECK_END]).subtract(f[Joint.CHEST])

    /** The head's published bone direction (`NECK_END → HEAD_POS`) at a frame. */
    private fun headDir(f: PoseFrameSweep.Frame) = Vector3().set(f[Joint.HEAD_POS]).subtract(f[Joint.NECK_END])

    /** The coverage metric itself: this exercise must resolve to a REAL skeletal animation. */
    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(ChinTuckPose::class.java, cfg!!.builder.javaClass)
        assertTrue("$ID is not a deduplicated dedicated animation", ID in PoseRegistry.getDedicatedAnimationIds())
    }

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        // The drill is symmetric about the rep's seam (p = 0 ≡ 1) and its hold plateau repeats, so the
        // rhythm's own frame count is asserted — measured 5 of the 9 samples. A sweep that collapsed to
        // ONE frame would be the T-7 aliasing signature and every travel assertion below would be
        // vacuous; a rhythm change (a moving hold) must be re-measured here rather than absorbed.
        assertEquals(
            "the published frames of the cycle changed — re-measure the rhythm",
            5, PoseFrameSweep.distinctFrameCount(f)
        )
    }

    /** THE exercise: the head is drawn straight BACK — the neck's own bone turns posteriorly. */
    @Test
    fun theNeckBoneTurnsPosteriorlyAndDrawsTheHeadBack() {
        val f = frames()
        // The neck's bone tilt is the authored angle at every phase (never a body-wide lean).
        for (frame in f) {
            val authored = Math.toDegrees(ChinTuckPose.neckTilt(frame.progress).toDouble()).toFloat()
            assertEquals(
                "the neck is ${fmt(angleDeg(neckDir(frame), Vector3(0f, 1f, 0f)))} deg off vertical at " +
                    "p=${fmt(frame.progress)} against the authored ${fmt(authored)} deg",
                0f, angleDeg(neckDir(frame), Vector3(0f, 1f, 0f)) - authored, 1e-2f
            )
        }
        // ... and the head's BASE travels posteriorly, monotonically, in the sagittal plane.
        val hold = f.last { it.progress <= ChinTuckPose.HOLD_END }
        assertEquals(
            "the head's base does not travel the authored posterior distance at the hold",
            -ChinTuckPose.retractionTravel(ChinTuckPose.HOLD_START), hold[Joint.NECK_END].x, 0.5f
        )
        for (frame in f) {
            assertEquals(
                "the retraction left the sagittal plane at p=${fmt(frame.progress)} (z ${fmt(frame[Joint.NECK_END].z)})",
                0f, frame[Joint.NECK_END].z, 1e-3f
            )
        }
        // The motion is monotone on the way in: the head's base never comes forward during the ramp.
        val ramp = f.filter { it.progress <= ChinTuckPose.HOLD_START }
        for (i in 1 until ramp.size) {
            assertTrue(
                "the head comes forward again inside the entry ramp (p=${fmt(ramp[i].progress)})",
                ramp[i][Joint.NECK_END].x <= ramp[i - 1][Joint.NECK_END].x + 1e-3f
            )
        }
    }

    /** `steps` §4: *"without tipping the head up or down"* — the head's own bone stays the vertical axis. */
    @Test
    fun theHeadIsRetractedWithoutTipping() {
        for (frame in frames()) {
            assertEquals(
                "the head's own axis tips ${fmt(angleDeg(headDir(frame), Vector3(0f, 1f, 0f)))} deg off " +
                    "vertical at p=${fmt(frame.progress)} while the neck carries the tilt — that is a nod",
                0f, angleDeg(headDir(frame), Vector3(0f, 1f, 0f)), 0.5f
            )
        }
    }

    /** `mistakes` *"Tilting the head toward the floor"* — the head only ever goes back, never forward/down. */
    @Test
    fun theHeadNeverPokesForwardOrDropsTowardTheFloor() {
        val f = frames()
        val neutral = f.first()
        for (frame in f) {
            assertTrue(
                "the head is ${fmt(frame[Joint.HEAD_POS].x - neutral[Joint.HEAD_POS].x)} u FORWARD of its " +
                    "neutral position at p=${fmt(frame.progress)} (the copy's own mistake)",
                frame[Joint.HEAD_POS].x <= neutral[Joint.HEAD_POS].x + 0.1f
            )
            assertTrue(
                "the head drops ${fmt(neutral[Joint.HEAD_POS].y - frame[Joint.HEAD_POS].y)} u toward the floor " +
                    "at p=${fmt(frame.progress)} — a `24`-degree rigid tilt costs at most 1.57 u",
                neutral[Joint.HEAD_POS].y - frame[Joint.HEAD_POS].y <= def.neckLength * (1f - kotlin.math.cos(ChinTuckPose.RETRACTION_RAD)) + 0.05f
            )
        }
    }

    /** The move is *"small but precise"*: the retraction is real AND low-amplitude. */
    @Test
    fun theRetractionIsRealAndLowAmplitude() {
        val f = frames()
        val travel = PoseFrameSweep.travel3D(f, Joint.NECK_END)
        assertTrue(
            "the head's base travels only ${fmt(travel)} u — the retraction is not there",
            travel >= 5f
        )
        assertTrue(
            "the retraction travels ${fmt(travel)} u — a chin tuck is a small movement, not a whole-body move",
            travel <= 10f
        )
        // ... and it is a fraction of the rig's own head bone, not a limb-sized sweep.
        assertTrue(
            "the retraction (${fmt(travel)} u) is not small next to the head bone (18 u)",
            travel <= 18f * 0.6f
        )
    }

    /** `steps` §4 *"Hold the end position briefly"* — the plateau is measurably flat, then released. */
    @Test
    fun theHoldPlateauIsFlatAndReleased() {
        val f = frames()
        val plateau = f.filter { it.progress >= ChinTuckPose.HOLD_START && it.progress <= ChinTuckPose.HOLD_END }
        assertTrue("the sweep must sample the hold plateau (got ${plateau.size})", plateau.size >= 3)
        for (joint in listOf(Joint.NECK_END, Joint.HEAD_POS)) {
            val worst = plateau.indices.maxOf { PoseFrameSweep.deviation(plateau, joint, 0, it) }
            assertEquals(
                "$joint drifts ${fmt(worst)} u inside the hold plateau — the copy asks for a HOLD",
                0f, worst, 0.01f
            )
        }
        // The release returns the body to its neutral state exactly (the loop seam).
        assertEquals(
            "the cycle does not close (HEAD_POS x ${fmt(f.first()[Joint.HEAD_POS].x)} → ${fmt(f.last()[Joint.HEAD_POS].x)})",
            0f, abs(f.first()[Joint.HEAD_POS].x - f.last()[Joint.HEAD_POS].x), 1e-3f
        )
    }

    /** A neck drill moves the NECK: nothing outside the cervical chain may translate. */
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
                "$joint moves ${fmt(PoseFrameSweep.travel3D(f, joint))} u — the drill moves the head, " +
                    "not the body",
                0f, PoseFrameSweep.travel3D(f, joint), 0.05f
            )
        }
    }

    /** `mistakes` *"Shrugging the shoulders"* — the girdle's height never changes. */
    @Test
    fun theShouldersNeverShrug() {
        val f = frames()
        for (joint in listOf(Joint.SHOULDER_A, Joint.SHOULDER_P)) {
            assertEquals(
                "$joint rises ${fmt(PoseFrameSweep.travelY(f, joint))} u during the retraction (a shrug)",
                0f, PoseFrameSweep.travelY(f, joint), 0.05f
            )
        }
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

    /** The declared base of support is the one the published frame carries (R8/B-5, the hero's model). */
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
        // ... and the stance is a real one: the feet are separated laterally, symmetric about the mid-line.
        assertEquals("the stance is not symmetric about the mid-line", 0f, abs(f.first()[Joint.ANKLE_F].z + f.first()[Joint.ANKLE_B].z), 0.05f)
        assertTrue("the feet are ${fmt(stance.mag())} u apart — not a stance", stance.mag() > 2f * def.hipWidth)
        assertTrue(
            "the hips are not over the base of support",
            sqrt(f.first()[Joint.PELVIS].x * f.first()[Joint.PELVIS].x + f.first()[Joint.PELVIS].z * f.first()[Joint.PELVIS].z) < 0.1f
        )
    }
}
