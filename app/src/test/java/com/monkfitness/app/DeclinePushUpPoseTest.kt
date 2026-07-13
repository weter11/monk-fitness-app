package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class DeclinePushUpPoseTest {

    @Test
    fun testDeclinePushUpPoseBiomechanicalCompliance() {
        val poseBuilder = DeclinePushUpPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = poseBuilder.metadata.environment ?: EnvironmentDefinition()
        val camera = Camera()
        val finalizer = SkeletonPoseFinalizer(def)

        val config = ValidatorConfig(
            allowFootGroundPenetration = true, // elevated feet can dip slightly
            isStaticExercise = false,
            checkBilateralSymmetry = true,
            checkHandShoulderAlignment = true,
            checkIkTargetReachability = true,
            expectedSupportJoints = setOf(
                Joint.HAND_A, Joint.HAND_P,
                Joint.WRIST_A, Joint.WRIST_P,
                Joint.TOE_F, Joint.TOE_B,
                Joint.HEEL_F, Joint.HEEL_B,
                Joint.ANKLE_F, Joint.ANKLE_B
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
        assertTrue("Decline push-up pose has ${errors.size} validation errors!", errors.isEmpty())
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
        val poseBuilder = DeclinePushUpPose()
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
    fun testPrintDeclinePushUpCoordinates() {
        val poseBuilder = DeclinePushUpPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val progressValues = arrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

        println("=== DECLINE PUSH-UP COORD COMPARISON ===")
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
        }
        println("=========================================")
    }

    @Test
    fun testPrintRequiredArmReach() {
        val poseBuilder = DeclinePushUpPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val progressValues = arrayOf(0.0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f)

        val L1 = def.upperArmLength
        val L2 = def.forearmLength
        val minCos = kotlin.math.cos(def.armIKConstraint.minimumFlexionAngle * kotlin.math.PI.toFloat() / 180f)
        val minDist = kotlin.math.sqrt(L1 * L1 + L2 * L2 - 2f * L1 * L2 * minCos)
        val maxDist = (L1 + L2) * def.armIKConstraint.maximumExtensionRatio

        println("=== DECLINE PUSH-UP REQUIRED ARM REACH (POST-FIX) ===")
        println(String.format("%-10s | %-15s | %-12s | %-12s | %-12s", "Progress", "Req Reach (3D)", "minDist", "maxDist", "Clamp Amount"))
        println("-------------------------------------------------------------------------")
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

            // Get shoulder and target hand (during build loop we solve armA using shoulderAW and targetHandA)
            // Let's re-calculate shoulderAW and targetHandA as they are computed in build
            val chestW = pose.getJoint(Joint.CHEST)
            val shoulderAW = Vector3(chestW.x, chestW.y, chestW.z - def.shoulderWidth)
            val legTargetLen = (def.shinLength + def.thighLength) * 0.99757f // post-fix d
            val boxHeight = 40f
            val ankleHeight = boxHeight + 25f

            val solverResult = PushUpSolverResult()
            PushUpGeometrySolver.solve(
                definition = def,
                support = poseBuilder.metadata.support,
                gripWidthMultiplier = poseBuilder.gripWidthMultiplier,
                progress = progress,
                result = solverResult
            )
            val targetHandA = Vector3(solverResult.handAnchorX, 0f, -def.shoulderWidth * 1.5f)
            val dMag = (targetHandA - shoulderAW).mag()
            val clampAmount = if (dMag < minDist) minDist - dMag else if (dMag > maxDist) dMag - maxDist else 0f

            println(String.format("%-10.1f | %-15.4f | %-12.4f | %-12.4f | %-12.4f", progress, dMag, minDist, maxDist, clampAmount))
        }
        println("=========================================================================")
    }
}
