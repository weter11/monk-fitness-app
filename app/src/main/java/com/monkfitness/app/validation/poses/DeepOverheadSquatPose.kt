package com.monkfitness.app.validation.poses

import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.ContactConstraint
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
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.569f, defaultZoom = 1.2f),
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
        // Hips track over the feet (audit §3.2): the previous x=-25 pushed the hip far behind the
        // foot target, reading as "sitting back". Center the pelvis on the midline so the femur
        // travels straight down into the squat.
        pelvis!!.localPosition.set(0f, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Coherent trunk lean (audit §3.1): a deep squat is one leaned trunk, not an opposed
        // pelvis/chest kink. The chest follows the pelvis forward (a touch less, so the upper
        // back stays long) rather than lifting against it.
        chest!!.localRotation.set(axisZ, -leanAngle * 0.3f)

        buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Deep squat: feet forward (+x), knees driven forward and out.
        val targetF = Vector3(10f, 0f, -def.hipWidth * 1.6f)
        val targetB = Vector3(10f, 0f, def.hipWidth * 1.6f)
        val legPoleF = Vector3(1f, 0f, -0.4f)
        val legPoleB = Vector3(1f, 0f, 0.4f)
        // Feet are fixed ground contacts: clamp the IK end onto the ground plane so a compact
        // target can't drive the ankle below level 0 (PR-03). The authored target is unchanged.
        val groundContact = ContactConstraint.ground(0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, contact = groundContact)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, contact = groundContact)

        // The engine derives heel/toe from the shank + the neutral ankle articulation (cancelling
        // the inherited lean automatically) and lays the foot flat on the ground — no manual
        // endpoint authoring, no leanAngle tilt counter-rotation.

        // Arms overhead (shoulder flexion to full elevation), reaching straight up.
        val shoulderY = shoulderA!!.worldPosition.y
        val reachUp = shoulderY + def.upperArmLength + def.forearmLength * 0.85f
        val armTargetA = Vector3(-5f, reachUp, -def.shoulderWidth * 0.5f)
        val armTargetP = Vector3(-5f, reachUp, def.shoulderWidth * 0.5f)
        val armPoleA = Vector3(0f, -0.6f, -1f)
        val armPoleP = Vector3(0f, -0.6f, 1f)
        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // The engine derives palm/knuckles/fingertips from the forearm + the neutral wrist
        // articulation (cancelling the inherited lean automatically) — no manual hand endpoint
        // authoring, no leanAngle*0.4f tilt counter-rotation.

        return finalizePose()
    }
}
