package com.monkfitness.app.validation.poses

import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.GroundDefinition
import com.monkfitness.app.animation.PivotType
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SupportContact
import com.monkfitness.app.animation.SupportDefinition
import com.monkfitness.app.animation.SupportPoint
import com.monkfitness.app.animation.Vector3

/**
 * Engineering Validation — Middle Split.
 *
 * Static, frozen snapshot. Validates: pelvis centering, hip symmetry, leg IK,
 * arm length, shoulder symmetry, left/right mirror correctness.
 */
class MiddleSplitPose : BaseValidationPose() {

    override val metadata = staticMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.35f),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )
    )

    override fun buildStatic(def: SkeletonDefinition): SkeletonPose {
        ensureHierarchy(def)

        val pelvisY = 0f
        pelvis!!.localPosition.set(0f, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, 0f)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        chest!!.localRotation.set(axisZ, 0f)

        buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Symmetric wide split on the floor: legs abducted straight out to the sides at hip
        // height, feet flat on the ground. Targets reach near full extension so the IK solver
        // keeps the limbs straight (knees pointing outward) instead of folding them.
        val legReach = (def.thighLength + def.shinLength) * 0.95f
        val targetF = Vector3(0f, 0f, -(def.hipWidth + legReach))
        val targetB = Vector3(0f, 0f, def.hipWidth + legReach)
        val legPoleF = Vector3(0f, 1f, 0f)
        val legPoleB = Vector3(0f, 1f, 0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, def.legIKConstraint, 0f, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, def.legIKConstraint, 0f, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, 0f); ankleB!!.localRotation.set(axisZ, 0f)
        heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // Arms extended symmetrically straight out to the sides at shoulder height, palms down.
        val chestY = chest!!.worldPosition.y
        val armReach = (def.upperArmLength + def.forearmLength) * 0.95f
        val armTargetA = Vector3(0f, chestY, -(def.shoulderWidth + armReach))
        val armTargetP = Vector3(0f, chestY, def.shoulderWidth + armReach)
        val armPoleA = Vector3(0f, 1f, 0f)
        val armPoleP = Vector3(0f, 1f, 0f)
        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, 0f, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, 0f, elbowP!!, handP!!, armPBuffer)

        handA!!.localRotation.set(axisZ, 0f); handP!!.localRotation.set(axisZ, 0f)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        jointsBuffer.maxIkClampAmount = maxOf(legFBuffer.clampAmount, legBBuffer.clampAmount, armABuffer.clampAmount, armPBuffer.clampAmount)

        return finalizePose()
    }
}
