package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.QuadrupedThoracicRotationsPose
import com.monkfitness.app.validation.poses.DeepOverheadSquatPose
import com.monkfitness.app.validation.poses.MiddleSplitPose
import org.junit.Assert.*
import org.junit.Test

/**
 * Temporary diagnostic to extract real joint values for the 5 S1 target tests, so engine fixes
 * can be made against data rather than guesswork. Will be removed before merge.
 */
class S1DiagnosticTest {
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val ctx = PoseContext(progress = 0.5f, side = Side.LEFT, definition = def)
    private fun finalized(pose: BaseValidationPose): SkeletonPose {
        val raw = pose.build(ctx)
        return SkeletonPoseFinalizer(def).finalize(raw)
    }

    @Test
    fun dumpMiddleSplit() {
        val pose = MiddleSplitPose()
        val raw = pose.build(ctx)
        val afPre = raw.getJoint(Joint.ANKLE_F)
        val abPre = raw.getJoint(Joint.ANKLE_B)
        println("DIAG MiddleSplit PRE-FINALIZE ankleF=(${afPre.x},${afPre.y},${afPre.z}) ankleB=(${abPre.x},${abPre.y},${abPre.z}) pelvisPre=${raw.getJoint(Joint.PELVIS).y}")
        for (c in raw.contacts) {
            println("DIAG contact end=${c.endJoint} target=(${c.targetWorld.x},${c.targetWorld.y},${c.targetWorld.z}) straight=${c.straight} contact=${c.contact} normal=${c.contact?.normal} pt=${c.contact?.point}")
        }
        val p = SkeletonPoseFinalizer(def).finalize(raw)
        val pelvisY = p.getJoint(Joint.PELVIS).y
        val af = p.getJoint(Joint.ANKLE_F)
        val ab = p.getJoint(Joint.ANKLE_B)
        val kf = p.getJoint(Joint.KNEE_F)
        val kneeDist = kotlin.math.sqrt((kf.x-af.x).pow(2)+(kf.y-af.y).pow(2)+(kf.z-af.z).pow(2))
        println("DIAG MiddleSplit POST-FINALIZE pelvisY=$pelvisY ankleF.y=${af.y} ankleB.y=${ab.y} ankleZspread=${kotlin.math.abs(af.z-ab.z)} kneeDist=$kneeDist thigh=${def.thighLength} shin=${def.shinLength}")
    }

    @Test
    fun dumpDeepOverheadSquat() {
        val p = finalized(DeepOverheadSquatPose())
        val af = p.getJoint(Joint.ANKLE_F)
        val ab = p.getJoint(Joint.ANKLE_B)
        println("DIAG DeepOverheadSquat ankleF.y=${af.y} ankleB.y=${ab.y} pelvisY=${p.getJoint(Joint.PELVIS).y}")
    }

    @Test
    fun dumpIKAngularClamp() {
        val constraint = IKConstraint(30f, 0.98f, AngularJointLimits(15f, 150f, 170f))
        val root = Vector3(0f, 0f, 0f)
        val target = Vector3(def.upperArmLength + def.forearmLength, 0f, 0f)
        val pole = Vector3(0f, 1f, 0f)
        val r = SkeletonMath.solveIK(root, target, def.upperArmLength, def.forearmLength, pole, constraint)
        val mid = r.joint
        val v1 = Vector3(root.x-mid.x, root.y-mid.y, root.z-mid.z)
        val v2 = Vector3(target.x-mid.x, target.y-mid.y, target.z-mid.z)
        val theta = kotlin.math.acos((v1.dot(v2)/(v1.mag()*v2.mag())).coerceIn(-1f,1f))*180f/Math.PI.toFloat()
        println("DIAG IK req=${r.requestedDistance} clamped=${r.clampedDistance} angClamp=${r.angularClampAmount} theta=$theta cap=150 maxReach=${(def.upperArmLength+def.forearmLength)*constraint.effectiveExtensionRatio} minFlex=${constraint.angularLimits.minFlexionDegrees} maxFlex=${constraint.angularLimits.maxFlexionDegrees}")
    }

    @Test
    fun dumpTrunkFrameBuildOnly() {
        val p = QuadrupedThoracicRotationsPose()
        val c0 = PoseContext(progress = 0f, side = Side.LEFT, definition = def)
        val c1 = PoseContext(progress = 1f, side = Side.LEFT, definition = def)
        val sA0 = p.build(c0).getJoint(Joint.SHOULDER_A)
        val sA1 = p.build(c1).getJoint(Joint.SHOULDER_A)
        println("DIAG TrunkFrame BUILD-ONLY sA0=(${sA0.x},${sA0.z}) sA1=(${sA1.x},${sA1.z}) delta=${kotlin.math.abs(sA1.x-sA0.x)+kotlin.math.abs(sA1.z-sA0.z)}")
    }

    @Test
    fun dumpVerticalPull() {
        val cases = com.monkfitness.app.poses.VerticalPullPosesTest.cases()
        val finalizer = SkeletonPoseFinalizer(def)
        for (c in cases) {
            val camera = Camera(c.pose.metadata.camera)
            val env = c.pose.metadata.environment
            var worstScore = 100f
            var worstFrame = -1
            var maxHandYDev = 0f
            var firstIssue = ""
            for (i in 0..90) {
                val progress = i / 90f
                val context = PoseContext(progress=progress, side=Side.LEFT, definition=def, deltaTime=0.033f, cycleDuration=3000f, playbackSpeed=1f, mirrored=false, phase=progress, loopIndex=0)
                val raw = c.pose.build(context)
                val pose = finalizer.finalize(raw)
                val report = ExerciseValidator().validate(pose=pose, definition=def, environment=env, camera=camera, width=1000f, height=1000f, previousPose=null, prePreviousPose=null, deltaTime=0.033f)
                val review = ExerciseReview.review(report)
                if (review.score < worstScore) { worstScore = review.score; worstFrame = i; firstIssue = report.allIssues.filter { it.severity == ValidationSeverity.ERROR }.map { it.ruleId }.joinToString(",") }
                maxHandYDev = max(maxHandYDev, max(kotlin.math.abs(pose.getJoint(Joint.HAND_A).y-500f), kotlin.math.abs(pose.getJoint(Joint.HAND_P).y-500f)))
            }
            println("DIAG VPull ${c.name} worstScore=$worstScore atFrame=$worstFrame firstErr=$firstIssue maxHandYDev=$maxHandYDev")
        }
    }

    @Test
    fun dumpTrunkFrame() {
        val p = QuadrupedThoracicRotationsPose()
        val c0 = PoseContext(progress = 0f, side = Side.LEFT, definition = def)
        val c1 = PoseContext(progress = 1f, side = Side.LEFT, definition = def)
        val sA0 = p.build(c0).getJoint(Joint.SHOULDER_A)
        val sA1 = p.build(c1).getJoint(Joint.SHOULDER_A)
        println("DIAG TrunkFrame sA0=(${sA0.x},${sA0.z}) sA1=(${sA1.x},${sA1.z}) delta=${kotlin.math.abs(sA1.x-sA0.x)+kotlin.math.abs(sA1.z-sA0.z)}")
    }

    @Test
    fun dumpVerticalPull() {
        val cases = com.monkfitness.app.poses.VerticalPullPosesTest.cases()
        println("DIAG VerticalPull caseCount=${cases.size} names=${cases.map { it.name }}")
    }
}
