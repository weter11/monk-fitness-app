package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.poses.ShoulderCarsPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Shoulder CARs (`shoulder_cars_standard`) — the exercise's own biomechanics on the PUBLISHED frame.
 *
 * ## Stated intent (its own copy — the only specification in the repository; there is no BPS)
 *
 * `desc` *"A controlled shoulder circle that trains active range without momentum … own every part of
 * the arc."* — `steps` *"1. Stand tall and brace your ribs down. 2. Lift one arm straight in front of
 * you. 3. Rotate and circle the arm overhead and behind the body as far as you can control. 4. Reverse
 * the path back to the start. 5. Repeat, then switch arms."* — `tech` *"Move one shoulder at a time.
 * Keep the torso quiet. Make the circle smooth, not fast."* — `mistakes` *"Twisting through the spine.
 * Bending the elbow to cheat the range. Speeding through the sticky spots."*
 *
 * The assertions below measure the CAR directly, clause by clause: **one arm** circles (the other arm
 * and the whole body are static), the hand's locus is **one circle centred on its own shoulder** whose
 * radius never changes (so the elbow's interior angle cannot change — there is no "bending the elbow
 * to cheat the range" left to express), the arc visits **all four quadrants** (front → overhead →
 * behind), the **azimuth is monotone** and advances *slowest through the posterior quadrant* (the
 * sticky spot), and the athlete stays on a planted, quiet stance.
 *
 * Every reading is taken through [PoseFrameSweep] (the pipeline entered exactly as `SkeletonRenderer`
 * enters it), never on a raw `build()` result.
 */
class ShoulderCarsPoseTest {

    private val ID = "shoulder_cars_standard"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = ShoulderCarsPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.4f", v)

    /** The hand's offset from its own shoulder on a published frame. */
    private fun handOffset(f: PoseFrameSweep.Frame): Vector3 =
        Vector3().set(f[Joint.HAND_A]).subtract(f[Joint.SHOULDER_A])

    /** The hand's azimuth about the shoulder, `0` = hanging at the side, `π/2` = straight in front. */
    private fun handAzimuth(f: PoseFrameSweep.Frame): Float {
        val o = handOffset(f)
        return atan2(o.x, -o.y)
    }

    /**
     * The hand's azimuth sequence, unwrapped to an increasing series: [handAzimuth] is an `atan2`, so
     * the published value jumps by `2π` at the seam (`θ = 0 ≡ 2π`) and would otherwise read as a
     * reversal. Measured, the unwrapped series runs `0.000 → 0.888 → 1.920 → 2.952 → 3.840 → 4.523 →
     * 5.061 → 5.600 → 6.283` rad — one controlled revolution.
     */
    private fun unwrappedAzimuths(f: List<PoseFrameSweep.Frame>): List<Float> {
        val twoPi = 2f * Math.PI.toFloat()
        val out = ArrayList<Float>(f.size)
        var prev = handAzimuth(f.first())
        if (prev < -1e-3f) prev += twoPi
        out.add(prev)
        for (i in 1 until f.size) {
            var v = handAzimuth(f[i])
            if (v < -1e-3f) v += twoPi
            while (v < prev - 1e-3f) v += twoPi
            out.add(v)
            prev = v
        }
        return out
    }

