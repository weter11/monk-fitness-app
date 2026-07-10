package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.easeInOut
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import com.monkfitness.app.animation.SkeletonMath.solveIK

class WorldGreatestStretchPose : PoseBuilder {
    private val jointsBuffer = SkeletonPose()
    private val legFIK = SkeletonMath.IKResult()
    private val legBIK = SkeletonMath.IKResult()
    private val armAIK = SkeletonMath.IKResult()
    private val armPIK = SkeletonMath.IKResult()
    private val tempV1 = Vector3()
    private val tempV2 = Vector3()
    private val tempV3 = Vector3()
    private val tempV4 = Vector3()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.3f),
        durationSeconds = 6.0f,
        loopMode = LoopMode.HOLD,
        motionCurve = MotionCurve.EASE_IN_OUT
    )

    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val side = context.side
        val definition = context.definition

        val s = if (side == Side.RIGHT) 1f else -1f
        val u = progress // 0 = threaded under, 1 = open to sky

        // Root & anchors (runner's lunge, back toe on ground)
        val pelvis = tempV1.set(-18f, 35f + 20f * u, 0f)

        // Hip mapping: L is front/active, R is back/planted in the p5 code
        // We maintain: hipF (active/front), hipB (planted/back)
        val hipF = tempV2.set(0f, 0f, -definition.hipWidth * s).add(pelvis)
        val hipB = Vector3(0f, 0f, definition.hipWidth * s).add(pelvis)

        val ankleHeight = definition.foot.ankleHeight
        val frontAnkle = Vector3(140f, 9f + ankleHeight, -24f * s)
        val frontToe = Vector3(175f, 0f + ankleHeight, -27f * s)
        val backAnkle = Vector3(-170f, 18f + ankleHeight, 26f * s)
        val backToe = Vector3(-195f, 0f + ankleHeight, 28f * s)

        // Legs — locked-length IK, anatomical knee poles
        val legF = solveIK(hipF, frontAnkle, definition.thighLength, definition.shinLength, Vector3(1f, 0.35f, -0.15f * s), IKConstraint.LegConstraint, legFIK)
        val legB = solveIK(hipB, backAnkle, definition.thighLength, definition.shinLength, Vector3(0.12f, -1f, 0.08f * s), IKConstraint.LegConstraint, legBIK)

        // Spine (FK, exact torsoLength length) — rises slightly as arm opens
        val lean = tempV3.set(0.85f - 0.20f * u, 0.45f + 0.34f * u, -0.10f * u * s).normalize().copy()
        val chest = tempV4.set(lean).multiply(definition.torsoLength).add(pelvis)

        // Thoracic twist: shoulder girdle rotates about the spine axis
        val tw = lerp(0.50f, -1.35f, u) * s
        val shVec = rotAround(Vector3(0f, 0f, -1f), lean, tw, tempV2).normalize().copy()
        val shoulderA = Vector3(shVec.x, shVec.y, shVec.z).multiply(definition.shoulderWidth * s).add(chest) // active side
        val shoulderP = Vector3(shVec.x, shVec.y, shVec.z).multiply(-(definition.shoulderWidth * s)).add(chest) // planted side

        // Head follows the twist, gaze rises with the opening arm
        val neckBase = lerp(shoulderA, shoulderP, 0.5f, tempV1).copy()
        val headDir = tempV1.set(lean).multiply(0.8f).add(Vector3(0f, 0.4f + 0.6f * u, -0.2f * u * s)).normalize().copy()
        val neckEnd = Vector3(headDir.x, headDir.y, headDir.z).multiply(definition.neckLength).add(neckBase)
        val headPos = Vector3(headDir.x, headDir.y, headDir.z).multiply(definition.neckLength + 18f).add(neckBase)

        // Planted support arm (hand fixed on ground, elbow bows outward)
        val plantHand = Vector3(70f, 0f, 20f * s)
        val armP = solveIK(shoulderP, plantHand, definition.upperArmLength, definition.forearmLength, Vector3(-0.25f, 0.15f, 0.9f * s), IKConstraint.ArmConstraint, armPIK)

        // Active arm trajectory
        val D0 = Vector3(0.32f, -0.86f, 0.40f * s)
        val Dm = Vector3(0.30f, -0.05f, -0.88f * s)
        val D2 = Vector3(-0.08f, 0.98f, -0.20f * s)

        var dir: Vector3
        var r: Float
        val totalArmLen = definition.upperArmLength + definition.forearmLength

        if (u < 0.5f) {
            val k = easeInOut(u * 2f)
            dir = lerp(D0, Dm, k, tempV1).normalize().copy()
            r = lerp(0.72f, 0.92f, k) * totalArmLen
        } else {
            val k = easeInOut(u * 2f - 1f)
            dir = lerp(Dm, D2, k, tempV1).normalize().copy()
            r = lerp(0.92f, 0.985f, k) * totalArmLen
        }

        val handTargetRaw = tempV1.set(dir).multiply(r).add(shoulderA)
        val handTarget = if (handTargetRaw.y < 6f) {
            val v = handTargetRaw.copy()
            v.y = 6f
            v
        } else handTargetRaw

        val poleA = lerp(Vector3(0f, 0f, -1f * s), Vector3(-1f, 0f, -1f * s), u, tempV2).copy()
        val armA = solveIK(shoulderA, handTarget, definition.upperArmLength, definition.forearmLength, poleA, IKConstraint.ArmConstraint, armAIK)

        jointsBuffer.setJoint(Joint.PELVIS, pelvis)
        jointsBuffer.setJoint(Joint.HIP_F, hipF)
        jointsBuffer.setJoint(Joint.HIP_B, hipB)
        jointsBuffer.setJoint(Joint.KNEE_F, legF.joint)
        jointsBuffer.setJoint(Joint.ANKLE_F, legF.end)
        jointsBuffer.setJoint(Joint.TOE_F, frontToe)
        jointsBuffer.setJoint(Joint.KNEE_B, legB.joint)
        jointsBuffer.setJoint(Joint.ANKLE_B, legB.end)
        jointsBuffer.setJoint(Joint.TOE_B, backToe)
        jointsBuffer.setJoint(Joint.CHEST, chest)
        jointsBuffer.setJoint(Joint.SHOULDER_A, shoulderA)
        jointsBuffer.setJoint(Joint.SHOULDER_P, shoulderP)
        jointsBuffer.setJoint(Joint.ELBOW_A, armA.joint)
        jointsBuffer.setJoint(Joint.HAND_A, armA.end)
        jointsBuffer.setJoint(Joint.ELBOW_P, armP.joint)
        jointsBuffer.setJoint(Joint.HAND_P, armP.end)
        jointsBuffer.setJoint(Joint.NECK_END, neckEnd)
        jointsBuffer.setJoint(Joint.HEAD_POS, headPos)

        return jointsBuffer
    }
}
