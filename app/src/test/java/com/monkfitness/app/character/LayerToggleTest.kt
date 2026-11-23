package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.anatomy.MuscleInstanceId
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.anatomy.MuscleSide
import com.monkfitness.app.anatomy.PathStatus
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The dev-only layer switchboard: a toggle reaches the geometry on the next rebuild, the body never
 * disappears, and the reading the toggles exist for stays a dimensionless number.
 */
class LayerToggleTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val mesh = ProceduralMuscleMesh(definition, unitsPerMetre)

    private fun bindPose(): SkeletonPose {
        val pose = SkeletonPose()
        for ((joint, v) in X1Bind.bindJoints(skeleton)) pose.setJoint(joint, Vector3(v.x, v.y, v.z))
        return pose
    }

    private fun squatPose(progress: Float): SkeletonPose {
        val config = PoseRegistry.getPoseConfig("squat_standard") ?: error("squat_standard is not registered")
        return SkeletonPipeline(skeleton).produceFrame(
            config.builder.build(PoseContext(progress = progress, side = Side.LEFT, definition = skeleton)),
            config.builder.metadata.environment,
            config.builder.metadata.support.supportPoints
        ).pose
    }

    private fun solvedFrame(pose: SkeletonPose = bindPose()): AnatomyFrame {
        val frame = AnatomyFrame.of(definition)
        AnatomySolver(definition, skeleton, unitsPerMetre, bindPose()).solve(pose, frame)
        return frame
    }

    @Test
    fun theTubesAreOffUntilADebugBuildTurnsThemOn() {
        val controller = CharacterLayerController()
        assertFalse("the shipped character draws no procedural tube", controller.selection.showsTubes)
        val slot = mesh.slot()
        assertEquals(0, mesh.write(solvedFrame(), slot, controller.selection))
        assertEquals(0, slot.triangleCount)
    }

    @Test
    fun turningTheTubesOnAdvancesTheRevisionOnce() {
        val controller = CharacterLayerController()
        val before = controller.revision
        assertTrue(controller.toggleTubes(true))
        assertEquals(before + 1, controller.revision)
        val afterFirst = controller.revision
        controller.toggleTubes(true)
        assertEquals("a toggle that changes nothing must not force a rebuild", afterFirst, controller.revision)
        assertEquals(mesh.triangleCapacity, mesh.write(solvedFrame(), mesh.slot(), controller.selection))
    }

    @Test
    fun hidingAMuscleLayerCollapsesItsHeadsAndKeepsTheTopology() {
        val controller = CharacterLayerController()
        controller.toggleTubes(true)
        val frame = solvedFrame()
        val slot = mesh.slot()
        val all = mesh.write(frame, slot, controller.selection)
        assertEquals(mesh.triangleCapacity, all)
        val drawn = (0 until frame.headCount).filter { !CollapsedHeads.isCollapsed(slot, mesh, it) }
        assertTrue("the shown layer draws real tubes", drawn.isNotEmpty())

        assertFalse("the toggle reports the layer's new state", controller.toggleLayer(CharacterLayerId.SUPERFICIAL_MUSCLE))
        assertEquals(
            "the topology belongs to the definition, not to the selection",
            all,
            mesh.write(frame, slot, controller.selection)
        )
        for (head in drawn) {
            assertEquals("head $head is collapsed now", 0f, CollapsedHeads.maxTriangleArea(slot, mesh, head), 0f)
        }
        assertTrue("the deeper layer is untouched", controller.showsLayer(MuscleLayer.DEEP))
        assertFalse(controller.showsLayer(MuscleLayer.SUPERFICIAL))
        assertTrue("the body is still the thing the tubes are read against", controller.layer(CharacterLayerId.BODY)?.visible == true)
    }

    @Test
    fun theBodyLayerCannotBeHidden() {
        val controller = CharacterLayerController()
        assertTrue("a body toggle reports it is still on", controller.toggleLayer(CharacterLayerId.BODY))
        assertTrue(controller.layer(CharacterLayerId.BODY)?.visible == true)
    }

    @Test
    fun hidingOneInstanceCollapsesThatInstanceAlone() {
        val controller = CharacterLayerController()
        controller.toggleTubes(true)
        val frame = solvedFrame()
        val slot = mesh.slot()
        val all = mesh.write(frame, slot, controller.selection)

        val instance = MuscleInstanceId(definition.muscles.first().id, MuscleSide.A)
        val owned = (0 until frame.headCount).filter {
            frame.heads[it].muscleId == instance.muscleId && frame.heads[it].side == instance.side
        }
        assertTrue("the instance owns at least one head", owned.isNotEmpty())
        controller.setInstanceVisible(instance, false)
        assertEquals("hiding an instance moves no topology", all, mesh.write(frame, slot, controller.selection))
        for (head in owned) {
            assertEquals("head $head is collapsed", 0f, CollapsedHeads.maxTriangleArea(slot, mesh, head), 0f)
        }
        for (head in (0 until frame.headCount) - owned.toSet()) {
            assertTrue(
                "head $head still draws",
                CollapsedHeads.maxTriangleArea(slot, mesh, head) > 0f
            )
        }
    }

    @Test
    fun theReferenceStanceSolvesEveryHeadSoTheWholeLayerDraws() {
        // The ruling on the placeholder geometry: the tuned corridors and capsules leave no head
        // refused at the bind stance, so a debug build shows sixteen instances rather than two.
        val frame = solvedFrame()
        val refused = (0 until frame.headCount).filter { frame.statuses[it] != PathStatus.VALID.ordinal }
        assertEquals("no head is refused at the reference stance: $refused", emptyList<Int>(), refused)
        assertTrue(
            "every instance is drawable",
            (0 until frame.instanceCount).all { frame.renderable[it] }
        )
    }

    @Test
    fun theRelativeLengthReadingIsDimensionlessAndZeroAtTheRestStance() {
        val rest = solvedFrame()
        for (slot in 0 until rest.headCount) {
            assertEquals("the rest stance is the reference it is measured against", 0f, rest.relativePathLengthChanges[slot], 0f)
            assertEquals(rest.modelledPathLengths[slot], rest.restLengths[slot], 1e-4f)
        }

        val squat = solvedFrame(squatPose(0.5f))
        var moving = 0
        for (slot in 0 until squat.headCount) {
            if (squat.statuses[slot] != PathStatus.VALID.ordinal) continue
            val change = squat.relativePathLengthChanges[slot]
            assertTrue("a ratio, not a length: $change", change.isFinite() && abs(change) < 1f)
            assertTrue("the reference stays the rest solve", squat.restLengths[slot] > 0f)
            if (abs(change) > 1e-4f) moving++
        }
        assertTrue("a squat must move at least one head's modelled length", moving > 0)
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