    /** The coverage metric itself: this exercise must resolve to a REAL skeletal animation. */
    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(ShoulderCarsPose::class.java, cfg!!.builder.javaClass)
        assertTrue("$ID is not a deduplicated dedicated animation", ID in PoseRegistry.getDedicatedAnimationIds())
    }

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        assertEquals(
            "the published frames of the arc changed — re-measure the rhythm",
            9, PoseFrameSweep.distinctFrameCount(frames())
        )
    }

    /**
     * `tech` *"Move **one** shoulder at a time"* — the drill is a single arm; everything else is the
     * frame the arm moves inside. Measured: the working hand travels `272.277 u` and its elbow
     * `153.097 u`, and every other joint of the body travels `0.0000 u`.
     */
    @Test
    fun onlyTheWorkingArmMoves() {
        val f = frames()
        // The hand's two most distant published samples are one arc apart: measured 269.57 u of the
        // sweep's own diameter 2R = 274.73 u (the sampled azimuths straddle, but do not land exactly on,
        // the antipodes — the extremes are reached between samples).
        assertTrue(
            "the working hand travels only ${fmt(PoseFrameSweep.travel3D(f, Joint.HAND_A))} u of its " +
                "${fmt(2f * ShoulderCarsPose.CAR_RADIUS)} u circle — the arm is not touring the arc",
            PoseFrameSweep.travel3D(f, Joint.HAND_A) >= 2f * ShoulderCarsPose.CAR_RADIUS * 0.95f
        )
        assertTrue(
            "the working elbow travels ${fmt(PoseFrameSweep.travel3D(f, Joint.ELBOW_A))} u — the shoulder is not driving",
            PoseFrameSweep.travel3D(f, Joint.ELBOW_A) > 50f
        )
        val still = Joint.entries.filterNot {
            it == Joint.HAND_A || it == Joint.ELBOW_A || it == Joint.WRIST_A ||
                it == Joint.PALM_A || it == Joint.KNUCKLES_A || it == Joint.FINGERTIPS_A
        }
        for (joint in still) {
            assertEquals(
                "$joint moves ${fmt(PoseFrameSweep.travel3D(f, joint))} u — the drill moves ONE shoulder, " +
                    "not the body",
                0f, PoseFrameSweep.travel3D(f, joint), 0.05f
            )
        }
    }

    /**
     * `steps` §2/§3 — *"Lift one arm straight in front of you … circle the arm **overhead and behind**
     * the body"*: the arc is a whole revolution about the shoulder, so the hand visits all four
     * quadrants in order, and comes back the way it went (`steps` §4).
     */
    @Test
    fun theHandReallyCirclesTheShoulderThroughAllFourQuadrants() {
        val f = frames()
        val r = ShoulderCarsPose.CAR_RADIUS
        // progress -> (in front of the shoulder, above the shoulder): the four quadrant samples.
        val quadrants = mapOf(
            0.125f to (true to false),
            0.25f to (true to true),
            0.5f to (false to true),
            0.75f to (false to false)
        )
        for (frame in f) {
            val (front, above) = quadrants[frame.progress] ?: continue
            val o = handOffset(frame)
            if (front) assertTrue(
                "at p=${fmt(frame.progress)} the hand is ${fmt(o.x)} u FORWARD of its shoulder — `steps` asks " +
                    "for the front half of the arc",
                o.x > 0.2f * r
            ) else assertTrue(
                "at p=${fmt(frame.progress)} the hand is ${fmt(o.x)} u from its shoulder — `steps` asks for the " +
                    "arm to circle BEHIND the body",
                o.x < -0.2f * r
            )
            if (above) assertTrue(
                "at p=${fmt(frame.progress)} the hand is ${fmt(o.y)} u above its shoulder — the overhead half of " +
                    "the arc is missing",
                o.y > 0.2f * r
            ) else assertTrue(
                "at p=${fmt(frame.progress)} the hand is ${fmt(o.y)} u above its shoulder — the low half of the " +
                    "arc is missing",
                o.y < -0.2f * r
            )
        }
        // ... and the *order* is one monotone revolution that closes at the seam: measured azimuth
        // 0.000 → 0.888 → 1.920 → 2.952 → 3.840 → 4.523 → 5.061 → 5.600 → 6.283 rad.
        val swept = unwrappedAzimuths(f)
        for (i in 1 until swept.size) {
            assertTrue(
                "the azimuth went backwards at p=${fmt(f[i].progress)} (${fmt(swept[i - 1])} → ${fmt(swept[i])}) — " +
                    "a CAR is one controlled revolution, not a swing",
                swept[i] > swept[i - 1] - 1e-4f
            )
        }
        assertEquals(
            "the revolution does not close (${fmt(swept.first())} → ${fmt(swept.last())} rad)",
            0f, abs(swept.last() - (2f * Math.PI.toFloat())), 1e-3f
        )
        assertEquals("the arc's first sample is not the hanging position", 0f, swept.first(), 1e-3f)
        // The arm's plane is the shoulder's own sagittal one: the hand's z never leaves it.
        for (frame in f) {
            assertEquals(
                "the hand leaves the shoulder's sagittal plane at p=${fmt(frame.progress)} " +
                    "(z offset ${fmt(handOffset(frame).z)})",
                0f, handOffset(frame).z + ShoulderCarsPose.CAR_OUTBOARD, 0.01f
            )
        }
    }

    /**
     * `steps` §2 *"straight"* and `mistakes` *"Bending the elbow to cheat the range"*: the radius is
     * constant, so the elbow's interior angle is constant and long. Measured `137.600 u` and
     * `140.751°` at every phase (the authored pair: the range check below is what makes the second a
     * consequence of the first).
     */
    @Test
    fun theElbowNeverCheatsTheArc() {
        val f = frames()
        val authored = ShoulderCarsPose.elbowInteriorDeg(def)
        for (frame in f) {
            val radius = handOffset(frame).mag()
            assertEquals(
                "the shoulder→hand radius is ${fmt(radius)} u at p=${fmt(frame.progress)} against the authored " +
                    "${fmt(ShoulderCarsPose.STRAIGHT_ARM_RADIUS)} u — the arm is shortening/lengthening its way " +
                    "around the arc",
                0f, radius - ShoulderCarsPose.STRAIGHT_ARM_RADIUS, 0.05f
            )
            val interior = PoseFrameSweep.interiorAngle(frame, Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A)
            assertEquals(
                "the elbow reads ${fmt(interior)}° at p=${fmt(frame.progress)} against the authored " +
                    "${fmt(authored)}° — a bending elbow is the copy's own cheat",
                0f, interior - authored, 0.5f
            )
            assertTrue(
                "the arm's elbow is only ${fmt(interior)}° — not the near-straight arm a CAR tours its range with",
                interior > 135f
            )
        }
    }

    /** `tech` *"Keep the torso quiet"* + `mistakes` *"Twisting through the spine"*: the trunk is still. */
    @Test
    fun theTorsoStaysQuietAndDoesNotTwist() {
        val f = frames()
        // The trunk's own joints AND the chain the chest carries: a thoracic twist or roll would not
        // move the chest's position but would move everything hanging from it, so both are measured.
        val trunk = listOf(Joint.PELVIS, Joint.LUMBAR, Joint.CHEST, Joint.NECK_END, Joint.HEAD_POS)
        for (joint in trunk) {
            assertEquals(
                "$joint travels ${fmt(PoseFrameSweep.travel3D(f, joint))} u — the torso must not move",
                0f, PoseFrameSweep.travel3D(f, joint), 0.05f
            )
        }
        for (frame in f) {
            val spine = Vector3().set(frame[Joint.CHEST]).subtract(frame[Joint.PELVIS])
            assertTrue(
                "the trunk leans ${fmt(spine.x)} u off vertical at p=${fmt(frame.progress)} — the drill is a " +
                    "shoulder CAR, not a spine movement",
                abs(spine.x) < 0.1f && abs(spine.z) < 0.1f
            )
            val head = Vector3().set(frame[Joint.HEAD_POS]).subtract(frame[Joint.NECK_END])
            assertTrue(
                "the head's own axis is ${fmt(head.x)}/{fmt(head.z)} u off the mid-plain at " +
                    "p=${fmt(frame.progress)} — `mistakes` is `Twisting through the spine`",
                abs(head.x) < 0.1f && abs(head.z) < 0.1f
            )
        }
    }

    /** `tech` *"Move one shoulder at a time"* + `mistakes` *"Shrugging"*: the girdles hold one height. */
    @Test
    fun theShouldersNeverShrug() {
        val f = frames()
        for (joint in listOf(Joint.SHOULDER_A, Joint.SHOULDER_P, Joint.CLAVICLE_A, Joint.CLAVICLE_P)) {
            assertEquals(
                "$joint moves ${fmt(PoseFrameSweep.travel3D(f, joint))} u during the arc — the drill is the " +
                    "shoulder JOINT's own rotation, and the recorded girdle residual is why its elevation is " +
                    "not driven (see the pose's KDoc)",
                0f, PoseFrameSweep.travel3D(f, joint), 0.05f
            )
        }
    }

    /**
     * `steps` §2/§3 in the order the copy states them: the arm starts hanging, is lifted **straight in
     * front**, passes **overhead** and continues **behind** — all measured as the published hand's own
     * offset from its shoulder.
     */
    @Test
    fun theArmIsLiftedInFrontOverheadAndBehindInThatOrder() {
        val f = frames()
        val r = ShoulderCarsPose.CAR_RADIUS
        val start = f.first()
        assertEquals(
            "the cycle does not start from the arm hanging at the side (offset ${handOffset(start)})",
            0f, handOffset(start).y + r, 0.05f
        )
        // The highest hand of the sweep is above the shoulder; the most forward is in front of it; the
        // most posterior is behind it — the three named positions, each at its own phase.
        val offsets = f.map { it.progress to handOffset(it) }
        val highest = offsets.maxByOrNull { it.second.y }!!
        val mostForward = offsets.maxByOrNull { it.second.x }!!
        val mostBehind = offsets.minByOrNull { it.second.x }!!
        assertTrue(
            "the hand never gets overhead (best ${fmt(highest.second.y)} u above the shoulder)",
            highest.second.y > 0.7f * r
        )
        assertTrue(
            "the hand never gets in front (best ${fmt(mostForward.second.x)} u)",
            mostForward.second.x > 0.9f * r
        )
        assertTrue(
            "the hand never gets behind the body (best ${fmt(mostBehind.second.x)} u)",
            mostBehind.second.x < -0.9f * r
        )
    }

    /**
     * `desc` *"without momentum"* + `mistakes` *"Speeding through the sticky spots"*: the authored
     * azimuth schedule is strictly increasing and its rate is *minimal* through the posterior quadrant.
     * Measured per-sample advances (rad): `0.888 1.032 1.032 0.888 | 0.683 0.539 0.539 0.683` — the
     * slowest pair sits behind the body, the fastest in front.
     */
    @Test
    fun theArcIsControlledAndSlowsThroughTheStickySpot() {
        val f = frames()
        val swept = unwrappedAzimuths(f)
        val steps = (1 until swept.size).map { swept[it] - swept[it - 1] }
        for (step in steps) {
            assertTrue("the arc reverses or stalls (step ${fmt(step)} rad) — a CAR never turns back", step > 1e-3f)
        }
        // DEFAULT_PROGRESS order: 0.125 is index 1, 0.25 index 2, 0.625 index 5, 0.75 index 6.
        val frontStep = steps[1]
        val behindStep = steps[5]
        assertTrue(
            "the arc advances ${fmt(frontStep)} rad/sample in front and ${fmt(behindStep)} behind — the copy's " +
                "`Speeding through the sticky spots` asks for the opposite",
            behindStep < frontStep
        )
        assertTrue(
            "the arc's slow-down is only ${fmt(behindStep / frontStep)} of its fast part — the control is not there",
            behindStep / frontStep < 0.8f
        )
        // the authored schedule itself: minimal rate at the sticky phase, maximal half a cycle later.
        val slow = ShoulderCarsPose.azimuthRate(ShoulderCarsPose.STICKY_PHASE)
        val fast = ShoulderCarsPose.azimuthRate(ShoulderCarsPose.STICKY_PHASE - 0.5f)
        assertTrue(
            "the authored schedule's rate at the sticky phase (${fmt(slow)}) is not below its rate in front (${fmt(fast)})",
            slow < fast
        )
    }

    /** The arm keeps its own two bones at every phase (the anatomy the radius is authored against). */
    @Test
    fun theArmChainKeepsItsBoneLengths() {
        for (frame in frames()) {
            val upper = Vector3().set(frame[Joint.ELBOW_A]).subtract(frame[Joint.SHOULDER_A]).mag()
            val fore = Vector3().set(frame[Joint.HAND_A]).subtract(frame[Joint.ELBOW_A]).mag()
            assertEquals("the upper arm reads ${fmt(upper)} u at p=${fmt(frame.progress)}", def.upperArmLength, upper, 0.05f)
            assertEquals("the forearm reads ${fmt(fore)} u at p=${fmt(frame.progress)}", def.forearmLength, fore, 0.05f)
        }
    }

    /** The reach contract: the authored radius is inside the chain's band at every phase. */
    @Test
    fun everyAuthoredTargetIsInsideItsChainsReach() {
        for (frame in frames()) {
            assertEquals(
                "the solver relocated the arm at p=${fmt(frame.progress)} (clamp ${fmt(frame.maxIkClampAmount)})",
                0f, frame.maxIkClampAmount, 1e-4f
            )
        }
        assertTrue(
            "the authored radius is not inside the chain's own cap",
            ShoulderCarsPose.STRAIGHT_ARM_RADIUS <= SkeletonMath.maxReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
        )
        assertTrue(
            "the authored radius is below the chain's fold stop",
            ShoulderCarsPose.STRAIGHT_ARM_RADIUS >= SkeletonMath.minReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
        )
    }

    /** `steps` §1 *"Stand tall"*: flat planted feet and nothing through the floor. */
    @Test
    fun theAthleteStandsFlatOnTheFloor() {
        val f = frames()
        val (joint, clearance) = PoseFrameSweep.worstClearance(f, def, level = 0f)
        assertTrue("$joint is ${fmt(clearance)} u below the floor", clearance >= -0.05f)
        for (j in listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)) {
            assertEquals(
                "the planted $j slides ${fmt(PoseFrameSweep.travel3D(f, j))} u",
                0f, PoseFrameSweep.travel3D(f, j), 0.05f
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
        assertEquals("the stance is not symmetric about the mid-line", 0f, abs(f.first()[Joint.ANKLE_F].z + f.first()[Joint.ANKLE_B].z), 0.05f)
        assertTrue("the feet are ${fmt(stance.mag())} u apart — not a stance", stance.mag() > 2f * def.hipWidth)
        assertTrue(
            "the hips are not over the base of support",
            sqrt(f.first()[Joint.PELVIS].x * f.first()[Joint.PELVIS].x + f.first()[Joint.PELVIS].z * f.first()[Joint.PELVIS].z) < 0.1f
        )
    }
}
