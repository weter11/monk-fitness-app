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
 * Engineering Validation — Pike Sit.
 *
 * Static, frozen snapshot. Validates: seated pelvis, hamstring geometry, spine alignment,
 * shoulder flexion, arm reach, wrist orientation.
 */
class PikeSitPose : BaseValidationPose() {

    override val metadata = staticMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.28f, defaultZoom = 1.3f),
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

        val pelvisY = 40f
        // Fold the torso forward over the extended legs (+x is forward).
        val fold = 0.95f
        pelvis!!.localPosition.set(0f, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -fold)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        chest!!.localRotation.set(axisZ, -fold * 0.6f)

        // Head follows the folded thorax, gaze forward/down.
        val gaze = tempV3.set(1f, 0.2f, 0f).normalize()
        buildHead(neck!!, head!!, def.neckLength, gaze)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Legs straight forward (+x), knees locked, feet on the floor.
        val legLen = def.thighLength + def.shinLength * 0.95f
        val footX = legLen
        val targetF = Vector3(footX, 0f, -def.hipWidth * 0.9f)
        val targetB = Vector3(footX, 0f, def.hipWidth * 0.9f)
        val legPoleF = Vector3(0.3f, 1f, -0.2f)
        val legPoleB = Vector3(0.3f, 1f, 0.2f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, def.legIKConstraint, -fold, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, def.legIKConstraint, -fold, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, -fold); ankleB!!.localRotation.set(axisZ, -fold)
        heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // Arms reach forward to grasp the toes (shoulder flexion + arm reach).
        val handX = footX - def.forearmLength * 0.4f
        val handY = 8f
        val armTargetA = Vector3(handX, handY, -def.hipWidth * 0.9f)
        val armTargetP = Vector3(handX, handY, def.hipWidth * 0.9f)
        val armPoleA = Vector3(1f, 0.4f, -0.3f)
        val armPoleP = Vector3(1f, 0.4f, 0.3f)
        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, -fold * 0.6f, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, -fold * 0.6f, elbowP!!, handP!!, armPBuffer)

        handA!!.localRotation.set(axisZ, -fold * 0.6f); handP!!.localRotation.set(axisZ, -fold * 0.6f)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        return finalizePose()
    }
}
