package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

abstract class BasePushUpPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = emptyList()
        )
    )

    protected var roots: List<SkeletonNode>? = null
    protected var frontLeg: LegChain? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var frontArm: ArmChain? = null
    protected var backArm: ArmChain? = null
    protected var backLeg: LegChain? = null

    protected val jointsBuffer = SkeletonPose()
    protected val armAIK = SkeletonMath.IKResult()
    protected val armPIK = SkeletonMath.IKResult()

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val dummy = SkeletonNode(Joint.PELVIS)
        frontLeg = LegChain.create(dummy, Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)

        pelvis = frontLeg!!.hip.addChild(SkeletonNode(Joint.PELVIS))
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END)); head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        frontArm = ArmChain.create(chest!!, Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A)
        backArm = ArmChain.create(chest!!, Joint.SHOULDER_P, Joint.ELBOW_P, Joint.HAND_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)

        backLeg = LegChain.create(pelvis!!, Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
        roots = listOf(frontLeg!!.ankle)
    }
}
