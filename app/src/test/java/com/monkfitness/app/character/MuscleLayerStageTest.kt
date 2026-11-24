package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.anatomy.PathStatus
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The authored layer's per-instance behaviour, on the objects the stage drives: the section state it
 * applies, the partition it is addressed by, and the palette it uploads. The Filament calls that carry
 * these decisions onto renderables need a GPU and are covered by the device run, not here.
 */
class MuscleLayerStageTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val mesh = ProceduralMuscleMesh(definition, unitsPerMetre)
    private val binds = HelperBinds.read(File(BINDS).readText())
    private val frame = AnatomyFrame.of(definition)
    private val partition = MuscleSectionPartition.of(frame)
    private val skinner = MuscleLayerSkinner(
        rig = GltfRig.parse(File(MUSCLE_ASSET).readBytes()),
        binds = binds,
        heads = frame.heads,
        unitsPerMetre = X1Bind.unitsPerMetre(skeleton),
        pointCapacity = frame.pointCapacity
    )
    private val state = MuscleSectionState(partition.size)

    private fun bindPose(): SkeletonPose {
        val pose = SkeletonPose()
        for ((joint, position) in X1Bind.bindJoints(skeleton)) pose.setJoint(joint, Vector3(position.x, position.y, position.z))
        return pose
    }

    private fun solver(): AnatomySolver =
        AnatomySolver(definition, skeleton, unitsPerMetre, bindPose(), lanes = 1)

    /** What `CharacterSession.uploadMuscleLayer` does with one frame's decisions. */
    private fun follow(selection: MuscleSelection) {
        for (section in 0 until partition.size) {
            state.show(section, selection.showsSection(partition, section, frame.renderable))
        }
        state.select(selection.selected?.let(partition::indexOf) ?: MuscleSectionState.NO_SECTION)
    }

    /** The palette entries the heads of [section] skin with: one matrix per station per head. */
    private fun helperMatrices(section: Int): IntArray {
        val instance = partition.instance(section)
        val offsets = ArrayList<Int>()
        for (slot in 0 until frame.headCount) {
            val head = frame.heads[slot]
            if (head.muscleId != instance.muscleId || head.side != instance.side) continue
            val group = binds.groupOf(head)
            for (station in 0 until binds.stationCount) {
                offsets += binds.paletteIndex(binds.helperIndexOf(group, station)) * 16
            }
        }
        return offsets.toIntArray()
    }

    @Test
    fun hidingOneSectionLeavesEveryNeighbourShown() {
        solver().use { it.solve(bindPose(), frame) }
        val all = MuscleSelection(showsMeshes = true)
        follow(all)
        assertEquals("a solved frame shows every section", partition.size, state.shownCount)

        val hidden = partition.instance(0)
        val selection = all.withInstance(hidden, false)
        follow(selection)

        assertEquals("the hidden section keeps its address", 0, partition.indexOf(hidden))
        assertFalse("only the hidden section stops drawing", state.draws(0))
        for (section in 1 until partition.size) {
            assertTrue("section $section still draws", state.draws(section))
            assertTrue(selection.showsSection(partition, section, frame.renderable))
        }
        assertEquals("the neighbour count is what the mask leaves on", partition.size - 1, state.shownCount)
        assertFalse("re-hiding an already hidden section writes no mask", state.show(0, false))

        follow(all)
        assertTrue("showing it again restores that section alone", state.draws(0))
        assertEquals(partition.size, state.shownCount)
    }

    @Test
    fun selectingOneSectionTintsItAndMovesNoVertex() {
        solver().use { it.solve(bindPose(), frame) }
        val slot = mesh.slot()
        mesh.write(frame, slot, MuscleSelection(showsTubes = true))
        val positions = slot.positions.copyOf(slot.vertexCount * 3)
        val indices = slot.indices.copyOf(slot.indexCount)
        val topology = slot.topologyRevision

        follow(MuscleSelection(showsMeshes = true))

        assertTrue("a selection moves the highlight", state.select(7))
        assertEquals(7, state.selected)
        assertFalse("re-selecting the same section writes nothing", state.select(7))
        assertEquals(7, state.selected)

        assertTrue("exactly one section carries the highlight", state.select(3))
        assertEquals(3, state.selected)

        state.select(MuscleSectionState.NO_SECTION)
        assertEquals(MuscleSectionState.NO_SECTION, state.selected)

        assertArrayEquals("a selection moves no vertex", positions, slot.positions.copyOf(positions.size), 0f)
        assertArrayEquals("a selection rewrites no index", indices, slot.indices.copyOf(indices.size))
        assertEquals("a selection keeps the topology", topology, slot.topologyRevision)
    }

    @Test
    fun aRefusedSectionHidesAndComesBackWhenItsHeadSolvesAgain() {
        val all = MuscleSelection(showsMeshes = true)
        val body = FloatArray(binds.skinJoints * 16)
        val palette = FloatArray(skinner.paletteCount * 16)

        solver().use { solver ->
            solver.solve(bindPose(), frame)
            follow(all)
            skinner.write(frame, body, palette)
            val solved = palette.copyOf()

            val section = 0
            val instance = partition.instance(section)
            val starts = helperMatrices(section)
            val owned = starts.flatMap { base -> (0 until 16).map { base + it } }
            val ownedSet = owned.toSet()
            val neighbours = (0 until palette.size).filter { it !in ownedSet }

            // The pair a refused head publishes: its status leaves VALID, and the instance's
            // renderable flag goes with it. The skinner then writes the helper's bind identity rather
            // than last frame's pose, which is what the hidden section would show if it kept drawing.
            for (slot in 0 until frame.headCount) {
                if (frame.heads[slot].muscleId == instance.muscleId && frame.heads[slot].side == instance.side) {
                    frame.statuses[slot] = PathStatus.INVALID.ordinal
                }
            }
            frame.renderable[section] = false

            follow(all)
            assertFalse("the refused instance is hidden", state.draws(section))
            assertEquals("its neighbours are untouched", partition.size - 1, state.shownCount)
            for (other in 1 until partition.size) assertTrue(state.draws(other))

            skinner.write(frame, body, palette)
            for (base in starts) assertIdentity("helper $base holds the bind, not the last path", palette, base)
            for (offset in neighbours) assertEquals("palette entry $offset is undisturbed", solved[offset], palette[offset], 0f)

            // The head solves again on the same instance address: path, palette and mask all return.
            solver.solve(bindPose(), frame)
            follow(all)
            assertTrue("the instance draws again", state.draws(section))
            assertEquals(partition.size, state.shownCount)

            skinner.write(frame, body, palette)
            assertArrayEquals("the restored instance skins exactly as it did", solved, palette, 0f)
        }
    }

    private fun assertIdentity(message: String, palette: FloatArray, offset: Int) {
        for (entry in 0 until 16) {
            val expected = when (entry) {
                0, 5, 10, 15 -> 1f
                else -> 0f
            }
            assertEquals("$message, entry $entry", expected, palette[offset + entry], 0f)
        }
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
        const val BINDS = "src/main/assets/anatomy/helpers_v1.json"
        const val MUSCLE_ASSET = "src/main/assets/characters/muscles_sup.glb"
    }
}
