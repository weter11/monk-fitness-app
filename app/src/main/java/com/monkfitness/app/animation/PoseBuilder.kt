package com.monkfitness.app.animation

interface PoseBuilder {
    @Deprecated("Use metadata.camera", ReplaceWith("metadata.camera"))
    val defaultCamera: CameraDefinition get() = metadata.camera

    val metadata: PoseMetadata get() = PoseMetadata(camera = CameraDefinition.DEFAULT)

    fun build(context: PoseContext): SkeletonPose

    // Kept for backward compatibility if needed, but build(context) is now preferred
    @Deprecated("Use build(context: PoseContext)", ReplaceWith("build(context)"))
    fun evaluate(progress: Float, side: Side, definition: SkeletonDefinition): SkeletonPose {
        val curveProgress = MotionCurves.transform(metadata.motionCurve, progress)
        return build(PoseContext(curveProgress, side, definition))
    }
}

enum class Side {
    LEFT, RIGHT
}
