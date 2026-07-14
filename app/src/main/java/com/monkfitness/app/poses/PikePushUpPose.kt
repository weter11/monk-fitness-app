package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class PikePushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.2f

    private val armAPoleLocal = Vector3()
    private val armPPoleLocal = Vector3()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_HAND),
                SupportContact(SupportPoint.RIGHT_HAND),
                SupportContact(SupportPoint.LEFT_TOES),
                SupportContact(SupportPoint.RIGHT_TOES)
            )
        )
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val totalLegLen = def.shinLength + def.thighLength

        val ankleX = 135f // Moved feet further back to widen the V-angle
        val ankleHeight = 25f

        // Chest trajectory pulled forward slightly to give arms breathing room
        val chestX = lerp(-40f, 10f, context.progress)
        val chestY = lerp(130f, 35f, context.progress)

        // Kinematic Triangle Solver
        val dx = chestX - ankleX
        val dy = chestY - ankleHeight
        val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)

        val r1 = totalLegLen
        val r2 = def.torsoLength
        val dClamped = d.coerceIn(abs(r1 - r2), r1 + r2 - 0.1f)

        val a = (r1 * r1 - r2 * r2 + dClamped * dClamped) / (2f * dClamped)
        val h = sqrt(max(0f, r1 * r1 - a * a))

        val dirX = dx / dClamped
        val dirY = dy / dClamped

        val nx = dirY
        val ny = -dirX

        val px = ankleX + dirX * a + nx * h
        val py = ankleHeight + dirY * a + ny * h

        val legVecX = px - ankleX
        val legVecY = py - ankleHeight
        val legPitch = atan2(-legVecY, -legVecX)

        val torsoVecX = chestX - px
        val torsoVecY = chestY - py
        val torsoGlobalPitch = atan2(-torsoVecY, -torsoVecX)

        ankleF!!.localPosition.set(ankleX, ankleHeight, -def.hipWidth)
        ankleF!!.localRotation.set(axisZ, legPitch)

        val worldFootDir = tempV1.set(0f, -1f, 0f)
        val localFootDir = rotAround(worldFootDir, axisZ, -legPitch, tempV2)
        heelF!!.localPosition.set(localFootDir.x * -def.foot.footLength * def.foot.heelRatio, localFootDir.y * -def.foot.footLength * def.foot.heelRatio, localFootDir.z * -def.foot.footLength * def.foot.heelRatio)
        toeF!!.localPosition.set(localFootDir.x * def.foot.footLength * def.foot.toeRatio, localFootDir.y * def.foot.footLength * def.foot.toeRatio, localFootDir.z * def.foot.footLength * def.foot.toeRatio)
        heelB!!.localPosition.set(localFootDir.x * -def.foot.footLength * def.foot.heelRatio, localFootDir.y * -def.foot.footLength * def.foot.heelRatio, localFootDir.z * -def.foot.footLength * def.foot.heelRatio)
        toeB!!.localPosition.set(localFootDir.x * def.foot.footLength * def.foot.toeRatio, localFootDir.y * def.foot.footLength * def.foot.toeRatio, localFootDir.z * def.foot.footLength * def.foot.toeRatio)

        kneeF!!.localPosition.set(-def.shinLength, 0f, 0f)
        hipF!!.localPosition.set(-def.thighLength, 0f, 0f)

        pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
        pelvis!!.localRotation.set(axisZ, torsoGlobalPitch - legPitch)
        chest!!.localPosition.set(-def.torsoLength, 0f, 0f)

        // 1. Correcting the Right-Side (Side B) Floating Leg Asymmetry
        // By subtracting the torso pitch, we isolate and enforce the exact same global leg pitch on both sides.
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        hipB!!.localRotation.set(axisZ, legPitch - torsoGlobalPitch)
        kneeB!!.localPosition.set(def.thighLength, 0f, 0f)
        kneeB!!.localRotation.set(axisZ, 0f)
        ankleB!!.localPosition.set(def.shinLength, 0f, 0f)

        val headDir = tempV1.set(-1f, -0.6f, 0f).normalize()
        buildHead(neck!!, head!!, def.neckLength, headDir)

        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(tempV1.set(0f, 0f, -def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV2).add(chestW)
        val shoulderPW = rotAround(tempV1.set(0f, 0f, def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV3).add(chestW)

        val handAnchorX = -25f
        val targetHandA = targetHandABuffer.set(handAnchorX, 0f, -def.shoulderWidth * gripWidthMultiplier)
        val targetHandP = targetHandPBuffer.set(handAnchorX, 0f, def.shoulderWidth * gripWidthMultiplier)

        // Pole vectors (1, 1, ±2) perfectly orient the shoulder joints outwards.
        // bakeIkLimb owns the IK-solve + local-space bake orchestration used by the whole family.
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        armAPoleLocal.set(1f, 1f, -2f)
        SkeletonMath.toLocalDirection(armAPoleLocal, chest!!.worldRotation, armAPoleLocal)
        bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, armAPoleLocal, chest!!.worldRotation, def.armIKConstraint, -torsoGlobalPitch, elbowA!!, handA!!, armAIK)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        armPPoleLocal.set(1f, 1f, 2f)
        SkeletonMath.toLocalDirection(armPPoleLocal, chest!!.worldRotation, armPPoleLocal)
        bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, armPPoleLocal, chest!!.worldRotation, def.armIKConstraint, -torsoGlobalPitch, elbowP!!, handP!!, armPIK)

        handA!!.localRotation.set(axisZ, -torsoGlobalPitch)
        val handDirA = tempV1.set(-1f, 0f, -0.1f).normalize()
        palmA!!.localPosition.set(handDirA.x * handPalmOffset, handDirA.y * handPalmOffset, handDirA.z * handPalmOffset); knucklesA!!.localPosition.set(handDirA.x * handPalmOffset, handDirA.y * handPalmOffset, handDirA.z * handPalmOffset); fingertipsA!!.localPosition.set(handDirA.x * handFingertipOffset, handDirA.y * handFingertipOffset, handDirA.z * handFingertipOffset)

        handP!!.localRotation.set(axisZ, -torsoGlobalPitch)
        val handDirP = tempV1.set(-1f, 0f, 0.1f).normalize()
        palmP!!.localPosition.set(handDirP.x * handPalmOffset, handDirP.y * handPalmOffset, handDirP.z * handPalmOffset); knucklesP!!.localPosition.set(handDirP.x * handPalmOffset, handDirP.y * handPalmOffset, handDirP.z * handPalmOffset); fingertipsP!!.localPosition.set(handDirP.x * handFingertipOffset, handDirP.y * handFingertipOffset, handDirP.z * handFingertipOffset)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        jointsBuffer.maxIkClampAmount = maxOf(armAIK.clampAmount, armPIK.clampAmount)
        return jointsBuffer
    }
}
