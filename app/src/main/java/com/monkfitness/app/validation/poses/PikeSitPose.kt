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
 * Engineering Validation — Pike Sit.
 *
 * Static, frozen snapshot. Validates: seated pelvis, hamstring geometry, spine alignment,
 * shoulder flexion, arm reach, wrist orientation.
 */
class PikeSitPose : BaseValidationPose() {

    override val metadata = staticMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.629f, defaultZoom = 1.3f),
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

        val pelvisY = 14f
        // Fold the torso forward over the extended legs (+x is forward).
        val fold = 0.95f
        pelvis!!.localPosition.set(0f, pelvisY, 0f)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // B2: route the pike fold through the declarative spine curve so carriers populate.
        buildSpineCurve(pelvis!!, chest!!, -fold, -fold * 0.6f, axisZ)

        // Head follows the folded thorax, gaze forward/down.
        val gaze = tempV3.set(1f, 0.2f, 0f).normalize()
        buildGaze(neck!!, head!!, def.neckLength, gaze)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        // Shoulder girdle: as the chest folds forward, the clavicles/scapulae protract so the
        // shoulders travel forward over the knees (audit §2.3 — the validation base previously
        // left the girdle a rigid pass-through).
        buildClavicularRotation(clavicleA!!, elevation = 0f, protraction = 0.6f, axialRotation = 0f, sideSign = -1f)
        buildClavicularRotation(clavicleP!!, elevation = 0f, protraction = 0.6f, axialRotation = 0f, sideSign = 1f)
        buildScapularRotation(scapulaA!!, retraction = 0f, depression = 0f, sideSign = -1f)
        buildScapularRotation(scapulaP!!, retraction = 0f, depression = 0f, sideSign = 1f)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Legs straight forward (+x), knees locked, feet on the floor.
        val legLen = def.thighLength + def.shinLength * 0.95f
        val footX = legLen
        val targetF = Vector3(footX, 0f, -def.hipWidth * 0.9f)
        val targetB = Vector3(footX, 0f, def.hipWidth * 0.9f)
        val legPoleF = Vector3(0.3f, 1f, -0.2f)
        val legPoleB = Vector3(0.3f, 1f, 0.2f)
        val groundContact = ContactConstraint.ground(0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, legStraightConstraint(def), pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, straight = true, contact = groundContact)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, legStraightConstraint(def), pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, straight = true, contact = groundContact)

        // Flat foot: the engine derives heel/toe from the shank (knee→ankle) + the neutral ankle
        // articulation, cancelling the inherited fold automatically and laying the foot flat on the
        // ground (heel ≈ toe ≈ 0). No manual endpoint authoring, no +fold tilt counter-rotation.

        // Arms reach forward to grasp the toes. The target is measured from the *actual folded
        // shoulder* (audit §2.1: the previous target was ~252u from the shoulder — unreachable
        // for a 146u arm — so the solver clamped it and broke bone lengths). We aim the hand at
        // the toes but never farther than the reachable band, so the arm stays a valid (slightly
        // bent) reach instead of an over-clamped limb.
        val footTargetF = Vector3(footX, 8f, -def.hipWidth * 0.9f)
        val footTargetB = Vector3(footX, 8f, def.hipWidth * 0.9f)
        val armReachF = tempV1.set(footTargetF).subtract(shoulderA!!.worldPosition)
        val armReachB = tempV2.set(footTargetB).subtract(shoulderP!!.worldPosition)
        val maxReach = (def.upperArmLength + def.forearmLength) * 0.92f
        if (armReachF.mag() > maxReach) armReachF.normalize().multiply(maxReach)
        if (armReachB.mag() > maxReach) armReachB.normalize().multiply(maxReach)
        val armTargetA = Vector3(shoulderA!!.worldPosition.x + armReachF.x, shoulderA!!.worldPosition.y + armReachF.y, shoulderA!!.worldPosition.z + armReachF.z)
        val armTargetP = Vector3(shoulderP!!.worldPosition.x + armReachB.x, shoulderP!!.worldPosition.y + armReachB.y, shoulderP!!.worldPosition.z + armReachB.z)
        val armPoleA = Vector3(1f, 0.4f, -0.3f)
        val armPoleP = Vector3(1f, 0.4f, 0.3f)
        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // The engine derives palm/knuckles/fingertips from the forearm + the neutral wrist
        // articulation, cancelling the inherited chest tilt automatically — no manual hand
        // endpoint authoring, no -fold*0.6f tilt counter-rotation.

        return finalizePose()
    }
}
probe
