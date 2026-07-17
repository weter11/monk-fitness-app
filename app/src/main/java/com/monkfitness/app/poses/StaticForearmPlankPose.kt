package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Forearm Plank — biomechanics-first rewrite.
 *
 * Physio description of the movement this pose reproduces (progress 0 -> 1 is
 * "settle down toward the mat, then press back up into the braced hold"; PING_PONG
 * loops it as a continuous, controlled stabilization cycle):
 *
 *  - SUPPORT: both forearms flat on the mat (elbows loaded, ~90°, never locked)
 *    plus both sets of toes. These four contacts are *planted* — authored as fixed
 *    world-space IK targets so the limbs re-solve as the trunk moves, giving them
 *    real "support" weight instead of floating with the body.
 *  - STABLE joints: elbows, wrists, toes (ground contacts). The lumbar spine is
 *    held neutral by the pelvis rather than moving.
 *  - MOVING joints: the shoulder girdle (scapular protraction), the thoracic
 *    spine (subtle rounding + breathing), and the whole trunk as the centre of
 *    mass drifts forward over the forearms and back.
 *  - CENTRE OF MASS: shifts a few units forward toward the forearms mid-hold
 *    (bracing) and returns — visible weight transfer through the planted arms.
 *  - SCAPULAE: protracted ("push the mat away"), never winged/retracted. Modeled
 *    as the forearm support lifting the shoulders away from the floor plus a small
 *    thoracic rounding that ramps in as the person presses up.
 *  - RIB CAGE: follows the shoulder girdle; gentle breathing swell mid-hold.
 *  - PELVIS: stabiliser. It sets the trunk height/line and holds a neutral tilt;
 *    it does not drive the motion.
 *  - HEAD: neutral, gaze just ahead of the hands; it follows the thorax by FK
 *    (no independent head choreography).
 */
class StaticForearmPlankPose : BasePlankPose() {

    override val metadata = PoseMetadata(
        camera = plankCamera,
        // Entering and stabilizing a plank is a slow, controlled action. 4.0s one-way
        // (PING_PONG => ~8s full cycle) reads as deliberate bracing, not a twitch.
        durationSeconds = 4.0f,
        // PING_PONG applies FastOutSlowInEasing at BOTH turnarounds (no snap). The
        // legacy LOOP fed raw linear progress (its EASE_IN_OUT curve was ignored by
        // the controller) and reversed with LinearEasing -> the robotic constant-speed
        // motion and end-snap the rewrite is meant to remove.
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = plankEnvironment,
        pivotType = PivotType.ELBOWS,
        supportContacts = setOf(
            SupportContact.LEFT_FOREARM, SupportContact.RIGHT_FOREARM,
            SupportContact.LEFT_TOES, SupportContact.RIGHT_TOES
        ),
        exerciseFamily = "plank",
        motionType = "Isometric Hold",
        bodyOrientation = "Prone"
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val lift = context.progress
        val breath = breathingSwell(lift)

        // --- 1. Trunk anchoring -------------------------------------------------
        // Pelvis height is the family contract (15f resting -> 35f braced).
        val pelvisY = SkeletonMath.lerp(restingPelvisY, plankPelvisY, lift)

        // The braced plank is a straight, gently inclined line: forearms prop the
        // shoulders up, hips mid, toes low. -1.57 (flat) -> -1.38 (~11° incline).
        val torsoPitch = SkeletonMath.lerp(-1.57f, -1.38f, lift)

        // Scapular protraction / thoracic rounding ramps in as the person presses
        // up, with a tiny breathing modulation. Kept small so it reads as a braced
        // upper back, not a hunch.
        val chestFlex = SkeletonMath.lerp(0f, 0.09f, lift) + breath * 0.02f

        // Centre-of-mass drift: a few units forward over the forearms mid-hold and
        // back. Zero at the endpoints (breath is 0 there) so the contract holds and
        // the planted forearms/toes re-solve, showing weight transfer.
        val comShiftX = breath * 4f

        pelvis!!.localPosition.set(comShiftX, pelvisY, 0f)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Phase 5 (W13/G4, W14/G5): single spine-intent call. Lower segment is the
        // PELVIS; chest adds the braced thoracic rounding. Hips inherit the incline.
        buildSpineCurve(pelvis!!, chest!!, torsoPitch, chestFlex)

        // Head neutral, gaze slightly toward the mat ahead of the hands; a tiny nod
        // with the breath. The rest of the head motion is inherited from the thorax. Declared as
        // a gaze target (Phase 7 Gap 7) while the legacy direction path still writes the head.
        val headDir = tempV3.set(0.14f, 1f, 0f)
        SkeletonMath.rotAround(headDir, axisZ, breath * 0.05f, headDir)
        headDir.normalize()
        buildGaze(neck!!, head!!, def.neckLength, headDir)

        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // --- 2. Legs: long, near-straight, toes planted behind the body ---------
        // Toes are planted at a FIXED world X (neutral pelvis is x=0), independent of
        // the COM sway, so when the trunk drifts forward the legs re-solve and the
        // toes act as real anchors. A small fold keeps the knee off full lock; the
        // LegConstraint's 0.98 extension ratio does the rest.
        val ankleX = -def.thighLength - def.shinLength + 24f
        val ankleY = SkeletonMath.lerp(contactY, 22f, lift)

        targetF.set(ankleX, ankleY, -def.hipWidth)
        poleF.set(0f, 1f, 0f) // residual knee bend points up, never sagging through the floor
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)

        targetB.set(ankleX, ankleY, def.hipWidth)
        poleB.set(0f, 1f, 0f)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // The engine derives heel/toe from the shank + the neutral ankle articulation. The
        // plantar-flexed (heels-lifted) foot is intentionally NOT hand-authored here; if the
        // engine derivation lands the foot flat that is an engine limitation left exposed.

        // --- 3. Forearms: flat on the mat, elbows loaded under the shoulders -----
        scratchShoulderA.set(shoulderA!!.worldPosition)
        scratchShoulderP.set(shoulderP!!.worldPosition)

        // The forearms are PLANTED: their world X is anchored to the *neutral* (no
        // sway) shoulder position, so as the COM drifts forward by comShiftX the
        // effective forearm reach shortens and the elbows load — visible weight
        // transfer through arms that behave like fixed supports. Hands tuck slightly
        // inward (mild A-frame). Hand height = forearm resting height. The reach is
        // set so the elbow holds a loaded ~75° bend (never locked) given the engine's
        // long upper arm vs. the low braced-shoulder height (see report §7 debt).
        val handReach = def.forearmLength * 1.15f
        val handZ = def.shoulderWidth * 0.6f
        val handPlantAX = (scratchShoulderA.x - comShiftX) + handReach
        val handPlantPX = (scratchShoulderP.x - comShiftX) + handReach

        targetA.set(handPlantAX, contactY, -handZ)
        poleA.set(-0.5f, -1f, -0.3f) // seat the elbow down/back and slightly outward onto the mat
        bakeIkLimb(scratchShoulderA, targetA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)

        targetP.set(handPlantPX, contactY, handZ)
        poleP.set(-0.5f, -1f, 0.3f)
        bakeIkLimb(scratchShoulderP, targetP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // Hands lie flat on the mat, fingers forward (open-hand family offsets 6/6/10).
        // W1: engine now derives hand orientation (removed tilt counter-rotation + 6/6/10 offsets).

        return finalizePlankPose()
    }
}
