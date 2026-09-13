package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.NinetyNinetyHipsPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2

/**
 * 90/90 Hips (`ninety_ninety_hips`) — the exercise's own biomechanics on the PUBLISHED frame.
 *
 * ## Stated intent (the exercise's own copy — the only in-repo specification; no BPS exists)
 *
 * `steps` §1 *"Sit on the floor with both knees bent to 90 degrees, one leg in front and one to the
 * side"*, §2 *"Sit tall and lean slightly over the front shin"*, §3 *"Rotate through the hips to switch
 * both knees to the other side"*, §4 *"Repeat slowly, staying controlled throughout"*; `desc` *"trains
 * both internal and external rotation"*; `tech` *"Keep the sit bones grounded … move from the hips
 * rather than throwing the knees around"*; `mistakes` *"Rounding the back heavily, forcing the knees
 * down, and rushing the side-to-side transition."*
 *
 * The assertions below therefore measure the **configuration** as much as the movement: the two thighs
 * are exactly `90°` apart in azimuth ("90/90", one hip internally and one externally rotated), both
 * knees are exactly `90°` at every phase (§1's "both knees bent to 90 degrees"), the shins rest in the
 * floor plane at the two named configurations, the switch rotates the pair rigidly (§3), and the seat
 * never lifts or twists (`tech`/`mistakes`). Every reading is taken through [PoseFrameSweep] (the
 * pipeline entered exactly as `SkeletonRenderer` enters it), never on a raw `build()` result.
 */
class NinetyNinetyHipsPoseTest {

    private val ID = "ninety_ninety_hips"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = NinetyNinetyHipsPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.3f", v)

