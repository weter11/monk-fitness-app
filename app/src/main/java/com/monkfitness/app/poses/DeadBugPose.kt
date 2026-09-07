package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * P12 (§12.6): converted from the legacy world-position-built representation
 * (`solveIK -> setJoint(result) -> fromJointPositions`) to the authored-hierarchy idiom shared
 * by every compliant pose: SkeletonFactory tree, node-local authoring, the registered package
 * bake for limbs, `fromHierarchy` as the final conversion. The supine trunk is now DECLARED
 * (pelvis tilt = the spine's authored world direction) instead of reconstructed from solved
 * joint positions; limbs reach the same world targets through the canonical bake, which also
 * registers the Limb Targets / stamps the bypass left absent.
 */
class DeadBugPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var lumbar: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; lumbar = nodes.lumbar; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB
    }

    private fun smootherStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    override fun build(context: PoseContext): SkeletonPose {
        // Per-frame hygiene: clear last build's §1.1 carriers from this reused singleton buffer.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        // B3 — every production pose declares its posture intent. Shape-driven root, so CUSTOM.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)
        val def = context.definition
        ensureHierarchy(def)

        // 1. Supine Core Positioning — authored as hierarchy state. The spine local offsets are
        // the standard +Y convention; the supine orientation is a declared pelvis tilt of +90°
        // about Z (rotZ(+90°) maps +Y -> -X, placing the chest at x = pelvisX - torsoLength
        // exactly where the legacy world layout put it).
        val pelvisX = 15f
        val pelvisY = 12f
        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), PI.toFloat() / 2f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        neck!!.localPosition.set(0f, def.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)
        hipF!!.localPosition.set(0f, 0f, -def.hipWidth)
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. Continuous Contrallateral Sine Wave Modulation
        val cycle = context.progress * 2f * PI.toFloat()
        val rawSin = sin(cycle)
        val actA = if (rawSin > 0f) smootherStep(0f, 1f, rawSin) else 0f
        val actP = if (rawSin < 0f) smootherStep(0f, 1f, -rawSin) else 0f

        // 3. ARM TARGETS
        val totalArmLen = def.upperArmLength + def.forearmLength
        // Neutral arm: extended straight up (along positive Y-axis)
        val neutralHandA = Vector3(shoulderA!!.worldPosition.x, shoulderA!!.worldPosition.y + totalArmLen, shoulderA!!.worldPosition.z)
        val neutralHandP = Vector3(shoulderP!!.worldPosition.x, shoulderP!!.worldPosition.y + totalArmLen, shoulderP!!.worldPosition.z)

        // Extended arm: lowered backward near the floor (along negative X-axis)
        val extendedHandA = Vector3(shoulderA!!.worldPosition.x - totalArmLen * 0.94f, 15f, shoulderA!!.worldPosition.z)
        val extendedHandP = Vector3(shoulderP!!.worldPosition.x - totalArmLen * 0.94f, 15f, shoulderP!!.worldPosition.z)

        // Interpolate target positions
        val targetHandA = lerp(neutralHandA, extendedHandA, actA)
        val targetHandP = lerp(neutralHandP, extendedHandP, actP)

        // P12 (§12.6): arms declared through the registered authoring bake.
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(1f, 1f, 0f), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(1f, 1f, 0f), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // 4. LEG TARGETS
        val totalLegLen = def.thighLength + def.shinLength
        // Neutral leg: tabletop position (thigh vertical, knee bent, shin horizontal pointing positive X)
        val neutralAnkleF = Vector3(hipF!!.worldPosition.x + def.shinLength * 0.8f, hipF!!.worldPosition.y + def.thighLength * 0.9f, hipF!!.worldPosition.z)
        val neutralAnkleB = Vector3(hipB!!.worldPosition.x + def.shinLength * 0.8f, hipB!!.worldPosition.y + def.thighLength * 0.9f, hipB!!.worldPosition.z)

        // Extended leg: extended forward near the floor (along positive X-axis)
        val extendedAnkleF = Vector3(hipF!!.worldPosition.x + totalLegLen * 0.94f, 15f, hipF!!.worldPosition.z)
        val extendedAnkleB = Vector3(hipB!!.worldPosition.x + totalLegLen * 0.94f, 15f, hipB!!.worldPosition.z)

        // Interpolate target positions (contrallateral: actP controls Left Leg F, actA controls Right Leg B)
        val targetAnkleF = lerp(neutralAnkleF, extendedAnkleF, actP)
        val targetAnkleB = lerp(neutralAnkleB, extendedAnkleB, actA)

        // P12 (§12.6): legs declared through the registered authoring bake.
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(-1f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(-1f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // W1: engine derives heel/toe + palm/hand orientation from the shank/forearm + neutral
        // articulation (the legacy hand-placed toe offsets along +X are superseded by the
        // declared-intent derivation, same as every migrated family — §12.9 quantifies).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
