package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class IKLimbHelperTest {

    @Test
    fun testPushUpPoseIKBakeCompliance() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pipeline = SkeletonPipeline(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // Verify Standard PushUp compiles, runs, and finalizes correctly with bakeIkLimb helper
        val pushupPose = StandardPushUpPose().build(context)
        val finalizedPushup = pipeline.produceFrame(pushupPose).pose

        assertNotNull(finalizedPushup)
        assertTrue(finalizedPushup.isTransformsUpdated)

        // Check finite and reasonable values for hand and elbow joints
        val handA = finalizedPushup.getJoint(Joint.HAND_A)
        val elbowA = finalizedPushup.getJoint(Joint.ELBOW_A)
        val shoulderA = finalizedPushup.getJoint(Joint.SHOULDER_A)

        assertTrue(handA.x.isFinite() && handA.y.isFinite() && handA.z.isFinite())
        assertTrue(elbowA.x.isFinite() && elbowA.y.isFinite() && elbowA.z.isFinite())
        assertTrue(shoulderA.x.isFinite() && shoulderA.y.isFinite() && shoulderA.z.isFinite())
    }

    @Test
    fun testSquatPoseIKBakeCompliance() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pipeline = SkeletonPipeline(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // Verify AirSquat compiles, runs, and finalizes correctly with bakeIkLimb helper
        val squatPose = AirSquatPose().build(context)
        val finalizedSquat = pipeline.produceFrame(squatPose).pose

        assertNotNull(finalizedSquat)
        assertTrue(finalizedSquat.isTransformsUpdated)

        val kneeF = finalizedSquat.getJoint(Joint.KNEE_F)
        val ankleF = finalizedSquat.getJoint(Joint.ANKLE_F)
        val hipF = finalizedSquat.getJoint(Joint.HIP_F)

        assertTrue(kneeF.x.isFinite() && kneeF.y.isFinite() && kneeF.z.isFinite())
        assertTrue(ankleF.x.isFinite() && ankleF.y.isFinite() && ankleF.z.isFinite())
        assertTrue(hipF.x.isFinite() && hipF.y.isFinite() && hipF.z.isFinite())
    }

    @Test
    fun testLungePoseIKBakeCompliance() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pipeline = SkeletonPipeline(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // Verify Lunge compiles, runs, and finalizes correctly with bakeIkLimb helper
        val lungePose = AlternatingForwardLungesPose().build(context)
        val finalizedLunge = pipeline.produceFrame(lungePose).pose

        assertNotNull(finalizedLunge)
        assertTrue(finalizedLunge.isTransformsUpdated)

        val kneeF = finalizedLunge.getJoint(Joint.KNEE_F)
        val ankleF = finalizedLunge.getJoint(Joint.ANKLE_F)

        assertTrue(kneeF.x.isFinite())
        assertTrue(ankleF.x.isFinite())
    }

    @Test
    fun testHangPoseIKBakeCompliance() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pipeline = SkeletonPipeline(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // Verify HangPose compiles, runs, and finalizes correctly with bakeIkLimb helper
        val hangPose = HangPose().build(context)
        val finalizedHang = pipeline.produceFrame(hangPose).pose

        assertNotNull(finalizedHang)
        assertTrue(finalizedHang.isTransformsUpdated)

        val elbowA = finalizedHang.getJoint(Joint.ELBOW_A)
        val kneeF = finalizedHang.getJoint(Joint.KNEE_F)

        assertTrue(elbowA.x.isFinite())
        assertTrue(kneeF.x.isFinite())
    }

    @Test
    fun testPullUpPoseIKBakeCompliance() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pipeline = SkeletonPipeline(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // Verify StandardPullUpPose compiles, runs, and finalizes correctly with bakeIkLimb helper
        val pullupPose = StandardPullUpPose().build(context)
        val finalizedPullup = pipeline.produceFrame(pullupPose).pose
        assertNotNull(finalizedPullup)
        assertTrue(finalizedPullup.isTransformsUpdated)

        val elbowA = finalizedPullup.getJoint(Joint.ELBOW_A)
        val kneeF = finalizedPullup.getJoint(Joint.KNEE_F)

        assertTrue(elbowA.x.isFinite())
        assertTrue(kneeF.x.isFinite())
    }

    @Test
    fun testSolveIKAngularLimitClampsHyperextension() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        // A tight flexion cap that forbids a fully straight (180°) limb.
        val constraint = IKConstraint(30f, 0.98f, AngularJointLimits(15f, 150f, 170f))
        // Target at full reach: the unconstrained middle-joint interior angle would be ~180°.
        val root = Vector3(0f, 0f, 0f)
        val target = Vector3(def.upperArmLength + def.forearmLength, 0f, 0f)
        val pole = Vector3(0f, 1f, 0f)

        val result = SkeletonMath.solveIK(root, target, def.upperArmLength, def.forearmLength, pole, constraint)

        assertTrue("angular clamp should be recorded for a hyperextended target", result.angularClampAmount > 0f)

        // The resulting middle-joint interior angle must respect the 150° cap. Measure the
        // angle against the *solved* end (not the unreachable target): when the target is
        // hyperextended the limb stops short and the joint angle is realized at the cap.
        val mid = result.joint
        val v1 = Vector3(root.x - mid.x, root.y - mid.y, root.z - mid.z)
        val v2 = Vector3(result.end.x - mid.x, result.end.y - mid.y, result.end.z - mid.z)
        val m1 = v1.mag(); val m2 = v2.mag()
        val dot = v1.dot(v2) / (m1 * m2)
        val theta = kotlin.math.acos(dot.coerceIn(-1f, 1f)) * 180f / kotlin.math.PI.toFloat()
        assertTrue("middle joint angle $theta should be <= 150 (cap)", theta <= 150f + 1e-2f)
    }

    @Test
    fun testSolveIKContactStaysOnGroundPlane() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val constraint = IKConstraint.LegConstraint
        // Hip 30 above ground; target on the ground but closer than the fold minimum, so an
        // unconstrained clamp would drive the ankle below ground (the PR-03 failure).
        val root = Vector3(0f, 30f, 0f)
        val target = Vector3(40f, 0f, 0f)
        val ground = ContactConstraint.ground(0f)

        val result = SkeletonMath.solveIK(root, target, def.thighLength, def.shinLength, Vector3(1f, 0f, 0f), constraint, contact = ground)

        // The end must stay on (or above) the support surface, exactly on the plane here.
        assertTrue("contact end must not penetrate the ground (y=${result.end.y})", result.end.y >= -1e-4f)
        assertEquals("contact end should be projected onto the ground plane", 0f, result.end.y, 1e-3f)

        // And the reach distance is still clamped within the biological band.
        val maxDist = (def.thighLength + def.shinLength) * constraint.maximumExtensionRatio
        val minCos = kotlin.math.cos(constraint.minimumFlexionAngle * kotlin.math.PI.toFloat() / 180f)
        val minDist = kotlin.math.sqrt(def.thighLength * def.thighLength + def.shinLength * def.shinLength - 2f * def.thighLength * def.shinLength * minCos)
        val d = kotlin.math.sqrt(
            (result.end.x - root.x).pow(2) + (result.end.y - root.y).pow(2) + (result.end.z - root.z).pow(2)
        )
        assertTrue("reach distance $d outside band [$minDist, $maxDist]", d in minDist - 1e-2f..maxDist + 1e-2f)
        // The clamp is still recorded (the authored target is genuinely unreachable).
        assertTrue("clamp should be recorded for the under-reach", result.clampAmount > 0f)
    }

    @Test
    fun testDeepOverheadSquatFeetRestOnGround() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pose = DeepOverheadSquatPose()
        val context = PoseContext(progress = 0.5f, side = Side.LEFT, definition = def)
        val skeleton = pose.build(context)

        // The IK end (ankle) and the completed foot (heel/toe) must not penetrate the ground.
        val feet = listOf(
            Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F,
            Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B
        )
        for (joint in feet) {
            val y = skeleton.getJoint(joint).y
            assertTrue("Foot joint $joint penetrates the ground (y=$y)", y >= -1e-3f)
        }

        // The engine's ground-contact clamp keeps the foot on the surface: the validator's
        // foot-ground-penetration rule must pass.
        val validator = ExerciseValidator(ValidatorConfig.ENGINEERING_VALIDATION)
        val report = validator.validate(skeleton, def, pose.metadata.environment, Camera(), 1000f, 1000f)
        val ground = report.results.first { it.ruleId == "FOOT_GROUND_PENETRATION" }
        assertTrue("FOOT_GROUND_PENETRATION should pass after contact-aware clamp", ground.isValid)
    }

    @Test
    fun testDeadHangHandsStayOnBar() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pose = DeadHangPose()
        val context = PoseContext(progress = 0.5f, side = Side.LEFT, definition = def)
        val skeleton = pose.build(context)

        // Hands are fixed contacts on the bar (y = 500): the contact-aware clamp must keep the
        // grip on the bar plane, not let the over-clamp drag it below.
        val handA = skeleton.getJoint(Joint.HAND_A)
        val handP = skeleton.getJoint(Joint.HAND_P)
        assertEquals("left hand should stay on the bar plane", 500f, handA.y, 1.0f)
        assertEquals("right hand should stay on the bar plane", 500f, handP.y, 1.0f)
    }

    @Test
    fun testValidationPosesNoAnkleBelowGround() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val context = PoseContext(progress = 0.5f, side = Side.LEFT, definition = def)
        val poses = listOf(
            DeepOverheadSquatPose(),
            PikeSitPose(),
            MiddleSplitPose(),
            DeadHangPose()
        )
        for (pose in poses) {
            val skeleton = pose.build(context)
            for (joint in listOf(Joint.ANKLE_F, Joint.ANKLE_B)) {
                val y = skeleton.getJoint(joint).y
                assertTrue("${pose.javaClass.simpleName} ankle $joint below ground (y=$y)", y >= -1e-3f)
            }
        }
    }
}
