package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * Attachment resolution is the join between authored metres and the engine's published geometry, so
 * every claim it makes is checkable against two independent sources: [X1Bind]'s positions and
 * [SegmentRest]'s bind frame.
 */
class MuscleAttachmentTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(skeleton)
    private val frames = SegmentFrames(skeleton)
    private val frameSet = SegmentFrameSet()
    private val builder = MusclePathBuilder(definition, unitsPerMetre)
    private val bind = X1Bind.bindJoints(skeleton)
    private val bindPose: SkeletonPose = AnatomyPoseFixture.bindPose(skeleton)
    private val out = Vector3()
    private val other = Vector3()

    private val slots: Map<String, Int> = AnatomyFrame.of(definition).let { reference ->
        reference.heads.withIndex().associate { (slot, id) -> "${id.muscleId}|${id.side}|${id.headIndex}" to slot }
    }

    private val heads: List<Triple<MuscleDefinition, MuscleSide, MuscleHead>> =
        definition.muscles.flatMap { muscle ->
            MuscleSide.entries.flatMap { side -> muscle.heads.map { Triple(muscle, side, it) } }
        }

    @Test
    fun atBindAnAttachmentLandsOnTheAuthoredOffsetThroughTheBindFrame() {
        frames.derive(bindPose, frameSet)
        var checked = 0
        for ((muscle, side, head) in heads) {
            for (attachment in head.attachments) {
                val expected = expectedAtBind(attachment, side)
                assertTrue("${muscle.id} $side would not resolve", builder.resolve(attachment, side, bindPose, frameSet, out))
                assertEquals("$muscle.id $side x", expected.x, out.x, BIND_TOLERANCE)
                assertEquals("$muscle.id $side y", expected.y, out.y, BIND_TOLERANCE)
                assertEquals("$muscle.id $side z", expected.z, out.z, BIND_TOLERANCE)
                checked++
            }
        }
        assertTrue("the library must exercise more than an empty loop", checked >= 18)
    }

    @Test
    fun theTwoSidesAreMirrorsAboutTheMidline() {
        frames.derive(bindPose, frameSet)
        var mirrored = 0
        for ((muscle, _, head) in heads.filter { it.second == MuscleSide.A }) {
            for (index in head.attachments.indices) {
                val attachment = head.attachments[index]
                builder.resolve(attachment, MuscleSide.A, bindPose, frameSet, out)
                builder.resolve(attachment, MuscleSide.P, bindPose, frameSet, other)

                assertEquals("$muscle.id attachment $index keeps its sagittal coordinate", out.x, other.x, BIND_TOLERANCE)
                assertEquals("$muscle.id attachment $index keeps its height", out.y, other.y, BIND_TOLERANCE)
                assertEquals("$muscle.id attachment $index mirrors laterally", -out.z, other.z, BIND_TOLERANCE)
                if (attachment.z != 0f) mirrored++
            }
        }
        assertTrue("a lateral offset must exist, or the mirror check proves nothing", mirrored > 0)
    }

    @Test
    fun aSecondUnitsPerMetreScalesTheOffsetAndNotTheJoint() {
        val doubled = MusclePathBuilder(definition, unitsPerMetre * 2f)
        frames.derive(bindPose, frameSet)
        val attachment = definition.muscle("latissimus_dorsi")!!.heads.single().insertion
        val id = MuscleSide.A.instance(attachment.segment)
        val origin = bind.getValue(SegmentSpec.of(id).node!!)

        builder.resolve(attachment, MuscleSide.A, bindPose, frameSet, out)
        doubled.resolve(attachment, MuscleSide.A, bindPose, frameSet, other)

        assertEquals("x offset doubles", (out.x - origin.x) * 2f, other.x - origin.x, BIND_TOLERANCE)
        assertEquals("y offset doubles", (out.y - origin.y) * 2f, other.y - origin.y, BIND_TOLERANCE)
        assertEquals("z offset doubles", (out.z - origin.z) * 2f, other.z - origin.z, BIND_TOLERANCE)
    }

    @Test
    fun aPosesRotationsCannotMoveAnAttachment() {
        frames.derive(bindPose, frameSet)
        val attachment = definition.muscle("rectus_femoris")!!.heads.single().waypoints.single()
        builder.resolve(attachment, MuscleSide.A, bindPose, frameSet, out)

        // Same positions, every authored rotation replaced: the stage must not read them at all.
        for (joint in Joint.entries) bindPose.rotations[joint.index].set(0.3f, -0.7f, 0.5f, 1.1f)
        val scrambledFrames = SegmentFrameSet()
        frames.derive(bindPose, scrambledFrames)
        builder.resolve(attachment, MuscleSide.A, bindPose, scrambledFrames, other)

        assertEquals("rotations must not reach the resolver (x)", out.x, other.x, 0f)
        assertEquals("rotations must not reach the resolver (y)", out.y, other.y, 0f)
        assertEquals("rotations must not reach the resolver (z)", out.z, other.z, 0f)
    }

    @Test
    fun thePathStartsAtTheOriginAndEndsAtTheInsertion() {
        frames.derive(bindPose, frameSet)
        val muscle = definition.muscle("rectus_femoris")!!
        val head = muscle.heads.single()
        val frame = AnatomyFrame.of(definition)
        val slot = slotOf(muscle.id, MuscleSide.A, 0)

        val count = builder.build(head, MuscleSide.A, bindPose, frameSet, frame, slot)
        assertTrue("the path must be resampled", count >= definition.sampling.minSamples)

        builder.resolve(head.origin, MuscleSide.A, bindPose, frameSet, out)
        frame.pathTo(slot, 0, other)
        assertEquals("the first point is the resolved origin (x)", out.x, other.x, 1e-3f)
        assertEquals(out.y, other.y, 1e-3f)
        assertEquals(out.z, other.z, 1e-3f)

        builder.resolve(head.insertion, MuscleSide.A, bindPose, frameSet, out)
        frame.pathTo(slot, count - 1, other)
        assertEquals("the last point is the resolved insertion (x)", out.x, other.x, 1e-3f)
        assertEquals(out.y, other.y, 1e-3f)
        assertEquals(out.z, other.z, 1e-3f)
    }

    @Test
    fun theResamplingFollowsTheDeclaredSpacingAndKeepsTheArcLength() {
        frames.derive(bindPose, frameSet)
        val muscle = definition.muscle("rectus_femoris")!!
        val head = muscle.heads.single()
        val frame = AnatomyFrame.of(definition)
        val slot = slotOf(muscle.id, MuscleSide.A, 0)

        val count = builder.build(head, MuscleSide.A, bindPose, frameSet, frame, slot)
        val controlLength = controlLength(head, MuscleSide.A)
        val expected = (controlLength / definition.sampling.spacingMetres).toInt()
            .coerceIn(definition.sampling.minSamples, definition.sampling.maxSamples)
        assertTrue(
            "the declared spacing decides the count: expected ~$expected, was $count",
            kotlin.math.abs(count - expected) <= 1
        )

        var arc = 0f
        for (index in 1 until count) {
            frame.pathTo(slot, index - 1, out)
            frame.pathTo(slot, index, other)
            arc += distance(out, other)
        }
        assertEquals("equal-arc sampling keeps the polyline's length", controlLength, arc, controlLength * 1e-3f)
    }

    @Test
    fun aZeroLengthControlPolylineIsRefusedRatherThanDivided() {
        frames.derive(bindPose, frameSet)
        val point = definition.muscle("rectus_abdominis")!!.heads.single().origin
        val degenerate = MuscleHead(origin = point, insertion = point)
        val frame = AnatomyFrame.of(definition)

        assertEquals(NO_INDEX, builder.build(degenerate, MuscleSide.A, bindPose, frameSet, frame, 0))
        assertEquals("a refused path writes no point", 0, frame.pointCounts[0])
    }

    @Test
    fun aPoseWithoutAUsableSegmentFrameRefusesThePath() {
        val broken = SkeletonPose()
        for (joint in Joint.entries) broken.joints[joint.index].set(bind.getValue(joint))
        broken.joints[Joint.PELVIS.index].y = Float.NaN
        frames.derive(broken, frameSet)

        val frame = AnatomyFrame.of(definition)
        val muscle = definition.muscle("rectus_abdominis")!!
        val slot = slotOf(muscle.id, MuscleSide.A, 0)
        assertEquals(NO_INDEX, builder.build(muscle.heads.single(), MuscleSide.A, broken, frameSet, frame, slot))
        assertEquals(0, frame.pointCounts[slot])
    }

    @Test
    fun anAttachmentOnASegmentWithoutAnOriginJointIsRefusedByName() {
        frames.derive(bindPose, frameSet)
        try {
            builder.resolve(Attachment(Segment.SKULL, 0f, 0f, 0f), MuscleSide.A, bindPose, frameSet, out)
            fail("a segment with no origin joint cannot carry an attachment")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("SKULL"))
        }
    }

    @Test
    fun twoPipelinesProduceBitIdenticalPaths() {
        val first = AnatomyFrame.of(definition)
        val second = AnatomyFrame.of(definition)
        val secondSets = SegmentFrameSet()
        val secondFrames = SegmentFrames(skeleton)
        secondFrames.derive(bindPose, secondSets)
        frames.derive(bindPose, frameSet)

        for (muscle in definition.muscles) {
            for (side in MuscleSide.entries) {
                for (headIndex in muscle.heads.indices) {
                    val slot = slotOf(muscle.id, side, headIndex)
                    val a = builder.build(muscle.heads[headIndex], side, bindPose, frameSet, first, slot)
                    val b = builder.build(muscle.heads[headIndex], side, bindPose, secondSets, second, slot)
                    assertEquals("$muscle $side head $headIndex point count", a, b)
                    for (index in 0 until a) {
                        assertEquals(first.pointComponent(slot, index, 0), second.pointComponent(slot, index, 0), 0f)
                        assertEquals(first.pointComponent(slot, index, 1), second.pointComponent(slot, index, 1), 0f)
                        assertEquals(first.pointComponent(slot, index, 2), second.pointComponent(slot, index, 2), 0f)
                    }
                }
            }
        }
    }

    private fun expectedAtBind(attachment: Attachment, side: MuscleSide): Vector3 {
        val id = side.instance(attachment.segment)
        val origin = bind.getValue(SegmentSpec.of(id).node!!)
        val m = SegmentRest.BIND_FRAME
        val vx = attachment.x * unitsPerMetre
        val vy = attachment.y * unitsPerMetre
        val vz = attachment.z * side.lateralSign * unitsPerMetre
        return Vector3(
            origin.x + m[0] * vx + m[3] * vy + m[6] * vz,
            origin.y + m[1] * vx + m[4] * vy + m[7] * vz,
            origin.z + m[2] * vx + m[5] * vy + m[8] * vz
        )
    }

    private fun controlLength(head: MuscleHead, side: MuscleSide): Float {
        var total = 0f
        var previous: Vector3? = null
        for (attachment in head.attachments) {
            builder.resolve(attachment, side, bindPose, frameSet, out)
            val point = Vector3(out.x, out.y, out.z)
            previous?.let { total += distance(it, point) }
            previous = point
        }
        return total
    }

    private fun slotOf(muscleId: String, side: MuscleSide, headIndex: Int): Int =
        slots.getValue("$muscleId|$side|$headIndex")

    private fun distance(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"

        /** Engine units: ~4 µm, well inside the millimetre tolerances the rig is held to. */
        const val BIND_TOLERANCE = 1e-3f
    }
}
