package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class StandardPushUpPoseTest {

    @Test
    fun testStandardPushUpPoseBiomechanicalCompliance() {
        val poseBuilder = StandardPushUpPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = poseBuilder.metadata.environment ?: EnvironmentDefinition()
        val camera = Camera()
        val finalizer = SkeletonPoseFinalizer(def)

        // Define ValidatorConfig for push-up:
        // Support joints are hands (HAND_A, HAND_P, WRIST_A, WRIST_P) and feet/toes.
        val config = ValidatorConfig(
            allowFootGroundPenetration = false,
            isStaticExercise = false,
            expectedSupportJoints = setOf(
                Joint.HAND_A, Joint.HAND_P,
                Joint.WRIST_A, Joint.WRIST_P,
                Joint.TOE_F, Joint.TOE_B,
                Joint.HEEL_F, Joint.HEEL_B,
                Joint.ANKLE_F, Joint.ANKLE_B
            )
        )
        val validator = ExerciseValidator(config)

        // Sample progress from 0 to 1 in 60 steps (finer than 10 frames)
        val frameCount = 60
        val dt = 0.0166f // ~16.6ms frame time at 60fps
        val poses = ArrayList<SkeletonPose>()

        for (i in 0 until frameCount) {
            val progress = i.toFloat() / (frameCount - 1)
            val context = PoseContext(
                progress = progress,
                side = Side.RIGHT,
                definition = def,
                deltaTime = dt,
                cycleDuration = 2500f
            )
            val rawPose = poseBuilder.build(context)
            val finalizedPose = finalizer.finalize(rawPose)
            poses.add(finalizedPose)
        }

        var previousPose: SkeletonPose? = null
        var prePreviousPose: SkeletonPose? = null
        val collectedIssues = ArrayList<ValidationIssue>()

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
                deltaTime = dt
            )

            for (issue in report.allIssues) {
                collectedIssues.add(issue)
                println("Frame $i (progress=${i.toFloat() / (frameCount - 1)}): ${issue.ruleId} (${issue.severity}): ${issue.message}")
            }

            prePreviousPose = previousPose
            previousPose = currentPose
        }

        // Print summary of issues
        println("=== VALIDATION ISSUE SUMMARY ===")
        println("Total issues collected: ${collectedIssues.size}")
        val uniqueRuleIds = collectedIssues.map { it.ruleId }.distinct()
        for (rule in uniqueRuleIds) {
            val count = collectedIssues.count { it.ruleId == rule }
            println("- Rule $rule: $count issues")
        }

        // We assert there are no error-level issues to satisfy biomechanical correctness
        val errors = collectedIssues.filter { it.severity == ValidationSeverity.ERROR }
        assertTrue("Push-up pose has ${errors.size} validation errors!", errors.isEmpty())
    }

    @Test
    fun testPrintStandardPushUpCoordinates() {
        val poseBuilder = StandardPushUpPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val progressValues = arrayOf(0.0f, 0.5f, 1.0f)

        println("=== STANDARD PUSH-UP COORD COMPARISON ===")
        for (progress in progressValues) {
            val context = PoseContext(
                progress = progress,
                side = Side.RIGHT,
                definition = def,
                deltaTime = 0.0166f,
                cycleDuration = 2500f
            )
            val rawPose = poseBuilder.build(context)
            val pose = finalizer.finalize(rawPose)

            val hip = pose.getJoint(Joint.HIP_F)
            val knee = pose.getJoint(Joint.KNEE_F)
            val ankle = pose.getJoint(Joint.ANKLE_F)

            println(String.format("Progress %.2f:", progress))
            println(String.format("  HIP_F  : (x=%.2f, y=%.2f, z=%.2f)", hip.x, hip.y, hip.z))
            println(String.format("  KNEE_F : (x=%.2f, y=%.2f, z=%.2f)", knee.x, knee.y, knee.z))
            println(String.format("  ANKLE_F: (x=%.2f, y=%.2f, z=%.2f)", ankle.x, ankle.y, ankle.z))
        }
        println("=========================================")
    }
}
