package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.HumanSkeletonDefinition
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * The rest data is a *derivation* of the bind pose, not a second table: every declared rest
 * direction and rest length is recomputed here from the X1 joints built from the definition.
 */
class SegmentRestPoseTest {

    private val definition: SkeletonDefinition = AnatomyPoseFixture.DEFINITION
    private val bind = AnatomyPoseFixture.bindJoints(definition)

    @Test
    fun everyRestDirectionIsTheBindDirectionReflectedIntoItsRestFrame() {
        var checked = 0
        for (id in SegmentId.entries) {
            val rest = SegmentRest.restDir(id)
            val spec = SegmentSpec.of(id)
            if (spec.inherits) {
                assertNull("${id.name} inherits and must declare no direction", rest)
                continue
            }
            val world = AnatomyPoseFixture.direction(
                bind.getValue(spec.node!!),
                bind.getValue(spec.child!!)
            ) ?: fail("${id.name} has no bind direction to reflect").let { return }
            // restDir = BIND_FRAMEᵀ · world, and BIND_FRAME is its own transpose.
            val expected = Vector3(-world.x, world.y, -world.z)
            val error = AnatomyPoseFixture.angleBetween(rest!!, expected)
            assertTrue(
                "${id.name}: declared rest direction $rest is not the reflected bind direction $expected ($error rad)",
                error <= 1e-6f
            )
            checked++
        }
        assertEquals("eighteen rows declare a rest direction", 18, checked)
    }

    @Test
    fun everyRestLengthIsTheLengthOfItsOwnPairAtBind() {
        for (segment in Segment.entries) {
            val declared = SegmentRest.restLength(segment, definition)
            val pairs = SegmentId.of(segment).mapNotNull { id ->
                val spec = SegmentSpec.of(id)
                if (spec.derives && spec.node != null && spec.child != null) {
                    val a = bind.getValue(spec.node)
                    val b = bind.getValue(spec.child)
                    Vector3(b.x - a.x, b.y - a.y, b.z - a.z).mag()
                } else {
                    null
                }
            }
            if (pairs.isEmpty()) {
                assertEquals("$segment has no position pair, so no rest length", 0f, declared, 0f)
            } else {
                for (length in pairs) {
                    assertEquals("$segment rest length is its bind span", length, declared, 1e-3f)
                }
            }
        }
    }

    @Test
    fun theZeroLengthRowsAreExactlyTheOnesWithoutAPair() {
        val zero = Segment.entries.filter { SegmentRest.restLength(it, definition) == 0f }.toSet()
        assertEquals(setOf(Segment.CLAVICLE, Segment.SKULL, Segment.TOES), zero)
    }

    @Test
    fun theRestLengthsComeFromTheDefinitionsOwnFields() {
        val scaled = HumanSkeletonDefinition(
            torsoLength = 150f,
            neckLength = 21f,
            thighLength = 130f,
            shinLength = 110f,
            upperArmLength = 95f,
            forearmLength = 78f,
            shoulderWidth = 52f,
            hipWidth = 26f
        )
        assertEquals(150f, SegmentRest.restLength(Segment.LUMBAR, scaled), 1e-3f)
        assertEquals(21f, SegmentRest.restLength(Segment.THORAX, scaled), 1e-3f)
        assertEquals(130f, SegmentRest.restLength(Segment.FEMUR, scaled), 1e-3f)
        assertEquals(110f, SegmentRest.restLength(Segment.TIBIA, scaled), 1e-3f)
        assertEquals(95f, SegmentRest.restLength(Segment.HUMERUS, scaled), 1e-3f)
        assertEquals(78f, SegmentRest.restLength(Segment.FOREARM, scaled), 1e-3f)
        assertEquals(52f, SegmentRest.restLength(Segment.SCAPULA, scaled), 1e-3f)
        assertEquals(
            scaled.hand.palmLength + scaled.hand.fingerLength,
            SegmentRest.restLength(Segment.HAND, scaled),
            1e-3f
        )
        assertEquals(
            scaled.foot.footLength * scaled.foot.toeRatio,
            SegmentRest.restLength(Segment.FOOT, scaled),
            1e-3f
        )
        assertEquals(52f, SegmentRest.hipLineRestLength(scaled), 1e-3f)
    }

