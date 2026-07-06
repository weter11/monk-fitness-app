package com.monkfitness.app.animation

interface PoseBuilder {
    fun evaluate(progress: Float, side: Side): SkeletonPose
}

enum class Side {
    LEFT, RIGHT
}
