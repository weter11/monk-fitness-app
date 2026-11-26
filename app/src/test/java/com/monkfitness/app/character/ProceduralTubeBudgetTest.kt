package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.anatomy.MuscleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tube budget is a contract with the GPU: one tessellation per version, so a slot sized from the
 * definition can never be overrun and a frame cannot quietly cost more than the last one.
 */
class ProceduralTubeBudgetTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val unitsPerMetre = X1Bind.unitsPerMetre().toDouble()
    private val mesh = ProceduralMuscleMesh(definition, unitsPerMetre)
    private val capped = ProceduralMuscleMesh(definition, unitsPerMetre)

    /** Every head carries a two-point path, so the budget is measured rather than partly skipped. */
    private fun solvedFrame(): AnatomyFrame {
        val frame = AnatomyFrame.of(definition)
        for (slot in 0 until frame.headCount) {
            frame.writePoint(slot, 0f, 0f, 0f)
            frame.writePoint(slot, 0f, 10f, 0f)
            frame.writePoint(slot, 0f, 20f, 0f)
        }
        return frame
    }

    @Test
    fun theShippedDefinitionCostsTheDeclaredTriangleBudget() {
        val samples = definition.sampling.maxSamples
        val segments = ProceduralMuscleMesh.RADIAL_SEGMENTS
        assertEquals(48, samples)
        assertEquals(8, segments)
        assertEquals("2 · R · (N − 1) band triangles per head", 2 * segments * (samples - 1), mesh.bandTrianglesPerHead)
        assertEquals("one R-triangle fan per end", 2 * segments, mesh.capTrianglesPerHead)
        assertEquals("a head is its bands plus its caps", 768, mesh.trianglesPerHead)
        assertEquals(
            "the caps-on budget is one head's cost for every head the library draws",
            definition.headCount * mesh.trianglesPerHead,
            mesh.triangleCapacity
        )
        assertEquals(
            "the band-only budget drops the two capped fans per head",
            definition.headCount * 2 * segments * (samples - 1),
            ProceduralMuscleMesh(definition, unitsPerMetre, caps = false).triangleCapacity
        )
        assertEquals("rings plus two cap centres per head", 48 * segments + 2, mesh.verticesPerHead)
        assertEquals(mesh.triangleCapacity * 3, mesh.slot().indexCapacity)
    }

    @Test
    fun theTubeWindingMatchesItsOutwardNormals() {
        val frame = solvedFrame()
        val slot = mesh.slot()
        mesh.write(frame, slot, MuscleSelection(showsTubes = true))

        // A mesh can carry outward normals and still be wound inside-out; back-face culling follows
        // (v1 − v0) × (v2 − v0), so that is what has to point the way the normals do.
        var inverted = 0
        var degenerate = 0
        for (triangle in 0 until slot.triangleCount) {
            val a = slot.indices[triangle * 3]
            val b = slot.indices[triangle * 3 + 1]
            val c = slot.indices[triangle * 3 + 2]
            val e1x = slot.positions[b * 3] - slot.positions[a * 3]
            val e1y = slot.positions[b * 3 + 1] - slot.positions[a * 3 + 1]
            val e1z = slot.positions[b * 3 + 2] - slot.positions[a * 3 + 2]
            val e2x = slot.positions[c * 3] - slot.positions[a * 3]
            val e2y = slot.positions[c * 3 + 1] - slot.positions[a * 3 + 1]
            val e2z = slot.positions[c * 3 + 2] - slot.positions[a * 3 + 2]
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            val vx = (slot.normals[a * 3] + slot.normals[b * 3] + slot.normals[c * 3]) / 3f
            val vy = (slot.normals[a * 3 + 1] + slot.normals[b * 3 + 1] + slot.normals[c * 3 + 1]) / 3f
            val vz = (slot.normals[a * 3 + 2] + slot.normals[b * 3 + 2] + slot.normals[c * 3 + 2]) / 3f
            val facing = nx * vx + ny * vy + nz * vz
            if (facing < 0f) inverted++ else if (facing == 0f) degenerate++
        }

        assertTrue("the measured build must contain triangles", slot.triangleCount > 0)
        assertEquals("every triangle is back-facing under its own normals", 0, inverted)
        assertEquals("a degenerate facing means the probe measured nothing", 0, degenerate)
    }

    @Test
    fun aFullyShownFrameFillsExactlyTheBudget() {
        val frame = solvedFrame()
        val slot = mesh.slot()
        val all = MuscleSelection(showsTubes = true)
        assertEquals(mesh.triangleCapacity, mesh.write(frame, slot, all))
        assertEquals(mesh.triangleCapacity, slot.triangleCount)
        assertEquals(mesh.vertexCapacity, slot.vertexCount)
        assertEquals("every index must name a vertex that exists", true, (0 until slot.indexCount).all { slot.indices[it] in 0 until slot.vertexCount })
    }

    @Test
    fun aHiddenLayerKeepsEveryHeadQuotaAndCollapsesItsOwn() {
        val frame = solvedFrame()
        val slot = mesh.slot()
        val superficialOnly = MuscleSelection(showsTubes = true).withLayer(MuscleLayer.DEEP, false)
        // Each layer owns exactly the heads of its own muscles, so the two budgets partition the slot.
        val deep = definition.muscles.filter { it.layer == MuscleLayer.DEEP }
            .sumOf { it.heads.size * MuscleSide.entries.size }
        val superficial = definition.muscles.filter { it.layer == MuscleLayer.SUPERFICIAL }
            .sumOf { it.heads.size * MuscleSide.entries.size }
        assertEquals("the deep layer's own heads", deep * mesh.trianglesPerHead, mesh.trianglesPerLayer(MuscleLayer.DEEP))
        assertEquals(
            "the superficial layer's own heads",
            superficial * mesh.trianglesPerHead,
            mesh.trianglesPerLayer(MuscleLayer.SUPERFICIAL)
        )
        assertEquals(mesh.triangleCapacity, mesh.trianglesPerLayer(MuscleLayer.DEEP) + mesh.trianglesPerLayer(MuscleLayer.SUPERFICIAL))
        assertEquals(mesh.triangleCapacity, mesh.write(frame, slot, superficialOnly))
        // A hidden layer collapses its own heads and leaves its neighbours' triangles untouched.
        for (slotIndex in 0 until frame.headCount) {
            val layer = definition.muscle(frame.heads[slotIndex].muscleId)!!.layer
            if (layer == MuscleLayer.DEEP) {
                assertTrue("deep head $slotIndex is collapsed", CollapsedHeads.isCollapsed(slot, mesh, slotIndex))
            } else {
                assertTrue(
                    "superficial head $slotIndex still draws",
                    CollapsedHeads.maxTriangleArea(slot, mesh, slotIndex) > 0f
                )
            }
        }

        val nothing = superficialOnly.withLayer(MuscleLayer.SUPERFICIAL, false)
        assertEquals(
            "hiding a layer collapses its heads instead of resizing the mesh",
            mesh.triangleCapacity,
            mesh.write(frame, slot, nothing)
        )
        for (head in 0 until frame.headCount) {
            assertTrue("head $head is collapsed", CollapsedHeads.isCollapsed(slot, mesh, head))
            assertEquals(0f, CollapsedHeads.maxTriangleArea(slot, mesh, head), 0f)
        }
    }

    @Test
    fun aTubesOffSelectionWritesNothingAndLeavesNoGeometryBehind() {
        val frame = solvedFrame()
        val slot = mesh.slot()
        assertEquals(mesh.triangleCapacity, mesh.write(frame, slot, MuscleSelection(showsTubes = true)))
        assertEquals(0, mesh.write(frame, slot, MuscleSelection(showsTubes = false)))
        assertEquals("a hidden build must not leave the previous frame's tubes", 0, slot.vertexCount)
        assertEquals(0, slot.indexCount)
    }

    @Test
    fun aHeadWithoutAPathIsCollapsedRatherThanSkipped() {
        val frame = solvedFrame()
        val single = frame.heads.indexOfFirst { it.side == MuscleSide.A }
        val slot = mesh.slot()
        val full = mesh.write(frame, slot, MuscleSelection(showsTubes = true))

        val truncated = solvedFrame()
        val id = truncated.heads[single]
        // One point is not a path: the tube needs two stations to have a tangent at all.
        val onlyOne = AnatomyFrame.of(definition).apply {
            for (slotIndex in 0 until headCount) {
                if (heads[slotIndex] != id) {
                    writePoint(slotIndex, 0f, 0f, 0f)
                    writePoint(slotIndex, 0f, 10f, 0f)
                } else {
                    writePoint(slotIndex, 0f, 0f, 0f)
                }
            }
        }
        val without = mesh.write(onlyOne, slot, MuscleSelection(showsTubes = true))
        assertEquals("the refused head keeps its quota, so the topology does not move", full, without)
        assertTrue("its triangles have no area", CollapsedHeads.isCollapsed(slot, mesh, single))
        assertEquals(0f, CollapsedHeads.maxTriangleArea(slot, mesh, single), 0f)
    }

    @Test
    fun aSlotSizedForFewerHeadsIsRefusedWholeWithNoGeometryWritten() {
        val frame = solvedFrame()
        val small = DynamicGeometrySlot(mesh.verticesPerHead, mesh.trianglesPerHead * 3)
        assertEquals(0, mesh.write(frame, small, MuscleSelection(showsTubes = true)))
        assertNotNull("a refusal must say why", mesh.lastRefusal)
        assertEquals("a refused build writes nothing", 0, small.vertexCount)
        assertEquals(0, small.indexCount)
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
