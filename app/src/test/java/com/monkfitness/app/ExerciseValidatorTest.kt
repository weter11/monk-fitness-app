package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test

class ExerciseValidatorTest {

    private val defaultDefinition = SkeletonDefinition.DEFAULT_ADULT
    private val defaultCamera = Camera()
    private val defaultEnv = EnvironmentDefinition()

    private fun createValidBasePose(def: SkeletonDefinition): SkeletonPose {
        val pose = SkeletonPose()
        val pelvisY = 210f

        // 1. Spine
        pose.setJoint(Joint.PELVIS, Vector3(0f, pelvisY, 0f))
        pose.setJoint(Joint.CHEST, Vector3(0f, pelvisY + def.torsoLength, 0f))
        pose.setJoint(Joint.NECK_END, Vector3(0f, pelvisY + def.torsoLength + def.neckLength, 0f))
        pose.setJoint(Joint.HEAD_POS, Vector3(0f, pelvisY + def.torsoLength + def.neckLength + 18f, 0f))

        pose.setJoint(Joint.HIP_F, Vector3(0f, pelvisY, -def.hipWidth))
        pose.setJoint(Joint.HIP_B, Vector3(0f, pelvisY, def.hipWidth))
        pose.setJoint(Joint.SHOULDER_A, Vector3(0f, pelvisY + def.torsoLength, -def.shoulderWidth))
        pose.setJoint(Joint.SHOULDER_P, Vector3(0f, pelvisY + def.torsoLength, def.shoulderWidth))

        // 2. Legs (bent slightly so distance is 200f, less than max extension 205.8f)
        val kneeX = 31.93f
        val kneeY = 102.65f
        val ankleY = 10f

        pose.setJoint(Joint.KNEE_F, Vector3(kneeX, kneeY, -def.hipWidth))
        pose.setJoint(Joint.ANKLE_F, Vector3(0f, ankleY, -def.hipWidth))
        pose.setJoint(Joint.HEEL_F, Vector3(def.foot.footLength * def.foot.heelRatio, ankleY, -def.hipWidth))
        pose.setJoint(Joint.TOE_F, Vector3(-def.foot.footLength * def.foot.toeRatio, ankleY, -def.hipWidth))

        pose.setJoint(Joint.KNEE_B, Vector3(kneeX, kneeY, def.hipWidth))
        pose.setJoint(Joint.ANKLE_B, Vector3(0f, ankleY, def.hipWidth))
        pose.setJoint(Joint.HEEL_B, Vector3(def.foot.footLength * def.foot.heelRatio, ankleY, def.hipWidth))
        pose.setJoint(Joint.TOE_B, Vector3(-def.foot.footLength * def.foot.toeRatio, ankleY, def.hipWidth))

        // 3. Arms (bent slightly so distance is 138f, less than max extension 138.7f)
        val elbowX = 23.63f
        val elbowY = 270.52f
        val handY = 192f

        pose.setJoint(Joint.ELBOW_A, Vector3(elbowX, elbowY, -def.shoulderWidth))
        pose.setJoint(Joint.HAND_A, Vector3(0f, handY, -def.shoulderWidth))
        pose.setJoint(Joint.WRIST_A, Vector3(0f, handY, -def.shoulderWidth))
        pose.setJoint(Joint.PALM_A, Vector3(def.hand.palmLength * 0.5f, handY, -def.shoulderWidth))
        pose.setJoint(Joint.KNUCKLES_A, Vector3(def.hand.palmLength, handY, -def.shoulderWidth))
        pose.setJoint(Joint.FINGERTIPS_A, Vector3(def.hand.palmLength + def.hand.fingerLength, handY, -def.shoulderWidth))

        pose.setJoint(Joint.ELBOW_P, Vector3(elbowX, elbowY, def.shoulderWidth))
        pose.setJoint(Joint.HAND_P, Vector3(0f, handY, def.shoulderWidth))
        pose.setJoint(Joint.WRIST_P, Vector3(0f, handY, def.shoulderWidth))
        pose.setJoint(Joint.PALM_P, Vector3(def.hand.palmLength * 0.5f, handY, def.shoulderWidth))
        pose.setJoint(Joint.KNUCKLES_P, Vector3(def.hand.palmLength, handY, def.shoulderWidth))
        pose.setJoint(Joint.FINGERTIPS_P, Vector3(def.hand.palmLength + def.hand.fingerLength, handY, def.shoulderWidth))

        return pose
    }