    @Test
    fun theHipLineSpanIsTwoHipWidthsAndMatchesTheBindPose() {
        assertEquals(2f * definition.hipWidth, SegmentRest.hipLineRestLength(definition), 1e-4f)
        val bindSpan = Vector3(
            bind.getValue(Joint.HIP_F).x - bind.getValue(Joint.HIP_B).x,
            bind.getValue(Joint.HIP_F).y - bind.getValue(Joint.HIP_B).y,
            bind.getValue(Joint.HIP_F).z - bind.getValue(Joint.HIP_B).z
        ).mag()
        assertEquals("the root frame's lateral direction is the hip line", bindSpan, SegmentRest.hipLineRestLength(definition), 1e-3f)
    }

    @Test
    fun aDefinitionWithoutAHipLineOrATorsoIsRefusedBeforeAnyFrameIsDerived() {
        assertRefused("hip width", HumanSkeletonDefinition(hipWidth = 0f))
        assertRefused("negative hip width", HumanSkeletonDefinition(hipWidth = -1f))
        assertRefused("torso length", HumanSkeletonDefinition(torsoLength = 0f))
        assertRefused("negative torso length", HumanSkeletonDefinition(torsoLength = -5f))
    }

    @Test
    fun theBindFrameIsTheHalfTurnAboutWorldUpAndSquaresToIdentity() {
        val frame = SegmentRest.BIND_FRAME
        assertEquals("nine slots", 9, frame.size)
        assertEquals(-1f, frame[0], 0f)
        assertEquals(1f, frame[4], 0f)
        assertEquals(-1f, frame[8], 0f)
        assertEquals("the off-diagonal is empty", 0f, frame[1] + frame[2] + frame[3] + frame[5] + frame[6] + frame[7], 0f)

        // A half-turn squares to the identity, which is what makes BIND · restDir the bind direction.
        for (row in 0..2) for (col in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += frame[row * 3 + k] * frame[k * 3 + col]
            assertEquals("BIND²[$row,$col]", if (row == col) 1f else 0f, sum, 1e-7f)
        }
        // det = +1: the bind frame is a proper rotation, so a mirrored pair cannot hide behind it.
        val det = frame[0] * (frame[4] * frame[8] - frame[5] * frame[7]) -
            frame[3] * (frame[1] * frame[8] - frame[2] * frame[7]) +
            frame[6] * (frame[1] * frame[5] - frame[2] * frame[4])
        assertEquals(1f, det, 1e-6f)
    }

    @Test
    fun theThresholdsArePositiveAndOrdered() {
        assertTrue(SegmentRest.TAU_LEN > 0f)
        // The test's own direction tolerance has to sit below the production branch threshold, or an
        // asserted direction could not tell a NEAR_PARALLEL frame from the general branch.
        assertTrue(SegmentRest.TAU_ROT > AnatomyPoseFixture.TAU_DIR)
        assertTrue("a direction tolerance below the float noise of a unit vector is not a tolerance", AnatomyPoseFixture.TAU_DIR >= 1e-6f)
        assertTrue("TAU_ROT is an angular threshold, so it must be far below a right angle", SegmentRest.TAU_ROT < 0.1f)
        assertTrue(abs(SegmentRest.TAU_LEN - 1e-6f) < 1e-12f)
    }

    @Test
    fun theRestPoleExistsOnlyWhereTheBindChainIsBent() {
        val anchored = SegmentId.entries.filter { SegmentRest.restPole(it) != null }.toSet()
        assertEquals(
            "the bind pose is straight-limbed, so only the hip, shoulder and ankle planes exist at rest",
            setOf(
                SegmentId.HUMERUS_A, SegmentId.HUMERUS_P,
                SegmentId.FEMUR_F, SegmentId.FEMUR_B,
                SegmentId.FOOT_F, SegmentId.FOOT_B
            ),
            anchored
        )
        // Each declared rest pole is perpendicular to the segment's own rest direction by construction.
        for (id in anchored) {
            val pole = SegmentRest.restPole(id)!!
            val rest = SegmentRest.restDir(id)!!
            assertEquals("${id.name} rest pole is perpendicular to its direction", 0f, pole.dot(rest), 1e-6f)
            assertEquals("${id.name} rest pole is a unit vector", 1f, pole.mag(), 1e-6f)
        }
    }

    private fun assertRefused(what: String, candidate: SkeletonDefinition) {
        try {
            SegmentFrames(candidate)
            fail("$what: a definition the root frame cannot be built from must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "$what: the message must name the offending field: ${expected.message}",
                expected.message!!.contains("must be positive")
            )
        }
    }
}
