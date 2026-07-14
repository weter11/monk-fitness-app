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
 * Engineering Validation — Deep Overhead Squat.
 *
 * Static, frozen snapshot. Stresses almost every joint simultaneously. Validates:
 * ankle mobility, knee tracking, pelvis depth, thoracic extension, overhead shoulders,
 * whole-body proportions.
 */
class DeepOverheadSquatPose : BaseValidationPose() {

    override val metadata = staticMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.2f),
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

        val pelvisY = 30f
        val leanAngle = 0.5f
        pelvis!!.localPosition.set(-25f, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Thoracic extension: chest lifts slightly upright relative to the folded pelvis.
        chest!!.localRotation.set(axisZ, leanAngle * 0.4f)

        buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Deep squat: feet forward (+x), knees driven forward and out. The foot target sits
        // above the ground so the foot detail stays on/above the floor, and is placed far enough
        // forward that it is within the biological IK reach (>= minimum flexion distance).
        val targetF = Vector3(30f, 25f, -def.hipWidth * 1.6f)
        val targetB = Vector3(30f, 25f, def.hipWidth * 1.6f)
        val legPoleF = Vector3(1f, 0f, -0.4f)
        val legPoleB = Vector3(1f, 0f, 0.4f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, def.legIKConstraint, leanAngle, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, def.legIKConstraint, leanAngle, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, leanAngle); ankleB!!.localRotation.set(axisZ, leanAngle)
        heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // Arms overhead (shoulder flexion to full elevation), reaching straight up.
        val shoulderY = shoulderA!!.worldPosition.y
        val reachUp = shoulderY + def.upperArmLength + def.forearmLength * 0.85f
        val armTargetA = Vector3(-5f, reachUp, -def.shoulderWidth * 0.5f)
        val armTargetP = Vector3(-5f, reachUp, def.shoulderWidth * 0.5f)
        val armPoleA = Vector3(0f, -0.6f, -1f)
        val armPoleP = Vector3(0f, -0.6f, 1f)
        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, leanAngle * 0.4f, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, leanAngle * 0.4f, elbowP!!, handP!!, armPBuffer)

        handA!!.localRotation.set(axisZ, leanAngle * 0.4f); handP!!.localRotation.set(axisZ, leanAngle * 0.4f)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        jointsBuffer.maxIkClampAmount = maxOf(legFBuffer.clampAmount, legBBuffer.clampAmount, armABuffer.clampAmount, armPBuffer.clampAmount)

        return finalizePose()
    }
}
