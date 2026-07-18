package com.monkfitness.app.animation

interface PoseBuilder {
    val metadata: PoseMetadata get() = PoseMetadata(camera = CameraDefinition.DEFAULT)

    fun build(context: PoseContext): SkeletonPose
}

enum class Side {
    LEFT, RIGHT
}
