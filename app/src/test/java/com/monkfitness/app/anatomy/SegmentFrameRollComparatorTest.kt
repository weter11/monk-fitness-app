package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The comparator's declared policy: re-seed every frame, hold a pole when the bend plane cannot
 * state one, mark the transition across that threshold, and report no angle it did not measure.
 */
class SegmentFrameRollComparatorTest {

    private val frames = SegmentFrames(AnatomyPoseFixture.DEFINITION)
    private val set = SegmentFrameSet()
    private val comparator = PoleComparator(AnatomyPoseFixture.DEFINITION)

    @Test
    fun aFirstWellConditionedFrameSeedsThePoleAndMeasures() {
        comparator.measure(kneeBend(30f), set)
        assertEquals(PoleRollStatus.POLE_MEASURED, comparator.status(SegmentId.TIBIA_F))
        assertTrue("a measured roll is an angle, not a placeholder", comparator.theta(SegmentId.TIBIA_F).isFinite())
        assertTrue(comparator.theta(SegmentId.TIBIA_F) <= HALF_PI)
    }

    @Test
    fun anIllConditionedBendHoldsThePoleAndReportsNoAngle() {
        comparator.measure(kneeBend(30f), set)
        val seeded = Vector3()
        assertTrue(comparator.pole(SegmentId.TIBIA_F, seeded))

        comparator.measure(kneeBend(2f), set)
        assertEquals("the first held frame is the transition", PoleRollStatus.POLE_TRANSITION, comparator.status(SegmentId.TIBIA_F))
        assertTrue("no angle from a held pole", comparator.theta(SegmentId.TIBIA_F).isNaN())

        comparator.measure(kneeBend(1f), set)
        assertEquals("the held run continues", PoleRollStatus.POLE_HELD, comparator.status(SegmentId.TIBIA_F))
        assertTrue(comparator.theta(SegmentId.TIBIA_F).isNaN())

        val held = Vector3()
        assertTrue(comparator.pole(SegmentId.TIBIA_F, held))
        assertEquals("the pole is the one that was seeded", seeded.x, held.x, 1e-6f)
        assertEquals(seeded.y, held.y, 1e-6f)
        assertEquals(seeded.z, held.z, 1e-6f)
    }

    @Test
    fun leavingTheIllConditionedBandReseedsAndMarksTheTransition() {
        comparator.measure(kneeBend(30f), set)
        comparator.measure(kneeBend(1f), set)
        comparator.measure(kneeBend(30f), set)
        assertEquals("the frame that re-seeds is a transition", PoleRollStatus.POLE_TRANSITION, comparator.status(SegmentId.TIBIA_F))
        assertTrue("and it carries no angle", comparator.theta(SegmentId.TIBIA_F).isNaN())

        comparator.measure(kneeBend(30f), set)
        assertEquals("the next conditioned frame measures again", PoleRollStatus.POLE_MEASURED, comparator.status(SegmentId.TIBIA_F))
        assertTrue(comparator.theta(SegmentId.TIBIA_F).isFinite())
    }

    @Test
    fun anUnseededIllConditionedFrameIsUninitializedNotHeld() {
        comparator.measure(kneeBend(1f), set)
        assertEquals(PoleRollStatus.POLE_UNINITIALIZED, comparator.status(SegmentId.TIBIA_F))
        assertTrue(comparator.theta(SegmentId.TIBIA_F).isNaN())
        assertFalse("no pole exists yet", comparator.pole(SegmentId.TIBIA_F, Vector3()))
    }

    @Test
    fun aDegenerateLegDropsThePoleRatherThanHoldingIt() {
        comparator.measure(kneeBend(30f), set)
        assertEquals(PoleRollStatus.POLE_MEASURED, comparator.status(SegmentId.TIBIA_F))

        comparator.measure(kneeBend(30f, ankleOnKnee = true), set)
        assertEquals(
            "a leg with no length is degenerate, and it outranks the hold precedence",
            PoleRollStatus.POLE_LEG_DEGENERATE,
            comparator.status(SegmentId.TIBIA_F)
        )
        assertTrue(comparator.theta(SegmentId.TIBIA_F).isNaN())
        assertFalse("the carried pole is void once a leg collapses", comparator.pole(SegmentId.TIBIA_F, Vector3()))

        comparator.measure(kneeBend(30f), set)
        assertEquals("the next conditioned frame re-seeds", PoleRollStatus.POLE_MEASURED, comparator.status(SegmentId.TIBIA_F))
    }

