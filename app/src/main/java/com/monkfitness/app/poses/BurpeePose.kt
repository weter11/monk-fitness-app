package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class BurpeePose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        pelvis = SkeletonNode(Joint.PELVIS)
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END))
        head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A))
        elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A))
        handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A))
        palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A))
        knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A))
        fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))

        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P))
        elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P))
        handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P))
        palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P))
        knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P))
        fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipF = pelvis!!.addChild(SkeletonNode(Joint.HIP_F))
        kneeF = hipF!!.addChild(SkeletonNode(Joint.KNEE_F))
        ankleF = kneeF!!.addChild(SkeletonNode(Joint.ANKLE_F))
        heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F))
        toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))

        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B))
        kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B))
        ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B))
        heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B))
        toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))

        roots = listOf(pelvis!!)
    }

    private data class PhaseInfo(
        val pelvisX: Float,
        val pelvisY: Float,
        val torsoAngle: Float,
        val ankleF: Vector3,
        val ankleB: Vector3,
        val handA: Vector3,
        val handP: Vector3,
        val armPole: Vector3
    )

    private fun getPhase(p: Float, def: SkeletonDefinition): PhaseInfo {
        if (p < 0.2f) {
            val t = p / 0.2f
            val st = SkeletonMath.easeInOut(t)
            return PhaseInfo(
                pelvisX = 0f,
                pelvisY = lerp(140f, 35f, st),
                torsoAngle = lerp(0f, -0.5f, st),
                ankleF = Vector3(0f, def.foot.ankleHeight, -def.hipWidth),
                ankleB = Vector3(0f, def.foot.ankleHeight, def.hipWidth),
                handA = Vector3(lerp(-10f, 25f, st), lerp(90f, 0f, st), -def.shoulderWidth - 5f),
                handP = Vector3(lerp(-10f, 25f, st), lerp(90f, 0f, st), def.shoulderWidth + 5f),
                armPole = Vector3(1f, 0f, 0f)
            )
        } else if (p < 0.4f) {
            val t = (p - 0.2f) / 0.2f
            val st = SkeletonMath.easeInOut(t)
            val liftF = 25f * sin(st * kotlin.math.PI.toFloat())
            return PhaseInfo(
                pelvisX = lerp(0f, -45f, st),
                pelvisY = lerp(35f, 45f, st),
                torsoAngle = lerp(-0.5f, -1.45f, st),
                ankleF = Vector3(lerp(0f, -110f, st), def.foot.ankleHeight + liftF, -def.hipWidth),
                ankleB = Vector3(lerp(0f, -110f, st), def.foot.ankleHeight + liftF, def.hipWidth),
                handA = Vector3(25f, 0f, -def.shoulderWidth - 5f),
                handP = Vector3(25f, 0f, def.shoulderWidth + 5f),
                armPole = Vector3(0f, -1f, 0f)
            )
        } else if (p < 0.6f) {
            val t = (p - 0.4f) / 0.2f
            val st = SkeletonMath.easeInOut(t)
            val dip = 8f * sin(st * kotlin.math.PI.toFloat())
            return PhaseInfo(
                pelvisX = -45f,
                pelvisY = 45f - dip,
                torsoAngle = -1.45f,
                ankleF = Vector3(-110f, def.foot.ankleHeight, -def.hipWidth),
                ankleB = Vector3(-110f, def.foot.ankleHeight, def.hipWidth),
                handA = Vector3(25f, 0f, -def.shoulderWidth - 5f),
                handP = Vector3(25f, 0f, def.shoulderWidth + 5f),
                armPole = Vector3(0f, -1f, 0f)
            )
        } else if (p < 0.8f) {
            val t = (p - 0.6f) / 0.2f
            val st = SkeletonMath.easeInOut(t)
            val liftF = 25f * sin(st * kotlin.math.PI.toFloat())
            return PhaseInfo(
                pelvisX = lerp(-45f, 0f, st),
                pelvisY = lerp(45f, 35f, st),
                torsoAngle = lerp(-1.45f, -0.5f, st),
                ankleF = Vector3(lerp(-110f, 0f, st), def.foot.ankleHeight + liftF, -def.hipWidth),
                ankleB = Vector3(lerp(-110f, 0f, st), def.foot.ankleHeight + liftF, def.hipWidth),
                handA = Vector3(25f, 0f, -def.shoulderWidth - 5f),
                handP = Vector3(25f, 0f, def.shoulderWidth + 5f),
                armPole = Vector3(1f, 0f, 0f)
            )
        } else if (p < 0.9f) {
            val t = (p - 0.8f) / 0.1f
            val st = SkeletonMath.easeInOut(t)
            val pelvisJumpY = lerp(35f, 185f, st)
            val footJumpY = lerp(def.foot.ankleHeight, 100f, st)
            return PhaseInfo(
                pelvisX = 0f,
                pelvisY = pelvisJumpY,
                torsoAngle = lerp(-0.5f, 0f, st),
                ankleF = Vector3(0f, footJumpY, -def.hipWidth),
                ankleB = Vector3(0f, footJumpY, def.hipWidth),
                handA = Vector3(lerp(25f, 0f, st), lerp(0f, 260f, st), -def.shoulderWidth - 5f),
                handP = Vector3(lerp(25f, 0f, st), lerp(0f, 260f, st), def.shoulderWidth + 5f),
                armPole = Vector3(0f, 0f, -1f)
            )
        } else {
            val t = (p - 0.9f) / 0.1f
            val st = SkeletonMath.easeInOut(t)
            val pelvisJumpY = lerp(185f, 140f, st)
            val footJumpY = lerp(100f, def.foot.ankleHeight, st)
            return PhaseInfo(
                pelvisX = 0f,
                pelvisY = pelvisJumpY,
                torsoAngle = 0f,
                ankleF = Vector3(0f, footJumpY, -def.hipWidth),
                ankleB = Vector3(0f, footJumpY, def.hipWidth),
                handA = Vector3(lerp(0f, -10f, st), lerp(260f, 90f, st), -def.shoulderWidth - 5f),
                handP = Vector3(lerp(0f, -10f, st), lerp(260f, 90f, st), def.shoulderWidth + 5f),
                armPole = Vector3(1f, 0f, 0f)
            )
        }
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val info = getPhase(context.progress, def)

        pelvis!!.localPosition = Vector3(info.pelvisX, info.pelvisY, 0f)
        pelvis!!.localRotation.set(Vector3(0f, 0f, 1f), info.torsoAngle)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Compute transforms
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // Solve IK for legs
        val legFIK = solveIK(hipF!!.worldPosition, info.ankleF, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, legFBuffer)
        val legBIK = solveIK(hipB!!.worldPosition, info.ankleB, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, legBBuffer)

        // Set knee and ankle positions (Phase 4: lean-cancel removed; the IK offset is written directly)
        kneeF!!.localPosition.set(legFIK.joint.x - hipF!!.worldPosition.x, legFIK.joint.y - hipF!!.worldPosition.y, legFIK.joint.z - hipF!!.worldPosition.z)
        ankleF!!.localPosition.set(legFIK.end.x - legFIK.joint.x, legFIK.end.y - legFIK.joint.y, legFIK.end.z - legFIK.joint.z)

        kneeB!!.localPosition.set(legBIK.joint.x - hipB!!.worldPosition.x, legBIK.joint.y - hipB!!.worldPosition.y, legBIK.joint.z - hipB!!.worldPosition.z)
        ankleB!!.localPosition.set(legBIK.end.x - legBIK.joint.x, legBIK.end.y - legBIK.joint.y, legBIK.end.z - legBIK.joint.z)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        // Solve IK for arms
        val armAIK = solveIK(shoulderA!!.worldPosition, info.handA, def.upperArmLength, def.forearmLength, info.armPole, def.armIKConstraint, armABuffer)
        val armPIK = solveIK(shoulderP!!.worldPosition, info.handP, def.upperArmLength, def.forearmLength, info.armPole, def.armIKConstraint, armPBuffer)

        // Set elbow and hand positions (Phase 4: lean-cancel removed; the IK offset is written directly)
        elbowA!!.localPosition.set(armAIK.joint.x - shoulderA!!.worldPosition.x, armAIK.joint.y - shoulderA!!.worldPosition.y, armAIK.joint.z - shoulderA!!.worldPosition.z)
        handA!!.localPosition.set(armAIK.end.x - armAIK.joint.x, armAIK.end.y - armAIK.joint.y, armAIK.end.z - armAIK.joint.z)

        elbowP!!.localPosition.set(armPIK.joint.x - shoulderP!!.worldPosition.x, armPIK.joint.y - shoulderP!!.worldPosition.y, armPIK.joint.z - shoulderP!!.worldPosition.z)
        handP!!.localPosition.set(armPIK.end.x - armPIK.joint.x, armPIK.end.y - armPIK.joint.y, armPIK.end.z - armPIK.joint.z)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
