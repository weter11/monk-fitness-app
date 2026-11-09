package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.poses.AirSquatPose

/** Engine→asset unit scale, derived from a standing frame so every exercise keeps the same size. */
object CharacterCalibration {

    fun unitScale(
        definition: SkeletonDefinition,
        rigHeight: Float,
        standing: PoseBuilder = AirSquatPose()
    ): Float {
        // A throwaway pipeline: calibration must not advance the render pipeline's frame history.
        val frame = SkeletonPipeline(definition)
            .produceFrame(standing, PoseContext(progress = 0f, side = Side.RIGHT, definition = definition))
            .pose
        val head = frame.getJoint(Joint.HEAD_POS).y
        val foot = minOf(frame.getJoint(Joint.TOE_F).y, frame.getJoint(Joint.TOE_B).y)
        return rigHeight / (head - foot).coerceAtLeast(1f)
    }
}