    @Test
    fun testRule1FiniteCoordinates() {
        val validator = ExerciseValidator()
        val pose = createValidBasePose(defaultDefinition)

        // Valid pose
        val reportValid = validator.validate(pose, defaultDefinition, defaultEnv, defaultCamera, 1000f, 1000f)
        assertTrue(reportValid.isValid)

        // Make one joint coordinate NaN
        pose.getJoint(Joint.HEAD_POS).x = Float.NaN
        val reportInvalid = validator.validate(pose, defaultDefinition, defaultEnv, defaultCamera, 1000f, 1000f)
        assertFalse(reportInvalid.isValid)
        val finiteResults = reportInvalid.results.first { it.ruleId == "FINITE_COORDINATES" }
        assertFalse(finiteResults.isValid)
        assertEquals(1, finiteResults.issues.size)
        assertEquals(Joint.HEAD_POS, finiteResults.issues[0].joint)
    }

    @Test
    fun testRule2BoneLengths() {
        val validator = ExerciseValidator()
        val pose = createValidBasePose(defaultDefinition)

        // Slightly alter chest joint position to change torsoLength
        pose.getJoint(Joint.CHEST).y += 5f // torso length is 120, +5 is >1% change
        val report = validator.validate(pose, defaultDefinition, defaultEnv, defaultCamera, 1000f, 1000f)
        assertFalse(report.isValid)
        val boneResults = report.results.first { it.ruleId == "BONE_LENGTH" }
        assertFalse(boneResults.isValid)
        assertTrue(boneResults.issues.any { it.message.contains("PELVIS -> CHEST") })
    }

    @Test
    fun testRule3HeadInsideCameraViewport() {
        val validator = ExerciseValidator()
        val pose = createValidBasePose(defaultDefinition)

        // Head inside
        val reportIn = validator.validate(pose, defaultDefinition, defaultEnv, defaultCamera, 1080f, 1920f)
        val viewportInResults = reportIn.results.first { it.ruleId == "HEAD_VIEWPORT" }
        assertTrue(viewportInResults.isValid)

        // Move head extremely far away to project outside screen space
        pose.getJoint(Joint.HEAD_POS).set(50000f, 50000f, 50000f)
        val reportOut = validator.validate(pose, defaultDefinition, defaultEnv, defaultCamera, 1080f, 1920f)
        val viewportOutResults = reportOut.results.first { it.ruleId == "HEAD_VIEWPORT" }
        assertFalse(viewportOutResults.isValid)
        assertEquals(Joint.HEAD_POS, viewportOutResults.issues[0].joint)
    }

    @Test
    fun testRule4FeetPenetrateGround() {
        val validator = ExerciseValidator(ValidatorConfig(allowFootGroundPenetration = false))
        val pose = createValidBasePose(defaultDefinition)

        // Let's set ground level to 0
        val env = EnvironmentDefinition(ground = GroundDefinition(level = 0f))

        // Move a foot below ground
        pose.getJoint(Joint.TOE_F).y = -5f
        val report = validator.validate(pose, defaultDefinition, env, defaultCamera, 1000f, 1000f)
        val groundResults = report.results.first { it.ruleId == "FOOT_GROUND_PENETRATION" }
        assertFalse(groundResults.isValid)
        assertEquals(Joint.TOE_F, groundResults.issues[0].joint)

        // Now explicitly allow ground penetration
        val validatorAllowed = ExerciseValidator(ValidatorConfig(allowFootGroundPenetration = true))
        val reportAllowed = validatorAllowed.validate(pose, defaultDefinition, env, defaultCamera, 1000f, 1000f)
        val groundAllowedResults = reportAllowed.results.first { it.ruleId == "FOOT_GROUND_PENETRATION" }
        assertTrue(groundAllowedResults.isValid)
    }

