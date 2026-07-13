package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseBirdDogPose is the single owner of all Bird Dog biomechanical scaffolding.
 *
 * It consolidates the duplicated engine knowledge that previously lived in each
 * variant (manual SkeletonNode construction, manual solveIK + rotAround, and the
 * duplicated camera / environment metadata). Variants only declare their metadata
 * and the diagonal-extension choreography.
 *
 * Shared responsibilities:
 *  - Skeleton hierarchy via SkeletonFactory.createStandardSkeleton()
 *  - Quadruped tabletop anchoring (pelvis height, torso pitch, head gaze)
 *  - Constant extremity geometry (hands forward, feet dorsiflexed)
 *  - IK baking via BasePose.bakeIkLimb() (replaces manual solveIK + rotAround)
 *  - Shared camera + environment metadata
 */
abstract class BaseBirdDogPose : BasePose() {

    // --- Quadruped tabletop anchoring (identical for every Bird Dog variant) ---
    protected val basePelvisX = -30f
    protected val basePelvisY = 127f // thigh (112) + knee floor resting height (15)
    protected val torsoPitch = -PI.toFloat() / 2f // spine rotated flat to the floor
    protected val inverseTorsoPitch = -torsoPitch // counter-rotation for bakeIkLimb

    // Gaze forward (+X) and slightly up, matching the prone quadruped posture.
    protected val birdDogHeadDirection = Vector3(0.3f, 1f, 0f).normalize()

    // --- Shared metadata (removed duplicated literals across variants) ---
    protected val birdDogCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.43f // ~10% closer than the legacy 1.3 for clearer movement reading
    )
    protected val birdDogEnvironment = EnvironmentDefinition(
        ground = GroundDefinition(visible = true, level = 0f)
    )

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult()
    protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()

    // Reusable scratch targets / poles to avoid any per-frame allocations.
    protected val targetA = Vector3(); protected val targetP = Vector3()
    protected val targetF = Vector3(); protected val targetB = Vector3()
    protected val poleA = Vector3(); protected val poleP = Vector3()
    protected val poleF = Vector3(); protected val poleB = Vector3()

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis
        chest = nodes.chest
        neck = nodes.neck
        head = nodes.head
        shoulderA = nodes.shoulderA
        elbowA = nodes.elbowA
        handA = nodes.handA
        palmA = nodes.palmA
        knucklesA = nodes.knucklesA
        fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP
        elbowP = nodes.elbowP
        handP = nodes.handP
        palmP = nodes.palmP
        knucklesP = nodes.knucklesP
        fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF
        kneeF = nodes.kneeF
        ankleF = nodes.ankleF
        heelF = nodes.heelF
        toeF = nodes.toeF
        hipB = nodes.hipB
        kneeB = nodes.kneeB
        ankleB = nodes.ankleB
        heelB = nodes.heelB
        toeB = nodes.toeB
    }

    /**
     * Anchors the quadruped tabletop: pelvis height + torso pitch, spine, head gaze,
     * hips and shoulders. Refreshes world transforms so limb IK roots are current.
     */
    protected fun anchorTabletop(def: SkeletonDefinition) {
        pelvis!!.localPosition.set(basePelvisX, basePelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, torsoPitch)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildHead(neck!!, head!!, def.neckLength, birdDogHeadDirection)

        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }
    }

    /**
     * Applies the constant Bird Dog extremity geometry. Hands point forward (global +X)
     * and feet are dorsiflexed so the tops rest on the ground. Uses the engine's standard
     * open-hand offsets (palm/knuckles 6, fingertips 10) and the foot heel/toe ratios.
     */
    protected fun applyBirdDogExtremities(def: SkeletonDefinition) {
        handA!!.localRotation.set(axisZ, -torsoPitch)
        handP!!.localRotation.set(axisZ, -torsoPitch)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        ankleF!!.localRotation.set(axisZ, -torsoPitch)
        ankleB!!.localRotation.set(axisZ, -torsoPitch)
        // Foot ratios are owned by FootDefinition (no duplicated literals).
        heelF!!.localPosition.set(def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(-def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(-def.foot.footLength * def.foot.toeRatio, 0f, 0f)
    }

    protected fun finalizeBirdDogPose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        jointsBuffer.maxIkClampAmount = maxOf(
            legFBuffer.clampAmount, legBBuffer.clampAmount,
            armABuffer.clampAmount, armPBuffer.clampAmount
        )
        return jointsBuffer
    }
}
