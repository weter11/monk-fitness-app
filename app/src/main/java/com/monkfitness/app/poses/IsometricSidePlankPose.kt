package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Side Plank — biomechanics-first rewrite.
 *
 * Physio description (progress 0 -> 1 = "from lying on the side, lift the hips into
 * the braced side-plank line, then settle"; PING_PONG makes it a controlled,
 * continuously-stabilizing cycle):
 *
 *  - SUPPORT: the bottom (down-side) forearm and the lateral edge of the bottom
 *    foot. Both are planted as fixed world anchors so the trunk loads them.
 *  - STABLE joints: bottom elbow/wrist and the bottom foot. The spine is held in
 *    one long line.
 *  - MOVING joints: the hips (the prime mover — they lift the body off the mat),
 *    the down-side shoulder girdle (must stay *depressed + protracted*, not shrug
 *    to the ear), the rib cage (breathing), and the top arm.
 *  - CENTRE OF MASS: rises over the bottom forearm/foot base as the hips lift, and
 *    settles — the obliques do the work, not a rigid roll.
 *  - SCAPULA (down side): actively stabilized — the shoulder is pushed away from
 *    the ear (never collapsed into the joint). Modeled by keeping the support
 *    shoulder lifted well above the planted elbow.
 *  - RIB CAGE / PELVIS: pelvis is the driver here (it lifts the line); the rib
 *    cage follows with a gentle breathing swell.
 *  - TOP ARM: rests along the top hip and floats up slightly with the breath
 *    (a stable, believable side-plank hand-on-hip, not a rigid strut).
 *  - HEAD: neutral, in line with the spine; follows the thorax by FK.
 */
class IsometricSidePlankPose : BasePlankPose() {

    override val metadata = PoseMetadata(
        camera = plankCamera,
        durationSeconds = 4.0f,
        // PING_PONG (FastOutSlowIn both ways) removes the legacy LOOP's linear,
        // curve-ignoring constant-speed motion and its end-snap.
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = plankEnvironment,
        pivotType = PivotType.ELBOWS,
        supportContacts = setOf(
            SupportContact.RIGHT_FOREARM, // down-side support forearm
            SupportContact.RIGHT_FOOT
        ),
        exerciseFamily = "plank",
        motionType = "Isometric Hold",
        bodyOrientation = "Side-lying"
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val lift = context.progress
        val breath = breathingSwell(lift)

        // --- 1. Trunk anchoring -------------------------------------------------
        // Hip height is the family contract (15f resting -> 35f braced). A tiny
        // breath float (0 at the endpoints) keeps the hips alive mid-hold.
        val pelvisY = SkeletonMath.lerp(restingPelvisY, plankPelvisY, lift) + breath * 2f

        // Incline established toward the supporting shoulder. The end incline is
        // chosen so the rolled down-side shoulder stays ABOVE the planted elbow
        // (otherwise the long engine upper arm would drive the elbow through the
        // floor). This is the core geometric tension for the side plank at the
        // contract hip height of 35 — documented as debt in the report (§7).
        val torsoPitch = SkeletonMath.lerp(-1.57f, -1.18f, lift)

        pelvis!!.localPosition.set(0f, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, torsoPitch)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)

        // Head continues the spine line (neutral); the thorax carries the rest.
        buildGaze(neck!!, head!!, def.neckLength, tempV3.set(0f, 1f, 0f))

        // --- 2. Roll the body onto its side ------------------------------------
        // Build the neutral lateral offsets, then roll them 90° about the local
        // spine (Y) axis. This drops SHOULDER_P / HIP_B to the down side (support)
        // and lifts SHOULDER_A / HIP_F to the top (stacked).
        val spineRoll = PI.toFloat() / 2f
        val spineAxis = tempV1.set(0f, 1f, 0f)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)
        SkeletonMath.rotAround(hipF!!.localPosition, spineAxis, spineRoll, hipF!!.localPosition)
        SkeletonMath.rotAround(hipB!!.localPosition, spineAxis, spineRoll, hipB!!.localPosition)
        SkeletonMath.rotAround(shoulderA!!.localPosition, spineAxis, spineRoll, shoulderA!!.localPosition)
        SkeletonMath.rotAround(shoulderP!!.localPosition, spineAxis, spineRoll, shoulderP!!.localPosition)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // --- 3. Legs: stacked, bottom foot planted -----------------------------
        val ankleX = -def.thighLength - def.shinLength + 20f
        // Bottom leg (HIP_B) rests on the mat; top leg (HIP_F) stacks just above it.
        targetB.set(ankleX, contactY, 0f)
        poleB.set(0f, -1f, 0f)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        targetF.set(ankleX, contactY + SkeletonMath.lerp(0f, 10f, lift), 0f)
        poleF.set(0f, 1f, 0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)

        // The engine derives heel/toe from the shank + the neutral ankle articulation. The
        // side-rolled stacked/planted feet are intentionally NOT hand-authored here; any visual
        // shortfall from the engine's derivation is an engine limitation left exposed.

        scratchShoulderP.set(shoulderP!!.worldPosition)
        // Planted: the forearm world X is anchored so the trunk loads it. The
        // support shoulder stays lifted above the planted elbow (scapular
        // depression — no shrug into the ear).
        val handReach = def.forearmLength * 1.3f
        targetP.set(scratchShoulderP.x + handReach, contactY, 0f)
        poleP.set(-0.4f, -1f, 0f) // seat the elbow straight down onto the mat
        bakeIkLimb(scratchShoulderP, targetP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // The engine derives palm/knuckles/fingertips from the forearm + the neutral wrist
        // articulation; the support forearm is intentionally NOT hand-authored here.

        // --- 5. Top arm (SHOULDER_A): hand resting on the top hip --------------
        scratchShoulderA.set(shoulderA!!.worldPosition)
        // Hand settles onto the raised top hip and floats up a touch with the breath.
        targetA.set(hipF!!.worldPosition.x, hipF!!.worldPosition.y + 6f + breath * 8f, 0f)
        poleA.set(-1f, 1f, -1f) // elbow up and outward, away from the body
        bakeIkLimb(scratchShoulderA, targetA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)

        // The engine derives palm/knuckles/fingertips from the forearm + the neutral wrist
        // articulation; the top hand resting on the hip is intentionally NOT hand-authored here.

        return finalizePlankPose()
    }
}
