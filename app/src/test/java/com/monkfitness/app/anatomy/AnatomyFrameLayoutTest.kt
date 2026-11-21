package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The frame's layout is a contract, not an implementation detail: a consumer derives an offset from
 * the stride, so a stride that disagrees with the addresses corrupts one head's path with another's.
 */
class AnatomyFrameLayoutTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val frame = AnatomyFrame.of(definition)
    private val out = Vector3()
    private val lastSlot get() = frame.headCount - 1

    @Test
    fun theStrideAddressesEveryPointOfEveryHeadWithoutCrossTalk() {
        assertEquals("the buffer is headCount × 3 × pointCapacity", frame.headCount * 3 * frame.pointCapacity, frame.paths.size)
        assertEquals("the stride is three floats per point", 3 * frame.pointCapacity, frame.pointStride)

        val last = frame.pointCapacity - 1
        assertTrue(frame.writePoint(0, 1f, 2f, 3f))
        assertTrue(frame.writePoint(lastSlot, 4f, 5f, 6f))
        for (index in 0 until last) assertTrue(frame.writePoint(lastSlot, 7f, 8f, 9f))

        assertEquals(1f, frame.pointComponent(0, 0, 0), 0f)
        assertEquals(3f, frame.pointComponent(0, 0, 2), 0f)
        assertEquals("the last head's first point starts at its own stride", 4f, frame.pointComponent(lastSlot, 0, 0), 0f)
        assertEquals(9f, frame.pointComponent(lastSlot, last, 2), 0f)
        assertEquals("head 0's second point must not have been written", 0f, frame.pointComponent(0, 1, 0), 0f)

        frame.muscleStates[0].pathPoint(0, out)
        assertEquals(1f, out.x, 0f)
        frame.muscleStates[lastSlot].pathPoint(last, out)
        assertEquals(9f, out.z, 0f)
    }

    @Test
    fun aFreshFrameReadsNotProcessedBecauseThatIsTheDeclaredZeroValue() {
        for (slot in 0 until frame.headCount) {
            val state = frame.muscleStates[slot]
            assertEquals(PathStatus.NOT_PROCESSED, state.pathStatus)
            assertEquals("a never-solved head must not claim a reason", PathReason.NOT_PROCESSED, state.reason)
            assertEquals(SearchOutcome.NOT_PROCESSED, state.searchOutcome)
            assertEquals(NO_INDEX, state.failingSegment)
            assertEquals(NO_INDEX, state.failingObstacle)
            assertEquals(0, state.pointCount)
            assertFalse("nothing is drawable before a solve", state.renderable)
        }
    }

    @Test
    fun clearResetsStatusReasonAndOutcomeTogether() {
        val slot = 1
        frame.statuses[slot] = PathStatus.VALID.ordinal
        frame.reasonCodes[slot] = PathReason.NO_REASON.ordinal
        frame.searchOutcomes[slot] = SearchOutcome.CLEARED.ordinal
        frame.failingSegments[slot] = 3
        frame.failingObstacles[slot] = 2
        frame.modelledPathLengths[slot] = 1.5f
        frame.restLengths[slot] = 1f
        frame.visualTendonPortionLengths[slot] = 0.2f
        frame.relativePathLengthChanges[slot] = 0.5f
        frame.renderable[frame.instanceOf(slot)] = true
        frame.writePoint(slot, 1f, 1f, 1f)
        frame.landmarks[0] = 4f

        frame.clear()

        assertEquals(PathStatus.NOT_PROCESSED, frame.muscleStates[slot].pathStatus)
        assertEquals(PathReason.NOT_PROCESSED, frame.muscleStates[slot].reason)
        assertEquals(SearchOutcome.NOT_PROCESSED, frame.muscleStates[slot].searchOutcome)
        assertEquals(NO_INDEX, frame.muscleStates[slot].failingSegment)
        assertEquals(NO_INDEX, frame.muscleStates[slot].failingObstacle)
        assertEquals(0, frame.muscleStates[slot].pointCount)
        assertEquals(0f, frame.muscleStates[slot].modelledPathLength, 0f)
        assertEquals(0f, frame.muscleStates[slot].relativePathLengthChange, 0f)
        assertFalse("a cleared frame renders nothing", frame.muscleStates[slot].renderable)
        assertEquals("a stale point must not survive the clear", 0f, frame.pointComponent(slot, 0, 0), 0f)
        assertEquals(0f, frame.landmarks[0], 0f)
    }

    @Test
    fun anOverCapacityHeadIsRefusedAndMarkedRatherThanGrownOrTruncated() {
        val sizeBefore = frame.paths.size
        repeat(frame.pointCapacity) { index ->
            assertTrue("point $index is inside the declared capacity", frame.writePoint(0, index.toFloat(), 0f, 0f))
        }
        assertFalse("the slot is full, so the write must be refused", frame.writePoint(0, 99f, 99f, 99f))

        assertEquals("the path keeps every declared point", frame.pointCapacity, frame.muscleStates[0].pointCount)
        assertEquals("the buffer is never grown", sizeBefore, frame.paths.size)
        assertEquals(PathStatus.INVALID, frame.muscleStates[0].pathStatus)
        assertEquals(
            "a refused write names the capacity rather than a geometric cause",
            PathReason.BUFFER_CAPACITY,
            frame.muscleStates[0].reason
        )
        try {
            frame.muscleStates[0].pathPoint(frame.pointCapacity, out)
            fail("a point beyond the capacity must not be readable")
        } catch (expected: IndexOutOfBoundsException) {
            assertTrue("the refusal names the head it refused", expected.message.orEmpty().contains("rectus_abdominis"))
        }
    }

    @Test
    fun aReasonIsReadThroughItsEnumNotThroughATablePosition() {
        val slot = 2
        frame.reasonCodes[slot] = PathReason.UNCERTIFIED_MINIMUM.ordinal
        assertEquals("the stored code is the enum ordinal", PathReason.entries[frame.reasonCodes[slot]], frame.muscleStates[slot].reason)
        assertEquals(PathReason.UNCERTIFIED_MINIMUM, frame.muscleStates[slot].reason)

        frame.reasonCodes[slot] = PathReason.CLEARANCE_VIOLATION.ordinal
        assertEquals(PathReason.CLEARANCE_VIOLATION, frame.muscleStates[slot].reason)
        assertEquals(
            "a reason alone never promotes a head to VALID",
            PathStatus.NOT_PROCESSED,
            frame.muscleStates[slot].pathStatus
        )
    }

    @Test
    fun theViewsAreBuiltOnceSoReadingOneAllocatesNothing() {
        assertSame("the state list is the frame's", frame.muscleStates, frame.muscleStates)
        val state = frame.muscleStates[0]
        assertSame("a state is stable across reads", state, frame.muscleStates[0])
        assertSame("a path view is built with its state", state.path, state.path)
    }

    @Test
    fun visibilityIsKeyedPerInstanceSoBothHeadsOfOneSideShareIt() {
        val biceps = frame.heads.indexOfFirst { it.muscleId == "biceps_femoris" && it.side == MuscleSide.A && it.headIndex == 0 }
        assertTrue("the two-head muscle must be in the frame", biceps >= 0)
        frame.renderable[frame.instanceOf(biceps)] = true

        val sideA = frame.heads.indices.filter { frame.heads[it].muscleId == "biceps_femoris" && frame.heads[it].side == MuscleSide.A }
        val sideP = frame.heads.indices.filter { frame.heads[it].muscleId == "biceps_femoris" && frame.heads[it].side == MuscleSide.P }
        assertEquals("biceps femoris has two heads per side", 2, sideA.size)
        assertTrue("both heads of the visible instance draw", sideA.all { frame.muscleStates[it].renderable })
        assertTrue("the other side stays hidden", sideP.none { frame.muscleStates[it].renderable })
    }

    @Test
    fun aFrameRefusesAHeadItCannotResolveOrOneDeclaredTwice() {
        val muscles = definition.muscles.associateBy { it.id }
        try {
            AnatomyFrame(listOf(MuscleHeadId("no_such_muscle", MuscleSide.A, 0)), muscles, 8, 0)
            fail("a head naming no muscle must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("no_such_muscle"))
        }
        try {
            val twice = MuscleHeadId("rectus_abdominis", MuscleSide.A, 0)
            AnatomyFrame(listOf(twice, twice), muscles, 8, 0)
            fail("a duplicated head must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("duplicate"))
        }
    }

    @Test
    fun theCanonicalFrameCarriesOneSlotPerHeadPerSide() {
        assertEquals(definition.headCount, frame.headCount)
        assertEquals(definition.instanceCount, frame.instanceCount)
        assertEquals("every head of every muscle appears exactly once", definition.headCount, frame.heads.toSet().size)
        assertEquals(
            "an instance is a (muscle, side) pair, not a head",
            definition.instanceCount,
            frame.heads.map { MuscleInstanceId(it.muscleId, it.side) }.distinct().size
        )
        assertEquals("landmarks come from the definition", definition.landmarks.size * 3, frame.landmarks.size)
        assertEquals(definition.sampling.maxPointsPerHead, frame.pointCapacity)
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
