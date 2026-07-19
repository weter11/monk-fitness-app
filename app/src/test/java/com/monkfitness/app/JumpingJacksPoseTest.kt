package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class JumpingJacksPoseTest {

    @Test
    fun testJumpingJacksPoseBiomechanicalCompliance() {
        val poseBuilder = JumpingJacksPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = poseBuilder.metadata.environment ?: EnvironmentDefinition()
        val camera = Camera()
        val pipeline = SkeletonPipeline(def)
        val validator = ExerciseValidator(ValidatorConfig(isStaticExercise = false))

        val frameCount = 30
        val deltaTime = 0.1f
        val poses = ArrayList<SkeletonPose>()

        for (i in 0 until frameCount) {
            val t = i.toFloat() / (frameCount - 1)
            val progress = 0.5f * (1.0f - kotlin.math.cos(t * 2 * kotlin.math.PI.toFloat()))

            val rawPose = poseBuilder.build(PoseContext(MotionCurves.transform(poseBuilder.metadata.motionCurve, progress), Side.LEFT, def))
            val finalizedPose = pipeline.produceFrame(rawPose).pose
            poses.add(finalizedPose)
        }

        var previousPose: SkeletonPose? = null
        var prePreviousPose: SkeletonPose? = null

        for (i in 0 until frameCount) {
            val currentPose = poses[i]

            val report = validator.validate(
                pose = currentPose,
                definition = def,
                environment = env,
                camera = camera,
                width = 1080f,
                height = 1920f,
                previousPose = previousPose,
                prePreviousPose = prePreviousPose,
                deltaTime = deltaTime
            )

            val snapshots = emptyList<ExerciseSnapshot>()
            val sequence = ExerciseSnapshotSequence(snapshots)

            assertTrue("Frame $i failed validation!", report.isValid)

            prePreviousPose = previousPose
            previousPose = currentPose
        }
    }
}
