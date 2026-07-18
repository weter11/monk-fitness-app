package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * Pike push-up — hips driven high so the body forms an inverted V; the
 * work is shoulder-flexion pressing the chest toward the hands. Distinct
 * geometry from the prone family (hips high, legs straight back), so it
 * owns its build() while reusing the same natural-looking conventions:
 *  - feet PLANTED via buildAnkleArticulation (toes plantar-flexed),
 *  - arm IK roots read from the ACTUAL shoulder node (IkStage-safe),
 *  - wrists mirrored HAND→WRIST at finalize (renderer convention),
 *  - forward-down gaze, slight scapular protraction.
 *
 * Engine-contact-less rigid shape (per playbook §2): the four supports
 * are declared in metadata for the renderer only.
 */
class PikePushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.2f

    // Elbow-bend plane points up-and-out so the arms frame the pike.
    override val poleA: Vector3 = Vector3(1f, 1f, -2f)
    override val poleP: Vector3 = Vector3(1f, 1f, 2f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
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
        )
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val totalLegLen = def.shinLength + def.thighLength

        // Feet set back to widen the V; chest sweeps forward-and-down across the rep.
        val ankleX = 135f
        val ankleHeight = 25f
        val chestX = lerp(-40f, 10f, context.progress)
        val chestY = lerp(130f, 35f, context.progress)

        // Triangle solver: ankle → hip → chest must close with leg + torso lengths.
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

        // Perpendicular to the leg direction → apex of the triangle.
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

        // Plant the feet (toes plantar-flexed); the hip carries the leg pitch.
        ankleF!!.localPosition.set(ankleX, ankleHeight, -def.hipWidth)
        buildAnkleArticulation(Extremity.FOOT_F, legPitch, 0f, ankleF!!)
        kneeF!!.localPosition.set(-def.shinLength, 0f, 0f)
        hipF!!.localPosition.set(-def.thighLength, 0f, 0f)

        pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
        // Pelvis tilt = torso pitch minus leg pitch keeps both sides sharing one global leg line.
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, torsoGlobalPitch - legPitch)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, torsoGlobalPitch - legPitch))
        chest!!.localPosition.set(-def.torsoLength, 0f, 0f)

        // Symmetry: identical global leg pitch on side B.
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        buildHipFlexion(hipB!!, legPitch - torsoGlobalPitch)
        kneeB!!.localPosition.set(def.thighLength, 0f, 0f)
        kneeB!!.localRotation.set(axisZ, 0f)
        ankleB!!.localPosition.set(def.shinLength, 0f, 0f)

        // Scapular protraction for a loaded look; gaze forward-down.
        buildClavicularRotation(clavicleA!!, 0.12f, 0f, 0f, -1f)
        buildClavicularRotation(clavicleP!!, 0.12f, 0f, 0f, +1f)
        val headDir = tempV1.set(-1f, -0.6f, 0f).normalize()
        buildGaze(neck!!, head!!, def.neckLength, headDir)

        val rSize = roots!!.size
        for (i in 0 until rSize) roots!![i].updateWorldTransforms(zeroVector, identityRotation)

        // Arm IK roots read from the ACTUAL shoulder nodes (after scapular
        // protraction moved them) — matches the engine-owned IkStage source.
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        for (i in 0 until rSize) roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        val shoulderAW = shoulderA!!.worldPosition
        val shoulderPW = shoulderP!!.worldPosition

        val handAnchorX = -25f
        val targetHandA = targetHandABuffer.set(handAnchorX, 0f, -def.shoulderWidth * gripWidthMultiplier)
        val targetHandP = targetHandPBuffer.set(handAnchorX, 0f, def.shoulderWidth * gripWidthMultiplier)

        armAPoleLocal.set(poleA.x, poleA.y, poleA.z)
        SkeletonMath.toLocalDirection(armAPoleLocal, chest!!.worldRotation, armAPoleLocal)
        val armAPoleWorld = SkeletonMath.toWorldDirection(armAPoleLocal, elbowA!!.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, armAPoleWorld, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK)

        armPPoleLocal.set(poleP.x, poleP.y, poleP.z)
        SkeletonMath.toLocalDirection(armPPoleLocal, chest!!.worldRotation, armPPoleLocal)
        val armPPoleWorld = SkeletonMath.toWorldDirection(armPPoleLocal, elbowP!!.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, armPPoleWorld, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK)

        // Neutral wrist (overhand-ish); mirrored to WRIST for the renderer.
        buildWristArticulation(Extremity.HAND_A, -torsoGlobalPitch, 0f, handA!!)
        buildWristArticulation(Extremity.HAND_P, -torsoGlobalPitch, 0f, handP!!)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
