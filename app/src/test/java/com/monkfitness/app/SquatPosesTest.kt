package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class SquatPosesTest {

    private val context0 = PoseContext(
        progress = 0f,
        side = Side.LEFT,
        definition = SkeletonDefinition.DEFAULT_ADULT,
        deltaTime = 16.6f,
        cycleDuration = 2500f,
        playbackSpeed = 1f,
        mirrored = false,
        phase = 0f,
        loopIndex = 0
    )

    private val context1 = PoseContext(
        progress = 1f,
        side = Side.LEFT,
        definition = SkeletonDefinition.DEFAULT_ADULT,
        deltaTime = 16.6f,
        cycleDuration = 2500f,
        playbackSpeed = 1f,
        mirrored = false,
        phase = 0f,
        loopIndex = 0
    )

    @Test
    fun testAirSquatPoseBuildsCorrectly() {
        val pose = AirSquatPose()
        assertNotNull(pose.metadata)
        assertEquals(MotionCurve.EASE_IN_OUT, pose.metadata.motionCurve)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertNotEquals(0f, pelvisY0)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        // At progress 1.0, pelvis should be lower (squatting depth) than at progress 0.0
        assertTrue("Pelvis should descend during air squat: pelvisY0=$pelvisY0, pelvisY1=$pelvisY1", pelvisY1 < pelvisY0)
    }

    @Test
    fun testSumoSquatPoseBuildsCorrectly() {
        val pose = SumoSquatPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertNotEquals(0f, pelvisY0)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertTrue("Pelvis should descend during sumo squat: pelvisY0=$pelvisY0, pelvisY1=$pelvisY1", pelvisY1 < pelvisY0)
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
    fun testSumoSquatPoseBiomechanicalCompliance() {
        val poseBuilder = SumoSquatPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = poseBuilder.metadata.environment ?: EnvironmentDefinition()
        val camera = Camera()
        val finalizer = SkeletonPoseFinalizer(def)

        val config = ValidatorConfig(
            allowFootGroundPenetration = false,
            isStaticExercise = false,
            checkBilateralSymmetry = true,
            checkHandShoulderAlignment = false,
            checkIkTargetReachability = true,
            expectedSupportJoints = setOf(
                Joint.ANKLE_F, Joint.ANKLE_B,
                Joint.HEEL_F, Joint.HEEL_B,
                Joint.TOE_F, Joint.TOE_B
            )
        )
        val validator = ExerciseValidator(config)

        val frameCount = 60
        val dt = 0.0166f
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

        println("=== SUMO SQUAT GEOMETRY SWEEP ===")
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

        println("=== VALIDATION ISSUE SUMMARY ===")
        println("Total issues collected: ${collectedIssues.size}")
        val uniqueRuleIds = collectedIssues.map { it.ruleId }.distinct()
        for (rule in uniqueRuleIds) {
            val count = collectedIssues.count { it.ruleId == rule }
            println("- Rule $rule: $count issues")
        }

        // Print a clean table of bilateral knee/elbow symmetry to check alignment manually!
        println("\n=== SUMO SQUAT KNEE & ELBOW BILATERAL SYMMETRY ===")
        val progressValues = arrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
        for (progress in progressValues) {
            val context = PoseContext(
                progress = progress,
                side = Side.RIGHT,
                definition = def,
                deltaTime = dt,
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

            val devKneeF = getSignedPerpendicularDeviation2D(hipF, ankleF, kneeF)
            val devKneeB = getSignedPerpendicularDeviation2D(hipB, ankleB, kneeB)

            val shoulderA = pose.getJoint(Joint.SHOULDER_A)
            val elbowA = pose.getJoint(Joint.ELBOW_A)
            val handA = pose.getJoint(Joint.HAND_A)

            val shoulderP = pose.getJoint(Joint.SHOULDER_P)
            val elbowP = pose.getJoint(Joint.ELBOW_P)
            val handP = pose.getJoint(Joint.HAND_P)

            val devElbowA = getSignedPerpendicularDeviation2D(shoulderA, handA, elbowA)
            val devElbowP = getSignedPerpendicularDeviation2D(shoulderP, handP, elbowP)

            println(String.format("Progress %.2f:", progress))
            println(String.format("  Knee  - devF: %10.4f, devB: %10.4f, Diff: %10.4f", devKneeF, devKneeB, kotlin.math.abs(devKneeF - devKneeB)))
            println(String.format("  Elbow - devA: %10.4f, devP: %10.4f, Diff: %10.4f", devElbowA, devElbowP, kotlin.math.abs(devElbowA - devElbowP)))
        }

        val errors = collectedIssues.filter { it.severity == ValidationSeverity.ERROR }
        assertTrue("Sumo Squat pose has ${errors.size} validation errors!", errors.isEmpty())
    }

    @Test
    fun testJumpSquatPoseBuildsCorrectly() {
        val pose = JumpSquatPose()
        assertNotNull(pose.metadata)
        assertEquals(MotionCurve.LINEAR, pose.metadata.motionCurve)

        // At progress 0.5, rawSin = sin(0.5 * 2 * PI - PI/2) = sin(PI/2) = 1.0 (Peak flight)
        val contextJump = PoseContext(
            progress = 0.5f,
            side = Side.LEFT,
            definition = SkeletonDefinition.DEFAULT_ADULT,
            deltaTime = 16.6f,
            cycleDuration = 1800f,
            playbackSpeed = 1f,
            mirrored = false,
            phase = 0f,
            loopIndex = 0
        )
        val resultJump = pose.build(contextJump)
        assertNotNull(resultJump)
        val pelvisYJump = resultJump.getJoint(Joint.PELVIS).y

        // At progress 0.0, rawSin = sin(0.0 * 2 * PI - PI/2) = sin(-PI/2) = -1.0 (Deep squat)
        val contextSquat = PoseContext(
            progress = 0.0f,
            side = Side.LEFT,
            definition = SkeletonDefinition.DEFAULT_ADULT,
            deltaTime = 16.6f,
            cycleDuration = 1800f,
            playbackSpeed = 1f,
            mirrored = false,
            phase = 0f,
            loopIndex = 0
        )
        val resultSquat = pose.build(contextSquat)
        assertNotNull(resultSquat)
        val pelvisYSquat = resultSquat.getJoint(Joint.PELVIS).y

        assertTrue("Pelvis should be higher at jump peak than squat depth: pelvisYJump=$pelvisYJump, pelvisYSquat=$pelvisYSquat", pelvisYJump > pelvisYSquat)
    }

    @Test
    fun testDeepSquatHoldPoseIsStatic() {
        val pose = DeepSquatHoldPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y

        val result1 = pose.build(context1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y

        assertEquals("Deep squat hold pelvis height should be static across progress", pelvisY0, pelvisY1, 1e-4f)
    }
}
