package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.PiriformisStretchPose
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
 * Piriformis Stretch (`piriformis_stretch_hold`) — the exercise's own biomechanics on the PUBLISHED
 * frame.
 *
 * ## Stated intent (the exercise's own copy — the only in-repo specification; no BPS exists)
 *
 * `steps` §1 *"Lie on your back with both knees bent"*, §2 *"Cross one ankle over the opposite
 * thigh"*, §3 *"Pull the uncrossed leg toward your chest"*, §4 *"Keep the tailbone heavy as the outer
 * hip opens up"*, §5 *"Hold, then change sides"*; `desc` *"keep the low back quiet"*; `tech` *"Flex the
 * crossed foot to protect the knee. Pull the legs in only until the hip stretches."*; `mistakes`
 * *"Yanking the knee toward the chest … Twisting the pelvis off the floor."*
 *
 * The assertions below therefore measure the **figure-4 configuration** and its hold, not just travel:
 * the body is supine (the ventral basis faces up), the crossed ankle rides on the opposite thigh, the
 * pull is the authored entry→hold hip flexion, the plateau really is a hold, the tailbone stays down
 * and the pelvis never twists. Every reading is a PUBLISHED frame via [PoseFrameSweep] (the pipeline
 * entered exactly as `SkeletonRenderer` enters it), never a raw `build()` result.
 */
class PiriformisStretchPoseTest {

    private val ID = "piriformis_stretch_hold"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = PiriformisStretchPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.3f", v)

    /** Distance from [p] to the segment `[a]–[b]`. */
    private fun pointToSegment(p: Vector3, a: Vector3, b: Vector3): Float {
        val abx = b.x - a.x; val aby = b.y - a.y; val abz = b.z - a.z
        val apx = p.x - a.x; val apy = p.y - a.y; val apz = p.z - a.z
        val len2 = abx * abx + aby * aby + abz * abz
        val t = if (len2 < 1e-6f) 0f else ((apx * abx + apy * aby + apz * abz) / len2).coerceIn(0f, 1f)
        val cx = a.x + abx * t; val cy = a.y + aby * t; val cz = a.z + abz * t
        return sqrt((p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy) + (p.z - cz) * (p.z - cz))
    }

    /** The anatomical hip flexion: `180°` minus the trunk↔thigh angle at the hip. */
    private fun hipFlexionDeg(frame: PoseFrameSweep.Frame, hip: Joint, knee: Joint): Float {
        val u = Vector3().set(frame[Joint.CHEST]).subtract(frame[Joint.PELVIS])
        val w = Vector3().set(frame[knee]).subtract(frame[hip])
        val angle = Math.toDegrees(acos((u.dot(w) / (u.mag() * w.mag())).coerceIn(-1f, 1f).toDouble())).toFloat()
        return 180f - angle
    }