    @Test
    fun testRule5HandsSliding() {
        val validator = ExerciseValidator(ValidatorConfig(expectedSupportJoints = setOf(Joint.HAND_A)))
        val pose1 = createValidBasePose(defaultDefinition)
        val pose2 = createValidBasePose(defaultDefinition)

        // Place hand at ground level 0
        val env = EnvironmentDefinition(ground = GroundDefinition(level = 0f))
        pose1.getJoint(Joint.HAND_A).set(50f, 0f, 0f)
        pose2.getJoint(Joint.HAND_A).set(52f, 0f, 0f) // Slid horizontally by 2.0f

        val report = validator.validate(
            pose = pose2,
            definition = defaultDefinition,
            environment = env,
            camera = defaultCamera,
            width = 1000f,
            height = 1000f,
            previousPose = pose1
        )

        val slidingResults = report.results.first { it.ruleId == "HAND_SLIDING" }
        assertFalse(slidingResults.isValid)
        assertEquals(Joint.HAND_A, slidingResults.issues[0].joint)
    }

    @Test
    fun testRule6IKConstraintLimits() {
        val validator = ExerciseValidator()
        val pose = createValidBasePose(defaultDefinition)

        // Stretch right leg way too far (exceeds thigh + shin length)
        val hipB = pose.getJoint(Joint.HIP_B)
        pose.getJoint(Joint.ANKLE_B).set(hipB.x + 300f, hipB.y, hipB.z)

        val report = validator.validate(pose, defaultDefinition, defaultEnv, defaultCamera, 1000f, 1000f)
        val ikResults = report.results.first { it.ruleId == "IK_CONSTRAINT_LIMIT" }
        assertFalse(ikResults.isValid)
        assertTrue(ikResults.issues.any { it.joint == Joint.KNEE_B })
    }

    @Test
    fun testRule7and8and10Dynamics() {
        val validator = ExerciseValidator()
        val pose1 = createValidBasePose(defaultDefinition)
        val pose2 = createValidBasePose(defaultDefinition)
        val pose3 = createValidBasePose(defaultDefinition)

        // Sudden jump of pelvis in pose2
        pose2.getJoint(Joint.PELVIS).x += 30f // Exceeds default displacement threshold 15f

        val reportDiscontinuity = validator.validate(
            pose = pose2,
            definition = defaultDefinition,
            environment = defaultEnv,
            camera = defaultCamera,
            width = 1000f,
            height = 1000f,
            previousPose = pose1,
            deltaTime = 0.033f
        )
        val discontinuityResults = reportDiscontinuity.results.first { it.ruleId == "POSITION_DISCONTINUITY" }
        assertFalse(discontinuityResults.isValid)

        // Test acceleration spike by moving pelvis smoothly from pose1 -> pose2 -> pose3
        pose1.getJoint(Joint.PELVIS).set(0f, 100f, 0f)
        pose2.getJoint(Joint.PELVIS).set(2f, 100f, 0f) // Velocity: 2 / 0.033 = 60
        pose3.getJoint(Joint.PELVIS).set(20f, 100f, 0f) // Velocity: 18 / 0.033 = 545 (Huge jump in velocity, spike in acceleration)

        val reportSpike = validator.validate(
            pose = pose3,
            definition = defaultDefinition,
            environment = defaultEnv,
            camera = defaultCamera,
            width = 1000f,
            height = 1000f,
            previousPose = pose2,
            prePreviousPose = pose1,
            deltaTime = 0.033f
        )
        val accelResults = reportSpike.results.first { it.ruleId == "ACCELERATION_SPIKE" }
        assertTrue(accelResults.issues.isNotEmpty())
    }

    @Test
    fun testRule9SupportPolygon() {
        val validator = ExerciseValidator(ValidatorConfig(isStaticExercise = true, supportMargin = 5f))
        val pose = createValidBasePose(defaultDefinition)

        // Set ground level
        val env = EnvironmentDefinition(ground = GroundDefinition(level = 0f))

        // Place support joints (feet) on the ground
        pose.getJoint(Joint.ANKLE_F).set(0f, 0f, -10f)
        pose.getJoint(Joint.TOE_F).set(5f, 0f, -10f)
        pose.getJoint(Joint.ANKLE_B).set(0f, 0f, 10f)
        pose.getJoint(Joint.TOE_B).set(5f, 0f, 10f)

        // Pelvis center at (100f, 100f, 0f) -> Out of support bounds X=[0f, 5f]
        pose.getJoint(Joint.PELVIS).set(100f, 100f, 0f)

        val report = validator.validate(pose, defaultDefinition, env, defaultCamera, 1000f, 1000f)
        val supportResults = report.results.first { it.ruleId == "STATIC_SUPPORT_POLYGON" }
        assertFalse(supportResults.isValid)
    }
}
