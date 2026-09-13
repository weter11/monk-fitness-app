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
 *  - SCAPULAE: protracted ("push the mat away"), never winged/retracted. Authored on the canonical
 *    girdle channel (`SkeletonMath.buildScapularRotation` via [driveScapula]): both blades protract
 *    toward their own planted elbows, flat against the rib cage (BPS §"Scapular strategy"), on the
 *    hold's own stabilization cycle — the same zero-at-the-endpoints [breathingSwell] driver the rib
 *    cage's swell uses. The amplitude is the family's [GIRDLE_PROTRACTION], derived from the planted
 *    forearm's own flat contact rather than the pull family's `4` units ([driveScapula] records the
 *    measured coupling); the thoracic rounding that carried this statement before it had a girdle
 *    drive stays as authored.
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
        // B-2 — the plant is declared on the ONE support channel (`SupportDefinition`), the channel
        // the pipeline derives `SkeletonPose.supportedPoints` from and the Finalizer's support-plane
        // derivation consumes. The former duplicate `supportContacts`/`pivotType` channels had no
        // production reader, so this pose's whole support model (both forearms + both toes) never
        // reached the runtime and the published frame carried an EMPTY support set.
        support = SupportDefinition(
            pivot = PivotType.ELBOWS,
            contacts = setOf(
                SupportContact.LEFT_FOREARM, SupportContact.RIGHT_FOREARM,
                SupportContact.LEFT_TOES, SupportContact.RIGHT_TOES
            )
        ),
        exerciseFamily = "plank",
        motionType = "Isometric Hold",
        bodyOrientation = "Prone"
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val lift = context.progress
        val breath = breathingSwell(lift)

        // --- 1. The planted feet (the family's rear support) ------------------------------------
        // Toes are planted at a FIXED world X (neutral pelvis is x≈0), independent of the COM sway,
        // so when the trunk drifts the legs re-solve and the toes act as real anchors. The small
        // fold keeps the knee off full lock; the LegConstraint's 0.98 extension ratio does the rest.
        val ankleX = -def.thighLength - def.shinLength + 24f
        val ankleY = SkeletonMath.lerp(contactY, 22f, lift)

        // --- 2. The planted forearms: the support chain, authored from its CONTACT --------------
        // Both forearms rest flat on the mat (the support `SupportContact.LEFT/RIGHT_FOREARM`
        // declares): the elbow directly under its shoulder, the hand one forearm length ahead of
        // it, both at the mat's planted-forearm height, and the shoulder at the top of that pillar.
        // The settled frame leans the pillar back over its elbow; the braced hold is the vertical
        // pillar BPS §6/§11 describe ("elbows directly under shoulders; the upper arms vertical").
        // The elbow therefore lands on the mat as the arm chain's OWN solution — see
        // [planPlantedForearm]: the pre-fix authoring instead aimed the solve's 60-unit bulge into
        // the floor, which is what put `ELBOW_A`/`ELBOW_P` 38–45 units below the mat.
        val elbowZ = def.shoulderWidth
        val handZ = def.shoulderWidth * 0.6f
        val pillarLean = SkeletonMath.lerp(settledPillarLean, 0f, lift)
        planPlantedForearm(def, forearmPlantX, -elbowZ, -handZ, pillarLean)
        val shoulderX = plantShoulder.x
        val shoulderY = plantShoulder.y
        targetA.set(plantHand)
        poleA.set(plantPole)

        // --- 3. Trunk: hung off the propped shoulder, hips settling into the plank line ---------
        // The braced frame's hip height is the one the straight shoulder→hip→ankle line fixes
        // (BPS §3: one line shoulder–hip–ankle, no sag, no butt-up); the settled frame drops the
        // hips as far as the planted leg still reaches with a folded knee. The trunk's inclination
        // and the hip's world X then follow from those two heights, because the trunk is rigid and
        // the shoulder cannot slide off the plant it is propped on.
        val bracedY = bracedBodyY(def, shoulderX, shoulderY, ankleX, ankleY)
        val bodyY = SkeletonMath.lerp(settledBodyY, bracedY, lift)
        val pitch = proppedTrunkPitch(def, bodyY, shoulderY)
        val hipX = proppedHipX(def, shoulderX, pitch)

        // Scapular protraction / thoracic rounding ramps in as the person presses up, with a tiny
        // breathing modulation. Kept small so it reads as a braced upper back, not a hunch.
        val chestFlex = SkeletonMath.lerp(0f, 0.09f, lift) + breath * 0.02f

        // Centre-of-mass drift: a few units forward over the forearms mid-hold and back. Zero at
        // the endpoints (breath is 0 there) so the contract holds; the planted arms absorb it, which
        // is what reads as weight transfer through arms that behave like fixed supports.
        val comShiftX = breath * 4f

        pelvis!!.localPosition.set(hipX + comShiftX, bodyY, 0f)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Phase 5 (W13/G4, W14/G5): single spine-intent call. Lower segment is the PELVIS; chest
        // adds the braced thoracic rounding. Hips inherit the (derived) incline.
        buildSpineCurve(pelvis!!, chest!!, pitch, chestFlex)

        // Head neutral, gaze slightly toward the mat ahead of the hands; a tiny nod with the
        // breath. The rest of the head motion is inherited from the thorax. Declared as a gaze
        // target (Phase 7 Gap 7) while the legacy direction path still writes the head.
        val headDir = tempV3.set(0.14f, 1f, 0f)
        SkeletonMath.rotAround(headDir, axisZ, breath * 0.05f, headDir)
        headDir.normalize()
        buildGaze(neck!!, head!!, def.neckLength, headDir)

        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        // --- 3b. The scapular girdle: the BPS's protraction, on the canonical channel -------------
        // BPS `Plank (Forearm)` §"Scapular strategy"/§5/§8/§11 — *"Scapulae protracted (serratus
        // anterior) and depressed, flat against the rib cage; the shoulder girdle is set"*, *"no
        // winging, no shrug"* — and this pose's own copy (`useScapula`… §SCAPULAE above). Before this
        // correction the two canonical SCAPULA joints published `0.0000` rad at every phase while the
        // pose drove only the chest's rounding, i.e. the exercise's stated girdle was carried by the
        // thorax alone.
        //
        // The drive is the hold's own stabilization cycle ([breathingSwell], zero at BOTH authored
        // endpoints) rather than a ramp onto the braced frame, and that is a measured necessity: the
        // PLANT is the constraint. At the braced endpoint the upper arm is exactly `80` u long and
        // exactly vertical (BPS §6/§11 — the pillar [planPlantedForearm] authors), so the support
        // shoulder sits exactly `80` u above its mat contact; a non-zero protraction lowers the
        // glenoid (`1.21` u at the authored amplitude) and an upper arm of fixed length can then no
        // longer reach a mat contact that stays DIRECTLY under it — the planted elbow leaves the mat
        // (measured `6.06` u at the pull family's `4` units; `PlankForearmSupportGeometryTest` pins
        // the braced hold's elbow-under-shoulder). The girdle's motion therefore lives inside the hold,
        // where the pose's own copy puts it ("a continuous, controlled stabilization cycle",
        // "bracing"), and the rep's two authored endpoints stay the pose's own frames.
        //
        // Both blades protract — the sign mirrors per side ([driveScapula]): a same-directions drive is
        // a shoulder-line twist in this layout, which would flatten one blade while winging the other.
        driveScapula(-GIRDLE_PROTRACTION * breath, GIRDLE_PROTRACTION * breath)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // --- 4. Legs: long, near-straight, toes planted behind the body -------------------------
        targetF.set(ankleX, ankleY, -def.hipWidth)
        poleF.set(0f, 1f, 0f) // residual knee bend points up, never sagging through the floor
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)

        targetB.set(ankleX, ankleY, def.hipWidth)
        poleB.set(0f, 1f, 0f)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // The engine derives heel/toe from the shank + the neutral ankle articulation. The
        // plantar-flexed (heels-lifted) foot is intentionally NOT hand-authored here; if the
        // engine derivation lands the foot flat that is an engine limitation left exposed.

        // --- 5. Arms: the planned plant, realized by the engine's own limb solve -----------------
        // The A-side plant/pole were planned in step 2 (the trunk was derived from them); the
        // P-side plant is the same chain reflected in Z.
        scratchShoulderA.set(shoulderA!!.worldPosition)
        bakeIkLimb(scratchShoulderA, targetA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)

        planPlantedForearm(def, forearmPlantX, elbowZ, handZ, pillarLean)
        targetP.set(plantHand)
        poleP.set(plantPole)
        scratchShoulderP.set(shoulderP!!.worldPosition)
        bakeIkLimb(scratchShoulderP, targetP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // Hands lie flat on the mat, fingers forward (open-hand family offsets 6/6/10).
        // W1: engine now derives hand orientation (removed tilt counter-rotation + 6/6/10 offsets).

        return finalizePlankPose()
    }

    companion object {
        /**
         * The settled frame's authored pillar lean in degrees (~12°): the deepest hip settle the
         * planted leg still reaches. Measured at the settled frame with this lean: the hip→ankle
         * span is `188.1` of the leg's `210`-unit length (`0.98` band ⇒ `205.8`), so the leg is
         * never pulled straight and the arm solve records no clamp.
         */
        const val SETTLED_PILLAR_LEAN_DEGREES = 12f

        /**
         * The settled hip height: the hips drop to it from the braced line and press back up. Fixed
         * (not a solver output) because it is the pose's authored amplitude; the constraint it
         * satisfies is the one above — at this height the planted leg still has 17.7 units of slack
         * inside its 0.98 reach band, and the hips' travel to the braced line (`37.2`) is the
         * rep's own motion.
         */
        const val SETTLED_BODY_Y = 30f
    }

    private val settledPillarLean = SETTLED_PILLAR_LEAN_DEGREES * (PI.toFloat() / 180f)
    private val settledBodyY = SETTLED_BODY_Y
}
