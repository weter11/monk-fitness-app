package com.monkfitness.spike.humanoid

import com.monkfitness.app.animation.HumanSkeletonDefinition
import com.monkfitness.app.animation.MotionCurves
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.PoseMetadata
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.poses.AirSquatPose
import com.monkfitness.app.poses.StandardPushUpPose
import com.monkfitness.app.poses.StaticForearmPlankPose

/**
 * Turns the engine's per-exercise pose function into a frame stream.
 *
 * The exercise's own `PoseMetadata` still owns duration, loop mode and easing — the driver only
 * supplies the progress the 2D controller would have supplied, so the 3D character performs exactly
 * the motion the 2D robot performs.
 */
class MonkMotionDriver(val definition: SkeletonDefinition = HumanSkeletonDefinition()) {

    enum class Exercise(val label: String, val factory: () -> PoseBuilder) {
        SQUAT("Squat", { AirSquatPose() }),
        PUSH_UP("Push-up", { StandardPushUpPose() }),
        PLANK("Plank", { StaticForearmPlankPose() })
    }

    var exercise: Exercise = Exercise.SQUAT
        private set

    var speed: Float = 1f

    var onPoseProduced: ((SkeletonPose) -> Unit)? = null

    private var builder: PoseBuilder = exercise.factory()
    private var metadata: PoseMetadata = builder.metadata
    private var pipeline = SkeletonPipeline(definition)
    private var phase = 0f
    private var direction = 1f

    val exerciseLabel: String get() = exercise.label

    fun select(next: Exercise) {
        if (next == exercise) return
        exercise = next
        builder = next.factory()
        metadata = builder.metadata
        pipeline = SkeletonPipeline(definition)
        phase = 0f
        direction = 1f
    }

    /** A frame at progress 0, used to measure the engine's own body height for unit scaling. */
    fun firstFrame(): SkeletonPose =
        pipeline.produceFrame(builder, PoseContext(0f, Side.RIGHT, definition)).pose

    fun prepare() {
        firstFrame()
    }

    fun advance(deltaNanos: Long) {
        val deltaSeconds = (deltaNanos / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        val duration = metadata.durationSeconds.coerceAtLeast(0.5f)
        phase += (deltaSeconds / duration) * speed * direction

        // LoopMode.LOOP/HOLD/PING_PONG all read as a there-and-back motion here; ONCE clamps.
        if (phase > 1f) {
            phase = 2f - phase
            direction = -1f
        } else if (phase < 0f) {
            phase = -phase
            direction = 1f
        }
        phase = phase.coerceIn(0f, 1f)

        val progress = MotionCurves.transform(metadata.motionCurve, phase)
        val pose = pipeline.produceFrame(builder, PoseContext(progress, Side.RIGHT, definition)).pose
        onPoseProduced?.invoke(pose)
    }
}
