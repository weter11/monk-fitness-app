package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The frame-to-renderer handoff: the scene carries the solved frame itself, not a copy of it, and the
 * geometry is rebuilt on a selection change and only then.
 */
class SceneFrameHandoffTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val mesh = ProceduralMuscleMesh(definition, unitsPerMetre)

    private fun bindPose(shiftX: Float = 0f): SkeletonPose {
        val pose = SkeletonPose()
        for ((joint, v) in X1Bind.bindJoints(skeleton)) pose.setJoint(joint, Vector3(v.x + shiftX, v.y, v.z))
        return pose
    }

    private fun solver(): AnatomySolver =
        AnatomySolver(definition, skeleton, unitsPerMetre, bindPose())

    private fun sceneFrame(): CharacterSceneFrame = CharacterSceneFrame(
        anatomy = AnatomyFrame.of(definition),
        morph = MorphWeightSet(5),
        geometry = mesh.slot(),
        selection = MuscleSelection(showsTubes = true)
    )

    @Test
    fun theSceneCarriesTheFrameTheSolverWroteRatherThanACopy() {
        val scene = sceneFrame()
        val solved = solver().solve(bindPose(), scene.anatomy)
        assertSame("the renderer must read the solved carrier, not a snapshot", scene.anatomy, solved)
        assertEquals("the carrier keeps the definition's head slots", definition.headCount, solved.headCount)
        assertTrue("a solved frame is a frame of results", (0 until solved.headCount).any { solved.pointCounts[it] > 0 })
    }

    @Test
    fun everyCarrierTheSceneHoldsIsTheInstanceThatWasHandedIn() {
        val anatomy = AnatomyFrame.of(definition)
        val morph = MorphWeightSet(5)
        val slot = mesh.slot()
        val scene = CharacterSceneFrame(anatomy, SkinPalette.DEFAULT, morph, slot)
        assertSame(anatomy, scene.anatomy)
        assertSame(morph, scene.morph)
        assertSame(slot, scene.geometry)
        assertSame(SkinPalette.DEFAULT, scene.skin)
        assertEquals("nothing has been built for a selection yet", -1, scene.builtRevision)
    }

    @Test
    fun aFrameRebuildsOnlyWhenTheSelectionRevisionMoved() {
        val controller = CharacterLayerController()
        val scene = CharacterSceneFrame(AnatomyFrame.of(definition), morph = MorphWeightSet(0), geometry = mesh.slot())
        assertTrue(scene.needsRebuild(controller.revision))

        scene.markBuilt(controller.revision)
        assertFalse("a moving pose alone must not force a rebuild", scene.needsRebuild(controller.revision))

        controller.toggleTubes(true)
        assertTrue(scene.needsRebuild(controller.revision))
        scene.markBuilt(controller.revision)
        assertFalse(scene.needsRebuild(controller.revision))
    }

    @Test
    fun theGeometryFollowsTheSolvedPoseThroughTheSameSlot() {
        val scene = sceneFrame()
        val solver = solver()
        val slot = scene.geometry

        solver.solve(bindPose(), scene.anatomy)
        assertEquals(mesh.triangleCapacity, mesh.write(scene.anatomy, slot, scene.selection))
        val bindPositions = slot.positions.copyOf(slot.vertexCount * 3)

        // The whole figure translated: same tessellation, different geometry. A slot still holding the
        // bind vertices would be indistinguishable from a working one on counts alone.
        solver.solve(bindPose(shiftX = 0.05f * X1Bind.unitsPerMetre(skeleton)), scene.anatomy)
        assertEquals(mesh.triangleCapacity, mesh.write(scene.anatomy, slot, scene.selection))
        assertEquals(bindPositions.size, slot.vertexCount * 3)
        assertFalse(
            "the slot must carry the new pose, not the previous one",
            bindPositions.contentEquals(slot.positions.copyOf(bindPositions.size))
        )
        assertNotEquals(bindPositions[0], slot.positions[0], 1e-4f)
    }

    @Test
    fun theMorphologyPresetNeverOutgrowsTheAssetsTargets() {
        val three = MorphWeightSet(3)
        assertEquals("an asset with three targets accepts a prefix of the preset", 3, three.apply(MorphologyPreset.HEAVY))
        val out = FloatArray(8)
        assertEquals(3, three.copyInto(out))
        assertArrayEquals(MorphologyPreset.HEAVY.weights.copyOf(3), out.copyOf(3), 0f)
        assertEquals("the tail is never written", 0f, out[3], 0f)

        assertEquals(0, MorphWeightSet(0).apply(MorphologyPreset.SLIM))
        assertEquals(5, MorphWeightSet(5).apply(MorphologyPreset.MUSCULAR))
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
