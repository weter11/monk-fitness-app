package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class PikePushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.2f

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_HAND),
                SupportContact(SupportPoint.RIGHT_HAND),
                SupportContact(SupportPoint.LEFT_TOES),
                SupportContact(SupportPoint.RIGHT_TOES)
            )
        ),
        pivotType = PivotType.FEET,
        supportContacts = setOf(
            SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND,
            SupportContact.LEFT_TOES, SupportContact.RIGHT_TOES
        ),
        exerciseFamily = "push-up",
        motionType = "Press",
        bodyOrientation = "Prone"
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val totalLegLen = def.shinLength + def.thighLength

        val ankleX = 135f // Moved feet further back to widen the V-angle
        val ankleHeight = 25f

        // Chest trajectory pulled forward slightly to give arms breathing room
        val chestX = lerp(-40f, 10f, context.progress)
        val chestY = lerp(130f, 35f, context.progress)

        // Kinematic Triangle Solver
        val dx = chestX - ankleX
        val dy = chestY - ankleHeight
        val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)

        val r1 = totalLegLen
        val r2 = def.torsoLength
        val dClamped = d.coerceIn(abs(r1 - r2), r1 + r2 - 0.1f)

        val a = (r1 * r1 - r2 * r2 + dClamped * dClamped) / (2f * dClamped)
        val h = sqrt(max(0f, r1 * r1 - a * a))

        val dirX = dx / dClamped
        val dirY = dy / dClamped

        val nx = dirY
        val ny = -dirX

        val px = ankleX + dirX * a + nx * h
        val py = ankleHeight + dirY * a + ny * h

        val legVecX = px - ankleX
        val legVecY = py - ankleHeight
        val legPitch = atan2(-legVecY, -legVecX)

        val torsoVecX = chestX - px
        val torsoVecY = chestY - py
        val torsoGlobalPitch = atan2(-torsoVecY, -torsoVecX)

        ankleF!!.localPosition.set(ankleX, ankleHeight, -def.hipWidth)
        // Branch C: ankle articulation (plantar/dorsi-flexion) routes through the §1.3 intent
        // carrier via the 2-DOF composer; flexion about the mediolateral Z axis, no inversion.
        buildAnkleArticulation(Extremity.FOOT_F, legPitch, 0f, ankleF!!)
        kneeF!!.localPosition.set(-def.shinLength, 0f, 0f)
        hipF!!.localPosition.set(-def.thighLength, 0f, 0f)

        pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
        // `declarePelvisTilt` already records the Joint.PELVIS intent on the carrier
        // (STABILIZATION_AUDIT P2: no duplicate joint intent line).
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, torsoGlobalPitch - legPitch)
        chest!!.localPosition.set(-def.torsoLength, 0f, 0f)

        // 1. Correcting the Right-Side (Side B) Floating Leg Asymmetry
        // By subtracting the torso pitch, we isolate and enforce the exact same global leg pitch on both sides.
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        // Phase 6 (W15/G7): route hip through the documented helper.
        buildHipFlexion(hipB!!, legPitch - torsoGlobalPitch)
        kneeB!!.localPosition.set(def.thighLength, 0f, 0f)
        kneeB!!.localRotation.set(axisZ, 0f)
        ankleB!!.localPosition.set(def.shinLength, 0f, 0f)

        val headDir = tempV1.set(-1f, -0.6f, 0f).normalize()
        buildGaze(neck!!, head!!, def.neckLength, headDir)

        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        // Phase 7 (G6) — route the shoulder girdle through buildShoulders + FK instead of the
        // hand-computed rotAround world-position. The chest world rotation already carries the torso
        // pitch, so FK-derived shoulderA/shoulderP.worldPosition equals the old rotAround result
        // (geometry unchanged), and the IK root now sits where the engine owns it.
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }
        val shoulderAW = shoulderA!!.worldPosition
        val shoulderPW = shoulderP!!.worldPosition

        val handAnchorX = -25f
        val targetHandA = targetHandABuffer.set(handAnchorX, 0f, -def.shoulderWidth * gripWidthMultiplier)
        val targetHandP = targetHandPBuffer.set(handAnchorX, 0f, def.shoulderWidth * gripWidthMultiplier)

        // Elbow poles are authored in WORLD space (MIGRATION_RULES A8: the pose never converts
        // frames — `bakeIkLimb` consumes the pole as world). The (1, 1, ±2) vectors seat the
        // elbow outward/upward. Shoulders already positioned by buildShoulders + FK above.
        val armAPoleWorld = Vector3(1f, 1f, -2f)
        bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, armAPoleWorld, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK)

        val armPPoleWorld = Vector3(1f, 1f, 2f)
        bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, armPPoleWorld, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK)

        // Branch C: wrist articulation (overhand-ish grip + counter torso pitch) routes
        // through the §1.3 intent carrier; flexion about the mediolateral Z axis, no
        // deviation. The engine (W1) derives the WRIST_* nodes from the hand + this
        // articulation, so the pose must NOT also copy wrist onto hand.
        buildWristArticulation(Extremity.HAND_A, -torsoGlobalPitch, 0f, handA!!)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        return jointsBuffer
    }
}
