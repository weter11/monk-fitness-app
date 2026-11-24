package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The procedural tube path must stay idle while the skinned muscle layer consumes the same solve. */
class CharacterSessionMuscleSolveTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(skeleton).toDouble()

    @Test
    fun meshOnlyFramesSolvePathsWithoutWritingOrUploadingTubes() {
        val layers = CharacterLayerController()
        layers.toggleMeshes(true)
        val frame = AnatomyFrame.of(definition)
        val mesh = ProceduralMuscleMesh(definition, unitsPerMetre)
        val scene = CharacterSceneFrame(frame, morph = MorphWeightSet(0), geometry = mesh.slot())
        val solver = AnatomySolver(definition, skeleton, unitsPerMetre, bindPose(), lanes = 1)
        val path = MuscleSolvePath(solver, frame, mesh, scene, layers)
        path.use {
            assertEquals("the fixture must leave procedural tubes off", false, layers.selection.showsTubes)
            assertEquals("the fixture must enable the skinned meshes", true, layers.selection.showsMeshes)
            assertTrue("meshes alone must open the solve gate", layers.selection.showsAnyLayer)

            path.solve(bindPose())
            val first = path.snapshot()
            assertEquals("mesh-only selection must still solve anatomy", true, first.musclesSolved)
            assertTrue("the solved paths must reach the skinned layer", first.muscleHeads > 0)
            assertTrue("the bind stance has drawable instances", first.muscleInstancesDrawn > 0)
            assertEquals("no procedural tessellation on a mesh-only frame", 0, first.tubeWrites)
            assertEquals("an empty tube slot must not be uploaded every frame", 0, first.tubeUploads)
            assertEquals("mesh-only frames carry no procedural triangles", 0, first.muscleTriangles)

            path.solve(bindPose(shiftX = 0.05f * X1Bind.unitsPerMetre(skeleton)))
            val second = path.snapshot()
            assertTrue("the second frame is solved too", second.musclesSolved)
            assertEquals("steady mesh-only frames never tessellate", 0, second.tubeWrites)
            assertEquals("steady mesh-only frames never upload", 0, second.tubeUploads)
            assertTrue(
                "the moving pose must reach the solved carrier, not a cached rest frame",
                second.firstPoint != first.firstPoint
            )
        }
    }

    @Test
    fun turningTubesOffClearsTheirSlotOnce() {
        val layers = CharacterLayerController()
        layers.toggleTubes(true)
        val frame = AnatomyFrame.of(definition)
        val mesh = ProceduralMuscleMesh(definition, unitsPerMetre)
        val scene = CharacterSceneFrame(frame, morph = MorphWeightSet(0), geometry = mesh.slot())
        val solver = AnatomySolver(definition, skeleton, unitsPerMetre, bindPose(), lanes = 1)
        val path = MuscleSolvePath(solver, frame, mesh, scene, layers)
        path.use {
            path.solve(bindPose())
            assertTrue("visible tubes must be tessellated", path.tubeWrites > 0)
            assertTrue("visible tubes must reach the upload path", path.tubeUploads > 0)
            val built = path.tubeWrites
            val uploaded = path.tubeUploads

            layers.toggleTubes(false)
            layers.toggleMeshes(true)
            path.solve(bindPose())
            assertEquals("hiding tubes stops tessellation immediately", built, path.tubeWrites)
            assertEquals("the old tube slot is cleared exactly once", uploaded + 1, path.tubeUploads)
            assertEquals("no procedural triangles remain", 0, path.muscleTriangles)

            path.solve(bindPose(shiftX = 0.05f * X1Bind.unitsPerMetre(skeleton)))
            assertEquals("later mesh-only frames do not tessellate", built, path.tubeWrites)
            assertEquals("later mesh-only frames do not re-enter upload", uploaded + 1, path.tubeUploads)
            assertTrue("the paths still move for the skinned layer", path.musclesSolved)
        }
    }

    private fun bindPose(shiftX: Float = 0f): SkeletonPose {
        val pose = SkeletonPose()
        for ((joint, position) in X1Bind.bindJoints(skeleton)) {
            pose.setJoint(joint, Vector3(position.x + shiftX, position.y, position.z))
        }
        return pose
    }

    private class MuscleSolvePath(
        private val solver: AnatomySolver,
        private val frame: AnatomyFrame,
        private val mesh: ProceduralMuscleMesh,
        private val scene: CharacterSceneFrame,
        private val layers: CharacterLayerController
    ) : AutoCloseable {
        var tubeWrites = 0
            private set
        var tubeUploads = 0
            private set
        var muscleTriangles = 0
            private set
        var musclesSolved = false
            private set

        fun solve(pose: SkeletonPose) {
            val selection = layers.selection
            if (!selection.showsAnyLayer) {
                frame.clear()
                scene.select(selection)
                scene.geometry.begin()
                muscleTriangles = 0
                musclesSolved = false
                return
            }
            solver.solve(pose, frame)
            musclesSolved = true
            scene.select(selection)
            val rebuild = scene.needsRebuild(layers.revision)
            if (selection.showsTubes) {
                muscleTriangles = mesh.write(frame, scene.geometry, selection)
                tubeWrites++
                tubeUploads++
                scene.markBuilt(layers.revision)
            } else if (scene.geometry.indexCount != 0) {
                scene.geometry.begin()
                tubeUploads++
                muscleTriangles = 0
            }
        }

        fun snapshot(): Snapshot = Snapshot(
            musclesSolved = musclesSolved,
            muscleHeads = frame.headCount,
            muscleInstancesDrawn = frame.renderable.count { it },
            muscleTriangles = muscleTriangles,
            muscleReadings = (0 until frame.headCount).map { slot ->
                val state = frame.muscleStates[slot]
                MuscleHeadReading(
                    head = state.id + " " + state.side.name + " " + state.headIndex,
                    relativePathLengthChange = state.relativePathLengthChange,
                    drawn = state.renderable
                )
            },
            tubeWrites = tubeWrites,
            tubeUploads = tubeUploads,
            firstPoint = frame.pointComponent(0, 0, 0)
        )

        override fun close() = solver.close()
    }

    private data class Snapshot(
        val musclesSolved: Boolean,
        val muscleHeads: Int,
        val muscleInstancesDrawn: Int,
        val muscleTriangles: Int,
        val muscleReadings: List<MuscleHeadReading>,
        val tubeWrites: Int,
        val tubeUploads: Int,
        val firstPoint: Float
    )

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
