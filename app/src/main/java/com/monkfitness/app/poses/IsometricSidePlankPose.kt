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
        // B-2 — the plant is declared on the ONE support channel (`SupportDefinition`), the channel
        // the pipeline derives `SkeletonPose.supportedPoints` from and the Finalizer's support-plane
        // derivation consumes. The former duplicate `supportContacts`/`pivotType` channels had no
        // production reader, so this pose's whole support model (down-side forearm + down-side foot)
        // never reached the runtime and the published frame carried an EMPTY support set.
        support = SupportDefinition(
            pivot = PivotType.ELBOWS,
            contacts = setOf(
                SupportContact.RIGHT_FOREARM, // down-side support forearm
                SupportContact.RIGHT_FOOT
            )
        ),
        exerciseFamily = "plank",
        motionType = "Isometric Hold",
        bodyOrientation = "Side-lying"
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val lift = context.progress
        val breath = breathingSwell(lift)

        // --- 1. The planted support foot (the down-side rear support) ----------------------------
        val ankleX = -def.thighLength - def.shinLength + 20f

        // --- 2. The planted support forearm: the down-side chain shoulder -> elbow -> hand ------
        // The declared `RIGHT_FOREARM` contact is the DOWN-side arm (B-4's canonical mapping:
        // `RIGHT_*` = the P/B family, which this pose rolls to the floor). Its forearm lies flat on
        // the mat: the elbow under the supporting shoulder, the hand one forearm length ahead of it
        // — and the shoulder is the top of that pillar. The settled frame leans the pillar back over
        // its elbow; the braced hold is the vertical pillar the side-plank BPS §6/§11 describe
        // ("supporting elbow directly under the shoulder; the supporting forearm flat"). The elbow
        // therefore lands on the mat as the chain's own solution instead of being driven 37.9 units
        // through it by an authored pole (the pre-fix measurement, `docs/STABILIZATION_AUDIT.md`).
        val elbowZ = -def.shoulderWidth
        val handZ = 0f // the hand is planted on the body's own midline
        val pillarLean = SkeletonMath.lerp(settledPillarLean, 0f, lift)
        planPlantedForearm(def, forearmPlantX, elbowZ, handZ, pillarLean)
        val shoulderX = plantShoulder.x
        val shoulderY = plantShoulder.y
        targetP.set(plantHand)
        poleP.set(plantPole)

        // --- 3. Trunk: the rolled torso, hung off the propped support shoulder -------------------
        // The braced frame's hip height is the one the straight shoulder→hip→ankle line of the
        // supporting side fixes (BPS §3/§5: the body one line, the hips lifted and level, no drop of
        // the lower hip); the settled frame drops the hips as far as the planted support leg still
        // reaches with a folded knee. The trunk's inclination and the hip's world X follow from
        // those two heights — the trunk is rigid and the propped shoulder cannot slide off its plant.
        val bracedY = bracedBodyY(def, shoulderX, shoulderY, ankleX, contactY)
        // A tiny breath float (0 at the endpoints) keeps the hips alive mid-hold.
        val bodyY = SkeletonMath.lerp(settledBodyY, bracedY, lift) + breath * 2f
        val pitch = proppedTrunkPitch(def, bodyY, shoulderY)
        val hipX = proppedHipX(def, shoulderX, pitch)

        pelvis!!.localPosition.set(hipX, bodyY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, pitch)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, pitch))

        chest!!.localPosition.set(0f, def.torsoLength, 0f)

        // Head continues the spine line (neutral); the thorax carries the rest.
        buildGaze(neck!!, head!!, def.neckLength, tempV3.set(0f, 1f, 0f))

        // --- 4. Roll the body onto its side ------------------------------------------------------
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

        // B-8 — the girdle roll above is a statement about the pose's TRUNK frame as well: rolling
        // the shoulder offsets 90° about the local spine axis moves the shoulder line onto the
        // chest's local X, and the chest's local -Z is that shoulder line, so the chest frame must
        // roll with it. The trunk frame is pose-owned Phase-0 intent (ARCHITECTURE_V2 §4.1), and
        // declaring it here (node write + the paired §1.1 chest carrier, the form every other
        // authored articulation uses) makes it authoritative BEFORE the Phase-1 limb realization.
        // Left unauthored, the frame was established only by the Finalizer's Phase-3 fallback
        // `reconstructChestFrame` — which derives exactly this roll from this layout — i.e. AFTER
        // the arms had been realized, and its re-FK then dragged the realized arm chain with the
        // reconstructed thorax: on a builder's first build the planted forearm landed 28 units off
        // target (B-8).
        chest!!.localRotation.set(axisY, spineRoll)
        declareJointIntent(Joint.CHEST, JointRotation(axisY, spineRoll))

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // --- 5. Legs: stacked, bottom foot planted ----------------------------------------------
        // Bottom leg (HIP_B) is the support leg: its foot is planted at the family's floor-contact
        // height and its down-side hip rests on the mat in the settled frame; the top leg (HIP_F)
        // stacks just above it.
        //
        // B3 — the support leg's residual knee bend is authored to leave the mat, never sag through
        // it. The stance (`ankleX`, `contactY`) spends most of the `112 / 98` chain's reach
        // (`197.6289 … 201.3616` of the constraint's own `maxReach = 205.8000`), so the chain can
        // never be drawn perfectly straight and its IK locus — a circle of radius `h = 29.7335 …
        // 37.3224` across the rep, `20.846` at the capped reach — has to bow to one side. The former pole
        // `(0, -1, 0)` aimed that bow INTO the mat and published `KNEE_B` `25.8897` below the pose's
        // own declared plane at every phase (the B-3 pin in
        // `PublishedBelowGroundInvariantTest`, whose exit criterion is this correction). BPS
        // `Plank (Side)` §7/§11 declare the supporting knee extended and in line with the trunk, and
        // the family's convention for the residual is the sibling `StaticForearmPlankPose`'s —
        // "residual knee bend points up, never sagging through the floor" — which is also the pole
        // the top leg below already uses.
        targetB.set(ankleX, contactY, 0f)
        poleB.set(0f, 1f, 0f) // residual knee bend points up, never sagging through the floor
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        targetF.set(ankleX, contactY + SkeletonMath.lerp(0f, 10f, lift), 0f)
        poleF.set(0f, 1f, 0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)

        // The engine derives heel/toe from the shank + the neutral ankle articulation. The
        // side-rolled stacked/planted feet are intentionally NOT hand-authored here; any visual
        // shortfall from the engine's derivation is an engine limitation left exposed.

        scratchShoulderP.set(shoulderP!!.worldPosition)
        // Planted: the forearm rests on the mat under the supporting shoulder (the plant planned in
        // step 2). The support shoulder stays lifted well above the planted elbow — scapular
        // depression, no shrug into the ear.
        bakeIkLimb(scratchShoulderP, targetP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // The engine derives palm/knuckles/fingertips from the forearm + the neutral wrist
        // articulation; the support forearm is intentionally NOT hand-authored here.

        // --- 6. Top arm (SHOULDER_A): hand resting on the top hip ------------------------------
        scratchShoulderA.set(shoulderA!!.worldPosition)
        // Hand settles onto the raised top hip and floats up a touch with the breath.
        targetA.set(hipF!!.worldPosition.x, hipF!!.worldPosition.y + 6f + breath * 8f, 0f)
        poleA.set(-1f, 1f, -1f) // elbow up and outward, away from the body
        bakeIkLimb(scratchShoulderA, targetA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)

        // The engine derives palm/knuckles/fingertips from the forearm + the neutral wrist
        // articulation; the top hand resting on the hip is intentionally NOT hand-authored here.

        return finalizePlankPose()
    }

    companion object {
        /**
         * The settled frame's authored pillar lean in degrees (~20°): the deepest hip settle this
         * pose's plant can reach. The side-rolled down-side hip sits `hipWidth` off the pelvis's
         * centre line, so the settle spends most of the support leg's slack before the hips have
         * dropped far. Measured at the settled frame with this lean: the down-side hip→ankle span is
         * `197.6` of the leg's `210`-unit length (`0.98` band ⇒ `205.8`) — 8.2 units of slack, no
         * clamp — while the hips travel `44.0` units into the braced line.
         */
        const val SETTLED_PILLAR_LEAN_DEGREES = 20f

        /**
         * The settled hip height: the hips lift from it into the braced line (BPS §9: the only
         * acceptable variation is steady breathing; the hips are the prime mover — the pose's
         * declared choreography is the hip lift itself). Fixed because it is the pose's authored
         * amplitude; the constraint it satisfies is the reach record above.
         */
        const val SETTLED_BODY_Y = 21f
    }

    private val settledPillarLean = SETTLED_PILLAR_LEAN_DEGREES * (PI.toFloat() / 180f)
    private val settledBodyY = SETTLED_BODY_Y
}
