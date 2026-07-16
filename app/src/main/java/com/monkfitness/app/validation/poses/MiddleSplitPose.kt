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
 * Engineering Validation — Middle Split.
 *
 * Static, frozen snapshot. Validates: pelvis centering, hip symmetry, leg IK,
 * arm length, shoulder symmetry, left/right mirror correctness.
 *
 * Authoring notes (ENGINEERING_VALIDATION_AUDIT §1): the previous pose placed the
 * straight-limb targets *inside* the proximal bone (leg dist 58.9 < L1 112; arm
 * dist 33.2 < L1 80), so `solveStraightLimb` fell back to the UNI-9 bent solve and
 * the authored `straight=true` intent was silently dropped — exactly the defect the
 * validator is meant to catch. The targets below are widened so every straight limb
 * is actually reachable (dist in [L1, L1+L2]), the hips externally rotate so the
 * knees face up, and the pelvis sits on the floor so the feet rest flat (no ground
 * penetration).
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

        // Seated on the floor: pelvis (and therefore the hips) at y = 0 so the straight legs
        // can lie flat out to the sides with the feet resting on the ground.
        val pelvisY = 0f
        pelvis!!.localPosition.set(0f, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, 0f)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        chest!!.localRotation.set(axisZ, 0f)

        buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        // Hip external (femoral axial) rotation: in a middle split the femurs abduct and the
        // knees must face up/forward, not laterally/down. This is the acetabular ball joint's
        // axial DOF, kept separate from the IK pole (audit §1.4). Capped at 0.8 rad (~46°) so the
        // resulting femur excursion (~136° from neutral) and axial twist (~46°) stay comfortably
        // inside the 150° excursion / 60° external-rotation ROM limits.
        buildHipRotation(hipF!!, 0.8f, -1f)
        buildHipRotation(hipB!!, 0.8f, 1f)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Symmetric wide split on the floor. A straight limb placed colinearly by solveStraightLimb
        // keeps BOTH bone lengths only when the target is at the true full-extension distance
        // dist = L1 + L2 (= 210 for the leg): the middle->end gap is dist - L1, which equals L2
        // only at full extension. Hips sit at z = ±hipWidth; the foot target is therefore placed
        // at |z| = hipWidth + (L1 + L2) = 22 + 210 = 232, on the floor (y = 0). The ground contact
        // keeps the foot on y = 0 (audit §1.1/§1.3). The ConstraintSolver re-bakes the leg against
        // this same target, reproducing the identical colinear, full-length placement.
        val spread = def.hipWidth + def.thighLength + def.shinLength
        val targetF = Vector3(0f, 0f, -spread)
        val targetB = Vector3(0f, 0f, spread)
        // Pole tracks the foot: pure lateral bend plane (the limb is straight so the pole is
        // unused by the solver, but a lateral pole keeps the knee facing the foot, audit §1.5).
        val legPoleF = Vector3(0f, 1f, -1f)
        val legPoleB = Vector3(0f, 1f, 1f)
        val groundContact = ContactConstraint.ground(0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, legStraightConstraint(def), pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, straight = true, contact = groundContact)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, legStraightConstraint(def), pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, straight = true, contact = groundContact)

        ankleF!!.localRotation.set(axisZ, 0f); ankleB!!.localRotation.set(axisZ, 0f)
        heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // Arms extended symmetrically out to the sides, palms down. Same full-extension rule as
        // the legs: a straight arm keeps both bones only at dist = L1 + L2 (= 146). The shoulder
        // sits at z = ±shoulderWidth, so the hand target is placed at |z| = shoulderWidth + 146
        // (= 192) at shoulder height (audit §1.2). The arm carries no contact, so it is exactly
        // the solveStraightLimb placement (no ConstraintSolver re-bake).
        val chestY = chest!!.worldPosition.y
        val armHandZ = def.shoulderWidth + def.upperArmLength + def.forearmLength
        val armTargetA = Vector3(0f, chestY, -armHandZ)
        val armTargetP = Vector3(0f, chestY, armHandZ)
        val armPoleA = Vector3(0f, -1f, -1f)
        val armPoleP = Vector3(0f, -1f, 1f)
        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armPoleA, armStraightConstraint(def), chest!!.worldRotation, elbowA!!, handA!!, armABuffer, straight = true)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPoleP, armStraightConstraint(def), chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, straight = true)

        handA!!.localRotation.set(axisZ, 0f); handP!!.localRotation.set(axisZ, 0f)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        return finalizePose()
    }
}
