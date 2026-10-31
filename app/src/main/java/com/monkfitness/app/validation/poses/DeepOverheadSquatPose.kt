package com.monkfitness.app.validation.poses

import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.ContactConstraint
import com.monkfitness.app.animation.GroundDefinition
import com.monkfitness.app.animation.PivotType
import com.monkfitness.app.animation.PostureIntent
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SupportContact
import com.monkfitness.app.animation.SupportDefinition
import com.monkfitness.app.animation.SupportPoint
import com.monkfitness.app.animation.Vector3

/** Engineering Validation — Deep Overhead Squat: almost every joint at once — ankle mobility, knee tracking, pelvis depth, thoracic extension, overhead shoulders, whole-body proportions. */
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

        // Declaring the intent lets the solver derive the pelvis height (SEATED_NEAR_FLOOR → floor plus
        // seated hip height) and honour the ground contacts; the authored pelvis is only the IK seed.
        declarePosture(PostureIntent.Kind.SEATED_NEAR_FLOOR)

        val leanAngle = 0.5f
        val pelvisY = 30f
        pelvis!!.localPosition.set(0f, pelvisY, 0f)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // A deep squat is one leaned trunk, not an opposed pelvis/chest kink: the chest follows the pelvis
        // forward, a touch less, so the upper back stays long.
        buildSpineCurve(pelvis!!, chest!!, -leanAngle, -leanAngle * 0.3f, axisZ)

        buildGaze(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Deep squat: feet forward (+x), knees driven forward and out.
        val targetF = Vector3(10f, 0f, -def.hipWidth * 1.6f)
        val targetB = Vector3(10f, 0f, def.hipWidth * 1.6f)
        val legPoleF = Vector3(1f, 0f, -0.4f)
        val legPoleB = Vector3(1f, 0f, 0.4f)
        // Clamping the IK end onto the ground plane keeps a compact target from driving the ankle below
        // level 0; the authored target is unchanged.
        val groundContact = ContactConstraint.ground(0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, contact = groundContact)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, contact = groundContact)

        // Heel/toe come from the shank and the neutral ankle articulation, which cancels the inherited
        // lean — no endpoints, no leanAngle counter-rotation.

        // Arms screened overhead to full elevation.
        val shoulderY = shoulderA!!.worldPosition.y
        val reachUp = shoulderY + def.upperArmLength + def.forearmLength * 0.85f
        val armTargetA = Vector3(-5f, reachUp, -def.shoulderWidth * 0.5f)
        val armTargetP = Vector3(-5f, reachUp, def.shoulderWidth * 0.5f)
        val armPoleA = Vector3(0f, -0.6f, -1f)
        val armPoleP = Vector3(0f, -0.6f, 1f)
        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // Palm/knuckles/fingertips come from the forearm and the neutral wrist, cancelling the inherited
        // lean — no hand endpoints.

        return finalizePose()
    }
}
