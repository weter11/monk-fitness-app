package com.monkfitness.app.animation

interface PoseBuilder {
    fun evaluate(progress: Float, side: Side, definition: SkeletonDefinition): SkeletonPose
}

enum class Side {
    LEFT, RIGHT
}