    @Test
    fun aStructuralRowReportsUnavailableAndNeverAZeroAngle() {
        comparator.measure(kneeBend(30f), set)
        for (id in SegmentId.entries) {
            if (PoleComparator.chainOf(id) != null) continue
            assertEquals("${id.name} has no bend plane", PoleRollStatus.POLE_UNAVAILABLE, comparator.status(id))
            assertTrue("${id.name} must report NaN, never 0", comparator.theta(id).isNaN())
        }
    }

    @Test
    fun resetDropsEveryCarriedPole() {
        comparator.measure(kneeBend(30f), set)
        assertTrue(comparator.theta(SegmentId.TIBIA_F).isFinite())
        comparator.reset()
        for (id in SegmentId.entries) {
            assertEquals("${id.name} after reset", PoleRollStatus.POLE_UNAVAILABLE, comparator.status(id))
            assertTrue(comparator.theta(id).isNaN())
            assertFalse(comparator.pole(id, Vector3()))
        }
        comparator.measure(kneeBend(1f), set)
        assertEquals("nothing is held after a reset", PoleRollStatus.POLE_UNINITIALIZED, comparator.status(SegmentId.TIBIA_F))
    }

    @Test
    fun noUnmeasuredStatusEverReportsAnAngle() {
        val poses = listOf(kneeBend(30f), kneeBend(1f), kneeBend(1f), kneeBend(30f), kneeBend(30f, ankleOnKnee = true))
        for (pose in poses) {
            comparator.measure(pose, set)
            for (id in SegmentId.entries) {
                if (comparator.status(id) == PoleRollStatus.POLE_MEASURED) {
                    assertTrue(
                        "${id.name} is measured and must carry an angle",
                        comparator.theta(id).isFinite()
                    )
                    assertTrue("${id.name} roll is an unsigned plane error", comparator.theta(id) in 0f..HALF_PI)
                } else {
                    assertTrue(
                        "${id.name} is ${comparator.status(id)} and must report NaN, never a number",
                        comparator.theta(id).isNaN()
                    )
                }
            }
        }
    }

    @Test
    fun theBindPoseAnchorsTheRowsWhoseRestChainStatesAPlane() {
        frames.derive(AnatomyPoseFixture.bindPose(), set)
        comparator.measure(AnatomyPoseFixture.bindPose(), set)
        var anchored = 0
        for (id in SegmentId.entries) {
            val restPole = SegmentRest.restPole(id)
            if (restPole == null) {
                assertTrue(
                    "${id.name} has no rest plane, so it cannot claim a measured roll at bind",
                    comparator.status(id) != PoleRollStatus.POLE_MEASURED
                )
                continue
            }
            anchored++
            assertEquals("${id.name} is the rest roll by construction", PoleRollStatus.POLE_MEASURED, comparator.status(id))
            assertEquals("${id.name} at bind carries the rest roll", 0f, comparator.theta(id), 1e-4f)
        }
        assertEquals("six instances state a rest plane", 6, anchored)
    }

    @Test
    fun theComparatorOnlyReportsRowsThatHaveAChain() {
        for (pose in AnatomyPoseFixture.swept("pushup_standard")) {
            comparator.measure(pose, set)
            for (id in SegmentId.entries) {
                if (PoleComparator.chainOf(id) == null) {
                    assertEquals(PoleRollStatus.POLE_UNAVAILABLE, comparator.status(id))
                }
            }
        }
    }

    /**
     * A pose whose knee bends by [degrees] in the sagittal plane, with the hip and pelvis at their
     * bind positions so only the chain under test moves.
     */
    private fun kneeBend(degrees: Float, ankleOnKnee: Boolean = false): SkeletonPose {
        val joints = HashMap(AnatomyPoseFixture.bindJoints())
        val knee = joints.getValue(Joint.KNEE_F)
        val ankle = if (ankleOnKnee) {
            Vector3(knee.x, knee.y, knee.z)
        } else {
            val angle = (degrees * Math.PI / 180.0).toFloat()
            val scale = AnatomyPoseFixture.DEFINITION.shinLength
            Vector3(
                knee.x + sin(angle) * scale,
                knee.y - cos(angle) * scale,
                knee.z
            )
        }
        joints[Joint.ANKLE_F] = ankle
        val pose = AnatomyPoseFixture.poseOf(joints)
        frames.derive(pose, set)
        return pose
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2.0).toFloat()
    }
}
