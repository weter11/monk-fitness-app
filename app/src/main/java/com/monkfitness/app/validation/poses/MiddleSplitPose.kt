package com.monkfitness.app.validation.poses

import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.ContactConstraint
import com.monkfitness.app.animation.PivotType
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SupportContact
import com.monkfitness.app.animation.SupportDefinition
import com.monkfitness.app.animation.SupportPoint
import com.monkfitness.app.animation.Vector3

/**
 * Engineering Validation — Middle Split, a **diagnostic instrument**: a probe whose reading must stay
 * faithful to the engine's state, never a pose retuned until it renders green.
 *
 * The split and the arm span are authored *inside* the proximal bone (leg hip→foot ≈ 58.9 < L1 112; arm
 * shoulder→hand ≈ 33.2 < L1 80), so `SkeletonMath.solveStraightLimb` takes its `dist < L1` branch and
 * silently drops the authored `straight = true`. That dropped intent **is** the reading the
 * `STRAIGHT_LIMB_INTENT` check surfaces.
 *
 * Do **not** widen the spread to full reach to make the limbs resolve straight: that moves the instrument
 * off the fault so the fault stops registering, which is the green-tuning the diagnostic rule forbids. A
 * truly straight split requires the ENGINE to honour `straight = true` at in-proximal targets.
 */
class MiddleSplitPose : BaseValidationPose() {

    override val metadata = staticMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.569f, defaultZoom = 1.35f),
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

        // Seated reference: the hips sit just above the floor. The exact height is not the point here —
        // the straight-limb reach is.
        val pelvisY = 14f
        pelvis!!.localPosition.set(0f, pelvisY, 0f)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildSpineCurve(pelvis!!, chest!!, 0f, 0f, axisZ)

        buildGaze(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Spread = hipWidth * 3.6 = 79.2 with the hip at z = ±hipWidth, so hip→foot ≈ 57.2 (replica
        // 58.9) << L1 = 112: the authored straight intent is dropped, which is the reading UNI-2 must
        // surface. Do NOT widen this to full reach to "fix" the render — that tampers with the probe.
        val spread = def.hipWidth * 3.6f
        val targetF = Vector3(0f, 0f, -spread)
        val targetB = Vector3(0f, 0f, spread)
        val legPoleF = Vector3(0.2f, 1f, -0.6f)
        val legPoleB = Vector3(0.2f, 1f, 0.6f)
        val groundContact = ContactConstraint.ground(0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, legStraightConstraint(def), pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, straight = true, contact = groundContact)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, legStraightConstraint(def), pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, straight = true, contact = groundContact)

        // Heel/toe come from the shank and the neutral ankle articulation — no manual endpoints, no tilt.

        // The same probe for the arms: hand targets inside the upper-arm bone (shoulder→hand ≈ 33.2 <<
        // L1 80) drop the straight arm to the bent fallback. Same reading, same rule against retargeting.
        val chestY = chest!!.worldPosition.y
        val armHandZ = spread
        val armTargetA = Vector3(0f, chestY, -armHandZ)
        val armTargetP = Vector3(0f, chestY, armHandZ)
        val armPoleA = Vector3(0.2f, -1f, -0.6f)
        val armPoleP = Vector3(0.2f, -1f, 0.6f)
        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armPoleA, armStraightConstraint(def), chest!!.worldRotation, elbowA!!, handA!!, armABuffer, straight = true)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPoleP, armStraightConstraint(def), chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, straight = true)

        // Palm/knuckles/fingertips are derived from the forearm and the neutral wrist — no hand endpoints.

        return finalizePose()
    }
}
