package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomyPoseFixture
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.anatomy.Attachment
import com.monkfitness.app.anatomy.MuscleDefinition
import com.monkfitness.app.anatomy.MuscleHead
import com.monkfitness.app.anatomy.MuscleHeadId
import com.monkfitness.app.anatomy.MuscleInstanceId
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.anatomy.MuscleSide
import com.monkfitness.app.anatomy.PathStatus
import com.monkfitness.app.anatomy.Segment
import com.monkfitness.app.anatomy.TendonSpec
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
 * applies, the highlight it writes as a uniform, the partition it is addressed by, and the palette it
 * uploads. The Filament calls that carry these decisions onto renderables need a GPU and are covered
 * by the device run, not here.
 */
class MuscleLayerStageTest {

    private val binds: HelperBinds = MuscleExportFixture.binds()
    private val definition: AnatomyDefinition = MuscleExportFixture.definition(binds = binds)

    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val mesh = ProceduralMuscleMesh(definition, unitsPerMetre)
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
            state.show(section, selection.showsSection(partition, section, frame))
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

    private fun maskFixture(): Pair<AnatomyFrame, MuscleSectionPartition> {
        val headIds = listOf(
            MuscleHeadId("probe", MuscleSide.A, 0),
            MuscleHeadId("probe", MuscleSide.P, 0),
            MuscleHeadId("pair", MuscleSide.A, 0),
            MuscleHeadId("pair", MuscleSide.A, 1),
            MuscleHeadId("pair", MuscleSide.P, 0),
            MuscleHeadId("pair", MuscleSide.P, 1)
        )
        val attachment = Attachment(Segment.THORAX, 0f, 0f, 0f)
        val muscles = mapOf(
            "probe" to MuscleDefinition(
                id = "probe",
                name = "probe",
                layer = MuscleLayer.SUPERFICIAL,
                widthMetres = 0.1f,
                thicknessMetres = 0.1f,
                tendon = TendonSpec(0f, 0f),
                heads = listOf(MuscleHead(attachment, attachment.copy(y = 1f)))
            ),
            "pair" to MuscleDefinition(
                id = "pair",
                name = "pair",
                layer = MuscleLayer.SUPERFICIAL,
                widthMetres = 0.1f,
                thicknessMetres = 0.1f,
                tendon = TendonSpec(0f, 0f),
                heads = listOf(
                    MuscleHead(attachment, attachment.copy(y = 1f)),
                    MuscleHead(attachment.copy(y = 0.25f), attachment.copy(y = 0.75f))
                )
            )
        )
        val anatomy = AnatomyFrame(headIds, muscles, pointCapacity = 2, landmarkCount = 0)
        val constructor = MuscleSectionPartition::class.java.getDeclaredConstructor(
            List::class.java,
            List::class.java,
            List::class.java
        )
        constructor.isAccessible = true
        val instances = listOf(
            MuscleInstanceId("probe", MuscleSide.A),
            MuscleInstanceId("pair", MuscleSide.A),
            MuscleInstanceId("probe", MuscleSide.P),
            MuscleInstanceId("pair", MuscleSide.P)
        )
        val partition = constructor.newInstance(
            instances,
            instances.map { "${it.muscleId}_${it.side.name}" },
            List(instances.size) { MuscleLayer.SUPERFICIAL }
        ) as MuscleSectionPartition
        return anatomy to partition
    }

    @Test
    fun aSectionReadsTheMaskByItsOwnInstanceAddress() {
        val (anatomy, partition) = maskFixture()
        val all = MuscleSelection(showsMeshes = true)
        anatomy.renderable.fill(true)
        for (section in 0 until partition.size) {
            assertTrue(
                "section $section reads its own instance bit",
                all.showsSection(partition, section, anatomy)
            )
        }

        val refusedInstance = partition.instance(2)
        anatomy.renderable[anatomy.instances.indexOf(refusedInstance)] = false
        for (section in 0 until partition.size) {
            assertEquals(
                "only ${refusedInstance}'s section follows its refusal",
                partition.instance(section) != refusedInstance,
                all.showsSection(partition, section, anatomy)
            )
        }
    }

    @Test
    fun aSolvedFrameOpensEveryAuthoredSection() {
        val live = AnatomyPoseFixture.livePose("squat_standard", 0.5f)
        solver().use { it.solve(live, frame) }
        val all = MuscleSelection(showsMeshes = true)

        follow(all)

        assertEquals("a solved frame shows every section", partition.size, state.shownCount)
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
            assertTrue(selection.showsSection(partition, section, frame))
        }
        assertEquals("the neighbour count is what the mask leaves on", partition.size - 1, state.shownCount)
        assertFalse("re-hiding an already hidden section writes no mask", state.show(0, false))

        follow(all)
        assertTrue("showing it again restores that section alone", state.draws(0))
        assertEquals(partition.size, state.shownCount)
    }

    @Test
    fun aFreshSectionMaskPublishesEverySection() {
        val state = MuscleSectionState(3)
        state.select(2)
        state.show(1, false)

        state.showAll()

        assertTrue(state.draws(0))
        assertTrue(state.draws(1))
        assertTrue(state.draws(2))
        assertEquals(3, state.shownCount)
        assertEquals(MuscleSectionState.NO_SECTION, state.selected)
    }

    @Test
    fun selectingOneSectionRecordsItAndMovesNoVertex() {
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
    fun aSelectionTintsOnlyThePickedSectionsUniformAndClearsThePreviousOne() {
        val tinted = BooleanArray(partition.size)
        var writes = 0
        val selection = MuscleHighlightSelection(object : MuscleHighlightTarget {
            override fun setHighlight(section: Int, on: Boolean) {
                tinted[section] = on
                writes++
            }
        })

        selection.select(2)
        assertTrue("the picked section carries the highlight", tinted[2])
        assertEquals("one section is tinted", 1, tinted.count { it })
        assertEquals("one uniform write per selection", 1, writes)

        selection.select(2)
        assertEquals("re-selecting the same section writes no uniform", 1, writes)

        selection.select(7)
        assertFalse("the section that lost the selection returns to its authored look", tinted[2])
        assertTrue("the newly picked section carries the highlight", tinted[7])
        assertEquals("still one section tinted", 1, tinted.count { it })

        selection.select(MuscleSectionState.NO_SECTION)
        assertEquals("clearing the selection leaves no tinted section", 0, tinted.count { it })
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
        const val BINDS = "src/main/assets/anatomy/helpers_v1.json"
        const val MUSCLE_ASSET = "src/main/assets/characters/muscles_sup.glb"
    }
}
