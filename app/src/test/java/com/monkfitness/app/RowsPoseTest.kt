package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.poses.RowsPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Inverted Bodyweight Row (`row_standard`) — the exercise's own biomechanics on the PUBLISHED frame.
 *
 * Stated intent (the only in-repo specification for this exercise; no BPS exists for it):
 * `ex_rows` title "Inverted Bodyweight Row" + `requiredEquipment = setOf(Equipment.BAR)` +
 * `imageRes = R.drawable.pull_up` (the catalog entry), `ex_rows_desc` "A pull exercise that builds
 * thickness in the upper and middle back", `ex_rows_steps` §2 "Pull the weight toward your lower ribs,
 * **squeezing shoulder blades together**". The `steps`/`tech` body describes a *bent-over weighted
 * row*; that contradiction is recorded in `rows`' KDoc and in `docs/ANIMATION_COVERAGE_PHASE.md` §2 as
 * stale copy, and the pose authors the bar-supported movement the id/title/equipment declare.
 *
 * Every assertion below reads the **published frame** through [PoseFrameSweep] (the pipeline entered
 * exactly as `SkeletonRenderer` enters it): a posed limb that is IK-baked late carries the previous
 * frame's world until the Finalizer re-runs FK, so a bare `build()` reading measures geometry the
 * athlete never sees.
 */
class RowsPoseTest {

    private val ID = "row_standard"
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun pose() = RowsPose()
    private fun frames() = PoseFrameSweep.sweep(pose())

    private val barY = RowsPose.BAR_Y
    private val barX = RowsPose.BAR_X
    private val gripZ = RowsPose.GRIP_WIDTH_FACTOR * def.shoulderWidth

    @Test
    fun theExerciseIsDrivenByARealSkeletalAnimation() {
        val cfg = PoseRegistry.getPoseConfig(ID)
        assertNotNull("$ID has no PoseRegistry config — ExerciseHero would fall back to the icon", cfg)
        assertEquals(RowsPose::class.java, cfg!!.builder.javaClass)
        assertTrue(ID in PoseRegistry.getDedicatedAnimationIds())
    }

    @Test
    fun theSweepSamplesDistinctPublishedFrames() {
        val f = frames()
        assertEquals(f.size, PoseFrameSweep.distinctFrameCount(f))
    }

    /** The bar is part of the exercise (the wall-seat lesson): a row without its bar is not a row. */
    @Test
    fun theBarExistsAndBothHandsGripIt() {
        val env = pose().metadata.environment
        assertTrue(
            "the row defines no box-like bar slab — the athlete pulls against empty space",
            env.props.filterIsInstance<BoxProp>().size == 1
        )
        val anchor = env.anchors.singleOrNull()
        assertNotNull("no bar anchor for the declared hand contacts", anchor)
        assertEquals(EnvironmentAnchorType.BAR, anchor!!.type)
        assertEquals(
            "the declared hand contacts must name the bar anchor",
            setOf("LEFT_HAND", "RIGHT_HAND"),
            pose().metadata.support.contacts.filter { it.anchorId != null }.map { it.point.name }.toSet()
        )
        assertEquals(
            "the pull pivots about the hands (the body moves relative to the grip)",
            PivotType.HANDS, pose().metadata.support.pivot
        )
        // The hands are IK'd to CONSTANTS: the grip does not move by a single float unit.
        for (frame in frames()) {
            assertEquals("HAND_A left its grip at p=%.3f (x)".format(frame.progress), barX, frame[Joint.HAND_A].x, 0.01f)
            assertEquals("HAND_A left its grip at p=%.3f (y)".format(frame.progress), barY, frame[Joint.HAND_A].y, 0.01f)
            assertEquals("HAND_A left its grip at p=%.3f (z)".format(frame.progress), -gripZ, frame[Joint.HAND_A].z, 0.01f)
            assertEquals("HAND_P left its grip at p=%.3f".format(frame.progress), gripZ, frame[Joint.HAND_P].z, 0.01f)
        }
    }

    /** `desc`: "a pull exercise" — the reach the arms take IS the pull, and it shortens through the rep. */
    @Test
    fun theShoulderToBarReachShortensIntoThePull() {
        val reaches = frames().map { f ->
            Vector3().set(f[Joint.HAND_A]).subtract(f[Joint.SHOULDER_A]).mag()
        }
        val bottom = reaches.first()
        val top = reaches.last()
        assertEquals(
            "the bottom of the rep must be the authored near-full-extension reach",
            RowsPose.BOTTOM_REACH, bottom, 0.05f
        )
        assertTrue(
            "the pull must shorten the shoulder→bar reach (measured %.2f → %.2f)".format(bottom, top),
            top < bottom - 40f
        )
        for (i in 1 until reaches.size) {
            assertTrue(
                "the reach must shrink monotonically through the pull (%.2f → %.2f)".format(reaches[i - 1], reaches[i]),
                reaches[i] <= reaches[i - 1] + 0.01f
            )
        }
    }

