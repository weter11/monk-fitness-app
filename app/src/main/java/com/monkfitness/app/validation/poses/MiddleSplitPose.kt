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
 * Engineering Validation — Middle Split.
 *
 * Static, frozen snapshot. **Diagnostic instrument** (see
 * `docs/VALIDATION.md §2` and `MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md`): a validation pose
 * is not a development target that gets retuned until it renders green. It is a probe
 * whose reading must stay faithful to the engine's true state.
 *
 * This pose requests a wide straight-limb split with the foot/hand targets placed
 * *inside* the proximal bone (leg hip→foot ≈ 58.9 < L1 112; arm shoulder→hand ≈ 33.2
 * < L1 80). At those distances `SkeletonMath.solveStraightLimb` takes the `dist < L1`
 * branch and returns a **bent** triangle limb: the authored `straight = true` intent
 * is silently dropped. That is the exact engine limitation this instrument exists to
 * surface — `STRAIGHT_LIMB_INTENT` (UNI-2) reads it as a dropped-intent failure.
 *
 * Do **not** widen the spread to full reach to make the limbs resolve straight. Doing
 * so moves the instrument off the fault so the fault stops registering (green-tuning),
 * which is precisely what the diagnostic-instrument rule forbids. If a true-straight
 * middle split is ever wanted, the ENGINE must first honour `straight = true` at
 * in-proximal-radius targets — the pose is left as the reading, not the fix.
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

        // Seated reference: pelvis (and therefore the hips) slightly above the floor. The exact
        // height is not the point of this instrument — the straight-limb reach is (see below).
        val pelvisY = 14f
        pelvis!!.localPosition.set(0f, pelvisY, 0f)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // B2: route the (neutral) trunk through the declarative spine curve so carriers populate.
        buildSpineCurve(pelvis!!, chest!!, 0f, 0f, axisZ)

        buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Straight-intent diagnostic: request a wide split with the foot targets placed *inside*
        // the proximal (thigh) bone. Spread = hipWidth * 3.6 = 79.2; the hip sits at z = ±hipWidth,
        // so hip→foot ≈ 79.2 − 22 = 57.2 (replica 58.9) << L1 = 112. `solveStraightLimb` therefore
        // takes the `dist < L1` branch and returns a BENT limb: the authored straight intent is
        // dropped. That dropped intent is the reading `STRAIGHT_LIMB_INTENT` (UNI-2) must surface.
        // Do NOT widen this to full reach to "fix" the render — that would tamper with the probe.
        val spread = def.hipWidth * 3.6f
        val targetF = Vector3(0f, 0f, -spread)
        val targetB = Vector3(0f, 0f, spread)
        val legPoleF = Vector3(0.2f, 1f, -0.6f)
        val legPoleB = Vector3(0.2f, 1f, 0.6f)
        val groundContact = ContactConstraint.ground(0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, legStraightConstraint(def), pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, straight = true, contact = groundContact)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, legStraightConstraint(def), pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, straight = true, contact = groundContact)

        // The engine derives heel/toe from the shank + the neutral ankle articulation (cancelling
        // inherited tilt automatically) — no manual endpoint authoring, no ankle tilt.

        // Arms extended out to the sides with the same straight-intent probe: hand targets placed
        // inside the upper-arm bone. Spread = shoulderWidth * 1.72 ≈ 79.2; the shoulder sits at
        // z = ±shoulderWidth, so shoulder→hand ≈ 33.2 << L1 = 80 → the straight arm also drops to
        // the bent fallback. Same reading as the legs; same rule against retargeting to green.
        val chestY = chest!!.worldPosition.y
        val armHandZ = spread
        val armTargetA = Vector3(0f, chestY, -armHandZ)
        val armTargetP = Vector3(0f, chestY, armHandZ)
        val armPoleA = Vector3(0.2f, -1f, -0.6f)
        val armPoleP = Vector3(0.2f, -1f, 0.6f)
        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armPoleA, armStraightConstraint(def), chest!!.worldRotation, elbowA!!, handA!!, armABuffer, straight = true)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPoleP, armStraightConstraint(def), chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, straight = true)

        // The engine derives palm/knuckles/fingertips from the forearm + the neutral wrist
        // articulation — no manual hand endpoint authoring, no hand tilt.

        return finalizePose()
    }
}