    /** The coverage metric itself: this exercise must resolve to a REAL skeletal animation. */
    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(PiriformisStretchPose::class.java, cfg!!.builder.javaClass)
        assertTrue("$ID is not a deduplicated dedicated animation", ID in PoseRegistry.getDedicatedAnimationIds())
    }

    /**
     * §1 — "Lie on your back": the body's own basis says SUPINE. The ventral direction is the spine ×
     * lateral cross product on the published frame (`monk-pose-qa`'s body-orientation forensics), and
     * `facing.y > 0` is what distingushes a face-up body from a face-down one. A pose that merely
     * *looks* flat can be lying on the wrong side; this measures it.
     */
    @Test
    fun theBodyIsSupineNotProne() {
        for (frame in frames()) {
            val spine = Vector3().set(frame[Joint.CHEST]).subtract(frame[Joint.PELVIS]).normalize()
            val lateral = Vector3().set(frame[Joint.PELVIS]).subtract(frame[Joint.HIP_F]).normalize()
            val facing = spine.cross(lateral).normalize()
            assertTrue(
                "the ventral direction (%.3f, %.3f, %.3f) does not face up at p=%.3f".format(
                    facing.x, facing.y, facing.z, frame.progress
                ),
                facing.y >= 0.9f
            )
            // and the trunk lies ALONG the mat: the chest is at the pelvis's own height, one body
            // length toward the head (-X).
            assertTrue(
                "the trunk is not flat on the mat at p=%.3f (chest y=%.2f vs pelvis y=%.2f)".format(
                    frame.progress, frame[Joint.CHEST].y, frame[Joint.PELVIS].y
                ),
                abs(frame[Joint.CHEST].y - frame[Joint.PELVIS].y) <= 2f
            )
            assertTrue(
                "the head end of the trunk is not toward -X at p=%.3f".format(frame.progress),
                frame[Joint.CHEST].x <= frame[Joint.PELVIS].x - def.torsoLength * 0.9f
            )
        }
    }

    /**
     * §2 — "Cross one ankle over the opposite thigh": the crossed ankle really crosses. Two independent
     * measurements: it has passed the body's mid-line (its Z is on the opposite side from its own hip),
     * and it rides ON the pulled thigh's segment (not floating away from it).
     */
    @Test
    fun theCrossedAnkleRestsonTheOppositeThigh() {
        for (frame in frames()) {
            assertTrue(
                "the crossed ankle (z=%.2f) has not crossed its own hip (z=%.2f) at p=%.3f".format(
                    frame[Joint.ANKLE_F].z, frame[Joint.HIP_F].z, frame.progress
                ),
                frame[Joint.HIP_F].z < 0f && frame[Joint.ANKLE_F].z > 0f
            )
            val gap = pointToSegment(frame[Joint.ANKLE_F], frame[Joint.HIP_B], frame[Joint.KNEE_B])
            assertTrue(
                "the crossed ankle is %.2f u from the pulled thigh at p=%.3f (it must ride on it)".format(
                    gap, frame.progress
                ),
                gap in 8f..24f
            )
            // ... and the two limbs never interpenetrate: the shin's axis keeps a limb-scale standoff
            // from the thigh's axis (measured 15.1–16.1 u over the cycle).
            val shinGap = minOf(
                pointToSegment(frame[Joint.ANKLE_F], frame[Joint.HIP_B], frame[Joint.KNEE_B]),
                pointToSegment(frame[Joint.KNEE_F], frame[Joint.HIP_B], frame[Joint.KNEE_B])
            )
            assertTrue("the crossed shin passes %.2f u from the thigh's axis".format(shinGap), shinGap >= 8f)
        }
    }

    /**
     * §2's external rotation, in the only form this rig realises it: the crossed femur's twist is the
     * GEOMETRY — its thigh's horizontal projection points outboard while its shin's points inboard,
     * which is what "the outer hip opens up" looks like from outside.
     */
    @Test
    fun theCrossedHipIsExternallyRotated() {
        for (frame in frames()) {
            val thighZ = frame[Joint.KNEE_F].z - frame[Joint.HIP_F].z
            val shinZ = frame[Joint.ANKLE_F].z - frame[Joint.KNEE_F].z
            assertTrue(
                "the crossed thigh must point outboard at p=%.3f (its Z travel is %.2f)".format(frame.progress, thighZ),
                thighZ <= -30f
            )
            assertTrue(
                "the crossed shin must cross inboard at p=%.3f (its Z travel is %.2f)".format(frame.progress, shinZ),
                shinZ >= 60f
            )
            // The crossed knee is UP and OUT (the figure-4 knee), never buried in the mat.
            assertTrue(
                "the crossed knee is %.2f u off the mat at p=%.3f".format(frame[Joint.KNEE_F].y, frame.progress),
                frame[Joint.KNEE_F].y >= 40f
            )
        }
    }

    /** §3 — "Pull the uncrossed leg toward your chest": the pull is the hip flexion, and it really moves. */
    @Test
    fun thePullTakesTheUncrossedLegToTheAuthoredFlexion() {
        val f = frames()
        val entry = hipFlexionDeg(f.first(), Joint.HIP_B, Joint.KNEE_B)
        val hold = hipFlexionDeg(f[f.size / 2], Joint.HIP_B, Joint.KNEE_B)
        assertEquals(
            "the entry hip flexion is %.2f deg, not the authored %.1f".format(entry, PiriformisStretchPose.PULL_ENTRY_DEG),
            PiriformisStretchPose.PULL_ENTRY_DEG, entry, 3f
        )
        assertEquals(
            "the held hip flexion is %.2f deg, not the authored %.1f".format(hold, PiriformisStretchPose.PULL_HOLD_DEG),
            PiriformisStretchPose.PULL_HOLD_DEG, hold, 3f
        )
        assertTrue(
            "the pull takes the pulled knee only %.2f u".format(PoseFrameSweep.travel3D(f, Joint.KNEE_B)),
            PoseFrameSweep.travel3D(f, Joint.KNEE_B) >= 30f
        )
        // `tech`: "Pull the legs in only until the hip stretches" — measured, a controlled 22 deg.
        assertTrue(
            "the pull is %.1f deg of hip flexion".format(hold - entry),
            hold - entry in 15f..30f
        )
    }

    /** §4/§5 — the cycle IS an entry, a HOLD and a release: the plateau is measurably flat. */
    @Test
    fun theCycleHoldsTheStretchOnAPlateau() {
        val f = frames()
        val holdSamples = f.filter { it.progress >= 0.375f && it.progress <= 0.75f }
        assertTrue("the sweep does not sample the plateau (${holdSamples.size} samples)", holdSamples.size >= 3)
        val spread = holdSamples.maxOf { it[Joint.KNEE_B].x } - holdSamples.minOf { it[Joint.KNEE_B].x }
        assertTrue(
            "the hold is not held: the pulled knee drifts %.4f u across the plateau".format(spread),
            spread <= 0.05f
        )
        assertTrue(
            "the entry must differ from the hold (%.2f u)".format(
                abs(f.first()[Joint.KNEE_B].x - holdSamples.first()[Joint.KNEE_B].x)
            ),
            abs(f.first()[Joint.KNEE_B].x - holdSamples.first()[Joint.KNEE_B].x) >= 20f
        )
        // The loop seam closes: the release returns to the entry (progress 1 ≡ progress 0).
        assertEquals(
            "the stretch does not return to its entry at the loop's seam",
            0f, PoseFrameSweep.deviation(f, Joint.KNEE_B, 0, f.size - 1), 1e-3f
        )
        // and the sweep is not aliased: the plateau's repeated samples plus the entry/hold/release
        // frames are what its own 9-point sweep can contain (measured 5 distinct frames).
        assertTrue(
            "the sweep observed ${PoseFrameSweep.distinctFrameCount(f)} distinct frames",
            PoseFrameSweep.distinctFrameCount(f) >= 4
        )
    }

    /** §4 + `mistakes` — "Keep the tailbone heavy", "Twisting the pelvis off the floor". */
    @Test
    fun theTailboneStaysHeavyAndThePelvisNeverTwists() {
        val f = frames()
        for (joint in listOf(Joint.PELVIS, Joint.HIP_F, Joint.HIP_B)) {
            assertTrue(
                "$joint drifts %.3f u through the stretch".format(PoseFrameSweep.travel3D(f, joint)),
                PoseFrameSweep.travel3D(f, joint) <= 0.5f
            )
        }
        for (frame in f) {
            assertEquals(
                "the pelvis lifted off the mat at p=%.3f".format(frame.progress),
                PiriformisStretchPose.PELVIS_Y, frame.y(Joint.PELVIS), 0.5f
            )
            // A pelvis twisting about the body's long axis breaks the hips' level line first.
            assertTrue(
                "the hips are no longer level at p=%.3f (F %.3f vs B %.3f)".format(
                    frame.progress, frame[Joint.HIP_F].y, frame[Joint.HIP_B].y
                ),
                abs(frame[Joint.HIP_F].y - frame[Joint.HIP_B].y) <= 0.5f
            )
            assertEquals(
                "the pelvis's lateral line is not its anatomical width at p=%.3f".format(frame.progress),
                2f * def.hipWidth, frame[Joint.HIP_B].z - frame[Joint.HIP_F].z, 0.5f
            )
        }
    }

    /** `desc` — "keep the low back quiet": the trunk never arches off the mat during the pull. */
    @Test
    fun theLowBackStaysQuietOnTheMat() {
        for (frame in frames()) {
            val trunk = Vector3().set(frame[Joint.CHEST]).subtract(frame[Joint.PELVIS])
            val tiltFromTheMat = Math.toDegrees(
                kotlin.math.asin((trunk.y / trunk.mag()).coerceIn(-1f, 1f).toDouble())
            ).toFloat()
            assertTrue(
                "the trunk leaves the mat by %.1f deg at p=%.3f".format(tiltFromTheMat, frame.progress),
                abs(tiltFromTheMat) <= 3f
            )
        }
    }

    /** `tech` — "Flex the crossed foot to protect the knee": the authored articulation is LIVE. */
    @Test
    fun theCrossedFootIsFlexed() {
        val f = frames()
        // The measured realization of the authored CROSSED_ANKLE_DORSIFLEXION (see the pose's KDoc):
        // 87.00 deg at the cycle's entry / 90.04 at the hold with the articulation authored, against
        // 90.00 deg at EVERY phase with it removed — so this bound is what turns RED when the
        // articulation stops being authored (the value is pinned at the measured realization, not at
        // the authored angle, because the engine's derivation absorbs the remainder).
        val entry = PoseFrameSweep.interiorAngle(f.first(), Joint.KNEE_F, Joint.ANKLE_F, Joint.TOE_F)
        assertTrue(
            "the crossed foot's own angle is %.2f deg at the entry — it is not flexed".format(entry),
            entry <= 88.5f
        )
        // ... and only the CROSSED foot is articulated: the copy asks nothing of the pulled foot.
        val built = pose().build(PoseContext(0.5f, Side.LEFT, def))
        assertEquals(
            "the copy asks only about the crossed foot — no other extremity may be articulated",
            setOf(Extremity.FOOT_F),
            built.extremityArticulations.keys
        )
        assertEquals(
            "the authored dorsiflexion moved — re-measure the realization the KDoc records",
            0.35f, PiriformisStretchPose.CROSSED_ANKLE_DORSIFLEXION, 1e-6f
        )
    }

    /** §3 — the hands hold the thigh they pull, and they travel with it. */
    @Test
    fun theHandsHoldTheThighTheyPull() {
        val f = frames()
        for (frame in f) {
            val gap = pointToSegment(frame[Joint.HAND_A], frame[Joint.HIP_B], frame[Joint.KNEE_B])
            assertTrue(
                "HAND_A is %.2f u off the pulled thigh at p=%.3f".format(gap, frame.progress),
                gap <= 20f
            )
        }
        assertTrue(
            "the hands do not travel with the pull (%.2f u)".format(PoseFrameSweep.travel3D(f, Joint.HAND_A)),
            PoseFrameSweep.travel3D(f, Joint.HAND_A) >= 5f
        )
        // The reach record: the grip sits inside the arm chain's own band at every phase.
        val cap = SkeletonMath.maxReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
        for (frame in f) {
            val chord = Vector3().set(frame[Joint.HAND_A]).subtract(frame[Joint.SHOULDER_A]).mag()
            assertTrue(
                "the grasp demands %.2f u of the arm's %.2f at p=%.3f".format(chord, cap, frame.progress),
                chord <= cap
            )
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

    /** The floor invariant: a supine body never passes through the mat. */
    @Test
    fun nothingGoesBelowTheFloor() {
        val (joint, clearance) = PoseFrameSweep.worstClearance(frames(), def, level = 0f)
        assertTrue("$joint is %.3f u below the floor".format(clearance), clearance >= -0.05f)
    }

    /** The declared support: the tailbone is what this pose rests on. */
    @Test
    fun theTailboneIsDeclaredAsTheSupport() {
        assertEquals(
            "the supine stretch rests on its core point",
            setOf(SupportPoint.HIPS),
            pose().metadata.support.contacts.map { it.point }.toSet()
        )
    }
}
