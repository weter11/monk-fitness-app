package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * P12 (§12.6): converted from the legacy world-position-built representation
 * (`solveIK -> setJoint(result) -> fromJointPositions`) to the authored-hierarchy idiom
 * (SkeletonFactory tree + declared root/lean + registered package bake limbs +
 * `fromHierarchy`). Prone layout = +90° pelvis declaration plus the per-frame lean; the
 * elevation choreography (leg/arm lean sweeps, gaze) is unchanged — only the solve route
 * moved from the bypass into the canonical authoring path. The legacy raw toe writes are
 * superseded by W1 engine derivation (declared-intent path, like every migrated family).
 */
class SupermanPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFIK = SkeletonMath.IKResult()
    private val legBIK = SkeletonMath.IKResult()
    private val armAIK = SkeletonMath.IKResult()
    private val armPIK = SkeletonMath.IKResult()

    private val tempV1 = Vector3()
    private val tempV2 = Vector3()
    private val tempV3 = Vector3()

    private fun ensureHierarchy(definition: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB
    }

    override fun build(context: PoseContext): SkeletonPose {
        // Per-frame hygiene: clear last build's §1.1 carriers from this reused singleton buffer.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        // B3 — every production pose declares its posture intent. Shape-driven root, so CUSTOM.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)
        val progress = context.progress
        val definition = context.definition
        ensureHierarchy(definition)

        // Prone position: progress 0 (resting) to 1 (extended).
        // Authored: spine local +Y maps to the legacy chest direction (-cos(lean), -sin(lean))
        // by declaring the pelvis tilt θ = 90° + chestLean about Z (rotZ(θ)·+Y = (-sinθ, cosθ);
        // at lean=0 the trunk points exactly -X, supine/prone, matching the legacy layout).
        val pelvisPos = tempV1.set(0f, 10f, 0f)
        val chestLean = lerp(0f, -0.2f, progress)
        pelvis!!.localPosition.set(pelvisPos)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), PI.toFloat() / 2f + chestLean)
        chest!!.localPosition.set(0f, definition.torsoLength, 0f)
        neck!!.localPosition.set(0f, definition.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)
        hipF!!.localPosition.set(0f, 0f, -definition.hipWidth)
        hipB!!.localPosition.set(0f, 0f, definition.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -definition.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, definition.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // LEG TARGETS (lift sweep along the authored lean — unchanged choreography).
        val totalLegLen = definition.thighLength + definition.shinLength
        val legLean = lerp(0f, 0.3f, progress)
        val toeF = tempV2.set(hipF!!.worldPosition.x + totalLegLen * cos(legLean), hipF!!.worldPosition.y + totalLegLen * sin(legLean), hipF!!.worldPosition.z)
        val toeB = tempV3.set(hipB!!.worldPosition.x + totalLegLen * cos(legLean), hipB!!.worldPosition.y + totalLegLen * sin(legLean), hipB!!.worldPosition.z)

        // P12 (§12.6): legs declared through the registered authoring bake (the legacy
        // IKConstraint.LegConstraint constant is preserved verbatim).
        bakeIkLimb(hipF!!.worldPosition, toeF, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFIK, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, toeB, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBIK, jointsBuffer)

        // ARM TARGETS (reach sweep behind, unchanged choreography).
        val totalArmLen = definition.upperArmLength + definition.forearmLength
        val armLean = lerp(0.1f, -0.4f, progress)
        val targetHandA = Vector3(shoulderA!!.worldPosition.x - totalArmLen * cos(armLean), shoulderA!!.worldPosition.y - totalArmLen * sin(armLean), shoulderA!!.worldPosition.z)
        val targetHandP = Vector3(shoulderP!!.worldPosition.x - totalArmLen * cos(armLean), shoulderP!!.worldPosition.y - totalArmLen * sin(armLean), shoulderP!!.worldPosition.z)

        // P12 (§12.6): arms declared through the registered authoring bake.
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, definition.upperArmLength, definition.forearmLength, Vector3(0f, 1f, -1f), IKConstraint.ArmConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, definition.upperArmLength, definition.forearmLength, Vector3(0f, 1f, 1f), IKConstraint.ArmConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK, jointsBuffer)

        // Gaze: the legacy raw neck/head world placement (-1, 0.3, 0) direction becomes the
        // declared Head Target (W1/Phase-7 canonical path; the Finalizer resolver owns head).
        // Same synthetic-target form buildGaze uses (BasePose member unavailable on a
        // PoseBuilder-direct pose): declare the gaze intent along the legacy head direction.
        val gazeDir = tempV1.set(-1f, 0.3f, 0f).normalize()
        val nw = neck!!.worldPosition
        SkeletonPose.IntentBuilder(jointsBuffer).headTarget(
            Vector3(nw.x + gazeDir.x * 100f, nw.y + gazeDir.y * 100f, nw.z + gazeDir.z * 100f)
        )

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
