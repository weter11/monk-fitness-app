package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.QuadrupedThoracicRotationsPose
import com.monkfitness.app.poses.StandardPullUpPose
import com.monkfitness.app.validation.poses.DeepOverheadSquatPose
import com.monkfitness.app.validation.poses.MiddleSplitPose
import org.junit.Assert.*
import org.junit.Test

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
        println("DIAG MS PRE ankleF=(${afPre.x},${afPre.y},${afPre.z}) pelvisPre=${raw.getJoint(Joint.PELVIS).y}")
        for (c in raw.contacts) {
            println("DIAG MS contact end=${c.endJoint} tgt=(${c.targetWorld.x},${c.targetWorld.y},${c.targetWorld.z}) straight=${c.straight} n=${c.contact?.normal} pt=${c.contact?.point}")
        }
        val p = SkeletonPoseFinalizer(def).finalize(raw)
        val af = p.getJoint(Joint.ANKLE_F); val ab = p.getJoint(Joint.ANKLE_B); val kf = p.getJoint(Joint.KNEE_F)
        val kd = kotlin.math.sqrt((kf.x-af.x)*(kf.x-af.x)+(kf.y-af.y)*(kf.y-af.y)+(kf.z-af.z)*(kf.z-af.z))
        println("DIAG MS POST pelvisY=${p.getJoint(Joint.PELVIS).y} ankleF.y=${af.y} ankleB.y=${ab.y} zspread=${kotlin.math.abs(af.z-ab.z)} kneeDist=$kd")
    }

    @Test
    fun dumpDeepOverheadSquat() {
        val p = finalized(DeepOverheadSquatPose())
        println("DIAG DOS ankleF.y=${p.getJoint(Joint.ANKLE_F).y} ankleB.y=${p.getJoint(Joint.ANKLE_B).y} pelvisY=${p.getJoint(Joint.PELVIS).y}")
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
        val v2 = Vector3(r.end.x-mid.x, r.end.y-mid.y, r.end.z-mid.z)
        val theta = kotlin.math.acos((v1.dot(v2)/(v1.mag()*v2.mag())).coerceIn(-1f,1f))*180f/Math.PI.toFloat()
        println("DIAG IK angClamp=${r.angularClampAmount} theta(end)=$theta req=${r.requestedDistance} clamped=${r.clampedDistance}")
    }

    @Test
    fun dumpTrunkFrameBuildOnly() {
        val p = QuadrupedThoracicRotationsPose()
        val c0 = PoseContext(progress = 0f, side = Side.LEFT, definition = def)
        val c1 = PoseContext(progress = 1f, side = Side.LEFT, definition = def)
        val sA0 = p.build(c0).getJoint(Joint.SHOULDER_A)
        val sA1 = p.build(c1).getJoint(Joint.SHOULDER_A)
        println("DIAG TF buildOnly sA0=(${sA0.x},${sA0.z}) sA1=(${sA1.x},${sA1.z}) delta=${kotlin.math.abs(sA1.x-sA0.x)+kotlin.math.abs(sA1.z-sA0.z)}")
        val ch0 = p.build(c0).getJointRotation(Joint.CHEST).angle
        val ch1 = p.build(c1).getJointRotation(Joint.CHEST).angle
        println("DIAG TF chestAngle c0=$ch0 c1=$ch1")
    }

    @Test
    fun dumpVerticalPull() {
        val pose = StandardPullUpPose()
        val camera = Camera(pose.metadata.camera)
        val env = pose.metadata.environment
        val finalizer = SkeletonPoseFinalizer(def)
        var worstScore = 100f; var worstFrame = -1; var maxHandYDev = 0f; var firstIssue = ""
        for (i in 0..90) {
            val progress = i / 90f
            val context = PoseContext(progress=progress, side=Side.LEFT, definition=def, deltaTime=0.033f, cycleDuration=3000f, playbackSpeed=1f, mirrored=false, phase=progress, loopIndex=0)
            val fp = finalizer.finalize(pose.build(context))
            val report = ExerciseValidator().validate(pose=fp, definition=def, environment=env, camera=camera, width=1000f, height=1000f, previousPose=null, prePreviousPose=null, deltaTime=0.033f)
            val review = ExerciseReview.review(report)
            if (review.score < worstScore) { worstScore = review.score; worstFrame = i; firstIssue = report.allIssues.filter { it.severity == ValidationSeverity.ERROR }.map { it.ruleId }.joinToString(",") }
            val hy = kotlin.math.max(kotlin.math.abs(fp.getJoint(Joint.HAND_A).y-500f), kotlin.math.abs(fp.getJoint(Joint.HAND_P).y-500f))
            if (hy > maxHandYDev) maxHandYDev = hy
        }
        println("DIAG VPull worstScore=$worstScore atFrame=$worstFrame firstErr=$firstIssue maxHandYDev=$maxHandYDev")
    }
}
