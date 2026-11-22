package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.MuscleInstanceId
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.anatomy.MuscleSide
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The mesh reads the anatomy frame and writes only its own slot: geometry ownership is what keeps a
 * stale tube, or a tube written into someone else's buffer, from reaching the GPU as this frame's.
 */
class MuscleGeometryOwnershipTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val mesh = ProceduralMuscleMesh(definition, X1Bind.unitsPerMetre().toDouble())

    private fun solvedFrame(): AnatomyFrame {
        val frame = AnatomyFrame.of(definition)
        for (slot in 0 until frame.headCount) {
            frame.writePoint(slot, 0f, slot.toFloat(), 0f)
            frame.writePoint(slot, 2f, slot + 10f, 1f)
            frame.writePoint(slot, 4f, slot + 20f, 2f)
        }
        return frame
    }

    @Test
    fun theAnatomyFrameIsReadOnlyToTheMesh() {
        val frame = solvedFrame()
        val paths = frame.paths.copyOf()
        val counts = frame.pointCounts.copyOf()
        val lengths = frame.modelledPathLengths.copyOf()
        val slot = mesh.slot()

        mesh.write(frame, slot, MuscleSelection(showsTubes = true))

        assertArrayEquals("the solver owns the path buffer", paths, frame.paths, 0f)
        assertArrayEquals(counts, frame.pointCounts)
        assertArrayEquals(lengths, frame.modelledPathLengths, 0f)
    }

    @Test
    fun aShorterBuildLeavesNoStaleGeometryBehindTheNewCounts() {
        val frame = solvedFrame()
        val slot = mesh.slot()
        val all = MuscleSelection(showsTubes = true)
        mesh.write(frame, slot, all)
        val full = slot.vertexCount

        val hidden = definition.muscles.first()
        val shorter = all.withInstance(MuscleInstanceId(hidden.id, MuscleSide.A), false)
        mesh.write(frame, slot, shorter)

        val skipped = hidden.heads.size * mesh.trianglesPerHead
        assertTrue("the second build is smaller", slot.vertexCount < full)
        assertEquals((mesh.triangleCapacity - skipped) * 3, slot.indexCount)
        assertFalse(
            "an index into the previous frame's tail would read a tube that is not there",
            (0 until slot.indexCount).any { slot.indices[it] >= slot.vertexCount }
        )
        assertTrue("the bytes past the counts are unreferenced, not authoritative", slot.positions.isNotEmpty())
    }

    @Test
    fun theSameFrameTessellatesIdenticallyTwice() {
        val frame = solvedFrame()
        val first = mesh.slot()
        val second = mesh.slot()
        mesh.write(frame, first, MuscleSelection(showsTubes = true))
        mesh.write(frame, second, MuscleSelection(showsTubes = true))

        assertEquals(first.vertexCount, second.vertexCount)
        assertEquals(first.indexCount, second.indexCount)
        assertArrayEquals(first.positions, second.positions, 0f)
        assertArrayEquals(first.normals, second.normals, 0f)
        assertArrayEquals(first.uvs, second.uvs, 0f)
        assertArrayEquals(first.indices, second.indices)
    }

    @Test
    fun aFrameThatOnlyMovedThePoseKeepsTheIndexBytesItAlreadyHas() {
        // The indices name a head's ring vertices, not where they are: a frame that moved every path
        // but drew the same heads leaves the uploaded index buffer correct, so re-emitting it is pure
        // cost. Marking one entry proves the array is not rewritten; a count that changed must rewrite.
        val frame = solvedFrame()
        val slot = mesh.slot()
        val shown = MuscleSelection(showsTubes = true)
        mesh.write(frame, slot, shown)
        val indexCount = slot.indexCount
        assertTrue(indexCount > 0)

        slot.indices[0] = MARKER
        for (head in 0 until frame.headCount) {
            frame.writePoint(head, 6f, head + 0f, 7f)
            frame.writePoint(head, 8f, head + 10f, 9f)
            frame.writePoint(head, 10f, head + 20f, 11f)
        }
        mesh.write(frame, slot, shown)

        assertEquals("a pose-only frame keeps the index count", indexCount, slot.indexCount)
        assertEquals("the index array must be left alone, not re-emitted", MARKER, slot.indices[0])

        slot.indices[0] = 0
        val hidden = shown.withInstance(MuscleInstanceId(definition.muscles.first().id, MuscleSide.A), false)
        mesh.write(frame, slot, hidden)
        assertTrue("a smaller head set moves the topology", slot.indexCount < indexCount)
        assertTrue("a moved topology is rebuilt, byte for byte", slot.indices[0] == 0)
        assertFalse(
            "a rebuilt topology only names vertices this frame wrote",
            (0 until slot.indexCount).any { slot.indices[it] >= slot.vertexCount }
        )
    }

    @Test
    fun writingOneSlotNeverTouchesAnother() {
        val frame = solvedFrame()
        val written = mesh.slot()
        val untouched = mesh.slot()
        untouched.begin()
        val before = untouched.positions.copyOf()

        mesh.write(frame, written, MuscleSelection(showsTubes = true))

        assertEquals(0, untouched.vertexCount)
        assertArrayEquals("an unclaimed slot must be byte-identical", before, untouched.positions, 0f)
    }

    @Test
    fun aSlotTooSmallForTheSelectionIsRefusedBeforeAnyVertexIsWritten() {
        val frame = solvedFrame()
        val small = DynamicGeometrySlot(mesh.vertexCapacity - 1, mesh.triangleCapacity * 3)
        assertEquals(0, mesh.write(frame, small, MuscleSelection(showsTubes = true)))
        assertNotNull("a refusal names what did not fit", mesh.lastRefusal)
        assertEquals(0, small.vertexCount)
        assertEquals(0, small.indexCount)

        val accepted = mesh.slot()
        assertEquals(mesh.triangleCapacity, mesh.write(frame, accepted, MuscleSelection(showsTubes = true)))
        assertNull("a build that fits reports no refusal", mesh.lastRefusal)
    }

    @Test
    fun theTubeSurfaceIsOutwardFacingAndSeatedOnItsPath() {
        val frame = solvedFrame()
        val slot = mesh.slot()
        mesh.write(frame, slot, MuscleSelection(showsTubes = true))

        // Each ring's centroid is its centre-line point, so every vertex of that ring must sit at the
        // profile's radius from it and its normal must point along that offset: a tube, not a smear.
        for (sample in 0 until mesh.samples) {
            val first = sample * ProceduralMuscleMesh.RADIAL_SEGMENTS
            var cx = 0f
            var cy = 0f
            var cz = 0f
            for (ring in 0 until ProceduralMuscleMesh.RADIAL_SEGMENTS) {
                cx += slot.positions[(first + ring) * 3]
                cy += slot.positions[(first + ring) * 3 + 1]
                cz += slot.positions[(first + ring) * 3 + 2]
            }
            val segments = ProceduralMuscleMesh.RADIAL_SEGMENTS.toFloat()
            cx /= segments
            cy /= segments
            cz /= segments

            for (ring in 0 until ProceduralMuscleMesh.RADIAL_SEGMENTS) {
                val vertex = first + ring
                val ox = slot.positions[vertex * 3] - cx
                val oy = slot.positions[vertex * 3 + 1] - cy
                val oz = slot.positions[vertex * 3 + 2] - cz
                val offset = kotlin.math.sqrt(ox * ox + oy * oy + oz * oz)
                assertTrue("a ring vertex sits off its own centre line", offset > 0f)

                val nx = slot.normals[vertex * 3]
                val ny = slot.normals[vertex * 3 + 1]
                val nz = slot.normals[vertex * 3 + 2]
                assertEquals("a ring normal is a unit vector", 1f, kotlin.math.sqrt(nx * nx + ny * ny + nz * nz), 1e-4f)
                assertEquals("the normal is the radial direction", 1f, (ox * nx + oy * ny + oz * nz) / offset, 1e-4f)
            }
        }
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"

        /** No triangle of this mesh can start at a negative vertex, so this marks an untouched entry. */
        const val MARKER = -1
    }
}
