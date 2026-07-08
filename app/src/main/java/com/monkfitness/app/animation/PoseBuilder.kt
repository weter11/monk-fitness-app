package com.monkfitness.app.animation

interface PoseBuilder {
    fun build(context: PoseContext): SkeletonPose

    // Kept for backward compatibility if needed, but build(context) is now preferred
    @Deprecated("Use build(context: PoseContext)", ReplaceWith("build(context)"))
    fun evaluate(progress: Float, side: Side, definition: SkeletonDefinition): SkeletonPose {
        return build(PoseContext(progress, side, definition))
    }
}

enum class Side {
    LEFT, RIGHT
}
