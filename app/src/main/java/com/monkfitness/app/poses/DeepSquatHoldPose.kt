package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class DeepSquatHoldPose : BaseSquatPose() {

    override val squatH = 60f
    override val pelvisXEnd = -30f
    override val leanAngleEnd = 0.5f
    override val armLeanEnd = 0f

    private val legFPole = Vector3(1f, 0f, -0.4f)
    private val legBPole = Vector3(1f, 0f, 0.4f)
    private val armAPole = Vector3(0f, -0.5f, -1f)
    private val armPPole = Vector3(0f, -0.5f, 1f)

    private val legTargetF = Vector3()
    private val legTargetB = Vector3()
    private val armTargetA = Vector3()
    private val armTargetP = Vector3()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.2f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
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

        // Fully locked static geometry
        val pelvisY = 60f
        val pelvisX = -30f
        val leanAngle = 0.5f

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        legTargetF.set(0f, 25f, -def.hipWidth * 1.5f)
        legTargetB.set(0f, 25f, def.hipWidth * 1.5f)

        bakeIkLimb(hipF!!.worldPosition, legTargetF, def.thighLength, def.shinLength, legFPole, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, legTargetB, def.thighLength, def.shinLength, legBPole, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, leanAngle); ankleB!!.localRotation.set(axisZ, leanAngle)
        heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // Clasp hands together at center chest
        val handTargetX = chest!!.worldPosition.x + 15f
        val handTargetY = chest!!.worldPosition.y

        armTargetA.set(handTargetX, handTargetY, -2f)
        armTargetP.set(handTargetX, handTargetY, 2f)

        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armAPole, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPPole, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        handA!!.localRotation.set(axisZ, leanAngle); handP!!.localRotation.set(axisZ, leanAngle)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
