package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class SumoSquatPose : BaseSquatPose() {

    override val squatH = 60f
    override val pelvisXEnd = -10f
    override val leanAngleEnd = 0.15f
    override val armLeanEnd = 0f

    private val legFPole = Vector3(1f, 0f, -2.0f)
    private val legBPole = Vector3(1f, 0f, 2.0f)
    private val armAPole = Vector3(0f, 0f, -1f)
    private val armPPole = Vector3(0f, 0f, 1f)

    // Local scratch axes for the outward toe flare (rotated around global Y)
    private val axisX = Vector3(1f, 0f, 0f)
    private val axisY = Vector3(0f, 1f, 0f)
    private val legTargetF = Vector3()
    private val legTargetB = Vector3()
    private val armTargetA = Vector3()
    private val armTargetP = Vector3()
    private val leftToeDir = Vector3()
    private val rightToeDir = Vector3()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.2f),
        // Controlled bodyweight sumo squat tempo: 1.5s eccentric descent, 0.5s pause, 1.0s concentric ascent
        durationSeconds = 3.0f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val standH = def.shinLength + def.thighLength + 25f

        val pelvisY = SkeletonMath.lerp(standH, squatH, context.progress)
        val pelvisX = SkeletonMath.lerp(0f, -10f, context.progress) // Less shifting needed for wide base
        val leanAngle = SkeletonMath.lerp(0f, 0.15f, context.progress) // Upright torso

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        legTargetF.set(0f, 25f, -def.hipWidth * 2.8f)
        legTargetB.set(0f, 25f, def.hipWidth * 2.8f)

        // Wide track pole vectors
        bakeIkLimb(hipF!!.worldPosition, legTargetF, def.thighLength, def.shinLength, legFPole, def.legIKConstraint, leanAngle, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, legTargetB, def.thighLength, def.shinLength, legBPole, def.legIKConstraint, leanAngle, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, leanAngle)
        ankleB!!.localRotation.set(axisZ, leanAngle)

        // 45-degree outward toe flare (rotated around global Y-axis)
        SkeletonMath.rotAround(axisX, axisY, -0.785f, leftToeDir)
        SkeletonMath.rotAround(axisX, axisY, 0.785f, rightToeDir)
        heelF!!.localPosition.set(leftToeDir.x * -def.foot.footLength * def.foot.heelRatio, 0f, leftToeDir.z * -def.foot.footLength * def.foot.heelRatio)
        toeF!!.localPosition.set(leftToeDir.x * def.foot.footLength * def.foot.toeRatio, 0f, leftToeDir.z * def.foot.footLength * def.foot.toeRatio)
        heelB!!.localPosition.set(rightToeDir.x * -def.foot.footLength * def.foot.heelRatio, 0f, rightToeDir.z * -def.foot.footLength * def.foot.heelRatio)
        toeB!!.localPosition.set(rightToeDir.x * def.foot.footLength * def.foot.toeRatio, 0f, rightToeDir.z * def.foot.footLength * def.foot.toeRatio)

        // Arms drop vertically toward crotch
        val handTargetX = pelvisX + 10f
        val handTargetY = SkeletonMath.lerp(pelvisY, pelvisY - 20f, context.progress)

        armTargetA.set(handTargetX, handTargetY, -10f)
        armTargetP.set(handTargetX, handTargetY, 10f)

        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armAPole, def.armIKConstraint, leanAngle, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPPole, def.armIKConstraint, leanAngle, elbowP!!, handP!!, armPBuffer)

        handA!!.localRotation.set(axisZ, leanAngle)
        handP!!.localRotation.set(axisZ, leanAngle)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
