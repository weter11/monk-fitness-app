package com.monkfitness.app.character

import com.monkfitness.app.animation.PoseMetadata
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SupportPoint

/**
 * The live 3D render loop's frame production, with the exercise's support declaration re-stamped on
 * every frame.
 *
 * A built pose does not carry its own declaration: `IntentBuilder.reset()` clears
 * `supportedPoints` and resets `environment` at the start of every build, and the pose only states
 * them in its metadata. The 2D renderer therefore passes the metadata's two values alongside the
 * built pose. Omitting them here leaves the Finalizer with no support plane, so the palm chain is
 * laid along the forearm and a planted hand is published under the mat — the same figure that
 * renders correctly in 2D.
 */
class CharacterFrameProducer(
    private val pipeline: SkeletonPipeline,
    metadata: PoseMetadata,
    private val poseSource: CharacterPoseSource
) {
    // Resolved once: `supportPoints` builds a fresh set per call and the render loop runs at 60 fps.
    private val environment = metadata.environment
    private val supportedPoints: Set<SupportPoint> = metadata.support.supportPoints

    fun next(): SkeletonPose =
        pipeline.produceFrame(poseSource.pose(), environment, supportedPoints).pose
}
