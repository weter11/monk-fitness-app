package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * Quadruped Thoracic Rotations — rewritten for correct biomechanics.
 *
 * The motion is now driven by the rib cage: the chest node rotates about the spine's
 * long axis (chest-local +Y), so the rib cage, neck, head and BOTH shoulders rotate
 * together as one segment. The supporting hand is pinned to the floor (a stable pillar),
 * while the reaching hand lives in the chest's rotating frame so the arm follows the
 * thorax as it threads under and opens to the sky. The pelvis and both planted legs
 * stay fixed in tabletop.
 */
class QuadrupedThoracicRotationsPose : BaseThoracicPose() {

    // Gaze up, slightly forward, toward the active side (constant — follows the thorax via FK).
    private val headDir = Vector3(0.3f, 1.0f, -0.25f).normalize()

    override val metadata = PoseMetadata(
        camera = thoracicCamera(0.24f, 1.2f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = thoracicGround
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val progress = context.progress

        // Tabletop anchoring: spine flat to the floor, pelvis stable.
        spinePitch = -PI.toFloat() / 2f
        val basePelvisX = -20f
        val basePelvisY = 127f

        pelvis!!.localPosition.set(basePelvisX, basePelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, spinePitch)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Thoracic rotation about the spine (chest-local +Y). Negative twist lifts the
        // active (-Z) shoulder up toward the sky; positive tucks it down/under.
        val twist = lerp(0.35f, -1.35f, progress)
        buildChestTwist(chest!!, twist)

        buildHead(neck!!, head!!, def.neckLength, headDir)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Legs: tabletop — knees and shins planted on the floor behind the hips.
        targetF.set(basePelvisX - def.shinLength, 15f, -def.hipWidth)
        targetB.set(basePelvisX - def.shinLength, 15f, def.hipWidth)
        poleF.set(0f, -1f, -0.5f); poleB.set(0f, -1f, 0.5f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        val chestW = chest!!.worldPosition

        // Support arm (P): hand pinned to the floor directly under the support shoulder -> stable pillar.
        targetP.set(chestW.x, 0f, def.shoulderWidth)
        poleP.set(0f, -1f, 1f)
        bakeThoracicArm(shoulderP!!.worldPosition, targetP, def, poleP, elbowP!!, handP!!, armPBuffer)

        // Reaching arm (A): target lives in the chest's rotating frame, so it follows the
        // thorax. Starts threaded under the chest, opens up toward the sky as the spine rotates.
        reachLocal.set(0.35f, lerp(-0.25f, 1.25f, progress), -0.45f).normalize()
        val reachLen = (def.upperArmLength + def.forearmLength) * 0.82f
        reachWorld.set(reachLocal.x * reachLen, reachLocal.y * reachLen, reachLocal.z * reachLen)
        chestLocalToWorld(reachWorld, targetA)
        poleA.set(0.2f, -0.6f, -1f)
        if (targetA.y < 6f) targetA.y = 6f
        bakeThoracicArm(shoulderA!!.worldPosition, targetA, def, poleA, elbowA!!, handA!!, armABuffer)

        // Extremities
        applyThoracicHands()
        // W1: engine now derives foot/hand orientation (removed tilt counter-rotation + endpoints).

        return finalizeThoracicPose()
    }
}
