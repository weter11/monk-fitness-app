package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.Side
import com.monkfitness.app.poses.SquatPose
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one row that drives a real pose builder through the composition: a squat must lower the hips
 * and rotate the knee on the shipped rig.
 */
class PoseRetargeterTest {

    private val definition = RetargetFixtures.definition
    private val rig = RetargetFixtures.assetRig()
    private val boneByJoint = RetargetFixtures.boneByJoint(rig)
    private val retargeter = RetargetFixtures.retargeter(rig)

    @Test
    fun aSquatFrameMovesTheHipsAndTheLegs() {
        val pipeline = SkeletonPipeline(definition)
        val builder = SquatPose()

        // The pipeline publishes into a reused carrier, so each frame's matrices must be taken
        // before the next frame is produced — the same constraint the renderer lives under.
        val standMatrices = retargeter.boneMatrices(
            pipeline.produceFrame(builder, PoseContext(progress = 0f, side = Side.RIGHT, definition = definition)).pose,
            RetargetFixtures.unitsPerMetre
        )
        val bottomMatrices = retargeter.boneMatrices(
            pipeline.produceFrame(builder, PoseContext(progress = 1f, side = Side.RIGHT, definition = definition)).pose,
            RetargetFixtures.unitsPerMetre
        )

        val hipsIndex = rig.jointNodes.indexOf(boneByJoint.getValue(Joint.PELVIS))
        val standHeight = standMatrices[hipsIndex * 16 + 13]
        val bottomHeight = bottomMatrices[hipsIndex * 16 + 13]
        assertTrue("the hips must descend in a squat ($standHeight -> $bottomHeight)", bottomHeight < standHeight)

        val kneeIndex = rig.jointNodes.indexOf(boneByJoint.getValue(Joint.KNEE_F))
        assertNotEquals(
            "the knee bone must rotate between the two frames",
            standMatrices[kneeIndex * 16 + 5],
            bottomMatrices[kneeIndex * 16 + 5],
            1e-4f
        )
    }
}