    /** The elbows fold as the chest comes to the bar (a pull with a fixed grip can only fold at the elbow). */
    @Test
    fun theElbowsFoldAsTheChestComesToTheBar() {
        val angles = frames().map { PoseFrameSweep.interiorAngle(it, Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A) }
        val bottom = angles.first()
        val top = angles.last()
        assertTrue(
            "at the bottom the arm must be at the chain's near-full extension (measured %.1f deg)".format(bottom),
            bottom >= 138f
        )
        assertTrue(
            "the elbows must fold at the top of the pull (measured %.1f → %.1f deg)".format(bottom, top),
            top <= 70f
        )
        for (i in 1 until angles.size) {
            assertTrue(
                "the elbow must close monotonically (%.1f → %.1f deg)".format(angles[i - 1], angles[i]),
                angles[i] <= angles[i - 1] + 0.01f
            )
        }
    }

    /** The body is ONE line from the planted heels to the shoulders (an incline row has no hip hinge). */
    @Test
    fun theBodyIsOneStraightLineFromTheHeelsToTheShoulders() {
        for (frame in frames()) {
            // The line is measured ankle → hip → CHEST: the chest is the body's own end of the trunk
            // chain, while the shoulder joint sits at the girdle's lateral offset, which the scapular
            // retraction deliberately rotates (a shoulder reading would report the girdle's own
            // travel as a "bend" — measured `4.04 u` at the top of the pull).
            // Measured in the SAGITTAL plane (X/Y): the stance gives each joint its own lateral
            // offset (the heel at ±1.4·hipWidth), so a 3-D colinearity reading would report the
            // stance's own width as a bend.
            val deviation = perpendicularDeviationSagittal(frame[Joint.ANKLE_F], frame[Joint.HIP_F], frame[Joint.CHEST])
            assertTrue(
                "the hip is %.2f u off the heel→chest line at p=%.3f — the body must be one line".format(deviation, frame.progress),
                deviation <= 1f
            )
            val knee = PoseFrameSweep.interiorAngle(frame, Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F)
            assertTrue(
                "the knee is bent to %.1f deg at p=%.3f — the leg is one straight segment".format(knee, frame.progress),
                knee >= 145f
            )
        }
        // The two spine segments are colinear with the leg line too (the trunk is not a hinge).
        val trunk = frames().map { PoseFrameSweep.interiorAngle(it, Joint.PELVIS, Joint.CHEST, Joint.NECK_END) }
        assertTrue(
            "the trunk must carry no hinge of its own (worst trunk angle %.1f deg)".format(trunk.min()),
            trunk.min() >= 150f
        )
    }

    /** The incline IS the rep: the body rises from the hanging bottom to the pulled-up top. */
    @Test
    fun theBodyRisesFromItsHangingBottomToThePulledUpTop() {
        val f = frames()
        val travel = PoseFrameSweep.travelY(f, Joint.SHOULDER_A)
        assertTrue(
            "measured shoulder travel %.2f u (bottom %.2f → top %.2f)".format(travel, f.first().y(Joint.SHOULDER_A), f.last().y(Joint.SHOULDER_A)),
            travel >= 60f
        )
        val pelvisRise = f.last().y(Joint.PELVIS) - f.first().y(Joint.PELVIS)
        assertTrue("the hips must rise with the body (measured %.2f u)".format(pelvisRise), pelvisRise >= 35f)
        // The incline is the pose's own closed form; the published body must realize it.
        for (frame in frames()) {
            val expected = RowsPose.inclineFor(
                SkeletonMath.lerp(RowsPose.BOTTOM_REACH, RowsPose.TOP_REACH, frame.progress), def
            )
            val actual = Math.toDegrees(
                kotlin.math.atan2(
                    (frame[Joint.CHEST].y - frame[Joint.PELVIS].y).toDouble(),
                    (frame[Joint.CHEST].x - frame[Joint.PELVIS].x).toDouble()
                )
            ).toFloat()
            assertEquals(
                "the published trunk incline at p=%.3f must be the incline the reach implies".format(frame.progress),
                Math.toDegrees(expected.toDouble()).toFloat(), actual, 1.5f
            )
        }
        assertTrue(
            "the authored endpoints must be the documented inclines (bottom %.4f, top %.4f rad)".format(RowsPose.BOTTOM_INCLINE, RowsPose.TOP_INCLINE),
            abs(RowsPose.inclineFor(RowsPose.BOTTOM_REACH, def) - RowsPose.BOTTOM_INCLINE) <= 2e-3f &&
                abs(RowsPose.inclineFor(RowsPose.TOP_REACH, def) - RowsPose.TOP_INCLINE) <= 2e-3f
        )
    }

