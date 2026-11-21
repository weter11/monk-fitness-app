package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.MotionCurves
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The live 3D path publishes the same supported figure as the 2D renderer.
 *
 * A built pose carries no support declaration of its own (`IntentBuilder.reset()` clears it), so a
 * caller that hands the bare pose to the pipeline derives no support plane and the Finalizer lays
 * the palm chain along the forearm — the hands that the 2D path plants on the mat are published
 * below it. [CharacterFrameProducer] is the seam both the render loop and this test drive, so the
 * assertion is made on the exact call the live character makes.
 */
class CharacterSupportPathTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT

    private fun context(progress: Float, side: Side) = PoseContext(
        progress = progress, side = side, definition = definition, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /**
     * Plays one side of [id]'s rep through [declared]'s call shape and returns its published frames,
     * copied by value because the pipeline hands back a reused buffer.
     */
    private fun sweep(id: String, side: Side, declared: Boolean): List<SkeletonPose> {
        val config = PoseRegistry.getPoseConfig(id)!!
        val metadata = config.builder.metadata
        var progress = 0f
        val source = CharacterPoseSource {
            config.builder.build(
                context(MotionCurves.transform(metadata.motionCurve, progress), side)
            )
        }
        val pipeline = SkeletonPipeline(definition)
        val producer = CharacterFrameProducer(pipeline, metadata, source)
        fun frame(): SkeletonPose =
            if (declared) producer.next() else pipeline.produceFrame(source.pose()).pose

        progress = WARM_UP_PROGRESS
        repeat(WARM_UP_FRAMES) { frame() }

        val frames = ArrayList<SkeletonPose>(STEPS + 1)
        for (i in 0..STEPS) {
            progress = i.toFloat() / STEPS
            frames.add(SkeletonPose().apply { copyFrom(frame()) })
        }
        return frames
    }

    private fun lowestJoint(frames: List<SkeletonPose>): Pair<Float, String> {
        var lowest = Float.MAX_VALUE
        var joint = ""
        for (frame in frames) {
            for (each in Joint.entries) {
                val y = frame.getJoint(each).y
                if (y < lowest) {
                    lowest = y
                    joint = each.name
                }
            }
        }
        return lowest to joint
    }

    @Test
    fun theThreeDimensionalPathKeepsTheBirdDogOnItsMat() {
        for (id in listOf("birddog_reps", "birddog_hold")) {
            val ground = PoseRegistry.getPoseConfig(id)!!.builder.metadata.environment.ground.level
            val sides = if (PoseRegistry.getPoseConfig(id)!!.alternating) SIDES else listOf(Side.RIGHT)
            for (side in sides) {
                val declared = lowestJoint(sweep(id, side, declared = true))
                assertTrue(
                    "$id/$side: every joint must stay on the mat through the rep — lowest is " +
                        "${declared.second} at ${f(declared.first)} against a floor of $ground",
                    declared.first >= ground - FLOOR_TOLERANCE
                )

                // The defect the seam closes: the same frames through the bare-pose call publish the
                // planted hands under the mat. A declared path that reads green must not be green
                // because the check cannot see the difference.
                val bare = lowestJoint(sweep(id, side, declared = false))
                assertTrue(
                    "$id/$side: the bare-pose call must still bury a joint (measured " +
                        "${bare.second} at ${f(bare.first)}), or this test cannot tell the two paths apart",
                    bare.first < declared.first - DEFECT_MARGIN
                )
            }
        }
    }

    @Test
    fun theSeamStampsTheExercisesOwnSupportDeclarationOnEveryFrame() {
        // The 2D renderer hands the engine the pose's metadata because a built pose carries none of
        // it. The 3D seam must do the same for every registered exercise: the published frame's
        // carriers are the exercise's own declaration, not the empty default `reset()` left behind.
        for (id in PoseRegistry.animationIds.sorted()) {
            val config = PoseRegistry.getPoseConfig(id)!!
            val metadata = config.builder.metadata
            var progress = 0f
            val source = CharacterPoseSource {
                config.builder.build(
                    context(MotionCurves.transform(metadata.motionCurve, progress), Side.RIGHT)
                )
            }
            val pipeline = SkeletonPipeline(definition)
            val producer = CharacterFrameProducer(pipeline, metadata, source)
            for (i in 0..PARITY_STEPS) {
                progress = i.toFloat() / PARITY_STEPS
                val pose = producer.next()
                assertEquals(
                    "$id at progress $progress: the 3D seam must stamp the declared ground/environment",
                    metadata.environment, pose.environment
                )
                assertEquals(
                    "$id at progress $progress: the 3D seam must stamp the declared support points",
                    metadata.support.supportPoints, pose.supportedPoints
                )
            }
        }
    }

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private companion object {
        val SIDES = listOf(Side.RIGHT, Side.LEFT)
        const val STEPS = 64
        const val PARITY_STEPS = 8
        const val WARM_UP_FRAMES = 11
        const val WARM_UP_PROGRESS = 0.3f

        /** The floor band the published figure is held to: 0.05 engine units, as the 2D gate states it. */
        const val FLOOR_TOLERANCE = 0.05f

        /** How far below the declared path's lowest joint the bare call must land to prove the seam acts. */
        const val DEFECT_MARGIN = 0.5f
    }
}
