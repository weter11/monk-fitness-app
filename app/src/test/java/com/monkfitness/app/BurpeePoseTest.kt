package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class BurpeePoseTest {

    @Test
    fun testBurpeePoseBiomechanicalCompliance() {
        val poseBuilder = BurpeePose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = poseBuilder.metadata.environment ?: EnvironmentDefinition()
        val camera = Camera()
        val pipeline = SkeletonPipeline(def)
        val validator = ExerciseValidator(ValidatorConfig(isStaticExercise = false))

        // Create a full rep sequence (smooth cosine trajectory for progress to ensure velocity and acceleration continuity)
        val frameCount = 30
        val deltaTime = 0.1f // 100ms per frame
        val poses = ArrayList<SkeletonPose>()

        for (i in 0 until frameCount) {
            // Smooth cosine wave to loop 0 -> 1 -> 0
            val t = i.toFloat() / (frameCount - 1)
            val progress = 0.5f * (1.0f - kotlin.math.cos(t * 2 * kotlin.math.PI.toFloat()))

            val rawPose = poseBuilder.build(PoseContext(MotionCurves.transform(poseBuilder.metadata.motionCurve, progress), Side.LEFT, def))
            val finalizedPose = pipeline.produceFrame(rawPose).pose
            poses.add(finalizedPose)
        }

        // Validate the full sequence frame-by-frame
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
            val reviewReport = ExerciseReview.review(report, sequence)

            println("Frame $i (progress=${i.toFloat() / (frameCount - 1)}): Valid=${report.isValid}, Score=${reviewReport.score}")
            if (!report.isValid || reviewReport.score < 95) {
                println("--- ISSUES ---")
                for (issue in report.allIssues) {
                    println("Issue: ${issue.ruleId} (${issue.severity}): ${issue.message} on joint ${issue.joint?.name}")
                }
                for (rec in reviewReport.recommendations) {
                    println("Rec: $rec")
                }
            }

            assertTrue("Frame $i failed validation!", report.isValid)
            assertTrue("Frame $i score ${reviewReport.score} is below 95!", reviewReport.score >= 95)

            prePreviousPose = previousPose
            previousPose = currentPose
        }
    }
}
