package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.JumpingJacksPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Jumping Jacks (`jumping_jack_standard`) — the exercise's own biomechanics on the PUBLISHED frame.
 *
 * ## Stated intent (the only in-repo statements of it)
 *
 * **There is no BPS and no exercise copy**: `WorkoutGenerator` passes `R.string.ex_jumping_jacks` as
 * every field, so the catalog entry is the title (`"Jumping Jacks"` / `"Джампинг Джек"` / `"Стрибки"`)
 * and the one illustration that shows it (`ExerciseSkeletonData`) — a **two-frame open/close cycle**
 * whose *closed* frame has the hands at the hips with the toes together and whose *open* frame has the
 * hands overhead (`0.19`, above the head at `0.20`) with the toes wide apart. The authored pose is that
 * pair of shapes plus the *jump* that connects them, which is the one thing the name states and the
 * illustration cannot show.
 *
 * The assertions below therefore measure, on the published frame: **two extremes that are the
 * illustration's own shapes** (hands at the hips / hands above the head, feet together / feet wide), the
 * **coordinated arrival** at those extremes (the arms and the legs are driven by one signal, so they
 * open and close together and reach the extreme at the touch-down), the **two flights** (the feet really
 * leave the floor, twice, with the legs tucking under the rising body), and the **absorbed landing**
 * (the pelvis sinks and the knees flex *after* the feet arrive, with the toes pointed through the flight
 * and flat on landing).
 *
 * Every reading is taken through [PoseFrameSweep] (the pipeline entered exactly as `SkeletonRenderer`
 * enters it), never on a raw `build()` result.
 */
class JumpingJacksPoseTest {

    private val ID = "jumping_jack_standard"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = JumpingJacksPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.4f", v)

    private fun frameAt(f: List<PoseFrameSweep.Frame>, progress: Float): PoseFrameSweep.Frame =
        f.first { abs(it.progress - progress) < 1e-6f }

    /** The stance's separation (the two ankles' lateral distance) on a published frame. */
    private fun stance(fr: PoseFrameSweep.Frame): Float = abs(fr[Joint.ANKLE_F].z - fr[Joint.ANKLE_B].z)

    private fun handOffset(fr: PoseFrameSweep.Frame): Vector3 =
        Vector3().set(fr[Joint.HAND_A]).subtract(fr[Joint.SHOULDER_A])