    /** The azimuth of the thigh's horizontal projection, in degrees; `0` = forward (`+X`). */
    private fun thighAzimuthDeg(frame: PoseFrameSweep.Frame, hip: Joint, knee: Joint): Float {
        val dx = frame[knee].x - frame[hip].x
        val dz = frame[knee].z - frame[hip].z
        return Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())).toFloat()
    }

    /** The interior angle at [vertex] between `[a]` and `[c]`, on the published frame. */
    private fun angleAt(a: Vector3, vertex: Vector3, c: Vector3): Float {
        val u = Vector3().set(a).subtract(vertex)
        val w = Vector3().set(c).subtract(vertex)
        return Math.toDegrees(acos((u.dot(w) / (u.mag() * w.mag())).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /** The coverage metric itself: this exercise must resolve to a REAL skeletal animation. */
    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(NinetyNinetyHipsPose::class.java, cfg!!.builder.javaClass)
        assertTrue("$ID is not a deduplicated dedicated animation", ID in PoseRegistry.getDedicatedAnimationIds())
    }

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        assertEquals(
            "the published-frame sweep aliased (T-7 class)",
            f.size, PoseFrameSweep.distinctFrameCount(f)
        )
    }

    /** §1 — BOTH KNEES ARE 90°, at every phase, because the drill's own name is its configuration. */
    @Test
    fun bothKneesStayAtNinetyDegreesThroughTheWholeSwitch() {
        for (frame in frames()) {
            for ((hip, knee, ankle) in listOf(
                Triple(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F),
                Triple(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B)
            )) {
                val angle = PoseFrameSweep.interiorAngle(frame, hip, knee, ankle)
                assertTrue(
                    "$knee is %.3f deg at p=%.3f — the 90/90 must hold its own 90".format(angle, frame.progress),
                    abs(angle - NinetyNinetyHipsPose.KNEE_ANGLE_DEG) <= 0.05f
                )
            }
        }
    }

    /** §1 — "one leg in front and one to the side": the two thighs are exactly 90° apart, always. */
    @Test
    fun theTwoThighsStayExactlyNinetyDegreesApart() {
        for (frame in frames()) {
            val azimuthF = thighAzimuthDeg(frame, Joint.HIP_F, Joint.KNEE_F)
            val azimuthB = thighAzimuthDeg(frame, Joint.HIP_B, Joint.KNEE_B)
            var separation = azimuthB - azimuthF
            while (separation > 180f) separation -= 360f
            while (separation < -180f) separation += 360f
            assertTrue(
                "the thighs are %.2f deg apart at p=%.3f (F %.2f, B %.2f) — the exercise's name is 90/90".format(
                    separation, frame.progress, azimuthF, azimuthB
                ),
                abs(abs(separation) - 90f) <= 0.5f
            )
        }
        // ... and at the cycle's start it is the copy's own layout: one leg in FRONT, one to the SIDE.
        val start = frames().first()
        assertEquals(
            "the front leg's thigh does not point forward at p=0",
            NinetyNinetyHipsPose.THIGH_AZIMUTH_F0_DEG, thighAzimuthDeg(start, Joint.HIP_F, Joint.KNEE_F), 0.5f
        )
        assertEquals(
            "the side leg's thigh does not point to the side at p=0",
            NinetyNinetyHipsPose.THIGH_AZIMUTH_B0_DEG, thighAzimuthDeg(start, Joint.HIP_B, Joint.KNEE_B), 0.5f
        )
    }

    /** §1 — the hips are flexed ~90° at the two named configurations, and the roles swap. */
    @Test
    fun bothHipsSitNearNinetyDegreesOfFlexionAtTheNamedConfigurations() {
        val f = frames()
        for (frame in listOf(f.first(), f.last())) {
            for ((hip, knee) in listOf(Joint.HIP_F to Joint.KNEE_F, Joint.HIP_B to Joint.KNEE_B)) {
                val flexion = angleAt(frame[Joint.CHEST], frame[hip], frame[knee])
                assertTrue(
                    "$hip is flexed %.1f deg at p=%.3f — a 90/90 seat has both hips at ~90".format(
                        flexion, frame.progress
                    ),
                    abs(flexion - 90f) <= 15f
                )
            }
        }
        // The roles really swap: the F hip opens while the B hip closes, by comparable amounts.
        val startF = angleAt(f.first()[Joint.CHEST], f.first()[Joint.HIP_F], f.first()[Joint.KNEE_F])
        val endF = angleAt(f.last()[Joint.CHEST], f.last()[Joint.HIP_F], f.last()[Joint.KNEE_F])
        assertTrue(
            "the F hip does not change role through the switch (%.1f → %.1f deg)".format(startF, endF),
            endF - startF >= 8f
        )
    }

    /** §1 — at the named configurations both shins lie in the floor plane (the seat's own layer). */
    @Test
    fun theShinsRestInTheFloorPlaneAtTheNamedConfigurations() {
        val f = frames()
        for (frame in listOf(f.first(), f.last())) {
            for ((knee, ankle) in listOf(Joint.KNEE_F to Joint.ANKLE_F, Joint.KNEE_B to Joint.ANKLE_B)) {
                assertTrue(
                    "$knee rides at y=%.3f and $ankle at y=%.3f at p=%.3f — the shin is not on the floor plane (%.1f)".format(
                        frame[knee].y, frame[ankle].y, frame.progress, NinetyNinetyHipsPose.SEAT_Y
                    ),
                    abs(frame[knee].y - NinetyNinetyHipsPose.SEAT_Y) <= 1f &&
                        abs(frame[ankle].y - NinetyNinetyHipsPose.SEAT_Y) <= 1f
                )
            }
        }
    }

    /** §3 — "switch both knees to the other side": the pair rotates rigidly and both knees sweep. */
    @Test
    fun theSwitchRotatesTheWholeNinetyNinetyArrangement() {
        val f = frames()
        val start = f.first()
        val end = f.last()
        for ((hip, knee) in listOf(Joint.HIP_F to Joint.KNEE_F, Joint.HIP_B to Joint.KNEE_B)) {
            val swept = thighAzimuthDeg(end, hip, knee) - thighAzimuthDeg(start, hip, knee)
            assertTrue(
                "$knee's thigh swept %.2f deg, not the authored %.1f".format(swept, NinetyNinetyHipsPose.SWITCH_SWEEP_DEG),
                abs(swept - NinetyNinetyHipsPose.SWITCH_SWEEP_DEG) <= 1f
            )
        }
        // Both knees genuinely travel across the floor (this is not a pose that merely re-angles).
        assertTrue(
            "KNEE_F only travels %.2f u".format(PoseFrameSweep.travel3D(f, Joint.KNEE_F)),
            PoseFrameSweep.travel3D(f, Joint.KNEE_F) >= 80f
        )
        assertTrue(
            "KNEE_B only travels %.2f u".format(PoseFrameSweep.travel3D(f, Joint.KNEE_B)),
            PoseFrameSweep.travel3D(f, Joint.KNEE_B) >= 80f
        )
    }

    /** §3 — the transition LIFTS the lower legs over and sets them down (never a slide). */
    @Test
    fun theLowerLegsLiftOverAndSetDownAgain() {
        val f = frames()
        val mid = f[f.size / 2]
        for ((knee, ankle) in listOf(Joint.KNEE_F to Joint.ANKLE_F, Joint.KNEE_B to Joint.ANKLE_B)) {
            assertTrue(
                "$knee does not lift at mid-switch (y=%.2f vs the seat %.1f)".format(mid[knee].y, NinetyNinetyHipsPose.SEAT_Y),
                mid[knee].y >= NinetyNinetyHipsPose.SEAT_Y + 15f
            )
            assertTrue(
                "$ankle does not lift at mid-switch (y=%.2f vs the seat %.1f)".format(mid[ankle].y, NinetyNinetyHipsPose.SEAT_Y),
                mid[ankle].y >= NinetyNinetyHipsPose.SEAT_Y + 80f
            )
            assertTrue(
                "$ankle never leaves the floor during the switch (its lowest point is %.2f)".format(
                    f.minOf { it[ankle].y }
                ),
                f.minOf { it[ankle].y } >= NinetyNinetyHipsPose.SEAT_Y - 1f
            )
        }
    }

    /** `tech` — "keep the sit bones grounded": the seat never lifts, rolls or twists. */
    @Test
    fun theSitBonesStayGroundedAndStill() {
        val f = frames()
        for (joint in listOf(Joint.PELVIS, Joint.HIP_F, Joint.HIP_B)) {
            assertTrue(
                "$joint drifts %.3f u through the switch".format(PoseFrameSweep.travel3D(f, joint)),
                PoseFrameSweep.travel3D(f, joint) <= 0.5f
            )
        }
        for (frame in f) {
            assertEquals(
                "the pelvis changes height at p=%.3f".format(frame.progress),
                NinetyNinetyHipsPose.SEAT_Y, frame.y(Joint.PELVIS), 0.5f
            )
            // A twisting pelvis breaks the hips' lateral line first: it is exactly 2 x hipWidth wide.
            assertEquals(
                "the pelvis twisted off its own lateral line at p=%.3f".format(frame.progress),
                2f * def.hipWidth, frame[Joint.HIP_B].z - frame[Joint.HIP_F].z, 0.5f
            )
        }
    }

    /** `tech` — "move from the hips": the trunk is a still, upright (slightly leaning) column. */
    @Test
    fun theTrunkIsStillAndLeansAsAuthored() {
        val f = frames()
        assertTrue(
            "the trunk travels %.3f u during the switch — the movement is not in the hips".format(
                PoseFrameSweep.travel3D(f, Joint.CHEST)
            ),
            PoseFrameSweep.travel3D(f, Joint.CHEST) <= 0.5f
        )
        val expected = Math.toDegrees(NinetyNinetyHipsPose.SEATED_LEAN.toDouble()).toFloat()
        for (frame in f) {
            val trunk = Vector3().set(frame[Joint.CHEST]).subtract(frame[Joint.PELVIS])
            val tilt = Math.toDegrees(
                atan2(kotlin.math.sqrt(trunk.x * trunk.x + trunk.z * trunk.z).toDouble(), trunk.y.toDouble())
            ).toFloat()
            assertEquals(
                "the trunk leans %.2f deg at p=%.3f, not the authored %.2f".format(tilt, frame.progress, expected),
                expected, tilt, 1.5f
            )
        }
    }

    /**
     * The published frame realizes the pose's OWN declared geometry (B-1's oracle): the knee the engine
     * publishes is the knee the pose authored, at every phase and for both legs — not merely “a leg
     * moved somewhere”.
     */
    @Test
    fun thePublishedKneesAreTheAuthoredKnees() {
        for (frame in frames()) {
            val elevation = NinetyNinetyHipsPose.thighElevation(frame.progress)
            val swing = NinetyNinetyHipsPose.shinSwingRad(frame.progress)
            assertPublishedLegGeometry(
                frame, Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F,
                NinetyNinetyHipsPose.thighAzimuthF(frame.progress), elevation, swing
            )
            assertPublishedLegGeometry(
                frame, Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B,
                NinetyNinetyHipsPose.thighAzimuthB(frame.progress), elevation, swing
            )
        }
    }

    /** The published chain must be the pose's own authored directions, applied to the published hip. */
    private fun assertPublishedLegGeometry(
        frame: PoseFrameSweep.Frame,
        hip: Joint,
        knee: Joint,
        ankle: Joint,
        azimuth: Float,
        elevation: Float,
        swing: Float
    ) {
        val geometry = NinetyNinetyHipsPose.legGeometry(azimuth, elevation, swing)
        val expectedKnee = Vector3(
            frame[hip].x + geometry.thigh.x * def.thighLength,
            frame[hip].y + geometry.thigh.y * def.thighLength,
            frame[hip].z + geometry.thigh.z * def.thighLength
        )
        val kneeError = Vector3().set(frame[knee]).subtract(expectedKnee).mag()
        assertTrue(
            "$knee is %.3f u off the authored thigh at p=%.3f".format(kneeError, frame.progress),
            kneeError <= 0.5f
        )
        val expectedAnkle = Vector3(
            expectedKnee.x + geometry.shin.x * def.shinLength,
            expectedKnee.y + geometry.shin.y * def.shinLength,
            expectedKnee.z + geometry.shin.z * def.shinLength
        )
        val ankleError = Vector3().set(frame[ankle]).subtract(expectedAnkle).mag()
        assertTrue(
            "$ankle is %.3f u off the authored shin at p=%.3f".format(ankleError, frame.progress),
            ankleError <= 0.5f
        )
    }

    /** No interpenetration: the two legs never pass through each other during the switch. */
    @Test
    fun theTwoLegsNeverIntersect() {
        for (frame in frames()) {
            val gap = legGap(
                frame[Joint.HIP_F], frame[Joint.KNEE_F], frame[Joint.ANKLE_F],
                frame[Joint.HIP_B], frame[Joint.KNEE_B], frame[Joint.ANKLE_B]
            )
            assertTrue(
                "the legs are only %.2f u apart at p=%.3f".format(gap, frame.progress),
                gap >= 15f
            )
        }
    }

    /** The reach contract: the pole each limb derives is the chain's own statement of its bend. */
    @Test
    fun everyAuthoredTargetIsInsideItsChainsReach() {
        for (frame in frames()) {
            assertEquals(
                "the solver relocated a limb at p=%.3f (clamp %.4f)".format(frame.progress, frame.maxIkClampAmount),
                0f, frame.maxIkClampAmount, 1e-4f
            )
        }
    }

    /** The floor invariant: the seated athlete never passes through the mat. */
    @Test
    fun nothingGoesBelowTheFloor() {
        val (joint, clearance) = PoseFrameSweep.worstClearance(frames(), def, level = 0f)
        assertTrue("$joint is %.3f u below the floor".format(clearance), clearance >= -0.05f)
    }

    /** The declared seat: the sit bones are what this pose rests on. */
    @Test
    fun theSeatIsDeclaredAsTheSupport() {
        val points = pose().metadata.support.contacts.map { it.point }.toSet()
        assertTrue("the sit bones are not declared at all", SupportPoint.HIPS in points)
        assertEquals(
            "the declared core support must resolve to the pelvis region",
            listOf(Joint.PELVIS, Joint.HIP_F, Joint.HIP_B),
            PoseFrameSweep.jointsFor(SupportPoint.HIPS)
        )
    }

    /** Minimum distance between the two legs, sampled as two 2-segment polylines. */
    private fun legGap(hf: Vector3, kf: Vector3, af: Vector3, hb: Vector3, kb: Vector3, ab: Vector3): Float {
        fun segSeg(p1: Vector3, q1: Vector3, p2: Vector3, q2: Vector3): Float {
            var best = Float.MAX_VALUE
            val steps = 20
            for (i in 0..steps) for (j in 0..steps) {
                val t = i.toFloat() / steps
                val s = j.toFloat() / steps
                val a = Vector3(p1.x + (q1.x - p1.x) * t, p1.y + (q1.y - p1.y) * t, p1.z + (q1.z - p1.z) * t)
                val b = Vector3(p2.x + (q2.x - p2.x) * s, p2.y + (q2.y - p2.y) * s, p2.z + (q2.z - p2.z) * s)
                best = minOf(best, Vector3().set(a).subtract(b).mag())
            }
            return best
        }
        return minOf(
            segSeg(hf, kf, hb, kb), segSeg(hf, kf, kb, ab),
            segSeg(kf, af, hb, kb), segSeg(kf, af, kb, ab)
        )
    }
}
