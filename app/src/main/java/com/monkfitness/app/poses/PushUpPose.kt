package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class PushUpPose : PoseBuilder {
    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val def = context.definition

        // progress 0 (up) to 1 (down)
        // Root: Ankle is at Y=24 (biomechanically accurate height)
        val ankleHeight = 24f
        val ankleF = SkeletonNode(Joint.ANKLE_F, Vector3(120f, ankleHeight, def.hipWidth))
        val ankleB = SkeletonNode(Joint.ANKLE_B, Vector3(120f, ankleHeight, -def.hipWidth))

        // Leg rotation: 0 (flat on floor) to -25 degrees (up)
        val angleDeg = lerp(-25f, -10f, progress)
        val angleRad = angleDeg * PI.toFloat() / 180f

        // Build hierarchy
        val kneeF = ankleF.addChild(SkeletonNode(Joint.KNEE_F, Vector3(-def.shinLength, 0f, 0f)))
        val hipF = kneeF.addChild(SkeletonNode(Joint.HIP_F, Vector3(-def.thighLength, 0f, 0f)))

        val kneeB = ankleB.addChild(SkeletonNode(Joint.KNEE_B, Vector3(-def.shinLength, 0f, 0f)))
        val hipB = kneeB.addChild(SkeletonNode(Joint.HIP_B, Vector3(-def.thighLength, 0f, 0f)))

        // Rotate legs (ankle is root, rotate children)
        ankleF.setLocalRotation(angleRad)
        ankleB.setLocalRotation(angleRad)

        // Pelvis is midpoint of hips
        val hipFPos = hipF.getGlobalPosition()
        val hipBPos = hipB.getGlobalPosition()
        val pelvisPos = (hipFPos + hipBPos) * 0.5f

        // Torso: Deriving Chest from Pelvis + TorsoLength
        val torsoAngleDeg = lerp(5f, 2f, progress) // Slight adjustment for pushup form
        val torsoAngleRad = torsoAngleDeg * PI.toFloat() / 180f
        val torsoDir = Vector3(-cos(torsoAngleRad), -sin(torsoAngleRad), 0f)
        val chestPos = pelvisPos + torsoDir * def.torsoLength

        // Upper body
        val shoulderA = chestPos + Vector3(0f, 0f, def.shoulderWidth)
        val shoulderP = chestPos + Vector3(0f, 0f, -def.shoulderWidth)

        // Hands are planted on floor (Y=0)
        val handA = Vector3(chestPos.x, 0f, def.shoulderWidth * 1.5f)
        val handP = Vector3(chestPos.x, 0f, -def.shoulderWidth * 1.5f)

        val armA = solveIK(shoulderA, handA, def.upperArmLength, def.forearmLength, Vector3(0f, 0f, 1f), IKConstraint.ArmConstraint)
        val armP = solveIK(shoulderP, handP, def.upperArmLength, def.forearmLength, Vector3(0f, 0f, -1f), IKConstraint.ArmConstraint)

        val headDir = Vector3(-1f, 0.2f, 0f).normalize()
        val neckEnd = chestPos + headDir * def.neckLength
        val headPos = chestPos + headDir * (def.neckLength + 18f)

        val joints = mutableMapOf<Joint, Vector3>()
        ankleF.flatten(joints)
        ankleB.flatten(joints)
        joints[Joint.PELVIS] = pelvisPos
        joints[Joint.CHEST] = chestPos
        joints[Joint.SHOULDER_A] = shoulderA
        joints[Joint.SHOULDER_P] = shoulderP
        joints[Joint.ELBOW_A] = armA.joint
        joints[Joint.HAND_A] = armA.end
        joints[Joint.ELBOW_P] = armP.joint
        joints[Joint.HAND_P] = armP.end
        joints[Joint.NECK_END] = neckEnd
        joints[Joint.HEAD_POS] = headPos

        // Toes are planted on floor (Y=0)
        joints[Joint.TOE_F] = Vector3(ankleF.getGlobalPosition().x + 10f, 0f, def.hipWidth)
        joints[Joint.TOE_B] = Vector3(ankleB.getGlobalPosition().x + 10f, 0f, -def.hipWidth)

        return SkeletonPose(
            joints = joints,
            hints = mapOf(
                Joint.HAND_DIR_A to Vector3(-1f, 0f, 0f), // Flat on floor
                Joint.HAND_DIR_P to Vector3(-1f, 0f, 0f),
                Joint.FOOT_DIR_F to Vector3(1f, 0f, 0f), // Flat on floor
                Joint.FOOT_DIR_B to Vector3(1f, 0f, 0f)
            )
        )
    }
}