    /** The coverage metric itself: this exercise must resolve to a REAL skeletal animation. */
    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(JumpingJacksPose::class.java, cfg!!.builder.javaClass)
        assertTrue("$ID is not a deduplicated dedicated animation", ID in PoseRegistry.getDedicatedAnimationIds())
    }

    @Test
    fun theSweepSamplesTheCyclesOwnDistinctFrames() {
        // The drill is symmetric about its two hops (an opening half and a closing half that pass
        // through the SAME shapes: p = ⅛ ≡ ⅞, ¼ ≡ ¾, ⅜ ≡ ⅝, and the seam p = 0 ≡ 1), so the rhythm's own
        // frame count is 7 of the 9 samples. A sweep that collapsed to ONE frame would be the T-7
        // aliasing signature and every travel assertion below would be vacuous.
        assertEquals(
            "the published frames of the cycle changed — re-measure the rhythm",
            7, PoseFrameSweep.distinctFrameCount(frames())
        )
    }

    /**
     * The illustration's own two shapes: **closed** (hands at the hips, feet together) and **open**
     * (hands above the head, feet wide). Measured: the stance is `24.200 u` closed and `105.600 u` open,
     * symmetric about the mid-line at every phase.
     */
    @Test
    fun theTwoExtremesAreTheIllustrationsOwnShapes() {
        val f = frames()
        val closed = frameAt(f, 0f)
        val open = frameAt(f, 0.5f)
        assertEquals(
            "the closed shape's stance is ${fmt(stance(closed))} u (the illustration's toes are together)",
            0f, stance(closed) - 2f * JumpingJacksPose.stanceHalfWidth(def, 0f), 0.05f
        )
        assertEquals(
            "the open shape's stance is ${fmt(stance(open))} u (the illustration's toes are wide apart)",
            0f, stance(open) - 2f * JumpingJacksPose.stanceHalfWidth(def, 1f), 0.05f
        )
        assertTrue(
            "the open stance is only ${fmt(stance(open))} u — not a wide one",
            stance(open) > 3f * stance(closed)
        )
        // hands at the hips ...
        assertTrue(
            "the closed shape's hands are at ${fmt(closed[Joint.HAND_A].y)} against the hips at " +
                "${fmt(closed[Joint.PELVIS].y)} — the illustration puts them at the hips",
            closed[Joint.HAND_A].y <= closed[Joint.PELVIS].y + 20f &&
                closed[Joint.HAND_A].y >= closed[Joint.PELVIS].y - 60f
        )
        // ... and above the head.
        assertTrue(
            "the open shape's hands (${fmt(open[Joint.HAND_A].y)}) are not above the head " +
                "(${fmt(open[Joint.HEAD_POS].y)})",
            open[Joint.HAND_A].y > open[Joint.HEAD_POS].y + 20f
        )
        // ... and the outward sweep is lateral, not forward: the drill lives in the frontal plane.
        for (frame in f) {
            assertEquals(
                "the hand leaves the shoulder's frontal plane at p=${fmt(frame.progress)} " +
                    "(x offset ${fmt(handOffset(frame).x)})",
                0f, handOffset(frame).x, 0.05f
            )
        }
    }

    /**
     * The arms sweep from the sides to overhead through the lateral extreme: measured offsets
     * `(0, −136, 0)` closed → `(0, 0, ∓136)` level → `(0, +136, 0)` open, with a constant radius of
     * `136.000 u` (so the elbows cannot bend through the sweep).
     */
    @Test
    fun theArmsSweepFromTheSidesToOverheadThroughTheLevelPosition() {
        val f = frames()
        val r = JumpingJacksPose.ARM_RADIUS
        val closed = handOffset(frameAt(f, 0f))
        assertEquals("the closed shape's hands do not hang at the sides (${fmt(closed.y)} u)", -r, closed.y, 0.05f)
        val open = handOffset(frameAt(f, 0.5f))
        assertEquals("the open shape's hands are not overhead (${fmt(open.y)} u)", r, open.y, 0.05f)
        // The apexes of the two flights are the level, lateral extreme: the hands are out at the sides.
        for (p in floatArrayOf(0.25f, 0.75f)) {
            val o = handOffset(frameAt(f, p))
            assertEquals(
                "at the flight apex p=${fmt(p)} the hand is ${fmt(o.y)} u off the shoulder's height — the " +
                    "sweep should pass LEVEL with the shoulders there",
                0f, o.y, 0.05f
            )
            assertEquals(
                "at the flight apex p=${fmt(p)} the hand is ${fmt(abs(o.z))} u out laterally",
                0f, abs(o.z) - r, 0.05f
            )
        }
        for (frame in f) {
            assertEquals(
                "the arm's radius is ${fmt(handOffset(frame).mag())} u at p=${fmt(frame.progress)} against the " +
                    "authored ${fmt(r)} u — the elbow is cheating the swing",
                0f, handOffset(frame).mag() - r, 0.05f
            )
        }
    }

    /**
     * The drill's coordination: the arms and the legs are read from one signal, so both are at their
     * extreme — and the feet are ON the floor — at each stance, and both extremes are held while the
     * body is grounded. That is what makes this a jack rather than two independent movements.
     */
    @Test
    fun theArmsAndLegsArriveAtBothExtremesTogetherAndGrounded() {
        val f = frames()
        for (progress in floatArrayOf(0f, 0.5f, 1f)) {
            val fr = frameAt(f, progress)
            val o = handOffset(fr)
            val open = JumpingJacksPose.openness(progress)
            val wantedArm = -JumpingJacksPose.ARM_RADIUS + 2f * JumpingJacksPose.ARM_RADIUS * open
            assertEquals(
                "at the stance p=${fmt(progress)} the arms read ${fmt(o.y)} u but the cycle's own openness " +
                    "(${fmt(open)}) asks for ${fmt(wantedArm)} u",
                0f, o.y - wantedArm, 0.05f
            )
            assertEquals(
                "at the stance p=${fmt(progress)} the stance reads ${fmt(stance(fr))} u but the cycle's own " +
                    "openness (${fmt(open)}) asks for ${fmt(2f * JumpingJacksPose.stanceHalfWidth(def, open))} u",
                0f, stance(fr) - 2f * JumpingJacksPose.stanceHalfWidth(def, open), 0.05f
            )
            assertEquals(
                "the feet are airborne (${fmt(fr[Joint.ANKLE_F].y)} u) at the stance p=${fmt(progress)} — the " +
                    "extremes must be reached with the feet ON the floor",
                0f, fr[Joint.ANKLE_F].y - JumpingJacksPose.FLOOR_ANKLE_Y, 0.05f
            )
        }
    }

    /** The hops: two flights per cycle, the feet exactly on the floor at every grounded window. */
    @Test
    fun theFeetLeaveTheFloorInTwoFlights() {
        val f = frames()
        for (fr in f) {
            val expected = JumpingJacksPose.flightArc(fr.progress) * JumpingJacksPose.FLIGHT_LIFT
            assertEquals(
                "the ankles are ${fmt(fr[Joint.ANKLE_F].y)} u high at p=${fmt(fr.progress)} against the flight's " +
                    "own ${fmt(expected)} u",
                0f, fr[Joint.ANKLE_F].y - (JumpingJacksPose.FLOOR_ANKLE_Y + expected), 0.05f
            )
            assertEquals(
                "the two ankles are not at the same height at p=${fmt(fr.progress)}",
                0f, fr[Joint.ANKLE_F].y - fr[Joint.ANKLE_B].y, 0.05f
            )
        }
        // Two separated airborne windows, with the grounded stance between them.
        val airborne = JumpingJacksPose.FLOOR_ANKLE_Y + JumpingJacksPose.FLIGHT_LIFT
        assertEquals(
            "the first flight's ankles are at ${fmt(frameAt(f, 0.25f)[Joint.ANKLE_F].y)} u",
            airborne, frameAt(f, 0.25f)[Joint.ANKLE_F].y, 0.05f
        )
        assertEquals(
            "the second flight's ankles are at ${fmt(frameAt(f, 0.75f)[Joint.ANKLE_F].y)} u",
            airborne, frameAt(f, 0.75f)[Joint.ANKLE_F].y, 0.05f
        )
        assertTrue(
            "the hop rises only ${fmt(PoseFrameSweep.travelY(f, Joint.ANKLE_F))} u — not a jump",
            PoseFrameSweep.travelY(f, Joint.ANKLE_F) > 10f
        )
        // ... and the body itself rises with it: the pelvis is higher at the apex than at the stance.
        assertTrue(
            "the pelvis is at ${fmt(frameAt(f, 0.25f)[Joint.PELVIS].y)} at the apex and " +
                "${fmt(frameAt(f, 0f)[Joint.PELVIS].y)} in the stance — the body is not jumping",
            frameAt(f, 0.25f)[Joint.PELVIS].y > frameAt(f, 0f)[Joint.PELVIS].y
        )
    }

    /**
     * The landing is a landing: the pelvis sinks and the knees flex *after* the feet are down, and the
     * feet dorsiflex as the shin comes over them. Measured: the pelvis `226` at the touch-down, `212`
     * through the stance; the knee `146.716°` closed → `128.848°` under load.
     */
    @Test
    fun theLandingIsAbsorbedAfterTheFeetArrive() {
        val f = frames()
        for (fr in f) {
            val expected = (JumpingJacksPose.FLIGHT_RISE * JumpingJacksPose.flightArc(fr.progress) -
                JumpingJacksPose.LANDING_ABSORB * JumpingJacksPose.absorbArc(fr.progress))
            assertEquals(
                "the pelvis reads ${fmt(fr[Joint.PELVIS].y)} at p=${fmt(fr.progress)} against the cycle's own " +
                    "${fmt(JumpingJacksPose.STANCE_PELVIS_Y + expected)}",
                0f, fr[Joint.PELVIS].y - (JumpingJacksPose.STANCE_PELVIS_Y + expected), 0.05f
            )
        }
        val touchDown = frameAt(f, 0f)[Joint.PELVIS].y
        val loaded = frameAt(f, 0.5f)[Joint.PELVIS].y
        assertTrue(
            "the pelvis does not sink under the landing ($touchDown → $loaded)",
            loaded < touchDown - 5f
        )
        val kneeAtTouchDown = PoseFrameSweep.interiorAngle(frameAt(f, 0.375f), Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F)
        val kneeLoaded = PoseFrameSweep.interiorAngle(frameAt(f, 0.5f), Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F)
        assertTrue(
            "the knee reads ${fmt(kneeLoaded)}° under load against ${fmt(kneeAtTouchDown)}° arriving — the landing " +
                "is not absorbed",
            kneeLoaded < kneeAtTouchDown
        )
        assertTrue(
            "the loaded knee is only ${fmt(kneeLoaded)}° — a landing absorbs into a real knee bend",
            kneeLoaded < 140f
        )
    }

    /** The feet: pointed through the flight, flat on the floor at the closed shape's stance. */
    @Test
    fun theFeetArePointedThroughTheFlightAndFlatOnLanding() {
        val f = frames()
        for (fr in f) {
            val expected = JumpingJacksPose.ankleDorsiflexion(
                JumpingJacksPose.flightArc(fr.progress), JumpingJacksPose.absorbArc(fr.progress)
            )
            val authoredDeg = Math.toDegrees(expected.toDouble()).toFloat()
            // The pitch of the foot's own line (heel → toe) about the ankle.
            val foot = Vector3().set(fr[Joint.TOE_F]).subtract(fr[Joint.HEEL_F])
            val actualDeg = Math.toDegrees(kotlin.math.atan2(foot.y.toDouble(), sqrt((foot.x * foot.x + foot.z * foot.z).toDouble()))).toFloat()
            assertEquals(
                "the foot is ${fmt(actualDeg)}° off the floor at p=${fmt(fr.progress)} against the authored " +
                    "${fmt(authoredDeg)}°",
                0f, actualDeg - authoredDeg, 1.0f
            )
        }
        val apex = frameAt(f, 0.25f)
        assertTrue(
            "the toe is only ${fmt(apex[Joint.HEEL_F].y - apex[Joint.TOE_F].y)} u below the heel at the apex — " +
                "the feet are not pointed through the flight",
            apex[Joint.HEEL_F].y - apex[Joint.TOE_F].y > 8f
        )
        val closed = frameAt(f, 0f)
        assertEquals(
            "the foot is not flat on the floor in the closed stance (${fmt(closed[Joint.TOE_F].y - closed[Joint.HEEL_F].y)} u)",
            0f, closed[Joint.TOE_F].y - closed[Joint.HEEL_F].y, 0.05f
        )
    }

    /** The trunk: upright, untwisted, and riding the hop — a jack does not lean. */
    @Test
    fun theTorsoStaysUprightAndUntwisted() {
        val f = frames()
        for (fr in f) {
            for ((a, b) in listOf(
                Joint.PELVIS to Joint.CHEST, Joint.CHEST to Joint.NECK_END, Joint.NECK_END to Joint.HEAD_POS
            )) {
                val axis = Vector3().set(fr[b]).subtract(fr[a])
                assertTrue(
                    "$a→$b is ${fmt(axis.x)}/{fmt(axis.z)} u off vertical at p=${fmt(fr.progress)} — the body is " +
                        "upright for the whole drill",
                    abs(axis.x) < 0.1f && abs(axis.z) < 0.1f
                )
            }
            assertEquals(
                "the hips are not symmetric about the mid-line at p=${fmt(fr.progress)}",
                0f, fr[Joint.HIP_F].z + fr[Joint.HIP_B].z, 0.05f
            )
            assertEquals(
                "the stance is not symmetric about the mid-line at p=${fmt(fr.progress)}",
                0f, fr[Joint.ANKLE_F].z + fr[Joint.ANKLE_B].z, 0.05f
            )
        }
    }

    /** The chains keep their own bones at every phase (the anatomy the radius/stance are authored against). */
    @Test
    fun theChainsKeepTheirBoneLengths() {
        for (fr in frames()) {
            val upper = Vector3().set(fr[Joint.ELBOW_A]).subtract(fr[Joint.SHOULDER_A]).mag()
            val fore = Vector3().set(fr[Joint.HAND_A]).subtract(fr[Joint.ELBOW_A]).mag()
            val thigh = Vector3().set(fr[Joint.KNEE_F]).subtract(fr[Joint.HIP_F]).mag()
            val shin = Vector3().set(fr[Joint.ANKLE_F]).subtract(fr[Joint.KNEE_F]).mag()
            assertEquals("the upper arm reads ${fmt(upper)} u at p=${fmt(fr.progress)}", def.upperArmLength, upper, 0.05f)
            assertEquals("the forearm reads ${fmt(fore)} u at p=${fmt(fr.progress)}", def.forearmLength, fore, 0.05f)
            assertEquals("the thigh reads ${fmt(thigh)} u at p=${fmt(fr.progress)}", def.thighLength, thigh, 0.05f)
            assertEquals("the shin reads ${fmt(shin)} u at p=${fmt(fr.progress)}", def.shinLength, shin, 0.05f)
        }
    }

    /**
     * The reach contract, with the drill's own worst case quoted: the widest stance is reached at the
     * touch-down with the body at its standing height, and that chord is the longest one the cycle asks
     * for — `203.3 u` of the leg chain's `205.80 u` cap.
     */
    @Test
    fun everyAuthoredTargetIsInsideItsChainsReach() {
        val f = frames()
        var worst = 0f
        for (fr in f) {
            assertEquals(
                "the solver relocated a limb at p=${fmt(fr.progress)} (clamp ${fmt(fr.maxIkClampAmount)})",
                0f, fr.maxIkClampAmount, 1e-4f
            )
            for ((hip, ankle) in listOf(Joint.HIP_F to Joint.ANKLE_F, Joint.HIP_B to Joint.ANKLE_B)) {
                worst = maxOf(worst, Vector3().set(fr[ankle]).subtract(fr[hip]).mag())
            }
        }
        val cap = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        assertTrue(
            "the widest stance asks for a ${fmt(worst)} u leg chord against the chain's ${fmt(cap)} u cap",
            worst <= cap
        )
        assertTrue(
            "the widest stance leaves only ${fmt(cap - worst)} u of the chain's cap — too little for a stance " +
                "that must not be relocated",
            cap - worst > 1f
        )
        assertTrue(
            "the drill's widest stance is only ${fmt(2f * JumpingJacksPose.stanceHalfWidth(def, 1f))} u — not " +
                "the wide stance a jack opens into",
            2f * JumpingJacksPose.stanceHalfWidth(def, 1f) > 4f * def.hipWidth
        )
    }

    /** Nothing goes through the floor, in flight or on landing. */
    @Test
    fun theAthleteNeverGoesThroughTheFloor() {
        val f = frames()
        val (joint, clearance) = PoseFrameSweep.worstClearance(f, def, level = 0f)
        assertTrue("$joint is ${fmt(clearance)} u below the floor", clearance >= -0.05f)
    }

    /** The declared base of support is the one the published frame carries (R8/B-5, the hero's model). */
    @Test
    fun theDeclaredSupportReachesThePublishedFrame() {
        val f = frames()
        val declared = pose().metadata.support.supportPoints
        assertEquals(
            "the declaration is not the two-footed base this drill leaves and lands on",
            setOf(SupportPoint.LEFT_FOOT, SupportPoint.RIGHT_FOOT), declared
        )
        for (fr in f) {
            assertEquals(
                "the published frame at p=${fmt(fr.progress)} does not carry the declared support model",
                declared, fr.supportedPoints
            )
        }
    }
}
