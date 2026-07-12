package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class KneePushUpPoseTest {

    @Test
    fun testKneePushUpPoseBiomechanicalCompliance() {
        val poseBuilder = KneePushUpPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = poseBuilder.metadata.environment ?: EnvironmentDefinition()
        val camera = Camera()
        val finalizer = SkeletonPoseFinalizer(def)

        val config = ValidatorConfig(
            allowFootGroundPenetration = true,
            isStaticExercise = false,
            checkBilateralSymmetry = true,
            checkHandShoulderAlignment = true,
            expectedSupportJoints = setOf(
                Joint.HAND_A, Joint.HAND_P,
                Joint.WRIST_A, Joint.WRIST_P,
                Joint.KNEE_F, Joint.KNEE_B
            )
        )
        val validator = ExerciseValidator(config)

        // Sample progress from 0 to 1 in 100 steps
        val frameCount = 100
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

        val errors = collectedIssues.filter { it.severity == ValidationSeverity.ERROR }
        assertTrue("Knee push-up pose has ${errors.size} validation errors!", errors.isEmpty())
    }

    private fun getSignedPerpendicularDeviation2D(a: Vector3, b: Vector3, p: Vector3): Float {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val lenSq = vx * vx + vy * vy
        if (lenSq < 1e-4f) return 0f
        val cross = vx * (p.y - a.y) - vy * (p.x - a.x)
        return cross / kotlin.math.sqrt(lenSq)
    }

    @Test
    fun testLegBilateralSymmetryCorrectness() {
        val poseBuilder = KneePushUpPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val progressValues = arrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

        println("=== LEG BILATERAL SYMMETRY VERIFICATION ===")
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

            val hipF = pose.getJoint(Joint.HIP_F)
            val kneeF = pose.getJoint(Joint.KNEE_F)
            val ankleF = pose.getJoint(Joint.ANKLE_F)

            val hipB = pose.getJoint(Joint.HIP_B)
            val kneeB = pose.getJoint(Joint.KNEE_B)
            val ankleB = pose.getJoint(Joint.ANKLE_B)

            val devF = getSignedPerpendicularDeviation2D(hipF, ankleF, kneeF)
            val devB = getSignedPerpendicularDeviation2D(hipB, ankleB, kneeB)

            println(String.format("Progress %.2f:", progress))
            println(String.format("  devF: %.6f, devB: %.6f", devF, devB))

            assertTrue(
                "Knees bend in opposite directions at progress $progress! devF=$devF, devB=$devB",
                devF * devB >= 0f || (kotlin.math.abs(devF) < 1e-3f && kotlin.math.abs(devB) < 1e-3f)
            )

            assertEquals(
                "Knee deviations differ in magnitude at progress $progress!",
                kotlin.math.abs(devF),
                kotlin.math.abs(devB),
                0.05f
            )
        }
        println("===========================================")
    }

    @Test
    fun testPrintKneePushUpCoordinates() {
        val poseBuilder = KneePushUpPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val progressValues = arrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

        println("=== KNEE PUSH-UP COORD COMPARISON ===")
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

            val hipF = pose.getJoint(Joint.HIP_F)
            val kneeF = pose.getJoint(Joint.KNEE_F)
            val ankleF = pose.getJoint(Joint.ANKLE_F)

            val hipB = pose.getJoint(Joint.HIP_B)
            val kneeB = pose.getJoint(Joint.KNEE_B)
            val ankleB = pose.getJoint(Joint.ANKLE_B)

            val devF = getSignedPerpendicularDeviation2D(hipF, ankleF, kneeF)
            val devB = getSignedPerpendicularDeviation2D(hipB, ankleB, kneeB)

            val shoulderA = pose.getJoint(Joint.SHOULDER_A)
            val handA = pose.getJoint(Joint.HAND_A)
            println(String.format("Progress %.2f:", progress))
            println(String.format("  Front Leg:"))
            println(String.format("    HIP_F  : (x=%.4f, y=%.4f, z=%.4f)", hipF.x, hipF.y, hipF.z))
            println(String.format("    KNEE_F : (x=%.4f, y=%.4f, z=%.4f)", kneeF.x, kneeF.y, kneeF.z))
            println(String.format("    ANKLE_F: (x=%.4f, y=%.4f, z=%.4f)", ankleF.x, ankleF.y, ankleF.z))
            println(String.format("    devF   : %.6f", devF))
            println(String.format("  Back Leg:"))
            println(String.format("    HIP_B  : (x=%.4f, y=%.4f, z=%.4f)", hipB.x, hipB.y, hipB.z))
            println(String.format("    KNEE_B : (x=%.4f, y=%.4f, z=%.4f)", kneeB.x, kneeB.y, kneeB.z))
            println(String.format("    ANKLE_B: (x=%.4f, y=%.4f, z=%.4f)", ankleB.x, ankleB.y, ankleB.z))
            println(String.format("    devB   : %.6f", devB))
            println(String.format("  Arm A:"))
            println(String.format("    SHOULDER_A: (x=%.4f, y=%.4f, z=%.4f)", shoulderA.x, shoulderA.y, shoulderA.z))
            println(String.format("    HAND_A    : (x=%.4f, y=%.4f, z=%.4f)", handA.x, handA.y, handA.z))
        }
        println("=========================================")
    }
}