    /** The chest comes to the bar: the shoulder arrives at the bar's own planes at the top of the pull. */
    @Test
    fun theShoulderArrivesAtTheBarAtTheTopOfThePull() {
        val f = frames()
        val bottom = f.first()
        val top = f.last()
        val topXGap = abs(barX - top[Joint.SHOULDER_A].x)
        val topYGap = barY - top[Joint.SHOULDER_A].y
        val bottomYGap = barY - bottom[Joint.SHOULDER_A].y
        assertTrue(
            "the shoulder must arrive at the bar's X plane (measured %.2f u)".format(topXGap),
            topXGap <= RowsPose.BAR_ARRIVAL_X
        )
        assertTrue(
            "the shoulder must arrive close below the bar (measured %.2f u at the top, %.2f at the bottom)".format(topYGap, bottomYGap),
            topYGap <= 80f && topYGap < bottomYGap - 50f
        )
    }

    /** The feet are the fixed end of the body line: planted, flat, and pointing the way the athlete faces. */
    @Test
    fun theFeetStayPlantedFlatOnTheMat() {
        val f = frames()
        for (joint in listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)) {
            val worst = f.indices.maxOf { PoseFrameSweep.deviation(f, joint, 0, it) }
            assertTrue("$joint slides %.3f u during the row".format(worst), worst <= 0.5f)
        }
        for (frame in f) {
            for ((heel, toe, ankle) in listOf(
                Triple(Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_F),
                Triple(Joint.HEEL_B, Joint.TOE_B, Joint.ANKLE_B)
            )) {
                assertTrue("$heel is not flat (y=%.3f vs ankle %.3f)".format(frame[heel].y, frame[ankle].y), abs(frame[heel].y - frame[ankle].y) <= 0.75f)
                assertTrue("$toe is not flat (y=%.3f vs ankle %.3f)".format(frame[toe].y, frame[ankle].y), abs(frame[toe].y - frame[ankle].y) <= 0.75f)
                // The toes point the way the athlete faces (+X, toward the bar's side of the body).
                val long = Vector3().set(frame[toe]).subtract(frame[heel])
                assertTrue(
                    "the foot's long axis is %.1f deg off the athlete's own forward direction".format(
                        Math.toDegrees(kotlin.math.atan2(long.z.toDouble(), long.x.toDouble())).toFloat()
                    ),
                    long.x > 0f && abs(long.z) <= 1f
                )
            }
        }
    }

    /** `steps` §2's "squeezing shoulder blades together": the girdle retracts as the pull completes. */
    @Test
    fun theShoulderBladesSqueezeTogetherThroughThePull() {
        val f = frames()
        // Measured as the shoulder's own offset from the chest — the girdle's effect alone, isolated
        // from the body's rise (the chest moves too). Retraction swings the glenoid's lateral offset
        // about the trunk's axis, so it reads as a posterior travel of the shoulder off the chest.
        val startOffset = f.first()[Joint.SHOULDER_A].x - f.first()[Joint.CHEST].x
        val endOffset = f.last()[Joint.SHOULDER_A].x - f.last()[Joint.CHEST].x
        assertTrue(
            "the shoulder must retract posteriorly off the chest (measured %.2f → %.2f u)".format(startOffset, endOffset),
            startOffset <= 0.01f && endOffset <= -3f
        )
        for (frame in f) {
            // The girdle never splays the shoulders laterally: the offset stays the anatomical width.
            assertEquals(
                "the girdle moved the shoulder laterally at p=%.3f".format(frame.progress),
                -def.shoulderWidth, frame[Joint.SHOULDER_A].z - frame[Joint.CHEST].z, 1f
            )
        }
    }

    /**
     * The gripping hand lies IN the bar's plane: the palm chain may not slope out of the bar's top face
     * (the surface the declared hand contact names). Measured as the engine's own penetration band.
     */
    @Test
    fun theGripStaysInTheBarsOwnPlane() {
        for (frame in frames()) {
            for (chain in listOf(
                listOf(Joint.HAND_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A),
                listOf(Joint.HAND_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)
            )) {
                for (joint in chain) {
                    val off = frame[joint].y - barY
                    assertTrue(
                        "$joint is %.3f u off the bar's top plane at p=%.3f".format(off, frame.progress),
                        abs(off) <= 2f
                    )
                }
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

    /** Distance of [p] from the line through [a] and [b], in the sagittal (X/Y) plane. */
    private fun perpendicularDeviationSagittal(p: Vector3, a: Vector3, b: Vector3): Float {
        val abx = b.x - a.x; val aby = b.y - a.y
        val apx = p.x - a.x; val apy = p.y - a.y
        val len = sqrt(abx * abx + aby * aby)
        if (len < 1e-6f) return sqrt(apx * apx + apy * apy)
        return abs(abx * apy - aby * apx) / len
    }
}
